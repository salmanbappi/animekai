package eu.kanade.tachiyomi.animeextension.en.animekai

import kotlinx.serialization.Serializable

@Serializable
data class ResultResponse(
    val result: String? = null,
)

@Serializable
data class IframeResponse(
    val result: IframeDto,
)

@Serializable
data class IframeDto(
    val url: String,
    val skip: SkipDto? = null,
)

@Serializable
data class SkipDto(
    val intro: List<Int>? = null,
    val outro: List<Int>? = null,
)

@Serializable
data class MegaUpResult(
    val sources: List<MegaUpSource>,
    val tracks: List<MegaUpTrack>,
    val download: String? = null,
)

@Serializable
data class MegaUpSource(
    val file: String,
)

@Serializable
data class MegaUpTrack(
    val file: String,
    val label: String? = null,
    val kind: String,
    val default: Boolean = false,
)

@Serializable
data class MegaDecodePostBody(
    val text: String,
    val agent: String,
)

@Serializable
data class VideoCode(
    val type: String,
    val serverId: String,
    val serverName: String,
)