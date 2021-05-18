package eu.kanade.tachiyomi.extension.zh.comicbus

import android.app.Application
import android.content.SharedPreferences
import android.util.Log
import com.luhuiguo.chinese.ChineseUtils
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.net.URLEncoder
import java.util.regex.Matcher
import java.util.regex.Pattern

class comicbus : ConfigurableSource, HttpSource() {
    override val name: String = "comicbus"
    override val lang: String = "zh"
    override val supportsLatest: Boolean = true
    override val baseUrl: String = "https://m.comicbus.com"
    private val apiUrl: String = "http://app.6comic.com:88"

    private var cid = ""
    private var path = ""

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    override fun headersBuilder() = super.headersBuilder()
        .add("Referer", baseUrl)

    // Popular

    override fun popularMangaRequest(page: Int): Request {
        return GET("$baseUrl/category/hot_comic-$page.html", headers)
    }

    override fun popularMangaParse(response: Response): MangasPage {
        return latestUpdatesParse(response)
    }

    // Latest

    override fun latestUpdatesRequest(page: Int): Request {
        return GET("$baseUrl/category/update_comic-$page.html", headers)
    }

    override fun latestUpdatesParse(response: Response): MangasPage {
        val document = asJsoupWithCharset(response)
        val mangasList = mutableListOf<SManga>()
        document.select(".picborder").map { element ->
            mangasList.add(
                SManga.create().apply {
                    var _title = element.select("a").attr("title")
                    if (preferences.getBoolean(SHOW_Simplified_Chinese_TITLE_PREF, false)) {
                        _title = ChineseUtils.toSimplified(_title)
                    }
                    title = _title
                    val uri = element.select("a").attr("href")
                    url = uri.substring(13, uri.length - 5)
                    val _img = element.select("img").attr("src").trim()
                    if (preferences.getBoolean(SHOW_Img_With_Api, false)) {
                        thumbnail_url = "$apiUrl/$_img"
                    } else thumbnail_url = "$baseUrl/$_img"
                }
            )
        }
        return MangasPage(mangasList, true)
    }

    // Search

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        var keyword = ChineseUtils.toTraditional(query)
        keyword = URLEncoder.encode(keyword, "big5")
        val queryuri = "$baseUrl/data/search.aspx?k=" + keyword + "&page=$page"
        return GET(queryuri, headers)
    }

    override fun searchMangaParse(response: Response): MangasPage {
        val document = response.asJsoup()
        val mangas = document.select(".picborder").map { element ->
            SManga.create().apply {
                val replaceWith = Regex("</?font.*?>")
                val titleorigin = element.select("a").attr("title")
                var _title = replaceWith.replace(titleorigin, "")
                if (preferences.getBoolean(SHOW_Simplified_Chinese_TITLE_PREF, false)) {
                    _title = ChineseUtils.toSimplified(_title)
                }
                title = _title
                val uri = element.select("a").attr("href")
                url = uri.substring(13, uri.length - 5)
                thumbnail_url = "$baseUrl/" + element.select("img").attr("src").trim()
            }
        }
        return MangasPage(mangas, false)
    }

    // Details

    override fun mangaDetailsRequest(manga: SManga): Request {
        return GET("$apiUrl/info/" + manga.url + ".html", headers)
    }

    override fun mangaDetailsParse(response: Response): SManga {
        val result = bodyWithCharset(response)
        val _result = result.split("\\|".toRegex()).toTypedArray()
        return SManga.create().apply {
            var _title = _result[4]
            if (preferences.getBoolean(SHOW_Simplified_Chinese_TITLE_PREF, false)) {
                _title = ChineseUtils.toSimplified(_title)
            }
            title = _title
            thumbnail_url = "$apiUrl/pics/0/" + _result[1] + ".jpg"
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
        return GET("$apiUrl/comic/" + manga.url + ".html", headers)
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
                    name = "该条目为动画"
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
        return GET("$apiUrl/comics/" + chapter.scanlator + ".html", headers)
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

    // Change Title to Simplified Chinese For Library Gobal Search Optionally
    override fun setupPreferenceScreen(screen: androidx.preference.PreferenceScreen) {
        val zhPreference = androidx.preference.CheckBoxPreference(screen.context).apply {
            key = SHOW_Simplified_Chinese_TITLE_PREF
            title = "将标题转换为简体中文"
            summary = "需要重启软件以生效。已添加漫画需要迁移改变标题。"

            setOnPreferenceChangeListener { _, newValue ->
                try {
                    val setting = preferences.edit().putBoolean(SHOW_Simplified_Chinese_TITLE_PREF, newValue as Boolean).commit()
                    setting
                } catch (e: Exception) {
                    e.printStackTrace()
                    false
                }
            }
        }
        val imgPreference = androidx.preference.CheckBoxPreference(screen.context).apply {
            key = SHOW_Img_With_Api
            title = "发现中漫画的封面使用api的值"
            summary = "可能会清楚一点，网页上的封面有点模糊"

            setOnPreferenceChangeListener { _, newValue ->
                try {
                    val setting = preferences.edit().putBoolean(SHOW_Img_With_Api, newValue as Boolean).commit()
                    setting
                } catch (e: Exception) {
                    e.printStackTrace()
                    false
                }
            }
        }
        screen.addPreference(zhPreference)
        screen.addPreference(imgPreference)
    }

    companion object {
        private const val SHOW_Simplified_Chinese_TITLE_PREF = "showSCTitle"
        private const val SHOW_Img_With_Api = "showImgApi"
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

    // asJsoup(big5)

    fun asJsoupWithCharset(response: Response): org.jsoup.nodes.Document {
        return Jsoup.parse(bodyWithCharset(response), response.request.url.toString())
    }
}
