package eu.kanade.tachiyomi.animeextension.en.animekai

import eu.kanade.tachiyomi.animeextension.BuildConfig
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.megacloudextractor.MegaCloudExtractor
import eu.kanade.tachiyomi.multisrc.zorotheme.ZoroTheme
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.animesource.model.SAnime
import okhttp3.Request

class AnimeKai : ZoroTheme(
    "en",
    "AnimeKai",
    "https://animekai.to",
    hosterNames = listOf(
        "VidSrc",
        "MegaCloud",
    ),
) {
    // Hardcoded stable 64-bit source ID
    override val id: Long = 7537715367149829913L

    override val ajaxRoute = ""
    override fun episodeListRequest(anime: SAnime): Request {
        val id = anime.url.substringAfterLast("-")
        return GET("$baseUrl/ajax/episode/list?animeId=$id", headers)
    }

    private val megaCloudExtractor by lazy { MegaCloudExtractor(client, headers, BuildConfig.MEGACLOUD_API) }

    override fun extractVideo(server: VideoData): List<Video> {
        return when (server.name) {
            "VidSrc", "MegaCloud" -> megaCloudExtractor.getVideosFromUrl(server.link, server.type, server.name)
            else -> emptyList()
        }
    }
}
