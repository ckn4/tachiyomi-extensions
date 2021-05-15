package eu.kanade.tachiyomi.extension.zh.comicbus

import android.util.Log
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Request
import okhttp3.Response
import java.net.URLEncoder
import java.util.regex.Matcher
import java.util.regex.Pattern

class comicbus : HttpSource() {
    override val name: String = "comicbus"
    override val lang: String = "zh"
    override val supportsLatest: Boolean = true
    override val baseUrl: String = "https://m.comicbus.com"

    private var cid = ""
    private var path = ""

    override fun headersBuilder() = super.headersBuilder()
        .add("Referer", baseUrl)

    // Popular

    override fun popularMangaRequest(page: Int): Request {
        return GET("http://app.6comic.com:88/i/hot-$page.html", headers)
    }

    override fun popularMangaParse(response: Response): MangasPage {
        return latestUpdatesParse(response)
    }

    // Latest

    override fun latestUpdatesRequest(page: Int): Request {
        return GET("http://app.6comic.com:88/i/update-$page.html", headers)
    }

    override fun latestUpdatesParse(response: Response): MangasPage {
        val html = bodyWithCharset(response)
        val result: Array<String> = html.split("\\|".toRegex()).toTypedArray()
        val mangas = mutableListOf<SManga>()
        for (i in result.indices) {
            val _url = match("(\\d+),,.*?,.*?,([^,]+).*?", result[i], 1)
            val _title = match("(\\d+),,.*?,.*?,([^,]+).*?", result[i], 2)
            mangas.add(
                SManga.create().apply {
                    url = _url!!
                    title = _title!!
                    thumbnail_url = "http://app.6comic.com:88/pics/0/$url.jpg"
                }
            )
        }
        return MangasPage(mangas, false)
    }

    // Search

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val keyword = URLEncoder.encode(query, "big5")
        val queryuri = "$baseUrl/data/search.aspx?k=" + keyword + "&page=$page"
        return GET(queryuri, headers)
    }

    override fun searchMangaParse(response: Response): MangasPage {
        val document = response.asJsoup()
        val mangas = document.select(".picborder").map { element ->
            SManga.create().apply {
                val replaceWith = Regex("</?font.*?>")
                val titleorigin = element.select("a").attr("title")
                title = replaceWith.replace(titleorigin, "")
                val uri = element.select("a").attr("href")
                url = uri.substring(13, uri.length - 5)
                thumbnail_url = "https://m.comicbus.com/" + element.select("img").attr("src").trim()
            }
        }
        return MangasPage(mangas, false)
    }

    // Details

    override fun mangaDetailsRequest(manga: SManga): Request {
        return GET("http://app.6comic.com:88/info/" + manga.url + ".html")
    }

    override fun mangaDetailsParse(response: Response): SManga {
        val result = bodyWithCharset(response)
        val _result = result.split("\\|".toRegex()).toTypedArray()
        return SManga.create().apply {
            title = _result[4]
            thumbnail_url = "http://app.6comic.com:88/pics/0/" + _result[1] + ".jpg"
            description = _result[10].substring(2).trim()
            if (_result[8].indexOf("&#") == -1) {
                author = _result[8]
            }
            genre = _result[9].replace("\\s\\d+:\\d+:\\d+".toRegex(), "")
            status = when (_result[7]) {
                "完" -> SManga.COMPLETED
                "連載中" -> SManga.ONGOING
                else -> SManga.UNKNOWN
            }
        }
    }

    // Chapters

    override fun chapterListRequest(manga: SManga): Request {
        return GET("http://app.6comic.com:88/comic/" + manga.url + ".html")
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        var result = bodyWithCharset(response)
        var ourl = response.request.url.toString()
        ourl = ourl.substring(31, ourl.length - 5)
        if (match("(<!--ch-->).+", result, 1) != null) {
            result = result.replace("<!--ch-->".toRegex(), "|")
        }
        if (result.indexOf("|") == 2) {
            result = result.substring(3)
        }
        val chapters = mutableListOf<SChapter>()
        if (result.contains("|")) {
            val _result = result.split("\\|".toRegex()).toTypedArray()
            for (m in _result.indices) {
                val _name = match("\\d+ (.*)", _result[m], 1)
                if (_name != null) {
                    chapters.add(
                        SChapter.create().apply {
                            name = _name
                            url = m.toString()
                            scanlator = ourl
                        }
                    )
                }
            }
        } else {
            chapters.add(
                SChapter.create().apply {
                    name = "为动画"
                    url = ""
                }
            )
        }
        chapters.reverse()
        return chapters
    }

    // Pages
    override fun pageListRequest(chapter: SChapter): Request {
        cid = chapter.scanlator!!
        path = chapter.url
        return GET("http://app.6comic.com:88/comics/" + chapter.scanlator + ".html")
    }

    override fun pageListParse(response: Response): List<Page> {
        val html = bodyWithCharset(response)
        val rresult: Array<String> = html.split("\\|".toRegex()).toTypedArray()
        val num: Int = path.toInt()
        val result = rresult[num]
        val r = result.split(" ".toRegex()).toTypedArray()
        val name = r[0]
        val imgserver = r[1]
        val name1 = r[2]
        val pagenum = r[3].toInt()
        val last = r[4]
        return mutableListOf<Page>().apply {
            for (i in 0..pagenum) {
                val last1 = last.substring(i % 100 / 10 + 3 * (i % 10), i % 100 / 10 + 3 + 3 * (i % 10))
                add(Page(size, "", "http://img" + imgserver + ".8comic.com/" + name1 + "/" + cid + "/" + name + "/" + String.format("%03d", i + 1) + "_" + last1 + ".jpg"))
            }
        }
    }

    override fun imageUrlParse(response: Response): String = throw Exception("Not Used")

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

    // decode(big5)

    fun bodyWithCharset(response: Response): String {
        val htmlBytes: ByteArray = response.body!!.bytes()
        return String(htmlBytes, charset("big5"))
    }
}
