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

    @Test fun eventIdempotencyAndImmutableEvaluationAreEnforcedThroughGateway() {
        TrainingPersistenceGateway(context).use { g ->
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
            val base = SitEvidence("base","d1","a1","s1","c1",CueType.VERBAL,LureStatus.NOT_REQUIRED,SitResult.YES,
                ResponseQuality.IMMEDIATE,RewardTiming.IMMEDIATE)
            assertTrue(g.appendEvidence(base, "base-canonical"))

            val competing = SitEvidence("e2","d1","a2","s1","c1",CueType.VERBAL,LureStatus.NOT_REQUIRED,SitResult.YES,
                ResponseQuality.IMMEDIATE,RewardTiming.IMMEDIATE,supersedesEvidenceId="base")
            assertTrue(g.appendEvidence(competing, "e2-canonical"))
            assertThrows(IllegalArgumentException::class.java) {
                g.appendEvidence(competing.copy(evidenceId="e3", clientGeneratedId="e3", supersedesEvidenceId="base"), "e3-canonical")
            }
            assertTrue(g.appendEvidence(
                competing.copy(evidenceId="e3", clientGeneratedId="e3", supersedesEvidenceId=null),
                "e3-canonical"
            ))
        }
    }

}
