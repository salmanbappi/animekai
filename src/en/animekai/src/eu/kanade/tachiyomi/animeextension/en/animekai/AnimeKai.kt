package eu.kanade.tachiyomi.animeextension.en.animekai

import android.app.Application
import android.content.SharedPreferences
import android.util.Log
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.util.asJsoup
import eu.kanade.tachiyomi.util.parallelCatchingFlatMap
import eu.kanade.tachiyomi.util.parallelMapNotNull
import eu.kanade.tachiyomi.util.parseAs
import extensions.utils.addListPreference
import extensions.utils.addSetPreference
import extensions.utils.getPreferencesLazy
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class AnimeKai : ParsedAnimeHttpSource(), ConfigurableAnimeSource {
    override val name = "AnimeKai"
    override val lang = "en"
    override val supportsLatest = true

    override val id: Long = 4567890123456L

    private val preferences: SharedPreferences by getPreferencesLazy()

    override val baseUrl:
        get() = preferences.getString("preferred_domain", PREF_DOMAIN_DEFAULT)!!

    override val client: OkHttpClient = network.client.newBuilder()
        .addInterceptor(RateLimitInterceptor(3, 1, TimeUnit.SECONDS))
        .connectTimeout(45, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(45, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    override fun headersBuilder(): Headers.Builder {
        return super.headersBuilder()
            .set("User-Agent", "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/135.0.0.0 Mobile Safari/537.36")
    }

    private fun getDocHeaders(): Headers = headersBuilder()
        .set("Referer", "$baseUrl/")
        .build()

    private val megaUpExtractor by lazy { MegaUp(client) }

    private val useEnglish:
        get() = preferences.getString("preferred_title_lang", "English") == "English"

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/trending?page=$page", getDocHeaders())
    override fun popularAnimeSelector(): String = "div.aitem"
    override fun popularAnimeFromElement(element: Element): SAnime = SAnime.create().apply {
        val poster = element.selectFirst("a.poster")!!
        setUrlWithoutDomain(poster.attr("href"))
        title = if (useEnglish) {
            element.selectFirst("a.title")?.text() ?: poster.attr("title") ?: "Unknown"
        } else {
            element.selectFirst("a.title")?.attr("data-jp") ?: element.selectFirst("a.title")?.text() ?: "Unknown"
        }
        thumbnail_url = poster.selectFirst("img")?.attr("data-src")
    }
    override fun popularAnimeNextPageSelector(): String = "ul.pagination a[rel=next]"

    // ============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/updates?page=$page", getDocHeaders())
    override fun latestUpdatesSelector(): String = popularAnimeSelector()
    override fun latestUpdatesFromElement(element: Element): SAnime = popularAnimeFromElement(element)
    override fun latestUpdatesNextPageSelector(): String = popularAnimeNextPageSelector()

    // ============================== Search ===============================
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val builder = "$baseUrl/browser".toHttpUrl().newBuilder()
        builder.addQueryParameter("keyword", query)
        builder.addQueryParameter("page", page.toString())
        
        filters.forEach { filter ->
            when (filter) {
                is AnimeKaiFilters.TypesFilter -> filter.addQueryParameters(builder)
                is AnimeKaiFilters.GenresFilter -> filter.addQueryParameters(builder)
                is AnimeKaiFilters.StatusFilter -> filter.addQueryParameters(builder)
                is AnimeKaiFilters.SortByFilter -> filter.addQueryParameters(builder)
                is AnimeKaiFilters.SeasonsFilter -> filter.addQueryParameters(builder)
                is AnimeKaiFilters.YearsFilter -> filter.addQueryParameters(builder)
                is AnimeKaiFilters.RatingFilter -> filter.addQueryParameters(builder)
                is AnimeKaiFilters.CountriesFilter -> filter.addQueryParameters(builder)
                is AnimeKaiFilters.LanguagesFilter -> filter.addQueryParameters(builder)
                else -> {}
            }
        }
        return GET(builder.build().toString(), getDocHeaders())
    }
    override fun searchAnimeSelector(): String = popularAnimeSelector()
    override fun searchAnimeFromElement(element: Element): SAnime = popularAnimeFromElement(element)
    override fun searchAnimeNextPageSelector(): String = popularAnimeNextPageSelector()

    // ============================== Details ===============================
    override fun animeDetailsParse(document: Document): SAnime = SAnime.create().apply {
        thumbnail_url = document.selectFirst(".poster img")?.attr("src")
        val mainEntity = document.selectFirst("div#main-entity")
        if (mainEntity != null) {
            val titleElement = mainEntity.selectFirst("h1.title")
            title = if (useEnglish) {
                titleElement?.text() ?: ""
            } else {
                titleElement?.attr("data-jp") ?: titleElement?.text() ?: ""
            }
            description = mainEntity.selectFirst("div.desc")?.text()
            genre = mainEntity.select("div.detail div:contains(Genres) span a").joinToString { it.text() }
            author = mainEntity.select("div.detail div:contains(Studios) span a").joinToString { it.text() }
            status = when (mainEntity.selectFirst("div.detail div:contains(Status) span")?.text()?.lowercase()?.trim()) {
                "releasing" -> SAnime.ONGOING
                "completed", "finished", "finished airing" -> SAnime.COMPLETED
                else -> SAnime.UNKNOWN
            }
        }
    }

    // ============================== Episodes ==============================
    override fun episodeListSelector(): String = "div.eplist.titles ul.range li > a[num][token]"

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val response = client.newCall(animeDetailsRequest(anime)).awaitSuccess()
        val document = response.asJsoup()
        val aniId = document.selectFirst("div[data-id]")?.attr("data-id") ?: throw Exception("Anime ID not found")

        val tokenResponse = client.newCall(GET("${DECODE1_URL}$aniId", getDocHeaders())).awaitSuccess()
        val token = tokenResponse.parseAs<ResultResponse>().result ?: throw Exception("Token null")

        val ajaxUrl = "$baseUrl/ajax/episodes/list?ani_id=$aniId&_=$token"
        val ajaxResponse = client.newCall(GET(ajaxUrl, getDocHeaders())).awaitSuccess()
        val episodesDoc = ajaxResponse.parseAs<ResultResponse>().toDocument()

        return episodesDoc.select(episodeListSelector()).map { ep ->
            episodeFromElement(ep)
        }.reversed()
    }

    override fun episodeFromElement(element: Element): SEpisode = SEpisode.create().apply {
        val num = element.attr("num")
        episode_number = num.toFloatOrNull() ?: 0f
        val epTitle = element.selectFirst("span")?.text() ?: ""
        name = if (epTitle.isNotBlank()) "Episode $num: $epTitle" else "Episode $num"
        val extractedToken = element.attr("token")
        val langs = element.attr("langs")
        scanlator = when (langs) {
            "3", "2" -> "Sub & Dub"
            "1" -> "Sub"
            else -> ""
        } + if (element.hasClass("filler")) " [Filler]" else ""
        url = extractedToken
    }

    // ============================ Video Links =============================
    override fun videoListSelector(): String = throw UnsupportedOperationException()
    override fun videoFromElement(element: Element): Video = throw UnsupportedOperationException()
    override fun videoUrlParse(document: Document): String = throw UnsupportedOperationException()

    private val semaphore = Semaphore(2)

    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val episodeToken = episode.url
        val secondaryTokenResponse = client.newCall(GET("${DECODE1_URL}$episodeToken", getDocHeaders())).awaitSuccess()
        val secondaryToken = secondaryTokenResponse.parseAs<ResultResponse>().result ?: return emptyList()

        val ajaxUrl = "$baseUrl/ajax/links/list?token=$episodeToken&_=$secondaryToken"
        val ajaxResponse = client.newCall(GET(ajaxUrl, getDocHeaders())).awaitSuccess()
        val linksDoc = ajaxResponse.parseAs<ResultResponse>().toDocument()

        val enabledTypes = preferences.getStringSet("type_selection", DEFAULT_TYPES) ?: DEFAULT_TYPES
        val enabledHosters = preferences.getStringSet("hoster_selection", HOSTERS.toSet()) ?: HOSTERS.toSet()

        val videoCodes = mutableListOf<VideoCode>()
        linksDoc.select("div.server-items[data-id]").forEach { items ->
            val type = items.attr("data-id")
            if (type in enabledTypes) {
                items.select("span.server[data-lid]").forEach { span ->
                    val serverName = span.text()
                    if (serverName in enabledHosters) {
                        videoCodes.add(VideoCode(type, span.attr("data-lid"), serverName))
                    }
                }
            }
        }

        return videoCodes.parallelMapNotNull {
            semaphore.withPermit {
                try {
                    val streamTokenResponse = client.newCall(GET("${DECODE1_URL}${it.serverId}", getDocHeaders())).awaitSuccess()
                    val streamToken = streamTokenResponse.parseAs<ResultResponse>().result ?: return@parallelMapNotNull null
                    
                    val streamUrl = "$baseUrl/ajax/links/view?id=${it.serverId}&_=$streamToken"
                    val streamResponse = client.newCall(GET(streamUrl, getDocHeaders())).awaitSuccess()
                    val encodedLink = streamResponse.parseAs<ResultResponse>().result?.trim() ?: return@parallelMapNotNull null
                    
                    val postBody = buildJsonObject { put("text", encodedLink) }
                        .toString()
                        .toRequestBody("application/json".toMediaTypeOrNull())
                    
                    val decryptedResponse = client.newCall(
                        Request.Builder()
                            .url(DECODE2_URL)
                            .headers(getDocHeaders())
                            .post(postBody)
                            .build()
                    ).awaitSuccess()
                    val decryptedLink = decryptedResponse.parseAs<IframeResponse>().result.url.trim()
                    
                    val typeDisplay = when (it.type) {
                        "sub" -> "Hard Sub"
                        "dub" -> "Dub & S-Sub"
                        "softsub" -> "Soft Sub"
                        else -> it.type
                    }
                    
                    VideoData(decryptedLink, "$typeDisplay | ${it.serverName}")
                } catch (e: Exception) {
                    null
                }
            }
        }.parallelCatchingFlatMap { extractVideo(it) }
    }

    private suspend fun extractVideo(server: VideoData): List<Video> {
        return try {
            megaUpExtractor.processUrl(server.iframe, headersBuilder().build()["User-Agent"]!!, server.serverName + " | ", baseUrl)
        } catch (e: Exception) {
            Log.e("AnimeKai", "Error extracting videos for ${server.serverName}", e)
            emptyList()
        }
    }

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString("preferred_quality", "1080p")!!
        val server = preferences.getString("preferred_server", "Server 1")!!
        val type = preferences.getString("preferred_type", "[Soft Sub]")!!

        return this.sortedWith(
            compareByDescending<Video> { it.quality.contains(quality) }
                .thenByDescending { it.quality.contains(server) }
                .thenByDescending { it.quality.contains(type.replace("[", "").replace("]", "")) }
        )
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        screen.addListPreference(
            "preferred_domain",
            PREF_DOMAIN_DEFAULT,
            "Preferred domain",
            "%s",
            DOMAIN_ENTRIES.toList(),
            DOMAIN_VALUES.toList()
        )

        screen.addListPreference(
            "preferred_title_lang",
            "English",
            "Preferred title language",
            "%s",
            listOf("English", "Romaji"),
            listOf("English", "Romaji")
        )

        screen.addListPreference(
            "preferred_quality",
            "1080p",
            "Preferred quality",
            "%s",
            listOf("1080p", "720p", "480p", "360p"),
            listOf("1080p", "720p", "480p", "360p")
        )

        screen.addListPreference(
            "preferred_server",
            "Server 1",
            "Preferred Server",
            "%s",
            HOSTERS,
            HOSTERS
        )

        screen.addListPreference(
            "preferred_type",
            "[Soft Sub]",
            "Preferred Type",
            "%s",
            listOf("[Hard Sub]", "[Soft Sub]", "[Dub & S-Sub]"),
            listOf("[Hard Sub]", "[Soft Sub]", "[Dub & S-Sub]")
        )

        screen.addListPreference(
            "score_position",
            "top",
            "Score display position",
            "%s",
            listOf("Top of description", "Bottom of description", "Don't show"),
            listOf("top", "bottom", "none")
        )

        screen.addSetPreference(
            "hoster_selection",
            HOSTERS.toSet(),
            "Enable/Disable Hosts",
            "Select which video hosts to show in the episode list",
            HOSTERS,
            HOSTERS
        )

        screen.addSetPreference(
            "type_selection",
            DEFAULT_TYPES,
            "Enable/Disable Types",
            "Select which video types to show in the episode list.\nDisable the one you don't want to speed up loading.",
            listOf("Hard Sub", "Dub & S-Sub", "Soft Sub"),
            listOf("sub", "dub", "softsub")
        )
    }

    private fun apiHeaders(referer: String) = headers.newBuilder()
        .add("Accept", "*/*")
        .add("X-Requested-With", "XMLHttpRequest")
        .add("Referer", referer)
        .build()

    override fun getFilterList(): AnimeFilterList = AnimeKaiFilters.FILTER_LIST

    private fun ResultResponse.toDocument() = Jsoup.parseBodyFragment(result ?: "")

    class RateLimitInterceptor(
        private val permits: Int,
        private val period: Long,
        private val unit: TimeUnit,
    ) : Interceptor {
        private val requestCounts = mutableListOf<Long>()

        @Synchronized
        override fun intercept(chain: Interceptor.Chain): Response {
            val now = System.currentTimeMillis()
            val periodMs = unit.toMillis(period)

            requestCounts.removeAll { it < now - periodMs }

            if (requestCounts.size >= permits) {
                val sleepTime = requestCounts.first() + periodMs - now
                if (sleepTime > 0) Thread.sleep(sleepTime)
            }

            requestCounts.add(System.currentTimeMillis())
            return chain.proceed(chain.request())
        }
    }

    companion object {
        const val DECODE1_URL = "https://enc-dec.app/api/enc-kai?text="
        const val DECODE2_URL = "https://enc-dec.app/api/dec-kai"
        
        const val PREF_DOMAIN_DEFAULT = "https://anikai.to"
        val DOMAIN_ENTRIES = arrayOf("animekai.to", "animekai.cc", "animekai.ac", "anikai.to")
        val DOMAIN_VALUES = arrayOf("https://animekai.to", "https://animekai.cc", "https://animekai.ac", "https://anikai.to")
        
        val HOSTERS = listOf("Server 1", "Server 2")
        val DEFAULT_TYPES = setOf("sub", "dub", "softsub")
    }
}
