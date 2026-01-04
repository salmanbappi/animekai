package eu.kanade.tachiyomi.animeextension.en.animekai

import eu.kanade.tachiyomi.animeextension.BuildConfig
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.megacloudextractor.MegaCloudExtractor
import eu.kanade.tachiyomi.lib.rapidcloudextractor.RapidCloudExtractor
import eu.kanade.tachiyomi.multisrc.zorotheme.ZoroTheme
import eu.kanade.tachiyomi.network.GET
import okhttp3.Request
import okhttp3.Response

class AnimeKai : ZoroTheme(
    "en",
    "AnimeKai",
    "https://animekai.to",
    hosterNames = listOf(
        "MegaCloud",
        "RapidCloud",
    ),
) {
    override val id: Long = 7537715367149829913L

    override val ajaxRoute = "/v2"

    private val megaCloudExtractor by lazy {
        MegaCloudExtractor(client, headers, BuildConfig.MEGACLOUD_API)
    }

    private val rapidCloudExtractor by lazy {
        RapidCloudExtractor(client, headers, preferences)
    }

        override fun extractVideo(server: VideoData): List<Video> {

            return when (server.name) {

                "MegaCloud" -> megaCloudExtractor.getVideosFromUrl(server.link, server.type, server.name)

                "RapidCloud" -> rapidCloudExtractor.getVideosFromUrl(server.link, server.type, server.name)

                else -> emptyList()

            }

        }

    

        override fun episodeListRequest(anime: SAnime): Request {

            val id = anime.url.substringAfterLast("-")

            return GET("$baseUrl/ajax/episode/list?id=$id", apiHeaders(baseUrl + anime.url))

        }

    

        override fun videoListRequest(episode: SEpisode): Request {

            val id = episode.url.substringAfterLast("?ep=")

            return GET("$baseUrl/ajax/episode/servers?episodeId=$id", apiHeaders(baseUrl + episode.url))

        }

    

        private fun apiHeaders(referer: String) = headers.newBuilder()

            .add("Accept", "*/*")

            .add("X-Requested-With", "XMLHttpRequest")

            .add("Referer", referer)

            .build()

    }

    