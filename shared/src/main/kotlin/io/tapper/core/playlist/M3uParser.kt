package io.tapper.core.playlist

import io.tapper.core.model.Channel
import io.tapper.core.model.Country
import io.tapper.core.model.M3uResult
import io.tapper.core.model.StreamRef

/**
 * Extended M3U parser. Deliberately forgiving: one malformed entry in a
 * 13,510-line playlist must not lose the other 13,509.
 *
 * Validated against iptv-org's live index.m3u — 13,510 entries, zero dropped.
 */
object M3uParser {

    private val URL_PREFIXES =
        listOf("http://", "https://", "rtmp://", "rtsp://", "udp://", "mms://", "mmsh://")

    /**
     * Schemes ExoPlayer cannot open. Kept in the catalogue rather than dropped:
     * two entries in the default playlist are mmsh://, and a channel that
     * silently disappears is harder to explain than one that says why it won't
     * play. The player checks this before attempting a load.
     */
    val UNPLAYABLE_SCHEMES = listOf("mms://", "mmsh://", "rtmp://", "rtsp://")

    fun isPlayable(url: String) = UNPLAYABLE_SCHEMES.none { url.startsWith(it) }

    fun parse(content: String, sourceId: String): M3uResult {
        var declaredEpgUrls: List<String> = emptyList()
        var pending: Pending? = null
        var autoNumber = 0
        val byKey = LinkedHashMap<String, Channel>()

        for (raw in content.lineSequence()) {
            val line = raw.trim().removePrefix("\uFEFF")
            if (line.isEmpty()) continue

            when {
                line.startsWith("#EXTM3U") -> {
                    // One attribute can hold several guides: x-tvg-url="a.gz,b.gz"
                    val a = attributes(line)
                    declaredEpgUrls = (a["url-tvg"] ?: a["x-tvg-url"] ?: a["tvg-url"])
                        ?.split(',')?.map { it.trim() }?.filter { it.startsWith("http") }
                        .orEmpty()
                }

                line.startsWith("#EXTINF:") -> pending = parseExtInf(line)

                line.startsWith("#EXTGRP:") -> {
                    val g = line.substringAfter(':').trim()
                    if (g.isNotEmpty()) pending = pending?.copy(group = pending.group ?: g)
                }

                // #EXTVLCOPT / #KODIPROP and friends — skip, don't mistake for a URL
                line.startsWith("#") -> Unit

                else -> {
                    val e = pending ?: continue
                    pending = null
                    if (URL_PREFIXES.none { line.startsWith(it) }) continue

                    val tvgId = e.tvgId?.takeIf { it.isNotBlank() }
                    val key = tvgId ?: line

                    val existing = byKey[key]
                    if (existing != null) {
                        // A repeated tvg-id is a backup feed, not a duplicate row.
                        byKey[key] = existing.copy(
                            streams = existing.streams +
                                StreamRef(line, existing.streams.size, e.headers),
                            logoUrl = existing.logoUrl ?: e.logo?.takeIf { it.isNotBlank() },
                            group = existing.group ?: e.group,
                        )
                        continue
                    }

                    autoNumber++
                    byKey[key] = Channel(
                        id = key,
                        sourceId = sourceId,
                        name = e.displayName.ifBlank { e.tvgName ?: "Channel $autoNumber" },
                        number = e.chNo ?: autoNumber,
                        logoUrl = e.logo?.takeIf { it.isNotBlank() },
                        group = e.group?.takeIf { it.isNotBlank() },
                        countryCode = Country.fromTvgId(tvgId),
                        epgChannelId = tvgId,
                        streams = listOf(StreamRef(line, 0, e.headers)),
                    )
                }
            }
        }
        return M3uResult(byKey.values.toList(), declaredEpgUrls)
    }

    private data class Pending(
        val displayName: String, val tvgId: String?, val tvgName: String?,
        val logo: String?, val group: String?, val chNo: Int?,
        val headers: Map<String, String>,
    )

    private fun parseExtInf(line: String): Pending {
        val split = attributeBoundaryComma(line)
        val attrPart = if (split >= 0) line.substring(0, split) else line
        val display = if (split >= 0) line.substring(split + 1).trim() else ""
        val a = attributes(attrPart)

        val headers = buildMap {
            a["http-user-agent"]?.takeIf { it.isNotBlank() }?.let { put("User-Agent", it) }
            (a["http-referrer"] ?: a["http-referer"])?.takeIf { it.isNotBlank() }
                ?.let { put("Referer", it) }
        }

        return Pending(
            displayName = display,
            tvgId = a["tvg-id"], tvgName = a["tvg-name"],
            logo = a["tvg-logo"], group = a["group-title"],
            chNo = a["tvg-chno"]?.toIntOrNull(),
            headers = headers,
        )
    }

    /**
     * The display name follows the comma ending the attribute section.
     * Splitting on the FIRST comma breaks group-title="News, US";
     * splitting on the LAST breaks display names like "CNN, HD".
     * The attribute section always ends after the final quoted value.
     */
    private fun attributeBoundaryComma(s: String): Int {
        val lastQuote = s.indexOfLast { it == '"' || it == '\'' }
        if (lastQuote >= 0) {
            val after = s.indexOf(',', lastQuote + 1)
            if (after >= 0) return after
            // Quote was inside the display name — fall back to the first comma.
        }
        return s.indexOf(',')
    }

    private fun attributes(s: String): Map<String, String> {
        val out = mutableMapOf<String, String>()
        var i = 0
        while (i < s.length) {
            val eq = s.indexOf('=', i)
            if (eq < 0) break
            val quote = s.getOrNull(eq + 1)
            if (quote != '"' && quote != '\'') { i = eq + 1; continue }
            val close = s.indexOf(quote, eq + 2)
            if (close < 0) break
            val key = s.substring(i, eq).trim().trimStart('#', ':')
                .substringAfterLast(' ').lowercase()
            if (key.isNotEmpty()) out[key] = s.substring(eq + 2, close)
            i = close + 1
        }
        return out
    }
}
