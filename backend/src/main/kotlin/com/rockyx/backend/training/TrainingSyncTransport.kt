package com.rockyx.backend.training

import java.security.MessageDigest
import kotlinx.serialization.Serializable

@Serializable
data class TrainingSyncEventRequest(
    val clientGeneratedId: String,
    val recordType: String,
    val recordId: String,
    val canonicalPayload: String,
    val ruleVersionId: String,
    val policyVersionId: String,
    val supersedesRecordId: String? = null,
    val sessionId: String = "",
    val dogId: String = "",
    val attemptId: String = "",
    val evidenceIds: List<String> = emptyList(),
    val attemptIds: List<String> = emptyList(),
    val basisEvaluationIds: List<String> = emptyList(),
    val evidenceStatus: String? = null
)

@Serializable
data class TrainingSyncEnvelopeRequest(
    val sessionId: String,
    val dogId: String,
    val ruleVersionId: String,
    val policyVersionId: String,
    val events: List<TrainingSyncEventRequest>
)

@Serializable
data class TrainingSyncApplyResponse(
    val accepted: Boolean,
    val acceptedEventIds: List<String> = emptyList(),
    val duplicateEventIds: List<String> = emptyList(),
    val rejections: List<String> = emptyList()
)

class TrainingSyncRejectedException(val codes: List<String>) : IllegalArgumentException(codes.joinToString(","))

interface TrainingSyncTransportRepository {
    fun apply(userId: String, envelope: TrainingSyncEnvelopeRequest): TrainingSyncApplyResponse
}

object TrainingSyncTransportPolicy {
    const val MAX_EVENTS = 200
    const val MAX_PAYLOAD_BYTES = 64 * 1024
    const val MAX_TOTAL_PAYLOAD_BYTES = 2 * 1024 * 1024
    private val recordTypes = setOf("SESSION", "ATTEMPT", "EVIDENCE", "EVALUATION", "DECISION")

    fun validateEnvelope(envelope: TrainingSyncEnvelopeRequest): List<String> {
        val errors = mutableListOf<String>()
        if (envelope.sessionId.isBlank() || envelope.dogId.isBlank()) errors += "EMPTY_SESSION"
        if (envelope.ruleVersionId.isBlank() || envelope.policyVersionId.isBlank()) errors += "VERSION_MISMATCH"
        if (envelope.events.size > MAX_EVENTS) errors += "EVENT_COUNT_LIMIT"
        val totalPayload = envelope.events.sumOf { it.canonicalPayload.toByteArray(Charsets.UTF_8).size.toLong() }
        if (totalPayload > MAX_TOTAL_PAYLOAD_BYTES) errors += "TOTAL_PAYLOAD_LIMIT"
        envelope.events.forEach { event ->
            if (event.clientGeneratedId.isBlank()) errors += "EMPTY_EVENT_ID:" + event.recordId
            if (event.recordId.isBlank()) errors += "EMPTY_RECORD_ID"
            if (event.canonicalPayload.toByteArray(Charsets.UTF_8).size > MAX_PAYLOAD_BYTES) errors += "EVENT_PAYLOAD_LIMIT:" + event.recordId
            if (event.sessionId != envelope.sessionId) errors += "EVENT_SESSION_MISMATCH:" + event.recordId
            if (event.recordType == "SESSION" && event.recordId != envelope.sessionId) errors += "SESSION_RECORD_ID_MISMATCH:" + event.recordId
            if (event.recordType !in recordTypes) errors += "UNSUPPORTED_RECORD_TYPE:" + event.recordType
            if (event.recordType in setOf("SESSION", "ATTEMPT", "EVIDENCE", "DECISION") &&
                event.dogId.isNotBlank() && event.dogId != envelope.dogId) {
                errors += "EVENT_DOG_MISMATCH:" + event.recordId
            }
            if (event.recordType == "ATTEMPT" && event.dogId.isBlank()) errors += "ATTEMPT_DOG_REQUIRED:" + event.recordId
            if (event.recordType == "EVIDENCE" && event.attemptId.isBlank()) errors += "MISSING_ATTEMPT_REFERENCE:" + event.recordId
            if (event.recordType == "EVALUATION" && (event.attemptIds.isEmpty() || event.evidenceIds.isEmpty())) {
                errors += "MISSING_EVALUATION_REFERENCE:" + event.recordId
            }
            if (event.recordType == "DECISION" && event.basisEvaluationIds.isEmpty()) errors += "DECISION_BASIS_REQUIRED:" + event.recordId
            if (event.recordType == "DECISION" && event.basisEvaluationIds != event.basisEvaluationIds.sorted()) {
                errors += "BASIS_EVALUATION_IDS_NOT_SORTED:" + event.recordId
            }
            if (event.ruleVersionId != envelope.ruleVersionId || event.policyVersionId != envelope.policyVersionId) {
                errors += "EVENT_VERSION_MISMATCH:" + event.recordId
            }
            if (event.recordType == "EVIDENCE" && event.evidenceStatus != null &&
                event.evidenceStatus !in setOf("VALID", "INVALID")) {
                errors += "INVALID_EVIDENCE_STATUS:" + event.recordId
            }
        }
        return errors.distinct()
    }

