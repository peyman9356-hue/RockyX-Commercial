# Rocky X — Gate 1 Closure Audit R2

Date: 2026-10-06
Contract: Gate 1 SIT Decision Loop Contract v3.1
Branch: gate1-sync-boundary-audit-2026-10-06
Audited HEAD: 1a49d7dfe6231b74562af91360147a0407cc29e8

## CI evidence

Run #97
- Run ID: 37420963269
- Commit: 1a49d7dfe6231b74562af91360147a0407cc29e8
- Result: SUCCESS
- JVM tests: SUCCESS
- Android debug build: SUCCESS
- Backend distribution build: SUCCESS
- APK output verification: SUCCESS
- Artifact: rocky-x-gate1-v8-source-overlay-debug-apk
- Artifact ID: 11393341470
- Artifact digest: sha256:b64c70bbed140230768090275ecb09e40e79cdc0c502e6ca1a219292d9096906

main remains unchanged at:
54ef24b63d11ceab3869bb733ea6d98f66672d6a

## R2 audit rule

Only concrete findings are recorded. No speculative defects are promoted to GAP.

## Closure result

**Implementation GAP count in the audited local Gate 1 domain/persistence/sync path: 0 ❌**

All six R1 ❌ findings were repaired and regression-tested.

Formal Gate 1 closure is still **HOLD** because three explicit boundary items remain ⚠️ and are not yet fully verified/settled.

## Six R1 findings — resolution

### G1 — Durable Session version pin binding — ✅ FIXED / VERIFIED

Session scope now durably stores RuleVersionId and PolicyVersionId.

Sync processing compares non-SESSION events against the pins persisted with the existing Session, rather than trusting a caller-supplied TrainingSession alone.

Regression:
- persisted Session pins cannot be remapped by a sync caller.

### G2 — Direct Evidence dog ownership — ✅ FIXED / VERIFIED

The direct persistence gateway now requires Evidence dog identity to match both the persisted Session dog and Attempt dog.

Regression:
- foreign-dog Evidence is rejected and is not persisted.

### G3 — Evaluation version pin enforcement — ✅ FIXED / VERIFIED

Direct Evaluation persistence now requires RuleVersionId and PolicyVersionId to match the exact durable Session pins.

Evaluation scope also persists those exact pins.

Regression:
- Evaluation with mismatched version pins is rejected.

### G4 — Decision basis/policy consistency — ✅ FIXED / VERIFIED

Direct Decision persistence now requires:
- non-empty basisEvaluationIds;
- lexicographically sorted basis IDs;
- one common Session scope;
- Decision PolicyVersionId equal to the persisted Session PolicyVersionId;
- every basis Evaluation to use the same PolicyVersionId.

Regression:
- empty Decision basis rejected;
- policy mismatch rejected.

### G5 — Canonical Active Evidence Set enforcement — ✅ FIXED / VERIFIED

Evidence scope now persists Evidence status and supersedesRecordId.

The durable boundary computes the canonical active Evidence IDs from:
- VALID Evidence only;
- valid supersede lineage;
- session scope;
- deterministic lexical ordering.

Evaluation persistence and Sync Evaluation ingestion require the supplied Evidence IDs to equal that canonical active set.

Regression:
- evaluation omitting an active Evidence record is rejected;
- Sync reference validation precedes canonical-set validation so missing/cross-session references retain their existing precise rejection semantics.

### G6 — Invalid superseder suppressing valid Evidence — ✅ FIXED / VERIFIED

The aggregator now filters INVALID Evidence before constructing the superseded-ID set.

Therefore an INVALID correction cannot deactivate a VALID parent.

Regression:
- valid parent remains active when superseded by an INVALID child.

## Additional R2 verification

The current implementation also verifies:
- transactional Sync atomicity;
- duplicate client event idempotency;
- full Session → Attempt → Evidence → Evaluation → Decision reference integrity;
- cross-session reference rejection;
- cross-dog reference rejection in Sync;
- immutable record conflict rejection;
- same-batch Evidence supersede topological ordering;
- exact version pin matching;
- numeric version ordering;
- Policy v1 12-cell decision mapping;
- deprecated legacy Decision facade delegates to TrainingPolicyEngine;
- INVALID Evidence exclusion from active Evidence;
- canonical ordering independent of arrival/timestamps.

## Remaining ⚠️ boundary items

### W1 — Session INVALID transition is not durably implemented — ⚠️

The contract states that missing/invalid version pins cause the Session to become INVALID and Sync to be rejected.

Current implementation rejects the operation through validation and exception paths, but does not durably transition an existing Session record to SessionStatus.INVALID.

This is a contract-state-transition item, not a speculative defect.

### W2 — Legacy record_scopes migration policy is unresolved — ⚠️

Database upgrade to v4 creates the new scope/version/evidence metadata columns, but pre-v4 immutable records are not backfilled.

Current behavior intentionally fails closed when required scope/version metadata is unavailable.

A release policy is still required for legacy databases:
- explicit migration/backfill strategy, or
- documented invalidation/rejection strategy.

### W3 — Legacy client-event identity metadata is not reconstructible — ⚠️

Pre-v2 client_events are backfilled using content_hash as identity_hash.

The new sync identity hash includes metadata that cannot be reconstructed from those historical rows.

A compatibility policy is still required for old persisted events.

### Production transport boundary

The tracked Gate 1 code now has a verified transactional local Sync ingestion boundary. The current repository tree does not contain a separately verified production network/server implementation that executes the complete Gate 1 Sync contract end-to-end.

This remains a release/verification boundary rather than a newly discovered local persistence defect.

## Final R2 verdict

**Gate 1 local implementation: ✅ no remaining ❌ execution gaps in the audited scope.**

**Gate 1 formal closure: HOLD** until W1–W3 are explicitly resolved or formally excluded from Gate 1 closure criteria, and the production Sync transport boundary is given a verified scope/acceptance decision.

No merge to main was performed.
