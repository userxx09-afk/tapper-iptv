package io.tapper.firetv.data

import io.tapper.core.model.Channel
import io.tapper.core.model.ContentKind
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
 * 13,510 channels is roughly 6MB of objects - fine even on a 1GB stick. Only
 * the EPG needs a database, because a guide is hundreds of thousands of rows.
 */
class PlaylistRepository(
    private val cacheDir: File,
    private val vault: CredentialVault,
) {
    companion object {
        private const val CACHE_MAX_AGE_MS = 12 * 60 * 60 * 1000L
    }

    /** One entry in a browse rail. */
    data class Group(val key: String?, val label: String, val channels: List<Channel>)

    enum class Axis { COUNTRY, CATEGORY }

    /** Grouping for one content kind. */
    data class Section(
        val kind: ContentKind,
        val items: List<Channel>,
        val byCountry: List<Group>,
        val byCategory: List<Group>,
    ) {
        /**
         * Pick the axis that actually divides this section. A provider whose
         * categories carry no country token yields a single "Ungrouped"
         * country rail, which is useless - fall through to categories then.
         */
        val defaultAxis: Axis
            get() = if (byCountry.count { it.key != null } > 1) Axis.COUNTRY else Axis.CATEGORY

        fun groups(axis: Axis) = if (axis == Axis.COUNTRY) byCountry else byCategory
    }

    data class Catalogue(
        val sourceId: String,
        val channels: List<Channel>,
        val sections: Map<ContentKind, Section>,
        val declaredEpgUrls: List<String>,
        val fromCache: Boolean,
    ) {
        /** Only kinds that actually have content get a tab. */
        val availableKinds: List<ContentKind>
            get() = ContentKind.entries.filter { sections[it]?.items?.isNotEmpty() == true }

        fun section(kind: ContentKind): Section? = sections[kind]

        val byCountry: List<Group> get() = sections[ContentKind.LIVE]?.byCountry.orEmpty()
        val byCategory: List<Group> get() = sections[ContentKind.LIVE]?.byCategory.orEmpty()
    }

    suspend fun load(source: TvSource, forceRefresh: Boolean = false): Result<Catalogue> =
        withContext(Dispatchers.IO) {
            runCatching {
                when (source.kind) {
                    TvSource.Kind.M3U -> loadM3u(source, forceRefresh)
                    TvSource.Kind.XTREAM -> loadXtream(source)
                }
            }
        }

    private fun cacheFile(sourceId: String) = File(cacheDir, "$sourceId.m3u")

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
        val parsed = M3uParser.parse(text, source.id)
        return index(source.id, parsed.channels, parsed.declaredEpgUrls, fromCache)
    }

    private fun loadXtream(source: TvSource): Catalogue {
        val creds = vault.get(source.id)
            ?: error("No saved credentials for ${source.name}. Remove and re-add it.")
        val client = XtreamClient(source.location, creds.first, creds.second)
        val account = client.authenticate()
        if (!account.isActive) error("Provider reports this account as ${account.status}.")

        // Live is the one that must succeed. Plenty of accounts carry no VOD at
        // all, and a panel that 404s on get_vod_streams should not take the
        // whole source down with it.
        val live = client.liveChannels(source.id)
        val movies = runCatching { client.movies(source.id) }.getOrDefault(emptyList())
        val series = runCatching { client.series(source.id) }.getOrDefault(emptyList())
        return index(source.id, live + movies + series, emptyList(), fromCache = false)
    }

    /** Episodes for a series, fetched on demand rather than up front. */
    suspend fun episodes(source: TvSource, seriesId: String): Result<List<Channel>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val creds = vault.get(source.id) ?: error("No saved credentials.")
                XtreamClient(source.location, creds.first, creds.second)
                    .episodes(source.id, seriesId)
            }
        }

    private fun index(
        sourceId: String,
        channels: List<Channel>,
        declaredEpgUrls: List<String>,
        fromCache: Boolean,
    ): Catalogue {
        // Rails are ordered by channel count, not alphabetically. The
        // distribution is steeply long-tailed and on a remote the rail you want
        // must be reachable without paging.
        fun build(by: Map<String?, List<Channel>>, label: (String?) -> String) =
            by.entries
                .sortedWith(
                    compareByDescending<Map.Entry<String?, List<Channel>>> { it.key != null }
                        .thenByDescending { it.value.size }
                )
                .map { Group(it.key, label(it.key), it.value) }

        fun sectionFor(kind: ContentKind): Section {
            val items = channels.filter { it.kind == kind }
            // An item with several categories appears under each of them; a
            // "Documentary;Series" entry belongs in both lists, not one.
            val byCat = HashMap<String?, MutableList<Channel>>()
            for (c in items) {
                val keys = c.categories.ifEmpty { listOfNotNull(c.group) }.ifEmpty { listOf(null) }
                for (k in keys) byCat.getOrPut(k) { ArrayList() }.add(c)
            }
            return Section(
                kind = kind,
                items = items,
                byCountry = build(items.groupBy { it.countryCode }) {
                    if (it == null) "Ungrouped" else Country.label(it)
                },
                byCategory = build(byCat.mapValues { it.value.toList() }) { it ?: "Uncategorised" },
            )
        }

        val sections = ContentKind.entries.associateWith { sectionFor(it) }
        return Catalogue(sourceId, channels, sections, declaredEpgUrls, fromCache)
    }

    private fun download(url: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            setRequestProperty("Accept-Encoding", "gzip")
            setRequestProperty("User-Agent", "TapperIPTV/0.3")
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

    /** Categories present within one country, for the secondary filter row. */
    fun categoriesIn(channels: List<Channel>): List<String> =
        channels.mapNotNull { it.group?.takeIf { g -> g.isNotBlank() } }
            .groupingBy { it }.eachCount()
            .entries.sortedByDescending { it.value }.map { it.key }
}
