package eu.kanade.tachiyomi.extension.ja.mangaraws

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.ParsedHttpSource
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Protocol
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.util.regex.Pattern

class MangaRaws : ParsedHttpSource() {
    override val name = "MangaRaws"
    override val baseUrl = "https://mangaraw.co"

    override val lang = "ja"

    override val supportsLatest = true

    override val client: OkHttpClient = network.cloudflareClient.newBuilder()
        .protocols(listOf(Protocol.HTTP_1_1))
        .build()

    override fun headersBuilder(): Headers.Builder {
        return super.headersBuilder().add("Referer", baseUrl)
    }

    override fun popularMangaRequest(page: Int) = GET("$baseUrl/top/?page=$page", headers)

    override fun popularMangaSelector() = ".rotate-img"

    override fun popularMangaFromElement(element: Element) = SManga.create().apply {
        setUrlWithoutDomain(element.select("a:has(img)").attr("href"))
        title = element.select("img").attr("alt").substringBefore("(Raw – Free)").trim()
        if (title.endsWith("(RAW – Free)"))
            title = title.substringBefore("(RAW – Free)").trim()
        thumbnail_url = baseUrl + element.select("img").attr("data-src")
    }

    override fun popularMangaNextPageSelector() = ".nextpostslink"

    override fun latestUpdatesRequest(page: Int) = GET("$baseUrl/page/$page", headers)

    override fun latestUpdatesSelector() = popularMangaSelector()

    override fun latestUpdatesFromElement(element: Element) = popularMangaFromElement(element)

    override fun latestUpdatesNextPageSelector() = popularMangaNextPageSelector()

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList) = GET("$baseUrl/?s=$query&page=$page", headers)

    override fun searchMangaSelector() = popularMangaSelector()

    override fun searchMangaFromElement(element: Element) = popularMangaFromElement(element)

    override fun searchMangaNextPageSelector() = popularMangaNextPageSelector()

    override fun mangaDetailsParse(document: Document) = SManga.create().apply {
        // genre = document.select("p:has(strong)").joinToString { it.text() }
        // description = document.select("p:has(strong)").first().text()
        description = document.select("p:has(strong)").joinToString { it.text() }
        thumbnail_url = document.select("p:has(img) img").attr("src")
    }

    override fun chapterListSelector() = ".list-scoll a"

    override fun chapterFromElement(element: Element) = SChapter.create().apply {
        setUrlWithoutDomain(element.attr("href"))
        val Name = element.text().trim()
        name = Pattern.compile("[^0-9.]").matcher(Name).replaceAll("")
    }

    override fun pageListParse(document: Document): List<Page> {
        return document.select(".card-wrap > img").mapIndexed { i, element ->
            val attribute = if (element.hasAttr("data-src")) "data-src" else "src"
            Page(i, "", element.attr(attribute))
        }
    }

    override fun imageUrlParse(document: Document): String = throw UnsupportedOperationException("Not Used")

    override fun getFilterList() = FilterList()
}
