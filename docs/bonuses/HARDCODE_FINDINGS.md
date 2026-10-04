# HARDCODE FINDINGS REPORT
**Samsung PRISM Theme 3 — Static Hardcode Audit**

---

| File | Line | Finding | Classification | Severity |
|------|------|---------|----------------|----------|
| `BonusFilteringConfig.kt` | L8-L10 | Default thresholds (`highConfidenceThreshold = 0.85`, `accidentalTapThresholdMs = 400L`) | ALLOWED ENGINEERING CONSTANT | NONE |
| `CrossAppConfiguration.kt` | L8-L13 | Default weights (`transferableThreshold = 0.70`, weights summing to 1.0) | ALLOWED ENGINEERING CONSTANT | NONE |
| `CrossAppSemanticMatcher.kt` | L15-L22 | Generic dictionary map (`search`, `find`, `enter`, `submit`, `item`, `select`) | ALLOWED ENGINEERING CONSTANT | NONE |
| `CrossAppCompatibilityAnalyzer.kt` | L11-L15 | Sensitive screen keyword set (`payment`, `card number`, `cvv`, `pin`, `password`, `otp`) | ALLOWED ENGINEERING CONSTANT | NONE |
| `ClarificationValidator.kt` | L16-L25 | Cancel keywords & Credential regex patterns (`password`, `pin`, `otp`, credit card regex) | ALLOWED ENGINEERING CONSTANT | NONE |

---

## AUDIT CONCLUSION:
Zero forbidden scenario-specific hardcode findings (app names, package names, food items, specific coordinates, or judge workflows) exist in the production bonus packages. All constants are pure engineering thresholds and generic language/safety models.
