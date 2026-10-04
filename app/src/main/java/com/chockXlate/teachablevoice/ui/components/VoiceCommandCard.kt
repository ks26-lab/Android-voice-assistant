package com.chockXlate.teachablevoice.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chockXlate.teachablevoice.ui.theme.*

@Composable
fun VoiceCommandCard(
    isListening: Boolean = false,
    statusText: String? = null,
    onRecordType: (() -> Unit)? = null,
    onMicToggle: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        SectionHeader(title = "Voice Command")
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RadiusMd)
                .background(ColorBgSurface)
                .border(
                    1.dp,
                    if (isListening) ColorAccentPrimary else ColorBorderSubtle,
                    RadiusMd
                )
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SecondaryButton(
                    label = "Record Type",
                    modifier = Modifier.weight(1f),
                    onClick = { onRecordType?.invoke() }
                )
                SecondaryButton(
                    label = if (isListening) "Stop Listening" else "Mic / Stop",
                    modifier = Modifier.weight(1f),
                    onClick = { onMicToggle?.invoke() }
                )
            }

            if (!statusText.isNullOrBlank()) {
                Text(
                    text = statusText,
                    color = if (statusText.contains("unavailable", ignoreCase = true) || statusText.contains("denied", ignoreCase = true) || statusText.contains("error", ignoreCase = true)) ColorStatusError else ColorTextSecondary,
                    fontSize = 11.5.sp
                )
            }
        }
    }
}
