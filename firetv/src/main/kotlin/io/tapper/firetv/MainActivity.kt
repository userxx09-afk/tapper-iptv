package io.tapper.firetv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.lifecycle.lifecycleScope
import io.tapper.core.model.Channel
import io.tapper.core.xtream.XtreamClient
import io.tapper.firetv.data.PlaylistRepository
import io.tapper.firetv.data.TvSource
import io.tapper.firetv.ui.AddSourceScreen
import io.tapper.firetv.ui.BrowseScreen
import io.tapper.firetv.ui.LoadingScreen
import io.tapper.firetv.ui.PlayerScreen
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
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as TapperApp

        setContent {
            var screen by remember { mutableStateOf<Screen>(Screen.Loading) }
            var playing by remember { mutableStateOf<Channel?>(null) }
            var sources by remember { mutableStateOf(app.sourceStore.all()) }
            var active by remember { mutableStateOf(app.sourceStore.active()) }
            var busy by remember { mutableStateOf(false) }
            var addError by remember { mutableStateOf<String?>(null) }

            fun loadActive(force: Boolean = false) {
                screen = Screen.Loading
                lifecycleScope.launch {
                    screen = app.repository.load(active, force).fold(
                        onSuccess = { Screen.Browse(it) },
                        onFailure = { Screen.Failed(it.message ?: "Couldn't load this source.") },
                    )
                }
            }

            LaunchedEffect(active.id) { loadActive() }

            TapperTheme {
                val channel = playing
                val current = screen
                when {
                    channel != null -> PlayerScreen(channel) { playing = null }

                    current is Screen.AddSource -> AddSourceScreen(
                        busy = busy,
                        error = addError,
                        onCancel = { addError = null; loadActive() },
                        onSubmitXtream = { name, host, user, pass ->
                            busy = true; addError = null
                            lifecycleScope.launch {
                                val id = "xtream-" + UUID.randomUUID().toString().take(8)
                                val result = withContext(Dispatchers.IO) {
                                    // Validate before saving, so a typo never becomes
                                    // a broken source sitting in the list.
                                    runCatching { XtreamClient(host, user, pass).authenticate() }
                                }
                                busy = false
                                result.fold(
                                    onSuccess = {
                                        app.vault.put(id, user, pass)
                                        app.sourceStore.add(
                                            TvSource(id, name, TvSource.Kind.XTREAM, host)
                                        )
                                        app.sourceStore.activeId = id
                                        sources = app.sourceStore.all()
                                        active = app.sourceStore.active()
                                        addError = null
                                    },
                                    onFailure = { addError = it.message ?: "Couldn't connect." },
                                )
                            }
                        },
                        onSubmitM3u = { name, url, epg ->
                            val id = "m3u-" + UUID.randomUUID().toString().take(8)
                            app.sourceStore.add(TvSource(id, name, TvSource.Kind.M3U, url, epg))
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
                        onPlay = { playing = it },
                        onSwitchSource = {
                            app.sourceStore.activeId = it.id
                            active = it
                        },
                        onAddSource = { addError = null; screen = Screen.AddSource },
                    )
                }
            }
        }
    }
}
