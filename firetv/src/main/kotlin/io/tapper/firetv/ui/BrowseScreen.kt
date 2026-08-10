package io.tapper.firetv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import io.tapper.core.model.Channel
import io.tapper.firetv.data.PlaylistRepository
import io.tapper.firetv.ui.theme.Backdrop
import io.tapper.firetv.ui.theme.Dim
import io.tapper.firetv.ui.theme.Focus
import io.tapper.firetv.ui.theme.Ink

/**
 * Two panes: countries on the left, that country's channels on the right.
 *
 * The 5% inset on every edge is the Fire TV overscan safe area — older TVs crop
 * the outer edge of the signal and anything flush to the border disappears.
 */
@Composable
fun BrowseScreen(
    catalogue: PlaylistRepository.Catalogue,
    repo: PlaylistRepository,
    onPlay: (Channel) -> Unit,
) {
    var selectedCountry by remember { mutableStateOf(catalogue.countryOrder.firstOrNull()) }
    val countryFocus = remember { FocusRequester() }

    // Focus must be claimed explicitly on a TV. With nothing focused, the D-pad
    // does nothing at all and the app looks frozen.
    LaunchedEffect(Unit) { runCatching { countryFocus.requestFocus() } }

    Row(
        Modifier
            .fillMaxSize()
            .background(Backdrop)
            .padding(horizontal = 48.dp, vertical = 27.dp)
    ) {
        // ---- country rail ----
        Column(Modifier.width(360.dp).fillMaxHeight()) {
            Text(
                "Countries",
                style = MaterialTheme.typography.headlineLarge,
                color = Ink,
                modifier = Modifier.padding(bottom = 16.dp),
            )
            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                itemsIndexed(catalogue.countryOrder) { i, code ->
                    val count = catalogue.byCountry[code]?.size ?: 0
                    FocusRow(
                        title = repo.countryLabel(code),
                        subtitle = "$count",
                        selected = code == selectedCountry,
                        modifier = if (i == 0) Modifier.focusRequester(countryFocus) else Modifier,
                        onFocused = { selectedCountry = code },
                        onClick = { selectedCountry = code },
                    )
                }
            }
        }

        Spacer(Modifier.width(32.dp))

        // ---- channel list ----
        Column(Modifier.weight(1f).fillMaxHeight()) {
            Text(
                repo.countryLabel(selectedCountry),
                style = MaterialTheme.typography.headlineLarge,
                color = Ink,
                modifier = Modifier.padding(bottom = 16.dp),
            )
            val channels = catalogue.byCountry[selectedCountry].orEmpty()
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(channels, key = { it.id }) { ch ->
                    ChannelRow(ch, onClick = { onPlay(ch) })
                }
            }
        }
    }
}

@Composable
private fun FocusRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onFocused: () -> Unit,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    LaunchedEffect(focused) { if (focused) onFocused() }

    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (focused) Focus.copy(alpha = 0.18f) else Color.Transparent)
            .border(
                width = if (focused) 2.dp else 0.dp,
                color = if (focused) Focus else Color.Transparent,
                shape = RoundedCornerShape(8.dp),
            )
            // clickable() is focusable AND handles DPAD_CENTER/Enter itself, so this
            // works on a remote and on a touchscreen. The previous focusable() +
            // onKeyEvent pair only ever fired for a D-pad — taps did nothing.
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = { onFocused(); onClick() },
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
            color = if (focused || selected) Ink else Dim,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = Dim)
    }
}

@Composable
private fun ChannelRow(channel: Channel, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()

    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (focused) Focus.copy(alpha = 0.18f) else Color.White.copy(alpha = 0.04f))
            .border(
                width = if (focused) 2.dp else 0.dp,
                color = if (focused) Focus else Color.Transparent,
                shape = RoundedCornerShape(8.dp),
            )
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (channel.logoUrl != null) {
            AsyncImage(
                model = channel.logoUrl,
                contentDescription = null,
                modifier = Modifier.size(48.dp).clip(RoundedCornerShape(6.dp)),
            )
            Spacer(Modifier.width(16.dp))
        } else {
            Spacer(Modifier.width(64.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                channel.name,
                style = MaterialTheme.typography.bodyLarge,
                color = Ink, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            channel.group?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = Dim, maxLines = 1)
            }
        }
        // Multiple feeds means silent failover is available for this channel.
        if (channel.streams.size > 1) {
            Text("${channel.streams.size} feeds",
                style = MaterialTheme.typography.bodyMedium, color = Dim)
        }
    }
}
