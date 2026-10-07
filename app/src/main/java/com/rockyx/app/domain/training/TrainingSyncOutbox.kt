package com.rockyx.app.domain.training

import android.database.Cursor
import org.json.JSONArray

data class TrainingSyncOutboxEntry(
    val id: Long,
    val deliveryKey: String,
    val clientGeneratedId: String,
    val recordType: String,
    val recordId: String,
    val sessionId: String,
    val dogId: String,
    val canonicalPayload: String,
    val ruleVersionId: String,
    val policyVersionId: String,
    val supersedesRecordId: String?,
    val attemptId: String,
    val attemptIds: List<String>,
    val evidenceIds: List<String>,
    val basisEvaluationIds: List<String>,
    val evidenceStatus: EvidenceStatus?,
    val state: String,
    val attemptCount: Int,
    val nextAttemptAt: Long,
    val lastError: String?
) {
    fun toSyncEvent(): TrainingSyncEvent =
        TrainingSyncEvent(
            clientGeneratedId = clientGeneratedId,
            recordType = recordType,
            recordId = recordId,
            canonicalPayload = canonicalPayload,
            ruleVersionId = ruleVersionId,
            policyVersionId = policyVersionId,
            supersedesRecordId = supersedesRecordId,
            sessionId = sessionId,
            dogId = dogId,
            attemptId = attemptId,
            evidenceIds = evidenceIds,
            attemptIds = attemptIds,
            basisEvaluationIds = basisEvaluationIds,
            evidenceStatus = evidenceStatus
        )

    companion object {
        fun fromCursor(cursor: Cursor): TrainingSyncOutboxEntry {
            fun strings(column: String): List<String> {
                val raw = cursor.getString(cursor.getColumnIndexOrThrow(column))
                val array = JSONArray(raw)
                return buildList {
                    for (i in 0 until array.length()) add(array.getString(i))
                }
            }
            val evidenceStatusValue = cursor.getString(cursor.getColumnIndexOrThrow("evidence_status"))
            return TrainingSyncOutboxEntry(
                id = cursor.getLong(cursor.getColumnIndexOrThrow("id")),
                deliveryKey = cursor.getString(cursor.getColumnIndexOrThrow("delivery_key")),
                clientGeneratedId = cursor.getString(cursor.getColumnIndexOrThrow("client_generated_id")),
                recordType = cursor.getString(cursor.getColumnIndexOrThrow("record_type")),
                recordId = cursor.getString(cursor.getColumnIndexOrThrow("record_id")),
                sessionId = cursor.getString(cursor.getColumnIndexOrThrow("session_id")),
                dogId = cursor.getString(cursor.getColumnIndexOrThrow("dog_id")),
                canonicalPayload = cursor.getString(cursor.getColumnIndexOrThrow("canonical_payload")),
                ruleVersionId = cursor.getString(cursor.getColumnIndexOrThrow("rule_version_id")),
                policyVersionId = cursor.getString(cursor.getColumnIndexOrThrow("policy_version_id")),
                supersedesRecordId = if (cursor.isNull(cursor.getColumnIndexOrThrow("supersedes_record_id"))) null else cursor.getString(cursor.getColumnIndexOrThrow("supersedes_record_id")),
                attemptId = cursor.getString(cursor.getColumnIndexOrThrow("attempt_id")),
                attemptIds = strings("attempt_ids_json"),
                evidenceIds = strings("evidence_ids_json"),
                basisEvaluationIds = strings("basis_evaluation_ids_json"),
                evidenceStatus = evidenceStatusValue?.let { runCatching { EvidenceStatus.valueOf(it) }.getOrNull() },
                state = cursor.getString(cursor.getColumnIndexOrThrow("state")),
                attemptCount = cursor.getInt(cursor.getColumnIndexOrThrow("attempt_count")),
                nextAttemptAt = cursor.getLong(cursor.getColumnIndexOrThrow("next_attempt_at")),
                lastError = if (cursor.isNull(cursor.getColumnIndexOrThrow("last_error"))) null else cursor.getString(cursor.getColumnIndexOrThrow("last_error"))
            )
        }
    }
}
