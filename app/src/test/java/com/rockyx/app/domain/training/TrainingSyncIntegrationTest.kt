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
            assertThrows(RuntimeException::class.java) {
                g.applySync(conflicting, session, registry)
            }
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

}
