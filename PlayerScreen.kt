package io.tapper.firetv.ui

import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import io.tapper.core.model.Channel
import io.tapper.core.model.ContentKind
import io.tapper.core.playback.Diagnosis
import io.tapper.firetv.data.EpgDatabase
import io.tapper.firetv.player.TapperPlayer
import io.tapper.firetv.ui.theme.Backdrop
import io.tapper.firetv.ui.theme.Dim
import io.tapper.firetv.ui.theme.Focus
import io.tapper.firetv.ui.theme.Ink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay

/**
 * Full-screen playback with up/down channel changing.
 *
 * The channel list handed in is the one the user was browsing, so zapping walks
 * the same order they were just looking at rather than some global index.
 */
@Composable
fun PlayerScreen(
    channels: List<Channel>,
    startIndex: Int,
    nowPlaying: (Channel) -> EpgDatabase.Programme?,
    resumeAt: (Channel) -> Long?,
    onProgress: (Channel, Long, Long, Boolean) -> Unit,
    onExit: () -> Unit,
) {
    val context = LocalContext.current
    val scope = remember { CoroutineScope(SupervisorJob() + Dispatchers.Main) }
    val focus = remember { FocusRequester() }

    var index by remember { mutableIntStateOf(startIndex.coerceIn(0, (channels.size - 1).coerceAtLeast(0))) }
    val channel = channels.getOrNull(index) ?: return
    var status by remember(channel.id) { mutableStateOf<String?>("Tuning ${channel.name}...") }
    // Shown briefly after a channel change, then fades out of the way.
    var overlayVisible by remember { mutableStateOf(true) }

    val player = remember {
        TapperPlayer(
            context = context,
            scope = scope,
            onDiagnosis = { d: Diagnosis -> status = d.message() },
            onPlaying = { status = null },
        )
    }

    // Re-tunes whenever the index changes; also covers the initial channel.
    LaunchedEffect(channel.id) {
        overlayVisible = true
        player.play(channel, resumeAt(channel))
    }

    LaunchedEffect(channel.id, status) {
        if (status == null) { delay(4000); overlayVisible = false }
    }

    // Progress is sampled on a timer and again on exit. Live channels have no
    // meaningful position, so only on-demand items are recorded.
    LaunchedEffect(channel.id) {
        if (channel.kind == ContentKind.LIVE) return@LaunchedEffect
        while (true) {
            delay(10_000)
            val pos = player.positionMs()
            val dur = player.durationMs()
            if (dur > 0) onProgress(channel, pos, dur, false)
        }
    }

    DisposableEffect(channel.id) {
        onDispose {
            if (channel.kind != ContentKind.LIVE) {
                val pos = player.positionMs(); val dur = player.durationMs()
                // "Only on exit" would lose everything when Fire OS kills the
                // app for memory, which it does often; this is the backstop.
                if (dur > 0) onProgress(channel, pos, dur, false)
            }
        }
    }

    DisposableEffect(Unit) { onDispose { player.release(); scope.cancel() } }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    BackHandler { onExit() }

    fun step(delta: Int) {
        if (channels.size < 2) return
        // Wraps at both ends: hitting up on the first channel should land on the
        // last, not sit there doing nothing.
        index = ((index + delta) % channels.size + channels.size) % channels.size
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Backdrop)
            .focusRequester(focus)
            .focusable()
            .onKeyEvent { e ->
                if (e.type != KeyEventType.KeyUp) return@onKeyEvent false
                when (e.key) {
                    Key.DirectionUp, Key.ChannelUp, Key.PageUp -> { step(-1); true }
                    Key.DirectionDown, Key.ChannelDown, Key.PageDown -> { step(1); true }
                    Key.DirectionCenter, Key.Enter -> { overlayVisible = !overlayVisible; true }
                    else -> false
                }
            }
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

        val msg = status
        if (msg != null) {
            Column(
                Modifier.align(Alignment.Center).padding(48.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(channel.name, style = MaterialTheme.typography.headlineLarge, color = Ink)
                Spacer(Modifier.height(12.dp))
                Text(msg, style = MaterialTheme.typography.bodyLarge, color = Dim)
                Spacer(Modifier.height(12.dp))
                Text(
                    "Up / Down to change channel  ·  Back to return",
                    style = MaterialTheme.typography.bodyMedium, color = Dim,
                )
            }
        } else if (overlayVisible) {
            ChannelBadge(
                channel = channel,
                position = "${index + 1} / ${channels.size}",
                programme = nowPlaying(channel),
                modifier = Modifier.align(Alignment.BottomStart).padding(48.dp),
            )
        }
    }
}

@Composable
private fun ChannelBadge(
    channel: Channel,
    position: String,
    programme: EpgDatabase.Programme?,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Color.Black.copy(alpha = 0.72f))
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (channel.logoUrl != null) {
            AsyncImage(
                model = channel.logoUrl, contentDescription = null,
                modifier = Modifier.size(52.dp).clip(RoundedCornerShape(6.dp)),
            )
            Spacer(Modifier.width(16.dp))
        }
        Column(Modifier.widthIn(max = 620.dp)) {
            Text(
                channel.name, style = MaterialTheme.typography.titleMedium, color = Ink,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            if (programme != null) {
                Text(
                    programme.title, style = MaterialTheme.typography.bodyLarge, color = Focus,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                programme.description?.let {
                    Text(
                        it, style = MaterialTheme.typography.bodyMedium, color = Dim,
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                }
            } else {
                Text(position, style = MaterialTheme.typography.bodyMedium, color = Dim)
            }
        }
    }
}
