package eu.kanade.tachiyomi.extension.zh.comicbus

import android.app.Application
import android.content.SharedPreferences
import android.util.Log
import com.luhuiguo.chinese.ChineseUtils
import eu.kanade.tachiyomi.lib.ratelimit.SpecificHostRateLimitInterceptor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.regex.Matcher
import java.util.regex.Pattern

class comicbus : ConfigurableSource, HttpSource() {
    override val name: String = "comicbus"
    override val lang: String = "zh"
    override val supportsLatest: Boolean = true
    override val baseUrl: String = "https://m.comicbus.com"
    private val apiUrl: String = "http://app.6comic.com:88"
    private val imageServer = arrayOf("https://img1.8comic.com", "https://img4.8comic.com", "https://img8.8comic.com")

    private var cid = ""
    private var path = ""

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    private val mainSiteRateLimitInterceptor = SpecificHostRateLimitInterceptor(baseUrl.toHttpUrlOrNull()!!, preferences.getString(MAINSITE_RATELIMIT_PREF, "1")!!.toInt())
    private val apiRateLimitInterceptor = SpecificHostRateLimitInterceptor(apiUrl.toHttpUrlOrNull()!!, preferences.getString(API_RATELIMIT_PREF, "2")!!.toInt())
    private val imageCDNRateLimitInterceptor1 = SpecificHostRateLimitInterceptor(imageServer[0].toHttpUrlOrNull()!!, preferences.getString(IMAGE_CDN_RATELIMIT_PREF, "2")!!.toInt())
    private val imageCDNRateLimitInterceptor2 = SpecificHostRateLimitInterceptor(imageServer[1].toHttpUrlOrNull()!!, preferences.getString(IMAGE_CDN_RATELIMIT_PREF, "2")!!.toInt())
    private val imageCDNRateLimitInterceptor3 = SpecificHostRateLimitInterceptor(imageServer[2].toHttpUrlOrNull()!!, preferences.getString(IMAGE_CDN_RATELIMIT_PREF, "2")!!.toInt())

