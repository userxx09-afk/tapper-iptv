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
        data class Series(val series: Channel, val back: Screen.Browse) : Screen
    }

    private data class Playing(val channels: List<Channel>, val index: Int)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as TapperApp

        setContent {
            var screen by remember { mutableStateOf<Screen>(Screen.Loading) }
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
                screen = Screen.Loading
                lifecycleScope.launch {
                    val res = app.repository.load(active, force)
                    screen = res.fold(
                        onSuccess = { Screen.Browse(it) },
                        onFailure = { Screen.Failed(it.message ?: "Couldn't load this source.") },
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

            val catalogue = when (val sc = screen) {
                is Screen.Browse -> sc.catalogue
                is Screen.Series -> sc.back.catalogue
                else -> null
            }

            fun openSeries(series: Channel, from: Screen.Browse) {
                screen = Screen.Series(series, from)
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
                        onExit = { screen = current.back },
                    )

                    current is Screen.Search && catalogue != null -> SearchScreen(
                        channels = catalogue.channels,
                        searchProgrammes = { q ->
                            app.epgDb.search(active.id, q, System.currentTimeMillis())
                        },
                        channelForEpgId = ::channelForEpgId,
                        onPlay = { ch ->
                            val back = Screen.Browse(catalogue)
                            // A series has no stream; selecting one from search
                            // must open its episode list, not the player.
                            if (ch.kind == ContentKind.SERIES) openSeries(ch, back)
                            else { playing = Playing(listOf(ch), 0); screen = back }
                        },
                        onExit = { screen = Screen.Browse(catalogue) },
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

                    current is Screen.Failed -> LoadingScreen(current.message)

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
                        onAddSource = { addError = null; screen = Screen.AddSource },
                        onSearch = { screen = Screen.Search },
                        onRefreshEpg = { refreshEpg(current.catalogue) },
                        onOpenSeries = { openSeries(it, current) },
                    )
                }
            }
        }
    }
}
