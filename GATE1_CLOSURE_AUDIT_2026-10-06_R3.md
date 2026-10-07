# Rocky X — Gate 1 Closure Audit R3

Date: 2026-10-06
Contract: Gate 1 SIT Decision Loop Contract v3.1
Branch: gate1-sync-boundary-audit-2026-10-06
Audited HEAD: 86fdd06d25c892cc4e942d2fb4a4b413433a6826

## CI evidence

Run #110
- Run ID: 37422902762
- Commit: 86fdd06d25c892cc4e942d2fb4a4b413433a6826
- Result: SUCCESS
- Static integration checks: SUCCESS
- JVM tests: SUCCESS
- Android debug build: SUCCESS
- Backend distribution build: SUCCESS
- APK output verification: SUCCESS
- Artifact: rocky-x-gate1-v8-source-overlay-debug-apk
- Artifact ID: 11393862728
- Artifact digest: sha256:d6f8a5dd93308a7af6ea0e95455e6f210b07c2a04bbf67bdae2ccbea8aeb2e9f

main remains unchanged at:
54ef24b63d11ceab3869bb733ea6d98f66672d6a

## R3 audit rule

Only concrete findings are recorded. No speculative defects are promoted to GAP.

## W1 — Durable Session INVALID transition — ✅ FIXED / VERIFIED

TrainingDurableStore now persists Session status in record_scopes.

When pinned Session validation fails during sync, TrainingPersistenceGateway durably marks the existing Session INVALID before rejecting the sync operation.

Subsequent direct Attempt/Evidence/Evaluation/Decision writes require an ACTIVE persisted Session and are rejected for INVALID or unverifiable legacy Sessions.

Regression coverage includes:
- invalid version pin durably invalidates an existing Session;
- subsequent Attempt persistence is rejected;
- subsequent sync against that Session is rejected.

CI Run #110 passed all JVM tests and both Android compilation/build phases.

## W2 — Legacy record_scopes migration policy — ✅ RESOLVED / VERIFIED

Database version is now 5.

Migration 4 -> 5 adds:
- session_status to record_scopes;
- legacy_identity to client_events.

Legacy Session scopes receive null session_status and legacy version/scope metadata remains absent rather than being guessed or synthesized.

The active write/sync boundaries fail closed when required durable pins or Session state are unavailable.

Regression coverage manually creates a pre-v4/v3 database, performs the current migration, and verifies:
- legacy Session version pins are rejected;
- legacy Session writes are rejected;
- missing historical metadata is not silently backfilled.

CI Run #110 passed the migration regression suite.

## W3 — Legacy client-event identity — ✅ RESOLVED / VERIFIED

Database version 5 adds durable legacy_identity.

Migrated historical client events are explicitly marked as unverifiable instead of treating content_hash as a reconstructed canonical event identity.

Any later duplicate/append/sync attempt using such a legacy clientGeneratedId is rejected with LEGACY_CLIENT_ID_IDENTITY_UNVERIFIED.

New events persist legacy_identity=0 with the full identity hash.

Regression coverage verifies rejection of a migrated legacy client event.

CI Run #110 passed the legacy identity regression suite.

## R1/R2 findings

All six original R1 ❌ findings remain repaired and regression-tested:
- durable Session Rule/Policy pin binding;
- direct Evidence dog ownership;
- Evaluation version pin enforcement;
- Decision basis/policy consistency;
- canonical active Evidence Set enforcement;
- INVALID superseder cannot suppress VALID Evidence.

## Remaining boundary

### Production transport / server end-to-end

The repository evidence verifies the deterministic local transactional Sync ingestion boundary and its persistence/security rules. A separately tracked production network/server implementation executing the complete Gate 1 Sync contract end-to-end is not established by this audit.

This is classified as a scope/release boundary, not as a newly discovered local Gate 1 execution defect.

## R3 verdict

**Gate 1 local domain + persistence + local Sync boundary: ✅ CLOSED FOR THE AUDITED SCOPE.**

**Production transport end-to-end: ⚠️ UNVERIFIED / OUTSIDE THIS LOCAL CLOSURE EVIDENCE.**

No merge to main was performed.
