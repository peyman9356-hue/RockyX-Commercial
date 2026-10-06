# Rocky X — Gate 2 Production Transport Audit

Date: 2026-10-06
Branch: gate1-sync-boundary-audit-2026-10-06
Audited HEAD: 19c8a16df3f32268d8f06c840be2b043434c498d

## Scope

This audit covers the production transport boundary built on top of the closed Gate 1 local domain/persistence/sync contract.

Gate 1 remains closed and was not modified.

## Production transport core

### Server HTTP boundary — ✅ VERIFIED IN CI

A dedicated endpoint is added through the production-source overlay:

- POST /api/v1/training/sync
- authenticated request
- required scope: sync:write
- Idempotency-Key required and bounded
- TrainingSyncEnvelope validated before durable mutation
- structured rejection through the existing ApiError boundary

The endpoint is wired to the PostgreSQL Training Sync repository in the production Application wiring.

### PostgreSQL durable transport — ✅ VERIFIED

The transport persists:

- exact Session RuleVersionId and PolicyVersionId;
- Session status;
- immutable clientGeneratedId identity hash;
- Session → Attempt → Evidence → Evaluation → Decision scope;
- Evidence status and supersede lineage;
- deterministic canonical active Evidence set.

The repository uses a transaction for the complete sync batch.

Invalid Session pin conflicts durably mark an existing Session INVALID and reject further writes.

Duplicate client events with the same identity are idempotent; reuse with a different identity is rejected.

### Deterministic transport policy — ✅ VERIFIED

The transport validates and orders:

- supported record types;
- event/session/dog identity;
- exact RuleVersion/PolicyVersion pins;
- mandatory references;
- lexicographic Decision basis ordering;
- Evidence supersede topology;
- canonical active Evidence membership;
- bounded request size.

Identity hashing mirrors the Gate 1 canonical field set.

### Android network client boundary — ✅ VERIFIED BY BUILD

The Android network client now exposes:

- applyTrainingSync(...);
- POST /api/v1/training/sync;
- Content-Type / Accept JSON headers;
- Idempotency-Key;
- bounded request/response sizes;
- parsing of accepted, duplicate, and rejection event IDs.

The method is part of the tracked production-source overlay and compiles in the full CI Android build.

## CI evidence

Run #125
- Run ID: 37426400444
- Commit: 19c8a16df3f32268d8f06c840be2b043434c498d
- Overall build job: SUCCESS
- Static integration checks: SUCCESS
- JVM tests: SUCCESS
- Android debug build: SUCCESS
- Backend distribution build: SUCCESS
- APK output verification: SUCCESS
- Production transport PostgreSQL integration job: SUCCESS
- Production transport audit job: SUCCESS

Production APK artifact:
- Name: rocky-x-gate1-v8-source-overlay-debug-apk
- Artifact ID: 11394268911
- SHA-256: sha256:1a317bdd8c02aaf527d15bbf8098fe1c780bddf9b0d3fafc33c56301b100955f

## Important boundary

### Live deployed network end-to-end — ⚠️ UNVERIFIED

CI verifies the server-side PostgreSQL transport and the Android network client compilation, but no deployed production endpoint was exercised with a real authenticated Android request against a live production database.

Therefore the following are not claimed as VERIFIED:

- real internet round-trip from Android client to deployed server;
- production authentication/token issuance and refresh during the training sync call;
- production reverse proxy/TLS/network path;
- production database migration execution;
- production deployment/rollback behavior.

This is a deployment/network verification boundary, not a newly discovered defect in the local transport implementation.

## Final verdict

**Production Transport Core (HTTP boundary + PostgreSQL persistence + Android client contract): ✅ VERIFIED IN CI**

**Live deployed Production E2E: ⚠️ UNVERIFIED**

Gate 1 remains closed and untouched.

main remains unchanged at:
54ef24b63d11ceab3869bb733ea6d98f66672d6a

No merge to main was performed.
