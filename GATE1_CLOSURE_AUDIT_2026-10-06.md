# Rocky X — Gate 1 Closure Audit

Date: 2026-10-06
Contract: Gate 1 SIT Decision Loop Contract v3.1
Audited branch: gate1-sync-boundary-audit-2026-10-06
Audited HEAD: 1600dce4f10393c89b981a9288d54a7b6eb29cb5
CI: Run #90 / 37406382936 — SUCCESS

## Audit rule

A clause is marked PASS only when the current tracked source and executable CI evidence support it. A clause is marked GAP only when a concrete execution or architecture mismatch is demonstrated. No speculative gaps are recorded.

## Closure status

**HOLD — Gate 1 is not closed.**

The Sync boundary is substantially hardened and CI is green, but the full Contract v3.1 closure audit found concrete gaps at the durable authority boundaries.

## PASS / VERIFIED areas

- Exact durable RuleVersion/PolicyVersion lookup and numeric version ordering are implemented.
- Offline pin resolution selects the newest valid local versions once; later versions do not alter an existing in-memory session pin.
- Sync validates envelope/event pins against the session supplied to the sync boundary.
- Sync processing order is deterministic: Session → Attempt → Evidence → Evaluation → Decision, with same-batch Evidence supersede topological ordering.
- Sync batches are transactional; failed batches do not leave partial durable records.
- Client event identity includes sync metadata and is idempotent.
- Duplicate record IDs are rejected without overwriting existing immutable records.
- Referential integrity is enforced across Session → Attempt → Evidence → Evaluation → Decision.
- Cross-session reference contamination is rejected.
- Cross-dog reference contamination is rejected in the Sync path.
- Evidence supersede is append-only; concurrent direct successors are rejected.
- Canonical Evidence ordering uses clientGeneratedId rather than timestamps or network arrival order.
- INVALID Evidence is excluded by the evaluation aggregator.
- Policy v1 implements the 12 required sufficiency/result combinations.
- The old TrainingDecisionEngine is only a compatibility facade and delegates decision authority to TrainingPolicyEngine.
- Run #90 completed successfully: static integration checks, JVM tests, Android debug build, backend distribution build, and APK output verification.
- main remains untouched; current main HEAD is 54ef24b63d11ceab3869bb733ea6d98f66672d6a.

## Real gaps

### G1 — Durable Session pin binding is incomplete — ❌

The persisted `record_scopes` table stores record/session/dog scope but does not persist RuleVersionId or PolicyVersionId. `applySync` validates event pins against the caller-supplied `TrainingSession`, not against immutable pins stored with the already-persisted Session record.

Impact: an existing Session can be presented later through a differently pinned `TrainingSession` and receive new records under a different valid version. That violates the no-remap/no-fallback historical pin contract.

Primary source:
`TrainingDurableStore.kt`, `TrainingPersistenceGateway.kt`.

### G2 — Direct Evidence persistence does not enforce dog ownership — ❌

`TrainingPersistenceGateway.appendEvidence` verifies Session/Attempt existence and session scope, but it does not require `evidence.dogId` to equal the persisted Session/Attempt dog scope before writing the new Evidence scope.

Impact: the Sync path rejects this class of mismatch, but the direct durable gateway still permits a foreign-dog Evidence record to be persisted.

Primary source:
`TrainingPersistenceGateway.kt`.

### G3 — Evaluation version pins are not enforced at the durable authority boundary — ❌

`appendEvaluation` checks referenced Attempt/Evidence existence and session ownership but does not require `evaluation.ruleVersionId` and `evaluation.policyVersionId` to equal the persisted Session pins.

The aggregator validates pins before producing an Evaluation, but the persistence gateway can accept a separately constructed Evaluation with different version IDs.

Impact: historical Evaluation version identity can be bypassed.

Primary source:
`TrainingPersistenceGateway.kt`, `SitSessionAggregator.kt`.

### G4 — Decision policy pin/basis consistency is not fully enforced at persistence — ❌

`appendDecision` checks sorted basis IDs, existence, and common session scope, but it does not enforce:

