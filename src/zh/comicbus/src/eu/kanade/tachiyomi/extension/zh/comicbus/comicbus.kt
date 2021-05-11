package eu.kanade.tachiyomi.extension.zh.comicbus

import android.net.Uri
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.ParsedHttpSource
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder

class comicbus : ParsedHttpSource() {
    override val name: String = "comicbus"
    override val lang: String = "zh"
    override val supportsLatest: Boolean = true
    override val baseUrl: String = "http://m.comicbus.com"
    override fun headersBuilder() = super.headersBuilder()
        .add("referer", baseUrl)
        .add("origin", baseUrl)

    // Popular

    override fun popularMangaRequest(page: Int): Request {
        return GET("$baseUrl/list/click/?page=$page", headers)
    }
    override fun popularMangaNextPageSelector(): String? = null
    override fun popularMangaSelector(): String = "li.list-comic"
    override fun popularMangaFromElement(element: Element): SManga = SManga.create().apply {
        title = element.select("a.txtA").text()
        setUrlWithoutDomain(element.select("a.txtA").attr("abs:href"))
        thumbnail_url = element.select("mip-img").attr("abs:src")
    }

    // Latest

    override fun latestUpdatesRequest(page: Int): Request {
        return GET("$baseUrl/list/update/?page=$page", headers)
    }
    override fun latestUpdatesNextPageSelector(): String? = null
    override fun latestUpdatesSelector(): String = popularMangaSelector()
    override fun latestUpdatesFromElement(element: Element): SManga = popularMangaFromElement(element)

