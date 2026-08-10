package io.tapper.core.model

data class StreamRef(
    val url: String,
    val priority: Int,
    /** ~880 channels in the default playlist 403 without these. */
    val headers: Map<String, String> = emptyMap(),
)

data class Channel(
    val id: String,
    val sourceId: String,
    val name: String,
    val number: Int?,
    val logoUrl: String?,
    val group: String?,
    val countryCode: String?,
    val epgChannelId: String?,
    /** Never empty; the player walks this on failure. */
    val streams: List<StreamRef>,
) {
    val primaryUrl: String get() = streams.first().url
}

data class M3uResult(
    val channels: List<Channel>,
    val declaredEpgUrls: List<String>,
)
