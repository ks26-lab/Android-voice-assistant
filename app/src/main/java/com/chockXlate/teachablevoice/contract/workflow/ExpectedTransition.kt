package com.chockXlate.teachablevoice.contract.workflow

import kotlinx.serialization.Serializable

@Serializable
enum class EvidenceOperator {
    ALL_REQUIRED,
    ANY_SUFFICIENT
}

@Serializable
enum class EvidenceType {
    EXPECTED_PACKAGE,
    ELEMENT_APPEARED,
    ELEMENT_DISAPPEARED,
    ELEMENT_EXISTS,
    ELEMENT_NOT_EXISTS,
    TEXT_EQUALS,
    TEXT_CHANGED,
    CHECKED_STATE,
    SELECTED_STATE,
    COUNTER_CHANGE,
    GENERIC_STATE_CHANGE
}

@Serializable
data class StateEvidenceRequirement(
    val schemaVersion: String = "1.0",
    val type: EvidenceType,
    val selector: SemanticSelector? = null,
    val expectedPackage: String? = null,
    val expectedValue: String? = null,
    val previousValue: String? = null,
    val expectedChecked: Boolean? = null,
    val expectedSelected: Boolean? = null,
    val counterDelta: Int? = null,
    val description: String? = null
)

@Serializable
data class ExpectedTransition(
    val schemaVersion: String = "1.0",
    val fromState: String? = null,
    val toState: String? = null,
    val transitionType: String = "STATE_CHANGE",
    val verification: String? = null,
    val expectedPackage: String? = null,
    val expectedElementAppeared: SemanticSelector? = null,
    val expectedElementDisappeared: SemanticSelector? = null,
    val timeoutMs: Long = 5000L,
    val expectedEvidence: List<StateEvidenceRequirement> = emptyList(),
    val evidenceOperator: EvidenceOperator = EvidenceOperator.ALL_REQUIRED
) {
    /**
     * Resolves the canonical list of evidence requirements.
     * If explicit [expectedEvidence] is provided, it is returned.
     * Otherwise, legacy fields (expectedElementAppeared, expectedElementDisappeared,
     * expectedPackage, transitionType) are mapped into equivalent StateEvidenceRequirements.
     */
    fun effectiveEvidence(): List<StateEvidenceRequirement> {
        if (expectedEvidence.isNotEmpty()) return expectedEvidence
        val list = mutableListOf<StateEvidenceRequirement>()
        expectedPackage?.let { pkg ->
            list.add(StateEvidenceRequirement(
                type = EvidenceType.EXPECTED_PACKAGE,
                expectedPackage = pkg,
                description = "Active package is '$pkg'"
            ))
        }
        expectedElementAppeared?.let { app ->
            list.add(StateEvidenceRequirement(
                type = EvidenceType.ELEMENT_APPEARED,
                selector = app,
                description = "Element appeared in UI"
            ))
        }
        expectedElementDisappeared?.let { dis ->
            list.add(StateEvidenceRequirement(
                type = EvidenceType.ELEMENT_DISAPPEARED,
                selector = dis,
                description = "Element disappeared from UI"
            ))
        }
        if (list.isEmpty() && transitionType in setOf("STATE_CHANGE", "UI_STATE_CHANGE", "SEMANTIC_ACTION")) {
            list.add(StateEvidenceRequirement(
                type = EvidenceType.GENERIC_STATE_CHANGE,
                description = "Semantic UI state changed after action"
            ))
        }
        return list
    }
}
