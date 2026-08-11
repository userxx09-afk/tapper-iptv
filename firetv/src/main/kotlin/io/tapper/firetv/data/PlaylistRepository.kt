package io.tapper.firetv.data

import io.tapper.core.model.Channel
import io.tapper.core.model.Country
import io.tapper.core.playlist.M3uParser
import io.tapper.core.xtream.XtreamClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

/**
 * Loads a source catalogue into memory.
 *
 * 13,510 channels is roughly 6MB of objects - fine even on a 1GB stick, and it
 * keeps a database out of this layer entirely. SQLite becomes necessary for the
 * EPG, which is ~400k rows and cannot live in memory. Not before.
 */
class PlaylistRepository(
    private val cacheDir: File,
    private val vault: CredentialVault,
) {
    companion object {
        private const val CACHE_MAX_AGE_MS = 12 * 60 * 60 * 1000L
    }

    data class Catalogue(
        val sourceId: String,
        val channels: List<Channel>,
        val byCountry: Map<String?, List<Channel>>,
        val countryOrder: List<String?>,
        val fromCache: Boolean,
    )

    private fun cacheFile(sourceId: String) = File(cacheDir, "$sourceId.m3u")

    suspend fun load(source: TvSource, forceRefresh: Boolean = false): Result<Catalogue> =
        withContext(Dispatchers.IO) {
            runCatching {
                when (source.kind) {
                    TvSource.Kind.M3U -> loadM3u(source, forceRefresh)
                    TvSource.Kind.XTREAM -> loadXtream(source)
                }
            }
        }

    private fun loadM3u(source: TvSource, forceRefresh: Boolean): Catalogue {
        val cache = cacheFile(source.id)
        val fresh = cache.exists() &&
            System.currentTimeMillis() - cache.lastModified() < CACHE_MAX_AGE_MS

        var fromCache = false
        val text = if (!forceRefresh && fresh) {
            fromCache = true; cache.readText()
        } else {
            try {
                download(source.location).also { cache.writeText(it) }
            } catch (t: Throwable) {
                // Network down but a stale copy exists - yesterday's channel list
                // beats an error screen.
                if (cache.exists()) { fromCache = true; cache.readText() } else throw t
            }
        }
        return index(source.id, M3uParser.parse(text, source.id).channels, fromCache)
    }

    private fun loadXtream(source: TvSource): Catalogue {
        val creds = vault.get(source.id)
            ?: error("No saved credentials for ${source.name}. Remove and re-add it.")
        val client = XtreamClient(source.location, creds.first, creds.second)
        val account = client.authenticate()
        if (!account.isActive) error("Provider reports this account as ${account.status}.")
        return index(source.id, client.liveChannels(source.id), fromCache = false)
    }

    private fun index(sourceId: String, channels: List<Channel>, fromCache: Boolean): Catalogue {
        val byCountry = channels.groupBy { it.countryCode }
        // Ordered by channel count, not alphabetically: the distribution is steeply
        // long-tailed and on a remote the list you want must be reachable fast.
        val order = byCountry.entries
            .sortedWith(
                compareByDescending<Map.Entry<String?, List<Channel>>> { it.key != null }
                    .thenByDescending { it.value.size }
            )
            .map { it.key }
        return Catalogue(sourceId, channels, byCountry, order, fromCache)
    }

    private fun download(url: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            setRequestProperty("Accept-Encoding", "gzip")
            setRequestProperty("User-Agent", "TapperIPTV/0.2")
        }
        try {
            if (conn.responseCode !in 200..299) error("Playlist fetch failed: HTTP ${conn.responseCode}")
            val stream = if (conn.contentEncoding?.contains("gzip", true) == true)
                GZIPInputStream(conn.inputStream) else conn.inputStream
            return stream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    fun countryLabel(code: String?): String =
        if (code == null) "Ungrouped" else Country.label(code)
}
