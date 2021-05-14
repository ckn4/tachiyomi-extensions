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
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.Response
import java.net.URLEncoder

class comicbus : HttpSource() {
    override val name: String = "comicbus"
    override val lang: String = "zh"
    override val supportsLatest: Boolean = true
    override val baseUrl: String = "https://m.comicbus.com"

    // Popular

    override fun popularMangaRequest(page: Int): Request {
        return GET("$baseUrl/list/click/?page=$page", headers)
    }

    override fun popularMangaParse(response: Response): MangasPage {
        TODO("Not yet implemented")
    }

    // Latest

    override fun latestUpdatesRequest(page: Int): Request {
        return GET("$baseUrl/list/update/?page=$page", headers)
    }

    override fun latestUpdatesParse(response: Response): MangasPage {
        TODO("Not yet implemented")
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
//    override fun mangaDetailsParse(document: Document): SManga = SManga.create().apply {
//        val _result = emptyArray<String>()
//        for ((index, i) in document.toString().split("\\|").withIndex())
//            _result[index] = i
//        title = _result[4]
//        thumbnail_url = "http://app.6comic.com:88/pics/0/" + _result[1] + ".jpg"
//        description = _result[10].substring(2).trim()
//        status = when (_result[7]) {
//            "完結" -> SManga.COMPLETED
//            else -> SManga.ONGOING
//        }
//    }

    override fun mangaDetailsParse(response: Response): SManga {
        TODO("Not yet implemented")
    }

    // Chapters

    override fun chapterListRequest(manga: SManga): Request {
        return GET("http://app.6comic.com:88/comic/" + manga.url + ".html")
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        TODO("Not yet implemented")
    }

    // Pages

    override fun pageListRequest(chapter: SChapter): Request {
        val url = (baseUrl + chapter.url).toHttpUrlOrNull()!!.newBuilder()
            .addQueryParameter("netType", "4")
            .addQueryParameter("loadreal", "1")
            .addQueryParameter("imageQuality", "2")
            .build()
        return GET(url.toString())
    }

    override fun pageListParse(response: Response): List<Page> = mutableListOf<Page>().apply {
        val script = ""
        val images = script.substringAfter("chapterImages = [\"").substringBefore("\"]").split("\",\"")
        val path = script.substringAfter("chapterPath = \"").substringBefore("\";")
        val server = script.substringAfter("pageImage = \"").substringBefore("/images/cover")
        images.forEach {
            add(Page(size, "", "$server/$path/$it"))
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
}
