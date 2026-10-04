package com.chockXlate.teachablevoice.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.chockXlate.teachablevoice.ui.theme.*

@Composable
fun VoiceCommandCard(
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        SectionHeader(title = "Voice Command")
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RadiusMd)
                .background(ColorBgSurface)
                .border(1.dp, ColorBorderSubtle, RadiusMd)
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SecondaryButton(
                label = "Record Type",
                modifier = Modifier.weight(1f)
            )
            SecondaryButton(
                label = "Mic / Stop",
                modifier = Modifier.weight(1f)
            )
        }
    }
}
