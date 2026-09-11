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
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
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
        private val ARTICLE_SELECTORS = listOf("article", ".post", ".video-item", ".entry", ".blog-post")
        private val TITLE_SELECTORS = listOf("h2 a", "h3 a", ".entry-title a", ".title a", "h2", "h3")
        private val POSTER_SELECTORS = listOf("img.wp-post-image", ".post-thumbnail img", ".entry-thumb img", "article img", "img")
    }

    override val mainPage = mainPageOf(
        "$mainUrl/page/" to "Latest Episodes"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val url = if (page == 1) mainUrl else "$mainUrl/page/$page/"
        val document = app.get(url, referer = "$mainUrl/").document
        val items = findArticles(document).mapNotNull { it.toSearchResult() }
        return newHomePageResponse(listOf(HomePageList(request.name, items)))
    }

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
                href = el.attr("href").ifEmpty { this.selectFirst("a")?.attr("href") }
                if (!title.isNullOrEmpty() && !href.isNullOrEmpty()) break
            }
        }
        if (href.isNullOrEmpty()) href = this.selectFirst("a")?.attr("href")
        if (title.isNullOrEmpty() || href.isNullOrEmpty()) return null

        var poster: String? = null
        for (selector in POSTER_SELECTORS) {
            val img = this.selectFirst(selector)
            if (img != null) {
                poster = img.attr("src").ifEmpty { img.attr("data-src") }.ifEmpty { img.attr("data-lazy-src") }
                if (!poster.isNullOrEmpty()) break
            }
        }

        return newMovieSearchResponse(title, fixUrl(href), TvType.TvSeries) {
            this.posterUrl = fixUrlNull(poster)
        }
    }

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

        val episodeLinks = document.select(
            ".episode-list a, .episodes-list a, .episode a, " +
            ".entry-content a[href*=-episode-], .entry-content a[href*=-watch-], " +
            ".post-content a[href*=-episode-]"
        )

        val episodes = mutableListOf<com.lagradost.cloudstream3.Episode>()
        episodeLinks.forEach { el ->
            val epHref = el.attr("href")
            val epTitle = el.text().trim().ifEmpty { "Episode" }
            if (epHref.isNotEmpty() && epHref.startsWith("http")) {
                episodes.add(newEpisode(epHref) { this.name = epTitle })
            }
        }

        return if (episodes.isNotEmpty()) {
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = fixUrlNull(poster)
                this.plot = description
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = fixUrlNull(poster)
                this.plot = description
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data, referer = "$mainUrl/").document
        var found = false

        document.select("iframe").forEach { iframe ->
            val src = iframe.attr("src").ifEmpty { iframe.attr("data-src") }.ifEmpty { iframe.attr("data-litespeed-src") }
            if (src.isNotEmpty() && src.startsWith("http")) {
                try {
                    if (loadExtractor(fixUrl(src), data, subtitleCallback, callback)) found = true
                } catch (_: Exception) {}
            }
        }
        if (found) return true

        document.selectFirst("video")?.let { video ->
            val source = video.selectFirst("source")
            val videoUrl = source?.attr("src") ?: video.attr("src") ?: video.attr("data-src")
            if (!videoUrl.isNullOrEmpty() && videoUrl.startsWith("http")) {
                callback.invoke(
                    newExtractorLink(name, name, fixUrl(videoUrl),
                        type = if (videoUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    ) { this.referer = data; this.quality = 720 }
                )
                found = true
            }
        }
        if (found) return true

        document.select("script").forEach { script ->
            val content = script.data()
            Regex("""["'](https?://[^"']+\.m3u8[^"']*)["']""").find(content)?.groupValues?.getOrNull(1)?.let { url ->
                callback.invoke(newExtractorLink(name, name, url, type = ExtractorLinkType.M3U8) { this.referer = data })
                found = true
            }
            Regex("""["'](https?://[^"']+\.mp4[^"']*)["']""").find(content)?.groupValues?.getOrNull(1)?.let { url ->
                callback.invoke(newExtractorLink(name, name, url, type = ExtractorLinkType.VIDEO) { this.referer = data })
                found = true
            }
        }
        return found
    }
}
