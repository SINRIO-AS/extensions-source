package eu.kanade.tachiyomi.extension.en.ehentai



import eu.kanade.tachiyomi.source.model.Filter

import eu.kanade.tachiyomi.source.model.FilterList

import eu.kanade.tachiyomi.source.model.MangasPage

import eu.kanade.tachiyomi.source.model.Page

import eu.kanade.tachiyomi.source.model.SChapter

import eu.kanade.tachiyomi.source.model.SManga

import eu.kanade.tachiyomi.source.model.SMangaUpdate

import eu.kanade.tachiyomi.network.GET

import eu.kanade.tachiyomi.source.model.UpdateStrategy

import eu.kanade.tachiyomi.util.asJsoup

import keiyoushi.annotation.Source

import keiyoushi.network.get

import keiyoushi.network.rateLimit

import keiyoushi.source.KeiSource

import keiyoushi.utils.firstInstanceOrNull

import okhttp3.Headers

import okhttp3.HttpUrl

import okhttp3.HttpUrl.Companion.toHttpUrl

import okhttp3.OkHttpClient

import okhttp3.Request

import org.jsoup.nodes.Document

import kotlinx.coroutines.delay

import kotlin.time.Duration.Companion.seconds



@Source

abstract class Ehentai : KeiSource() {
    

    
    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder =
    
        // The former one-request-per-three-seconds throttle made ordinary 20–40 page
    
        // galleries unusably slow. A short, bounded burst stays polite while allowing
    
        // the reader to resolve a normal gallery in seconds rather than minutes.
    
        rateLimit(4, 1.seconds) { it.host == baseUrl.toHttpUrl().host }
        

        
    override fun Headers.Builder.configureHeaders() =
    
        add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
        

        
    override suspend fun getPopularManga(page: Int): MangasPage =
    
        getGalleryList(page, "", FilterList())
        

        
    override suspend fun getLatestUpdates(page: Int): MangasPage =
    
        getGalleryList(page, "", FilterList())
        

        
    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage =
    
        getGalleryList(page, query, filters)
        

        
    private suspend fun getGalleryList(page: Int, query: String, filters: FilterList): MangasPage {
        
        val categoryFilter = filters.firstInstanceOrNull<CategoryFilter>()
        
        val categoryMode = filters.firstInstanceOrNull<CategoryModeFilter>()
        
        val categoryTags = categoryFilter?.queryTags().orEmpty()
        
        val hasCategoryTag = categoryFilter?.hasQueryTag() == true
        
        val language = filters.firstInstanceOrNull<LanguageFilter>()
        
        val includeTags = filters.firstInstanceOrNull<IncludeTagsFilter>()?.state?.searchTerms().orEmpty()
        
        val excludeTags = filters.firstInstanceOrNull<ExcludeTagsFilter>()?.state?.searchTerms(exclude = true).orEmpty()
        
        val minimumRating = filters.firstInstanceOrNull<MinimumRatingFilter>()
        
        val minimumPages = filters.firstInstanceOrNull<MinimumPagesFilter>()?.state?.pageCountOrNull()
        
        val maximumPages = filters.firstInstanceOrNull<MaximumPagesFilter>()?.state?.pageCountOrNull()
        
        val searchQuery = buildList {
            
            query.trim().takeIf { it.isNotEmpty() }?.let(::add)
            
            language?.queryValue()?.let(::add)
            
            addAll(categoryTags)
            
            addAll(includeTags)
            
            addAll(excludeTags)
            
        }.joinToString(" ")
        

        
        require(minimumPages == null || maximumPages == null || minimumPages.toInt() <= maximumPages.toInt()) {
            
            "Minimum pages cannot exceed maximum pages"
            
        }
        

        
        val firstUrl = baseUrl.toHttpUrl().newBuilder().apply {
            
            addQueryParameter("f_cats", categoryFilter?.mask(categoryMode?.state ?: 0).toString())
            
            searchQuery.takeIf { it.isNotEmpty() }?.let { addQueryParameter("f_search", it) }
            

            
            if (filters.firstInstanceOrNull<SearchTitlesFilter>()?.state != false) {
                
                addQueryParameter("f_sname", "on")
                
            }
            
            if (hasCategoryTag || filters.firstInstanceOrNull<SearchTagsFilter>()?.state != false) {
                
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
                
        







































































