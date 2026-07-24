package com.jiotv

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.*
import android.util.Base64

class JioTvProvider : MainAPI() {
    override var mainUrl = "https://jiotv.com"
    override var name = "JioTV Live"
    override val supportedTypes = setOf(TvType.Live)
    override var lang = "hi"
    override val hasMainPage = true

    // Data classes to parse JSON
    data class JioChannel(
        val channel_id: Int,
        val channel_name: String,
        val channelCategoryId: Int,
        val channelLanguageId: Int,
        val logoUrl: String,
        val isCatchupAvailable: Boolean
    )
    
    data class JioResponse(
        val result: List<JioChannel>? = emptyList()
    )

    private val categories = mapOf(
        1 to "Entertainment", 2 to "Movies", 3 to "Kids", 4 to "Sports",
        5 to "Lifestyle", 6 to "Infotainment", 7 to "News", 8 to "Music",
        9 to "Devotional", 10 to "Lifestyle", 11 to "Infotainment", 12 to "News",
        13 to "Educational", 14 to "Shopping", 15 to "Jio Darshan", 16 to "Business"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = "https://jiotv.data.cdn.jio.com/apis/v1.4/getMobileChannelList/get/?os=android&devicetype=phone"
        val response = app.get(url, headers = mapOf("User-Agent" to "okhttp/4.12.13"))
        
        val jioResponse = parseJson<JioResponse>(response.text)
        
        val channels = jioResponse.result ?: emptyList()
        val homeList = channels.map { ch ->
            val logoName = ch.logoUrl
            val logoUrl = "https://jiotvimages.cdn.jio.com/dare_images/images/$logoName"
            val category = categories[ch.channelCategoryId] ?: "Other"
            
            newLiveSearchResponse(
                name = ch.channel_name,
                url = ch.channel_id.toString(), // Store ID as URL
                type = TvType.Live
            ) {
                this.posterUrl = logoUrl
            }
        }
        
        // Group by category
        val grouped = homeList.groupBy { channels.find { ch -> ch.channel_id.toString() == it.url }?.channelCategoryId?.let { cat -> categories[cat] } ?: "Other" }
        
        val homePages = grouped.map { (cat, list) ->
            HomePageList(cat, list)
        }
        
        return newHomePageResponse(
            list = homePages,
            hasNext = false
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "https://jiotv.data.cdn.jio.com/apis/v1.4/getMobileChannelList/get/?os=android&devicetype=phone"
        val response = app.get(url, headers = mapOf("User-Agent" to "okhttp/4.12.13"))
        val jioResponse = parseJson<JioResponse>(response.text)
        
        val channels = jioResponse.result ?: emptyList()
        return channels.filter { 
            it.channel_name.contains(query, ignoreCase = true) 
        }.map { ch ->
            newLiveSearchResponse(
                name = ch.channel_name,
                url = ch.channel_id.toString(),
                type = TvType.Live
            ) {
                this.posterUrl = "https://jiotvimages.cdn.jio.com/dare_images/images/${ch.logoUrl}"
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val channelId = url
        // We set the URL to our local proxy, which handles everything!
        val proxyUrl = "http://127.0.0.1:8080/play.m3u8?id=$channelId"
        
        return newLiveStreamLoadResponse(
            name = "Live TV",
            url = proxyUrl,
            dataUrl = proxyUrl
        ) {
            this.posterUrl = null
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // data is the proxyUrl from load()
        callback.invoke(
            newExtractorLink(
                source = "JioTV Proxy",
                name = "JioTV Live",
                url = data,
                type = com.lagradost.cloudstream3.utils.ExtractorLinkType.M3U8
            ) {
                this.quality = 1080
                this.referer = ""
            }
        )
        return true
    }
}