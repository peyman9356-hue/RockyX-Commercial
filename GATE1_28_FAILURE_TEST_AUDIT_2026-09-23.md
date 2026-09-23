# Rocky X — Gate 1 — 28 Required Failure-Test Audit

Date: 2026-09-23
Contract: Gate 1 SIT Decision Loop Contract v3.1

Legend: PASS = behavior demonstrably enforced in current pure domain; GAP = implementation capability missing; BLOCKED = requires persistence/sync/server infrastructure not present in source under test.

| # | Failure test | Status | Evidence |
|---|---|---|---|
| 1 | duplicate client IDs | GAP | Evidence duplicate IDs are detected, but no domain-wide idempotency/event store for Attempts exists. |
| 2 | network order variation | PASS | Active Evidence is canonicalized by clientGeneratedId. |
| 3 | clock skew | PASS | Canonical ordering does not use timestamps; Evidence V1 has no createdAt field. |
| 4 | mismatched Dog | PASS | Session/Evidence and Attempt/Session dog mismatches are rejected. |
| 5 | mismatched Session | PASS | Evidence/Attempt session mismatches are rejected. |
| 6 | mismatched Attempt | PASS | Unknown/unreferenced Attempt is rejected. |
| 7 | session reference mutation | GAP | Data classes have no mutation API, but persistence-level immutable reference enforcement is absent. |
| 8 | offline Rule pinning | BLOCKED | Offline/session sync layer absent. |
| 9 | offline Policy pinning | BLOCKED | Offline/session sync layer absent. |
| 10 | nonexistent RuleVersion | BLOCKED | No RuleVersion registry/repository or sync validator. |
| 11 | nonexistent PolicyVersion | BLOCKED | No PolicyVersion registry/repository or sync validator. |
| 12 | fallback attempt rejection | GAP | No explicit fallback/remap rejection boundary exists in sync/domain. |
| 13 | concurrent Evidence correction | PASS | Second direct successor is rejected. |
| 14 | evidence history preservation | GAP | In-memory lineage exists; durable history store is absent. |
| 15 | invalid evidence exclusion | PASS | INVALID Evidence is excluded from active Evaluation. |
| 16 | canonical evidence ordering | PASS | clientGeneratedId ascending is enforced. |
| 17 | Evaluation immutability | GAP | Durable immutable Evaluation history is absent. |
| 18 | Decision immutability | GAP | Durable immutable Decision history is absent. |
| 19 | Decision missing basis Evaluation | GAP | No validator requiring complete/known basis Evaluation IDs. |
| 20 | basis ID ordering | PASS | Decision engine sorts basisEvaluationIds. |
| 21 | all 12 policy matrix combinations | PASS | All 12 cells executed and matched Contract v3.1. |
| 22 | determinism across retries | PASS | Same canonical input produced equal Evaluation/Decision. |
| 23 | determinism across different arrival order | PASS | Reordered input produced equal Evaluation. |
| 24 | determinism across timestamps | PASS | Ordering/result does not use Evidence timestamps. |
| 25 | determinism with same canonical active set | PASS | Same canonical active set produced equal Evaluation. |
| 26 | AI cannot bypass domain result | PASS | No AI authority path exists in pure domain decision flow. |
| 27 | pinned versions survive sync | BLOCKED | Sync layer not present. |
| 28 | rejected/invalid session behavior | PASS | INVALID/REJECTED sessions are rejected from evaluation. |

## Result
**PASS 15 / GAP 9 / BLOCKED 4 / 28 TOTAL**

## Gate implication
The failure suite is **not green**. Persistence, sync/server validation, idempotency, immutable history, and basis traceability remain before Gate 2 can be accepted.
