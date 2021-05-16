package eu.kanade.tachiyomi.extension.all.erocool

import android.util.Log
import eu.kanade.tachiyomi.annotations.Nsfw
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Request
import okhttp3.Response
import java.util.regex.Matcher
import java.util.regex.Pattern

@Nsfw
class erocool : HttpSource() {
    override val name: String = "Erocool"
    override val lang: String = "all"
    override val supportsLatest: Boolean = true
    override val baseUrl: String = "https://ja.erocool.net"

    override fun headersBuilder() = super.headersBuilder()
        .add("User-Agent", "Mozilla/5.0 (Windows NT 6.1; WOW64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/62.0.2357.134 Safari/537.36")

    // Popular

    override fun popularMangaRequest(page: Int): Request {
        return GET("$baseUrl/rank/day/page/$page", headers)
    }

    override fun popularMangaParse(response: Response): MangasPage {
        return latestUpdatesParse(response)
    }

    // Latest

    override fun latestUpdatesRequest(page: Int): Request {
        return GET("$baseUrl/latest/page/$page", headers)
    }

    override fun latestUpdatesParse(response: Response): MangasPage {
        val document = response.asJsoup()
        val mangas = document.select(".list-wrapper > a").map { element ->
            SManga.create().apply {
                var titleorigin = element.select(".caption").text()
                if (element.attr("data-tags").contains("29963")) {
                    titleorigin = "[中]" + titleorigin
                } else if (element.attr("data-tags").contains("6346")) {
                    titleorigin = "[日]" + titleorigin
                } else {
                    titleorigin = "[英]" + titleorigin
                }
                title = titleorigin
                url = element.attr("href")
                var cover = element.select(".list-content").attr("style")
                cover = match("background-image:url\\((.*?)\\);", cover, 1)
                thumbnail_url = cover
            }
        }
        return MangasPage(mangas, true)
    }

    // Search

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        if (query != "") {
            val url = "$baseUrl/search/q_$query/page/$page"
            return GET(url, headers)
        } else {
            var params = filters.map {
                if (it is UriPartFilter) {
                    it.toUriPart()
                } else ""
            }.filter { it != "" }.joinToString("")
            if (params == "全部") {
                params = ""
            }

            var params2 = filters.map {
                if (it is UriPartFilter2) {
                    it.toUriPart()
                } else ""
            }.filter { it != "" }.joinToString("")
            if (params2 == "全部") {
                params2 = ""
            }

            var param = ""
            if (params != "") param = params
            if (params2 != "")param = params2
            debugLarge("erocool", "$baseUrl/$param/page/$page")
            return GET("$baseUrl$param/page/$page")
        }
    }

    override fun searchMangaParse(response: Response): MangasPage {
        val document = response.asJsoup()
        val mangas = document.select(".list-wrapper > a").map { element ->
            SManga.create().apply {
                var titleorigin = element.select(".caption").text()
                if (element.attr("data-tags").contains("29963")) {
                    titleorigin = "[中]" + titleorigin
                } else if (element.attr("data-tags").contains("6346")) {
                    titleorigin = "[日]" + titleorigin
                } else {
                    titleorigin = "[英]" + titleorigin
                }
                title = titleorigin
                url = element.attr("href")
                var cover = element.select(".list-content").attr("style")
                cover = match("background-image:url\\((.*?)\\);", cover, 1)
                thumbnail_url = cover
            }
        }
        return MangasPage(mangas, false)
    }

    // Details

    override fun mangaDetailsRequest(manga: SManga): Request {
        // val url = manga.url.replace(",".toRegex(),"/")
        return GET("${baseUrl}${manga.url}", headers)
    }

    override fun mangaDetailsParse(response: Response): SManga {
        val document = response.asJsoup()
        val mangas = SManga.create().apply {
            title = document.select("#comicdetail h1").text()
            thumbnail_url = document.select("#comicdetail img").attr("src")
            genre = document.select("#comicdetail .listdetail_box > div:contains(投稿日) .ld_body").first().text()
            status = SManga.COMPLETED
        }
        return mangas
    }

    // Chapters

    override fun chapterListParse(response: Response): List<SChapter> {
        val chapters = mutableListOf<SChapter>()
        chapters.add(
            SChapter.create().apply {
                name = "全一话"
                url = response.request.url.toString()
            }
        )
        return chapters
    }

    // Pages
    override fun pageListRequest(chapter: SChapter): Request {
        return GET(chapter.url, headers)
    }

    override fun pageListParse(response: Response): List<Page> {
        val document = response.asJsoup()
        val page = mutableListOf<Page>()
        document.select("img.vimg.lazyload").map { element ->
            page.add(
                Page(element.childrenSize(), "", element.attr("data-src"))
            )
        }
        return page
    }

    override fun imageUrlParse(response: Response): String = throw Exception("Not Used")

    // Filters
    // 按照类别信息进行检索

    override fun getFilterList() = FilterList(
        LanguageFilter(),
        TimeFilter()
    )

    private class LanguageFilter : UriPartFilter(
        "语言",
        arrayOf(
            Pair("全部", "全部"),
            Pair("日本語新着", "/language/japanese/"),
            Pair("中国語新着", "/language/chinese/"),
            Pair("日本語人気", "/language/japanese/popular"),
            Pair("中国語人気", "/language/chinese/popular")
        )
    )

    private class TimeFilter : UriPartFilter2(
        "时间",
        arrayOf(
            Pair("全部", "全部"),
            Pair("週間", "/rank/week/"),
            Pair("月間", "/rank/month/")
        )
    )

    private open class UriPartFilter(
        displayName: String,
        val vals: Array<Pair<String, String>>,
        defaultValue: Int = 0
    ) :
        Filter.Select<String>(displayName, vals.map { it.first }.toTypedArray(), defaultValue) {
        open fun toUriPart() = vals[state].second
    }

    private open class UriPartFilter2(
        displayName: String,
        val vals: Array<Pair<String, String>>,
        defaultValue: Int = 0
    ) :
        Filter.Select<String>(displayName, vals.map { it.first }.toTypedArray(), defaultValue) {
        open fun toUriPart() = vals[state].second
    }

    // Long log
    private val MAX_LENGTH: Int = 3900

    fun debugLarge(tag: String?, content: String) {
        if (content.length > MAX_LENGTH) {
            var part = content.substring(0, MAX_LENGTH)
            Log.d(tag, part)
            part = content.substring(MAX_LENGTH)
            if (content.length - MAX_LENGTH > MAX_LENGTH) {
                debugLarge(tag, part)
            } else {
                Log.d(tag, part)
            }
        } else {
            Log.d(tag, content)
        }
    }

    // String match

    fun match(regex: String, input: String, group: Int): String? {
        try {
            val pattern: Pattern = Pattern.compile(regex)
            val matcher: Matcher = pattern.matcher(input)
            if (matcher.find()) {
                return matcher.group(group)!!.trim()
            }
        } catch (e: java.lang.Exception) {
            e.printStackTrace()
        }
        return null
    }
}
