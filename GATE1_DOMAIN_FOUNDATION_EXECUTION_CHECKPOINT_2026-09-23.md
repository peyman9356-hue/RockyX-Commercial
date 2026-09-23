# Rocky X — Gate 1 Domain Foundation Execution Checkpoint

Date: 2026-09-23
Branch: gate1-domain-foundation-minfix-2026-09-23-1
Current branch HEAD before this checkpoint: dd29717daa9d626f7c099cd8d5e02e870e996425

## Actual execution

Source archive:
RockyX_Gate1_Integrated_BuildAudit.zip

ZIP extraction:
PASS — 222 files extracted.

Static integration gate:
PASS — tools/integration-check.sh, 9/9 checks.

Pure Kotlin domain compilation:
PASS — kotlinc compiled app/src/main/java/com/rockyx/app/domain/training/*.kt.

Contract smoke suite:
PASS — 16 assertions:
- all 12 Policy v1 matrix cells
- canonical clientGeneratedId ordering
- concurrent supersede rejection
- unsupported PolicyVersion rejection
- RuleVersion pin mismatch rejection

## Corrections made in the working source copy

- Removed legacy EvaluationResult.NOT_ASSESSED.
- TrainingAttempt now requires dogId, createdAt and clientGeneratedId.
- TrainingSession now contains createdAt and SessionStatus.
- Session validation rejects INVALID/REJECTED sessions.
- Attempt dog identity is checked when supplied.
- RuleVersion pin matching no longer permits a fallback to bare version.
- Aggregator validates the exact pinned RuleVersion and PolicyVersion.
- Empty active evidence evaluates as UNKNOWN + INSUFFICIENT.
- TrainingPolicyEngine no longer references NOT_ASSESSED.

## Current blockers

- The corrected source has not been merged into the GitHub binary ZIP yet because the GitHub connector cannot write binary ZIP content directly from the local execution environment.
- Full Gradle/Android build is NOT VERIFIED: the archive has Gradle project files but no gradlew wrapper, and this environment has no Gradle installation or Android SDK configured.
- The complete executable 42-invariant suite is NOT VERIFIED.
- The complete executable 28-failure suite is NOT VERIFIED.

## Gate decision

HOLD — do not merge to main.

The working corrected archive is a real artifact and the pure domain smoke checks pass. The next required engineering evidence is a real Android/Gradle build plus complete invariant/failure regression.
