package io.tapper.firetv.data

import android.util.Xml
import io.tapper.core.epg.XmltvTime
import io.tapper.core.xtream.XtreamClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import java.io.BufferedInputStream
import java.io.InputStream
import java.io.PushbackInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

/**
 * Downloads and stores XMLTV guides.
 *
 * The guide a playlist declares is not trustworthy. The default iptv-org
 * playlist names two XMLTV URLs; the first returns 404 and the second is a
 * free-tier worker. iptv-org deliberately does not host a guide at all. An
 * Xtream account, by contrast, serves its own guide matched to exactly the
 * channels it carries - which is why guide data only became worth building
 * once user-supplied sources existed.
 */
class EpgRepository(private val db: EpgDatabase, private val vault: CredentialVault) {

    companion object {
        private const val STALE_AFTER_MS = 6 * 60 * 60 * 1000L
        /** Keep a little history and three days ahead; the rest is dead weight. */
        private const val KEEP_BEFORE_MS = 6 * 60 * 60 * 1000L
        private const val KEEP_AFTER_MS = 72 * 60 * 60 * 1000L
        private const val MAX_ROWS = 400_000
    }

    data class Result(val programmes: Int, val channels: Int, val source: String)

    fun isStale(sourceId: String): Boolean =
        System.currentTimeMillis() - db.fetchedAt(sourceId) > STALE_AFTER_MS

    fun hasData(sourceId: String): Boolean = db.countFor(sourceId) > 0

    /**
     * Resolves which URL to use, in priority order: an explicit override, then
     * the provider's own guide, then whatever the playlist declared.
     */
    fun guideUrls(source: TvSource, declared: List<String>): List<String> = buildList {
        source.epgUrlOverride?.takeIf { it.isNotBlank() }?.let { add(it) }
        if (source.kind == TvSource.Kind.XTREAM) {
            vault.get(source.id)?.let { (u, p) ->
                add(XtreamClient(source.location, u, p).epgUrl())
            }
        }
        addAll(declared)
    }

    suspend fun refresh(source: TvSource, declared: List<String>): kotlin.Result<Result> =
        withContext(Dispatchers.IO) {
            val urls = guideUrls(source, declared)
            if (urls.isEmpty()) {
                return@withContext kotlin.Result.failure(
                    IllegalStateException("No guide URL for ${source.name}. Add one in the source settings.")
                )
            }
            var lastError: Throwable? = null
            // Try each candidate: dead guide URLs are the norm, not the exception.
            for (url in urls) {
                try {
                    val rows = open(url).use { parse(it) }
                    if (rows.isEmpty()) {
                        lastError = IllegalStateException("Guide at $url contained no programmes.")
                        continue
                    }
                    db.replaceAll(source.id, rows)
                    db.prune(System.currentTimeMillis() - KEEP_BEFORE_MS)
                    return@withContext kotlin.Result.success(
                        Result(rows.size, rows.map { it.channelId }.distinct().size, url)
                    )
                } catch (t: Throwable) {
                    lastError = t
                }
            }
            kotlin.Result.failure(lastError ?: IllegalStateException("Guide download failed."))
        }

    private fun open(url: String): InputStream {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 120_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "TapperIPTV/0.3")
        }
        if (conn.responseCode !in 200..299) {
            conn.disconnect()
            error("HTTP ${conn.responseCode}")
        }
        // Sniff for gzip rather than trusting the extension or Content-Encoding.
        // Guides are commonly served as .xml.gz with no encoding header, and
        // equally often as plain .xml behind a .gz filename.
        val push = PushbackInputStream(BufferedInputStream(conn.inputStream), 2)
        val b1 = push.read()
        val b2 = push.read()
        if (b2 != -1) push.unread(b2)
        if (b1 != -1) push.unread(b1)
        return if (b1 == 0x1f && b2 == 0x8b) GZIPInputStream(push) else push
    }

    /**
     * Streaming pull parse. Never materialises the document: a real guide is
     * 100-200MB of XML and several hundred thousand programme elements.
     */
    private fun parse(input: InputStream): List<EpgDatabase.Programme> {
        val now = System.currentTimeMillis()
        val floor = now - KEEP_BEFORE_MS
        val ceiling = now + KEEP_AFTER_MS

        val out = ArrayList<EpgDatabase.Programme>(8192)
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(input, null)

        var channelId: String? = null
        var start: Long? = null
        var stop: Long? = null
        var title: String? = null
        var descr: String? = null
        var inTitle = false
        var inDesc = false

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT && out.size < MAX_ROWS) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "programme" -> {
                        channelId = parser.getAttributeValue(null, "channel")
                        start = parser.getAttributeValue(null, "start")?.let(XmltvTime::parse)
                        stop = parser.getAttributeValue(null, "stop")?.let(XmltvTime::parse)
                        title = null; descr = null
                    }
                    "title" -> inTitle = true
                    "desc" -> inDesc = true
                }

                XmlPullParser.TEXT -> {
                    // Multi-language guides repeat title/desc per language; keep the first.
                    if (inTitle && title == null) title = parser.text?.trim()
                    if (inDesc && descr == null) descr = parser.text?.trim()
                }

                XmlPullParser.END_TAG -> when (parser.name) {
                    "title" -> inTitle = false
                    "desc" -> inDesc = false
                    "programme" -> {
                        val ch = channelId; val s = start; val e = stop
                        // Drop anything that cannot be placed on a timeline. A
                        // wrong guide slot is worse than a gap.
                        if (ch != null && s != null && e != null && e > s && e > floor && s < ceiling) {
                            out.add(
                                EpgDatabase.Programme(
                                    channelId = ch,
                                    startUtc = s,
                                    endUtc = e,
                                    title = title?.takeIf { it.isNotBlank() } ?: "No information",
                                    description = descr?.takeIf { it.isNotBlank() },
                                )
                            )
                        }
                        channelId = null; start = null; stop = null
                    }
                }
            }
            event = parser.next()
        }
        return out
    }
}
