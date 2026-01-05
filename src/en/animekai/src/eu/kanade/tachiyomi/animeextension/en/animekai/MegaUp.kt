package eu.kanade.tachiyomi.animeextension.en.animekai

import android.util.Log
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.util.parseAs
import kotlinx.serialization.Serializable
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
            // Important: Use the iframe URL as referer for the media request
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
            
            val masterPlaylistUrl = megaUpResult.sources.firstOrNull { it.file.contains("list") && it.file.endsWith(".m3u8") }?.file
                ?: megaUpResult.sources.firstOrNull()?.file
                
            val subtitleTracks = megaUpResult.tracks
                .filter { it.kind == "captions" || it.kind == "subtitles" }
                .map { Track(it.file, it.label ?: "Unknown") }
                
            buildVideoResults(masterPlaylistUrl, url, subtitleTracks, qualityPrefix, url, userAgent)
        } catch (e: Exception) {
            Log.e(tag, "Error processing URL: ${e.message}")
            emptyList()
        }
    }

    private suspend fun buildVideoResults(
        masterPlaylistUrl: String?,
        reqUrl: String,
        subtitleTracks: List<Track>,
        qualityPrefix: String?,
        originalUrl: String,
        userAgent: String,
    ): List<Video> {
        val videoResults = mutableListOf<Video>()
        val prefix = qualityPrefix ?: "MegaUp - "
        // Use the original iframe URL as the Referer for the actual video stream
        val headers = Headers.Builder().apply {
            add("User-Agent", userAgent)
            add("Referer", originalUrl)
        }.build()
        
        try {
            val playlistUrl = masterPlaylistUrl ?: reqUrl
            val playlistResponse = client.newCall(GET(playlistUrl, headers)).awaitSuccess()
            val playlistContent = playlistResponse.body.string()
            if (playlistContent.contains("#EXT-X-STREAM-INF")) {
                val lines = playlistContent.lines()
                val pattern = Regex("RESOLUTION=(\\d+)x(\\d+)")
                for (i in lines.indices) {
                    val line = lines[i]
                    if (line.startsWith("#EXT-X-STREAM-INF")) {
                        val match = pattern.find(line)
                        val height = match?.groupValues?.getOrNull(2)
                        val currentQuality = if (height != null) "${height}p" else "Auto"
                        val streamUrl = lines.getOrNull(i + 1)?.trim()
                        if (!streamUrl.isNullOrEmpty()) {
                            val absoluteUrl = if (streamUrl.startsWith("http")) streamUrl else playlistUrl.substringBeforeLast("/") + "/" + streamUrl
                            videoResults.add(
                                Video(
                                    absoluteUrl, // Use direct URL for playback
                                    "$prefix$currentQuality",
                                    absoluteUrl,
                                    headers,
                                    subtitleTracks,
                                ),
                            )
                        }
                    }
                }
            } else {
                videoResults.add(Video(playlistUrl, "${prefix}Auto", playlistUrl, headers, subtitleTracks))
            }
        } catch (e: Exception) {
            Log.e(tag, "Error building videos: ${e.message}")
        }
        return videoResults
    }
}
