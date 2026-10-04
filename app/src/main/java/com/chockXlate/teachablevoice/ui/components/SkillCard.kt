package com.chockXlate.teachablevoice.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Divider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chockXlate.teachablevoice.ui.mock.LearnedSkillData
import com.chockXlate.teachablevoice.ui.theme.*

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SkillCard(
    skillData: LearnedSkillData,
    isExpanded: Boolean,
    onToggleExpand: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        SectionHeader(title = "Learned Skill")

        if (!skillData.hasSkill) {
            // Empty / Building state
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RadiusMd)
                    .background(ColorBgSurface)
                    .border(1.dp, ColorBorderSubtle, RadiusMd)
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                if (skillData.state == "BUILDING") {
                    Text(
                        text = skillData.buildingTitle,
                        color = ColorTextSecondary,
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = skillData.buildingDescription,
                        color = ColorTextMuted,
                        fontSize = 11.sp
                    )
                } else {
                    Text(
                        text = skillData.emptyTitle,
                        color = ColorTextSecondary,
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = skillData.emptyDescription,
                        color = ColorTextMuted,
                        fontSize = 11.sp
                    )
                }
            }
        } else {
            // Populated state
            val gradientBrush = Brush.verticalGradient(
                colors = listOf(ColorBgSurfaceElevated, ColorBgSurface)
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RadiusMd)
                    .background(gradientBrush)
                    .border(1.dp, ColorBorderMedium, RadiusMd)
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                // Header Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top
                ) {
                    Column {
                        Text(
                            text = skillData.name,
                            color = ColorTextPrimary,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = (-0.01).sp
                        )
                        Text(
                            text = skillData.technicalId,
                            color = ColorTextMuted,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                    Box(
                        modifier = Modifier
                            .clip(RadiusFull)
                            .background(ColorStatusReadyBg)
                            .border(1.dp, ColorStatusReadyBorder, RadiusFull)
                            .padding(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = skillData.status,
                            color = ColorStatusReady,
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.05.sp
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Intent Section
                Text(
                    text = "INTENT",
                    color = ColorTextMuted,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.04.sp
                )
                Spacer(modifier = Modifier.height(4.dp))
                Box(
                    modifier = Modifier
                        .clip(RadiusXs)
                        .background(ColorBgSurfaceElevated)
                        .border(1.dp, ColorBorderSubtle, RadiusXs)
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    Text(
                        text = skillData.intent,
                        color = ColorTextAccent,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Slots Section
                if (skillData.slots.isNotEmpty()) {
                    Text(
                        text = "SLOTS",
                        color = ColorTextMuted,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 0.04.sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        skillData.slots.forEach { slot ->
                            Box(
                                modifier = Modifier
                                    .clip(RadiusXs)
                                    .background(ColorBgSurfaceElevated)
                                    .border(1.dp, ColorBorderMedium, RadiusXs)
                                    .padding(horizontal = 8.dp, vertical = 3.dp)
                            ) {
                                Text(
                                    text = "[ ${slot.name} ]",
                                    color = ColorTextSecondary,
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))
                Divider(color = ColorBorderSubtle, thickness = 1.dp)
                Spacer(modifier = Modifier.height(8.dp))

                // Footer Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = skillData.actionsLabel,
                        color = ColorTextSecondary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = if (isExpanded) "Hide Details ▴" else "↗ Inspect Skill ▾",
                        color = ColorTextAccent,
                        fontSize = 11.sp,
                        modifier = Modifier
                            .clip(RadiusXs)
                            .clickable { onToggleExpand() }
                            .padding(4.dp)
                    )
                }

                // Expanded Inspect Details
                AnimatedVisibility(
                    visible = isExpanded,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        if (skillData.appContext != null) {
                            Text(
                                text = "APP CONTEXT: ${skillData.appContext}",
                                color = ColorTextMuted,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold,
                                letterSpacing = 0.04.sp
                            )
                        }
                        if (skillData.safetyBoundaryText != null) {
                            Text(
                                text = "SAFETY: ${skillData.safetyBoundaryText}",
                                color = ColorStatusReady,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        if (skillData.slots.isNotEmpty()) {
                            Text(
                                text = "BOUND PARAMETER EXAMPLES",
                                color = ColorTextMuted,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold,
                                letterSpacing = 0.04.sp
                            )
                            skillData.slots.forEach { slot ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RadiusXs)
                                        .background(Color(0x05FFFFFF))
                                        .padding(horizontal = 6.dp, vertical = 3.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = slot.name,
                                        color = ColorTextSecondary,
                                        fontSize = 11.sp,
                                        fontFamily = FontFamily.Monospace
                                    )
                                    Text(
                                        text = slot.example ?: "unassigned",
                                        color = ColorTextMuted,
                                        fontSize = 11.sp,
                                        fontStyle = FontStyle.Italic
                                    )
                                }
                            }
                        }
                        if (!skillData.inspectionFormattedText.isNullOrBlank()) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "INSPECTION DETAILS",
                                color = ColorTextMuted,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold,
                                letterSpacing = 0.04.sp
                            )
                            Text(
                                text = skillData.inspectionFormattedText,
                                color = ColorTextSecondary,
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RadiusXs)
                                    .background(Color(0x08FFFFFF))
                                    .padding(6.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
