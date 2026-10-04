package com.chockXlate.teachablevoice.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.chockXlate.teachablevoice.ui.mock.UiState

@Composable
fun TeachSection(
    currentState: UiState,
    onAction: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        SectionHeader(title = "Teach")
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            when (currentState) {
                UiState.READY -> {
                    PrimaryButton(
                        label = "Start Teaching",
                        modifier = Modifier.weight(1f),
                        onClick = { onAction("START_TEACHING") }
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
                        onClick = { onAction("STOP_TEACHING") }
                    )
                }
                UiState.TRACE_CAPTURED -> {
                    PrimaryButton(
                        label = "Normalize Trace",
                        modifier = Modifier.weight(1f),
                        onClick = { onAction("NORMALIZE_TRACE") }
                    )
                    SecondaryButton(
                        label = "Discard",
                        modifier = Modifier.weight(1f),
                        onClick = { onAction("RESET") }
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
                        onClick = { onAction("EXTRACT_ACTIONS") }
                    )
                    SecondaryButton(
                        label = "Discard",
                        modifier = Modifier.weight(1f),
                        onClick = { onAction("RESET") }
                    )
                }
                UiState.EXTRACTING -> {
                    PrimaryButton(
                        label = "Validate Workflow",
                        modifier = Modifier.weight(1f),
                        onClick = { onAction("VALIDATE_WORKFLOW") }
                    )
                    SecondaryButton(
                        label = "Discard",
                        modifier = Modifier.weight(1f),
                        onClick = { onAction("RESET") }
                    )
                }
                UiState.VALIDATING -> {
                    PrimaryButton(
                        label = "Store Learned Skill",
                        modifier = Modifier.weight(1f),
                        onClick = { onAction("STORE_SKILL") }
                    )
                    SecondaryButton(
                        label = "Discard",
                        modifier = Modifier.weight(1f),
                        onClick = { onAction("RESET") }
                    )
                }
                UiState.SKILL_STORED -> {
                    PrimaryButton(
                        label = "Teach New Workflow",
                        modifier = Modifier.weight(1f),
                        onClick = { onAction("START_TEACHING") }
                    )
                    SecondaryButton(
                        label = "Reset",
                        modifier = Modifier.weight(1f),
                        onClick = { onAction("RESET") }
                    )
                }
            }
        }
    }
}
