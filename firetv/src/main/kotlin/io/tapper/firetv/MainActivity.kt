package io.tapper.firetv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.lifecycle.lifecycleScope
import io.tapper.core.model.Channel
import io.tapper.core.model.ContentKind
import io.tapper.core.xtream.XtreamClient
import io.tapper.firetv.data.EpgDatabase
import io.tapper.firetv.data.PlaylistRepository
import io.tapper.firetv.data.TvSource
import io.tapper.firetv.ui.AddSourceScreen
import io.tapper.firetv.ui.BrowseScreen
import io.tapper.firetv.ui.EpisodesScreen
import io.tapper.firetv.ui.FailureScreen
import io.tapper.firetv.ui.SettingsScreen
import io.tapper.firetv.ui.LoadingScreen
import io.tapper.firetv.ui.PlayerScreen
import io.tapper.firetv.ui.SearchScreen
import io.tapper.firetv.ui.theme.TapperTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

class MainActivity : ComponentActivity() {

    private sealed interface Screen {
        data object Loading : Screen
        data class Failed(val message: String) : Screen
        data class Browse(val catalogue: PlaylistRepository.Catalogue) : Screen
        data object AddSource : Screen
        data object Search : Screen
        data object Settings : Screen
        data class Series(val series: Channel) : Screen
    }

    private data class Playing(val channels: List<Channel>, val index: Int)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as TapperApp

