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
    override val name = "Eporner Pics"
    override val baseUrl = "https://www.eporner.com"
    override val supportsLatest = true

    override fun headersBuilder() = super.headersBuilder()
        .add("User-Agent", USER_AGENT)
        .add("Referer", "$baseUrl/")
        .add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")

    override fun popularMangaRequest(page: Int): Request = picsRequest(page, "most-viewed")
    override fun popularMangaParse(response: Response): MangasPage = parseSearch(response.asJsoup())
    override fun latestUpdatesRequest(page: Int): Request = picsRequest(page, "latest")
    override fun latestUpdatesParse(response: Response): MangasPage = parseSearch(response.asJsoup())
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request = searchRequest(page, query, filters)
    override fun searchMangaParse(response: Response): MangasPage = parseSearch(response.asJsoup())

    override fun mangaDetailsRequest(manga: SManga): Request = GET(getMangaUrl(manga), headers)
    override fun mangaDetailsParse(response: Response): SManga = parseDetails(response.asJsoup())

    override fun chapterListRequest(manga: SManga): Request = GET(getMangaUrl(manga), headers)
    override fun chapterListParse(response: Response): List<SChapter> = listOf(
        SChapter.create().apply {
            setUrlWithoutDomain(response.request.url.toString())
            name = "Pics"
            chapter_number = 1f
        },
    )

    override fun pageListRequest(chapter: SChapter): Request = GET(getChapterUrl(chapter), headers)
    override fun pageListParse(response: Response): List<Page> {
        val document = response.asJsoup()
        val photoPages = document.select("a[href*='/photo/']")
            .mapNotNull { link ->
                val image = link.selectFirst("img")
                    ?: link.parent()?.selectFirst("img")
                    ?: link.parent()?.parent()?.selectFirst("img")
                val imageUrl = image?.imageUrl()
                link.absUrl("href")
                    .takeIf(String::isNotBlank)
                    ?.takeIf { !it.contains("/gifs/", true) && !it.contains("/porn-gifs/", true) }
                    ?.takeIf { !link.text().contains("gif", true) }
                    ?.takeIf { imageUrl == null || imageUrl.isStaticImageUrl() }
            }
            .distinct()
        if (photoPages.isNotEmpty()) return photoPages.mapIndexed { index, url -> Page(index, url = url) }
        return listOf(Page(0, imageUrl = document.photoImageUrl()))
    }

    override fun imageUrlParse(response: Response): String = response.asJsoup().photoImageUrl()

    override fun imageRequest(page: Page): Request {
        val pageUrl = page.url
        return if (pageUrl.isNullOrBlank()) {
            GET(page.imageUrl!!.toHttpUrl(), headers)
        } else {
            GET(pageUrl.toHttpUrl(), headersBuilder().set("Referer", pageUrl).build())
        }
    }

    override fun fetchPopularManga(page: Int): Observable<MangasPage> = super.fetchPopularManga(page)
    override fun fetchLatestUpdates(page: Int): Observable<MangasPage> = super.fetchLatestUpdates(page)
    override fun fetchSearchManga(page: Int, query: String, filters: FilterList): Observable<MangasPage> = super.fetchSearchManga(page, query, filters)
    override fun fetchMangaDetails(manga: SManga): Observable<SManga> = super.fetchMangaDetails(manga)
    override fun fetchChapterList(manga: SManga): Observable<List<SChapter>> = super.fetchChapterList(manga)
    override fun fetchPageList(chapter: SChapter): Observable<List<Page>> = super.fetchPageList(chapter)

    override fun getMangaUrl(manga: SManga): String = "$baseUrl${manga.url}"
    override fun getChapterUrl(chapter: SChapter): String = "$baseUrl${chapter.url}"

    override fun getFilterList() = FilterList(
        Filter.Header("Eporner Pics sections"),
        SectionFilter(),
        SortFilter(),
        Filter.Separator(),
        Filter.Header("Static images only: GIFs are excluded"),
    )

    private fun picsRequest(page: Int, sort: String): Request {
        val builder = baseUrl.toHttpUrl().newBuilder().addPathSegment("pics")
        if (sort != "latest") builder.addQueryParameter("sort", sort)
        if (page > 1) builder.addPathSegment(page.toString())
        return GET(builder.build(), headers)
    }

    private fun searchRequest(page: Int, query: String, filters: FilterList): Request {
        val section = filters.sectionValue()
        val sort = when (section) {
            POPULAR -> "most-viewed"
            TOP_RATED -> "top-rated"
            else -> filters.sortValue()
        }
        val builder = baseUrl.toHttpUrl().newBuilder()
        when {
            query.isNotBlank() -> {
                builder.addPathSegment("search-photos")
                builder.addPathSegment(query.toSearchSlug())
            }
            section == COLLECTIONS -> builder.addPathSegment("best-collections")
            else -> builder.addPathSegment("pics")
        }
        if (sort != "latest") builder.addQueryParameter("sort", sort)
        if (page > 1) builder.addPathSegment(page.toString())
        return GET(builder.build(), headers)
    }

    private fun parseSearch(document: Document): MangasPage {
        val mangas = document.select("a[href*='/photo/'], a[href*='/gallery/']")
            .mapNotNull { link ->
                val href = link.absUrl("href").ifBlank { link.attr("href").toAbsoluteUrl() }
                if (href.contains("/gifs/", true) || href.contains("/porn-gifs/", true)) return@mapNotNull null
                val image = link.selectFirst("img")
                    ?: link.parent()?.selectFirst("img")
                    ?: link.parent()?.parent()?.selectFirst("img")
                val thumbnail = image?.imageUrl()?.takeIf { it.isStaticImageUrl() }
                val title = image?.attr("alt")?.trim().orEmpty()
                    .ifBlank { link.attr("title").trim() }
                    .ifBlank { link.text().trim().removePrefix("amateur photo").trim() }
                if (title.contains("gif", true) || href.isBlank() || title.isBlank() || thumbnail.isNullOrBlank()) return@mapNotNull null
                SManga.create().apply {
                    setUrlWithoutDomain(href)
                    this.title = title
                    thumbnail_url = thumbnail
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
        thumbnail_url = document.selectFirst("meta[property='og:image']")?.attr("content")
            ?.toAbsoluteUrl()?.takeIf { it.isStaticImageUrl() }
        genre = document.select("a[href*='/tag/'], a[href*='/category/']").eachText().distinct().joinToString(", ").ifBlank { null }
        description = document.selectFirst("meta[name='description']")?.attr("content")?.trim()
            ?: document.selectFirst(".description, .gallery-description")?.text()?.trim()
        status = SManga.COMPLETED
        initialized = true
    }

    private fun FilterList.sectionValue(): Int = filterIsInstance<SectionFilter>().firstOrNull()?.state ?: 0
    private fun FilterList.sortValue(): String = SORT_VALUES.getOrElse(filterIsInstance<SortFilter>().firstOrNull()?.state ?: 0) { "latest" }

    private class SectionFilter : Filter.Select<String>("Pics section", SECTION_LABELS)
    private class SortFilter : Filter.Select<String>("Sort by", SORT_LABELS)

    private fun Document.photoImageUrl(): String = listOf(
        selectFirst("meta[property='og:image']")?.attr("content"),
        selectFirst("#image, .photo img, img[data-original], img[data-src], img[src]")?.imageUrl(),
    ).mapNotNull { it?.toAbsoluteUrl() }.firstOrNull { it.isStaticImageUrl() }
        ?: error("Eporner did not return a static original image URL")

    private fun org.jsoup.nodes.Element.imageUrl(): String = attr("data-original")
        .ifBlank { attr("data-src") }
        .ifBlank { attr("src") }
        .toAbsoluteUrl()

    private fun String.toSearchSlug(): String = trim()
        .replace(Regex("\\s+"), "-")
        .replace(Regex("[^\\p{L}\\p{N}-]+"), "-")
        .replace(Regex("-{2,}"), "-")
        .trim('-')

    private fun String.toAbsoluteUrl(): String = when {
        isBlank() -> this
        startsWith("http://") || startsWith("https://") -> this
        startsWith("//") -> "https:$this"
        startsWith("/") -> baseUrl + this
        else -> "$baseUrl/$this"
    }

    private fun String.isStaticImageUrl() = startsWith("http") &&
        Regex("(?i)\\.(?:jpe?g|png|webp)(?:[?#].*)?$").containsMatchIn(this)

    private companion object {
        const val USER_AGENT = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/131 Mobile Safari/537.36"
        const val POPULAR = 1
        const val TOP_RATED = 2
        const val COLLECTIONS = 3
        val SECTION_LABELS = arrayOf("All Pics", "Popular", "Top Rated", "Best Collections")
        val SORT_LABELS = arrayOf("Most recent", "Most viewed", "Top rated")
        val SORT_VALUES = arrayOf("latest", "most-viewed", "top-rated")
    }
}
