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
import extensions.utils.LazyMutable
import extensions.utils.addListPreference
import extensions.utils.addSetPreference
import extensions.utils.getPreferencesLazy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
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

    private val preferences: SharedPreferences by getPreferencesLazy {
        val domain = getString("preferred_domain", PREF_DOMAIN_DEFAULT)!!
        if (domain.contains("bz")) {
            edit().putString("preferred_domain", PREF_DOMAIN_DEFAULT).apply()
        }
    }

    override val baseUrl: String
        get() = preferences.getString("preferred_domain", PREF_DOMAIN_DEFAULT)!!

    override val client: OkHttpClient by lazy {
        network.client.newBuilder()
            .addInterceptor(eu.kanade.tachiyomi.network.interceptor.SpecificHostRateLimitInterceptor(baseUrl.toHttpUrl(), 5, 1, TimeUnit.SECONDS))
            .build()
    }

    private val json = Json { ignoreUnknownKeys = true }

    override fun headersBuilder(): Headers.Builder {
        return super.headersBuilder()
            .set("User-Agent", "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/135.0.0.0 Mobile Safari/537.36")
    }

    private var docHeaders: Headers = headersBuilder().build()

    private val megaUpExtractor by lazy { MegaUpExtractor(client, docHeaders) }

    private var useEnglish: Boolean = preferences.getString("preferred_title_lang", "English") == "English"

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/trending?page=$page", docHeaders)
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
    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/updates?page=$page", docHeaders)
    override fun latestUpdatesSelector(): String = popularAnimeSelector()
    override fun latestUpdatesFromElement(element: Element): SAnime = popularAnimeFromElement(element)
    override fun latestUpdatesNextPageSelector(): String = popularAnimeNextPageSelector()

    // ============================== Search ===============================
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val url = if (query.isNotBlank()) {
            "$baseUrl/browser?keyword=${URLEncoder.encode(query, "UTF-8")}&page=$page"
        } else {
            val builder = "$baseUrl/browser?page=$page".toHttpUrl().newBuilder()
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
            builder.build().toString()
        }
        return GET(url, docHeaders)
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

        val tokenResponse = client.newCall(GET("${DECODE1_URL}$aniId", docHeaders)).awaitSuccess()
        val token = tokenResponse.parseAs<ResultResponse>().result ?: throw Exception("Token null")

        val ajaxUrl = "$baseUrl/ajax/episodes/list?ani_id=$aniId&_=$token"
        val ajaxResponse = client.newCall(GET(ajaxUrl, docHeaders)).awaitSuccess()
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

    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val episodeToken = episode.url
        val secondaryTokenResponse = client.newCall(GET("${DECODE1_URL}$episodeToken", docHeaders)).awaitSuccess()
        val secondaryToken = secondaryTokenResponse.parseAs<ResultResponse>().result ?: return emptyList()

        val ajaxUrl = "$baseUrl/ajax/links/list?token=$episodeToken&_=$secondaryToken"
        val ajaxResponse = client.newCall(GET(ajaxUrl, docHeaders)).awaitSuccess()
        val linksDoc = ajaxResponse.parseAs<ResultResponse>().toDocument()

        val enabledTypes = preferences.getStringSet("type_selection", DEFAULT_TYPES) ?: DEFAULT_TYPES
        val enabledHosters = preferences.getStringSet("hoster_selection", HOSTERS.toSet()) ?: HOSTERS.toSet()

        val embedLinks = linksDoc.select("div.server-items[data-id]").flatMap { items ->
            val type = items.attr("data-id")
            if (type !in enabledTypes) return@flatMap emptyList<VideoData>()
            
            val typeDisplay = when (type) {
                "sub" -> "Hard Sub"
                "dub" -> "Dub & S-Sub"
                "softsub" -> "Soft Sub"
                else -> type
            }

            items.select("span.server[data-lid]").parallelMapNotNull {
                val serverName = it.text()
                if (serverName !in enabledHosters) return@parallelMapNotNull null
                
                val serverId = it.attr("data-lid")
                val streamTokenResponse = client.newCall(GET("${DECODE1_URL}$serverId", docHeaders)).awaitSuccess()
                val streamToken = streamTokenResponse.parseAs<ResultResponse>().result ?: return@parallelMapNotNull null
                
                val streamUrl = "$baseUrl/ajax/links/view?id=$serverId&_=$streamToken"
                val streamResponse = client.newCall(GET(streamUrl, docHeaders)).awaitSuccess()
                val encodedLink = streamResponse.parseAs<ResultResponse>().result?.trim() ?: return@parallelMapNotNull null
                
                val postBody = buildJsonObject { put("text", encodedLink) }
                    .toString()
                    .toRequestBody("application/json".toMediaTypeOrNull())
                
                val decryptedResponse = client.newCall(POST(DECODE2_URL, body = postBody)).awaitSuccess()
                val decryptedLink = decryptedResponse.parseAs<IframeResponse>().result.url.trim()
                
                VideoData(decryptedLink, "$typeDisplay | $serverName")
            }
        }

        return embedLinks.parallelCatchingFlatMap { extractVideo(it) }
    }

    private suspend fun extractVideo(server: VideoData): List<Video> {
        return try {
            megaUpExtractor.videosFromUrl(server.iframe, server.serverName + " | ")
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
            DOMAIN_ENTRIES,
            DOMAIN_VALUES
        ) { docHeaders = headersBuilder().build() }

        screen.addListPreference(
            "preferred_title_lang",
            "English",
            "Preferred title language",
            "%s",
            listOf("English", "Romaji"),
            listOf("English", "Romaji")
        ) { useEnglish = it == "English" }

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
            listOf("top", "bottom", "none"),
            listOf("Top of description", "Bottom of description", "Don't show")
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
            listOf("sub", "dub", "softsub"),
            listOf("Hard Sub", "Dub & S-Sub", "Soft Sub")
        )
    }

    private suspend fun getAsync(url: String, referer: String? = null): String {
        val builder = Request.Builder().url(url)
        if (referer != null) builder.header("Referer", referer)
        return client.newCall(builder.build()).awaitSuccess().body.string()
    }

    private fun getSync(url: String): String {
        return client.newCall(Request.Builder().url(url).build()).execute().body.string()
    }

    private fun apiHeaders(referer: String) = headers.newBuilder()
        .add("Accept", "*/*")
        .add("X-Requested-With", "XMLHttpRequest")
        .add("Referer", referer)
        .build()

    override fun getFilterList(): AnimeFilterList = AnimeKaiFilters.FILTER_LIST

    private fun ResultResponse.toDocument() = Jsoup.parseBodyFragment(result ?: "")

    companion object {
        const val DECODE1_URL = "https://enc-dec.app/api/enc-kai?text="
        const val DECODE2_URL = "https://enc-dec.app/api/dec-kai"
        
        const val PREF_DOMAIN_DEFAULT = "https://anikai.to"
        val DOMAIN_ENTRIES = listOf("animekai.to", "animekai.cc", "animekai.ac", "anikai.to")
        val DOMAIN_VALUES = listOf("https://anikai.to", "https://animekai.cc", "https://animekai.ac", "https://anikai.to")
        
        val HOSTERS = listOf("Server 1", "Server 2")
        val DEFAULT_TYPES = setOf("sub", "dub", "softsub")
    }
}
