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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chockXlate.teachablevoice.ui.theme.*

@Composable
fun CurrentStateCard(
    statusBadge: String,
    statusVariant: StatusVariant,
    title: String,
    description: String,
    modifier: Modifier = Modifier
) {
    val gradientBrush = Brush.verticalGradient(
        colors = listOf(ColorBgSurfaceElevated, ColorBgSurface)
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RadiusMd)
            .background(gradientBrush)
            .border(1.dp, ColorBorderMedium, RadiusMd)
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "CURRENT STATE",
                color = ColorTextMuted,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.06.sp
            )
            StatusIndicator(
                status = statusBadge,
                variant = statusVariant
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = title,
            color = ColorTextPrimary,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = (-0.01).sp
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = description,
            color = ColorTextSecondary,
            fontSize = 12.sp,
            lineHeight = 17.sp
        )
    }
}
