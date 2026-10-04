package com.chockXlate.teachablevoice.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chockXlate.teachablevoice.ui.theme.*

@Composable
fun SecondaryButton(
    label: String,
    modifier: Modifier = Modifier,
    disabled: Boolean = false,
    onClick: () -> Unit = {}
) {
    val (bgColor, textColor, borderColor) = when {
        disabled -> Triple(Color(0x05FFFFFF), ColorTextDisabled, Color(0x0AFFFFFF))
        else -> Triple(ColorBgSurfaceSubtle, ColorTextSecondary, ColorBorderSubtle)
    }

    Box(
        modifier = modifier
            .defaultMinSize(minHeight = 38.dp)
            .clip(RadiusSm)
            .background(bgColor)
            .border(1.dp, borderColor, RadiusSm)
            .clickable(enabled = !disabled) { onClick() }
            .padding(horizontal = 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = textColor,
            fontSize = 12.5.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center
        )
    }
}
