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
import io.tapper.core.model.ContentKind
import io.tapper.firetv.data.EpgDatabase
import io.tapper.firetv.data.FavoritesStore
import io.tapper.firetv.data.PlaylistRepository
import io.tapper.firetv.data.PlaylistRepository.Axis
import io.tapper.firetv.data.TvSource
import io.tapper.firetv.ui.theme.Backdrop
import io.tapper.firetv.ui.theme.Dim
import io.tapper.firetv.ui.theme.Focus
import io.tapper.firetv.ui.theme.Ink

internal fun kindLabel(kind: ContentKind): String = when (kind) {
    ContentKind.LIVE -> "Live TV"
    ContentKind.MOVIE -> "Movies"
    ContentKind.SERIES -> "Shows"
}

private sealed interface Rail {
    data object Favorites : Rail
    data class Group(val key: String?) : Rail
}

@Composable
fun BrowseScreen(
    catalogue: PlaylistRepository.Catalogue,
    repo: PlaylistRepository,
    favorites: FavoritesStore,
    sources: List<TvSource>,
    activeSource: TvSource,
    nowPlaying: Map<String, EpgDatabase.Programme>,
    epgStatus: String?,
    onPlay: (List<Channel>, Int) -> Unit,
    onSwitchSource: (TvSource) -> Unit,
    onAddSource: () -> Unit,
    onSearch: () -> Unit,
    onSettings: () -> Unit,
    onRefreshEpg: () -> Unit,
    onOpenSeries: (Channel) -> Unit,
) {
    var revision by remember { mutableIntStateOf(0) }
    var menu by remember { mutableStateOf<(@Composable () -> Unit)?>(null) }
    val kinds = catalogue.availableKinds
    var kind by remember(catalogue.sourceId) {
        mutableStateOf(kinds.firstOrNull() ?: ContentKind.LIVE)
    }
    val section = catalogue.section(kind)
    var axis by remember(catalogue.sourceId, kind) {
        mutableStateOf(section?.defaultAxis ?: Axis.CATEGORY)
    }
    // Secondary filter: within a country, narrow to one of its categories.
    var categoryFilter by remember(catalogue.sourceId, axis, kind) { mutableStateOf<String?>(null) }

    val favoriteChannels = remember(revision, catalogue, kind) {
        val ids = favorites.favorites()
        catalogue.channels.filter { it.kind == kind && "${it.sourceId}|${it.id}" in ids }
    }
    val pinned = remember(revision) { favorites.pinnedCountries() }
    val groups = section?.groups(axis).orEmpty()

    val rails = remember(revision, catalogue, axis, kind) {
        buildList {
            if (favoriteChannels.isNotEmpty()) add(Rail.Favorites)
            val (pin, rest) = groups.partition { it.key != null && it.key in pinned }
            pin.forEach { add(Rail.Group(it.key)) }
            rest.forEach { add(Rail.Group(it.key)) }
        }
    }

    var selected by remember(rails) { mutableStateOf(rails.firstOrNull() ?: Rail.Group(null)) }
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { firstFocus.requestFocus() } }

    val baseChannels: List<Channel> = when (val s = selected) {
        is Rail.Favorites -> favoriteChannels
        is Rail.Group -> groups.firstOrNull { it.key == s.key }?.channels.orEmpty()
    }
    // The category chips only make sense on the country axis; on the category
    // axis the rail already is the category.
    val categories = remember(baseChannels, axis) {
        if (axis == Axis.COUNTRY) repo.categoriesIn(baseChannels) else emptyList()
    }
    val shown = remember(baseChannels, categoryFilter) {
        if (categoryFilter == null) baseChannels
        else baseChannels.filter { it.group == categoryFilter }
    }
    val heading = when (val s = selected) {
        is Rail.Favorites -> "Favorites"
        is Rail.Group -> groups.firstOrNull { it.key == s.key }?.label ?: "Channels"
    } + (categoryFilter?.let { "  ·  $it" } ?: "")

    Row(
        Modifier.fillMaxSize().background(Backdrop)
            .padding(horizontal = 48.dp, vertical = 27.dp)
    ) {
        Column(Modifier.width(360.dp).fillMaxHeight()) {

            SourceHeader(activeSource, epgStatus) {
                menu = {
                    ItemMenu(
                        title = "Sources",
                        subtitle = "Currently: ${activeSource.name}",
                        actions = sources.filter { it.id != activeSource.id }
                            .map { s -> MenuAction("Switch to ${s.name}") { onSwitchSource(s) } }
                            + MenuAction("Add IPTV service...") { onAddSource() }
                            + MenuAction("Refresh guide") { onRefreshEpg() }
                            + MenuAction("Settings") { onSettings() },
                        onDismiss = { menu = null },
                    )
                }
            }

            Spacer(Modifier.height(10.dp))

            // Top level: content kind. Only kinds this source actually carries
            // appear, so a live-only playlist shows no empty Movies tab.
            if (kinds.size > 1) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(kinds, key = { it.name }) { k ->
                        Chip(kindLabel(k), k == kind) { kind = k }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("Search", false, onSearch)
                Chip("Settings", false, onSettings)
                Chip("Country", axis == Axis.COUNTRY) { axis = Axis.COUNTRY; categoryFilter = null }
                Chip("Category", axis == Axis.CATEGORY) { axis = Axis.CATEGORY; categoryFilter = null }
            }
            Spacer(Modifier.height(10.dp))

            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                itemsIndexed(rails) { i, rail ->
                    val group = (rail as? Rail.Group)?.let { r -> groups.firstOrNull { it.key == r.key } }
                    val label = if (rail is Rail.Favorites) "* Favorites" else group?.label ?: "Other"
                    val count = if (rail is Rail.Favorites) favoriteChannels.size else group?.channels?.size ?: 0
                    val key = (rail as? Rail.Group)?.key
                    val isPinned = key != null && key in pinned

                    RailRow(
                        title = if (isPinned) "[pin] $label" else label,
                        subtitle = "$count",
                        selected = rail == selected,
                        modifier = if (i == 0) Modifier.focusRequester(firstFocus) else Modifier,
                        onFocused = { if (selected != rail) { selected = rail; categoryFilter = null } },
                        onClick = { selected = rail; categoryFilter = null },
                        onLongPress = {
                            if (key != null) {
                                menu = {
                                    ItemMenu(
                                        title = label,
                                        subtitle = "$count channels",
                                        actions = listOf(
                                            MenuAction(if (isPinned) "Unpin from top" else "Pin to top") {
                                                favorites.togglePinned(key); revision++
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
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(10.dp))

            if (categories.size > 1) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item { Chip("All", categoryFilter == null) { categoryFilter = null } }
                    items(categories, key = { it }) { c ->
                        Chip(c, categoryFilter == c) { categoryFilter = if (categoryFilter == c) null else c }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }

            if (shown.isEmpty()) {
                Text(
                    if (selected is Rail.Favorites)
                        "No favorites yet. Long-press a channel to add one."
                    else "No channels here.",
                    style = MaterialTheme.typography.bodyLarge, color = Dim,
                )
            }

            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                itemsIndexed(shown, key = { _, it -> "${it.sourceId}|${it.id}" }) { i, ch ->
                    val isFav = remember(revision, ch.id) { favorites.isFavorite(ch.sourceId, ch.id) }
                    // A series is a container, not a stream: opening it lists
                    // episodes rather than handing an empty URL to the player.
                    val activate: () -> Unit =
                        if (ch.kind == ContentKind.SERIES) ({ onOpenSeries(ch) })
                        else ({ onPlay(shown.filter { it.isPlayable }, i) })
                    ChannelRow(
                        channel = ch,
                        favorite = isFav,
                        programme = ch.epgChannelId?.let { nowPlaying[it] },
                        onClick = activate,
                        onLongPress = {
                            menu = {
                                ItemMenu(
                                    title = ch.name,
                                    subtitle = ch.epgChannelId?.let { nowPlaying[it]?.title } ?: ch.group,
                                    actions = listOf(
                                        MenuAction(
                                            if (ch.kind == ContentKind.SERIES) "Open" else "Play"
                                        ) { activate() },
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
private fun SourceHeader(source: TvSource, epgStatus: String?, onClick: () -> Unit) {
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
        epgStatus?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = Dim,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
internal fun Chip(label: String, active: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Box(
        Modifier.clip(RoundedCornerShape(18.dp))
            .background(
                if (active || focused) Focus.copy(alpha = if (active) 0.28f else 0.16f)
                else Color.White.copy(alpha = 0.06f)
            )
            .border(1.dp, if (focused || active) Focus else Color.Transparent, RoundedCornerShape(18.dp))
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium,
            color = if (active || focused) Ink else Dim, maxLines = 1)
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
            .border(if (focused) 2.dp else 0.dp, if (focused) Focus else Color.Transparent,
                RoundedCornerShape(8.dp))
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
    programme: EpgDatabase.Programme?,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()

    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (focused) Focus.copy(alpha = 0.18f) else Color.White.copy(alpha = 0.04f))
            .border(if (focused) 2.dp else 0.dp, if (focused) Focus else Color.Transparent,
                RoundedCornerShape(8.dp))
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
            // Guide line when it exists, otherwise the category - never blank,
            // so rows keep a consistent height whether or not EPG has loaded.
            Text(
                programme?.title ?: channel.group ?: "",
                style = MaterialTheme.typography.bodyMedium,
                color = if (programme != null) Focus else Dim,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
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
