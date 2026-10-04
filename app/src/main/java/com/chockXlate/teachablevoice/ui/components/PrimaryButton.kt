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

enum class PrimaryButtonVariant {
    ACCENT,
    DESTRUCTIVE,
    WARNING
}

@Composable
fun PrimaryButton(
    label: String,
    modifier: Modifier = Modifier,
    disabled: Boolean = false,
    variant: PrimaryButtonVariant = PrimaryButtonVariant.ACCENT,
    onClick: () -> Unit = {}
) {
    val (bgColor, textColor, borderColor) = when {
        disabled -> Triple(ColorBgSurfaceElevated, ColorTextDisabled, ColorBorderSubtle)
        variant == PrimaryButtonVariant.DESTRUCTIVE -> Triple(Color(0x26EF4444), Color(0xFFF87171), Color(0x4DEF4444))
        variant == PrimaryButtonVariant.WARNING -> Triple(Color(0x26F59E0B), Color(0xFFFBBF24), Color(0x59F59E0B))
        else -> Triple(ColorAccentPrimary, Color.White, Color.Transparent)
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
