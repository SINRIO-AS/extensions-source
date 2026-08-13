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
            if (filters.firstInstanceOrNull<SearchDescriptionFilter>()?.state == true) {
                addQueryParameter("f_sdesc", "on")
            }
            if (filters.firstInstanceOrNull<SearchTorrentNamesFilter>()?.state == true) {
                addQueryParameter("f_storr", "on")
            }
            if (filters.firstInstanceOrNull<OnlyTorrentsFilter>()?.state == true) {
                addQueryParameter("f_sto", "on")
            }
            if (filters.firstInstanceOrNull<ShowExpungedFilter>()?.state == true) {
                addQueryParameter("f_sh", "on")
            }
            if (filters.firstInstanceOrNull<LowPowerTagsFilter>()?.state == true) {
                addQueryParameter("f_sdt1", "on")
            }
            if (filters.firstInstanceOrNull<DownvotedTagsFilter>()?.state == true) {
                addQueryParameter("f_sdt2", "on")
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
            val galleryLink = row.selectFirst(".gl3c.glname > a[href], .glname > a[href]")
                ?: return@mapNotNull null
            val title = galleryLink.text().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val galleryUrl = galleryLink.absUrl("href").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val cover = row.selectFirst(".glthumb img, .gl2c img")
            val coverUrl = cover?.attr("data-src")?.takeIf { it.isNotEmpty() }
                ?: cover?.absUrl("src")?.takeUnless { it.startsWith("data:") }

            SManga.create().apply {
                setUrlWithoutDomain(galleryUrl)
                this.title = title
                thumbnail_url = coverUrl
                genre = row.select(".gt[title]")
                    .map { it.attr("title") }
                    .takeIf { it.isNotEmpty() }
                    ?.joinToString()
            }
        }

        val hasNextPage = document.select("a[href]").any { anchor ->
            anchor.id() == "dnext" || anchor.text().startsWith("Next", ignoreCase = true)
        }
        return MangasPage(mangas, hasNextPage)
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.host != baseUrl.toHttpUrl().host || !url.encodedPath.startsWith("/g/")) return null

        val manga = SManga.create().apply {
            setUrlWithoutDomain(url.toString())
        }
        return fetchMangaUpdate(manga, emptyList(), fetchDetails = true, fetchChapters = true).manga.apply {
            initialized = true
        }
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val document = client.get(getMangaUrl(manga)).asJsoup()
        val details = parseMangaDetails(document)
        val chapterList = if (fetchChapters) listOf(parseGalleryChapter(details, document)) else chapters
        return SMangaUpdate(details, chapterList)
    }

    private fun parseMangaDetails(document: Document): SManga = SManga.create().apply {
        setUrlWithoutDomain(document.location())
        title = document.selectFirst("#gn")?.text()?.takeIf { it.isNotEmpty() } ?: "E-Hentai Gallery"
        thumbnail_url = document.selectFirst("#gd1 > div[style], #gd1 [style]")
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
        name = document.selectFirst("#gn")?.text()?.takeIf { it.isNotEmpty() } ?: manga.title
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

        return imagePages.distinct().mapIndexed { index, pageUrl -> Page(index, url = pageUrl) }
    }

    private fun Document.imagePageUrls(): List<String> =
        select("#gdt a[href]").mapNotNull { it.absUrl("href").takeIf { url -> "/s/" in url } }

    override suspend fun getImageUrl(page: Page): String {
        val document = client.get(page.url).asJsoup()
        return document.selectFirst("#img")?.absUrl("src")?.takeIf { it.isNotEmpty() }
            ?: document.selectFirst("a[href*='/fullimg/']")?.absUrl("href").orEmpty()
    }

    override fun getFilterList(data: kotlinx.serialization.json.JsonElement?): FilterList = FilterList(
        Filter.Header("E-Hentai public search filters"),
        CategoryFilter(),
        Filter.Separator(),
        SearchTitlesFilter(),
        SearchTagsFilter(),
        SearchDescriptionFilter(),
        SearchTorrentNamesFilter(),
        OnlyTorrentsFilter(),
        ShowExpungedFilter(),
        LowPowerTagsFilter(),
        DownvotedTagsFilter(),
        MinimumRatingFilter(),
        MinimumPagesFilter(),
        MaximumPagesFilter(),
    )

    private companion object {
        val coverUrlRegex = Regex("""url\(['\"]?([^'")]+)""")
    }
}
