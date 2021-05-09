package eu.kanade.tachiyomi.extension.ja.mangaraws

import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.SourceFactory

class MangaRawFactory : SourceFactory {
    override fun createSources(): List<Source> = listOf(
        //Manga1000(),
        Manga1001()
    )
}

//class Manga1000 : MangaRaws("Manga1000s", "https://manga1000.com")
class Manga1001 : MangaRaws("Manga1001s", "https://manga1001.com")
