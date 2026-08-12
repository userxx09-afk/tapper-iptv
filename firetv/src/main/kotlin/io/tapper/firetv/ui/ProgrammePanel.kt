package io.tapper.firetv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import io.tapper.core.model.Channel
import io.tapper.firetv.data.EpgDatabase
import io.tapper.firetv.ui.theme.Dim
import io.tapper.firetv.ui.theme.Focus
import io.tapper.firetv.ui.theme.Ink
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * What is on the focused channel. Occupies the third column of the browse
 * layout and grows as the columns to its left collapse.
 */
@Composable
fun ProgrammePanel(
    channel: Channel?,
    schedule: List<EpgDatabase.Programme>,
    expanded: Boolean,
    modifier: Modifier = Modifier,
) {
    val fmt = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    Column(modifier) {
        if (channel == null) {
            Text("Select a channel", style = MaterialTheme.typography.bodyMedium, color = Dim)
            return@Column
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            if (channel.logoUrl != null) {
                AsyncImage(
                    model = channel.logoUrl, contentDescription = null,
                    modifier = Modifier.size(if (expanded) 56.dp else 36.dp)
                        .clip(RoundedCornerShape(6.dp)),
                )
                Spacer(Modifier.width(12.dp))
            }
            Text(
                channel.name,
                style = if (expanded) MaterialTheme.typography.titleMedium
                        else MaterialTheme.typography.bodyLarge,
                color = Ink, maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
        }

        Spacer(Modifier.height(12.dp))

        if (schedule.isEmpty()) {
            Text(
                "No guide data for this channel.",
                style = MaterialTheme.typography.bodyMedium, color = Dim,
            )
            return@Column
        }

        val now = System.currentTimeMillis()
        // The first entry is what is on air; the rest is what follows.
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(schedule, key = { it.startUtc }) { p ->
                val live = now in p.startUtc until p.endUtc
                Column(
                    Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(
                            if (live) Focus.copy(alpha = 0.14f) else Color.White.copy(alpha = 0.03f)
                        )
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                ) {
                    Text(
                        fmt.format(Date(p.startUtc)) + (if (live) "   ON NOW" else ""),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (live) Focus else Dim,
                    )
                    Text(
                        p.title, style = MaterialTheme.typography.bodyLarge, color = Ink,
                        maxLines = if (expanded) 2 else 1, overflow = TextOverflow.Ellipsis,
                    )
                    // Descriptions only earn their space once the panel is wide.
                    if (expanded) {
                        p.description?.let {
                            Spacer(Modifier.height(4.dp))
                            Text(it, style = MaterialTheme.typography.bodyMedium, color = Dim,
                                maxLines = 4, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}
