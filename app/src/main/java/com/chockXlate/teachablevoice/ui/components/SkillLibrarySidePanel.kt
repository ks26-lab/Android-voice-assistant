package com.chockXlate.teachablevoice.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.chockXlate.teachablevoice.contract.skill.SkillRecord
import com.chockXlate.teachablevoice.contract.workflow.Workflow
import com.chockXlate.teachablevoice.skill.repository.SkillRepository
import com.chockXlate.teachablevoice.ui.theme.*

/**
 * Generic UI data model representing a skill item in the Skill Library.
 * Completely decoupled from Workflow, WorkflowStep, and runtime architecture.
 */
data class SkillUiModel(
    val id: String,
    val title: String,
    val description: String,
    val metadata: String? = null
)

/**
 * Maps a domain [Workflow] and optional [SkillRecord] to a UI-friendly [SkillUiModel].
 * Strictly derives all fields from existing domain facts; never invents text.
 */
fun mapWorkflowToSkillUiModel(workflow: Workflow, skillRecord: SkillRecord? = null): SkillUiModel {
    val title = workflow.name.ifBlank { skillRecord?.name?.ifBlank { null } ?: workflow.intent.ifBlank { workflow.skillId } }
    val description = skillRecord?.description?.takeIf { it.isNotBlank() } ?: workflow.intent.takeIf { it.isNotBlank() } ?: ""
    val metadata = buildString {
        append("${workflow.steps.size} step${if (workflow.steps.size != 1) "s" else ""}")
        if (workflow.appContext.isNotBlank()) {
            append(" • ${workflow.appContext}")
        }
    }
    return SkillUiModel(
        id = workflow.skillId,
        title = title,
        description = description,
        metadata = metadata
    )
}

/**
 * Maps a domain [SkillRecord] to a [SkillUiModel].
 */
fun mapSkillRecordToSkillUiModel(record: SkillRecord): SkillUiModel {
    return SkillUiModel(
        id = record.id,
        title = record.name.ifBlank { record.id },
        description = record.description,
        metadata = record.status.name
    )
}

/**
 * Reads all authentic stored workflows and skill records from the authoritative [SkillRepository]
 * and maps them into presentation models for the Skill Library panel.
 * Never creates fake skills or hardcoded catalogs.
 */
fun loadSkillsFromRepository(repository: SkillRepository): List<SkillUiModel> {
    val workflows = repository.getAllWorkflows()
    val records = repository.listSkills()
    val recordMap = records.associateBy { it.id }

    val fromWorkflows = workflows.map { wf ->
        mapWorkflowToSkillUiModel(wf, recordMap[wf.skillId])
    }

    val workflowIds = workflows.map { it.skillId }.toSet()
    val standaloneRecords = records.filter { it.id !in workflowIds }.map {
        mapSkillRecordToSkillUiModel(it)
    }

    return (fromWorkflows + standaloneRecords).sortedBy { it.title.lowercase() }
}

/**
 * Standalone presentation state model for the Skill Library panel.
 */
data class SkillLibraryUiState(
    val isOpen: Boolean = false,
    val searchQuery: String = "",
    val skills: List<SkillUiModel> = emptyList(),
    val skillToDelete: SkillUiModel? = null
)

/**
 * Pure, deterministic case-insensitive skill filtering for search.
 * Does not contain any hardcoded knowledge of skills or workflows.
 */
fun filterSkills(skills: List<SkillUiModel>, query: String): List<SkillUiModel> {
    if (query.isBlank()) return skills
    val clean = query.trim()
    return skills.filter {
        it.title.contains(clean, ignoreCase = true) ||
        it.description.contains(clean, ignoreCase = true) ||
        (it.metadata != null && it.metadata.contains(clean, ignoreCase = true))
    }
}

/**
 * Standalone Skill Library Side Panel component.
 * Displays dynamically supplied skills, local search, delete with confirmation, and redo actions.
 */
