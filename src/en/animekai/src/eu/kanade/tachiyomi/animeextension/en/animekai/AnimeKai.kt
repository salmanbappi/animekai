package eu.kanade.tachiyomi.animeextension.en.animekai

import eu.kanade.tachiyomi.animeextension.BuildConfig

import eu.kanade.tachiyomi.animesource.model.AnimeFilterList

import eu.kanade.tachiyomi.animesource.model.SAnime

import eu.kanade.tachiyomi.animesource.model.SEpisode

import eu.kanade.tachiyomi.animesource.model.Video

import eu.kanade.tachiyomi.lib.megacloudextractor.MegaCloudExtractor

import eu.kanade.tachiyomi.lib.rapidcloudextractor.RapidCloudExtractor

import eu.kanade.tachiyomi.multisrc.zorotheme.ZoroTheme

import eu.kanade.tachiyomi.network.GET

import eu.kanade.tachiyomi.util.asJsoup

import okhttp3.HttpUrl.Companion.toHttpUrl

import okhttp3.Request

import org.jsoup.nodes.Document

import org.jsoup.nodes.Element



class AnimeKai : ZoroTheme(

    "en",

    "AnimeKai",

    "https://anikai.to",

    hosterNames = listOf(

        "VidSrc",

        "MegaCloud",

        "RapidCloud",

    ),

) {

    override val id: Long = 7537715367149829913L



    override val ajaxRoute = ""



    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/trending?page=$page", docHeaders)



    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/updates?page=$page", docHeaders)



    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {

        val url = "$baseUrl/browser".toHttpUrl().newBuilder().apply {

            addQueryParameter("page", page.toString())

            if (query.isNotBlank()) {

                addQueryParameter("keyword", query)

            }

        }.build()

        return GET(url, docHeaders)

    }



    override fun popularAnimeSelector(): String = "div.aitem"



    override fun popularAnimeFromElement(element: Element) = SAnime.create().apply {

        element.selectFirst("a.poster")!!.let {

            setUrlWithoutDomain(it.attr("href"))

            thumbnail_url = it.selectFirst("img")?.attr("data-src")

        }

        title = element.selectFirst("a.title")!!.text()

    }



    override fun popularAnimeNextPageSelector() = "ul.pagination li.page-item a[rel=next]"



    override fun animeDetailsParse(document: Document) = SAnime.create().apply {

        thumbnail_url = document.selectFirst("div.poster img")?.attr("src")

        title = document.selectFirst("h1.title")!!.text()

        description = document.selectFirst("div.desc")?.text()

        genre = document.select("div.detail a[href^='/genres/']").eachText().joinToString()

        author = document.select("div.detail a[href^='/studios/']").eachText().joinToString()

        status = parseStatus(document.select("div.detail div:contains(Status:) span").text())

    }



    override fun episodeListRequest(anime: SAnime): Request {

        val url = baseUrl + anime.url

        val document = client.newCall(GET(url, headers)).execute().asJsoup()

        val id = document.selectFirst("#watch-page")?.attr("data-id")

            ?: throw Exception("Could not find anime ID")

        return GET("$baseUrl/ajax/episode/list?id=$id", apiHeaders(url))

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



    private val megaCloudExtractor by lazy { MegaCloudExtractor(client, headers, BuildConfig.MEGACLOUD_API) }

    private val rapidCloudExtractor by lazy { RapidCloudExtractor(client, headers, preferences) }



    override fun extractVideo(server: VideoData): List<Video> {

        return when (server.name) {

            "VidSrc", "MegaCloud" -> megaCloudExtractor.getVideosFromUrl(server.link, server.type, server.name)

            "RapidCloud" -> rapidCloudExtractor.getVideosFromUrl(server.link, server.type, server.name)

            else -> emptyList()

        }

    }

}


