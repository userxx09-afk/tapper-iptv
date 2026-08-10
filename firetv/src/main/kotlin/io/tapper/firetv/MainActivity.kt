package io.tapper.firetv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.lifecycle.lifecycleScope
import io.tapper.core.model.Channel
import io.tapper.firetv.data.PlaylistRepository
import io.tapper.firetv.ui.BrowseScreen
import io.tapper.firetv.ui.LoadingScreen
import io.tapper.firetv.ui.PlayerScreen
import io.tapper.firetv.ui.theme.TapperTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private sealed interface State {
        data object Loading : State
        data class Ready(val catalogue: PlaylistRepository.Catalogue) : State
        data class Failed(val message: String) : State
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val repo = (application as TapperApp).repository

        setContent {
            var state by remember { mutableStateOf<State>(State.Loading) }
            var playing by remember { mutableStateOf<Channel?>(null) }

            LaunchedEffect(Unit) {
                lifecycleScope.launch {
                    state = repo.load().fold(
                        onSuccess = { State.Ready(it) },
                        onFailure = { State.Failed(it.message ?: "Couldn't load the channel list.") },
                    )
                }
            }

            TapperTheme {
                when (val s = state) {
                    is State.Loading -> LoadingScreen("Loading channels…")
                    is State.Failed -> LoadingScreen(s.message)
                    is State.Ready -> {
                        val channel = playing
                        if (channel != null) {
                            PlayerScreen(channel = channel, onExit = { playing = null })
                        } else {
                            BrowseScreen(
                                catalogue = s.catalogue,
                                repo = repo,
                                onPlay = { playing = it },
                            )
                        }
                    }
                }
            }
        }
    }
}