- non-empty basis IDs at the direct gateway boundary;
- Decision policyVersionId equals the Session/basis Evaluation policy version;
- every basis Evaluation belongs to the same pinned policy version.

`DecisionValidation` contains some of these checks, but the gateway does not invoke it as the persistence authority.

Impact: a Decision can be durably recorded under a policy identity that is inconsistent with its Evaluation basis.

Primary source:
`TrainingPersistenceGateway.kt`, `TrainingContractFoundation.kt`.

### G5 — Evaluation persistence can bypass the Canonical Active Evidence Set — ❌

The aggregator derives Evaluation from active valid Evidence, but `appendEvaluation` accepts an Evaluation plus arbitrary Evidence IDs and only checks existence/session scope.

It does not enforce at the durable boundary that the Evaluation's Evidence IDs equal the canonical active set, nor that every referenced Evidence is currently VALID and not superseded.

Impact: a caller can persist an Evaluation that is not derivable from the canonical active Evidence Set even though the normal aggregator path is correct.

Primary source:
`TrainingPersistenceGateway.kt`, `SitSessionAggregator.kt`.

### G6 — Invalid superseding Evidence can suppress valid Evidence — ❌

`SitSessionAggregator.activeEvidence` builds the superseded-ID set from all supplied Evidence before filtering INVALID Evidence.

Therefore an INVALID correction child can suppress its VALID parent, producing an active set that does not match the Contract's invalid-Evidence exclusion semantics.

Impact: Evaluation result can change because of an INVALID correction event.

Primary source:
`SitSessionAggregator.kt`.

### G7 — Historical Session INVALID state transition is not implemented — ⚠️

The contract calls for an invalid/missing version pin to make the Session INVALID and reject Sync. Current code rejects the operation through validation/exception paths, but does not persist a Session status transition to INVALID.

This needs an explicit authority/state-transition decision before closure.

Primary source:
`TrainingPersistenceGateway.kt`, `TrainingSyncValidator.kt`.

### G8 — Legacy `record_scopes` migration policy is unresolved — ⚠️

Database version 3 creates `record_scopes`, but existing immutable records from pre-v3 databases have no backfilled scope. Current reference checks fail closed when scope is missing.

That behavior is defensible, but the release migration policy is not yet specified or verified.

Primary source:
`TrainingDurableStore.kt` onUpgrade(v3).

### G9 — Legacy `client_events.identity_hash` migration cannot reconstruct historical metadata identity — ⚠️

Database version 2 backfills `identity_hash` from `content_hash`. For pre-v2 events, the system cannot retroactively prove the full sync metadata identity used by the new identity hash scheme.

This requires an explicit compatibility/release policy.

Primary source:
`TrainingDurableStore.kt` onUpgrade(v2).

### G10 — Production network/server Sync is not verified by the current tracked Gate 1 implementation — ⚠️

The tracked branch contains the transactional local Sync boundary in `TrainingPersistenceGateway`/SQLite, but no current tracked server/network implementation tied to the Gate 1 Sync contract. Run #90's backend build comes from the archived Rev15 project assembled by CI, not from a tracked Gate 1 server Sync implementation.

Therefore full production transport/remote persistence cannot be marked VERIFIED.

## CI evidence

Run #90:
- Run ID: 37406382936
- Commit: 1600dce4f10393c89b981a9288d54a7b6eb29cb5
- Result: SUCCESS
- JVM tests: SUCCESS
- Android debug build: SUCCESS
- Backend distribution build: SUCCESS
- APK output verification: SUCCESS
- Artifact: `rocky-x-gate1-v8-source-overlay-debug-apk`
- Artifact SHA-256: 89265bb24af282b40845b5409bd96844263638001b0b32dc3957c28f3b52e962

## Gate decision

**GATE 1: HOLD**

There are real ❌ implementation gaps. Gate 1 must not be closed and Gate 2 must not start as an implementation phase until the ❌ items are repaired, regression-tested, and re-audited.

The ⚠️ migration/transport items require explicit closure criteria even after the code gaps are fixed.
