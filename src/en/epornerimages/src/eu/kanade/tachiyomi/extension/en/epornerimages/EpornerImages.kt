package eu.kanade.tachiyomi.extension.en.epornerimages

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.annotation.Source
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import rx.Observable

@Source
abstract class EpornerImages : HttpSource() {
    override val name = "Eporner Images"
    override val baseUrl = "https://www.eporner.com"
    override val supportsLatest = true

    override fun headersBuilder() = super.headersBuilder()
        .add("User-Agent", USER_AGENT)
        .add("Referer", "$baseUrl/")
        .add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")

    override fun popularMangaRequest(page: Int): Request = searchRequest(page, "", FilterList(SortFilter(3)))
    override fun popularMangaParse(response: Response): MangasPage = parseSearch(response.asJsoup())
    override fun latestUpdatesRequest(page: Int): Request = searchRequest(page, "", FilterList(SortFilter(0)))
    override fun latestUpdatesParse(response: Response): MangasPage = parseSearch(response.asJsoup())
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request = searchRequest(page, query, filters)
    override fun searchMangaParse(response: Response): MangasPage = parseSearch(response.asJsoup())

    override fun mangaDetailsRequest(manga: SManga): Request = GET(getMangaUrl(manga), headers)
    override fun mangaDetailsParse(response: Response): SManga = parseDetails(response.asJsoup())

    override fun chapterListRequest(manga: SManga): Request = GET(getMangaUrl(manga), headers)
    override fun chapterListParse(response: Response): List<SChapter> = listOf(
        SChapter.create().apply {
            setUrlWithoutDomain(response.request.url.toString())
            name = "Gallery"
            chapter_number = 1f
        },
    )

    override fun pageListRequest(chapter: SChapter): Request = GET(getChapterUrl(chapter), headers)
    override fun pageListParse(response: Response): List<Page> {
        val document = response.asJsoup()
        val links = document.select("a[href*='/photo-'], a[href*='/gallery-']")
            .mapNotNull { it.absUrl("href").takeIf(String::isNotBlank) }
        if (links.isNotEmpty()) return links.distinct().mapIndexed { index, url -> Page(index, url = url) }
        return document.select("img[data-original], img[data-src], img[src]")
            .mapNotNull { it.imageUrl().takeIf(String::isImageUrl) }
            .distinct()
            .mapIndexed { index, url -> Page(index, imageUrl = url) }
    }

    override fun imageUrlParse(response: Response): String {
        val document = response.asJsoup()
        return listOf(
            document.selectFirst("meta[property='og:image']")?.attr("content"),
            document.selectFirst("#image, .photo img, img[data-original], img[data-src], img[src]")?.imageUrl(),
        ).mapNotNull { it?.toAbsoluteUrl() }.firstOrNull(String::isImageUrl)
            ?: error("Eporner did not return an original image URL")
    }

    override fun imageRequest(page: Page): Request = GET(
        page.imageUrl!!.toHttpUrl(),
        headersBuilder().set("Referer", page.url).build(),
    )

    override fun fetchPopularManga(page: Int): Observable<MangasPage> = super.fetchPopularManga(page)
    override fun fetchLatestUpdates(page: Int): Observable<MangasPage> = super.fetchLatestUpdates(page)
    override fun fetchSearchManga(page: Int, query: String, filters: FilterList): Observable<MangasPage> = super.fetchSearchManga(page, query, filters)
    override fun fetchMangaDetails(manga: SManga): Observable<SManga> = super.fetchMangaDetails(manga)
    override fun fetchChapterList(manga: SManga): Observable<List<SChapter>> = super.fetchChapterList(manga)
    override fun fetchPageList(chapter: SChapter): Observable<List<Page>> = super.fetchPageList(chapter)

    override fun getMangaUrl(manga: SManga): String = "$baseUrl${manga.url}"
    override fun getChapterUrl(chapter: SChapter): String = "$baseUrl${chapter.url}"

    override fun getFilterList() = FilterList(
        Filter.Header("All Eporner photo search controls"),
        SortFilter(),
        QualityFilter(),
        ProductionFilter(),
        MinimumDurationFilter(),
        MaximumDurationFilter(),
        CategoryFilter(),
        Filter.Separator(),
        Filter.Header("Categories: select Include, Any, or Exclude. Site limits apply."),
    )

    private fun searchRequest(page: Int, query: String, filters: FilterList): Request {
        val builder = "$baseUrl/search-photos/".toHttpUrl().newBuilder()
        if (query.isNotBlank()) builder.addQueryParameter("q", query.trim())
        builder.addQueryParameter("sort", filters.sortValue())
        filters.qualityValue()?.let { builder.addQueryParameter("quality", it) }
        filters.productionValue()?.let { builder.addQueryParameter("production", it) }
        filters.minimumDuration()?.let { builder.addQueryParameter("min_duration", it) }
        filters.maximumDuration()?.let { builder.addQueryParameter("max_duration", it) }
        val categories = filters.categoryValues()
        if (categories.first.isNotEmpty()) builder.addQueryParameter("categories", categories.first.joinToString(","))
        if (categories.second.isNotEmpty()) builder.addQueryParameter("exclude_categories", categories.second.joinToString(","))
        if (page > 1) builder.addQueryParameter("page", page.toString())
        return GET(builder.build(), headers)
    }

