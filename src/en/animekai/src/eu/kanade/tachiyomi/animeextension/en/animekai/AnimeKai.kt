import eu.kanade.tachiyomi.multisrc.zorotheme.ZoroTheme
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.animesource.model.SAnime
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl

class AnimeKai : ZoroTheme(
    "en",
    "AnimeKai",
    "https://animekai.to",
) {
    // Hardcoded stable 64-bit source ID
    override val id: Long = 7537715367149829913L

    override val ajaxRoute = ""

    override fun episodeListRequest(anime: SAnime): Request {
        val id = anime.url.substringAfterLast("-")
        return GET("$baseUrl/ajax/episodes/list?ani_id=$id", headers)
    }
}