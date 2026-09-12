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
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Element
import org.json.JSONObject

class DesiTashanProvider : MainAPI() {
    override var mainUrl = "https://watch.desitashan.ru"
    override var name = "DesiTashan"
    override val hasMainPage = true
    override var lang = "hi"
    override val hasQuickSearch = false
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)

    companion object {
        private const val PLAYER_BASE = "https://dstshndisk.showdetails.org/hls"
        private const val BLOG_REFERER = "https://blog.showdetails.org/"
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
        val titleEl = this.selectFirst(".post-title a, h2 a, h3 a")
        val title = titleEl?.text()?.trim() ?: return null
        val href = titleEl.attr("href")
        if (href.isEmpty()) return null

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

    // ==================== LOAD LINKS ====================
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data, referer = "$mainUrl/").document
        var found = false

        // Saare "Watch Now" links nikaalo
        val links = document.select(".stream-panel .stream-row .stream-action a")

        for (link in links) {
            val href = link.attr("href").replace("&#038;", "&").replace("&amp;", "&")
            if (href.isEmpty()) continue

            val row = link.closest(".stream-row")
            val playerName = row?.selectFirst(".stream-title")?.text()?.trim() ?: "Server"

            // v token, part2, part3 extract karo
            val tokens = mutableListOf<Pair<String, String>>()

            Regex("""[?&]v=([^&]+)""").find(href)?.let {
                tokens.add("Part 1" to it.groupValues[1])
            }
            Regex("""[?&]part2=([^&]+)""").find(href)?.let {
                tokens.add("Part 2" to it.groupValues[1])
            }
            Regex("""[?&]part3=([^&]+)""").find(href)?.let {
                tokens.add("Part 3" to it.groupValues[1])
            }

            // Har part ke liye media_meta fetch karo
            for ((partLabel, token) in tokens) {
                try {
                    val metaUrl = "$PLAYER_BASE/media_meta.php?v=$token&type=player"
                    val metaJson = app.get(metaUrl, referer = BLOG_REFERER).text

                    val json = JSONObject(metaJson)
                    val source = json.optJSONObject("source") ?: continue
                    val file = source.optString("file", "")
                    if (file.isEmpty()) continue

                    // Full URL banao
                    val fullUrl = if (file.startsWith("http")) file
                                  else "$PLAYER_BASE/$file"

                    callback.invoke(
                        newExtractorLink(
                            name,
                            "$partLabel ($playerName)",
                            fullUrl,
                            type = ExtractorLinkType.VIDEO
                        ) {
                            this.referer = BLOG_REFERER
                            this.quality = 720
                        }
                    )
                    found = true
                } catch (_: Exception) {
                    // Yeh part skip karo, next try karo
                }
            }

            // Pehla server kaam kar gaya toh baaki skip kar sakte ho
            if (found) break
        }

        return found
    }
}
