package com.sexalarab.plugin

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class SexAlArabProvider : MainAPI() {
    override var name = "سكس العرب"
    override var mainUrl = "https://sexalarab.com"
    override var lang = "ar"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.NSFW)

    override val mainPage = mainPageOf(
        "latest-updates/" to "احدث الافلام",
        "top-rated/" to "افضل الافلام",
        "most-popular/" to "الاعلى مشاهدة",
        "category/سكس-مترجم/" to "مترجم",
        "category/سكس-امهات/" to "أمهات",
        "category/سكس-محارم/" to "محارم",
        "category/سكس-نيك-عربي/" to "عربي",
        "category/مسلسلات-سكس-مترجم/" to "مسلسلات",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        return try {
            val url = "$mainUrl/${request.data}${if (page > 1) "page/$page/" else ""}"
            val doc = app.get(url, referer = mainUrl).document
            val items = doc.select("div.item").mapNotNull { item ->
                try {
                    val a = item.selectFirst("a") ?: return@mapNotNull null
                    val href = a.attr("href") ?: return@mapNotNull null
                    val title = item.selectFirst("strong.title")?.text()?.trim() ?: a.attr("title")
                    val poster = item.selectFirst("img.thumb")?.let { it.attr("data-original").ifBlank { it.attr("src") } }
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
                    val title = item.selectFirst("strong.title")?.text()?.trim() ?: a.attr("title")
                    val poster = item.selectFirst("img.thumb")?.let { it.attr("data-original").ifBlank { it.attr("src") } }
                    newMovieSearchResponse(title, href, TvType.NSFW) { this.posterUrl = poster }
                } catch (e: Exception) { null }
            }
        } catch (e: Exception) { null }
    }

    override suspend fun load(url: String): LoadResponse? {
        return try {
            val doc = app.get(url, referer = mainUrl).document
            val title = doc.selectFirst("h1.htitle")?.text()?.trim() ?: doc.title().substringBefore(" -").trim()
            val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
            val description = doc.selectFirst("meta[name=description]")?.attr("content")
            val tags = doc.select("meta[name=keywords]")?.attr("content")?.split(",")?.map { it.trim() }?.take(6)
            newMovieLoadResponse(title, url, TvType.NSFW, url) { this.posterUrl = poster; this.plot = description; this.tags = tags }
        } catch (e: Exception) { null }
    }

    override suspend fun loadLinks(
        data: String, isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val doc = app.get(data, referer = mainUrl).document
            
            // Method 1: HTML5 video sources (fastest)
            doc.select("video source").forEach { src ->
                val url = src.attr("src")
                val quality = src.attr("title").ifBlank { qual(url) }
                if (url.isNotBlank()) cb(url, quality, mainUrl, callback)
            }
            if (doc.select("video source").isNotEmpty()) return true
            
            // Method 2: flashvars
            val script = doc.select("script").map { it.html() }.firstOrNull { it.contains("flashvars") }
            if (script != null) {
                val v1 = regex(script, "video_url"); val v2 = regex(script, "video_alt_url"); val v3 = regex(script, "video_alt_url2")
                val q1 = regex(script, "video_url_text") ?: "360p"; val q2 = regex(script, "video_alt_url_text") ?: "480p"; val q3 = regex(script, "video_alt_url2_text") ?: "720p"
                v1?.let { cb(it, q1, mainUrl, callback) }; v2?.let { cb(it, q2, mainUrl, callback) }; v3?.let { cb(it, q3, mainUrl, callback) }
                if (v1 != null || v2 != null || v3 != null) return true
            }
            
            // Method 3: iframe
            val iframe = doc.selectFirst("div.embed-wrap iframe, iframe[src*=embed]")
            if (iframe != null) { cb(iframe.attr("src"), "720p", data, callback); return true }
            false
        } catch (e: Exception) { false }
    }

    private fun regex(script: String, key: String): String? {
        val match = Regex("""$key\s*[:=]\s*['"]([^'"]+)['"]""").find(script)
        return match?.groupValues?.get(1)?.ifBlank { null }
    }
    private fun qual(url: String): String = when { url.contains("720p") -> "720p"; url.contains("480p") -> "480p"; url.contains("360p") -> "360p"; url.contains("1080p") -> "1080p"; else -> "360p" }
    private fun clean(url: String): String = when { url.startsWith("function/0/") -> url.removePrefix("function/0/"); url.startsWith("//") -> "https:$url"; else -> url }
    private suspend fun cb(url: String, quality: String, referer: String, callback: (ExtractorLink) -> Unit) {
        callback(newExtractorLink(source = name, name = name, url = clean(url), type = ExtractorLinkType.VIDEO) { this.referer = mainUrl; this.quality = getQualityFromName(quality) })
    }
}
