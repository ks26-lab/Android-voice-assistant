# DESIGN DOCUMENT: IRRELEVANT-ACTION FILTERING
**Samsung PRISM Theme 3 — Bonus Feature 1 (+3 Points)**

---

## 1. RELEVANCE MODEL ARCHITECTURE

The Bonus Irrelevant-Action Filtering System introduces a deterministic, multi-factor evidence classification model in `com.chockXlate.teachablevoice.teach.bonus.irrelevant`.

```text
               [ DemonstrationTrace (v1.0) ]
                            │
                            ▼
               [ ActionSequenceGrouper ]
           (Groups events into interaction sequences)
                            │
                            ▼
            [ IrrelevantActionClassifier ]
      (Evaluates semantic, state, and temporal evidence)
                            │
                            ▼
                 [ Conservative Policy ]
   ┌────────────────────────┼────────────────────────┐
   │ High Confidence        │ Low/Med Confidence     │
   │ Irrelevant             │ or Uncertain           │
   ▼                        ▼                        ▼
[ FILTER (REMOVE) ]   [ RETAIN (PRESERVE) ]   [ RETAIN (PRESERVE) ]
                            │
                            ▼
          [ BonusFilteredDemonstration (v1.0) ]
```

---

## 2. DETERMINISTIC EVIDENCE METRICS

Each captured action event is evaluated across five semantic evidence axes:

1. **Semantic Target Evidence (`semanticTargetEvidence`)**: Evaluates role, resource ID, text, content description, and clickable/editable status. Anonymous containers without semantic evidence receive low score.
2. **State Change Evidence (`stateChangeEvidence`)**: Measures whether the action produced a verifiable UI hierarchy or screen state transition (`beforeState` vs `afterState`).
3. **Task Progress Evidence (`taskProgressEvidence`)**: Assesses whether the action is followed by productive task flow (e.g., text entry after field focus, or item selection after search submit).
4. **Temporal Progress Evidence (`temporalEvidence`)**: Checks relative timing and interaction pace.
5. **Sequence & Repetition Evidence (`repetitionEvidence` & `navigationNoiseEvidence`)**: Detects repeated accidental taps on identical targets within rapid time windows (<400ms) without state change, transient focus shifts, or accidental back presses followed by immediate recovery.

---

## 3. CONSERVATIVE FILTERING POLICY

To prevent false positive deletion (which would destroy valid workflow steps):
- **HIGH CONFIDENCE IRRELEVANT** ($\ge 0.85$ confidence) $\longrightarrow$ `FILTER`
- **MEDIUM / LOW CONFIDENCE** ($< 0.85$ confidence) $\longrightarrow$ `RETAIN`
- **UNCERTAIN** $\longrightarrow$ `RETAIN`

$$\text{Action Decision} = \begin{cases} \text{FILTER} & \text{if } \text{Decision} = \text{IRRELEVANT} \text{ and } \text{Confidence} \ge 0.85 \\ \text{RETAIN} & \text{otherwise} \end{cases}$$

---

## 4. ACTION SEQUENCE GROUPING

`ActionSequenceGrouper` organizes individual events into coherent logical sequences:
- **Search Flow**: `CLICK(Search Field) -> INPUT_TEXT("item") -> CLICK(Search Button)`.
- **Selection Flow**: `CLICK(Item Card) -> STATE_CHANGE`.
- **Accidental Noise**: `CLICK(Blank Container) -> CLICK(Blank Container)` (No state change) -> Grouped as accidental repetition.

---

## 5. NEW PACKAGE DATA CONTRACTS

All data structures are defined in `com.chockXlate.teachablevoice.teach.bonus.irrelevant`:
- `RelevanceDecision`: Enum (`RELEVANT`, `IRRELEVANT`, `UNCERTAIN`).
- `RelevanceEvidence`: Data class containing evidence scores, confidence, and human-readable reason.
- `BonusFilteredDemonstration`: Data contract with `schemaVersion = "1.0"`, session metadata, counts, retained/removed event lists, decisions, and filtering statistics.
