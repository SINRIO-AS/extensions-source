package eu.kanade.tachiyomi.extension.en.ehentai

import android.content.SharedPreferences
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.source.model.UpdateStrategy
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.network.rateLimit
import keiyoushi.source.KeiSource
import keiyoushi.utils.firstInstanceOrNull
import keiyoushi.utils.getPreferencesLazy
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.nodes.Document
import java.util.LinkedHashMap
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.seconds

@Source
abstract class Ehentai : KeiSource(), ConfigurableSource {

    private val preferences: SharedPreferences by getPreferencesLazy()
    private val documentCache = object : LinkedHashMap<String, Document>(DOCUMENT_CACHE_SIZE, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Document>?): Boolean =
            size > DOCUMENT_CACHE_SIZE
    }
    private val resultHistory = object : LinkedHashMap<String, MutableSet<String>>(RESULT_HISTORY_SIZE, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, MutableSet<String>>?): Boolean =
            size > RESULT_HISTORY_SIZE
    }
    private val imageUrlCache = object : LinkedHashMap<String, String>(IMAGE_URL_CACHE_SIZE, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean =
            size > IMAGE_URL_CACHE_SIZE
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) = setupEhentaiPreferenceScreen(screen)

    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder {
        val (burst, interval) = when (preferences.requestRateProfile) {
            "polite" -> 2 to 2.seconds
            "fast" -> 6 to 1.seconds
            else -> 4 to 1.seconds
        }
        return rateLimit(burst, interval) { it.host == baseUrl.toHttpUrl().host }
            .addInterceptor { chain ->
                val request = chain.request()
                val backupUrl = request.url.fragment
                    ?: return@addInterceptor chain.proceed(request)
                val primaryResult = runCatching { chain.proceed(request) }
                val primaryResponse = primaryResult.getOrNull()
                val primaryIsImage = primaryResponse?.isSuccessful == true &&
                    primaryResponse.body?.contentType()?.type == "image"
                if (primaryIsImage) return@addInterceptor primaryResponse
                primaryResponse?.close()
                if (primaryResult.isFailure) {
                    // Continue with the E-Hentai fallback below when the H@H request fails.
                }
                val backupRequest = GET(backupUrl.toHttpUrl(), headersBuilder().build())
                val backupImageUrl = chain.proceed(backupRequest).use { response ->
                    imageUrlFromDocument(response.asJsoup(), allowBackup = false)
                }
                chain.proceed(request.newBuilder().url(backupImageUrl.toHttpUrl()).build())
            }
    }

    override fun Headers.Builder.configureHeaders() =
        add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")

    override suspend fun getPopularManga(page: Int): MangasPage =
        getGalleryList(page, "", FilterList(), "/popular")

    override suspend fun getLatestUpdates(page: Int): MangasPage =
        getGalleryList(page, "", FilterList(), "/")

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage =
        getGalleryList(page, query, filters, "/")

    private suspend fun getGalleryList(
        page: Int,
        query: String,
        filters: FilterList,
        route: String,
    ): MangasPage {
        val categoryFilter = filters.firstInstanceOrNull<CategoryFilter>()
        val categoryMode = filters.firstInstanceOrNull<CategoryModeFilter>()
        val categoryTags = categoryFilter?.queryTags().orEmpty()
        val hasCategoryTag = categoryFilter?.hasQueryTag() == true
        val language = filters.firstInstanceOrNull<LanguageFilter>()
        val exactTags = filters.firstInstanceOrNull<ExactTagFilter>()?.state == 1
        val includeTags = filters.firstInstanceOrNull<IncludeTagsFilter>()?.state
            ?.searchTerms(exact = exactTags).orEmpty()
        val excludeTags = filters.firstInstanceOrNull<ExcludeTagsFilter>()?.state
            ?.searchTerms(exclude = true, exact = exactTags).orEmpty()
        val namespaceTags = buildList {
            addAll(filters.firstInstanceOrNull<ArtistTagsFilter>()?.state
                ?.searchTerms(exact = exactTags, namespace = "artist").orEmpty())
            addAll(filters.firstInstanceOrNull<GroupTagsFilter>()?.state
                ?.searchTerms(exact = exactTags, namespace = "group").orEmpty())
            addAll(filters.firstInstanceOrNull<ParodyTagsFilter>()?.state
                ?.searchTerms(exact = exactTags, namespace = "parody").orEmpty())
            addAll(filters.firstInstanceOrNull<CharacterTagsFilter>()?.state
                ?.searchTerms(exact = exactTags, namespace = "character").orEmpty())
            addAll(filters.firstInstanceOrNull<FemaleTagsFilter>()?.state
                ?.searchTerms(exact = exactTags, namespace = "female").orEmpty())
            addAll(filters.firstInstanceOrNull<MaleTagsFilter>()?.state
                ?.searchTerms(exact = exactTags, namespace = "male").orEmpty())
            addAll(filters.firstInstanceOrNull<CosplayerTagsFilter>()?.state
                ?.searchTerms(exact = exactTags, namespace = "cosplayer").orEmpty())
            addAll(filters.firstInstanceOrNull<LocationTagsFilter>()?.state
                ?.searchTerms(exact = exactTags, namespace = "location").orEmpty())
            addAll(filters.firstInstanceOrNull<OtherTagsFilter>()?.state
                ?.searchTerms(exact = exactTags, namespace = "other").orEmpty())
            addAll(filters.firstInstanceOrNull<UploaderFilter>()?.state
                ?.searchTerms(namespace = "uploader").orEmpty())
            addAll(filters.firstInstanceOrNull<GalleryIdFilter>()?.state
                ?.searchTerms(namespace = "gid").orEmpty())
        }
        val titleTerms = filters.firstInstanceOrNull<TitleQueryFilter>()?.state
            ?.searchTerms(exact = exactTags, namespace = "title").orEmpty()
        val minimumRating = filters.firstInstanceOrNull<MinimumRatingFilter>()
        val minimumPages = filters.firstInstanceOrNull<MinimumPagesFilter>()?.state?.pageCountOrNull()
        val maximumPages = filters.firstInstanceOrNull<MaximumPagesFilter>()?.state?.pageCountOrNull()
        val sortOrder = filters.firstInstanceOrNull<PageOrderFilter>()?.state ?: 0
        val trimmedQuery = query.trim()
        val searchQuery = buildList {
            trimmedQuery.takeIf { it.isNotEmpty() }?.let(::add)
            language?.queryValue()?.let(::add)
            addAll(categoryTags)
            addAll(namespaceTags)
            addAll(includeTags)
            addAll(excludeTags)
            addAll(titleTerms)
        }.joinToString(" ")
        val inclusionCount = categoryTags.size + namespaceTags.size + includeTags.size + titleTerms.size
        val exclusionCount = excludeTags.count { it.startsWith("-") }

        require(searchQuery.length <= MAX_SEARCH_LENGTH) {
            "Search terms cannot exceed $MAX_SEARCH_LENGTH characters"
        }
        require(inclusionCount <= MAX_INCLUSION_TERMS) {
            "Use at most $MAX_INCLUSION_TERMS inclusion terms"
        }
        require(exclusionCount <= MAX_EXCLUSION_TERMS) {
            "Use at most $MAX_EXCLUSION_TERMS exclusion terms"
        }
        require(minimumPages == null || maximumPages == null || minimumPages.toInt() <= maximumPages.toInt()) {
            "Minimum pages cannot exceed maximum pages"
        }

        val titleSearchEnabled = filters.firstInstanceOrNull<SearchTitlesFilter>()?.state != false
        val tagSearchEnabled = filters.firstInstanceOrNull<SearchTagsFilter>()?.state != false
        val hasTagQuery = categoryTags.isNotEmpty() || namespaceTags.isNotEmpty() ||
            includeTags.isNotEmpty() || excludeTags.isNotEmpty()
        val firstUrl = baseUrl.toHttpUrl().newBuilder().apply {
            if (route != "/") addPathSegment(route.removePrefix("/"))
            addQueryParameter("f_cats", categoryFilter?.mask(categoryMode?.state ?: 0).toString())
            searchQuery.takeIf { it.isNotEmpty() }?.let { addQueryParameter("f_search", it) }

            // Comics and other category tags must not be matched against titles.
            // An explicit title term or a non-empty user query can still request title matching.
            if (titleTerms.isNotEmpty() || (titleSearchEnabled && (!hasCategoryTag || trimmedQuery.isNotEmpty()))) {
                addQueryParameter("f_sname", "on")
            }
            if (hasTagQuery || tagSearchEnabled) {
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
        }.build()

        var document = getDocument(firstUrl)
        val visitedPages = mutableSetOf(firstUrl.toString())
        repeat(page - 1) {
            val nextUrl = document.selectFirst("#dnext[href]")?.absUrl("href")?.toHttpUrl()
                ?: return@repeat
            if (!visitedPages.add(nextUrl.toString())) return@repeat
            document = getDocument(nextUrl)
        }
        val resultKey = "$firstUrl|sort=$sortOrder"
        if (page == 1) synchronized(resultHistory) { resultHistory[resultKey] = linkedSetOf() }
        return parseMangaList(document, sortOrder, resultKey)
    }

    private fun parseMangaList(document: Document, sortOrder: Int, resultKey: String): MangasPage {
        val results = document.select("table.itg.gltc > tbody > tr, table.itg.gltc > tr").mapNotNull { row ->
            val galleryLink = row.selectFirst(".gl3c.glname > a[href], .glname > a[href]")
                ?: return@mapNotNull null
            val title = galleryLink.text().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val galleryUrl = galleryLink.absUrl("href").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val cover = row.selectFirst(".glthumb img, .gl2c img")
            val coverUrl = cover?.attr("data-src")?.takeIf { it.isNotEmpty() }
                ?: cover?.absUrl("src")?.takeUnless { it.startsWith("data:") }
            val pageCount = pageCountRegex.find(row.text())?.groupValues?.get(1)
                ?.replace(",", "")?.toIntOrNull() ?: -1
            val manga = SManga.create().apply {
                setUrlWithoutDomain(galleryUrl)
                this.title = title
                thumbnail_url = coverUrl
                genre = row.select(".gt[title]")
                    .map { it.attr("title") }
                    .takeIf { it.isNotEmpty() }
                    ?.joinToString()
            }
            SearchResult(manga, pageCount)
        }.distinctBy { it.manga.url }

        val ordered = when (sortOrder) {
            1 -> results.sortedByDescending { it.pageCount }
            2 -> results.sortedWith(compareBy { it.pageCount.takeIf { count -> count >= 0 } ?: Int.MAX_VALUE })
            3 -> results.sortedBy { it.manga.title.lowercase() }
            4 -> results.sortedByDescending { it.manga.title.lowercase() }
            else -> results
        }
        val uniquePageResults = synchronized(resultHistory) {
            val history = resultHistory.getOrPut(resultKey) { linkedSetOf() }
            ordered.filter { result ->
                history.add(result.manga.url).also {
                    if (history.size > MAX_HISTORY_RESULTS) history.remove(history.first())
                }
            }
        }.map { it.manga }
        val nextUrl = document.selectFirst("#dnext[href]")?.absUrl("href")
        val hasNextPage = !nextUrl.isNullOrEmpty() && nextUrl != document.location()
        return MangasPage(uniquePageResults, hasNextPage)
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
        val document = galleryDocument(getMangaUrl(manga).toHttpUrl())
        val details = parseMangaDetails(document)
        val chapterList = if (fetchChapters) listOf(parseGalleryChapter(details, document)) else chapters
        return SMangaUpdate(details, chapterList)
    }

    private suspend fun galleryDocument(url: HttpUrl): Document {
        var latest: Document? = null
        repeat(preferences.requestRetries) { attempt ->
            val document = getDocument(url)
            latest = document
            if (document.selectFirst("#gn") != null && document.selectFirst("#gdt") != null) {
                return document
            }
            synchronized(documentCache) { documentCache.remove(url.toString()) }
            if (attempt < preferences.requestRetries - 1) delay((attempt + 1) * 500L)
        }
        return latest ?: error("Unable to load the E-Hentai gallery")
    }

    private fun parseMangaDetails(document: Document): SManga = SManga.create().apply {
        setUrlWithoutDomain(document.location())
        title = document.selectFirst("#gn")?.text()?.takeIf { it.isNotEmpty() } ?: "E-Hentai Gallery"
        thumbnail_url = document.selectFirst("#gd1 > div[style], #gd1 [style]")
            ?.attr("style")
            ?.let { coverUrlRegex.find(it)?.groupValues?.getOrNull(1) }

        val galleryTags = document.galleryTags()
        val tagTitles = galleryTags.map { (namespace, value) -> "$namespace:$value" }
        if (preferences.richDetails) {
            genre = buildList {
                document.selectFirst("#gdc")?.text()?.takeIf { it.isNotEmpty() }?.let(::add)
                addAll(tagTitles)
            }.takeIf { it.isNotEmpty() }?.joinToString()
        }
        val contributors = galleryTags.filter { it.first == "artist" || it.first == "group" }
            .map { it.second }
            .distinct()
        author = contributors.takeIf { it.isNotEmpty() }?.joinToString()
        artist = galleryTags.filter { it.first == "artist" }
            .map { it.second }
            .distinct()
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
        val chapterUrl = getChapterUrl(chapter).toHttpUrl()
        val firstDocument = galleryDocument(chapterUrl)
        val imagePages = firstDocument.imagePageUrls().toMutableList()
        val lastGridPage = firstDocument.select(".gtb a[href*='?p=']")
            .maxOfOrNull { it.absUrl("href").toHttpUrl().queryParameter("p")?.toIntOrNull() ?: 0 }
            ?: 0
        val visitedGridPages = mutableSetOf(chapterUrl.toString())

        for (gridPage in 1..lastGridPage) {
            val gridUrl = chapterUrl.newBuilder()
                .addQueryParameter("p", gridPage.toString())
                .build()
            if (!visitedGridPages.add(gridUrl.toString())) continue
            imagePages += galleryDocument(gridUrl).imagePageUrls()
        }

        val uniquePages = imagePages.distinct()
        val prefetched = prefetchImageUrls(uniquePages.take(PREFETCH_PAGE_COUNT))
        return uniquePages.mapIndexed { index, pageUrl ->
            prefetched[pageUrl]?.let { imageUrl ->
                Page(index, url = pageUrl, imageUrl = imageUrl)
            } ?: Page(index, url = pageUrl)
        }
    }

    private fun Document.imagePageUrls(): List<String> =
        select("#gdt a[href]").mapNotNull { it.absUrl("href").takeIf { url -> "/s/" in url } }

    private fun Document.galleryTags(): List<Pair<String, String>> =
        select("#taglist a[id^=ta_]").mapNotNull { link ->
            val tagId = link.id().removePrefix("ta_")
            val namespace = tagId.substringBefore(':').trim()
            val value = link.text().trim()
            if (namespace.isNotEmpty() && value.isNotEmpty()) namespace to value else null
        }.ifEmpty {
            select("#taglist .gt[title]").mapNotNull { tag ->
                val title = tag.attr("title").trim()
                val namespace = title.substringBefore(':').trim()
                val value = title.substringAfter(':', "").trim()
                if (namespace.isNotEmpty() && value.isNotEmpty()) namespace to value else null
            }
        }

    override suspend fun getImageUrl(page: Page): String = resolveImageUrl(page.url)

    private suspend fun resolveImageUrl(pageUrl: String): String {
        synchronized(imageUrlCache) {
            imageUrlCache[pageUrl]?.let { return it }
        }
        var imageUrl = ""
        for (attempt in 0 until preferences.requestRetries) {
            val document = getDocument(pageUrl.toHttpUrl())
            imageUrl = imageUrlFromDocument(document, pageUrl)
            if (imageUrl.isNotEmpty()) break
            synchronized(documentCache) { documentCache.remove(pageUrl) }
            if (attempt < preferences.requestRetries - 1) delay((attempt + 1) * 500L)
        }
        return imageUrl.takeIf { it.isNotEmpty() }?.also {
            synchronized(imageUrlCache) { imageUrlCache[pageUrl] = it }
        } ?: error("E-Hentai did not return an image URL for this page")
    }

    private suspend fun prefetchImageUrls(pageUrls: List<String>): Map<String, String> = coroutineScope {
        pageUrls.map { pageUrl ->
            async { pageUrl to runCatching { resolveImageUrl(pageUrl) }.getOrNull() }
        }.awaitAll().mapNotNull { (pageUrl, imageUrl) -> imageUrl?.let { pageUrl to it } }.toMap()
    }

    override fun imageRequest(page: Page): Request =
        GET(page.imageUrl!!.toHttpUrl(), headersBuilder().set("Referer", page.url).build())

    private fun imageUrlFromDocument(document: Document, pageUrl: String? = null, allowBackup: Boolean = true): String {
        val imageUrl = document.selectFirst("#img")?.absUrl("src")?.takeIf { it.isNotEmpty() }
            ?: document.selectFirst("a[href*='/fullimg/']")?.absUrl("href").orEmpty()
        if (!allowBackup || pageUrl.isNullOrEmpty() || imageUrl.isEmpty()) return imageUrl
        val onclick = document.selectFirst("#loadfail")?.attr("onclick").orEmpty()
        val nlValue = Regex("nl\\('(.+?)'\\)").find(onclick)?.groupValues?.get(1)
        if (nlValue.isNullOrEmpty()) return imageUrl
        val backupUrl = pageUrl.toHttpUrl().newBuilder()
            .addQueryParameter("nl", nlValue)
            .build()
        return "$imageUrl#$backupUrl"
    }

    private suspend fun getDocument(url: HttpUrl): Document {
        synchronized(documentCache) {
            documentCache[url.toString()]?.let { return it }
        }
        val document = client.get(url).asJsoup()
        synchronized(documentCache) { documentCache[url.toString()] = document }
        return document
    }

    override fun getFilterList(data: kotlinx.serialization.json.JsonElement?): FilterList = FilterList(
        Filter.Header("E-Hentai public search filters"),
        CategoryModeFilter(),
        CategoryFilter(),
        PageOrderFilter(),
        Filter.Separator(),
        LanguageFilter(),
        ExactTagFilter(),
        IncludeTagsFilter(),
        ExcludeTagsFilter(),
        Filter.Header("Tag namespaces"),
        ArtistTagsFilter(),
        GroupTagsFilter(),
        ParodyTagsFilter(),
        CharacterTagsFilter(),
        FemaleTagsFilter(),
        MaleTagsFilter(),
        CosplayerTagsFilter(),
        LocationTagsFilter(),
        OtherTagsFilter(),
        UploaderFilter(),
        GalleryIdFilter(),
        TitleQueryFilter(),
        Filter.Separator(),
        Filter.Header("Search fields and availability"),
        SearchTitlesFilter(),
        SearchTagsFilter(),
        SearchDescriptionFilter(),
        SearchTorrentNamesFilter(),
        OnlyTorrentsFilter(),
        ShowExpungedFilter(),
        LowPowerTagsFilter(),
        DownvotedTagsFilter(),
        Filter.Separator(),
        Filter.Header("Rating and page count"),
        MinimumRatingFilter(),
        MinimumPagesFilter(),
        MaximumPagesFilter(),
    )

    private data class SearchResult(
        val manga: SManga,
        val pageCount: Int,
    )

    private companion object {
        const val MAX_SEARCH_LENGTH = 200
        const val MAX_INCLUSION_TERMS = 5
        const val MAX_EXCLUSION_TERMS = 10
        const val DOCUMENT_CACHE_SIZE = 24
        const val IMAGE_URL_CACHE_SIZE = 256
        const val PREFETCH_PAGE_COUNT = 4
        const val RESULT_HISTORY_SIZE = 12
        const val MAX_HISTORY_RESULTS = 2000
        val coverUrlRegex = Regex("""url\(['\"]?([^'")]+)""")
        val pageCountRegex = Regex("""(\d[\d,]*)\s+pages?""", RegexOption.IGNORE_CASE)
    }
}
