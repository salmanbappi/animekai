package eu.kanade.tachiyomi.animeextension.en.animekai

import android.util.Log
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.util.parseAs
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URL

class MegaUp(private val client: OkHttpClient) {
    private val tag = "AnimeKaiMegaUp"
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
    private val playlistUtils by lazy { PlaylistUtils(client) }

    suspend fun processUrl(
        url: String,
        userAgent: String,
        qualityPrefix: String? = null,
        referer: String? = null,
    ): List<Video> {
        return try {
            val parsedUrl = URL(url)
            val baseUrl = buildString {
                append(parsedUrl.protocol)
                append("://")
                append(parsedUrl.host)
                if (parsedUrl.port != -1 && parsedUrl.port != parsedUrl.defaultPort) {
                    append(":").append(parsedUrl.port)
                }
            }
            val pathSegments = parsedUrl.path.split("/").filter { it.isNotEmpty() }
            val token = pathSegments.lastOrNull()?.substringBefore("?")
                ?: throw IllegalArgumentException("No token found in URL: $url")
            
            val reqUrl = "$baseUrl/media/$token"
            val mediaHeaders = Headers.Builder()
                .add("User-Agent", userAgent)
                .add("Referer", url)
                .build()
                
            val response = client.newCall(GET(reqUrl, mediaHeaders)).awaitSuccess()
            val megaToken = response.parseAs<ResultResponse>().result ?: throw Exception("Mega token null")
            
            val postBody = buildJsonObject {
                put("text", megaToken)
                put("agent", userAgent)
            }.toString().toRequestBody("application/json".toMediaTypeOrNull())

            val postRequest = Request.Builder()
                .url("https://enc-dec.app/api/dec-mega")
                .headers(mediaHeaders)
                .post(postBody)
                .build()
                
            val postResponse = client.newCall(postRequest).awaitSuccess()
            val megaUpResult = postResponse.parseAs<MegaUpResponse>().result
            
            if (megaUpResult.sources.isEmpty()) return emptyList()

            val subtitleTracks = megaUpResult.tracks
                .filter { (it.kind == "captions" || it.kind == "subtitles") && it.file.isNotBlank() }
                .map { Track(it.file, it.label ?: "Unknown") }

            val prefix = qualityPrefix ?: ""

            megaUpResult.sources.flatMap { source ->
                val videoUrl = source.file
                when {
                    videoUrl.contains(".m3u8") -> {
                        playlistUtils.extractFromHls(
                            videoUrl,
                            url, // Use iframe URL as referer
                            mediaHeaders,
                            mediaHeaders,
                            { quality -> "$prefix$quality" },
                            subtitleTracks
                        )
                    }
                    videoUrl.contains(".mp4") -> {
                        listOf(
                            Video(
                                videoUrl,
                                "${prefix}MP4",
                                videoUrl,
                                mediaHeaders,
                                subtitleTracks
                            )
                        )
                    }
                    else -> emptyList()
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "Error processing URL: ${e.message}")
            emptyList()
        }
    }
}
