package io.tapper.firetv.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import io.tapper.firetv.data.FavoritesStore
import io.tapper.firetv.data.PlaylistRepository
import io.tapper.firetv.data.TvSource
import io.tapper.firetv.ui.theme.Backdrop
import io.tapper.firetv.ui.theme.Dim
import io.tapper.firetv.ui.theme.Focus
import io.tapper.firetv.ui.theme.Ink

/** Left-rail entries. Favourites is a pseudo-country pinned above everything. */
private sealed interface Rail {
    data object Favorites : Rail
    data class Country(val code: String?) : Rail
}

@Composable
fun BrowseScreen(
    catalogue: PlaylistRepository.Catalogue,
    repo: PlaylistRepository,
    favorites: FavoritesStore,
    sources: List<TvSource>,
    activeSource: TvSource,
    onPlay: (Channel) -> Unit,
    onSwitchSource: (TvSource) -> Unit,
    onAddSource: () -> Unit,
) {
    // Bumped whenever favourites change, to force the derived lists to recompute.
    var revision by remember { mutableIntStateOf(0) }
    var menu by remember { mutableStateOf<(@Composable () -> Unit)?>(null) }

    val favoriteChannels = remember(revision, catalogue) {
        val ids = favorites.favorites()
        catalogue.channels.filter { "${it.sourceId}|${it.id}" in ids }
    }
    val pinned = remember(revision) { favorites.pinnedCountries() }

    val rails = remember(revision, catalogue) {
        buildList {
            if (favoriteChannels.isNotEmpty()) add(Rail.Favorites)
            val (pin, rest) = catalogue.countryOrder.partition { it != null && it in pinned }
            pin.forEach { add(Rail.Country(it)) }
            rest.forEach { add(Rail.Country(it)) }
        }
    }

    var selected by remember(rails) { mutableStateOf(rails.firstOrNull() ?: Rail.Country(null)) }
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { firstFocus.requestFocus() } }

    val shown: List<Channel> = when (val s = selected) {
        is Rail.Favorites -> favoriteChannels
        is Rail.Country -> catalogue.byCountry[s.code].orEmpty()
    }
    val heading = when (val s = selected) {
        is Rail.Favorites -> "Favorites"
        is Rail.Country -> repo.countryLabel(s.code)
    }

    Row(
        Modifier.fillMaxSize().background(Backdrop)
            .padding(horizontal = 48.dp, vertical = 27.dp)
    ) {
        Column(Modifier.width(360.dp).fillMaxHeight()) {

            SourceHeader(activeSource) {
                menu = {
                    ItemMenu(
                        title = "Sources",
                        subtitle = "Currently: ${activeSource.name}",
                        actions = sources.filter { it.id != activeSource.id }
                            .map { s -> MenuAction("Switch to ${s.name}") { onSwitchSource(s) } }
                            + MenuAction("Add IPTV service...") { onAddSource() },
                        onDismiss = { menu = null },
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                itemsIndexed(rails) { i, rail ->
                    val label = when (rail) {
                        is Rail.Favorites -> "* Favorites"
                        is Rail.Country -> repo.countryLabel(rail.code)
                    }
                    val count = when (rail) {
                        is Rail.Favorites -> favoriteChannels.size
                        is Rail.Country -> catalogue.byCountry[rail.code]?.size ?: 0
                    }
                    val isPinned = rail is Rail.Country && rail.code != null && rail.code in pinned

                    RailRow(
                        title = if (isPinned) "[pin] $label" else label,
                        subtitle = "$count",
                        selected = rail == selected,
                        modifier = if (i == 0) Modifier.focusRequester(firstFocus) else Modifier,
                        onFocused = { selected = rail },
                        onClick = { selected = rail },
                        onLongPress = {
                            if (rail is Rail.Country && rail.code != null) {
                                menu = {
                                    ItemMenu(
                                        title = repo.countryLabel(rail.code),
                                        subtitle = "$count channels",
                                        actions = listOf(
                                            MenuAction(if (isPinned) "Unpin from top" else "Pin to top") {
                                                favorites.togglePinned(rail.code); revision++
                                            }
                                        ),
                                        onDismiss = { menu = null },
                                    )
                                }
                            }
                        },
                    )
                }
            }
        }

        Spacer(Modifier.width(32.dp))

        Column(Modifier.weight(1f).fillMaxHeight()) {
            Text(heading, style = MaterialTheme.typography.headlineLarge, color = Ink,
                modifier = Modifier.padding(bottom = 16.dp))

            if (shown.isEmpty()) {
                Text(
                    if (selected is Rail.Favorites)
                        "No favorites yet. Long-press a channel to add one."
                    else "No channels here.",
                    style = MaterialTheme.typography.bodyLarge, color = Dim,
                )
            }

            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(shown, key = { "${it.sourceId}|${it.id}" }) { ch ->
                    val isFav = remember(revision, ch.id) { favorites.isFavorite(ch.sourceId, ch.id) }
                    ChannelRow(
                        channel = ch,
                        favorite = isFav,
                        onClick = { onPlay(ch) },
                        onLongPress = {
                            menu = {
                                ItemMenu(
                                    title = ch.name,
                                    subtitle = ch.group,
                                    actions = listOf(
                                        MenuAction("Play") { onPlay(ch) },
                                        MenuAction(
                                            if (isFav) "Remove from Favorites" else "Add to Favorites"
                                        ) { favorites.toggle(ch.sourceId, ch.id); revision++ },
                                    ),
                                    onDismiss = { menu = null },
                                )
                            }
                        },
                    )
                }
            }
        }
    }

    menu?.invoke()
}

