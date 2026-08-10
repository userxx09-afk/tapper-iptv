package io.tapper.firetv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.tapper.firetv.ui.theme.Backdrop
import io.tapper.firetv.ui.theme.Dim
import io.tapper.firetv.ui.theme.Ink

@Composable
fun LoadingScreen(message: String) {
    Box(Modifier.fillMaxSize().background(Backdrop), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("TAPPER IPTV", style = MaterialTheme.typography.displayLarge, color = Ink)
            Spacer(Modifier.height(24.dp))
            Text(message, style = MaterialTheme.typography.bodyLarge, color = Dim)
        }
    }
}