@Composable
fun SkillLibrarySidePanel(
    isOpen: Boolean,
    onClose: () -> Unit,
    skills: List<SkillUiModel>,
    onDelete: (String) -> Unit = {},
    onRedo: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    var searchQuery by remember { mutableStateOf("") }
    var skillToDelete by remember { mutableStateOf<SkillUiModel?>(null) }

    val filteredSkills = remember(skills, searchQuery) {
        filterSkills(skills, searchQuery)
    }

    // Delete confirmation dialog
    if (skillToDelete != null) {
        DeleteSkillConfirmationDialog(
            skill = skillToDelete!!,
            onConfirm = {
                val id = skillToDelete!!.id
                skillToDelete = null
                onDelete(id)
            },
            onDismiss = {
                skillToDelete = null
            }
        )
    }

    if (isOpen) {
        // Modal overlay backdrop
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(Color(0x8A000000))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { onClose() },
            contentAlignment = Alignment.CenterEnd
        ) {
            // Slide-in drawer container
            Column(
                modifier = Modifier
                    .fillMaxHeight()
                    .widthIn(max = 340.dp)
                    .fillMaxWidth(0.85f)
                    .background(ColorBgSurface)
                    .border(1.dp, ColorBorderMedium, RoundedCornerShape(topStart = 16.dp, bottomStart = 16.dp))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { /* absorb clicks */ }
                    .padding(16.dp)
            ) {
                // 1. Header (Title + Close Button)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Skills",
                        color = ColorTextPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = (-0.01).sp
                    )
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(ColorBgSurfaceSubtle)
                            .clickable { onClose() }
                            .semantics { contentDescription = "Close skills" },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "✕",
                            color = ColorTextSecondary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                // 2. Search Field
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(38.dp)
                        .clip(RadiusSm)
                        .background(ColorBgInput)
                        .border(1.dp, ColorBorderSubtle, RadiusSm)
                        .padding(horizontal = 12.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    if (searchQuery.isEmpty()) {
                        Text(
                            text = "Search skills...",
                            color = ColorTextDisabled,
                            fontSize = 12.5.sp
                        )
                    }
                    BasicTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics { contentDescription = "Search skills" },
                        textStyle = TextStyle(
                            color = ColorTextPrimary,
                            fontSize = 12.5.sp
                        ),
                        singleLine = true,
                        cursorBrush = SolidColor(ColorAccentPrimary)
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                // 3. Dynamic Skills List or Truthful Empty States
                if (skills.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .padding(vertical = 32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "No skills available",
                            color = ColorTextMuted,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Normal
                        )
                    }
                } else if (filteredSkills.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .padding(vertical = 32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "No matching skills",
                            color = ColorTextMuted,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Normal
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(filteredSkills, key = { it.id }) { skill ->
                            SkillLibraryCard(
                                skill = skill,
                                onRedo = { onRedo(skill.id) },
                                onDelete = { skillToDelete = skill }
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Reusable generic card for each skill in the library.
 */
@Composable
fun SkillLibraryCard(
    skill: SkillUiModel,
    onRedo: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RadiusSm)
            .background(ColorBgSurfaceElevated)
            .border(1.dp, ColorBorderSubtle, RadiusSm)
            .padding(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                Text(
                    text = skill.title,
                    color = ColorTextPrimary,
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.SemiBold
                )
                if (skill.description.isNotBlank()) {
                    Spacer(modifier = Modifier.height(3.dp))
                    Text(
                        text = skill.description,
                        color = ColorTextSecondary,
                        fontSize = 11.5.sp,
                        lineHeight = 16.sp
                    )
                }
                if (!skill.metadata.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = skill.metadata,
                        color = ColorTextMuted,
                        fontSize = 10.sp
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Action Buttons Row (Reteach + Delete)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
        ) {
            // Reteach Button
            Box(
                modifier = Modifier
                    .clip(RadiusSm)
                    .background(ColorAccentSubtle)
                    .border(1.dp, ColorAccentGlow, RadiusSm)
                    .clickable { onRedo() }
                    .padding(horizontal = 10.dp, vertical = 5.dp)
                    .semantics { contentDescription = "Reteach skill" },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Reteach",
                    color = ColorAccentHover,
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Medium
                )
            }

            // Delete Button
            Box(
                modifier = Modifier
                    .clip(RadiusSm)
                    .background(ColorStatusErrorBg)
                    .border(1.dp, ColorStatusErrorBorder, RadiusSm)
                    .clickable { onDelete() }
                    .padding(horizontal = 10.dp, vertical = 5.dp)
                    .semantics { contentDescription = "Delete skill" },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Delete",
                    color = ColorStatusError,
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

/**
 * Generic Confirmation Dialog shown prior to deleting a skill.
 * Copy is strictly generic and contains zero application- or workflow-specific information.
 */
@Composable
fun DeleteSkillConfirmationDialog(
    skill: SkillUiModel,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RadiusMd)
                .background(ColorBgSurfaceElevated)
                .border(1.dp, ColorBorderMedium, RadiusMd)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                text = "Delete this skill?",
                color = ColorTextPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Are you sure you want to delete \"${skill.title}\"? This action cannot be undone.",
                color = ColorTextSecondary,
                fontSize = 12.5.sp,
                lineHeight = 18.sp
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)
            ) {
                SecondaryButton(
                    label = "Cancel",
                    onClick = onDismiss
                )
                Box(
                    modifier = Modifier
                        .defaultMinSize(minHeight = 38.dp)
                        .clip(RadiusSm)
                        .background(ColorStatusErrorBg)
                        .border(1.dp, ColorStatusErrorBorder, RadiusSm)
                        .clickable { onConfirm() }
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                        .semantics { contentDescription = "Confirm delete skill" },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Delete",
                        color = ColorStatusError,
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}
