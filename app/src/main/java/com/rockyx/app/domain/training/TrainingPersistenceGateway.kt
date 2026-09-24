package com.rockyx.app.domain.training

import android.content.Context

class TrainingPersistenceGateway(context: Context) : AutoCloseable {
    private val store = TrainingDurableStore(context)

    fun register(rule: RuleVersion, policy: PolicyVersion) {
        store.registerRule(rule)
        store.registerPolicy(policy)
    }

    fun validatePinnedSession(session: TrainingSession): Pair<RuleVersion, PolicyVersion> {
        val rule = store.requireRule(session.ruleVersionId)
        val policy = store.requirePolicy(session.policyVersionId)
        require(session.status != SessionStatus.INVALID && session.status != SessionStatus.REJECTED) { "SESSION_NOT_ACCEPTABLE" }
        return rule to policy
    }

    fun acceptEvent(clientGeneratedId: String, canonicalPayload: String): Boolean =
        store.acceptClientEvent(clientGeneratedId, canonicalPayload)

    fun appendEvidence(evidence: SitEvidence, canonicalPayload: String): Boolean {
        return store.appendEventAndImmutableRecord(
            eventId = evidence.clientGeneratedId,
            recordType = "EVIDENCE",
            recordId = evidence.evidenceId,
            canonicalPayload = canonicalPayload,
            createdAt = System.currentTimeMillis(),
            supersedesEvidenceId = evidence.supersedesEvidenceId
        )
    }

    fun appendEvaluation(evaluation: Evaluation, canonicalPayload: String): Boolean {
        return store.appendEventAndImmutableRecord(
            eventId = evaluation.evaluationId,
            recordType = "EVALUATION",
            recordId = evaluation.evaluationId,
            canonicalPayload = canonicalPayload,
            createdAt = evaluation.createdAt
        )
    }

    fun appendDecision(decision: Decision, canonicalPayload: String): Boolean {
        require(decision.basisEvaluationIds == decision.basisEvaluationIds.sorted()) { "BASIS_EVALUATION_IDS_NOT_SORTED" }
        return store.appendEventAndImmutableRecord(
            eventId = decision.decisionId,
            recordType = "DECISION",
            recordId = decision.decisionId,
            canonicalPayload = canonicalPayload,
            createdAt = decision.createdAt
        )
    }

    override fun close() { store.close() }
}
