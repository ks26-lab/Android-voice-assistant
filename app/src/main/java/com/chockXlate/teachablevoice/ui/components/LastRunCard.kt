package com.chockXlate.teachablevoice.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chockXlate.teachablevoice.ui.mock.LastRunData
import com.chockXlate.teachablevoice.ui.theme.*

@Composable
fun LastRunCard(
    lastRunData: LastRunData,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        SectionHeader(title = "Last Run")

        if (!lastRunData.hasHistory) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RadiusMd)
                    .background(ColorBgSurface)
                    .border(1.dp, ColorBorderSubtle, RadiusMd)
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                Text(
                    text = lastRunData.emptyTitle,
                    color = ColorTextSecondary,
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = lastRunData.emptyDescription,
                    color = ColorTextMuted,
                    fontSize = 11.sp
                )
            }
        } else {
            val gradientBrush = Brush.verticalGradient(
                colors = listOf(ColorBgSurfaceElevated, ColorBgSurface)
            )

            val isHandoff = lastRunData.outcome == "HANDOFF" || lastRunData.status == "HANDOFF"
            val isError = lastRunData.outcome == "TARGET_NOT_RESOLVED"

            val (badgeBg, badgeBorder, badgeText, badgeLabel) = when {
                isHandoff -> Quadruple(Color(0x1FF59E0B), Color(0x59F59E0B), Color(0xFFFBBF24), "⚠ HANDOFF")
                isError -> Quadruple(Color(0x1FF59E0B), Color(0x59F59E0B), Color(0xFFFBBF24), "⚠ UNRESOLVED")
                else -> Quadruple(ColorStatusReadyBg, ColorStatusReadyBorder, ColorStatusReady, "✓ SUCCESS")
            }

            Column(
                modifier = Modifier
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
                        text = lastRunData.skillName,
                        color = ColorTextPrimary,
                        fontSize = 14.5.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Box(
                        modifier = Modifier
                            .clip(RadiusFull)
                            .background(badgeBg)
                            .border(1.dp, badgeBorder, RadiusFull)
                            .padding(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = badgeLabel,
                            color = badgeText,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.04.sp
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (isHandoff) {
                        Text(text = "Stopped at: ${lastRunData.stoppedAt}", color = ColorTextSecondary, fontSize = 11.5.sp)
                        Text(text = "•", color = ColorTextMuted)
                        Text(text = "${lastRunData.completedSteps} / ${lastRunData.totalSteps} steps", color = ColorTextSecondary, fontSize = 11.5.sp)
                    } else if (isError) {
                        Text(text = "Target: ${lastRunData.target}", color = ColorTextSecondary, fontSize = 11.5.sp)
                        Text(text = "•", color = ColorTextMuted)
                        Text(text = "Unresolved", color = ColorTextSecondary, fontSize = 11.5.sp)
                    } else {
                        Text(text = "${lastRunData.stepsCount} steps executed", color = ColorTextSecondary, fontSize = 11.5.sp)
                        Text(text = "•", color = ColorTextMuted)
                        Text(text = "${lastRunData.failuresCount} failures", color = ColorTextSecondary, fontSize = 11.5.sp)
                        Text(text = "•", color = ColorTextMuted)
                        Text(text = lastRunData.duration, color = ColorTextSecondary, fontSize = 11.5.sp)
                    }
                }
            }
        }
    }
}

private data class Quadruple<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)
