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
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
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
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.net.URLEncoder

class AnimeKai : AnimeHttpSource(), ConfigurableAnimeSource {
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

    override val baseUrl by lazy {
        preferences.getString("preferred_domain", PREF_DOMAIN_DEFAULT)!!
    }

    override val client: OkHttpClient = network.client

    private val json = Json { ignoreUnknownKeys = true }

    override fun headersBuilder(): Headers.Builder {
        return super.headersBuilder()
            .set("User-Agent", "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/135.0.0.0 Mobile Safari/537.36")
    }

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/trending?page=$page", headers)
    override fun popularAnimeParse(response: Response): AnimesPage = parseAnimesPage(response)

    // ============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/updates?page=$page", headers)
    override fun latestUpdatesParse(response: Response): AnimesPage = parseAnimesPage(response)

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
        return GET(url, headers)
    }
    override fun searchAnimeParse(response: Response): AnimesPage = parseAnimesPage(response)

    // ============================== Details ===============================
    override fun animeDetailsParse(response: Response): SAnime {
        val document = response.asJsoup()
        return SAnime.create().apply {
            title = if (preferences.getString("preferred_title_lang", "English") == "English") {
                document.selectFirst("h1.title")?.text() ?: ""
            } else {
                document.selectFirst("h1.title")?.attr("data-jp") ?: document.selectFirst("h1.title")?.text() ?: ""
            }
            thumbnail_url = document.selectFirst(".poster img")?.attr("src")
            description = document.selectFirst("div.desc")?.text()
            genre = document.select("div.detail div:contains(Genres) span a").joinToString { it.text() }
            author = document.select("div.detail div:contains(Studios) span a").joinToString { it.text() }
            status = when (document.selectFirst("div.detail div:contains(Status) span")?.text()?.lowercase()?.trim()) {
                "releasing" -> SAnime.ONGOING
                "completed", "finished" -> SAnime.COMPLETED
                else -> SAnime.UNKNOWN
            }
        }
    }

    // ============================== Episodes ==============================
    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        val aniId = document.selectFirst("#watch-page")?.attr("data-id")
            ?: document.selectFirst("div.rate-box")?.attr("data-id")
            ?: document.selectFirst("div[data-id]")?.attr("data-id")
            ?: throw Exception("Anime ID not found")

        val token = getSync("${DECODE1_URL}$aniId").trim()
        val ajaxUrl = "$baseUrl/ajax/episodes/list?ani_id=$aniId&_=$token"
        
        val ajaxResponse = client.newCall(GET(ajaxUrl, apiHeaders(response.request.url.toString()))).execute()
        val resultHtml = try {
            ajaxResponse.parseAs<ResultResponse>().result
        } catch (e: Exception) {
            null
        }

        if (resultHtml.isNullOrBlank()) {
            val subText = document.selectFirst("div.info span.sub")?.text()
            val epText = document.selectFirst("div.detail div:contains(Episodes:) span")?.text()
            
            var epCount = subText?.let { Regex("\\d+").find(it)?.value?.toIntOrNull() } 
                ?: epText?.let { Regex("\\d+").find(it)?.value?.toIntOrNull() } 
                ?: 1
            
            if (epCount > 2000) epCount = 1
            
            val slug = response.request.url.toString().substringAfterLast("/").substringBefore("?")
            return (1..epCount).map { i ->
                SEpisode.create().apply {
                    episode_number = i.toFloat()
                    name = "Episode $i"
                    url = "/watch/$slug?ep=$i"
                }
            }.reversed()
        }

        val episodesDoc = Jsoup.parseBodyFragment(resultHtml)
        val episodeElements = episodesDoc.select("div.eplist.titles ul.range li > a[num][token]")

        return episodeElements.map { ep ->
            SEpisode.create().apply {
                val num = ep.attr("num")
                episode_number = num.toFloatOrNull() ?: 0f
                val epTitle = ep.selectFirst("span")?.text() ?: ""
                name = if (epTitle.isNotBlank()) "Episode $num: $epTitle" else "Episode $num"
                val extractedToken = ep.attr("token")
                val langs = ep.attr("langs")
                scanlator = when (langs) {
                    "3", "2" -> "Sub & Dub"
                    "1" -> "Sub"
                    else -> ""
                } + if (ep.hasClass("filler")) " [Filler]" else ""
                url = "${response.request.url.encodedPath}?token=$extractedToken&ep=$num"
            }
        }.reversed()
    }

    // ============================ Video Links =============================
    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        if (!episode.url.contains("?token=")) return emptyList()
        
        val urlParts = episode.url.split("?token=")
        val watchUrl = baseUrl + urlParts[0]
        val tokenParts = urlParts[1].split("&ep=")
        val episodeToken = tokenParts[0]
        
        val secondaryToken = getAsync("${DECODE1_URL}$episodeToken").trim()
        val ajaxUrl = "$baseUrl/ajax/links/list?token=$episodeToken&_=$secondaryToken"
        val ajaxResponse = client.newCall(GET(ajaxUrl, apiHeaders(watchUrl))).awaitSuccess()
        val resultHtml = ajaxResponse.parseAs<ResultResponse>().result ?: return emptyList()

        val linksDoc = Jsoup.parseBodyFragment(resultHtml)
        val serverItems = linksDoc.select("div.server-items[data-id]")

        val megaUp = MegaUp(client)
        val userAgent = headers["User-Agent"] ?: "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/135.0.0.0 Mobile Safari/537.36"
        
        val enabledTypes = preferences.getStringSet("type_selection", DEFAULT_TYPES) ?: DEFAULT_TYPES
        val enabledHosters = preferences.getStringSet("hoster_selection", HOSTERS.toSet()) ?: HOSTERS.toSet()

        return serverItems.flatMap { items ->
            val type = items.attr("data-id")
            if (type !in enabledTypes) return@flatMap emptyList<Video>()
            
            val serverSpans = items.select("span.server[data-lid]")
            serverSpans.flatMap { span ->
                val serverName = span.text()
                if (serverName !in enabledHosters) return@flatMap emptyList<Video>()
                
                val serverId = span.attr("data-lid")
                val streamToken = getAsync("${DECODE1_URL}$serverId").trim()
                val streamUrl = "$baseUrl/ajax/links/view?id=$serverId&_=$streamToken"
                
                val streamResponse = client.newCall(GET(streamUrl, apiHeaders(watchUrl))).awaitSuccess()
                val encodedLink = streamResponse.parseAs<ResultResponse>().result?.trim() ?: return@flatMap emptyList<Video>()
                
                val postBody = buildJsonObject { put("text", encodedLink) }
                    .toString()
                    .toRequestBody("application/json".toMediaTypeOrNull())
                
                val decryptedResponse = client.newCall(
                    POST(DECODE2_URL, body = postBody),
                ).awaitSuccess()
                val decryptedLink = decryptedResponse.parseAs<IframeResponse>().result.url.trim()
                
                val typeDisplay = when (type) {
                    "sub" -> "Subtitled"
                    "dub" -> "Dubbed"
                    "softsub" -> "Softsubbed"
                    else -> type
                }
                
                megaUp.processUrl(decryptedLink, userAgent, "$typeDisplay | $serverName | ", baseUrl)
            }
        }
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val domainPref = androidx.preference.ListPreference(screen.context).apply {
            key = "preferred_domain"
            title = "Preferred domain"
            entries = DOMAIN_ENTRIES.toTypedArray()
            entryValues = DOMAIN_VALUES.toTypedArray()
            setDefaultValue(PREF_DOMAIN_DEFAULT)
            summary = "%s"
        }

        val titlePref = androidx.preference.ListPreference(screen.context).apply {
            key = "preferred_title_lang"
            title = "Preferred title language"
            entries = arrayOf("English", "Romaji")
            entryValues = arrayOf("English", "Romaji")
            setDefaultValue("English")
            summary = "%s"
        }

        val qualityPref = androidx.preference.ListPreference(screen.context).apply {
            key = "preferred_quality"
            title = "Preferred quality"
            entries = arrayOf("1080p", "720p", "480p", "360p")
            entryValues = arrayOf("1080p", "720p", "480p", "360p")
            setDefaultValue("1080p")
            summary = "%s"
        }

        val serverPref = androidx.preference.ListPreference(screen.context).apply {
            key = "preferred_server"
            title = "Preferred Server"
            entries = HOSTERS.toTypedArray()
            entryValues = HOSTERS.toTypedArray()
            setDefaultValue("Server 1")
            summary = "%s"
        }

        val typePref = androidx.preference.ListPreference(screen.context).apply {
            key = "preferred_type"
            title = "Preferred Type"
            entries = arrayOf("[Hard Sub]", "[Soft Sub]", "[Dub & S-Sub]")
            entryValues = arrayOf("[Hard Sub]", "[Soft Sub]", "[Dub & S-Sub]")
            setDefaultValue("[Soft Sub]")
            summary = "%s"
        }

        val scorePosPref = androidx.preference.ListPreference(screen.context).apply {
            key = "score_position"
            title = "Score display position"
            entries = arrayOf("Top of description", "Bottom of description", "Don't show")
            entryValues = arrayOf("top", "bottom", "none")
            setDefaultValue("top")
            summary = "%s"
        }

        val hosterTogglePref = androidx.preference.MultiSelectListPreference(screen.context).apply {
            key = "hoster_selection"
            title = "Enable/Disable Hosts"
            summary = "Select which video hosts to show in the episode list"
            entries = HOSTERS.toTypedArray()
            entryValues = HOSTERS.toTypedArray()
            setDefaultValue(HOSTERS.toSet())
        }

        val typeTogglePref = androidx.preference.MultiSelectListPreference(screen.context).apply {
            key = "type_selection"
            title = "Enable/Disable Types"
            summary = "Select which video types to show in the episode list.\nDisable the one you don't want to speed up loading."
            entries = arrayOf("Sub", "Dub", "Soft Sub")
            entryValues = arrayOf("sub", "dub", "softsub")
            setDefaultValue(DEFAULT_TYPES)
        }

        screen.addPreference(domainPref)
        screen.addPreference(titlePref)
        screen.addPreference(qualityPref)
        screen.addPreference(serverPref)
        screen.addPreference(typePref)
        screen.addPreference(scorePosPref)
        screen.addPreference(hosterTogglePref)
        screen.addPreference(typeTogglePref)
    }

    private fun parseAnimesPage(response: Response): AnimesPage {
        val document = response.asJsoup()
        val animeElements = document.select("div.aitem")
        val animes = animeElements.map { element ->
            SAnime.create().apply {
                val poster = element.selectFirst("a.poster")!!
                setUrlWithoutDomain(poster.attr("href"))
                title = element.selectFirst("a.title")?.text() ?: poster.attr("title") ?: "Unknown"
                thumbnail_url = poster.selectFirst("img")?.attr("data-src")
            }
        }
        return AnimesPage(animes, document.selectFirst("ul.pagination a[rel=next]") != null)
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

    companion object {
        const val DECODE1_URL = "https://enc-dec.app/api/enc-kai?text="
        const val DECODE2_URL = "https://enc-dec.app/api/dec-kai"
        
        const val PREF_DOMAIN_DEFAULT = "https://anikai.to"
        val DOMAIN_ENTRIES = listOf("animekai.to", "animekai.cc", "animekai.ac", "anikai.to")
        val DOMAIN_VALUES = listOf("https://animekai.to", "https://animekai.cc", "https://animekai.ac", "https://anikai.to")
        
        val HOSTERS = listOf("Server 1", "Server 2")
        val DEFAULT_TYPES = setOf("sub", "dub", "softsub")
    }
}
