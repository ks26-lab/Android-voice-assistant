package com.chockXlate.teachablevoice.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chockXlate.teachablevoice.ui.mock.RuntimeScenario
import com.chockXlate.teachablevoice.ui.mock.RuntimeState
import com.chockXlate.teachablevoice.ui.mock.RuntimeStepData
import com.chockXlate.teachablevoice.ui.theme.*

@Composable
fun RuntimeCard(
    runtimeState: RuntimeState,
    selectedScenario: RuntimeScenario,
    currentStep: Int,
    steps: List<RuntimeStepData>,
    onAction: (String, Any?) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        SectionHeader(title = "Runtime")

        val cardBg = when (runtimeState) {
            RuntimeState.RUNNING -> Brush.verticalGradient(listOf(ColorBgSurfaceElevated, ColorBgSurface))
            RuntimeState.USER_HANDOFF -> Brush.verticalGradient(listOf(Color(0x10F59E0B), ColorBgSurface))
            RuntimeState.CLARIFICATION_NEEDED, RuntimeState.RECOVERING -> Brush.verticalGradient(listOf(Color(0x10A78BFA), ColorBgSurface))
            else -> Brush.verticalGradient(listOf(ColorBgSurface, ColorBgSurface))
        }

        val cardBorder = when (runtimeState) {
            RuntimeState.RUNNING -> ColorAccentPrimary
            RuntimeState.USER_HANDOFF -> Color(0x66F59E0B)
            RuntimeState.CLARIFICATION_NEEDED, RuntimeState.RECOVERING -> ColorAccentPrimary
            RuntimeState.TARGET_NOT_RESOLVED -> Color(0x66EF4444)
            else -> ColorBorderSubtle
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RadiusMd)
                .background(cardBg)
                .border(1.dp, cardBorder, RadiusMd)
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            when (runtimeState) {
                RuntimeState.NO_WORKFLOW -> {
                    Text(
                        text = "No workflow available",
                        color = ColorTextSecondary,
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "Teach a skill to enable execution",
                        color = ColorTextMuted,
                        fontSize = 11.sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    PrimaryButton(label = "Execute Runtime", disabled = true, modifier = Modifier.fillMaxWidth())
                    SecondaryButton(label = "Resume Runtime", disabled = true, modifier = Modifier.fillMaxWidth())
                }

                RuntimeState.READY -> {
                    // Scenario selector pills
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RadiusXs)
                            .background(ColorBgSurfaceSubtle)
                            .border(1.dp, ColorBorderSubtle, RadiusXs)
                            .padding(2.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        RuntimeScenario.values().forEach { sc ->
                            val isSelected = selectedScenario == sc
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RadiusXs)
                                    .background(if (isSelected) ColorBgSurfaceElevated else Color.Transparent)
                                    .border(
                                        1.dp,
                                        if (isSelected) ColorBorderMedium else Color.Transparent,
                                        RadiusXs
                                    )
                                    .clickable { onAction("SELECT_SCENARIO", sc) }
                                    .padding(vertical = 4.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = sc.label,
                                    color = if (isSelected) Color.White else ColorTextMuted,
                                    fontSize = 10.sp,
                                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    }

                    Text(
                        text = "Order Food",
                        color = ColorTextSecondary,
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "Ready to execute",
                        color = ColorTextMuted,
                        fontSize = 11.sp
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    PrimaryButton(
                        label = "Execute Runtime",
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { onAction("START_RUNTIME", null) }
                    )
                    SecondaryButton(
                        label = "Resume Runtime",
                        disabled = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        Text(
                            text = "Reset",
                            color = ColorTextMuted,
                            fontSize = 11.sp,
                            modifier = Modifier
                                .clip(RadiusXs)
                                .clickable { onAction("RESET_RUNTIME", null) }
                                .padding(4.dp)
                        )
                    }
                }

                RuntimeState.STARTING -> {
                    Text(
                        text = "Order Food",
                        color = ColorTextPrimary,
                        fontSize = 14.5.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Preparing runtime execution...",
                        color = ColorTextMuted,
                        fontSize = 11.sp
                    )
                    PrimaryButton(label = "Starting...", disabled = true, modifier = Modifier.fillMaxWidth())
                }

                RuntimeState.RUNNING -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Order Food",
                            color = ColorTextPrimary,
                            fontSize = 14.5.sp,
                            fontWeight = FontWeight.Bold
                        )
                        StatusIndicator(status = "STEP $currentStep OF ${steps.size}", variant = StatusVariant.RUNNING)
                    }

                    // Progress chips
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        steps.forEach { step ->
                            val isDone = step.stepNumber < currentStep
                            val isActive = step.stepNumber == currentStep
                            val chipBg = when {
                                isActive -> ColorAccentPrimary
                                isDone -> ColorStatusReadyBg
                                else -> ColorBgSurfaceSubtle
                            }
                            val chipBorder = when {
                                isActive -> ColorAccentPrimary
                                isDone -> ColorStatusReady
                                else -> ColorBorderSubtle
                            }
                            val chipText = when {
                                isActive -> Color.White
                                isDone -> ColorStatusReady
                                else -> ColorTextMuted
                            }
                            Box(
                                modifier = Modifier
                                    .clip(RadiusXs)
                                    .background(chipBg)
                                    .border(1.dp, chipBorder, RadiusXs)
                                    .padding(horizontal = 8.dp, vertical = 3.dp)
                            ) {
                                Text(
                                    text = "${step.stepNumber} ${step.action}",
                                    color = chipText,
                                    fontSize = 10.5.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal
                                )
                            }
                        }
                    }

                    // Current action box
                    val curAction = steps.getOrNull(currentStep - 1)
                    if (curAction != null) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RadiusSm)
                                .background(ColorBgSurfaceSubtle)
                                .border(1.dp, ColorBorderSubtle, RadiusSm)
                                .padding(10.dp)
                        ) {
                            Text(
                                text = "CURRENT ACTION",
                                color = ColorTextMuted,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold,
                                letterSpacing = 0.05.sp
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "${curAction.action} -> ${curAction.target}",
                                color = ColorTextAccent,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            )
                            Text(
                                text = curAction.description,
                                color = ColorTextSecondary,
                                fontSize = 11.5.sp
                            )
                        }
                    }

                    PrimaryButton(
                        label = "Pause Runtime",
                        variant = PrimaryButtonVariant.WARNING,
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { onAction("PAUSE_RUNTIME", null) }
                    )
                }

                RuntimeState.PAUSED -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(text = "Order Food", color = ColorTextPrimary, fontSize = 14.5.sp, fontWeight = FontWeight.Bold)
                        StatusIndicator(status = "PAUSED", variant = StatusVariant.PAUSED)
                    }
                    Text(
                        text = "Execution suspended at step $currentStep of ${steps.size}.",
                        color = ColorTextSecondary,
                        fontSize = 12.sp
                    )
                    PrimaryButton(
                        label = "Resume Runtime",
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { onAction("RESUME_RUNTIME", null) }
                    )
                    SecondaryButton(
                        label = "Reset",
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { onAction("RESET_RUNTIME", null) }
                    )
                }

                RuntimeState.USER_HANDOFF -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(text = "Order Food", color = ColorTextPrimary, fontSize = 14.5.sp, fontWeight = FontWeight.Bold)
                        StatusIndicator(status = "USER HANDOFF", variant = StatusVariant.HANDOFF)
                    }

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RadiusSm)
                            .background(Color(0x14F59E0B))
                            .border(1.dp, Color(0x40F59E0B), RadiusSm)
                            .padding(10.dp)
                    ) {
                        Text(
                            text = "⚠ User Control Required",
                            color = Color(0xFFFBBF24),
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "Automation stopped at Payment. Sensitive action requires user confirmation.",
                            color = ColorTextSecondary,
                            fontSize = 11.5.sp
                        )
                    }

                    PrimaryButton(
                        label = "Return Control to User",
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { onAction("RETURN_TO_USER", null) }
                    )
                    SecondaryButton(
                        label = "Reset",
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { onAction("RESET_RUNTIME", null) }
                    )
                }

                RuntimeState.CLARIFICATION_NEEDED -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(text = "Order Food", color = ColorTextPrimary, fontSize = 14.5.sp, fontWeight = FontWeight.Bold)
                        StatusIndicator(status = "WAITING", variant = StatusVariant.WAITING)
                    }

                    Text(
                        text = "Target could not be resolved. Please clarify which restaurant target you intended:",
                        color = ColorTextSecondary,
                        fontSize = 11.5.sp
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RadiusXs)
                                .background(ColorBgSurfaceElevated)
                                .border(1.dp, ColorBorderMedium, RadiusXs)
                                .clickable { onAction("PROVIDE_CLARIFICATION", "Pizza Palace") }
                                .padding(8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(text = "Pizza Palace", color = ColorTextPrimary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                        }
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RadiusXs)
                                .background(ColorBgSurfaceElevated)
                                .border(1.dp, ColorBorderMedium, RadiusXs)
                                .clickable { onAction("PROVIDE_CLARIFICATION", "Domino's") }
                                .padding(8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(text = "Domino's", color = ColorTextPrimary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                }

                RuntimeState.RECOVERING -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(text = "Order Food", color = ColorTextPrimary, fontSize = 14.5.sp, fontWeight = FontWeight.Bold)
                        StatusIndicator(status = "RECOVERING", variant = StatusVariant.RECOVERING)
                    }
                    Text(
                        text = "Attempting target resolution with selected option...",
                        color = ColorTextSecondary,
                        fontSize = 12.sp
                    )
                }

                RuntimeState.TARGET_NOT_RESOLVED -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(text = "Order Food", color = ColorTextPrimary, fontSize = 14.5.sp, fontWeight = FontWeight.Bold)
                        StatusIndicator(status = "UNRESOLVED", variant = StatusVariant.UNRESOLVED)
                    }

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RadiusSm)
                            .background(ColorStatusErrorBg)
                            .border(1.dp, ColorStatusErrorBorder, RadiusSm)
                            .padding(10.dp)
                    ) {
                        Text(
                            text = "⚠ Target Not Resolved",
                            color = Color(0xFFF87171),
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "Automation paused. Target element selector failed. User input required.",
                            color = ColorTextSecondary,
                            fontSize = 11.5.sp
                        )
                    }

                    PrimaryButton(
                        label = "Return Control to User",
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { onAction("RETURN_TO_USER", null) }
                    )
                    SecondaryButton(
                        label = "Reset",
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { onAction("RESET_RUNTIME", null) }
                    )
                }

                RuntimeState.COMPLETED -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(text = "Order Food", color = ColorTextPrimary, fontSize = 14.5.sp, fontWeight = FontWeight.Bold)
                        StatusIndicator(status = "COMPLETED", variant = StatusVariant.COMPLETED)
                    }

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RadiusSm)
                            .background(ColorStatusReadyBg)
                            .border(1.dp, ColorStatusReadyBorder, RadiusSm)
                            .padding(10.dp)
                    ) {
                        Text(
                            text = "✓ Workflow Completed",
                            color = ColorStatusReady,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text(text = "5 steps executed", color = ColorTextSecondary, fontSize = 11.sp)
                            Text(text = "0 failures", color = ColorTextSecondary, fontSize = 11.sp)
                            Text(text = "12.4 s duration", color = ColorTextSecondary, fontSize = 11.sp)
                        }
                    }

                    PrimaryButton(
                        label = "Execute Again",
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { onAction("START_RUNTIME", null) }
                    )
                    SecondaryButton(
                        label = "Reset",
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { onAction("RESET_RUNTIME", null) }
                    )
                }
            }
        }
    }
}
