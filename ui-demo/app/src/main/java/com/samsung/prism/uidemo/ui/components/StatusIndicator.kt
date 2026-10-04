package com.samsung.prism.uidemo.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.samsung.prism.uidemo.ui.theme.*

@Composable
fun StatusIndicator(
    status: String,
    modifier: Modifier = Modifier,
    variant: String = "ready"
) {
    val (bgColor, borderColor, dotColor, textColor) = when (variant.lowercase()) {
        "ready", "success", "completed" -> Quad(StatusReadyBg, StatusReadyBorder, StatusReady, StatusReady)
        "teaching", "warning", "paused", "waiting" -> Quad(StatusTeachingBg, StatusTeachingBorder, StatusTeaching, StatusTeaching)
        "learning", "validating", "recovering" -> Quad(StatusLearningBg, StatusLearningBorder, StatusLearning, StatusLearning)
        "handoff", "unresolved", "error" -> Quad(StatusErrorBg, StatusErrorBorder, StatusError, StatusError)
        else -> Quad(StatusInactiveBg, BorderMedium, StatusInactive, TextSecondary)
    }

    Row(
        modifier = modifier
            .background(bgColor, RoundedCornerShape(12.dp))
            .border(1.dp, borderColor, RoundedCornerShape(12.dp))
            .padding(horizontal = 9.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .background(dotColor, CircleShape)
        )
        Text(
            text = status.uppercase(),
            color = textColor,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.4.sp
        )
    }
}

private data class Quad<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)