    override val client: OkHttpClient = network.client.newBuilder()
        .addNetworkInterceptor(apiRateLimitInterceptor)
        .addNetworkInterceptor(mainSiteRateLimitInterceptor)
        .addNetworkInterceptor(imageCDNRateLimitInterceptor1)
        .addNetworkInterceptor(imageCDNRateLimitInterceptor2)
        .addNetworkInterceptor(imageCDNRateLimitInterceptor3)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    override fun headersBuilder() = super.headersBuilder()
        .add("Referer", baseUrl)
    // .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/88.0.4324.150 Safari/537.36 Edg/88.0.705.63")

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
                    url = uri.substring(7, uri.length - 5)
                    val _img = element.select("img").attr("src").trim()
                    val reg = "/(\\d+)m\\.jpg"
                    val m = match(reg, _img, 1)
                    if (preferences.getBoolean(SHOW_Img_With_Api, false)) {
                        thumbnail_url = "$apiUrl/$_img"
                    } else thumbnail_url = "$baseUrl/$_img"
                    val reg2 = Regex("/\\d+m\\.jpg")
                    thumbnail_url = thumbnail_url?.replace(reg2, "/$m\\.jpg")
                }
            )
        }
        return MangasPage(mangasList, true)
    }

    // Search

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        var keyword = query
        if (preferences.getBoolean(SERACH_WITH_TRAN, false)) {
            keyword = ChineseUtils.toTraditional(keyword)
        }
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
                url = uri.substring(7, uri.length - 5)
                val _img = element.select("img").attr("src").trim()
                val reg = "/(\\d+)m\\.jpg"
                val m = match(reg, _img, 1)
                thumbnail_url = "$baseUrl/" + _img
                val reg2 = Regex("/\\d+m\\.jpg")
                thumbnail_url = thumbnail_url?.replace(reg2, "/$m\\.jpg")
            }
        }
        return MangasPage(mangas, false)
    }

    // Details

    override fun mangaDetailsRequest(manga: SManga): Request {
        return GET("$baseUrl/comic/${manga.url}.html", headers)
    }

    override fun mangaDetailsParse(response: Response): SManga {
        val document = asJsoupWithCharset(response)
        return SManga.create().apply {
            var _title = document.select(".info > .title").text()
            if (preferences.getBoolean(SHOW_Simplified_Chinese_TITLE_PREF, false)) {
                _title = ChineseUtils.toSimplified(_title)
            }
            title = _title
            val _pic = document.select(".cover img").attr("src")
            val reg = "/(\\d+)m\\.jpg"
            val m = match(reg, _pic, 1)
            thumbnail_url = "$apiUrl$_pic"
            val reg2 = Regex("/\\d+m\\.jpg")
            thumbnail_url = thumbnail_url?.replace(reg2, "/$m\\.jpg")
            description = document.select(".item_show_detail .full_text").text().substring(2).trim()
            val _author = document.select("p:contains(作者)").eq(0).text()
            author = match("作者(.*)", _author, 1)
            var _status = document.select("p:contains(人氣)").eq(0).text()
            _status = match("完|連載中", _status, 0)
            status = when (_status) {
                "完" -> SManga.COMPLETED
                "連載中" -> SManga.ONGOING
                else -> SManga.UNKNOWN
            }
        }
    }

    // Chapters

    override fun chapterListRequest(manga: SManga): Request {
        return GET("$baseUrl/comic/${manga.url}.html", headers)
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val result = asJsoupWithCharset(response)
        val date = parseDate(result.select("p:contains(作者)").first())
        val chapter = mutableListOf<SChapter>()
        val chapters = mutableListOf<SChapter>()
        result.select("#div_li2 td:has(a)").map { element ->
            val _class = element.select("a").attr("class")
            if (_class == "Vol" || _class == "Ch") {
                val id = element.select("a").attr("id")
                val _fanwai = match("8\\d{3}", id, 0)
                if (_fanwai != null) {
                    chapter.add(
                        SChapter.create().apply {
                            name = element.select("a").text()
                            url = id.substring(1)
                            scanlator = match("'(.*)-\\d+", element.select("a").attr("onclick"), 1)
                        }
                    )
                } else {
                    chapters.add(
                        SChapter.create().apply {
                            name = element.select("a").text()
                            url = id.substring(1)
                            scanlator = match("'(.*)-\\d+", element.select("a").attr("onclick"), 1)
                        }
                    )
                }
            }
        }
        chapters.addAll(chapter)
        if (chapters.isEmpty()) {
            chapters.add(
                SChapter.create().apply {
                    name = "该条目为动画"
                    url = ""
                }
            )
        }
        chapters.first().date_upload = date
        return chapters
    }

    // Pages
    override fun pageListRequest(chapter: SChapter): Request {
        cid = chapter.scanlator!!
        path = chapter.url
        return GET("$apiUrl/comics/${chapter.scanlator}.html", headers)
    }

    override fun pageListParse(response: Response): List<Page> {
        val html = bodyWithCharset(response)
        val rresult: Array<String> = html.split("\\|".toRegex()).toTypedArray()
        val num: Int = path.toInt()
        var result = ""
        if (num == 0) {
            result = rresult[num]
        } else if (num >= 8000) {
            result = rresult[rresult.size - (num - 8000)]
        } else {
            result = rresult[num - 1]
        }
        val r = result.split(" ".toRegex()).toTypedArray()
        val name = r[0]
        val imgserver = r[1]
        val name1 = r[2]
        val pagenum = r[3].toInt()
        val last = r[4]
        return mutableListOf<Page>().apply {
            for (i in 0..pagenum - 1) {
                val last1 = last.substring(i % 100 / 10 + 3 * (i % 10), i % 100 / 10 + 3 + 3 * (i % 10))
                add(Page(size, "", "https://img" + imgserver + ".8comic.com/" + name1 + "/" + cid + "/" + name + "/" + String.format("%03d", i + 1) + "_" + last1 + ".jpg"))
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

        val searchPreference = androidx.preference.CheckBoxPreference(screen.context).apply {
            key = SERACH_WITH_TRAN
            title = "搜索是否自动转换为繁体"
            summary = "否则可能需要手动输入繁体来搜索，有些条目搜索不出来请尝试关闭此项"

            setOnPreferenceChangeListener { _, newValue ->
                try {
                    val setting = preferences.edit().putBoolean(SERACH_WITH_TRAN, newValue as Boolean).commit()
                    setting
                } catch (e: Exception) {
                    e.printStackTrace()
                    false
                }
            }
        }

        val mainSiteRateLimitPreference = androidx.preference.ListPreference(screen.context).apply {
            key = MAINSITE_RATELIMIT_PREF
            title = MAINSITE_RATELIMIT_PREF_TITLE
            entries = ENTRIES_ARRAY
            entryValues = ENTRIES_ARRAY
            summary = MAINSITE_RATELIMIT_PREF_SUMMARY

            setDefaultValue("1")
            setOnPreferenceChangeListener { _, newValue ->
                try {
                    val setting = preferences.edit().putString(MAINSITE_RATELIMIT_PREF, newValue as String).commit()
                    setting
                } catch (e: Exception) {
                    e.printStackTrace()
                    false
                }
            }
        }

        val imgCDNRateLimitPreference = androidx.preference.ListPreference(screen.context).apply {
            key = IMAGE_CDN_RATELIMIT_PREF
            title = IMAGE_CDN_RATELIMIT_PREF_TITLE
            entries = ENTRIES_ARRAY
            entryValues = ENTRIES_ARRAY
            summary = IMAGE_CDN_RATELIMIT_PREF_SUMMARY

            setDefaultValue("2")
            setOnPreferenceChangeListener { _, newValue ->
                try {
                    val setting = preferences.edit().putString(IMAGE_CDN_RATELIMIT_PREF, newValue as String).commit()
                    setting
                } catch (e: Exception) {
                    e.printStackTrace()
                    false
                }
            }
        }
        val apiRatelimitPreference = androidx.preference.ListPreference(screen.context).apply {
            key = API_RATELIMIT_PREF
            title = API_RATELIMIT_PREF_TITLE
            entries = ENTRIES_ARRAY
            entryValues = ENTRIES_ARRAY
            summary = API_RATELIMIT_PREF_SUMMARY

            setDefaultValue("2")
            setOnPreferenceChangeListener { _, newValue ->
                try {
                    val setting = preferences.edit().putString(API_RATELIMIT_PREF, newValue as String).commit()
                    setting
                } catch (e: Exception) {
                    e.printStackTrace()
                    false
                }
            }
        }

        screen.addPreference(zhPreference)
        screen.addPreference(imgPreference)
        screen.addPreference(searchPreference)
        screen.addPreference(mainSiteRateLimitPreference)
        screen.addPreference(imgCDNRateLimitPreference)
        screen.addPreference(apiRatelimitPreference)
    }

    companion object {
        private const val SHOW_Simplified_Chinese_TITLE_PREF = "showSCTitle"
        private const val SHOW_Img_With_Api = "showImgApi"
        private const val SERACH_WITH_TRAN = "searchWithTran"

        private const val MAINSITE_RATELIMIT_PREF = "mainSiteRatelimitPreference"
        private const val MAINSITE_RATELIMIT_PREF_TITLE = "主站每秒连接数限制" // "Ratelimit permits per second for main website"
        private const val MAINSITE_RATELIMIT_PREF_SUMMARY = "此值影响更新书架时发起连接请求的数量。调低此值可能减小IP被屏蔽的几率，但加载速度也会变慢。需要重启软件以生效。\n当前值：%s" // "This value affects network request amount for updating library. Lower this value may reduce the chance to get IP Ban, but loading speed will be slower too. Tachiyomi restart required."

        private const val API_RATELIMIT_PREF = "apiRatelimitPreference"
        private const val API_RATELIMIT_PREF_TITLE = "API每秒连接数限制" // "Ratelimit permits per second for main website"
        private const val API_RATELIMIT_PREF_SUMMARY = "此值影响更新书架时发起连接请求的数量。调低此值可能减小IP被屏蔽的几率，但加载速度也会变慢。需要重启软件以生效。\n当前值：%s" // "This value affects network request amount for updating library. Lower this value may reduce the chance to get IP Ban, but loading speed will be slower too. Tachiyomi restart required."

        private const val IMAGE_CDN_RATELIMIT_PREF = "imgCDNRatelimitPreference"
        private const val IMAGE_CDN_RATELIMIT_PREF_TITLE = "图片CDN每秒连接数限制" // "Ratelimit permits per second for image CDN"
        private const val IMAGE_CDN_RATELIMIT_PREF_SUMMARY = "此值影响加载图片时发起连接请求的数量。调低此值可能减小IP被屏蔽的几率，但加载速度也会变慢。需要重启软件以生效。\n当前值：%s" // "This value affects network request amount for loading image. Lower this value may reduce the chance to get IP Ban, but loading speed will be slower too. Tachiyomi restart required."

        private val ENTRIES_ARRAY = (1..10).map { i -> i.toString() }.toTypedArray()
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

    private fun parseDate(element: Element): Long = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).parse(match("\\d{4}-\\d{2}-\\d{2}", element.text(), 0)!!)?.time ?: 0
}