        setContent {
            // A real stack, so Back always goes up one level from any screen
            // rather than each screen inventing its own idea of "back".
            var stack by remember { mutableStateOf<List<Screen>>(listOf(Screen.Loading)) }
            val screen = stack.last()
            fun push(s: Screen) { stack = stack + s }
            fun pop() { if (stack.size > 1) stack = stack.dropLast(1) }
            fun replaceAll(s: Screen) { stack = listOf(s) }
            var playing by remember { mutableStateOf<Playing?>(null) }
            var sources by remember { mutableStateOf(app.sourceStore.all()) }
            var active by remember { mutableStateOf(app.sourceStore.active()) }
            var busy by remember { mutableStateOf(false) }
            var addError by remember { mutableStateOf<String?>(null) }
            var nowPlaying by remember { mutableStateOf<Map<String, EpgDatabase.Programme>>(emptyMap()) }
            var epgStatus by remember { mutableStateOf<String?>(null) }
            var episodes by remember { mutableStateOf<List<Channel>>(emptyList()) }
            var episodesLoading by remember { mutableStateOf(false) }
            var episodesError by remember { mutableStateOf<String?>(null) }

            fun reloadNowPlaying() {
                lifecycleScope.launch {
                    val map = withContext(Dispatchers.IO) {
                        runCatching { app.epgDb.nowPlaying(active.id, System.currentTimeMillis()) }
                            .getOrDefault(emptyMap())
                    }
                    nowPlaying = map
                }
            }

            fun refreshEpg(catalogue: PlaylistRepository.Catalogue?) {
                epgStatus = "Guide: updating..."
                lifecycleScope.launch {
                    val result = app.epg.refresh(active, catalogue?.declaredEpgUrls.orEmpty())
                    epgStatus = result.fold(
                        onSuccess = { "Guide: ${it.programmes} entries, ${it.channels} channels" },
                        // Dead guide URLs are routine, so this is informational
                        // rather than an error screen - the app still works.
                        onFailure = { "Guide unavailable: ${it.message?.take(60)}" },
                    )
                    reloadNowPlaying()
                }
            }

            fun loadActive(force: Boolean = false) {
                replaceAll(Screen.Loading)
                lifecycleScope.launch {
                    val res = app.repository.load(active, force)
                    replaceAll(
                        res.fold(
                            onSuccess = { Screen.Browse(it) },
                            onFailure = { Screen.Failed(it.message ?: "Couldn't load this source.") },
                        )
                    )
                    res.getOrNull()?.let { cat ->
                        epgStatus = if (app.epg.hasData(active.id)) null else "Guide: none yet"
                        reloadNowPlaying()
                        // Refresh in the background; the channel list is already
                        // usable and must not wait on a 100MB guide download.
                        if (app.epg.isStale(active.id)) refreshEpg(cat)
                    }
                }
            }

            LaunchedEffect(active.id) { loadActive() }

            // The catalogue survives navigation: it lives on the Browse entry
            // at the bottom of the stack, so Settings and Search do not lose it.
            val catalogue = stack.filterIsInstance<Screen.Browse>().lastOrNull()?.catalogue

            fun openSeries(series: Channel) {
                push(Screen.Series(series))
                episodes = emptyList(); episodesError = null; episodesLoading = true
                lifecycleScope.launch {
                    val id = series.seriesId
                    if (id == null) {
                        episodesLoading = false
                        episodesError = "This item has no series id."
                        return@launch
                    }
                    val res = app.repository.episodes(active, id)
                    episodesLoading = false
                    res.fold(
                        onSuccess = { episodes = it },
                        onFailure = { episodesError = it.message ?: "Couldn't load episodes." },
                    )
                }
            }

            fun channelForEpgId(epgId: String): Channel? =
                catalogue?.channels?.firstOrNull { it.epgChannelId == epgId }

            TapperTheme {
                val p = playing
                val current = screen
                when {
                    p != null -> PlayerScreen(
                        channels = p.channels,
                        startIndex = p.index,
                        nowPlaying = { ch -> ch.epgChannelId?.let { nowPlaying[it] } },
                        onExit = { playing = null },
                    )

                    current is Screen.Series -> EpisodesScreen(
                        series = current.series,
                        episodes = episodes,
                        loading = episodesLoading,
                        error = episodesError,
                        onPlay = { list, i -> playing = Playing(list, i) },
                        onExit = { pop() },
                    )

                    current is Screen.Search && catalogue != null -> SearchScreen(
                        channels = catalogue.channels,
                        searchProgrammes = { q ->
                            app.epgDb.search(active.id, q, System.currentTimeMillis())
                        },
                        channelForEpgId = ::channelForEpgId,
                        onPlay = { ch ->
                            // A series has no stream; selecting one from search
                            // must open its episode list, not the player.
                            if (ch.kind == ContentKind.SERIES) openSeries(ch)
                            else { playing = Playing(listOf(ch), 0); pop() }
                        },
                        onExit = { pop() },
                    )

                    current is Screen.AddSource -> AddSourceScreen(
                        busy = busy,
                        error = addError,
                        onCancel = { addError = null; loadActive() },
                        onSubmitXtream = { name, host, user, pass ->
                            busy = true; addError = null
                            lifecycleScope.launch {
                                val id = "xtream-" + UUID.randomUUID().toString().take(8)
                                val result = withContext(Dispatchers.IO) {
                                    runCatching { XtreamClient(host, user, pass).authenticate() }
                                }
                                busy = false
                                result.fold(
                                    onSuccess = {
                                        app.vault.put(id, user, pass)
                                        app.sourceStore.add(TvSource(id, name, TvSource.Kind.XTREAM, host))
                                        app.sourceStore.activeId = id
                                        sources = app.sourceStore.all()
                                        active = app.sourceStore.active()
                                        addError = null
                                    },
                                    onFailure = { addError = it.message ?: "Couldn't connect." },
                                )
                            }
                        },
                        onSubmitM3u = { name, url, epgUrl ->
                            val id = "m3u-" + UUID.randomUUID().toString().take(8)
                            app.sourceStore.add(TvSource(id, name, TvSource.Kind.M3U, url, epgUrl))
                            app.sourceStore.activeId = id
                            sources = app.sourceStore.all()
                            active = app.sourceStore.active()
                            addError = null
                        },
                    )

                    current is Screen.Loading -> LoadingScreen("Loading " + active.name + "...")

                    current is Screen.Failed -> FailureScreen(
                        sourceName = active.name,
                        message = current.message,
                        canFallBack = active.id != TvSource.BUILTIN.id,
                        onRetry = { loadActive(force = true) },
                        onSettings = { push(Screen.Settings) },
                        onUseBuiltIn = {
                            app.sourceStore.activeId = TvSource.BUILTIN.id
                            active = TvSource.BUILTIN
                        },
                    )

                    current is Screen.Settings -> SettingsScreen(
                        sources = sources,
                        activeId = active.id,
                        guideSummary = epgStatus
                            ?: if (app.epg.hasData(active.id)) "Guide loaded." else "No guide data yet.",
                        onSwitchSource = {
                            app.sourceStore.activeId = it.id
                            active = it
                            pop()
                        },
                        onAddSource = { addError = null; push(Screen.AddSource) },
                        onRemoveSource = { s2 ->
                            app.vault.delete(s2.id)
                            app.sourceStore.remove(s2.id)
                            sources = app.sourceStore.all()
                            if (active.id == s2.id) {
                                app.sourceStore.activeId = TvSource.BUILTIN.id
                                active = app.sourceStore.active()
                            }
                        },
                        onSetEpgUrl = { s2, url ->
                            app.sourceStore.update(s2.copy(epgUrlOverride = url))
                            sources = app.sourceStore.all()
                            if (active.id == s2.id) active = app.sourceStore.active()
                        },
                        onRefreshGuide = { refreshEpg(catalogue) },
                        onClearCache = { app.repository.clearCache() },
                        onExit = { pop() },
                    )

                    current is Screen.Browse -> BrowseScreen(
                        catalogue = current.catalogue,
                        repo = app.repository,
                        favorites = app.favorites,
                        sources = sources,
                        activeSource = active,
                        nowPlaying = nowPlaying,
                        epgStatus = epgStatus,
                        onPlay = { list, i -> playing = Playing(list, i) },
                        onSwitchSource = { app.sourceStore.activeId = it.id; active = it },
                        onAddSource = { addError = null; push(Screen.AddSource) },
                        onSearch = { push(Screen.Search) },
                        onSettings = { push(Screen.Settings) },
                        onRefreshEpg = { refreshEpg(current.catalogue) },
                        onOpenSeries = { openSeries(it) },
                    )
                }
            }
        }
    }
}
