package eu.kanade.tachiyomi.animeextension.en.animekai

import android.util.Log
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.util.parseAs
import kotlinx.serialization.json.Json
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
            val response = client.newCall(GET(reqUrl)).awaitSuccess()
            val megaToken = response.parseAs<ResultResponse>().result ?: throw Exception("Mega token null")
            
            val postBody = MegaDecodePostBody(megaToken, userAgent)
            val postRequest = Request.Builder()
                .url("https://enc-dec.app/api/dec-mega")
                .post(
                    json.encodeToString(MegaDecodePostBody.serializer(), postBody)
                        .toRequestBody("application/json".toMediaTypeOrNull()),
                )
                .build()
                
            val postResponse = client.newCall(postRequest).awaitSuccess()
            val decodedResult = postResponse.parseAs<ResultResponse>().result ?: throw Exception("Decoded result null")
            val megaUpResult = json.decodeFromString<MegaUpResult>(decodedResult)
            
            val masterPlaylistUrl = megaUpResult.sources.firstOrNull { it.file.contains("list") && it.file.endsWith(".m3u8") }?.file
                ?: megaUpResult.sources.firstOrNull()?.file
                
            val subtitleTracks = megaUpResult.tracks
                .filter { it.kind == "captions" && it.file.endsWith(".vtt") }
                .sortedByDescending { it.default }
                .map { Track(it.file, it.label ?: "Unknown") }
                
            buildVideoResults(masterPlaylistUrl, url, subtitleTracks, qualityPrefix, url, userAgent, referer)
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
        referer: String?,
    ): List<Video> {
        val videoResults = mutableListOf<Video>()
        val prefix = qualityPrefix ?: "MegaUp - "
        val headers = Headers.Builder().apply {
            add("User-Agent", userAgent)
            if (!referer.isNullOrBlank()) add("Referer", referer)
        }.build()
        
        try {
            val playlistUrl = masterPlaylistUrl ?: reqUrl
            val playlistResponse = client.newCall(GET(playlistUrl)).awaitSuccess()
            val playlistContent = playlistResponse.body.string()
            if (playlistContent.contains("#EXT-X-STREAM-INF")) {
                val lines = playlistContent.lines()
                val pattern = Regex("RESOLUTION=(\\d+)x(\\d+)")
                val codecsPattern = Regex("CODECS=\"([^\"]+)\"")
                for (i in lines.indices) {
                    val line = lines[i]
                    if (line.startsWith("#EXT-X-STREAM-INF")) {
                        val match = pattern.find(line)
                        val height = match?.groupValues?.getOrNull(2)
                        val currentQuality = if (height != null) "${height}p" else null
                        val codecsMatch = codecsPattern.find(line)
                        val currentCodecs = codecsMatch?.groupValues?.getOrNull(1)
                        val streamUrl = lines.getOrNull(i + 1)?.trim()
                        if (!streamUrl.isNullOrEmpty() && currentQuality != null) {
                            val absoluteUrl = if (streamUrl.startsWith("http")) streamUrl else playlistUrl.substringBeforeLast("/") + "/" + streamUrl
                            val qualityWithCodec = "$currentQuality${if (currentCodecs != null) " [$currentCodecs]" else ""}"
                            videoResults.add(
                                Video(
                                    originalUrl,
                                    "$prefix$qualityWithCodec",
                                    absoluteUrl,
                                    headers,
                                    subtitleTracks,
                                ),
                            )
                        }
                    }
                }
            } else if (playlistContent.contains("#EXTINF")) {
                videoResults.add(Video(originalUrl, "${prefix}Default", playlistUrl, headers, subtitleTracks))
            }
        } catch (e: Exception) {
            Log.e(tag, "Error building videos: ${e.message}")
        }
        return videoResults
    }
}
