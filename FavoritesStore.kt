package io.tapper.firetv.data

import android.content.Context

/**
 * Favourites and pinned countries.
 *
 * Favourites are keyed "sourceId|channelId" and deliberately span sources — a
 * favourites list organised by provider would be useless, since the whole point
 * is one place for the handful of channels actually watched.
 */
class FavoritesStore(context: Context) {

    private val prefs = context.getSharedPreferences("tapper_favorites", Context.MODE_PRIVATE)

    private fun key(sourceId: String, channelId: String) = "$sourceId|$channelId"

    fun favorites(): Set<String> = prefs.getStringSet("ids", emptySet()) ?: emptySet()

    fun isFavorite(sourceId: String, channelId: String) = key(sourceId, channelId) in favorites()

    fun toggle(sourceId: String, channelId: String): Boolean {
        val k = key(sourceId, channelId)
        val next = favorites().toMutableSet()
        val added = if (k in next) { next.remove(k); false } else { next.add(k); true }
        // A new Set instance is required: SharedPreferences does not copy the set
        // it is handed, so mutating the original in place silently does nothing.
        prefs.edit().putStringSet("ids", HashSet(next)).apply()
        return added
    }

    fun pinnedCountries(): Set<String> = prefs.getStringSet("pinned", emptySet()) ?: emptySet()

    fun togglePinned(code: String): Boolean {
        val next = pinnedCountries().toMutableSet()
        val added = if (code in next) { next.remove(code); false } else { next.add(code); true }
        prefs.edit().putStringSet("pinned", HashSet(next)).apply()
        return added
    }
}
