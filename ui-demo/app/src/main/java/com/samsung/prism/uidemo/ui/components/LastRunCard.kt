package com.samsung.prism.uidemo.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.samsung.prism.uidemo.ui.mock.LastRunData
import com.samsung.prism.uidemo.ui.theme.*

@Composable
fun LastRunCard(
    lastRunData: LastRunData,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        SectionHeader(title = "Last Run")
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(BgSurface, RoundedCornerShape(12.dp))
                .border(1.dp, BorderMedium, RoundedCornerShape(12.dp))
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (!lastRunData.hasHistory) {
                Text(
                    text = lastRunData.emptyTitle,
                    color = TextPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = lastRunData.emptyDescription,
                    color = TextMuted,
                    fontSize = 12.sp
                )
            } else {
                val isHandoff = lastRunData.outcome == "HANDOFF" || lastRunData.status == "HANDOFF"
                val isError = lastRunData.outcome == "TARGET_NOT_RESOLVED"

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = lastRunData.skillName,
                        color = TextPrimary,
                        fontSize = 14.5.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Box(
                        modifier = Modifier
                            .background(
                                if (isHandoff || isError) StatusErrorBg else StatusReadyBg,
                                RoundedCornerShape(6.dp)
                            )
                            .border(
                                1.dp,
                                if (isHandoff || isError) StatusErrorBorder else StatusReadyBorder,
                                RoundedCornerShape(6.dp)
                            )
                            .padding(horizontal = 7.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = when {
                                isHandoff -> "⚠ HANDOFF"
                                isError -> "⚠ UNRESOLVED"
                                else -> "✓ SUCCESS"
                            },
                            color = if (isHandoff || isError) StatusError else StatusReady,
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    when {
                        isHandoff -> {
                            Text(
                                text = "Stopped at: ${lastRunData.stoppedAt ?: "Payment"}",
                                color = TextSecondary,
                                fontSize = 11.5.sp
                            )
                            Text(text = "•", color = TextMuted, fontSize = 11.sp)
                            Text(
                                text = "${lastRunData.completedSteps} / ${lastRunData.totalSteps} steps",
                                color = TextSecondary,
                                fontSize = 11.5.sp
                            )
                        }
                        isError -> {
                            Text(
                                text = "Target: ${lastRunData.target ?: "Restaurant"}",
                                color = TextSecondary,
                                fontSize = 11.5.sp
                            )
                            Text(text = "•", color = TextMuted, fontSize = 11.sp)
                            Text(
                                text = "Unresolved",
                                color = TextSecondary,
                                fontSize = 11.5.sp
                            )
                        }
                        else -> {
                            Text(
                                text = "${lastRunData.stepsCount} steps executed",
                                color = TextSecondary,
                                fontSize = 11.5.sp
                            )
                            Text(text = "•", color = TextMuted, fontSize = 11.sp)
                            Text(
                                text = "${lastRunData.failuresCount} failures",
                                color = TextSecondary,
                                fontSize = 11.5.sp
                            )
                            Text(text = "•", color = TextMuted, fontSize = 11.sp)
                            Text(
                                text = lastRunData.duration,
                                color = TextSecondary,
                                fontSize = 11.5.sp
                            )
                        }
                    }
                }
            }
        }
    }
}
