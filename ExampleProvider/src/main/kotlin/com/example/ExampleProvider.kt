package com.example.ExamplePlugin

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class DesiTashanProvider : MainAPI() {
    override var mainUrl = "https://watch.desitashan.ru"
    override var name = "DesiTashan"
    override val hasMainPage = true
    override var lang = "hi"
    override val hasQuickSearch = false
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Latest Episodes"
    )

    // ========== 1. HOMEPAGE ==========
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val url = if (page == 1) mainUrl else "$mainUrl/page/$page/"
        val document = app.get(url, referer = "$mainUrl/").document
        val items = document.select("article.post-item, article").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(listOf(HomePageList(request.name, items)))
    }

    // ========== 2. SEARCH ==========
    override suspend fun search(query: String): List<SearchResponse>? {
        val document = app.get("$mainUrl/?s=${query.replace(" ", "+")}", referer = "$mainUrl/").document
        return document.select("article.post-item, article").mapNotNull { it.toSearchResult() }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst("h2 a, h3 a, .entry-title a")?.text()?.trim() ?: return null
        val href = this.selectFirst("h2 a, h3 a, .entry-title a")?.attr("href") ?: return null
        val poster = this.selectFirst("img")?.attr("src")
            ?: this.selectFirst("img")?.attr("data-src")
        return newMovieSearchResponse(title, fixUrl(href), TvType.TvSeries) {
            this.posterUrl = fixUrlNull(poster)
        }
    }

    // ========== 3. DETAIL PAGE ==========
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

    // ========== 4. LOAD LINKS (Multi-Part Support) ==========
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data, referer = "$mainUrl/").document
        var found = false

        // Har part ke liye alag link nikaalo
        val partButtons = document.select(".part-button, .server-btn, a[data-part], .episode-part")

        partButtons.forEachIndexed { index, button ->
            val partUrl = button.attr("href").ifEmpty { button.attr("data-url") }
            if (partUrl.isNotEmpty()) {
                try {
                    val partDoc = app.get(fixUrl(partUrl), referer = data).document
                    val videoUrl = extractVideoUrl(partDoc)

                    if (videoUrl != null) {
                        callback.invoke(
                            newExtractorLink(
                                this.name,
                                "Part ${index + 1}",
                                videoUrl,
                                type = if (videoUrl.contains(".m3u8")) ExtractorLinkType.M3U8
                                       else ExtractorLinkType.VIDEO
                            ) {
                                this.referer = data
                                this.quality = 720
                            }
                        )
                        found = true
                    }
                } catch (_: Exception) {}
            }
        }

        // Fallback: Direct video tag
        if (!found) {
            val videoUrl = extractVideoUrl(document)
            if (videoUrl != null) {
                callback.invoke(
                    newExtractorLink(this.name, "Default", videoUrl,
                        type = if (videoUrl.contains(".m3u8")) ExtractorLinkType.M3U8
                               else ExtractorLinkType.VIDEO
                    ) {
                        this.referer = data
                        this.quality = 720
                    }
                )
                found = true
            }
        }

        return found
    }

    private fun extractVideoUrl(document: org.jsoup.nodes.Document): String? {
        // Method 1: Direct video/source tag
        document.selectFirst("video source")?.let {
            val src = it.attr("src").ifEmpty { it.attr("data-src") }
            if (src.isNotEmpty()) return fixUrl(src)
        }

        // Method 2: JavaScript mein m3u8/mp4
        document.select("script").forEach { script ->
            val content = script.data()
            Regex("""["'](https?://[^"']+\.m3u8[^"']*)["']""").find(content)?.let {
                return it.groupValues[1]
            }
            Regex("""["'](https?://[^"']+\.mp4[^"']*)["']""").find(content)?.let {
                return it.groupValues[1]
            }
        }

        // Method 3: iframe
        document.selectFirst("iframe")?.let {
            val src = it.attr("src").ifEmpty { it.attr("data-src") }
            if (src.isNotEmpty()) return fixUrl(src)
        }

        return null
    }
}
