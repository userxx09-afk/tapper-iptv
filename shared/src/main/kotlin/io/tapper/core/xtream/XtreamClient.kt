package io.tapper.core.xtream

import io.tapper.core.model.Channel
import io.tapper.core.model.StreamRef
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Xtream Codes panel client. Uses HttpURLConnection and org.json - both are in
 * the platform, so this adds no dependencies.
 *
 * Panels are inconsistent in ways that matter: numeric fields arrive as JSON
 * numbers on one endpoint and quoted strings on the next, and a rejected login
 * is commonly an HTML page served with HTTP 200. Everything here assumes that.
 */
class XtreamClient(
    private val host: String,
    private val username: String,
    private val password: String,
) {
    class XtreamException(message: String, cause: Throwable? = null) : Exception(message, cause)

    private val base = host.trim().trimEnd('/').let {
        if (it.startsWith("http://") || it.startsWith("https://")) it else "http://$it"
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun api(action: String?) = buildString {
        append("$base/player_api.php?username=${enc(username)}&password=${enc(password)}")
        if (action != null) append("&action=$action")
    }

    /** Full guide for this account, matched to exactly the channels it carries. */
    fun epgUrl() = "$base/xmltv.php?username=${enc(username)}&password=${enc(password)}"

    fun liveUrl(streamId: String, ext: String = "ts") =
        "$base/live/${enc(username)}/${enc(password)}/$streamId.$ext"

    private fun fetch(url: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("User-Agent", "TapperIPTV/0.2")
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw XtreamException("Server returned HTTP $code")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } catch (e: XtreamException) {
            throw e
        } catch (t: Throwable) {
            throw XtreamException("Couldn't reach $base", t)
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Validates the account and returns what the panel says about it. Surfacing
     * this is worth the extra call - "your subscription expired" is the single
     * most common cause of an app that looks broken.
     */
    fun authenticate(): XtreamAccount {
        val body = fetch(api(null))
        if (body.trimStart().startsWith("<")) {
            throw XtreamException("The server returned a web page, not account data. Check the host address.")
        }
        val root = try { JSONObject(body) } catch (t: Throwable) {
            throw XtreamException("Unexpected response from the server.", t)
        }
        val info = root.optJSONObject("user_info")
            ?: throw XtreamException("No account information returned.")

        if (info.lenientInt("auth") == 0) throw XtreamException("Username or password rejected.")

        return XtreamAccount(
            username = info.lenientString("username") ?: username,
            status = info.lenientString("status") ?: "Unknown",
            expiresUtc = info.lenientLong("exp_date")?.takeIf { it > 0 }?.times(1000L),
            maxConnections = info.lenientInt("max_connections") ?: 1,
            activeConnections = info.lenientInt("active_cons") ?: 0,
            trial = info.lenientInt("is_trial") == 1,
        )
    }

    fun liveChannels(sourceId: String, preferHls: Boolean = false): List<Channel> {
        val cats = runCatching { categoryNames() }.getOrDefault(emptyMap())
        val arr = JSONArray(fetch(api("get_live_streams")))
        val ext = if (preferHls) "m3u8" else "ts"
        val out = ArrayList<Channel>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.lenientString("stream_id") ?: continue
            val name = o.lenientString("name")?.trim() ?: continue
            out.add(
                Channel(
                    id = id,
                    sourceId = sourceId,
                    name = name,
                    number = o.lenientInt("num") ?: (i + 1),
                    logoUrl = o.lenientString("stream_icon")?.takeIf { it.isNotBlank() },
                    group = o.lenientString("category_id")?.let { cats[it] },
                    // Xtream has no country field; grouping falls back to category.
                    countryCode = null,
                    epgChannelId = o.lenientString("epg_channel_id")?.takeIf { it.isNotBlank() },
                    streams = listOf(StreamRef(liveUrl(id, ext), 0)),
                )
            )
        }
        return out
    }

    private fun categoryNames(): Map<String, String> {
        val arr = JSONArray(fetch(api("get_live_categories")))
        val map = HashMap<String, String>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.lenientString("category_id") ?: continue
            map[id] = o.lenientString("category_name") ?: "Unnamed"
        }
        return map
    }
}

data class XtreamAccount(
    val username: String,
    val status: String,
    val expiresUtc: Long?,
    val maxConnections: Int,
    val activeConnections: Int,
    val trial: Boolean,
) {
    val isActive get() = status.equals("Active", ignoreCase = true)

    fun daysRemaining(nowUtc: Long): Long? =
        expiresUtc?.let { (it - nowUtc) / 86_400_000L }

    fun summary(nowUtc: Long): String = buildString {
        append(status)
        daysRemaining(nowUtc)?.let {
            append(if (it < 0) " - expired" else " - $it days left")
        }
        append(" | $maxConnections stream")
        if (maxConnections != 1) append("s")
    }
}

// Panels vary field types between endpoints, so never trust getInt/getString.
private fun JSONObject.lenientString(key: String): String? {
    if (isNull(key)) return null
    val v = opt(key) ?: return null
    val s = v.toString()
    return if (s == "null" || s.isEmpty()) null else s
}
private fun JSONObject.lenientInt(key: String): Int? = lenientString(key)?.toIntOrNull()
private fun JSONObject.lenientLong(key: String): Long? = lenientString(key)?.toLongOrNull()
