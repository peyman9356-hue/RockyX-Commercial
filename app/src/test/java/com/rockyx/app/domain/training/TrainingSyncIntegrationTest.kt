package com.rockyx.app.domain.training

import android.content.Context
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TrainingSyncIntegrationTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    @Test fun applySyncPersistsOutOfOrderBatchAtomicallyAndIdempotently() {
        TrainingPersistenceGateway(context).use { g ->
            g.register(RuleVersion("SYNC","1","VALID"), PolicyVersion("SYNC_POLICY","1","VALID"))
            val session = TrainingSession("sync-int-1","d1","sit","c1",emptyList(),ruleVersionId="SYNC:1",policyVersionId="SYNC_POLICY:1")
            val registry = TrainingVersionRegistry(
                listOf(RuleVersion("SYNC","1","VALID")),
                listOf(PolicyVersion("SYNC_POLICY","1","VALID"))
            )
            val envelope = TrainingSyncEnvelope(
                "sync-int-1","d1","SYNC:1","SYNC_POLICY:1",
                listOf(
                    TrainingSyncEvent("ev-2","ATTEMPT","a1","attempt", "SYNC:1","SYNC_POLICY:1",sessionId="sync-int-1",dogId="d1"),
                    TrainingSyncEvent("ev-3","EVIDENCE","e1","evidence", "SYNC:1","SYNC_POLICY:1",sessionId="sync-int-1",attemptId="a1"),
                    TrainingSyncEvent("ev-4","EVALUATION","eval-1","evaluation", "SYNC:1","SYNC_POLICY:1",sessionId="sync-int-1",attemptIds=listOf("a1"),evidenceIds=listOf("e1")),
                    TrainingSyncEvent("ev-5","DECISION","decision-1","decision", "SYNC:1","SYNC_POLICY:1",sessionId="sync-int-1",basisEvaluationIds=listOf("eval-1")),
                    TrainingSyncEvent("ev-1","SESSION","sync-int-1","session", "SYNC:1","SYNC_POLICY:1",sessionId="sync-int-1")
                )
            )
            val first = g.applySync(envelope, session, registry)
            assertTrue(first.accepted)
            assertEquals(listOf("ev-1","ev-2","ev-3","ev-4","ev-5"), first.acceptedEventIds)
            assertEquals("session", TrainingDurableStore(context).use { it.readImmutable("SESSION","sync-int-1") })
            assertEquals("attempt", TrainingDurableStore(context).use { it.readImmutable("ATTEMPT","a1") })
            assertEquals("evidence", TrainingDurableStore(context).use { it.readImmutable("EVIDENCE","e1") })
            assertEquals("evaluation", TrainingDurableStore(context).use { it.readImmutable("EVALUATION","eval-1") })
            assertEquals("decision", TrainingDurableStore(context).use { it.readImmutable("DECISION","decision-1") })

            val second = g.applySync(envelope, session, registry)
            assertTrue(second.accepted)
            assertTrue(second.acceptedEventIds.isEmpty())
            assertEquals(listOf("ev-1","ev-2","ev-3","ev-4","ev-5"), second.duplicateEventIds)
        }
    }

    @Test fun syncVersionMismatchIsRejectedBeforeAnyDurableMutation() {
        TrainingPersistenceGateway(context).use { g ->
            g.register(RuleVersion("SYNC","1","VALID"), PolicyVersion("SYNC_POLICY","1","VALID"))
            val session = TrainingSession("sync-int-2","d1","sit","c1",emptyList(),ruleVersionId="SYNC:1",policyVersionId="SYNC_POLICY:1")
            val registry = TrainingVersionRegistry(
                listOf(RuleVersion("SYNC","1","VALID"),RuleVersion("SYNC","2","VALID")),
                listOf(PolicyVersion("SYNC_POLICY","1","VALID"),PolicyVersion("SYNC_POLICY","2","VALID"))
            )
            val envelope = TrainingSyncEnvelope(
                "sync-int-2","d1","SYNC:1","SYNC_POLICY:1",
                listOf(
                    TrainingSyncEvent("ev-1","SESSION","sync-int-2","session", "SYNC:1","SYNC_POLICY:1",sessionId="sync-int-2"),
                    TrainingSyncEvent("ev-2","ATTEMPT","a1","attempt", "SYNC:2","SYNC_POLICY:1",sessionId="sync-int-2")
                )
            )
            assertThrows(IllegalArgumentException::class.java) { g.applySync(envelope, session, registry) }
            assertNull(TrainingDurableStore(context).use { it.readImmutable("SESSION","sync-int-2") })
            assertNull(TrainingDurableStore(context).use { it.readImmutable("ATTEMPT","a1") })
        }
    }

    @Test fun syncConflictingClientIdentityFailsWithoutOverwritingOriginalRecord() {
        TrainingPersistenceGateway(context).use { g ->
            g.register(RuleVersion("SYNC","1","VALID"), PolicyVersion("SYNC_POLICY","1","VALID"))
            val session = TrainingSession("sync-int-3","d1","sit","c1",emptyList(),ruleVersionId="SYNC:1",policyVersionId="SYNC_POLICY:1")
            val registry = TrainingVersionRegistry(
                listOf(RuleVersion("SYNC","1","VALID")),
                listOf(PolicyVersion("SYNC_POLICY","1","VALID"))
            )
            val base = TrainingSyncEnvelope(
                "sync-int-3","d1","SYNC:1","SYNC_POLICY:1",
                listOf(
                    TrainingSyncEvent("ev-1","SESSION","sync-int-3","session-a", "SYNC:1","SYNC_POLICY:1",sessionId="sync-int-3")
                )
            )
            assertTrue(g.applySync(base, session, registry).accepted)

            val conflict = base.copy(events=listOf(
                base.events.first().copy(canonicalPayload="session-b")
            ))
            val result = g.applySync(conflict, session, registry)
            assertFalse(result.accepted)
            assertTrue(result.rejections.any { it.startsWith("CLIENT_ID_REUSE_WITH_DIFFERENT_CONTENT:") })
            assertEquals("session-a", TrainingDurableStore(context).use { it.readImmutable("SESSION","sync-int-3") })
        }
    }
    @Test fun syncInvalidSessionPinIsRejectedDeterministically() {
        TrainingPersistenceGateway(context).use { g ->
            g.register(RuleVersion("SYNC","1","VALID"), PolicyVersion("SYNC_POLICY","1","VALID"))
            val session = TrainingSession("sync-int-invalid-pin","d1","sit","c1",emptyList(),ruleVersionId="SYNC:1",policyVersionId="SYNC_POLICY:1")
            val registry = TrainingVersionRegistry(emptyList(), emptyList())
            val envelope = TrainingSyncEnvelope("sync-int-invalid-pin","d1","SYNC:1","SYNC_POLICY:1",emptyList())
            val result = TrainingSyncValidator.validate(envelope, session, registry)
            assertFalse(result.accepted)
            assertTrue(result.rejections.contains(SyncRejectionCode.INVALID_SESSION))
        }
    }

    @Test fun syncDuplicateRecordIdentityIsRejectedAtomically() {
        TrainingPersistenceGateway(context).use { g ->
            g.register(RuleVersion("SYNC","1","VALID"), PolicyVersion("SYNC_POLICY","1","VALID"))
            val session = TrainingSession("sync-int-duplicate-record","d1","sit","c1",emptyList(),ruleVersionId="SYNC:1",policyVersionId="SYNC_POLICY:1")
            val registry = TrainingVersionRegistry(
                listOf(RuleVersion("SYNC","1","VALID")),
                listOf(PolicyVersion("SYNC_POLICY","1","VALID"))
            )
            val first = TrainingSyncEnvelope(
                "sync-int-duplicate-record","d1","SYNC:1","SYNC_POLICY:1",
                listOf(TrainingSyncEvent("ev-1","SESSION","sync-int-duplicate-record","session", "SYNC:1","SYNC_POLICY:1",sessionId="sync-int-duplicate-record"))
            )
            assertTrue(g.applySync(first, session, registry).accepted)

            val conflicting = first.copy(events=listOf(
                TrainingSyncEvent("ev-2","SESSION","sync-int-duplicate-record","different", "SYNC:1","SYNC_POLICY:1",sessionId="sync-int-duplicate-record")
            ))
            val result = g.applySync(conflicting, session, registry)
            assertFalse(result.accepted)
            assertTrue(result.rejections.any { it.contains("SYNC_RECORD_ID_CONFLICT") })
            assertEquals("session", TrainingDurableStore(context).use { it.readImmutable("SESSION","sync-int-duplicate-record") })
            assertNull(TrainingDurableStore(context).use { it.readImmutable("SESSION","never-created") })
        }
    }


    @Test fun syncRejectsEvidenceWithUnknownAttemptWithoutMutation() {
        TrainingPersistenceGateway(context).use { g ->
            g.register(RuleVersion("SYNC","1","VALID"), PolicyVersion("SYNC_POLICY","1","VALID"))
            val session = TrainingSession("sync-ref-1","d1","sit","c1",emptyList(),ruleVersionId="SYNC:1",policyVersionId="SYNC_POLICY:1")
            val registry = TrainingVersionRegistry(listOf(RuleVersion("SYNC","1","VALID")), listOf(PolicyVersion("SYNC_POLICY","1","VALID")))
            val envelope = TrainingSyncEnvelope("sync-ref-1","d1","SYNC:1","SYNC_POLICY:1",
                listOf(
                    TrainingSyncEvent("s1","SESSION","sync-ref-1","session","SYNC:1","SYNC_POLICY:1",sessionId="sync-ref-1"),
                    TrainingSyncEvent("e1","EVIDENCE","ev1","evidence","SYNC:1","SYNC_POLICY:1",sessionId="sync-ref-1",attemptId="missing-attempt")
                ))
            val result = g.applySync(envelope, session, registry)
            assertFalse(result.accepted)
            assertTrue(result.rejections.any { it.contains("SYNC_REFERENCE_NOT_FOUND:ATTEMPT:missing-attempt") })
            assertNull(TrainingDurableStore(context).use { it.readImmutable("SESSION","sync-ref-1") })
            assertNull(TrainingDurableStore(context).use { it.readImmutable("EVIDENCE","ev1") })
        }
    }

    @Test fun syncRejectsEvaluationWithUnknownReferenceWithoutMutation() {
        TrainingPersistenceGateway(context).use { g ->
            g.register(RuleVersion("SYNC","1","VALID"), PolicyVersion("SYNC_POLICY","1","VALID"))
            val session = TrainingSession("sync-ref-2","d1","sit","c1",emptyList(),ruleVersionId="SYNC:1",policyVersionId="SYNC_POLICY:1")
            val registry = TrainingVersionRegistry(listOf(RuleVersion("SYNC","1","VALID")), listOf(PolicyVersion("SYNC_POLICY","1","VALID")))
            val envelope = TrainingSyncEnvelope("sync-ref-2","d1","SYNC:1","SYNC_POLICY:1",
                listOf(
                    TrainingSyncEvent("s1","SESSION","sync-ref-2","session","SYNC:1","SYNC_POLICY:1",sessionId="sync-ref-2"),
                    TrainingSyncEvent("a1","ATTEMPT","attempt-1","attempt","SYNC:1","SYNC_POLICY:1",sessionId="sync-ref-2",dogId="d1"),
                    TrainingSyncEvent("v1","EVIDENCE","evidence-1","evidence","SYNC:1","SYNC_POLICY:1",sessionId="sync-ref-2",attemptId="attempt-1"),
                    TrainingSyncEvent("x1","EVALUATION","eval-1","evaluation","SYNC:1","SYNC_POLICY:1",sessionId="sync-ref-2",attemptIds=listOf("attempt-1"),evidenceIds=listOf("missing-evidence"))
                ))
            val result = g.applySync(envelope, session, registry)
            assertFalse(result.accepted)
            assertTrue(result.rejections.any { it.contains("SYNC_REFERENCE_NOT_FOUND:EVIDENCE:missing-evidence") })
            assertNull(TrainingDurableStore(context).use { it.readImmutable("SESSION","sync-ref-2") })
            assertNull(TrainingDurableStore(context).use { it.readImmutable("EVALUATION","eval-1") })
        }
    }

    @Test fun syncRejectsDecisionWithUnknownBasisEvaluationWithoutMutation() {
        TrainingPersistenceGateway(context).use { g ->
            g.register(RuleVersion("SYNC","1","VALID"), PolicyVersion("SYNC_POLICY","1","VALID"))
            val session = TrainingSession("sync-ref-3","d1","sit","c1",emptyList(),ruleVersionId="SYNC:1",policyVersionId="SYNC_POLICY:1")
            val registry = TrainingVersionRegistry(listOf(RuleVersion("SYNC","1","VALID")), listOf(PolicyVersion("SYNC_POLICY","1","VALID")))
            val envelope = TrainingSyncEnvelope("sync-ref-3","d1","SYNC:1","SYNC_POLICY:1",
                listOf(
                    TrainingSyncEvent("s1","SESSION","sync-ref-3","session","SYNC:1","SYNC_POLICY:1",sessionId="sync-ref-3"),
                    TrainingSyncEvent("d1","DECISION","decision-1","decision","SYNC:1","SYNC_POLICY:1",sessionId="sync-ref-3",basisEvaluationIds=listOf("missing-evaluation"))
                ))
            val result = g.applySync(envelope, session, registry)
            assertFalse(result.accepted)
            assertTrue(result.rejections.any { it.contains("SYNC_REFERENCE_NOT_FOUND:EVALUATION:missing-evaluation") })
            assertNull(TrainingDurableStore(context).use { it.readImmutable("DECISION","decision-1") })
        }
    }


    @Test fun syncRejectsClientIdentityReuseWhenMetadataDiffersEvenWithSamePayload() {
        TrainingPersistenceGateway(context).use { g ->
            g.register(RuleVersion("SYNC","1","VALID"), PolicyVersion("SYNC_POLICY","1","VALID"))
            val session = TrainingSession("sync-ref-4","d1","sit","c1",emptyList(),ruleVersionId="SYNC:1",policyVersionId="SYNC_POLICY:1")
            val registry = TrainingVersionRegistry(
                listOf(RuleVersion("SYNC","1","VALID")),
                listOf(PolicyVersion("SYNC_POLICY","1","VALID"))
            )
            val first = TrainingSyncEnvelope(
                "sync-ref-4","d1","SYNC:1","SYNC_POLICY:1",
                listOf(
                    TrainingSyncEvent("same-id","SESSION","sync-ref-4","same-payload","SYNC:1","SYNC_POLICY:1",sessionId="sync-ref-4")
                )
            )
            assertTrue(g.applySync(first, session, registry).accepted)

            val conflictingMetadata = TrainingSyncEnvelope(
                "sync-ref-4","d1","SYNC:1","SYNC_POLICY:1",
                listOf(
                    TrainingSyncEvent("same-id","ATTEMPT","attempt-2","same-payload","SYNC:1","SYNC_POLICY:1",sessionId="sync-ref-4",dogId="d1")
                )
            )
            val result = g.applySync(conflictingMetadata, session, registry)
            assertFalse(result.accepted)
            assertTrue(result.rejections.any { it.startsWith("CLIENT_ID_REUSE_WITH_DIFFERENT_CONTENT:") })
            assertNull(TrainingDurableStore(context).use { it.readImmutable("ATTEMPT","attempt-2") })
        }
    }

    @Test fun syncAcceptsEvidenceSupersedeWhenParentAndChildArriveInSameBatch() {
        TrainingPersistenceGateway(context).use { g ->
            g.register(RuleVersion("SYNC","1","VALID"), PolicyVersion("SYNC_POLICY","1","VALID"))
            val session = TrainingSession("sync-ref-5","d1","sit","c1",emptyList(),ruleVersionId="SYNC:1",policyVersionId="SYNC_POLICY:1")
            val registry = TrainingVersionRegistry(
                listOf(RuleVersion("SYNC","1","VALID")),
                listOf(PolicyVersion("SYNC_POLICY","1","VALID"))
            )
            val first = TrainingSyncEnvelope(
                "sync-ref-5","d1","SYNC:1","SYNC_POLICY:1",
                listOf(
                    TrainingSyncEvent("s1","SESSION","sync-ref-5","session","SYNC:1","SYNC_POLICY:1",sessionId="sync-ref-5"),
                    TrainingSyncEvent("a1","ATTEMPT","attempt-1","attempt","SYNC:1","SYNC_POLICY:1",sessionId="sync-ref-5",dogId="d1")
                )
            )
            assertTrue(g.applySync(first, session, registry).accepted)

            val batch = first.copy(events=listOf(
                TrainingSyncEvent("child-first","EVIDENCE","evidence-child","child","SYNC:1","SYNC_POLICY:1",
                    supersedesRecordId="evidence-parent",sessionId="sync-ref-5",attemptId="attempt-1"),
                TrainingSyncEvent("parent-second","EVIDENCE","evidence-parent","parent","SYNC:1","SYNC_POLICY:1",
                    sessionId="sync-ref-5",attemptId="attempt-1")
            ))

            val result = g.applySync(batch, session, registry)
            assertTrue(result.accepted)
            assertEquals(listOf("parent-second","child-first"), result.acceptedEventIds)
            assertEquals("parent", TrainingDurableStore(context).use { it.readImmutable("EVIDENCE","evidence-parent") })
            assertEquals("child", TrainingDurableStore(context).use { it.readImmutable("EVIDENCE","evidence-child") })
        }
    }




    @Test fun syncRejectsCrossSessionReferencesEvenWhenReferencedRecordsExist() {
        TrainingPersistenceGateway(context).use { g ->
            g.register(RuleVersion("SYNC","1","VALID"), PolicyVersion("SYNC_POLICY","1","VALID"))
            val registry = TrainingVersionRegistry(
                listOf(RuleVersion("SYNC","1","VALID")),
                listOf(PolicyVersion("SYNC_POLICY","1","VALID"))
            )

            val foreignSession = TrainingSession(
                "sync-foreign-a","dog-a","sit","c1",emptyList(),
                ruleVersionId="SYNC:1",policyVersionId="SYNC_POLICY:1"
            )
            val foreignEnvelope = TrainingSyncEnvelope(
                "sync-foreign-a","dog-a","SYNC:1","SYNC_POLICY:1",
                listOf(
                    TrainingSyncEvent("fa-session","SESSION","sync-foreign-a","foreign-session","SYNC:1","SYNC_POLICY:1",sessionId="sync-foreign-a"),
                    TrainingSyncEvent("fa-attempt","ATTEMPT","foreign-attempt","foreign-attempt","SYNC:1","SYNC_POLICY:1",sessionId="sync-foreign-a",dogId="dog-a"),
                    TrainingSyncEvent("fa-evidence","EVIDENCE","foreign-evidence","foreign-evidence","SYNC:1","SYNC_POLICY:1",sessionId="sync-foreign-a",attemptId="foreign-attempt"),
                    TrainingSyncEvent("fa-eval","EVALUATION","foreign-evaluation","foreign-evaluation","SYNC:1","SYNC_POLICY:1",sessionId="sync-foreign-a",attemptIds=listOf("foreign-attempt"),evidenceIds=listOf("foreign-evidence")),
                    TrainingSyncEvent("fa-decision","DECISION","foreign-decision","foreign-decision","SYNC:1","SYNC_POLICY:1",sessionId="sync-foreign-a",basisEvaluationIds=listOf("foreign-evaluation"))
                )
            )
            assertTrue(g.applySync(foreignEnvelope, foreignSession, registry).accepted)

            val targetSession = TrainingSession(
                "sync-target-b","dog-b","sit","c1",emptyList(),
                ruleVersionId="SYNC:1",policyVersionId="SYNC_POLICY:1"
            )

            val foreignAttemptEvidence = TrainingSyncEnvelope(
                "sync-target-b","dog-b","SYNC:1","SYNC_POLICY:1",
                listOf(
                    TrainingSyncEvent("tb-session-1","SESSION","sync-target-b","target-session","SYNC:1","SYNC_POLICY:1",sessionId="sync-target-b"),
                    TrainingSyncEvent("tb-evidence-foreign-attempt","EVIDENCE","target-evidence-foreign-attempt","target-evidence","SYNC:1","SYNC_POLICY:1",sessionId="sync-target-b",attemptId="foreign-attempt")
                )
            )
            val evidenceResult = g.applySync(foreignAttemptEvidence, targetSession, registry)
            assertFalse(evidenceResult.accepted)
            assertTrue(evidenceResult.rejections.any { it.contains("SYNC_REFERENCE_SESSION_MISMATCH:ATTEMPT:foreign-attempt") })
            assertNull(TrainingDurableStore(context).use { it.readImmutable("SESSION","sync-target-b") })
            assertNull(TrainingDurableStore(context).use { it.readImmutable("EVIDENCE","target-evidence-foreign-attempt") })

            val targetBase = TrainingSyncEnvelope(
                "sync-target-b","dog-b","SYNC:1","SYNC_POLICY:1",
                listOf(
                    TrainingSyncEvent("tb-session-2","SESSION","sync-target-b","target-session","SYNC:1","SYNC_POLICY:1",sessionId="sync-target-b"),
                    TrainingSyncEvent("tb-attempt","ATTEMPT","target-attempt","target-attempt","SYNC:1","SYNC_POLICY:1",sessionId="sync-target-b",dogId="dog-b"),
                    TrainingSyncEvent("tb-evidence","EVIDENCE","target-evidence","target-evidence","SYNC:1","SYNC_POLICY:1",sessionId="sync-target-b",attemptId="target-attempt")
                )
            )
            assertTrue(g.applySync(targetBase, targetSession, registry).accepted)

            val foreignAttemptEvaluation = targetBase.copy(events=listOf(
                TrainingSyncEvent("tb-eval-foreign-attempt","EVALUATION","target-eval-foreign-attempt","target-eval","SYNC:1","SYNC_POLICY:1",
                    sessionId="sync-target-b",attemptIds=listOf("foreign-attempt"),evidenceIds=listOf("target-evidence"))
            ))
            val evaluationAttemptResult = g.applySync(foreignAttemptEvaluation, targetSession, registry)
            assertFalse(evaluationAttemptResult.accepted)
            assertTrue(evaluationAttemptResult.rejections.any { it.contains("SYNC_REFERENCE_SESSION_MISMATCH:ATTEMPT:foreign-attempt") })
            assertNull(TrainingDurableStore(context).use { it.readImmutable("EVALUATION","target-eval-foreign-attempt") })

            val foreignEvidenceEvaluation = targetBase.copy(events=listOf(
                TrainingSyncEvent("tb-eval-foreign-evidence","EVALUATION","target-eval-foreign-evidence","target-eval","SYNC:1","SYNC_POLICY:1",
                    sessionId="sync-target-b",attemptIds=listOf("target-attempt"),evidenceIds=listOf("foreign-evidence"))
            ))
            val evaluationEvidenceResult = g.applySync(foreignEvidenceEvaluation, targetSession, registry)
            assertFalse(evaluationEvidenceResult.accepted)
            assertTrue(evaluationEvidenceResult.rejections.any { it.contains("SYNC_REFERENCE_SESSION_MISMATCH:EVIDENCE:foreign-evidence") })
            assertNull(TrainingDurableStore(context).use { it.readImmutable("EVALUATION","target-eval-foreign-evidence") })

            val foreignDecision = targetBase.copy(events=listOf(
                TrainingSyncEvent("tb-decision-foreign-evaluation","DECISION","target-decision-foreign-evaluation","target-decision","SYNC:1","SYNC_POLICY:1",
                    sessionId="sync-target-b",basisEvaluationIds=listOf("foreign-evaluation"))
            ))
            val decisionResult = g.applySync(foreignDecision, targetSession, registry)
            assertFalse(decisionResult.accepted)
            assertTrue(decisionResult.rejections.any { it.contains("SYNC_REFERENCE_SESSION_MISMATCH:EVALUATION:foreign-evaluation") })
            assertNull(TrainingDurableStore(context).use { it.readImmutable("DECISION","target-decision-foreign-evaluation") })

            val foreignSupersede = targetBase.copy(events=listOf(
                TrainingSyncEvent("tb-supersede-foreign","EVIDENCE","target-evidence-foreign-supersede","replacement","SYNC:1","SYNC_POLICY:1",
                    supersedesRecordId="foreign-evidence",sessionId="sync-target-b",attemptId="target-attempt")
            ))
            val supersedeResult = g.applySync(foreignSupersede, targetSession, registry)
            assertFalse(supersedeResult.accepted)
            assertTrue(supersedeResult.rejections.any { it.contains("SYNC_REFERENCE_SESSION_MISMATCH:EVIDENCE:foreign-evidence") })
            assertNull(TrainingDurableStore(context).use { it.readImmutable("EVIDENCE","target-evidence-foreign-supersede") })
        }
    }

    @Test fun syncDuplicateRecordConflictIsReturnedAsStructuredRejection() {
        TrainingPersistenceGateway(context).use { g ->
            g.register(RuleVersion("SYNC","1","VALID"), PolicyVersion("SYNC_POLICY","1","VALID"))
            val session = TrainingSession("sync-constraint-1","d1","sit","c1",emptyList(),ruleVersionId="SYNC:1",policyVersionId="SYNC_POLICY:1")
            val registry = TrainingVersionRegistry(
                listOf(RuleVersion("SYNC","1","VALID")),
                listOf(PolicyVersion("SYNC_POLICY","1","VALID"))
            )

            val first = TrainingSyncEnvelope(
                "sync-constraint-1","d1","SYNC:1","SYNC_POLICY:1",
                listOf(
                    TrainingSyncEvent("constraint-1","SESSION","sync-constraint-1","session","SYNC:1","SYNC_POLICY:1",sessionId="sync-constraint-1")
                )
            )
            assertTrue(g.applySync(first, session, registry).accepted)

            val conflict = first.copy(events=listOf(
                TrainingSyncEvent("constraint-2","SESSION","sync-constraint-1","different","SYNC:1","SYNC_POLICY:1",sessionId="sync-constraint-1")
            ))
            val result = g.applySync(conflict, session, registry)
            assertFalse(result.accepted)
            assertTrue(result.rejections.any { it.contains("SYNC_RECORD_ID_CONFLICT") })
            assertEquals("session", TrainingDurableStore(context).use { it.readImmutable("SESSION","sync-constraint-1") })
        }
    }

}