    // Search

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        if (query.isNotBlank()) {
            val keyword = URLEncoder.encode(query, "big5")
            val queryuri = Uri.parse("$baseUrl/data/search.aspx").buildUpon()
            queryuri.appendQueryParameter("k", keyword).appendQueryParameter("page", page.toString())
            return GET(queryuri.toString(), headers)
        } else {
            val uri = Uri.parse(baseUrl).buildUpon()
            uri.appendPath("list")
            val pathBuilder = Uri.Builder()
            filters.forEach {
                if (it is UriFilter)
                    it.addToUri(pathBuilder)
            }
            val filterPath = pathBuilder.toString().replace("/", "-").removePrefix("-")
            uri.appendEncodedPath(filterPath)
                .appendEncodedPath("")
            return GET(uri.toString(), headers)
        }
    }

    override fun searchMangaNextPageSelector(): String? = null
    override fun searchMangaSelector(): String = ".cat_list td"
    override fun searchMangaFromElement(element: Element): SManga = SManga.create().apply {
        title = element.select(".pictitle").text()
        url = element.select(".picborder a").attr("abs:href").substring(13, -6)
        thumbnail_url = "http://m.comicbus.com/" + element.select(".picborder img").attr("abs:src").trim()
    }

    // Details

    override fun mangaDetailsRequest(manga: SManga): Request {
        return GET("http://app.6comic.com:88/info/" + manga.url + ".html")
    }
    override fun mangaDetailsParse(document: Document): SManga = SManga.create().apply {
        val _result = emptyArray<String>()
        for ((index, i) in document.toString().split("\\|").withIndex())
            _result[index] = i
        title = _result[4]
        thumbnail_url = "http://app.6comic.com:88/pics/0/" + _result[1] + ".jpg"
        description = _result[10].substring(2).trim()
        status = when (_result[7]) {
            "完結" -> SManga.COMPLETED
            else -> SManga.ONGOING
        }
    }

    // Chapters

    override fun chapterListRequest(manga: SManga): Request {
        return GET("http://app.6comic.com:88/comic/" + manga.url + ".html")
    }
    override fun chapterListSelector(): String = "."
    override fun chapterFromElement(element: Element): SChapter = SChapter.create().apply {
        url = element.select("a").attr("href")
        name = element.select("span").text()
    }
    override fun chapterListParse(response: Response): List<SChapter> {
        return super.chapterListParse(response).reversed()
    }

    // Pages

    override fun pageListParse(document: Document): List<Page> = mutableListOf<Page>().apply {
        val script = document.select("script:containsData(chapterImages )").html()
        val images = script.substringAfter("chapterImages = [\"").substringBefore("\"]").split("\",\"")
        val path = script.substringAfter("chapterPath = \"").substringBefore("\";")
        val server = script.substringAfter("pageImage = \"").substringBefore("/images/cover")
        images.forEach {
            add(Page(size, "", "$server/$path/$it"))
        }
    }
    override fun imageUrlParse(document: Document): String = throw Exception("Not Used")

    // Filters

    override fun getFilterList(): FilterList {
        return FilterList(
            Filter.Header("如果使用文本搜索"),
            Filter.Header("过滤器将被忽略"),
            typefilter(),
            regionfilter(),
            genrefilter(),
            letterfilter(),
            statusfilter()
        )
    }

    private class typefilter : UriSelectFilterPath(
        "按类型",
        "filtertype",
        arrayOf(
            Pair("", "全部"),
            Pair("shaonian", "少年漫画"),
            Pair("shaonv", "少女漫画"),
            Pair("qingnian", "青年漫画"),
            Pair("zhenrenmanhua", "真人漫画")
        )
    )

    private class regionfilter : UriSelectFilterPath(
        "按地区",
        "filterregion",
        arrayOf(
            Pair("", "全部"),
            Pair("ribenmanhua", "日本漫画"),
            Pair("guochanmanhua", "国产漫画"),
            Pair("gangtaimanhua", "港台漫画"),
            Pair("oumeimanhua", "欧美漫画"),
            Pair("hanguomanhua", "韩国漫画")
        )
    )

    private class genrefilter : UriSelectFilterPath(
        "按剧情",
        "filtergenre",
        arrayOf(
            Pair("", "全部"),
            Pair("maoxian", "冒险"),
            Pair("mofa", "魔法"),
            Pair("kehuan", "科幻"),
            Pair("kongbu", "恐怖"),
            Pair("lishi", "历史"),
            Pair("jingji", "竞技")
        )
    )

    private class letterfilter : UriSelectFilterPath(
        "按字母",
        "filterletter",
        arrayOf(
            Pair("", "全部"),
            Pair("a", "A"),
            Pair("b", "B"),
            Pair("c", "C"),
            Pair("d", "D"),
            Pair("e", "E"),
            Pair("f", "F"),
            Pair("g", "G"),
            Pair("h", "H"),
            Pair("i", "I"),
            Pair("j", "J"),
            Pair("k", "K"),
            Pair("l", "L"),
            Pair("m", "M"),
            Pair("n", "N"),
            Pair("o", "O"),
            Pair("p", "P"),
            Pair("q", "Q"),
            Pair("r", "R"),
            Pair("s", "S"),
            Pair("t", "T"),
            Pair("u", "U"),
            Pair("v", "V"),
            Pair("w", "W"),
            Pair("x", "X"),
            Pair("y", "Y"),
            Pair("z", "Z"),
            Pair("1", "其他")
        )
    )

    private class statusfilter : UriSelectFilterPath(
        "按进度",
        "filterstatus",
        arrayOf(
            Pair("", "全部"),
            Pair("wanjie", "已完结"),
            Pair("lianzai", "连载中")
        )
    )

    /**
     * Class that creates a select filter. Each entry in the dropdown has a name and a display name.
     * If an entry is selected it is appended as a query parameter onto the end of the URI.
     * If `firstIsUnspecified` is set to true, if the first entry is selected, nothing will be appended on the the URI.
     */
    // vals: <name, display>
    private open class UriSelectFilterPath(
        displayName: String,
        val uriParam: String,
        val vals: Array<Pair<String, String>>,
        val firstIsUnspecified: Boolean = true,
        defaultValue: Int = 0
    ) :
        Filter.Select<String>(displayName, vals.map { it.second }.toTypedArray(), defaultValue), UriFilter {
        override fun addToUri(uri: Uri.Builder) {
            if (state != 0 || !firstIsUnspecified)
                uri.appendPath(vals[state].first)
        }
    }

    /**
     * Represents a filter that is able to modify a URI.
     */
    private interface UriFilter {
        fun addToUri(uri: Uri.Builder)
    }
}
