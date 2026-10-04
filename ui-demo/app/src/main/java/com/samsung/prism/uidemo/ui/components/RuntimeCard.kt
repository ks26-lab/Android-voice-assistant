package com.samsung.prism.uidemo.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.samsung.prism.uidemo.ui.mock.*
import com.samsung.prism.uidemo.ui.theme.*

@Composable
fun RuntimeCard(
    runtimeState: RuntimeState,
    hasWorkflow: Boolean,
    currentStep: Int,
    selectedScenario: RuntimeScenario,
    onSelectScenario: (RuntimeScenario) -> Unit,
    onStartRuntime: () -> Unit,
    onPauseRuntime: () -> Unit,
    onResumeRuntime: () -> Unit,
    onProvideClarification: (String) -> Unit,
    onReturnToUser: () -> Unit,
    onResetRuntime: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        SectionHeader(title = "Runtime")
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(BgSurface, RoundedCornerShape(12.dp))
                .border(
                    1.dp,
                    when (runtimeState) {
                        RuntimeState.RUNNING -> BorderFocus
                        RuntimeState.USER_HANDOFF, RuntimeState.TARGET_NOT_RESOLVED -> StatusErrorBorder
                        RuntimeState.CLARIFICATION_NEEDED -> StatusWarningBorder
                        RuntimeState.COMPLETED -> StatusReadyBorder
                        else -> BorderMedium
                    },
                    RoundedCornerShape(12.dp)
                )
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            when {
                // 1. NO WORKFLOW
                !hasWorkflow || runtimeState == RuntimeState.NO_WORKFLOW -> {
                    Text(
                        text = "No workflow available",
                        color = TextPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "Teach a skill to enable execution",
                        color = TextMuted,
                        fontSize = 12.sp
                    )
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        CustomButton(
                            text = "Execute Runtime",
                            onClick = {},
                            enabled = false,
                            modifier = Modifier.fillMaxWidth(),
                            isPrimary = true
                        )
                        CustomButton(
                            text = "Resume Runtime",
                            onClick = {},
                            enabled = false,
                            modifier = Modifier.fillMaxWidth(),
                            isPrimary = false
                        )
                    }
                }

                // 2. READY STATE
                runtimeState == RuntimeState.READY -> {
                    // Scenario Selector
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        RuntimeScenario.values().forEach { sc ->
                            val isSelected = selectedScenario == sc
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(if (isSelected) AccentPrimary else BgSurfaceElevated)
                                    .border(
                                        1.dp,
                                        if (isSelected) BorderFocus else BorderSubtle,
                                        RoundedCornerShape(6.dp)
                                    )
                                    .clickable { onSelectScenario(sc) }
                                    .padding(vertical = 5.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = sc.label,
                                    color = if (isSelected) TextPrimary else TextMuted,
                                    fontSize = 10.5.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                )
                            }
                        }
                    }

                    Text(
                        text = "Order Food",
                        color = TextPrimary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Ready to execute",
                        color = TextMuted,
                        fontSize = 12.sp
                    )

                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        CustomButton(
                            text = "Execute Runtime",
                            onClick = onStartRuntime,
                            modifier = Modifier.fillMaxWidth(),
                            isPrimary = true
                        )
                        CustomButton(
                            text = "Resume Runtime",
                            onClick = {},
                            enabled = false,
                            modifier = Modifier.fillMaxWidth(),
                            isPrimary = false
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Last Run",
                            color = TextMuted,
                            fontSize = 11.sp
                        )
                        Text(
                            text = "Reset",
                            color = TextAccent,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.clickable(onClick = onResetRuntime)
                        )
                    }
                }

                // 3. STARTING STATE
                runtimeState == RuntimeState.STARTING -> {
                    Text(
                        text = "Order Food",
                        color = TextPrimary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Preparing runtime execution...",
                        color = TextMuted,
                        fontSize = 12.sp
                    )
                    CustomButton(
                        text = "Starting...",
                        onClick = {},
                        enabled = false,
                        modifier = Modifier.fillMaxWidth(),
                        isPrimary = true
                    )
                }

                // 4. RUNNING STATE
                runtimeState == RuntimeState.RUNNING -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Order Food",
                            color = TextPrimary,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        )
                        StatusIndicator(
                            status = "Step $currentStep of 5",
                            variant = "learning"
                        )
                    }

                    // Progress Chips
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        MockData.runtimeMockSteps.forEachIndexed { idx, s ->
                            val isDone = (idx + 1) < currentStep
                            val isActive = (idx + 1) == currentStep
                            val (chipBg, chipBorder, chipText) = when {
                                isDone -> Triple(StatusReadyBg, StatusReadyBorder, StatusReady)
                                isActive -> Triple(AccentSubtle, BorderFocus, TextAccent)
                                else -> Triple(BgSurfaceElevated, BorderSubtle, TextDisabled)
                            }

                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .background(chipBg, RoundedCornerShape(4.dp))
                                    .border(1.dp, chipBorder, RoundedCornerShape(4.dp))
                                    .padding(vertical = 4.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = if (isDone) "${s.action} ✓" else s.action,
                                    color = chipText,
                                    fontSize = 9.sp,
                                    fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }
                    }

                    // Current Action Box
                    val activeStep = MockData.runtimeMockSteps.getOrNull(currentStep - 1)
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(BgSurfaceElevated, RoundedCornerShape(8.dp))
                            .border(1.dp, BorderSubtle, RoundedCornerShape(8.dp))
                            .padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = "CURRENT ACTION • STEP $currentStep / 5",
                            color = TextSecondary,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.5.sp
                        )
                        Text(
                            text = activeStep?.action ?: "SEARCH",
                            color = TextPrimary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = activeStep?.description ?: "Executing action...",
                            color = TextMuted,
                            fontSize = 11.5.sp
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        CustomButton(
                            text = "Pause",
                            onClick = onPauseRuntime,
                            modifier = Modifier.weight(1f),
                            isPrimary = false
                        )
                        CustomButton(
                            text = "Reset",
                            onClick = onResetRuntime,
                            modifier = Modifier.weight(1f),
                            isPrimary = false
                        )
                    }
                }

                // 5. PAUSED STATE
                runtimeState == RuntimeState.PAUSED -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Order Food",
                            color = TextPrimary,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        )
                        StatusIndicator(
                            status = "PAUSED ($currentStep / 5)",
                            variant = "paused"
                        )
                    }
                    Text(
                        text = "Execution paused at step $currentStep",
                        color = TextMuted,
                        fontSize = 12.sp
                    )
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        CustomButton(
                            text = "Resume Runtime",
                            onClick = onResumeRuntime,
                            modifier = Modifier.fillMaxWidth(),
                            isPrimary = true
                        )
                        CustomButton(
                            text = "Reset",
                            onClick = onResetRuntime,
                            modifier = Modifier.fillMaxWidth(),
                            isPrimary = false
                        )
                    }
                }

                // 6. USER HANDOFF STATE
                runtimeState == RuntimeState.USER_HANDOFF -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Order Food",
                            color = TextPrimary,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        )
                        StatusIndicator(
                            status = "⚠ USER HANDOFF",
                            variant = "handoff"
                        )
                    }

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(StatusErrorBg, RoundedCornerShape(8.dp))
                            .border(1.dp, StatusErrorBorder, RoundedCornerShape(8.dp))
                            .padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = "Stopped at: Payment",
                            color = StatusError,
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Reason: Sensitive action requires user confirmation.",
                            color = TextPrimary,
                            fontSize = 11.5.sp
                        )
                    }

                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        CustomButton(
                            text = "Continue Manually",
                            onClick = onReturnToUser,
                            modifier = Modifier.fillMaxWidth(),
                            isPrimary = true,
                            backgroundColor = StatusWarning,
                            textColor = Color.Black
                        )
                        CustomButton(
                            text = "Reset",
                            onClick = onResetRuntime,
                            modifier = Modifier.fillMaxWidth(),
                            isPrimary = false
                        )
                    }
                }

                // 7. CLARIFICATION NEEDED STATE
                runtimeState == RuntimeState.CLARIFICATION_NEEDED -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Order Food",
                            color = TextPrimary,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        )
                        StatusIndicator(
                            status = "WAITING FOR USER",
                            variant = "waiting"
                        )
                    }

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(StatusTeachingBg, RoundedCornerShape(8.dp))
                            .border(1.dp, StatusTeachingBorder, RoundedCornerShape(8.dp))
                            .padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "Clarification Needed: Target Ambiguity",
                            color = TextAccent,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Which restaurant should I use?",
                            color = TextPrimary,
                            fontSize = 11.5.sp
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .background(BgSurface, RoundedCornerShape(6.dp))
                                    .border(1.dp, BorderFocus, RoundedCornerShape(6.dp))
                                    .clickable { onProvideClarification("Pizza Palace") }
                                    .padding(vertical = 8.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "Pizza Palace",
                                    color = TextPrimary,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .background(BgSurface, RoundedCornerShape(6.dp))
                                    .border(1.dp, BorderMedium, RoundedCornerShape(6.dp))
                                    .clickable { onProvideClarification("Domino's") }
                                    .padding(vertical = 8.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "Domino's",
                                    color = TextPrimary,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }

                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        CustomButton(
                            text = "Clarify / Continue",
                            onClick = { onProvideClarification("Pizza Palace") },
                            modifier = Modifier.fillMaxWidth(),
                            isPrimary = true
                        )
                        CustomButton(
                            text = "Reset",
                            onClick = onResetRuntime,
                            modifier = Modifier.fillMaxWidth(),
                            isPrimary = false
                        )
                    }
                }

                // 8. RECOVERING STATE
                runtimeState == RuntimeState.RECOVERING -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Order Food",
                            color = TextPrimary,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        )
                        StatusIndicator(
                            status = "RECOVERING",
                            variant = "learning"
                        )
                    }
                    Text(
                        text = "Attempting to resolve target again...",
                        color = TextMuted,
                        fontSize = 12.sp
                    )
                }

                // 9. TARGET NOT RESOLVED STATE
                runtimeState == RuntimeState.TARGET_NOT_RESOLVED -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Order Food",
                            color = TextPrimary,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        )
                        StatusIndicator(
                            status = "⚠ TARGET NOT RESOLVED",
                            variant = "unresolved"
                        )
                    }

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(StatusErrorBg, RoundedCornerShape(8.dp))
                            .border(1.dp, StatusErrorBorder, RoundedCornerShape(8.dp))
                            .padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = "Target: Restaurant",
                            color = StatusError,
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Could not uniquely identify the requested target. Automation paused. User input required.",
                            color = TextPrimary,
                            fontSize = 11.5.sp
                        )
                    }

                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        CustomButton(
                            text = "Return to User",
                            onClick = onReturnToUser,
                            modifier = Modifier.fillMaxWidth(),
                            isPrimary = true,
                            backgroundColor = StatusError,
                            textColor = Color.White
                        )
                        CustomButton(
                            text = "Reset",
                            onClick = onResetRuntime,
                            modifier = Modifier.fillMaxWidth(),
                            isPrimary = false
                        )
                    }
                }

                // 10. COMPLETED STATE
                runtimeState == RuntimeState.COMPLETED -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Order Food",
                            color = TextPrimary,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        )
                        StatusIndicator(
                            status = "✓ COMPLETED",
                            variant = "success"
                        )
                    }

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(StatusReadyBg, RoundedCornerShape(8.dp))
                            .border(1.dp, StatusReadyBorder, RoundedCornerShape(8.dp))
                            .padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = "✓ SUCCESS (5 / 5 steps executed)",
                            color = StatusReady,
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "5 steps executed • 0 failures • 12.4s",
                            color = TextSecondary,
                            fontSize = 11.5.sp
                        )
                    }

                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        CustomButton(
                            text = "Execute Runtime Again",
                            onClick = onStartRuntime,
                            modifier = Modifier.fillMaxWidth(),
                            isPrimary = true
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Last Run",
                            color = TextMuted,
                            fontSize = 11.sp
                        )
                        Text(
                            text = "Reset",
                            color = TextAccent,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.clickable(onClick = onResetRuntime)
                        )
                    }
                }
            }
        }
    }
}
