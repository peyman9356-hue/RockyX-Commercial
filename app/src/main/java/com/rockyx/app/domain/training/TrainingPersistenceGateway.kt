package com.rockyx.app.domain.training

import android.content.Context

class TrainingPersistenceGateway(context: Context) : AutoCloseable {
    private val store = TrainingDurableStore(context)

    fun register(rule: RuleVersion, policy: PolicyVersion) {
        store.registerRule(rule)
        store.registerPolicy(policy)
    }

    /**
     * Offline session creation pinning boundary.
     * Resolves the newest valid locally cached versions once and returns their
     * exact IDs. Callers must persist these IDs in TrainingSession and must not
     * replace them with a later/latest version during sync.
     */
    fun resolveOfflinePins(ruleId: String, policyId: String): Pair<String, String> {
        val rule = store.requireLatestValidRule(ruleId)
        val policy = store.requireLatestValidPolicy(policyId)
        return "${rule.ruleId}:${rule.version}" to policy.policyVersionId
    }

    fun validatePinnedSession(session: TrainingSession): Pair<RuleVersion, PolicyVersion> {
        val rule = store.requireRule(session.ruleVersionId)
        val policy = store.requirePolicy(session.policyVersionId)
        require(session.status != SessionStatus.INVALID && session.status != SessionStatus.REJECTED) { "SESSION_NOT_ACCEPTABLE" }
        return rule to policy
    }

    fun appendSession(session: TrainingSession, canonicalPayload: String): Boolean {
        validatePinnedSession(session)
        TrainingValidation.validateSession(session).also { errors ->
            require(errors.isEmpty()) { "INVALID_SESSION:" + errors.joinToString(",") }
        }
        store.appendSession(
            session.sessionId, session.dogId, canonicalPayload, session.createdAt,
            session.ruleVersionId, session.policyVersionId, session.status
        )
        return true
    }

    fun appendAttempt(attempt: TrainingAttempt, canonicalPayload: String): Boolean {
        require(attempt.sessionId.isNotBlank() && attempt.dogId.isNotBlank()) { "INVALID_ATTEMPT_REFERENCE" }
        require(attempt.attemptId.isNotBlank() && attempt.clientGeneratedId.isNotBlank()) { "INVALID_ATTEMPT_IDENTITY" }
        store.requireImmutableRecord("SESSION", attempt.sessionId)
        store.requireSessionActive(attempt.sessionId)
        require(store.requireRecordSession("SESSION", attempt.sessionId) == attempt.sessionId) { "ATTEMPT_SESSION_MISMATCH" }
        val sessionPins = store.requireRecordVersionPins("SESSION", attempt.sessionId)
        val sessionDogId = store.readRecordDog("SESSION", attempt.sessionId)
        require(sessionDogId == null || sessionDogId == attempt.dogId) { "ATTEMPT_DOG_MISMATCH" }
        return store.appendAttempt(
            attempt.attemptId,
            attempt.clientGeneratedId,
            canonicalPayload,
            attempt.createdAt,
            attempt.sessionId,
            attempt.dogId,
            sessionPins.first,
            sessionPins.second,
            attempt.evidenceIds
        )
    }

    fun acceptEvent(clientGeneratedId: String, canonicalPayload: String): Boolean =
        store.acceptClientEvent(clientGeneratedId, canonicalPayload)

    fun appendEvidence(evidence: SitEvidence, canonicalPayload: String): Boolean {
        store.requireImmutableRecord("SESSION", evidence.sessionId)
        store.requireSessionActive(evidence.sessionId)
        store.requireImmutableRecord("ATTEMPT", evidence.attemptId)
        require(evidence.dogId.isNotBlank()) { "EVIDENCE_DOG_REQUIRED" }
        require(store.requireRecordSession("ATTEMPT", evidence.attemptId) == evidence.sessionId) { "EVIDENCE_ATTEMPT_SESSION_MISMATCH" }
        val sessionDogId = store.readRecordDog("SESSION", evidence.sessionId)
        val attemptDogId = store.readRecordDog("ATTEMPT", evidence.attemptId)
        require(sessionDogId == evidence.dogId) { "EVIDENCE_DOG_MISMATCH" }
        require(attemptDogId == evidence.dogId) { "EVIDENCE_ATTEMPT_DOG_MISMATCH" }
        if (evidence.supersedesEvidenceId != null) {
            require(store.requireRecordSession("EVIDENCE", evidence.supersedesEvidenceId) == evidence.sessionId) {
                "EVIDENCE_SUPERSEDE_SESSION_MISMATCH"
            }
            require(store.readRecordDog("EVIDENCE", evidence.supersedesEvidenceId) == evidence.dogId) {
                "EVIDENCE_SUPERSEDE_DOG_MISMATCH"
            }
        }
        return store.appendEventAndImmutableRecord(
            eventId = evidence.clientGeneratedId,
            recordType = "EVIDENCE",
            recordId = evidence.evidenceId,
            canonicalPayload = canonicalPayload,
            createdAt = System.currentTimeMillis(),
            supersedesEvidenceId = evidence.supersedesEvidenceId,
            scopeSessionId = evidence.sessionId,
            scopeDogId = evidence.dogId,
            scopeRuleVersionId = store.requireRecordVersionPins("SESSION", evidence.sessionId).first,
            scopePolicyVersionId = store.requireRecordVersionPins("SESSION", evidence.sessionId).second,
            evidenceStatus = evidence.status,
            outboxAttemptId = evidence.attemptId
        )
    }

