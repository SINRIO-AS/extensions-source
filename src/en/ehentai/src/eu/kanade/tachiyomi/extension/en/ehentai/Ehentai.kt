package eu.kanade.tachiyomi.extension.en.ehentai

import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.source.model.UpdateStrategy
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.network.rateLimit
import keiyoushi.source.KeiSource
import keiyoushi.utils.firstInstanceOrNull
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.jsoup.nodes.Document
import kotlin.time.Duration.Companion.seconds

@Source
abstract class Ehentai : KeiSource() {

    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder =
        rateLimit(1, 3.seconds) { it.host == baseUrl.toHttpUrl().host }

    override suspend fun getPopularManga(page: Int): MangasPage =
        getGalleryList(page, "", FilterList())

    override suspend fun getLatestUpdates(page: Int): MangasPage =
        getGalleryList(page, "", FilterList())

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage =
        getGalleryList(page, query, filters)

    private suspend fun getGalleryList(page: Int, query: String, filters: FilterList): MangasPage {
        val categoryFilter = filters.firstInstanceOrNull<CategoryFilter>()
        val minimumRating = filters.firstInstanceOrNull<MinimumRatingFilter>()
        val minimumPages = filters.firstInstanceOrNull<MinimumPagesFilter>()?.state?.pageCountOrNull()
        val maximumPages = filters.firstInstanceOrNull<MaximumPagesFilter>()?.state?.pageCountOrNull()

        require(minimumPages == null || maximumPages == null || minimumPages.toInt() <= maximumPages.toInt()) {
            "Minimum pages cannot exceed maximum pages"
        }

        val url = baseUrl.toHttpUrl().newBuilder().apply {
            addQueryParameter("f_cats", (categoryFilter?.excludedMask() ?: 0).toString())
            query.trim().takeIf { it.isNotEmpty() }?.let { addQueryParameter("f_search", it) }

            if (filters.firstInstanceOrNull<SearchTitlesFilter>()?.state != false) {
                addQueryParameter("f_sname", "on")
            }
            if (filters.firstInstanceOrNull<SearchTagsFilter>()?.state != false) {
                addQueryParameter("f_stags", "on")
            }
            minimumRating?.value()?.let {
                addQueryParameter("f_sr", "on")
                addQueryParameter("f_srdd", it)
            }
            minimumPages?.let { addQueryParameter("f_spf", it) }
            maximumPages?.let { addQueryParameter("f_spt", it) }
            if (page > 1) addQueryParameter("page", (page - 1).toString())
        }.build()

        return parseMangaList(client.get(url).asJsoup())
    }

    private fun parseMangaList(document: Document): MangasPage {
        val mangas = document.select("table.itg.gltc > tbody > tr, table.itg.gltc > tr").mapNotNull { row ->
            val galleryLink = row.selectFirst(".gl3c.glname > a[href]") ?: return@mapNotNull null
            val title = galleryLink.text().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val galleryUrl = galleryLink.absUrl("href").takeIf { it.isNotEmpty() } ?: return@mapNotNull null

            SManga.create().apply {
                setUrlWithoutDomain(galleryUrl)
                this.title = title
                thumbnail_url = row.selectFirst(".gl2c img")?.absUrl("src")
                genre = row.select(".gl3c .gt[title]")
                    .map { it.attr("title") }
                    .takeIf { it.isNotEmpty() }
                    ?.joinToString()
            }
        }

        val hasNextPage = document.select("a[href]").any { anchor ->
            anchor.text().startsWith("Next", ignoreCase = true)
        }
        return MangasPage(mangas, hasNextPage)
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.host != baseUrl.toHttpUrl().host || !url.encodedPath.startsWith("/g/")) return null
        return parseMangaDetails(client.get(url).asJsoup())
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val document = client.get(getMangaUrl(manga)).asJsoup()
        return SMangaUpdate(parseMangaDetails(document), listOf(parseGalleryChapter(manga, document)))
    }

    private fun parseMangaDetails(document: Document): SManga = SManga.create().apply {
        setUrlWithoutDomain(document.location())
        title = document.selectFirst("#gn")!!.text()
        thumbnail_url = document.selectFirst("#gd1")
            ?.attr("style")
            ?.let { coverUrlRegex.find(it)?.groupValues?.getOrNull(1) }

        val tagTitles = document.select("#taglist .gt[title]").map { it.attr("title") }
        genre = buildList {
            document.selectFirst("#gdc")?.text()?.takeIf { it.isNotEmpty() }?.let(::add)
            addAll(tagTitles)
        }.takeIf { it.isNotEmpty() }?.joinToString()
        author = tagTitles.filter { it.startsWith("artist:") || it.startsWith("group:") }
            .map { it.substringAfter(':') }
            .takeIf { it.isNotEmpty() }
            ?.joinToString()
        artist = tagTitles.filter { it.startsWith("artist:") }
            .map { it.substringAfter(':') }
            .takeIf { it.isNotEmpty() }
            ?.joinToString()
        status = SManga.COMPLETED
        update_strategy = UpdateStrategy.ONLY_FETCH_ONCE

        description = buildString {
            document.selectFirst("#gj")?.text()?.takeIf { it.isNotEmpty() }?.let {
                appendLine(it)
                appendLine()
            }
            document.select("#gdd tr").forEach { row ->
                val label = row.selectFirst(".gdt1")?.text()
                val value = row.selectFirst(".gdt2")?.text()
                if (!label.isNullOrEmpty() && !value.isNullOrEmpty()) {
                    appendLine("$label $value")
                }
            }
        }.trim().takeIf { it.isNotEmpty() }
    }

    private fun parseGalleryChapter(manga: SManga, document: Document): SChapter = SChapter.create().apply {
        url = manga.url
        name = document.selectFirst("#gn")!!.text()
        chapter_number = 1F
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val chapterUrl = getChapterUrl(chapter)
        val firstDocument = client.get(chapterUrl).asJsoup()
        val imagePages = firstDocument.imagePageUrls().toMutableList()
        val lastGridPage = firstDocument.select(".gtb a[href*='?p=']")
            .maxOfOrNull { it.absUrl("href").toHttpUrl().queryParameter("p")?.toIntOrNull() ?: 0 }
            ?: 0

        for (gridPage in 1..lastGridPage) {
            val gridUrl = chapterUrl.toHttpUrl().newBuilder()
                .addQueryParameter("p", gridPage.toString())
                .build()
            imagePages += client.get(gridUrl).asJsoup().imagePageUrls()
        }

        return imagePages.mapIndexed { index, pageUrl -> Page(index, url = pageUrl) }
    }

    private fun Document.imagePageUrls(): List<String> =
        select("#gdt a[href]").mapNotNull { it.absUrl("href").takeIf(String::isNotEmpty) }

    override suspend fun getImageUrl(page: Page): String {
        val document = client.get(page.url).asJsoup()
        return document.selectFirst("a[href*='/fullimg/']")?.absUrl("href")
            ?.takeIf { it.isNotEmpty() }
            ?: document.selectFirst("#img")?.absUrl("src").orEmpty()
    }

    override fun getFilterList(data: kotlinx.serialization.json.JsonElement?): FilterList = FilterList(
        Filter.Header("Filters use E-Hentai's public search options."),
        CategoryFilter(),
        Filter.Separator(),
        SearchTitlesFilter(),
        SearchTagsFilter(),
        MinimumRatingFilter(),
        MinimumPagesFilter(),
        MaximumPagesFilter(),
    )

    private companion object {
        val coverUrlRegex = Regex("""url\(['\"]?([^'")]+)""")
    }
}
