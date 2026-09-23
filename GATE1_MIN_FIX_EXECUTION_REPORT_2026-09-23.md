# Rocky X — Gate 1 Domain Foundation Minimum-Fix Execution Report

Date: 2026-09-23

## Scope
Applied minimum domain-only fixes to the local corrected Gate 1 source. No Room, Sync, UI, or main branch changes were made.

## Implemented
1. Exact RuleVersion/PolicyVersion registry with no fallback/remap/latest behavior.
2. Client-event identity registry: duplicate same-content event is idempotent; same client ID with different content is rejected.
3. Append-only Evidence history with one direct successor and concurrent supersede rejection.
4. Append-only Evaluation history with duplicate-ID rejection.
5. Append-only Decision history with canonical basis IDs and duplicate-ID rejection.
6. Decision basis validation against known Evaluation IDs and policy-version consistency.
7. Assessment read-model/projection implementation.
8. HistoricalVersionGuard requiring the exact historical RuleVersion/PolicyVersion to remain valid.

## Verification
- Pure Kotlin compilation: PASS.
- Minimum-fix executable harness: **16/16 PASS**.
- ZIP integrity test: PASS.
- Full Gradle/Android build: NOT VERIFIED; execution environment still lacks Gradle wrapper/installation and Android SDK.
- Durable database/server enforcement: NOT CLAIMED; these adapters remain the next infrastructure layer.

## Important boundary
The implementation above is currently in the extracted Gate 1 source archive/worktree. It has **not** been integrated into the application's tracked Kotlin source tree on GitHub, because the current branch contains the uploaded archive rather than those source files. Therefore no claim of GitHub source integration is made.

## Gate status
**HOLD — DO NOT MERGE.**

Next gate work: integrate this verified domain source into the actual repository source tree, then run the full repository/Gradle build and re-run the 42/28 suites against the integrated tree before any merge.
