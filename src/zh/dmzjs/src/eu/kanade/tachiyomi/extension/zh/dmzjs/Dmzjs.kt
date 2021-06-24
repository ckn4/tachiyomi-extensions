package eu.kanade.tachiyomi.extension.zh.dmzjs

import android.app.Application
import android.content.SharedPreferences
import android.net.Uri
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.lib.ratelimit.SpecificHostRateLimitInterceptor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.asObservableSuccess
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import rx.Observable
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.net.URLEncoder
import java.util.ArrayList

/**
 * Dmzj source
 */

class Dmzjs : ConfigurableSource, HttpSource() {
    override val lang = "zh"
    override val supportsLatest = true
    override val name = "动漫之家S"
    override val baseUrl = "https://m.dmzj.com"
    private val v3apiUrl = "https://v3api.dmzj.com"
    private val apiUrl = "https://api.dmzj.com"
    private val oldPageListApiUrl = "https://m.dmzj.com/chapinfo"
    private val imageCDNUrl = "https://images.dmzj.com"

    private fun cleanUrl(url: String) = if (url.startsWith("//"))
        "https:$url"
    else url

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    private val v3apiRateLimitInterceptor = SpecificHostRateLimitInterceptor(
        v3apiUrl.toHttpUrlOrNull()!!,
        preferences.getString(API_RATELIMIT_PREF, "5")!!.toInt()
    )
    private val apiRateLimitInterceptor = SpecificHostRateLimitInterceptor(
        apiUrl.toHttpUrlOrNull()!!,
        preferences.getString(API_RATELIMIT_PREF, "5")!!.toInt()
    )
    private val imageCDNRateLimitInterceptor = SpecificHostRateLimitInterceptor(
        imageCDNUrl.toHttpUrlOrNull()!!,
        preferences.getString(IMAGE_CDN_RATELIMIT_PREF, "5")!!.toInt()
    )

    override val client: OkHttpClient = network.client.newBuilder()
        .addNetworkInterceptor(apiRateLimitInterceptor)
        .addNetworkInterceptor(v3apiRateLimitInterceptor)
        .addNetworkInterceptor(imageCDNRateLimitInterceptor)
        .build()

    override fun headersBuilder() = Headers.Builder().apply {
        set("Referer", "https://www.dmzj.com/")
        set(
            "User-Agent",
            "Mozilla/5.0 (Linux; Android 10) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/88.0.4324.93 " +
                "Mobile Safari/537.36 " +
                "Tachiyomi/1.0"
        )
    }

