package eu.kanade.tachiyomi.extension.all.epornerimages

import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.source.KeiSource
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.nodes.Document

@Source
abstract class EpornerImages : KeiSource() {
    override val supportsLatest = true

    override suspend fun getPopularManga(page: Int): MangasPage = getSearchMangaList(page, "", FilterList(SortFilter(3)))

    override suspend fun getLatestUpdates(page: Int): MangasPage = getSearchMangaList(page, "", FilterList(SortFilter(0)))

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val url = buildSearchUrl(page, query, filters)
        return parseSearch(client.get(url).asJsoup())
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.host != baseUrl.toHttpUrl().host) return null
        return mangaDetailsParse(client.get(url).asJsoup()).apply { this.url = url.encodedPath }
    }

    override fun getMangaUrl(manga: SManga): String = "$baseUrl${manga.url}"
    override fun getChapterUrl(chapter: SChapter): String = "$baseUrl${chapter.url}"

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val document = client.get(baseUrl + manga.url).asJsoup()
        val updatedManga = mangaDetailsParse(document)
        val updatedChapters = listOf(
            SChapter.create().apply {
                setUrlWithoutDomain(manga.url)
                name = "Gallery"
                chapter_number = 0f
            },
        )
        return SMangaUpdate(updatedManga, updatedChapters)
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val pages = mutableListOf<Page>()
        var document = client.get(baseUrl + chapter.url).asJsoup()
        while (true) {
            document.select("img[data-original], img[data-src], img[src]")
                .mapNotNull { it.attr("data-original").ifBlank { it.attr("data-src") }.ifBlank { it.attr("src") }.toAbsoluteUrl() }
                .filter { it.isImageUrl() }
                .distinct()
                .forEach { pages.add(Page(pages.size, imageUrl = it)) }
            val next = document.selectFirst("a[rel='next'], a.next")?.absUrl("href")
            if (next.isNullOrBlank() || next == document.baseUri()) break
            document = client.get(next.toHttpUrl()).asJsoup()
        }
        return pages
    }

    private fun buildSearchUrl(page: Int, query: String, filters: FilterList): HttpUrl {
        val builder = "$baseUrl/search-photos/".toHttpUrl().newBuilder()
        if (query.isNotBlank()) builder.addQueryParameter("q", query.trim())
        builder.addQueryParameter("sort", filters.sortValue())
        filters.minimumDuration()?.let { builder.addQueryParameter("min_duration", it) }
        filters.maximumDuration()?.let { builder.addQueryParameter("max_duration", it) }
        filters.qualityValue()?.let { builder.addQueryParameter("quality", it) }
        filters.productionValue()?.let { builder.addQueryParameter("production", it) }
        val categories = filters.categoryValues()
        if (categories.first.isNotEmpty()) builder.addQueryParameter("categories", categories.first.joinToString(","))
        if (categories.second.isNotEmpty()) builder.addQueryParameter("exclude_categories", categories.second.joinToString(","))
        if (page > 1) builder.addQueryParameter("page", page.toString())
        return builder.build()
    }

    private fun parseSearch(document: Document): MangasPage {
        val mangas = document.select("div.item a[href], li.thumb a[href], .photo-item a[href], .gallery-item a[href], a[href*='/photo-'], a[href*='/gallery-']")
            .mapNotNull { link ->
                val href = link.absUrl("href").ifBlank { link.attr("href").toAbsoluteUrl() }
                val image = link.selectFirst("img") ?: link.parent()?.selectFirst("img") ?: return@mapNotNull null
                val title = image.attr("alt").trim().ifBlank { link.attr("title").trim() }.ifBlank { link.text().trim() }
                if (href.isBlank() || title.isBlank()) return@mapNotNull null
                SManga.create().apply {
                    setUrlWithoutDomain(href)
                    this.title = title
                    thumbnail_url = image.attr("data-original").ifBlank { image.attr("data-src") }.ifBlank { image.attr("src") }.toAbsoluteUrl()
                    initialized = true
                }
            }
            .distinctBy { it.url }
        val hasNext = document.select("a[rel='next'], a.next, .pagination a")
            .any { it.attr("rel") == "next" || it.text().trim().equals("next", true) }
        return MangasPage(mangas, hasNext)
    }

    private fun mangaDetailsParse(document: Document): SManga = SManga.create().apply {
        title = document.selectFirst("meta[property='og:title']")?.attr("content")?.trim()
            .orEmpty().ifBlank { document.selectFirst("h1, .title")?.text()?.trim().orEmpty() }
        thumbnail_url = document.selectFirst("meta[property='og:image']")?.attr("content")?.toAbsoluteUrl()
        genre = document.select("a[href*='/tag/'], a[href*='/category/']").eachText().distinct().joinToString(", ").ifBlank { null }
        description = document.selectFirst("meta[name='description']")?.attr("content")?.trim()
            ?: document.selectFirst(".description, .gallery-description")?.text()?.trim()
        status = SManga.COMPLETED
        initialized = true
    }

    private fun FilterList.sortValue(): String = SORT_VALUES.getOrElse(filterIsInstance<SortFilter>().firstOrNull()?.state ?: 0) { "latest" }
    private fun FilterList.minimumDuration(): String? = filterIsInstance<MinimumDurationFilter>().firstOrNull()?.state?.trim()?.takeIf { it.isNotBlank() }
    private fun FilterList.maximumDuration(): String? = filterIsInstance<MaximumDurationFilter>().firstOrNull()?.state?.trim()?.takeIf { it.isNotBlank() }
    private fun FilterList.qualityValue(): String? = QUALITY_VALUES.getOrNull(filterIsInstance<QualityFilter>().firstOrNull()?.state ?: 0)?.takeIf { it.isNotBlank() }
    private fun FilterList.productionValue(): String? = PRODUCTION_VALUES.getOrNull(filterIsInstance<ProductionFilter>().firstOrNull()?.state ?: 0)?.takeIf { it.isNotBlank() }

    private fun FilterList.categoryValues(): Pair<List<String>, List<String>> {
        val categories = filterIsInstance<CategoryFilter>().firstOrNull()?.state.orEmpty()
        val include = categories.filter { it.state == Filter.TriState.STATE_INCLUDE }.map { it.name.toToken() }.take(5)
        val exclude = categories.filter { it.state == Filter.TriState.STATE_EXCLUDE }.map { it.name.toToken() }.take(10)
        return include to exclude
    }

    private class SortFilter(default: Int = 0) : Filter.Select<String>("Sort by", SORT_LABELS, default)
    private class QualityFilter : Filter.Select<String>("Quality", QUALITY_LABELS)
    private class ProductionFilter : Filter.Select<String>("Production", PRODUCTION_LABELS)
    private class MinimumDurationFilter : Filter.Text("Minimum duration (minutes)")
    private class MaximumDurationFilter : Filter.Text("Maximum duration (minutes)")
    private class CategoryFilter : Filter.Group<Filter.TriState>(CATEGORY_LABELS.map { Filter.TriState(it) })

    override fun getFilterList() = FilterList(
        Filter.Header("All Eporner photo search controls"),
        SortFilter(),
        QualityFilter(),
        ProductionFilter(),
        MinimumDurationFilter(),
        MaximumDurationFilter(),
        CategoryFilter(),
        Filter.Separator(),
        Filter.Header("Categories: select Include, Any, or Exclude. Site limits: 5 included and 10 excluded."),
    )

    private fun String.toToken() = lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
    private fun String.toAbsoluteUrl() = when {
        isBlank() -> this
        startsWith("http://") || startsWith("https://") -> this
        startsWith("//") -> "https:$this"
        startsWith("/") -> baseUrl + this
        else -> "$baseUrl/$this"
    }
    private fun String.isImageUrl() = startsWith("http") && Regex("(?i)\\.(?:jpe?g|png|gif|webp)(?:[?#].*)?$").containsMatchIn(this)

    private companion object {
        val SORT_LABELS = arrayOf("Most recent", "Weekly Top", "Monthly Top", "Most viewed", "Top rated", "Longest", "Shortest")
        val SORT_VALUES = arrayOf("latest", "top-weekly", "top-monthly", "most-viewed", "top-rated", "longest", "shortest")
        val QUALITY_LABELS = arrayOf("All", "720p+", "1080p+", "4K")
        val QUALITY_VALUES = arrayOf("", "720p", "1080p", "4k")
        val PRODUCTION_LABELS = arrayOf("All", "Professional", "Homemade")
        val PRODUCTION_VALUES = arrayOf("", "professional", "homemade")
        val CATEGORY_LABELS = arrayOf(
            "4K Ultra HD", "60 FPS", "AI", "Amateur", "Anal", "Asian", "ASMR", "BBW", "BDSM", "Big Ass", "Big Dick", "Big Tits", "Bisexual", "Blonde", "Blowjob", "Bondage", "Brunette", "Bukkake", "Casting", "Compilation", "Cosplay", "Creampie", "Cuckold", "Cumshot", "Double Penetration", "Ebony", "Fat", "Fetish", "Fisting", "Footjob", "For Women", "Gay", "Gloryhole", "Group Sex", "Handjob", "Hardcore", "HD Porn 1080p", "HD Sex", "Hentai", "Homemade", "Hotel", "Hotwife", "Housewives", "HQ Porn", "Indian", "Indonesia", "Interracial", "Japanese", "Latina", "Lesbian", "Lingerie", "Massage", "Masturbation", "Mature", "MILF", "Nurses", "Office", "Older Men", "Orgy", "Outdoor", "PAWG", "Petite", "Pinay", "Pornstar", "POV", "Pregnant", "Public", "Redhead", "Shemale", "Sleep", "Small Tits", "Squirt", "Stepmom", "Stepsister", "Striptease", "Students", "Swinger", "Teen", "Threesome", "Toys", "Uncategorized", "Uniform", "Vintage", "VR Porn", "Webcam",
        )
    }
}