    fun identityHash(event: TrainingSyncEventRequest): String {
        fun part(value: String): String = value.length.toString() + ":" + value
        fun list(values: List<String>): String = values.joinToString(prefix = "[", postfix = "]") { part(it) }
        val canonical = buildString {
            append("recordType=").append(part(event.recordType))
            append("|recordId=").append(part(event.recordId))
            append("|canonicalPayload=").append(part(event.canonicalPayload))
            append("|ruleVersionId=").append(part(event.ruleVersionId))
            append("|policyVersionId=").append(part(event.policyVersionId))
            append("|supersedesRecordId=").append(part(event.supersedesRecordId ?: ""))
            append("|sessionId=").append(part(event.sessionId))
            append("|dogId=").append(part(event.dogId))
            append("|attemptId=").append(part(event.attemptId))
            append("|evidenceIds=").append(list(event.evidenceIds))
            append("|attemptIds=").append(list(event.attemptIds))
            append("|basisEvaluationIds=").append(list(event.basisEvaluationIds))
            append("|evidenceStatus=").append(part(event.evidenceStatus ?: if (event.recordType == "EVIDENCE") "VALID" else ""))
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    fun orderEvents(events: List<TrainingSyncEventRequest>): List<TrainingSyncEventRequest> {
        val evidenceEvents = events.filter { it.recordType == "EVIDENCE" }
        val byRecordId = evidenceEvents.associateBy { it.recordId }
        val indegree = events.associate { it.clientGeneratedId to 0 }.toMutableMap()
        val children = events.associate { it.clientGeneratedId to mutableListOf<String>() }.toMutableMap()

        evidenceEvents.forEach { event ->
            val parent = event.supersedesRecordId?.let { byRecordId[it] }
            if (parent != null) {
                indegree[event.clientGeneratedId] = indegree.getValue(event.clientGeneratedId) + 1
                children.getValue(parent.clientGeneratedId).add(event.clientGeneratedId)
            }
        }

        val byClientId = events.associateBy { it.clientGeneratedId }
        val available = java.util.PriorityQueue<String>()
        indegree.filterValues { it == 0 }.keys.sorted().forEach(available::add)
        val topo = mutableListOf<TrainingSyncEventRequest>()

        while (available.isNotEmpty()) {
            val id = available.remove()
            topo += byClientId.getValue(id)
            children.getValue(id).sorted().forEach { childId ->
                val next = indegree.getValue(childId) - 1
                indegree[childId] = next
                if (next == 0) available.add(childId)
            }
        }

        val base = if (topo.size == events.size) topo else events.sortedBy { it.clientGeneratedId }
        val evidenceRank = base.filter { it.recordType == "EVIDENCE" }
            .withIndex()
            .associate { it.value.clientGeneratedId to it.index }

        return base.sortedWith(
            compareBy<TrainingSyncEventRequest>(
                { rank(it.recordType) },
                { evidenceRank[it.clientGeneratedId] ?: Int.MAX_VALUE },
                { it.clientGeneratedId }
            )
        )
    }

    private fun rank(recordType: String): Int = when (recordType) {
        "SESSION" -> 0
        "ATTEMPT" -> 1
        "EVIDENCE" -> 2
        "EVALUATION" -> 3
        "DECISION" -> 4
        else -> 99
    }
}