    // for simple searches (query only, no filters)
    private fun simpleSearchJsonParse(json: String): MangasPage {
        val arr = JSONArray(json)
        val ret = ArrayList<SManga>(arr.length())
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            val cid = obj.getString("id")
            ret.add(
                SManga.create().apply {
                    title = obj.getString("comic_name")
                    thumbnail_url = cleanUrl(obj.getString("comic_cover"))
                    author = obj.optString("comic_author")
                    url = "/comic/comic_$cid.json?version=2.7.019"
                }
            )
        }
        return MangasPage(ret, false)
    }

    // for popular, latest, and filtered search
    private fun mangaFromJSON(json: String): MangasPage {
        val arr = JSONArray(json)
        val ret = ArrayList<SManga>(arr.length())
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            val cid = obj.getString("id")
            ret.add(
                SManga.create().apply {
                    if (obj.has("name"))
                        title = obj.getString("name")
                    else title = obj.getString("title")
                    var cover = obj.getString("cover")
                    if (!cover.contains("http"))
                        cover = imageCDNUrl + '/' + cover
                    thumbnail_url = cover
                    author = obj.optString("authors")
                    status = when (obj.getString("status")) {
                        "已完结" -> SManga.COMPLETED
                        "连载中" -> SManga.ONGOING
                        else -> SManga.UNKNOWN
                    }
                    url = "/comic/comic_$cid.json?version=2.7.019"
                }
            )
        }
        return MangasPage(ret, arr.length() != 0)
    }

    override fun popularMangaRequest(page: Int) = GET("$v3apiUrl/classify/0/0/${page - 1}.json")

    override fun popularMangaParse(response: Response) = searchMangaParse(response)

    override fun latestUpdatesRequest(page: Int) = GET("$baseUrl/classify/0-0-0-1-1-${page - 1}.json")

    override fun latestUpdatesParse(response: Response): MangasPage = searchMangaParse(response)

    private fun searchMangaById(id: String): MangasPage {
        val comicNumberID = if (checkComicIdIsNumericalRegex.matches(id)) {
            id
        } else {
            val document = client.newCall(GET("$baseUrl/info/$id.html", headers)).execute().asJsoup()
            extractComicIdFromWebpageRegex.find(document.select("#Subscribe").attr("onclick"))!!.groups[1]!!.value // onclick="addSubscribe('{comicNumberID}')"
        }

        val sManga = try {
            val r = client.newCall(GET("$v3apiUrl/comic/comic_$comicNumberID.json", headers)).execute()
            mangaDetailsParse(r)
        } catch (_: Exception) {
            val r = client.newCall(GET("$apiUrl/dynamic/comicinfo/$comicNumberID.json", headers)).execute()
            mangaDetailsParse(r)
        }
        sManga.url = "$baseUrl/info/$comicNumberID.html"

        return MangasPage(listOf(sManga), false)
    }

    override fun fetchSearchManga(page: Int, query: String, filters: FilterList): Observable<MangasPage> {
        return if (query.startsWith(PREFIX_ID_SEARCH)) {
            // ID may be numbers or Chinese pinyin
            val id = query.removePrefix(PREFIX_ID_SEARCH).removeSuffix(".html")
            Observable.just(searchMangaById(id))
        } else {
            client.newCall(searchMangaRequest(page, query, filters))
                .asObservableSuccess()
                .map { response ->
                    searchMangaParse(response)
                }
        }
    }

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        if (query != "") {
            val uri = Uri.parse("http://s.acg.dmzj.com/comicsum/search.php").buildUpon()
            uri.appendQueryParameter("s", query)
            return GET(uri.toString())
        } else {
            var params = filters.map {
                if (it is UriPartFilter) {
                    it.toUriPart()
                } else ""
            }.filter { it != "" }.joinToString("-")
            if (params == "") {
                params = "0"
            }

            return GET("$baseUrl/classify/$params-${page - 1}.json")
        }
    }

    override fun searchMangaParse(response: Response): MangasPage {
        val body = response.body!!.string()

        return if (body.contains("g_search_data")) {
            simpleSearchJsonParse(body.substringAfter("=").trim().removeSuffix(";"))
        } else {
            mangaFromJSON(body)
        }
    }

    // Bypass mangaDetailsRequest, fetch api url directly
    override fun fetchMangaDetails(manga: SManga): Observable<SManga> {
        val cid = extractComicIdFromMangaUrlRegex.find(manga.url)!!.groups[1]!!.value
        return try {
            // Not using client.newCall().asObservableSuccess() to ensure we can catch exception here.
            val response = client.newCall(GET("$v3apiUrl/comic/comic_$cid.json", headers)).execute()
            val sManga = mangaDetailsParse(response).apply { initialized = true }
            Observable.just(sManga)
        } catch (e: Exception) {
            val response = client.newCall(GET("$apiUrl/dynamic/comicinfo/$cid.json", headers)).execute()
            val sManga = mangaDetailsParse(response).apply { initialized = true }
            Observable.just(sManga)
        } catch (e: Exception) {
            Observable.error(e)
        }
    }

    // Workaround to allow "Open in browser" use human readable webpage url.
    override fun mangaDetailsRequest(manga: SManga): Request {
        val cid = extractComicIdFromMangaUrlRegex.find(manga.url)!!.groups[1]!!.value
        return GET("$baseUrl/info/$cid.html")
    }

    override fun mangaDetailsParse(response: Response) = SManga.create().apply {
        val obj = JSONObject(response.body!!.string())

        if (response.request.url.toString().startsWith(v3apiUrl)) {
            title = obj.getString("title")
            thumbnail_url = obj.getString("cover")
            var arr = obj.getJSONArray("authors")
            val tmparr = ArrayList<String>(arr.length())
            for (i in 0 until arr.length()) {
                tmparr.add(arr.getJSONObject(i).getString("tag_name"))
            }
            author = tmparr.joinToString(", ")

            arr = obj.getJSONArray("types")
            tmparr.clear()
            for (i in 0 until arr.length()) {
                tmparr.add(arr.getJSONObject(i).getString("tag_name"))
            }
            genre = tmparr.joinToString(", ")
            status = when (obj.getJSONArray("status").getJSONObject(0).getInt("tag_id")) {
                2310 -> SManga.COMPLETED
                2309 -> SManga.ONGOING
                else -> SManga.UNKNOWN
            }

            description = obj.getString("description")
        } else {
            val data = obj.getJSONObject("data").getJSONObject("info")
            title = data.getString("title")
            thumbnail_url = data.getString("cover")
            author = data.getString("authors")
            genre = data.getString("types").replace("/", ", ")
            status = when (data.getString("status")) {
                "连载中" -> SManga.ONGOING
                "已完结" -> SManga.COMPLETED
                else -> SManga.UNKNOWN
            }
            description = data.getString("description")
        }
    }

    override fun chapterListRequest(manga: SManga): Request = throw UnsupportedOperationException("Not used.")

    override fun fetchChapterList(manga: SManga): Observable<List<SChapter>> {
        val cid = extractComicIdFromMangaUrlRegex.find(manga.url)!!.groups[1]!!.value
        return if (manga.status != SManga.LICENSED) {
            try {
                val response = client.newCall(GET("$v3apiUrl/comic/comic_$cid.json", headers)).execute()
                val sChapter = chapterListParse(response)
                Observable.just(sChapter)
            } catch (e: Exception) {
                val response = client.newCall(GET("$apiUrl/dynamic/comicinfo/$cid.json", headers)).execute()
                val sChapter = chapterListParse(response)
                Observable.just(sChapter)
            } catch (e: Exception) {
                Observable.error(e)
            }
        } else {
            Observable.error(Exception("Licensed - No chapters to show"))
        }
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val obj = JSONObject(response.body!!.string())
        val ret = ArrayList<SChapter>()

        if (response.request.url.toString().startsWith(v3apiUrl)) {
            val cid = obj.getString("id")
            val chaptersList = obj.getJSONArray("chapters")
            for (i in 0 until chaptersList.length()) {
                val chapterObj = chaptersList.getJSONObject(i)
                val chapterData = chapterObj.getJSONArray("data")
                val prefix = chapterObj.getString("title")
                for (j in 0 until chapterData.length()) {
                    val chapter = chapterData.getJSONObject(j)
                    ret.add(
                        SChapter.create().apply {
                            name = "$prefix: ${chapter.getString("chapter_title")}"
                            date_upload = chapter.getString("updatetime").toLong() * 1000 // milliseconds
                            url = "https://api.m.dmzj.com/comic/chapter/$cid/${chapter.getString("chapter_id")}.html"
                        }
                    )
                }
            }
        } else {
            // Fallback to old api
            val chaptersList = obj.getJSONObject("data").getJSONArray("list")
            for (i in 0 until chaptersList.length()) {
                val chapter = chaptersList.getJSONObject(i)
                ret.add(
                    SChapter.create().apply {
                        name = chapter.getString("chapter_name")
                        date_upload = chapter.getString("updatetime").toLong() * 1000
                        url = "$oldPageListApiUrl/${chapter.getString("comic_id")}/${chapter.getString("id")}.html"
                    }
                )
            }
        }
        return ret
    }

    override fun pageListRequest(chapter: SChapter) = GET(chapter.url, headers) // Bypass base url

    override fun pageListParse(response: Response): List<Page> {
        val arr = if (response.request.url.toString().startsWith(oldPageListApiUrl)) {
            JSONObject(response.body!!.string()).getJSONArray("page_url")
        } else {
            // some chapters are hidden and won't return a JSONObject from api.m.dmzj, have to get them through v3api (but images won't be as HQ)
            try {
                val obj = JSONObject(response.body!!.string())
                obj.getJSONObject("chapter").getJSONArray("page_url") // api.m.dmzj.com already return HD image url
            } catch (_: Exception) {
                // example url: http://v3api.dmzj.com/chapter/44253/101852.json
                val url = response.request.url.toString()
                    .replace("api.m", "v3api")
                    .replace("comic/", "")
                    .replace(".html", ".json")
                val obj = client.newCall(GET(url, headers)).execute().let { JSONObject(it.body!!.string()) }
                obj.getJSONArray("page_url_hd") // page_url in v3api.dmzj.com will return compressed image, page_url_hd will return HD image url as api.m.dmzj.com does.
            } catch (_: Exception) {
                // Fallback to old api
                // example url: https://m.dmzj.com/chapinfo/44253/101852.html
                val url = response.request.url.toString()
                    .replaceFirst("api.", "")
                    .replaceFirst(".dmzj.", ".dmzj.")
                    .replaceFirst("comic/chapter", "chapinfo")
                val obj = client.newCall(GET(url, headers)).execute().let { JSONObject(it.body!!.string()) }
                obj.getJSONArray("page_url")
            }
        }
        val ret = ArrayList<Page>(arr.length())
        for (i in 0 until arr.length()) {
            ret.add(
                Page(i, "", arr.getString(i).replace("http:", "https:").replace("dmzj.com", "dmzj.com"))
            )
        }
        return ret
    }

    private fun String.encoded(): String {
        return this.chunked(1)
            .joinToString("") { if (it in setOf("%", " ", "+", "#")) URLEncoder.encode(it, "UTF-8") else it }
            .let { if (it.endsWith(".jp")) "${it}g" else it }
    }

    override fun imageRequest(page: Page): Request {
        return GET(page.imageUrl!!.encoded(), headers)
    }

    // Unused, we can get image urls directly from the chapter page
    override fun imageUrlParse(response: Response) =
        throw UnsupportedOperationException("This method should not be called!")

    override fun getFilterList() = FilterList(
        GenreGroup(),
        ReaderFilter(),
        StatusFilter(),
        TypeFilter(),
        SortFilter()
    )

    private class GenreGroup : UriPartFilter(
        "分类",
        arrayOf(
            Pair("全部", "0"),
            Pair("冒险", "1"),
            Pair("欢乐向", "2"),
            Pair("格斗", "3"),
            Pair("科幻", "4"),
            Pair("爱情", "5"),
            Pair("竞技", "6"),
            Pair("魔法", "7"),
            Pair("校园", "8"),
            Pair("悬疑", "9"),
            Pair("恐怖", "10"),
            Pair("生活亲情", "11"),
            Pair("百合", "12"),
            Pair("伪娘", "13"),
            Pair("耽美", "14"),
            Pair("后宫", "15"),
            Pair("萌系", "16"),
            Pair("治愈", "17"),
            Pair("武侠", "18"),
            Pair("职场", "19"),
            Pair("奇幻", "20"),
            Pair("节操", "21"),
            Pair("轻小说", "22"),
            Pair("搞笑", "23")
        )
    )

    private class StatusFilter : UriPartFilter(
        "连载状态",
        arrayOf(
            Pair("全部", "0"),
            Pair("连载", "1"),
            Pair("完结", "2")
        )
    )

    private class TypeFilter : UriPartFilter(
        "地区",
        arrayOf(
            Pair("全部", "0"),
            Pair("日本", "1"),
            Pair("内地", "2"),
            Pair("欧美", "3"),
            Pair("港台", "4"),
            Pair("韩国", "5"),
            Pair("其他", "6")
        )
    )

    private class SortFilter : UriPartFilter(
        "排序",
        arrayOf(
            Pair("人气", "0"),
            Pair("更新", "1")
        )
    )

    private class ReaderFilter : UriPartFilter(
        "读者",
        arrayOf(
            Pair("全部", "0"),
            Pair("少年", "1"),
            Pair("少女", "2"),
            Pair("青年", "3")
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

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val apiRateLimitPreference = ListPreference(screen.context).apply {
            key = API_RATELIMIT_PREF
            title = API_RATELIMIT_PREF_TITLE
            summary = API_RATELIMIT_PREF_SUMMARY
            entries = ENTRIES_ARRAY
            entryValues = ENTRIES_ARRAY

            setDefaultValue("5")
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

        val imgCDNRateLimitPreference = ListPreference(screen.context).apply {
            key = IMAGE_CDN_RATELIMIT_PREF
            title = IMAGE_CDN_RATELIMIT_PREF_TITLE
            summary = IMAGE_CDN_RATELIMIT_PREF_SUMMARY
            entries = ENTRIES_ARRAY
            entryValues = ENTRIES_ARRAY

            setDefaultValue("5")
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

        screen.addPreference(apiRateLimitPreference)
        screen.addPreference(imgCDNRateLimitPreference)
    }

    companion object {
        private const val API_RATELIMIT_PREF = "apiRatelimitPreference"
        private const val API_RATELIMIT_PREF_TITLE = "主站每秒连接数限制" // "Ratelimit permits per second for main website"
        private const val API_RATELIMIT_PREF_SUMMARY = "此值影响向动漫之家网站发起连接请求的数量。调低此值可能减少发生HTTP 429（连接请求过多）错误的几率，但加载速度也会变慢。需要重启软件以生效。\n当前值：%s" // "This value affects network request amount to dmzj's url. Lower this value may reduce the chance to get HTTP 429 error, but loading speed will be slower too. Tachiyomi restart required. Current value: %s"

        private const val IMAGE_CDN_RATELIMIT_PREF = "imgCDNRatelimitPreference"
        private const val IMAGE_CDN_RATELIMIT_PREF_TITLE = "图片CDN每秒连接数限制" // "Ratelimit permits per second for image CDN"
        private const val IMAGE_CDN_RATELIMIT_PREF_SUMMARY = "此值影响加载图片时发起连接请求的数量。调低此值可能减小图片加载错误的几率，但加载速度也会变慢。需要重启软件以生效。\n当前值：%s" // "This value affects network request amount for loading image. Lower this value may reduce the chance to get error when loading image, but loading speed will be slower too. Tachiyomi restart required. Current value: %s"

        private val extractComicIdFromWebpageRegex = Regex("""addSubscribe\((\d+)\)""")
        private val checkComicIdIsNumericalRegex = Regex("""^\d+$""")
        private val extractComicIdFromMangaUrlRegex = Regex("""(\d+)\.(json|html)""") // Get comic ID from manga.url

        private val ENTRIES_ARRAY = (1..10).map { i -> i.toString() }.toTypedArray()
        const val PREFIX_ID_SEARCH = "id:"
    }
}
