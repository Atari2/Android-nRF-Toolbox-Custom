package no.nordicsemi.android.theme.view

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import no.nordicsemi.android.material.you.Card

@Composable
fun ScreenSection(content: @Composable () -> Unit) {
    Card(
        backgroundColor = MaterialTheme.colorScheme.background,
        shape = RoundedCornerShape(4.dp),
        elevation = 0.dp
    ) {
        Box(modifier = Modifier.padding(16.dp)) {
            content()
        }
    }
}
