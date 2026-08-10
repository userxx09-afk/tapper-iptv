package io.tapper.firetv.ui

import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.ui.PlayerView
import io.tapper.core.model.Channel
import io.tapper.core.playback.Diagnosis
import io.tapper.firetv.player.TapperPlayer
import io.tapper.firetv.ui.theme.Backdrop
import io.tapper.firetv.ui.theme.Dim
import io.tapper.firetv.ui.theme.Ink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

@Composable
fun PlayerScreen(channel: Channel, onExit: () -> Unit) {
    val context = LocalContext.current
    val scope = remember { CoroutineScope(SupervisorJob() + Dispatchers.Main) }

    var status by remember { mutableStateOf<String?>("Tuning ${channel.name}…") }

    val player = remember(channel.id) {
        TapperPlayer(
            context = context,
            scope = scope,
            onDiagnosis = { d: Diagnosis -> status = d.message() },
            onPlaying = { status = null },
        ).also { it.play(channel) }
    }

    DisposableEffect(channel.id) {
        onDispose { player.release(); scope.cancel() }
    }

    // Back must leave playback, not the app. onKeyEvent never sees Back on a
    // touchscreen (it arrives as a system back gesture), so BackHandler is the
    // right API — it covers the tablet gesture and the Fire TV remote alike.
    BackHandler { onExit() }

    Box(
        Modifier
            .fillMaxSize()
            .background(Backdrop)
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    player.attach(this)
                }
            },
            modifier = Modifier.fillMaxSize(),
        )

        status?.let { msg ->
            Column(
                Modifier.align(Alignment.Center).padding(48.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(channel.name, style = MaterialTheme.typography.headlineLarge, color = Ink)
                Spacer(Modifier.height(12.dp))
                Text(msg, style = MaterialTheme.typography.bodyLarge, color = Dim)
                Spacer(Modifier.height(12.dp))
                Text("Press Back to return", style = MaterialTheme.typography.bodyMedium, color = Dim)
            }
        }
    }
}