    fun appendEvaluation(evaluation: Evaluation, canonicalPayload: String): Boolean {
        store.requireImmutableRecord("SESSION", evaluation.sessionId)
        store.requireSessionActive(evaluation.sessionId)
        val sessionPins = store.requireRecordVersionPins("SESSION", evaluation.sessionId)
        require(evaluation.ruleVersionId == sessionPins.first) { "EVALUATION_RULE_VERSION_MISMATCH" }
        require(evaluation.policyVersionId == sessionPins.second) { "EVALUATION_POLICY_VERSION_MISMATCH" }
        store.requireCanonicalActiveEvidenceIds(evaluation.sessionId, evaluation.evidenceIds)
        evaluation.attemptIds.forEach {
            store.requireImmutableRecord("ATTEMPT", it)
            require(store.requireRecordSession("ATTEMPT", it) == evaluation.sessionId) { "EVALUATION_ATTEMPT_SESSION_MISMATCH:$it" }
        }
        evaluation.evidenceIds.forEach {
            store.requireImmutableRecord("EVIDENCE", it)
            require(store.requireRecordSession("EVIDENCE", it) == evaluation.sessionId) { "EVALUATION_EVIDENCE_SESSION_MISMATCH:$it" }
        }
        return store.appendEventAndImmutableRecord(
            eventId = evaluation.evaluationId,
            recordType = "EVALUATION",
            recordId = evaluation.evaluationId,
            canonicalPayload = canonicalPayload,
            createdAt = evaluation.createdAt,
            scopeSessionId = evaluation.sessionId,
            scopeRuleVersionId = sessionPins.first,
            scopePolicyVersionId = sessionPins.second,
            outboxAttemptIds = evaluation.attemptIds,
            outboxEvidenceIds = evaluation.evidenceIds
        )
    }

    fun appendDecision(decision: Decision, canonicalPayload: String): Boolean {
        require(decision.basisEvaluationIds.isNotEmpty()) { "DECISION_BASIS_REQUIRED" }
        require(decision.basisEvaluationIds == decision.basisEvaluationIds.sorted()) { "BASIS_EVALUATION_IDS_NOT_SORTED" }
        val basisEvaluations = decision.basisEvaluationIds.map {
            store.requireImmutableRecord("EVALUATION", it)
            it to store.requireRecordVersionPins("EVALUATION", it)
        }
        val basisSessions = basisEvaluations.map { store.requireRecordSession("EVALUATION", it.first) }.distinct()
        require(basisSessions.size == 1) { "DECISION_BASIS_SESSION_MISMATCH" }
        store.requireSessionActive(basisSessions.single())
        val sessionPins = store.requireRecordVersionPins("SESSION", basisSessions.single())
        require(decision.policyVersionId == sessionPins.second) { "DECISION_POLICY_VERSION_MISMATCH" }
        require(basisEvaluations.all { it.second.second == decision.policyVersionId }) {
            "DECISION_BASIS_POLICY_VERSION_MISMATCH"
        }
        return store.appendEventAndImmutableRecord(
            eventId = decision.decisionId,
            recordType = "DECISION",
            recordId = decision.decisionId,
            canonicalPayload = canonicalPayload,
            createdAt = decision.createdAt,
            scopeSessionId = basisSessions.single(),
            scopeRuleVersionId = sessionPins.first,
            scopePolicyVersionId = sessionPins.second,
            outboxBasisEvaluationIds = decision.basisEvaluationIds
        )
    }

    /**
     * Server-side sync ingestion boundary. Validation happens before durable
     * mutation; the store then applies the complete batch atomically.
     */
    fun applySync(envelope: TrainingSyncEnvelope, session: TrainingSession, registry: TrainingVersionRegistry): SyncApplyResult {
        val validation = TrainingSyncValidator.validate(envelope, session, registry)
        if (!validation.accepted) {
            if (validation.rejections.contains(SyncRejectionCode.INVALID_SESSION)) {
                store.invalidateSessionIfPresent(session.sessionId)
            }
            require(false) { "SYNC_REJECTED:" + validation.rejections.joinToString(",") }
        }
        try {
            validatePinnedSession(session)
        } catch (e: IllegalArgumentException) {
            store.invalidateSessionIfPresent(session.sessionId)
            throw e
        }
        val normalizedEvents = envelope.events.map { event ->
            if (event.recordType in setOf("SESSION", "ATTEMPT", "EVIDENCE", "DECISION") && event.dogId.isBlank()) {
                event.copy(dogId = session.dogId)
            } else {
                event
            }
        }
        return store.appendSyncBatch(normalizedEvents)
    }

    override fun close() { store.close() }
}
