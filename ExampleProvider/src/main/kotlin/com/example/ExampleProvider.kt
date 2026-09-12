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

class DesiTvSerialzProvider : MainAPI() {
    override var mainUrl = "https://desitvserialz.se"
    override var name = "DesiTvSerialz"
    override val hasMainPage = true
    override var lang = "hi"
    override val hasQuickSearch = false
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)

    companion object {
        private val ARTICLE_SELECTORS = listOf(
            ".recent-item",
            ".tie_video",
            "article",
            ".post",
            ".video-item"
        )
        private val TITLE_SELECTORS = listOf(
            ".post-box-title a",
            "h2 a",
            "h3 a",
            ".entry-title a",
            ".title a"
        )
        private val POSTER_SELECTORS = listOf(
            ".post-thumbnail img",
            "img.wp-post-image",
            ".entry-thumb img",
            "img"
        )
    }

    override val mainPage = mainPageOf(
        "$mainUrl/page/" to "Latest Episodes"
    )

    // ==================== MAIN PAGE ====================
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val url = if (page == 1) mainUrl else "$mainUrl/page/$page/"
        val document = app.get(url, referer = "$mainUrl/").document
        val items = findArticles(document).mapNotNull { it.toSearchResult() }
        return newHomePageResponse(listOf(HomePageList(request.name, items)))
    }

    // ==================== SEARCH ====================
    override suspend fun search(query: String): List<SearchResponse>? {
        val document = app.get("$mainUrl/?s=${query.replace(" ", "+")}", referer = "$mainUrl/").document
        val results = findArticles(document).mapNotNull { it.toSearchResult() }
        return if (results.isEmpty()) null else results
    }

    private fun findArticles(document: org.jsoup.nodes.Document): List<Element> {
        for (selector in ARTICLE_SELECTORS) {
            val elements = document.select(selector)
            if (elements.isNotEmpty()) return elements.toList()
        }
        return emptyList()
    }

    private fun Element.toSearchResult(): SearchResponse? {
        var title: String? = null
        var href: String? = null
        for (selector in TITLE_SELECTORS) {
            val el = this.selectFirst(selector)
            if (el != null) {
                title = el.text().trim()
                href = el.attr("href")
                if (!title.isNullOrEmpty() && !href.isNullOrEmpty()) break
            }
        }
        if (href.isNullOrEmpty()) href = this.selectFirst("a")?.attr("href")
        if (title.isNullOrEmpty() || href.isNullOrEmpty()) return null

        var poster: String? = null
        for (selector in POSTER_SELECTORS) {
            val img = this.selectFirst(selector)
            if (img != null) {
                poster = img.attr("src")
                    .ifEmpty { img.attr("data-src") }
                    .ifEmpty { img.attr("data-lazy-src") }
                if (!poster.isNullOrEmpty()) break
            }
        }

        return newMovieSearchResponse(title, fixUrl(href), TvType.TvSeries) {
            this.posterUrl = fixUrlNull(poster)
        }
    }

    // ==================== DETAIL PAGE ====================
    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, referer = "$mainUrl/").document
        val title = document.selectFirst("h1.entry-title, h1.post-title, h1")?.text()?.trim()
            ?: document.selectFirst("meta[property=og:title]")?.attr("content") ?: "Unknown"
        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")
            ?: document.selectFirst(".post-thumbnail img, .entry-thumb img, article img")?.let {
                it.attr("src").ifEmpty { it.attr("data-src") }
            }
        val description = document.selectFirst("meta[name=description]")?.attr("content")
            ?: document.selectFirst(".entry-content p, .post-content p")?.text()

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = fixUrlNull(poster)
            this.plot = description
        }
    }

    // ==================== LOAD LINKS (VIDEO PLAYBACK) ====================
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data, referer = "$mainUrl/").document
        var found = false

        // STEP 1: Saare iframes se Dailymotion video ID nikalo
        document.select("iframe").forEach { iframe ->
            val src = iframe.attr("src")
                .ifEmpty { iframe.attr("data-src") }
                .ifEmpty { iframe.attr("data-litespeed-src") }

            if (src.isNotEmpty() && src.contains("dailymotion")) {
                val videoId = extractDailymotionId(src)

                if (videoId != null) {
                    // STEP 2: Dailymotion metadata API se direct m3u8 link lo
                    try {
                        val apiUrl = "https://www.dailymotion.com/player/metadata/video/$videoId"
                        val jsonResponse = app.get(apiUrl, referer = src).text

                        // STEP 3: JSON se m3u8 URL extract karo
                        val m3u8Regex = Regex(""""(https?:\\/\\/[^"]+?\.m3u8[^"]*)"""")
                        val m3u8Match = m3u8Regex.find(jsonResponse)

                        if (m3u8Match != null) {
                            val m3u8Url = m3u8Match.groupValues[1].replace("\\/", "/")
                            callback.invoke(
                                newExtractorLink(
                                    this.name,
                                    "Dailymotion",
                                    m3u8Url,
                                    type = ExtractorLinkType.M3U8
                                ) {
                                    this.referer = "https://www.dailymotion.com/"
                                    this.quality = 720
                                }
                            )
                            found = true
                        } else {
                            // Fallback: saare m3u8 links dhoondo
                            val anyUrlRegex = Regex(""""(https?:\\/\\/[^"]+?\.m3u8[^"]*)"""")
                            anyUrlRegex.findAll(jsonResponse).forEach { match ->
                                val url = match.groupValues[1].replace("\\/", "/")
                                if (url.contains("dailymotion") || url.contains("dmcdn")) {
                                    callback.invoke(
                                        newExtractorLink(
                                            this.name,
                                            "Dailymotion",
                                            url,
                                            type = ExtractorLinkType.M3U8
                                        ) {
                                            this.referer = "https://www.dailymotion.com/"
                                            this.quality = 720
                                        }
                                    )
                                    found = true
                                }
                            }
                        }
                    } catch (_: Exception) { }

                    // STEP 4: Agar API se nahi mila to loadExtractor try karo
                    if (!found) {
                        try {
                            if (loadExtractor(fixUrl(src), data, subtitleCallback, callback)) {
                                found = true
                            }
                        } catch (_: Exception) {}
                    }
                }
            }
        }

        // FALLBACK: Agar Dailymotion nahi mila to koi bhi iframe try karo
        if (!found) {
            document.select("iframe").forEach { iframe ->
                val src = iframe.attr("src").ifEmpty { iframe.attr("data-src") }
                if (src.isNotEmpty() && src.startsWith("http")) {
                    try {
                        if (loadExtractor(fixUrl(src), data, subtitleCallback, callback)) {
                            found = true
                        }
                    } catch (_: Exception) {}
                }
            }
        }

        return found
    }

    // ==================== HELPER: Dailymotion ID Extractor ====================
    private fun extractDailymotionId(url: String): String? {
        val patterns = listOf(
            Regex("""dailymotion\.com/(?:embed/)?video/([a-zA-Z0-9]+)"""),
            Regex("""[?&]video=([a-zA-Z0-9]+)"""),
            Regex("""dai\.ly/([a-zA-Z0-9]+)""")
        )
        for (pattern in patterns) {
            val match = pattern.find(url)
            if (match != null) return match.groupValues[1]
        }
        return null
    }
}
