package com.samsung.prism.uidemo.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.samsung.prism.uidemo.ui.theme.*

@Composable
fun VoiceCommandCard(
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        SectionHeader(title = "Voice Command")
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(BgSurface, RoundedCornerShape(12.dp))
                .border(1.dp, BorderMedium, RoundedCornerShape(12.dp))
                .padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            CustomButton(
                text = "Record Type",
                onClick = {},
                modifier = Modifier.weight(1f),
                isPrimary = false
            )
            CustomButton(
                text = "Mic / Stop",
                onClick = {},
                modifier = Modifier.weight(1f),
                isPrimary = false
            )
        }
    }
}
