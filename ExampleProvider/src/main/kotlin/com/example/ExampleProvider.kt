package com.example.ExamplePlugin

import android.util.Base64
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
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class DesiTvSerialzProvider : MainAPI() {
    override var mainUrl = "https://desitvserialz.se"
    override var name = "DesiTvSerialz"
    override val hasMainPage = true
    override var lang = "hi"
    override val hasQuickSearch = false
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)

    companion object {
        // WordPress Sahifa theme ke liye sahi selectors
        private val ARTICLE_SELECTORS = listOf(
            ".recent-item",        // ← Asli selector (homepage)
            ".tie_video",
            "article",
            ".post",
            ".video-item"
        )
        private val TITLE_SELECTORS = listOf(
            ".post-box-title a",   // ← Asli selector
            "h2 a", "h3 a",
            ".entry-title a",
            ".title a"
        )
        private val POSTER_SELECTORS = listOf(
            ".post-thumbnail img", // ← Asli selector
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

        // Yeh ek single episode page hai, isliye direct movie banao
        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = fixUrlNull(poster)
            this.plot = description
        }
    }

    // ==================== LOAD LINKS (MAIN FIX) ====================
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data, referer = "$mainUrl/").document
        var found = false

        // ---- STEP 1: Har server se data-embed aur data-sv-id nikaalo ----
        val servers = document.select("#servers .server, a.server")
        for (server in servers) {
            val embed = server.attr("data-embed")
            val svId = server.attr("data-sv-id")
            
            if (embed.isEmpty() || svId.isEmpty()) continue

            // ---- STEP 2: Player iframe URL construct karo ----
            // Host = https://player.dramavideo.se (from cdn.js decryption)
            val playerUrl = "https://player.dramavideo.se/in?id=$embed&sv=$svId"

            try {
                // ---- STEP 3: Player page fetch karo ----
                val playerDoc = app.get(playerUrl, referer = data).text
                
                // ---- STEP 4: encData, keyHex, ivHex extract karo ----
                val encDataRegex = Regex("""encData\s*=\s*"([^"]+)"""")
                val keyRegex = Regex("""keyHex\s*=\s*"([^"]+)"""")
                val ivRegex = Regex("""ivHex\s*=\s*"([^"]+)"""")

                val encData = encDataRegex.find(playerDoc)?.groupValues?.get(1)
                val keyHex = keyRegex.find(playerDoc)?.groupValues?.get(1)
                val ivHex = ivRegex.find(playerDoc)?.groupValues?.get(1)

                if (encData != null && keyHex != null && ivHex != null) {
                    // ---- STEP 5: AES-256-CBC decrypt karo ----
                    val decryptedHtml = aesDecrypt(encData, keyHex, ivHex)

                    if (decryptedHtml.isNotEmpty()) {
                        // ---- STEP 6: Decrypted HTML mein se video URL nikaalo ----
                        val videoUrlRegex = Regex("""["'](https?://[^"']+\.(?:m3u8|mp4)[^"']*)["']""")
                        val videoMatch = videoUrlRegex.find(decryptedHtml)

                        if (videoMatch != null) {
                            val videoUrl = videoMatch.groupValues[1]
                            callback.invoke(
                                newExtractorLink(
                                    name,
                                    "Server ($svId)",
                                    videoUrl,
                                    type = if (videoUrl.contains(".m3u8")) ExtractorLinkType.M3U8 
                                           else ExtractorLinkType.VIDEO
                                ) {
                                    this.referer = playerUrl
                                    this.quality = 720
                                }
                            )
                            found = true
                        } else {
                            // Agar decrypted HTML mein iframe/script hai toh loadExtractor try karo
                            val iframeRegex = Regex("""<iframe[^>]+src=["']([^"']+)["']""")
                            val iframeMatch = iframeRegex.find(decryptedHtml)
                            if (iframeMatch != null) {
                                val iframeUrl = iframeMatch.groupValues[1]
                                if (loadExtractor(fixUrl(iframeUrl), playerUrl, subtitleCallback, callback)) {
                                    found = true
                                }
                            }
                        }
                    }
                }
            } catch (_: Exception) { }

            // Agar pehla server kaam kar gaya toh baaki skip kar sakte ho,
            // lekin multiple servers dena better hai
        }

        // ---- FALLBACK: Agar servers se kuch nahi mila ----
        if (!found) {
            // Direct iframe check (agar kabhi ho)
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

    // ==================== AES DECRYPTION HELPER ====================
    private fun aesDecrypt(encDataB64: String, keyHex: String, ivHex: String): String {
        return try {
            val keyBytes = hexToBytes(keyHex)
            val ivBytes = hexToBytes(ivHex)
            val ciphertext = Base64.decode(encDataB64, Base64.DEFAULT)

            val cipher = Cipher.getInstance("AES/CBC/PKCS7Padding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(keyBytes, "AES"),
                IvParameterSpec(ivBytes)
            )

            val plainBytes = cipher.doFinal(ciphertext)
            String(plainBytes, Charsets.UTF_8)
        } catch (e: Exception) {
            ""
        }
    }

    private fun hexToBytes(hex: String): ByteArray {
        return hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }
}
