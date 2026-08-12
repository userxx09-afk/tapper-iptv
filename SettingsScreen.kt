package io.tapper.firetv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.tapper.firetv.data.TvSource
import io.tapper.firetv.ui.theme.Backdrop
import io.tapper.firetv.ui.theme.Dim
import io.tapper.firetv.ui.theme.Focus
import io.tapper.firetv.ui.theme.Ink

/**
 * Settings, and the recovery route out of a broken source.
 *
 * This screen exists because the previous build could strand the app: if the
 * active source failed to load, the error screen offered nothing but the error.
 * Source configuration lives in preferences, not in an editable file, so there
 * was no way back short of clearing app storage. Settings is therefore
 * reachable from the failure screen, not only from a working one.
 */
@Composable
fun SettingsScreen(
    sources: List<TvSource>,
    activeId: String,
    guideSummary: String,
    onSwitchSource: (TvSource) -> Unit,
    onAddSource: () -> Unit,
    onRemoveSource: (TvSource) -> Unit,
    onSetEpgUrl: (TvSource, String?) -> Unit,
    onRefreshGuide: () -> Unit,
    onClearCache: () -> Unit,
    syncSummary: String,
    syncBusy: Boolean,
    onPickFolder: () -> Unit,
    onSaveWebDav: (String, String, String) -> Unit,
    onSyncNow: () -> Unit,
    onDisableSync: () -> Unit,
    onExit: () -> Unit,
) {
    BackHandler { onExit() }
    var editing by remember { mutableStateOf<String?>(null) }
    var epgDraft by remember { mutableStateOf("") }
    var confirmRemove by remember { mutableStateOf<TvSource?>(null) }
    var davOpen by remember { mutableStateOf(false) }
    var davUrl by remember { mutableStateOf("") }
    var davUser by remember { mutableStateOf("") }
    var davPass by remember { mutableStateOf("") }

    Column(
        Modifier.fillMaxSize().background(Backdrop)
            .padding(horizontal = 48.dp, vertical = 32.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineLarge, color = Ink)
        Spacer(Modifier.height(6.dp))
        Text("Back returns to the previous screen.",
            style = MaterialTheme.typography.bodyMedium, color = Dim)

        Spacer(Modifier.height(24.dp))
        Text("SOURCES", style = MaterialTheme.typography.bodyMedium, color = Dim)
        Spacer(Modifier.height(8.dp))

        sources.forEach { s ->
            val isActive = s.id == activeId
            Column(
                Modifier.fillMaxWidth().padding(bottom = 10.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color.White.copy(alpha = if (isActive) 0.08f else 0.04f))
                    .border(1.dp, if (isActive) Focus.copy(alpha = 0.6f) else Color.Transparent,
                        RoundedCornerShape(10.dp))
                    .padding(16.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            s.name + if (isActive) "   (active)" else "",
                            style = MaterialTheme.typography.titleMedium, color = Ink,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        // Host only. The full Xtream URL carries the username and
                        // password, which must never be rendered on screen.
                        Text(
                            s.kind.name + "  ·  " + hostOnly(s.location),
                            style = MaterialTheme.typography.bodyMedium, color = Dim,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        s.epgUrlOverride?.let {
                            Text("Guide: " + hostOnly(it),
                                style = MaterialTheme.typography.bodyMedium, color = Dim, maxLines = 1)
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!isActive) Chip("Use this", false) { onSwitchSource(s) }
                    Chip("Guide URL", editing == s.id) {
                        editing = if (editing == s.id) null else s.id
                        epgDraft = s.epgUrlOverride.orEmpty()
                    }
                    if (!s.builtIn) Chip("Remove", false) { confirmRemove = s }
                }

                if (editing == s.id) {
                    Spacer(Modifier.height(12.dp))
                    Text("XMLTV guide URL (leave blank to use the provider's own)",
                        style = MaterialTheme.typography.bodyMedium, color = Dim)
                    Spacer(Modifier.height(6.dp))
                    Box(
                        Modifier.fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color.White.copy(alpha = 0.06f))
                            .border(1.dp, Focus.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                            .padding(horizontal = 14.dp, vertical = 12.dp)
                    ) {
                        if (epgDraft.isEmpty()) {
                            Text("https://.../guide.xml.gz",
                                style = MaterialTheme.typography.bodyLarge,
                                color = Dim.copy(alpha = 0.6f))
                        }
                        BasicTextField(
                            value = epgDraft, onValueChange = { epgDraft = it }, singleLine = true,
                            textStyle = MaterialTheme.typography.bodyLarge.copy(color = Ink),
                            cursorBrush = SolidColor(Focus),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Chip("Save", false) {
                            onSetEpgUrl(s, epgDraft.trim().ifBlank { null }); editing = null
                        }
                        Chip("Cancel", false) { editing = null }
                    }
                }
            }
        }

        Chip("Add IPTV service", false, onAddSource)

        Spacer(Modifier.height(28.dp))
        Text("GUIDE", style = MaterialTheme.typography.bodyMedium, color = Dim)
        Spacer(Modifier.height(8.dp))
        Text(guideSummary, style = MaterialTheme.typography.bodyLarge, color = Ink)
        Spacer(Modifier.height(10.dp))
        Chip("Refresh guide now", false, onRefreshGuide)

        Spacer(Modifier.height(28.dp))
        Text("SHARED WATCH HISTORY", style = MaterialTheme.typography.bodyMedium, color = Dim)
        Spacer(Modifier.height(8.dp))
        Text(syncSummary, style = MaterialTheme.typography.bodyLarge, color = Ink)
        Spacer(Modifier.height(6.dp))
        Text(
            "Each device writes only its own file, so two devices can never " +
                "overwrite each other. Progress merges when they sync.",
            style = MaterialTheme.typography.bodyMedium, color = Dim,
        )
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // Drive, OneDrive and Dropbox all appear in the system folder
            // picker when their app is installed - no separate sign-in here.
            Chip("Choose folder", false, onPickFolder)
            Chip("WebDAV / NAS", davOpen) { davOpen = !davOpen }
            Chip(if (syncBusy) "Syncing..." else "Sync now", false) { if (!syncBusy) onSyncNow() }
            Chip("Turn off", false, onDisableSync)
        }

        if (davOpen) {
            Spacer(Modifier.height(12.dp))
            SettingField("Folder URL", davUrl, "https://nas.local/remote.php/dav/files/me/tapper") { davUrl = it }
            SettingField("Username (optional)", davUser, "") { davUser = it }
            SettingField("Password (optional)", davPass, "", password = true) { davPass = it }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("Save", false) {
                    onSaveWebDav(davUrl.trim(), davUser.trim(), davPass); davOpen = false
                }
                Chip("Cancel", false) { davOpen = false }
            }
        }

        Spacer(Modifier.height(28.dp))
        Text("STORAGE", style = MaterialTheme.typography.bodyMedium, color = Dim)
        Spacer(Modifier.height(8.dp))
        Text(
            "Playlists are cached for 12 hours. Clearing forces a fresh download " +
                "on the next load; saved sources and credentials are kept.",
            style = MaterialTheme.typography.bodyMedium, color = Dim,
        )
        Spacer(Modifier.height(10.dp))
        Chip("Clear cached playlists", false, onClearCache)
        Spacer(Modifier.height(40.dp))
    }

    confirmRemove?.let { s ->
        ItemMenu(
            title = "Remove " + s.name + "?",
            subtitle = "Saved credentials for this source are deleted too.",
            actions = listOf(
                MenuAction("Remove") { onRemoveSource(s); confirmRemove = null },
                MenuAction("Keep it") { confirmRemove = null },
            ),
            onDismiss = { confirmRemove = null },
        )
    }
}

@Composable
private fun SettingField(
    label: String,
    value: String,
    hint: String,
    password: Boolean = false,
    onChange: (String) -> Unit,
) {
    Spacer(Modifier.height(10.dp))
    Text(label, style = MaterialTheme.typography.bodyMedium, color = Dim)
    Spacer(Modifier.height(4.dp))
    Box(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
            .background(Color.White.copy(alpha = 0.06f))
            .border(1.dp, Focus.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        if (value.isEmpty() && hint.isNotEmpty()) {
            Text(hint, style = MaterialTheme.typography.bodyMedium, color = Dim.copy(alpha = 0.6f),
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        BasicTextField(
            value = value, onValueChange = onChange, singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = Ink),
            cursorBrush = SolidColor(Focus),
            visualTransformation = if (password)
                androidx.compose.ui.text.input.PasswordVisualTransformation()
            else androidx.compose.ui.text.input.VisualTransformation.None,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** Strips everything after the host: Xtream paths contain credentials. */
private fun hostOnly(url: String): String =
    Regex("""^(https?://)?([^/]+)""").find(url)?.groupValues?.get(2) ?: url
