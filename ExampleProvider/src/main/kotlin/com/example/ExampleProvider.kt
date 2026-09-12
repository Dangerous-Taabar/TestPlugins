package com.example.ExamplePlugin

import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrl
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Element

class DesiTashanProvider : MainAPI() {
    override var mainUrl = "https://watch.desitashan.ru"
    override var name = "DesiTashan"
    override val hasMainPage = true
    override var lang = "hi"
    override val hasQuickSearch = false
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)

    companion object {
        private val ARTICLE_SELECTORS = listOf(
            "article.post-item",
            "article",
            ".post-item"
        )
        private val TITLE_SELECTORS = listOf(
            ".post-title a",
            "h2.post-title a",
            "h2 a",
            "h3 a"
        )
    }

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Latest Episodes"
    )

    // ==================== HOMEPAGE ====================
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val url = if (page == 1) mainUrl else "$mainUrl/page/$page/"
        val document = app.get(url, referer = "$mainUrl/").document
        val items = document.select("article.post-item, article").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(listOf(HomePageList(request.name, items)))
    }

    // ==================== SEARCH ====================
    override suspend fun search(query: String): List<SearchResponse>? {
        val document = app.get("$mainUrl/?s=${query.replace(" ", "+")}", referer = "$mainUrl/").document
        val results = document.select("article.post-item, article").mapNotNull { it.toSearchResult() }
        return if (results.isEmpty()) null else results
    }

    private fun Element.toSearchResult(): SearchResponse? {
        var title: String? = null
        var href: String? = null
        for (sel in TITLE_SELECTORS) {
            val el = this.selectFirst(sel)
            if (el != null) {
                title = el.text().trim()
                href = el.attr("href")
                if (!title.isNullOrEmpty() && !href.isNullOrEmpty()) break
            }
        }
        if (href.isNullOrEmpty()) href = this.selectFirst("a")?.attr("href")
        if (title.isNullOrEmpty() || href.isNullOrEmpty()) return null

        val poster = this.selectFirst(".post-thumbnail img, img.wp-post-image, img")?.let {
            it.attr("src").ifEmpty { it.attr("data-src") }
        }

        return newMovieSearchResponse(title, fixUrl(href), TvType.TvSeries) {
            this.posterUrl = fixUrlNull(poster)
        }
    }

    // ==================== DETAIL PAGE ====================
    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, referer = "$mainUrl/").document
        val title = document.selectFirst("h1.entry-title, h1")?.text()?.trim() ?: "Unknown"
        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")
        val description = document.selectFirst("meta[name=description]")?.attr("content")

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = fixUrlNull(poster)
            this.plot = description
        }
    }

    // ==================== LOAD LINKS (MAIN LOGIC) ====================
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data, referer = "$mainUrl/").document
        var found = false

        // Har stream-row ke andar "Watch Now" link nikaalo
        val streamLinks = document.select(".stream-panel .stream-row .stream-action a")
        
        for (link in streamLinks) {
            val href = link.attr("href")
            if (href.isEmpty()) continue

            // Player ka naam nikaalo (JW Player, Video.js, Plyr, Shaka, HLS)
            val row = link.closest(".stream-row")
            val playerName = row?.selectFirst(".stream-title")?.text()?.trim() ?: "Server"

            try {
                // getlink.php page fetch karo
                val response = app.get(href, referer = data).text

                // Multiple patterns se video URL dhundo
                var videoUrl: String? = null
                var videoType = ExtractorLinkType.M3U8

                // Pattern 1: m3u8 URL
                Regex("""["'](https?://[^"']+\.m3u8[^"']*)["']""").find(response)?.let {
                    videoUrl = it.groupValues[1]
                    videoType = ExtractorLinkType.M3U8
                }

                // Pattern 2: mp4 URL
                if (videoUrl == null) {
                    Regex("""["'](https?://[^"']+\.mp4[^"']*)["']""").find(response)?.let {
                        videoUrl = it.groupValues[1]
                        videoType = ExtractorLinkType.VIDEO
                    }
                }

                // Pattern 3: source/src attributes
                if (videoUrl == null) {
                    Regex("""(?:src|file|source)\s*[:=]\s*["']([^"']+)["']""").find(response)?.let {
                        val url = it.groupValues[1]
                        if (url.startsWith("http") && (url.contains(".m3u8") || url.contains(".mp4"))) {
                            videoUrl = url
                            videoType = if (url.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                        }
                    }
                }

                // Agar video URL mila
                if (videoUrl != null) {
                    callback.invoke(
                        newExtractorLink(
                            name,
                            playerName,
                            videoUrl,
                            type = videoType
                        ) {
                            this.referer = href
                            this.quality = 720
                        }
                    )
                    found = true
                    continue
                }

                // Agar video URL nahi mila to iframe check karo
                Regex("""<iframe[^>]+src=["']([^"']+)["']""").find(response)?.let {
                    val iframeUrl = fixUrl(it.groupValues[1])
                    try {
                        if (loadExtractor(iframeUrl, href, subtitleCallback, callback)) {
                            found = true
                            return@let
                        }
                    } catch (_: Exception) {}
                }

                // Ya direct video tag
                Regex("""<video[^>]+src=["']([^"']+)["']""").find(response)?.let {
                    val vidUrl = fixUrl(it.groupValues[1])
                    callback.invoke(
                        newExtractorLink(
                            name,
                            playerName,
                            vidUrl,
                            type = if (vidUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                        ) {
                            this.referer = href
                        }
                    )
                    found = true
                }
            } catch (_: Exception) {
                // Yeh server skip karo, next try karo
            }
        }

        return found
    }
}
