package com.arabx.plugin

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class ArabxCamProvider : MainAPI() {
    override var name = "ArabX"
    override var mainUrl = "https://www.arabx.cam"
    override var lang = "ar"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.NSFW)

    override val mainPage = mainPageOf(
        "latest-updates/" to "احدث الافلام",
        "top-rated/" to "افضل الافلام",
        "most-popular/" to "الاعلى مشاهدة",
        "categories/سكس-مترجم/" to "مترجم",
        "categories/سكس-امهات-مترجم/" to "أمهات",
        "categories/سكس-محارم/" to "محارم",
        "categories/سكس-اخوات/" to "اخوات",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        return try {
            val url = "$mainUrl/${request.data}${if (page > 1) "page/$page/" else ""}"
            val doc = app.get(url, referer = mainUrl).document
            val items = doc.select("div.item").mapNotNull { item ->
                try {
                    val a = item.selectFirst("a") ?: return@mapNotNull null
                    val href = a.attr("href") ?: return@mapNotNull null
                    val title = item.selectFirst("strong.title")?.text()?.trim()
                        ?: a.attr("title")
                    val poster = item.selectFirst("img.thumb, img.lazy-load")?.let {
                        it.attr("data-original").ifBlank { it.attr("src") }
                    }
                    val rating = item.selectFirst("div.rating")?.text()?.trim()?.replace("%", "")
                    newMovieSearchResponse(title, href, TvType.NSFW) {
                        this.posterUrl = poster
                        if (!rating.isNullOrBlank()) this.score = Score.from(rating, 100)
                    }
                } catch (e: Exception) { null }
            }
            newHomePageResponse(request.name, items)
        } catch (e: Exception) { null }
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        return try {
            val doc = app.get("$mainUrl/search/videos/?q=$query", referer = mainUrl).document
            doc.select("div.item").mapNotNull { item ->
                try {
                    val a = item.selectFirst("a") ?: return@mapNotNull null
                    val href = a.attr("href") ?: return@mapNotNull null
                    val title = item.selectFirst("strong.title")?.text()?.trim()
                        ?: a.attr("title")
                    val poster = item.selectFirst("img.thumb, img.lazy-load")?.let {
                        it.attr("data-original").ifBlank { it.attr("src") }
                    }
                    newMovieSearchResponse(title, href, TvType.NSFW) { this.posterUrl = poster }
                } catch (e: Exception) { null }
            }
        } catch (e: Exception) { null }
    }

    override suspend fun load(url: String): LoadResponse? {
        return try {
            val doc = app.get(url, referer = mainUrl).document
            val title = doc.selectFirst("h1.htitle")?.text()?.trim()
                ?: doc.title().substringBefore(" -").trim()
            val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
            val description = doc.selectFirst("meta[name=description]")?.attr("content")
            val tags = doc.select("meta[name=keywords]")?.attr("content")
                ?.split(",")?.map { it.trim() }?.take(6)
            newMovieLoadResponse(title, url, TvType.NSFW, url) {
                this.posterUrl = poster; this.plot = description; this.tags = tags
            }
        } catch (e: Exception) { null }
    }

    override suspend fun loadLinks(
        data: String, isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val doc = app.get(data, referer = mainUrl).document
            
            // Method 1: flashvars
            val script = doc.select("script").map { it.html() }
                .firstOrNull { it.contains("flashvars") }
            if (script != null) {
                val v1 = regex(script, "video_url")
                val v2 = regex(script, "video_alt_url")
                val v3 = regex(script, "video_alt_url2")
                val q1 = regex(script, "video_url_text") ?: "360p"
                val q2 = regex(script, "video_alt_url_text") ?: "480p"
                val q3 = regex(script, "video_alt_url2_text") ?: "720p"
                v1?.let { cb(it, q1, mainUrl, callback) }
                v2?.let { cb(it, q2, mainUrl, callback) }
                v3?.let { cb(it, q3, mainUrl, callback) }
                if (v1 != null || v2 != null || v3 != null) return true
            }
            
            // Method 2: iframe embed (playeriz.com)
            val iframe = doc.selectFirst("div.embed-wrap iframe")
            if (iframe != null) {
                val iframeUrl = iframe.attr("src")
                if (iframeUrl.isNotBlank()) {
                    try {
                        val iframeDoc = app.get(iframeUrl, referer = data).document
                        val iScript = iframeDoc.select("script").map { it.html() }
                            .firstOrNull { it.contains("video_url") || it.contains("sources") }
                        if (iScript != null) {
                            val v1 = regex(iScript, "video_url")
                            val v2 = regex(iScript, "video_alt_url")
                            val v3 = regex(iScript, "video_alt_url2")
                            v1?.let { cb(it, "360p", mainUrl, callback) }
                            v2?.let { cb(it, "480p", mainUrl, callback) }
                            v3?.let { cb(it, "720p", mainUrl, callback) }
                            if (v1 != null || v2 != null || v3 != null) return true
                        }
                    } catch (_: Exception) {}
                    cb(iframeUrl, "720p", data, callback)
                    return true
                }
            }
            
            // Method 3: HTML5 video sources
            doc.select("video source").forEach { src ->
                val url = src.attr("src")
                val quality = src.attr("title").ifBlank { qual(url) }
                if (url.isNotBlank()) cb(url, quality, data, callback)
            }
            true
        } catch (e: Exception) { false }
    }

    private fun regex(script: String, key: String): String? {
        val match = Regex("""$key\s*[:=]\s*['"]([^'"]+)['"]""").find(script)
        return match?.groupValues?.get(1)?.ifBlank { null }
    }

    private fun qual(url: String): String = when {
        url.contains("720p") -> "720p"; url.contains("480p") -> "480p"
        url.contains("360p") -> "360p"; url.contains("1080p") -> "1080p"
        else -> "360p"
    }

    private fun clean(url: String): String = when {
        url.startsWith("function/0/") -> url.removePrefix("function/0/")
        url.startsWith("//") -> "https:$url"; else -> url
    }

    private suspend fun cb(url: String, quality: String, referer: String, callback: (ExtractorLink) -> Unit) {
        callback(newExtractorLink(source = name, name = name, url = clean(url), type = ExtractorLinkType.VIDEO) {
            this.referer = mainUrl; this.quality = getQualityFromName(quality)
        })
    }
}
