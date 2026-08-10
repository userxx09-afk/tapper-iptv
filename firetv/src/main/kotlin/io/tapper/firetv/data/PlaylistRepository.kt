package io.tapper.firetv.data

import io.tapper.core.model.Channel
import io.tapper.core.model.Country
import io.tapper.core.playlist.M3uParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

/**
 * v0.1 keeps the catalogue in memory rather than SQLite.
 *
 * 13,510 channels is roughly 6MB of objects — comfortable even on a 1GB stick,
 * and it removes the entire database layer from the first build. SQLite becomes
 * necessary when EPG arrives, because a guide is 400k rows and cannot live in
 * memory. Not before.
 */
class PlaylistRepository(private val cacheDir: File) {

    companion object {
        const val BUILTIN_ID = "iptv-org"
        const val BUILTIN_URL = "https://iptv-org.github.io/iptv/index.m3u"
        private const val CACHE_MAX_AGE_MS = 12 * 60 * 60 * 1000L
    }

    data class Catalogue(
        val channels: List<Channel>,
        val byCountry: Map<String?, List<Channel>>,
        val countryOrder: List<String?>,
        val fromCache: Boolean,
    )

    private val cacheFile get() = File(cacheDir, "$BUILTIN_ID.m3u")

    suspend fun load(forceRefresh: Boolean = false): Result<Catalogue> = withContext(Dispatchers.IO) {
        runCatching {
            val fresh = cacheFile.exists() &&
                System.currentTimeMillis() - cacheFile.lastModified() < CACHE_MAX_AGE_MS

            var fromCache = false
            val text = if (!forceRefresh && fresh) {
                fromCache = true
                cacheFile.readText()
            } else {
                try {
                    download(BUILTIN_URL).also { cacheFile.writeText(it) }
                } catch (t: Throwable) {
                    // Network down but a stale copy exists — showing yesterday's
                    // channel list beats showing an error screen.
                    if (cacheFile.exists()) { fromCache = true; cacheFile.readText() }
                    else throw t
                }
            }

            val parsed = M3uParser.parse(text, BUILTIN_ID)
            index(parsed.channels, fromCache)
        }
    }

    private fun index(channels: List<Channel>, fromCache: Boolean): Catalogue {
        val byCountry = channels.groupBy { it.countryCode }
        // Ordered by channel count, not alphabetically. The distribution is
        // steeply long-tailed (us=2016 ... 14 singletons) and on a remote the
        // list you want must be reachable without paging.
        val order = byCountry.entries
            .sortedWith(
                compareByDescending<Map.Entry<String?, List<Channel>>> { it.key != null }
                    .thenByDescending { it.value.size }
            )
            .map { it.key }
        return Catalogue(channels, byCountry, order, fromCache)
    }

    private fun download(url: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 60_000
            setRequestProperty("Accept-Encoding", "gzip")
            setRequestProperty("User-Agent", "TapperIPTV/0.1")
        }
        try {
            if (conn.responseCode !in 200..299) {
                error("Playlist fetch failed: HTTP ${conn.responseCode}")
            }
            val stream = if (conn.contentEncoding?.contains("gzip", true) == true) {
                GZIPInputStream(conn.inputStream)
            } else conn.inputStream
            return stream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    fun regionLabel(code: String?) = Country.regionOf(code).label
    fun countryLabel(code: String?) = Country.label(code)
}
