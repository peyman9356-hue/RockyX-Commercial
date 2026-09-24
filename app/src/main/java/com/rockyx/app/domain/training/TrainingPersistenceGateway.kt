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
        val accepted = acceptEvent(evidence.clientGeneratedId, canonicalPayload)
        if (!accepted) return false
        store.appendEvidence(evidence.evidenceId, canonicalPayload, System.currentTimeMillis(), evidence.supersedesEvidenceId)
        return true
    }

    fun appendEvaluation(evaluation: Evaluation, canonicalPayload: String): Boolean {
        val accepted = acceptEvent(evaluation.evaluationId, canonicalPayload)
        if (!accepted) return false
        store.appendImmutable("EVALUATION", evaluation.evaluationId, canonicalPayload, evaluation.createdAt)
        return true
    }

    fun appendDecision(decision: Decision, canonicalPayload: String): Boolean {
        val accepted = acceptEvent(decision.decisionId, canonicalPayload)
        if (!accepted) return false
        require(decision.basisEvaluationIds == decision.basisEvaluationIds.sorted()) { "BASIS_EVALUATION_IDS_NOT_SORTED" }
        store.appendImmutable("DECISION", decision.decisionId, canonicalPayload, decision.createdAt)
        return true
    }

    override fun close() { store.close() }
}
