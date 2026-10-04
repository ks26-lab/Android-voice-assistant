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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.samsung.prism.uidemo.ui.mock.UiState
import com.samsung.prism.uidemo.ui.theme.*

@Composable
fun TeachSection(
    uiState: UiState,
    onStartTeaching: () -> Unit,
    onStopTeaching: () -> Unit,
    onNormalizeTrace: () -> Unit,
    onExtractActions: () -> Unit,
    onValidateWorkflow: () -> Unit,
    onStoreSkill: () -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        SectionHeader(title = "Teach")
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            when (uiState) {
                UiState.READY -> {
                    CustomButton(
                        text = "Start Teaching",
                        onClick = onStartTeaching,
                        modifier = Modifier.weight(1f),
                        isPrimary = true
                    )
                    CustomButton(
                        text = "Stop Teaching",
                        onClick = {},
                        enabled = false,
                        modifier = Modifier.weight(1f),
                        isPrimary = false
                    )
                }
                UiState.TEACHING -> {
                    CustomButton(
                        text = "Stop Teaching",
                        onClick = onStopTeaching,
                        modifier = Modifier.fillMaxWidth(),
                        isPrimary = true,
                        backgroundColor = StatusWarning,
                        textColor = Color.Black
                    )
                }
                UiState.TRACE_CAPTURED -> {
                    CustomButton(
                        text = "Normalize Trace",
                        onClick = onNormalizeTrace,
                        modifier = Modifier.weight(1f),
                        isPrimary = true
                    )
                    CustomButton(
                        text = "Reset",
                        onClick = onReset,
                        modifier = Modifier.weight(1f),
                        isPrimary = false
                    )
                }
                UiState.NORMALIZING -> {
                    CustomButton(
                        text = "Normalizing...",
                        onClick = {},
                        enabled = false,
                        modifier = Modifier.fillMaxWidth(),
                        isPrimary = true
                    )
                }
                UiState.NORMALIZED -> {
                    CustomButton(
                        text = "Extract Semantic Actions",
                        onClick = onExtractActions,
                        modifier = Modifier.weight(1.3f),
                        isPrimary = true
                    )
                    CustomButton(
                        text = "Reset",
                        onClick = onReset,
                        modifier = Modifier.weight(0.7f),
                        isPrimary = false
                    )
                }
                UiState.EXTRACTING -> {
                    CustomButton(
                        text = "Validate Learned Workflow",
                        onClick = onValidateWorkflow,
                        modifier = Modifier.weight(1.3f),
                        isPrimary = true
                    )
                    CustomButton(
                        text = "Reset",
                        onClick = onReset,
                        modifier = Modifier.weight(0.7f),
                        isPrimary = false
                    )
                }
                UiState.VALIDATING -> {
                    CustomButton(
                        text = "Store Learned Skill",
                        onClick = onStoreSkill,
                        modifier = Modifier.weight(1.3f),
                        isPrimary = true
                    )
                    CustomButton(
                        text = "Reset",
                        onClick = onReset,
                        modifier = Modifier.weight(0.7f),
                        isPrimary = false
                    )
                }
                UiState.SKILL_STORED -> {
                    CustomButton(
                        text = "Teach Another Workflow",
                        onClick = onStartTeaching,
                        modifier = Modifier.weight(1.3f),
                        isPrimary = true
                    )
                    CustomButton(
                        text = "Reset All",
                        onClick = onReset,
                        modifier = Modifier.weight(0.7f),
                        isPrimary = false
                    )
                }
            }
        }
    }
}

@Composable
fun CustomButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    isPrimary: Boolean = true,
    backgroundColor: Color? = null,
    textColor: Color? = null
) {
    val bg = when {
        !enabled -> BgSurfaceElevated
        backgroundColor != null -> backgroundColor
        isPrimary -> AccentPrimary
        else -> BgSurfaceElevated
    }
    val border = when {
        !enabled -> BorderSubtle
        isPrimary -> BorderFocus
        else -> BorderMedium
    }
    val txtColor = when {
        !enabled -> TextDisabled
        textColor != null -> textColor
        isPrimary -> TextPrimary
        else -> TextPrimary
    }

    Box(
        modifier = modifier
            .height(42.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .border(1.dp, border, RoundedCornerShape(8.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = txtColor,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}
