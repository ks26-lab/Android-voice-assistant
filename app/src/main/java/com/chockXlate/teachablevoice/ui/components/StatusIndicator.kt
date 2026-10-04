package com.chockXlate.teachablevoice.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chockXlate.teachablevoice.ui.theme.*

enum class StatusVariant {
    READY,
    TEACHING,
    LEARNING,
    VALIDATING,
    RUNNING,
    PAUSED,
    HANDOFF,
    WAITING,
    RECOVERING,
    UNRESOLVED,
    ERROR,
    COMPLETED,
    SUCCESS,
    INACTIVE
}

@Composable
fun StatusIndicator(
    status: String,
    variant: StatusVariant = StatusVariant.READY,
    modifier: Modifier = Modifier
) {
    val (bgColor, textColor, borderColor) = when (variant) {
        StatusVariant.READY, StatusVariant.COMPLETED, StatusVariant.SUCCESS ->
            Triple(ColorStatusReadyBg, ColorStatusReady, ColorStatusReadyBorder)
        StatusVariant.TEACHING ->
            Triple(ColorStatusTeachingBg, ColorStatusTeaching, ColorStatusTeachingBorder)
        StatusVariant.LEARNING, StatusVariant.VALIDATING, StatusVariant.RUNNING, StatusVariant.RECOVERING ->
            Triple(ColorStatusLearningBg, ColorStatusLearning, ColorStatusLearningBorder)
        StatusVariant.PAUSED, StatusVariant.WAITING ->
            Triple(ColorStatusTeachingBg, ColorStatusTeaching, ColorStatusTeachingBorder)
        StatusVariant.HANDOFF ->
            Triple(ColorStatusHandoffBg, ColorStatusHandoff, ColorStatusHandoffBorder)
        StatusVariant.UNRESOLVED, StatusVariant.ERROR ->
            Triple(ColorStatusErrorBg, ColorStatusError, ColorStatusErrorBorder)
        StatusVariant.INACTIVE ->
            Triple(ColorStatusInactiveBg, ColorStatusInactive, ColorBorderSubtle)
    }

    Row(
        modifier = modifier
            .clip(RadiusFull)
            .background(bgColor)
            .border(1.dp, borderColor, RadiusFull)
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(textColor)
        )
        Spacer(modifier = Modifier.width(5.dp))
        Text(
            text = status.uppercase(),
            color = textColor,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.03.sp
        )
    }
}