@Composable
private fun SourceHeader(source: TvSource, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Column(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (focused) Focus.copy(alpha = 0.18f) else Color.White.copy(alpha = 0.05f))
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text("SOURCE", style = MaterialTheme.typography.bodyMedium, color = Dim)
        Text(source.name, style = MaterialTheme.typography.titleMedium, color = Ink,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RailRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onFocused: () -> Unit,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    LaunchedEffect(focused) { if (focused) onFocused() }

    Row(
        modifier.fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (focused) Focus.copy(alpha = 0.18f) else Color.Transparent)
            .border(
                width = if (focused) 2.dp else 0.dp,
                color = if (focused) Focus else Color.Transparent,
                shape = RoundedCornerShape(8.dp),
            )
            // combinedClickable covers tap, long-press, and D-pad centre - including
            // a held centre button, which is how a Fire TV remote signals long-press.
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                onClick = { onFocused(); onClick() },
                onLongClick = onLongPress,
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
            color = if (focused || selected) Ink else Dim,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = Dim)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChannelRow(
    channel: Channel,
    favorite: Boolean,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()

    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (focused) Focus.copy(alpha = 0.18f) else Color.White.copy(alpha = 0.04f))
            .border(
                width = if (focused) 2.dp else 0.dp,
                color = if (focused) Focus else Color.Transparent,
                shape = RoundedCornerShape(8.dp),
            )
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
                onLongClick = onLongPress,
            )
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (channel.logoUrl != null) {
            AsyncImage(model = channel.logoUrl, contentDescription = null,
                modifier = Modifier.size(48.dp).clip(RoundedCornerShape(6.dp)))
            Spacer(Modifier.width(16.dp))
        } else {
            Spacer(Modifier.width(64.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(channel.name, style = MaterialTheme.typography.bodyLarge, color = Ink,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            channel.group?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = Dim, maxLines = 1)
            }
        }
        if (favorite) {
            Text("*", style = MaterialTheme.typography.titleMedium, color = Focus)
            Spacer(Modifier.width(12.dp))
        }
        if (channel.streams.size > 1) {
            Text("${channel.streams.size} feeds",
                style = MaterialTheme.typography.bodyMedium, color = Dim)
        }
    }
}
