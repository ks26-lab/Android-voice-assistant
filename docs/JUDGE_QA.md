# Judge questions

**Is this macro recording?** The output is inspectable semantic Workflow IR with selectors, slots, preconditions, transitions and safety metadata. Runtime resolves fresh live controls and verifies each effect; recorded coordinates are never replayed.

**Why not coordinates?** Coordinates do not establish identity across layout/device changes. Semantic labels, descriptions, naturally learned resource IDs and hierarchy evidence can; conflicting or ambiguous evidence still stops execution.

**Where is the AI/semantic intelligence?** In normalization, action/target extraction, intent/slot interpretation and workflow synthesis. This implementation uses deterministic rules, not an LLM. It does not claim open-ended language understanding. One-shot variable selection is explicitly confirmed by the teacher.

**What happens when the UI changes?** Moderate hierarchy differences can retain enough evidence. Changed required IDs/text, inaccessible content, or ambiguity cause a specific stop/clarification; recovery does not guess coordinates.

**What if confidence is low?** Only an exact semantic anchor and sufficiently unique score can execute. A close second candidate asks for clarification; no candidate does not become “best available.”

**What prevents accidental payment?** A deterministic gate below interpretation examines restrictions and live credential/payment evidence before the sole action dispatch. Handoff is sticky. Reset starts a newly validated execution and does not exempt sensitive screens. Finite keywords/accessibility metadata are a limitation, not a universal safety certification.

**Are workflows hard-coded?** Production has no seeded judge workflows, target-app resource IDs, app package branches, deeplinks or web fallback. Test fixtures are separate. The command grammar is predefined and limited; task layouts are learned.

**Can it learn another app?** The Accessibility executor is package-generic. A second accessible app can supply another learned Workflow. Actual application/language/task compatibility must be demonstrated; a generic engine alone does not establish success in every app.

**What happens when it does not know a command?** The command/matching/request path rejects unknown, ambiguous or missing-slot input. No unrelated workflow should be selected. Unsupported custom intents require further interpretation support; they are not silently executed.

**Why feasible on Android?** Accessibility provides live semantic node trees and supported click/text/scroll actions. The user explicitly enables the service. Android acceptance is followed by observation; remote business outcomes are not inferred merely from acceptance.

**What is still MVP scope?** In-memory storage, current-version resolution, limited English grammar, manual popup/back recovery, single-package teaching, OEM/recognizer dependence, and no generalized non-accessible canvas handling.
