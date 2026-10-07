package com.rockyx.app.domain.training

import android.content.Context
import org.robolectric.RuntimeEnvironment
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TrainingPersistenceGatewayTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    @Test fun pinnedSessionMustResolveExactDurableVersions() {
        TrainingPersistenceGateway(context).use { g ->
            g.register(RuleVersion("SIT", "1", "VALID"), PolicyVersion("SIT_POLICY", "1", "VALID"))
            val session = TrainingSession(
                sessionId="s1", dogId="d1", skillId="sit", contextId="c1", attempts=emptyList(),
                ruleVersionId="SIT:1", policyVersionId="SIT_POLICY:1"
            )
            assertEquals("1", g.validatePinnedSession(session).first.version)
            assertThrows(IllegalArgumentException::class.java) {
                g.validatePinnedSession(session.copy(ruleVersionId="SIT:999"))
            }
        }
    }

    @Test fun sessionAndAttemptAreDurableAndAttemptEventIsIdempotent() {
        TrainingPersistenceGateway(context).use { g ->
            val session = TrainingSession(
                sessionId="s-durable", dogId="d1", skillId="sit", contextId="c1", attempts=emptyList(),
                ruleVersionId="SIT:1", policyVersionId="SIT_POLICY:1", createdAt=10L
            )
            g.register(RuleVersion("SIT", "1", "VALID"), PolicyVersion("SIT_POLICY", "1", "VALID"))
            assertTrue(g.appendSession(session, "session-canonical"))
            assertEquals("session-canonical", TrainingDurableStore(context).use { it.readImmutable("SESSION", "s-durable") })

            val attempt = TrainingAttempt("a-durable", "s-durable", 1, emptyList(), "d1", 11L, "attempt-event-1")
            assertTrue(g.appendAttempt(attempt, "attempt-canonical"))
            assertFalse(g.appendAttempt(attempt, "attempt-canonical"))
            assertThrows(IllegalArgumentException::class.java) {
                g.appendAttempt(attempt, "different-attempt")
            }
            assertEquals("attempt-canonical", TrainingDurableStore(context).use { it.readImmutable("ATTEMPT", "a-durable") })
        }
    }

    @Test fun unknownTrainingReferencesAreRejected() {
        TrainingPersistenceGateway(context).use { g ->
            g.register(RuleVersion("SIT","1","VALID"), PolicyVersion("SIT_POLICY","1","VALID"))
            val session = TrainingSession("s-ref","d1","sit","c1",emptyList(),ruleVersionId="SIT:1",policyVersionId="SIT_POLICY:1")
            assertTrue(g.appendSession(session, "session-ref"))
            assertThrows(IllegalArgumentException::class.java) {
                g.appendAttempt(TrainingAttempt("a-missing","missing-session",1,emptyList(),"d1",1L,"attempt-missing"), "attempt-missing")
            }
            assertThrows(IllegalArgumentException::class.java) {
                g.appendEvidence(SitEvidence("e-missing","d1","missing-attempt","s-ref","c1",CueType.VERBAL,LureStatus.NOT_REQUIRED,SitResult.YES,ResponseQuality.IMMEDIATE,RewardTiming.IMMEDIATE), "e-missing")
            }
        }
    }

    @Test fun eventIdempotencyAndImmutableEvaluationAreEnforcedThroughGateway() {
        TrainingPersistenceGateway(context).use { g ->
            g.register(RuleVersion("SIT","1","VALID"), PolicyVersion("SIT_POLICY","1","VALID"))
            assertTrue(g.appendSession(TrainingSession("s1","d1","sit","c1",emptyList(),ruleVersionId="SIT:1",policyVersionId="SIT_POLICY:1"), "session-eval"))
            assertTrue(g.appendEvaluation(
                Evaluation("ev1","s1",emptyList(),emptyList(),EvaluationResult.UNKNOWN,Sufficiency.INSUFFICIENT,
                    EvaluationStatus.INSUFFICIENT,ConfidenceTier.LOW,"SIT:1","SIT_POLICY:1"),
                "canonical-evaluation"
            ))
            assertFalse(g.appendEvaluation(
                Evaluation("ev1","s1",emptyList(),emptyList(),EvaluationResult.UNKNOWN,Sufficiency.INSUFFICIENT,
                    EvaluationStatus.INSUFFICIENT,ConfidenceTier.LOW,"SIT:1","SIT_POLICY:1"),
                "canonical-evaluation"
            ))
            assertThrows(IllegalArgumentException::class.java) {
                g.appendEvaluation(
                    Evaluation("ev1","s1",emptyList(),emptyList(),EvaluationResult.FAIL,Sufficiency.SUFFICIENT,
                        EvaluationStatus.VALID,ConfidenceTier.HIGH,"SIT:1","SIT_POLICY:1"),
                    "different"
                )
            }
        }
    }
    @Test fun failedSupersedeDoesNotConsumeClientEvent() {
        TrainingPersistenceGateway(context).use { g ->
            val session = TrainingSession("s1","d1","sit","c1",emptyList(),ruleVersionId="SIT:1",policyVersionId="SIT_POLICY:1")
            g.register(RuleVersion("SIT","1","VALID"), PolicyVersion("SIT_POLICY","1","VALID"))
            assertTrue(g.appendSession(session, "session-base"))
            val attempt = TrainingAttempt("a1","s1",1,emptyList(),"d1",1L,"attempt-a1")
            assertTrue(g.appendAttempt(attempt, "attempt-a1"))
            val base = SitEvidence("base","d1","a1","s1","c1",CueType.VERBAL,LureStatus.NOT_REQUIRED,SitResult.YES,
                ResponseQuality.IMMEDIATE,RewardTiming.IMMEDIATE)
            assertTrue(g.appendEvidence(base, "base-canonical"))

            val competing = SitEvidence("e2","d1","a1","s1","c1",CueType.VERBAL,LureStatus.NOT_REQUIRED,SitResult.YES,
                ResponseQuality.IMMEDIATE,RewardTiming.IMMEDIATE,supersedesEvidenceId="base")
            assertTrue(g.appendEvidence(competing, "e2-canonical"))
            assertThrows(IllegalArgumentException::class.java) {
                g.appendEvidence(competing.copy(evidenceId="e3", clientGeneratedId="e3", supersedesEvidenceId="base"), "e3-canonical")
            }
            assertTrue(g.appendEvidence(
                competing.copy(evidenceId="e3", attemptId="a1", clientGeneratedId="e3", supersedesEvidenceId=null),
                "e3-canonical"
            ))
        }
    }



    
    @Test fun offlinePinningUsesNumericVersionOrderingAndRejectsAmbiguousVersions() {
        TrainingPersistenceGateway(context).use { g ->
            g.register(RuleVersion("ORDER", "2", "VALID"), PolicyVersion("ORDER_POLICY", "2", "VALID"))
            g.register(RuleVersion("ORDER", "10", "VALID"), PolicyVersion("ORDER_POLICY", "10", "VALID"))
            assertEquals("ORDER:10", g.resolveOfflinePins("ORDER", "ORDER_POLICY").first)
            assertEquals("ORDER_POLICY:10", g.resolveOfflinePins("ORDER", "ORDER_POLICY").second)

            assertThrows(IllegalArgumentException::class.java) {
                g.register(RuleVersion("ORDER", "1-beta", "VALID"), PolicyVersion("ORDER_POLICY", "1-beta", "VALID"))
            }
            assertThrows(IllegalArgumentException::class.java) {
                g.register(RuleVersion("ORDER", "01", "VALID"), PolicyVersion("ORDER_POLICY", "01", "VALID"))
            }
        }
    }

    @Test fun offlinePinningSelectsNewestValidLocalVersionsAndKeepsExistingSessionExact() {
        TrainingPersistenceGateway(context).use { g ->
            g.register(RuleVersion("SIT", "1", "VALID"), PolicyVersion("SIT_POLICY", "1", "VALID"))
            g.register(RuleVersion("SIT", "2", "VALID"), PolicyVersion("SIT_POLICY", "2", "VALID"))

            val pins = g.resolveOfflinePins("SIT", "SIT_POLICY")
            assertEquals("SIT:2", pins.first)
            assertEquals("SIT_POLICY:2", pins.second)

            val session = TrainingSession(
                "offline-s1", "d1", "sit", "c1", emptyList(),
                ruleVersionId=pins.first, policyVersionId=pins.second
            )
            assertEquals("2", g.validatePinnedSession(session).first.version)

            g.register(RuleVersion("SIT", "3", "VALID"), PolicyVersion("SIT_POLICY", "3", "VALID"))
            assertEquals("2", g.validatePinnedSession(session).first.version)
            assertEquals("SIT_POLICY:2", g.validatePinnedSession(session).second.policyVersionId)
        }
    }

    @Test fun offlinePinningIgnoresInvalidNewestAndRejectsMissingValidCache() {
        TrainingPersistenceGateway(context).use { g ->
            g.register(RuleVersion("SIT", "1", "VALID"), PolicyVersion("SIT_POLICY", "1", "VALID"))
            g.register(RuleVersion("SIT", "2", "INVALID"), PolicyVersion("SIT_POLICY", "2", "INVALID"))

            assertEquals("SIT:1", g.resolveOfflinePins("SIT", "SIT_POLICY").first)
            assertEquals("SIT_POLICY:1", g.resolveOfflinePins("SIT", "SIT_POLICY").second)

            assertThrows(IllegalArgumentException::class.java) {
                g.resolveOfflinePins("DOWN", "SIT_POLICY")
            }
        }
    }


    @Test fun syncValidatorAcceptsExactSessionPinsAndRejectsMismatchedEventPins() {
        TrainingPersistenceGateway(context).use { g ->
            g.register(RuleVersion("SYNC","1","VALID"), PolicyVersion("SYNC_POLICY","1","VALID"))
            val session = TrainingSession("sync-s1","d1","sit","c1",emptyList(),ruleVersionId="SYNC:1",policyVersionId="SYNC_POLICY:1")
            val registry = TrainingVersionRegistry(listOf(RuleVersion("SYNC","1","VALID")), listOf(PolicyVersion("SYNC_POLICY","1","VALID")))
            val valid = TrainingSyncEnvelope("sync-s1","d1","SYNC:1","SYNC_POLICY:1",listOf(
                TrainingSyncEvent("evt-1","ATTEMPT","a1","attempt", "SYNC:1","SYNC_POLICY:1", sessionId="sync-s1", dogId="d1")
            ))
            assertTrue(TrainingSyncValidator.validate(valid,session,registry).accepted)
            val bad = valid.copy(events=listOf(valid.events.first().copy(ruleVersionId="SYNC:2")))
            val result = TrainingSyncValidator.validate(bad,session,registry)
            assertFalse(result.accepted)
            assertTrue(result.rejections.contains(SyncRejectionCode.EVENT_VERSION_MISMATCH))
        }
    }

    @Test fun syncValidatorRejectsEnvelopeThatTriesToRemapAnExistingSession() {
        TrainingPersistenceGateway(context).use { g ->
            g.register(RuleVersion("SYNC","1","VALID"), PolicyVersion("SYNC_POLICY","1","VALID"))
            g.register(RuleVersion("SYNC","2","VALID"), PolicyVersion("SYNC_POLICY","2","VALID"))
            val session = TrainingSession("sync-s2","d1","sit","c1",emptyList(),ruleVersionId="SYNC:1",policyVersionId="SYNC_POLICY:1")
            val registry = TrainingVersionRegistry(
                listOf(RuleVersion("SYNC","1","VALID"),RuleVersion("SYNC","2","VALID")),
                listOf(PolicyVersion("SYNC_POLICY","1","VALID"),PolicyVersion("SYNC_POLICY","2","VALID"))
            )
            val remapped = TrainingSyncEnvelope("sync-s2","d1","SYNC:2","SYNC_POLICY:2",emptyList())
            val result = TrainingSyncValidator.validate(remapped,session,registry)
            assertFalse(result.accepted)
            assertTrue(result.rejections.contains(SyncRejectionCode.VERSION_MISMATCH))
        }
    }


    @Test fun persistedSessionPinsCannotBeRemappedBySyncCaller() {
        TrainingPersistenceGateway(context).use { g ->
            g.register(RuleVersion("PIN", "1", "VALID"), PolicyVersion("PIN_POLICY", "1", "VALID"))
            g.register(RuleVersion("PIN", "2", "VALID"), PolicyVersion("PIN_POLICY", "2", "VALID"))
            val storedSession = TrainingSession(
                "pin-session", "d1", "sit", "c1", emptyList(),
                ruleVersionId = "PIN:1", policyVersionId = "PIN_POLICY:1"
            )
            assertTrue(g.appendSession(storedSession, "pin-session"))

            val callerSession = storedSession.copy(
                ruleVersionId = "PIN:2",
                policyVersionId = "PIN_POLICY:2"
            )
            val registry = TrainingVersionRegistry(
                listOf(RuleVersion("PIN","1","VALID"), RuleVersion("PIN","2","VALID")),
                listOf(PolicyVersion("PIN_POLICY","1","VALID"), PolicyVersion("PIN_POLICY","2","VALID"))
            )
            val envelope = TrainingSyncEnvelope(
                "pin-session","d1","PIN:2","PIN_POLICY:2",
                listOf(
                    TrainingSyncEvent(
                        "pin-attempt","ATTEMPT","pin-attempt","attempt",
                        "PIN:2","PIN_POLICY:2",sessionId="pin-session",dogId="d1"
                    )
                )
            )
            val result = g.applySync(envelope, callerSession, registry)
            assertFalse(result.accepted)
            assertTrue(result.rejections.any { it.contains("SYNC_SESSION_RULE_VERSION_MISMATCH") })
            assertNull(TrainingDurableStore(context).use { it.readImmutable("ATTEMPT","pin-attempt") })
        }
    }

    @Test fun directEvidenceRejectsForeignDog() {
        TrainingPersistenceGateway(context).use { g ->
            g.register(RuleVersion("DOG", "1", "VALID"), PolicyVersion("DOG_POLICY", "1", "VALID"))
            val session = TrainingSession("dog-session","dog-a","sit","c1",emptyList(),ruleVersionId="DOG:1",policyVersionId="DOG_POLICY:1")
            assertTrue(g.appendSession(session,"dog-session"))
            assertTrue(g.appendAttempt(TrainingAttempt("dog-attempt","dog-session",1,emptyList(),"dog-a",1L,"dog-attempt"),"dog-attempt"))

            val evidence = SitEvidence(
                "foreign-evidence","dog-b","dog-attempt","dog-session","c1",
                CueType.VERBAL,LureStatus.NOT_REQUIRED,SitResult.YES,
                ResponseQuality.IMMEDIATE,RewardTiming.IMMEDIATE
            )
            assertThrows(IllegalArgumentException::class.java) {
                g.appendEvidence(evidence,"foreign-evidence")
            }
            assertNull(TrainingDurableStore(context).use { it.readImmutable("EVIDENCE","foreign-evidence") })
        }
    }

    @Test fun directEvaluationMustUsePersistedSessionVersionPins() {
        TrainingPersistenceGateway(context).use { g ->
            g.register(RuleVersion("EVAL_PIN", "1", "VALID"), PolicyVersion("EVAL_POLICY", "1", "VALID"))
            g.register(RuleVersion("EVAL_PIN", "2", "VALID"), PolicyVersion("EVAL_POLICY", "2", "VALID"))
            val session = TrainingSession("eval-pin-session","d1","sit","c1",emptyList(),ruleVersionId="EVAL_PIN:1",policyVersionId="EVAL_POLICY:1")
            assertTrue(g.appendSession(session,"eval-pin-session"))

            val evaluation = Evaluation(
                "eval-pin-1","eval-pin-session",emptyList(),emptyList(),
                EvaluationResult.UNKNOWN,Sufficiency.INSUFFICIENT,EvaluationStatus.INSUFFICIENT,
                ConfidenceTier.LOW,"EVAL_PIN:2","EVAL_POLICY:2"
            )
            assertThrows(IllegalArgumentException::class.java) {
                g.appendEvaluation(evaluation,"eval-pin")
            }
            assertNull(TrainingDurableStore(context).use { it.readImmutable("EVALUATION","eval-pin-1") })
        }
    }

    @Test fun directDecisionRequiresNonEmptyBasisAndMatchingPolicy() {
        TrainingPersistenceGateway(context).use { g ->
            g.register(RuleVersion("DEC_PIN", "1", "VALID"), PolicyVersion("DEC_POLICY", "1", "VALID"))
            g.register(RuleVersion("DEC_PIN", "2", "VALID"), PolicyVersion("DEC_POLICY", "2", "VALID"))
            val session = TrainingSession("decision-session","d1","sit","c1",emptyList(),ruleVersionId="DEC_PIN:1",policyVersionId="DEC_POLICY:1")
            assertTrue(g.appendSession(session,"decision-session"))
            val evaluation = Evaluation(
                "decision-eval","decision-session",emptyList(),emptyList(),
                EvaluationResult.UNKNOWN,Sufficiency.INSUFFICIENT,EvaluationStatus.INSUFFICIENT,
                ConfidenceTier.LOW,"DEC_PIN:1","DEC_POLICY:1"
            )
            assertTrue(g.appendEvaluation(evaluation,"decision-eval"))

            assertThrows(IllegalArgumentException::class.java) {
                g.appendDecision(
                    Decision("decision-empty","d1","sit","DEC_POLICY:1",emptyList(),DecisionType.REVIEW,emptyList()),
                    "decision-empty"
                )
            }
            assertThrows(IllegalArgumentException::class.java) {
                g.appendDecision(
                    Decision("decision-policy-mismatch","d1","sit","DEC_POLICY:2",listOf("decision-eval"),DecisionType.REVIEW,emptyList()),
                    "decision-policy-mismatch"
                )
            }
            assertNull(TrainingDurableStore(context).use { it.readImmutable("DECISION","decision-policy-mismatch") })
        }
    }

    @Test fun directEvaluationMustMatchCanonicalActiveEvidenceSet() {
        TrainingPersistenceGateway(context).use { g ->
            g.register(RuleVersion("CANON", "1", "VALID"), PolicyVersion("CANON_POLICY", "1", "VALID"))
            val session = TrainingSession("canonical-session","d1","sit","c1",emptyList(),ruleVersionId="CANON:1",policyVersionId="CANON_POLICY:1")
            assertTrue(g.appendSession(session,"canonical-session"))
            assertTrue(g.appendAttempt(TrainingAttempt("canonical-attempt","canonical-session",1,listOf("canonical-evidence"),"d1",1L,"canonical-attempt"),"canonical-attempt"))
            val evidence = SitEvidence(
                "canonical-evidence","d1","canonical-attempt","canonical-session","c1",
                CueType.VERBAL,LureStatus.NOT_REQUIRED,SitResult.YES,
                ResponseQuality.IMMEDIATE,RewardTiming.IMMEDIATE,EvidenceStatus.VALID
            )
            assertTrue(g.appendEvidence(evidence,"canonical-evidence"))

            val invalidEvaluation = Evaluation(
                "canonical-eval-bad","canonical-session",listOf("canonical-attempt"),emptyList(),
                EvaluationResult.UNKNOWN,Sufficiency.INSUFFICIENT,EvaluationStatus.INSUFFICIENT,
                ConfidenceTier.LOW,"CANON:1","CANON_POLICY:1"
            )
            assertThrows(IllegalArgumentException::class.java) {
                g.appendEvaluation(invalidEvaluation,"canonical-eval-bad")
            }
            assertNull(TrainingDurableStore(context).use { it.readImmutable("EVALUATION","canonical-eval-bad") })

            val validEvaluation = invalidEvaluation.copy(
                evaluationId = "canonical-eval-good",
                evidenceIds = listOf("canonical-evidence")
            )
            assertTrue(g.appendEvaluation(validEvaluation,"canonical-eval-good"))
        }
    }

    @Test fun invalidEvidenceCannotSupersedeValidEvidenceInAggregator() {
        val active = SitSessionAggregator.activeEvidence(
            listOf(
                SitEvidence(
                    "active-parent","d1","a1","s1","c1",
                    CueType.VERBAL,LureStatus.NOT_REQUIRED,SitResult.YES,
                    ResponseQuality.IMMEDIATE,RewardTiming.IMMEDIATE,EvidenceStatus.VALID
                ),
                SitEvidence(
                    "invalid-child","d1","a1","s1","c1",
                    CueType.VERBAL,LureStatus.NOT_REQUIRED,SitResult.NO,
                    ResponseQuality.IMMEDIATE,RewardTiming.IMMEDIATE,EvidenceStatus.INVALID,
                    supersedesEvidenceId = "active-parent"
                )
            )
        )
        assertEquals(listOf("active-parent"), active.map { it.evidenceId })
    }

    @Test fun invalidVersionPinDurablyInvalidatesExistingSession() {
        TrainingPersistenceGateway(context).use { g ->
            g.register(RuleVersion("INVALIDATE", "1", "VALID"), PolicyVersion("INVALIDATE_POLICY", "1", "VALID"))
            val session = TrainingSession(
                "invalidate-session","d1","sit","c1",emptyList(),
                ruleVersionId="INVALIDATE:1",policyVersionId="INVALIDATE_POLICY:1"
            )
            assertTrue(g.appendSession(session,"invalidate-session"))

            val envelope = TrainingSyncEnvelope(
                "invalidate-session","d1","INVALIDATE:1","INVALIDATE_POLICY:1",emptyList()
            )
            assertThrows(IllegalArgumentException::class.java) {
                g.applySync(envelope, session, TrainingVersionRegistry(emptyList(), emptyList()))
            }
            assertEquals(
                SessionStatus.INVALID,
                TrainingDurableStore(context).use { it.readSessionStatus("invalidate-session") }
            )
            assertThrows(IllegalArgumentException::class.java) {
                g.appendAttempt(
                    TrainingAttempt("direct-blocked","invalidate-session",1,emptyList(),"d1",2L,"direct-blocked"),
                    "direct-blocked"
                )
            }

            val registry = TrainingVersionRegistry(
                listOf(RuleVersion("INVALIDATE","1","VALID")),
                listOf(PolicyVersion("INVALIDATE_POLICY","1","VALID"))
            )
            val blocked = envelope.copy(
                events = listOf(
                    TrainingSyncEvent(
                        "blocked-attempt","ATTEMPT","blocked-attempt","attempt",
                        "INVALIDATE:1","INVALIDATE_POLICY:1",
                        sessionId="invalidate-session",dogId="d1"
                    )
                )
            )
            val result = g.applySync(blocked, session, registry)
            assertFalse(result.accepted)
            assertTrue(result.rejections.any { it.contains("SYNC_SESSION_INVALID") })
            assertNull(TrainingDurableStore(context).use { it.readImmutable("ATTEMPT","blocked-attempt") })
        }
    }


}
