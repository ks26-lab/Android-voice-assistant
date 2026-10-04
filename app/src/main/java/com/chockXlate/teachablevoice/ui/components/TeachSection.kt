package com.chockXlate.teachablevoice.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chockXlate.teachablevoice.ui.mock.UiState
import com.chockXlate.teachablevoice.ui.theme.*

data class SkillCreationData(
    val name: String,
    val description: String = ""
)

@Composable
fun TeachSection(
    currentState: UiState,
    onAction: (String, Any?) -> Unit,
    activeSkillName: String? = null,
    activeSkillId: String? = null,
    modifier: Modifier = Modifier
) {
    var skillNameInput by remember { mutableStateOf("") }
    var skillDescInput by remember { mutableStateOf("") }
    var validationError by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        SectionHeader(title = "Teach")

        // Input card for skill name and description when preparing to teach
        if (currentState == UiState.READY || currentState == UiState.SKILL_STORED) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RadiusMd)
                    .background(ColorBgSurface)
                    .border(1.dp, ColorBorderSubtle, RadiusMd)
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "CREATE NEW SKILL",
                    color = ColorTextSecondary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp
                )

                // Skill Name Input
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        text = "Skill Name *",
                        color = ColorTextMuted,
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RadiusXs)
                            .background(ColorBgInput)
                            .border(
                                1.dp,
                                if (validationError != null) ColorStatusErrorBorder else ColorBorderSubtle,
                                RadiusXs
                            )
                            .padding(horizontal = 10.dp, vertical = 8.dp)
                    ) {
                        if (skillNameInput.isEmpty()) {
                            Text(
                                text = "e.g. Search Google, Open Amazon, Book Train...",
                                color = ColorTextMuted,
                                fontSize = 12.5.sp
                            )
                        }
                        BasicTextField(
                            value = skillNameInput,
                            onValueChange = {
                                skillNameInput = it
                                if (it.isNotBlank()) validationError = null
                            },
                            textStyle = TextStyle(
                                color = ColorTextPrimary,
                                fontSize = 12.5.sp,
                                fontWeight = FontWeight.Normal
                            ),
                            cursorBrush = SolidColor(ColorAccentPrimary),
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                    }
                }

                // Description Input
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        text = "Description / Task Goal",
                        color = ColorTextMuted,
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RadiusXs)
                            .background(ColorBgInput)
                            .border(1.dp, ColorBorderSubtle, RadiusXs)
                            .padding(horizontal = 10.dp, vertical = 8.dp)
                    ) {
                        if (skillDescInput.isEmpty()) {
                            Text(
                                text = "e.g. Search Google for a user-provided query...",
                                color = ColorTextMuted,
                                fontSize = 12.5.sp
                            )
                        }
                        BasicTextField(
                            value = skillDescInput,
                            onValueChange = { skillDescInput = it },
                            textStyle = TextStyle(
                                color = ColorTextPrimary,
                                fontSize = 12.5.sp,
                                fontWeight = FontWeight.Normal
                            ),
                            cursorBrush = SolidColor(ColorAccentPrimary),
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = false,
                            maxLines = 2
                        )
                    }
                }

                if (validationError != null) {
                    Text(
                        text = validationError ?: "",
                        color = ColorStatusError,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }

        // Action Buttons Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            when (currentState) {
                UiState.READY -> {
                    PrimaryButton(
                        label = "Start Teaching",
                        modifier = Modifier.weight(1f),
                        onClick = {
                            if (skillNameInput.trim().isBlank()) {
                                validationError = "Please provide a skill name before starting teaching."
                            } else {
                                validationError = null
                                val data = SkillCreationData(skillNameInput.trim(), skillDescInput.trim())
                                onAction("START_TEACHING", data)
                                skillNameInput = ""
                                skillDescInput = ""
                            }
                        }
                    )
                    SecondaryButton(
                        label = "Stop Teaching",
                        modifier = Modifier.weight(1f),
                        disabled = true
                    )
                }
                UiState.TEACHING -> {
                    PrimaryButton(
                        label = "Stop Teaching",
                        variant = PrimaryButtonVariant.DESTRUCTIVE,
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { onAction("STOP_TEACHING", null) }
                    )
                }
                UiState.TRACE_CAPTURED -> {
                    PrimaryButton(
                        label = "Normalize Trace",
                        modifier = Modifier.weight(1f),
                        onClick = { onAction("NORMALIZE_TRACE", null) }
                    )
                    SecondaryButton(
                        label = "Discard",
                        modifier = Modifier.weight(1f),
                        onClick = { onAction("RESET", null) }
                    )
                }
                UiState.NORMALIZING -> {
                    PrimaryButton(
                        label = "Normalizing...",
                        modifier = Modifier.weight(1f),
                        disabled = true
                    )
                    SecondaryButton(
                        label = "Discard",
                        modifier = Modifier.weight(1f),
                        disabled = true
                    )
                }
                UiState.NORMALIZED -> {
                    PrimaryButton(
                        label = "Extract Semantic Actions",
                        modifier = Modifier.weight(1f),
                        onClick = { onAction("EXTRACT_ACTIONS", null) }
                    )
                    SecondaryButton(
                        label = "Discard",
                        modifier = Modifier.weight(1f),
                        onClick = { onAction("RESET", null) }
                    )
                }
                UiState.EXTRACTING -> {
                    PrimaryButton(
                        label = "Validate Workflow",
                        modifier = Modifier.weight(1f),
                        onClick = { onAction("VALIDATE_WORKFLOW", null) }
                    )
                    SecondaryButton(
                        label = "Discard",
                        modifier = Modifier.weight(1f),
                        onClick = { onAction("RESET", null) }
                    )
                }
                UiState.VALIDATING -> {
                    PrimaryButton(
                        label = "Store Learned Skill",
                        modifier = Modifier.weight(1f),
                        onClick = { onAction("STORE_SKILL", null) }
                    )
                    SecondaryButton(
                        label = "Discard",
                        modifier = Modifier.weight(1f),
                        onClick = { onAction("RESET", null) }
                    )
                }
                UiState.SKILL_STORED -> {
                    PrimaryButton(
                        label = "Teach New Workflow",
                        modifier = Modifier.weight(1f),
                        onClick = {
                            if (skillNameInput.trim().isBlank()) {
                                validationError = "Please provide a skill name before starting teaching."
                            } else {
                                validationError = null
                                val data = SkillCreationData(skillNameInput.trim(), skillDescInput.trim())
                                onAction("START_TEACHING", data)
                                skillNameInput = ""
                                skillDescInput = ""
                            }
                        }
                    )
                    SecondaryButton(
                        label = "Reset",
                        modifier = Modifier.weight(1f),
                        onClick = { onAction("RESET", null) }
                    )
                }
            }
        }
    }
}
