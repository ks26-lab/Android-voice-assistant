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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.samsung.prism.uidemo.ui.mock.PipelineStep
import com.samsung.prism.uidemo.ui.theme.*

@Composable
fun LearningPipeline(
    steps: List<PipelineStep>,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        SectionHeader(title = "Learning pipeline")
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(BgSurface, RoundedCornerShape(12.dp))
                .border(1.dp, BorderMedium, RoundedCornerShape(12.dp))
                .padding(horizontal = 8.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            steps.forEachIndexed { index, step ->
                val isDone = step.status == "done"
                val isActive = step.status == "active"

                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    val (nodeBg, nodeBorder, nodeText) = when {
                        isDone -> Triple(StatusReadyBg, StatusReadyBorder, StatusReady)
                        isActive -> Triple(StatusLearningBg, StatusLearningBorder, StatusLearning)
                        else -> Triple(BgSurfaceElevated, BorderSubtle, TextDisabled)
                    }

                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .clip(CircleShape)
                            .background(nodeBg)
                            .border(1.dp, nodeBorder, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (isDone) "✓" else "${index + 1}",
                            color = nodeText,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Text(
                        text = step.label,
                        color = if (isActive || isDone) TextPrimary else TextMuted,
                        fontSize = 10.sp,
                        fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal
                    )
                }

                if (index < steps.size - 1) {
                    Text(
                        text = "›",
                        color = if (isDone) StatusReady else TextDisabled,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )
                }
            }
        }
    }
}
