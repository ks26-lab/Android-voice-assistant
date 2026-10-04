package com.chockXlate.teachablevoice.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import com.chockXlate.teachablevoice.ui.mock.PipelineStepData
import com.chockXlate.teachablevoice.ui.mock.StepStatus
import com.chockXlate.teachablevoice.ui.theme.*

@Composable
fun LearningPipeline(
    steps: List<PipelineStepData>,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        SectionHeader(title = "Learning Pipeline")
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RadiusMd)
                .background(ColorBgSurfaceSubtle)
                .border(1.dp, ColorBorderSubtle, RadiusMd)
                .padding(horizontal = 8.dp, vertical = 10.dp)
                .horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            steps.forEachIndexed { index, step ->
                PipelineStepItem(
                    stepNumber = index + 1,
                    label = step.label,
                    status = step.status
                )
                if (index < steps.size - 1) {
                    Text(
                        text = "›",
                        color = ColorBorderMedium,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(horizontal = 2.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun PipelineStepItem(
    stepNumber: Int,
    label: String,
    status: StepStatus
) {
    val (nodeBg, nodeBorder, nodeTextColor) = when (status) {
        StepStatus.DONE -> Triple(ColorStatusReadyBg, ColorStatusReady, ColorStatusReady)
        StepStatus.ACTIVE -> Triple(ColorAccentPrimary, ColorAccentPrimary, Color.White)
        StepStatus.INACTIVE -> Triple(ColorBgSurface, ColorBorderMedium, ColorTextDisabled)
    }

    val labelColor = when (status) {
        StepStatus.DONE -> ColorStatusReady
        StepStatus.ACTIVE -> ColorTextPrimary
        StepStatus.INACTIVE -> ColorTextMuted
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.padding(horizontal = 2.dp)
    ) {
        Box(
            modifier = Modifier
                .size(22.dp)
                .clip(CircleShape)
                .background(nodeBg)
                .border(1.5.dp, nodeBorder, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = if (status == StepStatus.DONE) "✓" else "$stepNumber",
                color = nodeTextColor,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = label,
            color = labelColor,
            fontSize = 9.5.sp,
            fontWeight = if (status == StepStatus.ACTIVE) FontWeight.SemiBold else FontWeight.Medium,
            textAlign = TextAlign.Center
        )
    }
}