    private fun parseSearch(document: Document): MangasPage {
        val mangas = document.select("div.item a[href], li.thumb a[href], .photo-item a[href], .gallery-item a[href], a[href*='/photo-'], a[href*='/gallery-']")
            .mapNotNull { link ->
                val image = link.selectFirst("img") ?: link.parent()?.selectFirst("img") ?: return@mapNotNull null
                val href = link.absUrl("href").ifBlank { link.attr("href").toAbsoluteUrl() }
                val title = image.attr("alt").trim().ifBlank { link.attr("title").trim() }.ifBlank { link.text().trim() }
                if (href.isBlank() || title.isBlank()) return@mapNotNull null
                SManga.create().apply {
                    setUrlWithoutDomain(href)
                    this.title = title
                    thumbnail_url = image.imageUrl()
                    initialized = true
                }
            }
            .distinctBy { it.url }
        val hasNext = document.select("a[rel='next'], a.next, .pagination a")
            .any { it.attr("rel") == "next" || it.text().trim().equals("next", true) }
        return MangasPage(mangas, hasNext)
    }

    private fun parseDetails(document: Document): SManga = SManga.create().apply {
        title = document.selectFirst("meta[property='og:title']")?.attr("content")?.trim().orEmpty()
            .ifBlank { document.selectFirst("h1, .title")?.text()?.trim().orEmpty() }
        thumbnail_url = document.selectFirst("meta[property='og:image']")?.attr("content")?.toAbsoluteUrl()
        genre = document.select("a[href*='/tag/'], a[href*='/category/']").eachText().distinct().joinToString(", ").ifBlank { null }
        description = document.selectFirst("meta[name='description']")?.attr("content")?.trim()
            ?: document.selectFirst(".description, .gallery-description")?.text()?.trim()
        status = SManga.COMPLETED
        initialized = true
    }

    private fun FilterList.sortValue(): String = SORT_VALUES.getOrElse(filterIsInstance<SortFilter>().firstOrNull()?.state ?: 0) { "latest" }
    private fun FilterList.qualityValue(): String? = QUALITY_VALUES.getOrNull(filterIsInstance<QualityFilter>().firstOrNull()?.state ?: 0)?.takeIf(String::isNotBlank)
    private fun FilterList.productionValue(): String? = PRODUCTION_VALUES.getOrNull(filterIsInstance<ProductionFilter>().firstOrNull()?.state ?: 0)?.takeIf(String::isNotBlank)
    private fun FilterList.minimumDuration(): String? = filterIsInstance<MinimumDurationFilter>().firstOrNull()?.state?.trim()?.takeIf(String::isNotBlank)
    private fun FilterList.maximumDuration(): String? = filterIsInstance<MaximumDurationFilter>().firstOrNull()?.state?.trim()?.takeIf(String::isNotBlank)

    private fun FilterList.categoryValues(): Pair<List<String>, List<String>> {
        val selected = filterIsInstance<CategoryFilter>().firstOrNull()?.state.orEmpty()
        val included = selected.filter { it.state == Filter.TriState.STATE_INCLUDE }.map { it.name.toToken() }.take(5)
        val excluded = selected.filter { it.state == Filter.TriState.STATE_EXCLUDE }.map { it.name.toToken() }.take(10)
        return included to excluded
    }

    private class SortFilter(default: Int = 0) : Filter.Select<String>("Sort by", SORT_LABELS, default)
    private class QualityFilter : Filter.Select<String>("Quality", QUALITY_LABELS)
    private class ProductionFilter : Filter.Select<String>("Production", PRODUCTION_LABELS)
    private class MinimumDurationFilter : Filter.Text("Minimum duration (minutes)")
    private class MaximumDurationFilter : Filter.Text("Maximum duration (minutes)")
    private class CategoryTriState(name: String) : Filter.TriState(name)
    private class CategoryFilter : Filter.Group<Filter.TriState>("Categories", CATEGORY_LABELS.map { CategoryTriState(it) })

    private fun org.jsoup.nodes.Element.imageUrl(): String = attr("data-original").ifBlank { attr("data-src") }.ifBlank { attr("src") }.toAbsoluteUrl()
    private fun String.toToken(): String = lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
    private fun String.toAbsoluteUrl(): String = when {
        isBlank() -> this
        startsWith("http://") || startsWith("https://") -> this
        startsWith("//") -> "https:$this"
        startsWith("/") -> baseUrl + this
        else -> "$baseUrl/$this"
    }
    private fun String.isImageUrl() = startsWith("http") && Regex("(?i)\\.(?:jpe?g|png|gif|webp)(?:[?#].*)?$").containsMatchIn(this)

    private companion object {
        const val USER_AGENT = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/131 Mobile Safari/537.36"
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
