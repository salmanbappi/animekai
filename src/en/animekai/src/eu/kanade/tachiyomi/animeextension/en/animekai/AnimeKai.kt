import eu.kanade.tachiyomi.multisrc.zorotheme.ZoroTheme
import eu.kanade.tachiyomi.multisrc.zorotheme.dto.HtmlResponse
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.util.parseAs
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Element

class AnimeKai : ZoroTheme(
    "en",
    "AnimeKai",
    "https://animekai.to",
) {
    override val id: Long = 7537715367149829913L

    override val ajaxRoute = ""

    override fun episodeListRequest(anime: SAnime): Request {
        val id = anime.url.substringAfterLast("-")
        return GET("$baseUrl/ajax/episode/list?id=$id", apiHeaders(baseUrl + anime.url))
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val res = response.parseAs<HtmlResponse>()
        val document = res.getHtml()

        return document.select(episodeListSelector())
            .map(::episodeFromElement)
            .reversed()
    }

    private fun apiHeaders(referer: String) = headers.newBuilder()
        .add("Accept", "*/*")
        .add("X-Requested-With", "XMLHttpRequest")
        .add("Referer", referer)
        .build()
}
