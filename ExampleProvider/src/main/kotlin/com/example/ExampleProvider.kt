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
                // getlink.php URL se v aur type extract karo
                val v = Regex("""[?&]v=([^&]+)""").find(href)?.groupValues?.get(1) ?: ""
                val type = Regex("""[?&]type=([^&]+)""").find(href)?.groupValues?.get(1) ?: "jwplayer"

                if (v.isEmpty()) continue

                // Player URL banao (network tab se confirmed)
                val playerUrl = "https://dstshndisk.showdetails.org/hls/$type.php?v=$v"

                // Player page fetch karo
                val playerHtml = app.get(playerUrl, referer = "https://watch.desitashan.ru/").text

                var videoUrl: String? = null
                var videoType = ExtractorLinkType.M3U8

                // Pattern 1: Direct m3u8
                Regex("""["'](https?://[^"']+\.m3u8[^"']*)["']""").find(playerHtml)?.let {
                    videoUrl = it.groupValues[1].replace("\\/", "/")
                    videoType = ExtractorLinkType.M3U8
                }

                // Pattern 2: JW Player file: property
                if (videoUrl == null) {
                    Regex("""file\s*:\s*["']([^"']+)["']""").find(playerHtml)?.let {
                        val url = it.groupValues[1].replace("\\/", "/")
                        if (url.startsWith("http")) {
                            videoUrl = url
                            videoType = if (url.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                        }
                    }
                }

                // Pattern 3: mp4
                if (videoUrl == null) {
                    Regex("""["'](https?://[^"']+\.mp4[^"']*)["']""").find(playerHtml)?.let {
                        videoUrl = it.groupValues[1].replace("\\/", "/")
                        videoType = ExtractorLinkType.VIDEO
                    }
                }

                // Pattern 4: source src=
                if (videoUrl == null) {
                    Regex("""<source[^>]+src=["']([^"']+)["']""").find(playerHtml)?.let {
                        val url = it.groupValues[1].replace("\\/", "/")
                        if (url.startsWith("http")) {
                            videoUrl = url
                            videoType = if (url.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                        }
                    }
                }

                // Agar video URL mila toh callback
                if (videoUrl != null) {
                    callback.invoke(
                        newExtractorLink(
                            name,
                            playerName,
                            videoUrl,
                            type = videoType
                        ) {
                            this.referer = playerUrl
                            this.quality = 720
                        }
                    )
                    found = true
                    continue
                }

                // Fallback: loadExtractor try karo
                try {
                    if (loadExtractor(playerUrl, "https://watch.desitashan.ru/", subtitleCallback, callback)) {
                        found = true
                        continue
                    }
                } catch (_: Exception) {}

            } catch (_: Exception) {
                // Yeh server skip karo, next try karo
            }
        }

        return found
    }
}
