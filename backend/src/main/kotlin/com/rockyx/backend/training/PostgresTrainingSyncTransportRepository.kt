package com.rockyx.backend.training

import javax.sql.DataSource
import java.sql.Connection
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val transportJson = Json { encodeDefaults = true; ignoreUnknownKeys = true }

class PostgresTrainingSyncTransportRepository(
    private val dataSource: DataSource
) : TrainingSyncTransportRepository {

    override fun apply(userId: String, envelope: TrainingSyncEnvelopeRequest): TrainingSyncApplyResponse =
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                applyInTransaction(connection, userId, envelope).also { connection.commit() }
            } catch (e: TrainingSyncRejectedException) {
                connection.rollback()
                throw e
            } catch (t: Throwable) {
                connection.rollback()
                throw t
            }
        }

    private fun applyInTransaction(
        c: Connection,
        userId: String,
        envelope: TrainingSyncEnvelopeRequest
    ): TrainingSyncApplyResponse {
        val envelopeErrors = TrainingSyncTransportPolicy.validateEnvelope(envelope)
        if (envelopeErrors.isNotEmpty()) throw TrainingSyncRejectedException(envelopeErrors)

        ensureAccount(c, userId)
        ensureDogOwned(c, userId, envelope.dogId)
        ensureVersionValid(c, "training_sync_rule_versions", envelope.ruleVersionId)
        ensureVersionValid(c, "training_sync_policy_versions", envelope.policyVersionId)

        val existingSession = loadSession(c, userId, envelope.sessionId)
        if (existingSession != null) {
            if (existingSession.status != "ACTIVE") {
                throw TrainingSyncRejectedException(listOf("SYNC_SESSION_INVALID:" + envelope.sessionId))
            }
            if (existingSession.dogId != envelope.dogId ||
                existingSession.ruleVersionId != envelope.ruleVersionId ||
                existingSession.policyVersionId != envelope.policyVersionId) {
                invalidateSession(c, userId, envelope.sessionId)
                c.commit()
                throw TrainingSyncRejectedException(listOf("INVALID_SESSION:" + envelope.sessionId))
            }
        }

        val accepted = mutableListOf<String>()
        val duplicates = mutableListOf<String>()

        for (event in TrainingSyncTransportPolicy.orderEvents(envelope.events)) {
            val existingClient = findClientEvent(c, userId, event.clientGeneratedId)
            if (existingClient != null) {
                val incomingHash = TrainingSyncTransportPolicy.identityHash(event)
                if (existingClient.identityHash != incomingHash) {
                    throw TrainingSyncRejectedException(
                        listOf("CLIENT_ID_REUSE_WITH_DIFFERENT_CONTENT:" + event.clientGeneratedId)
                    )
                }
                duplicates += event.clientGeneratedId
                continue
            }

            requireSessionAndEventIdentity(envelope, event)
            val session = loadSession(c, userId, envelope.sessionId)

            if (event.recordType == "SESSION") {
                if (session == null) {
                    insertSession(c, userId, envelope)
                } else if (session.recordId != event.recordId) {
                    throw TrainingSyncRejectedException(listOf("SESSION_RECORD_ID_CONFLICT:" + event.recordId))
                }
            } else {
                if (session == null) {
                    throw TrainingSyncRejectedException(listOf("SYNC_SESSION_NOT_FOUND:" + envelope.sessionId))
                }
                if (session.status != "ACTIVE") {
                    throw TrainingSyncRejectedException(listOf("SYNC_SESSION_INVALID:" + envelope.sessionId))
                }
                requireEventPins(event, session)
                requireReferences(c, userId, envelope, event, session)
            }

            ensureRecordIdAvailable(c, userId, event.recordType, event.recordId)

            if (event.recordType == "EVIDENCE" && event.supersedesRecordId != null) {
                requireEvidenceSupersede(c, userId, envelope.sessionId, envelope.dogId, event.supersedesRecordId)
            }

            insertRecord(c, userId, envelope, event)
            accepted += event.clientGeneratedId
        }

        return TrainingSyncApplyResponse(
            accepted = true,
            acceptedEventIds = accepted,
            duplicateEventIds = duplicates
        )
    }

    private data class SessionRow(
        val recordId: String,
        val dogId: String,
        val ruleVersionId: String,
        val policyVersionId: String,
        val status: String
    )

    private data class ClientEventRow(val identityHash: String)

    private data class RecordRow(
        val sessionId: String,
        val dogId: String?,
        val ruleVersionId: String,
        val policyVersionId: String,
        val evidenceStatus: String?,
        val supersedesRecordId: String?,
        val recordType: String
    )

    private fun ensureAccount(c: Connection, userId: String) {
        c.prepareStatement(
            "INSERT INTO accounts(user_id) VALUES (?) ON CONFLICT (user_id) DO NOTHING"
        ).use { ps ->
            ps.setString(1, userId)
            ps.executeUpdate()
        }
    }

    private fun ensureDogOwned(c: Connection, userId: String, dogId: String) {
        c.prepareStatement(
            "SELECT 1 FROM dogs WHERE dog_id=? AND owner_user_id=?"
        ).use { ps ->
            ps.setString(1, dogId)
            ps.setString(2, userId)
            ps.executeQuery().use { rs ->
                if (!rs.next()) throw TrainingSyncRejectedException(listOf("DOG_NOT_OWNED:" + dogId))
            }
        }
    }

    private fun ensureVersionValid(c: Connection, table: String, versionId: String) {
        c.prepareStatement("SELECT 1 FROM " + table + " WHERE version_id=? AND status='VALID'").use { ps ->
            ps.setString(1, versionId)
            ps.executeQuery().use { rs ->
                if (!rs.next()) throw TrainingSyncRejectedException(listOf("VERSION_INVALID:" + versionId))
            }
        }
    }

    private fun loadSession(c: Connection, userId: String, sessionId: String): SessionRow? =
        c.prepareStatement(
            """
            SELECT session_id,dog_id,rule_version_id,policy_version_id,status
            FROM training_sync_sessions
            WHERE session_id=? AND user_id=?
            """.trimIndent()
        ).use { ps ->
            ps.setString(1, sessionId)
            ps.setString(2, userId)
            ps.executeQuery().use { rs ->
                if (!rs.next()) null
                else SessionRow(
                    rs.getString(1),
                    rs.getString(2),
                    rs.getString(3),
                    rs.getString(4),
                    rs.getString(5)
                )
            }
        }

    private fun invalidateSession(c: Connection, userId: String, sessionId: String) {
        c.prepareStatement(
            "UPDATE training_sync_sessions SET status='INVALID',updated_at=now() WHERE session_id=? AND user_id=?"
        ).use { ps ->
            ps.setString(1, sessionId)
            ps.setString(2, userId)
            ps.executeUpdate()
        }
    }

    private fun insertSession(c: Connection, userId: String, envelope: TrainingSyncEnvelopeRequest) {
        c.prepareStatement(
            """
            INSERT INTO training_sync_sessions(
                session_id,user_id,dog_id,rule_version_id,policy_version_id,status
            ) VALUES (?,?,?,?,?,'ACTIVE')
            """.trimIndent()
        ).use { ps ->
            ps.setString(1, envelope.sessionId)
            ps.setString(2, userId)
            ps.setString(3, envelope.dogId)
            ps.setString(4, envelope.ruleVersionId)
            ps.setString(5, envelope.policyVersionId)
            ps.executeUpdate()
        }
    }

    private fun requireSessionAndEventIdentity(
        envelope: TrainingSyncEnvelopeRequest,
        event: TrainingSyncEventRequest
    ) {
        if (event.sessionId != envelope.sessionId) {
            throw TrainingSyncRejectedException(listOf("EVENT_SESSION_MISMATCH:" + event.recordId))
        }
        if (event.recordType == "SESSION" && event.recordId != envelope.sessionId) {
            throw TrainingSyncRejectedException(listOf("SESSION_RECORD_ID_MISMATCH:" + event.recordId))
        }
        if (event.recordType in setOf("SESSION", "ATTEMPT", "EVIDENCE", "DECISION") &&
            event.dogId.isNotBlank() && event.dogId != envelope.dogId) {
            throw TrainingSyncRejectedException(listOf("EVENT_DOG_MISMATCH:" + event.recordId))
        }
    }

    private fun requireEventPins(event: TrainingSyncEventRequest, session: SessionRow) {
        if (event.ruleVersionId != session.ruleVersionId ||
            event.policyVersionId != session.policyVersionId) {
            throw TrainingSyncRejectedException(listOf("EVENT_VERSION_MISMATCH:" + event.recordId))
        }
    }

    private fun requireReferences(
        c: Connection,
        userId: String,
        envelope: TrainingSyncEnvelopeRequest,
        event: TrainingSyncEventRequest,
        session: SessionRow
    ) {
        when (event.recordType) {
            "ATTEMPT" -> Unit

            "EVIDENCE" -> {
                val attempt = loadRecord(c, userId, "ATTEMPT", event.attemptId)
                    ?: throw TrainingSyncRejectedException(listOf("MISSING_ATTEMPT_REFERENCE:" + event.recordId))
                if (attempt.sessionId != envelope.sessionId || attempt.dogId != session.dogId) {
                    throw TrainingSyncRejectedException(listOf("EVIDENCE_ATTEMPT_SCOPE_MISMATCH:" + event.recordId))
                }
            }

            "EVALUATION" -> {
                event.attemptIds.forEach { id ->
                    val attempt = loadRecord(c, userId, "ATTEMPT", id)
                        ?: throw TrainingSyncRejectedException(listOf("MISSING_ATTEMPT_REFERENCE:" + id))
                    if (attempt.sessionId != envelope.sessionId || attempt.dogId != session.dogId) {
                        throw TrainingSyncRejectedException(listOf("EVALUATION_ATTEMPT_SCOPE_MISMATCH:" + id))
                    }
                }
                event.evidenceIds.forEach { id ->
                    val evidence = loadRecord(c, userId, "EVIDENCE", id)
                        ?: throw TrainingSyncRejectedException(listOf("MISSING_EVIDENCE_REFERENCE:" + id))
                    if (evidence.sessionId != envelope.sessionId || evidence.dogId != session.dogId) {
                        throw TrainingSyncRejectedException(listOf("EVALUATION_EVIDENCE_SCOPE_MISMATCH:" + id))
                    }
                }
                requireCanonicalActiveEvidenceIds(c, userId, envelope.sessionId, event.evidenceIds)
            }

            "DECISION" -> {
                if (event.basisEvaluationIds != event.basisEvaluationIds.sorted()) {
                    throw TrainingSyncRejectedException(listOf("BASIS_EVALUATION_IDS_NOT_SORTED:" + event.recordId))
                }
                event.basisEvaluationIds.forEach { id ->
                    val evaluation = loadRecord(c, userId, "EVALUATION", id)
                        ?: throw TrainingSyncRejectedException(listOf("MISSING_EVALUATION_REFERENCE:" + id))
                    if (evaluation.sessionId != envelope.sessionId ||
                        evaluation.policyVersionId != session.policyVersionId) {
                        throw TrainingSyncRejectedException(listOf("DECISION_BASIS_SCOPE_MISMATCH:" + id))
                    }
                }
            }
        }
    }

    private fun ensureRecordIdAvailable(c: Connection, userId: String, recordType: String, recordId: String) {
        c.prepareStatement(
            "SELECT 1 FROM training_sync_records WHERE user_id=? AND record_type=? AND record_id=?"
        ).use { ps ->
            ps.setString(1, userId)
            ps.setString(2, recordType)
            ps.setString(3, recordId)
            ps.executeQuery().use { rs ->
                if (rs.next()) {
                    throw TrainingSyncRejectedException(
                        listOf("SYNC_RECORD_ID_CONFLICT:" + recordType + ":" + recordId)
                    )
                }
            }
        }
    }

    private fun requireEvidenceSupersede(
        c: Connection,
        userId: String,
        sessionId: String,
        dogId: String,
        parentId: String
    ) {
        val parent = loadRecord(c, userId, "EVIDENCE", parentId)
            ?: throw TrainingSyncRejectedException(listOf("SUPERSEDED_EVIDENCE_NOT_FOUND:" + parentId))
        if (parent.sessionId != sessionId || parent.dogId != dogId) {
            throw TrainingSyncRejectedException(listOf("SUPERSEDED_EVIDENCE_SCOPE_MISMATCH:" + parentId))
        }
        c.prepareStatement(
            "SELECT 1 FROM training_sync_records WHERE user_id=? AND supersedes_record_id=?"
        ).use { ps ->
            ps.setString(1, userId)
            ps.setString(2, parentId)
            ps.executeQuery().use { rs ->
                if (rs.next()) throw TrainingSyncRejectedException(listOf("CONCURRENT_SUPERSEDE:" + parentId))
            }
        }
    }

    private fun loadRecord(
        c: Connection,
        userId: String,
        recordType: String,
        recordId: String
    ): RecordRow? =
        c.prepareStatement(
            """
            SELECT session_id,dog_id,rule_version_id,policy_version_id,
                   evidence_status,supersedes_record_id,record_type
            FROM training_sync_records
            WHERE user_id=? AND record_type=? AND record_id=?
            """.trimIndent()
        ).use { ps ->
            ps.setString(1, userId)
            ps.setString(2, recordType)
            ps.setString(3, recordId)
            ps.executeQuery().use { rs ->
                if (!rs.next()) null
                else RecordRow(
                    rs.getString(1),
                    rs.getString(2),
                    rs.getString(3),
                    rs.getString(4),
                    rs.getString(5),
                    rs.getString(6),
                    rs.getString(7)
                )
            }
        }

    private fun requireCanonicalActiveEvidenceIds(
        c: Connection,
        userId: String,
        sessionId: String,
        expected: List<String>
    ) {
        val valid = mutableListOf<String>()
        val superseded = mutableSetOf<String>()

        c.prepareStatement(
            """
            SELECT record_id,evidence_status,supersedes_record_id
            FROM training_sync_records
            WHERE user_id=? AND session_id=? AND record_type='EVIDENCE'
            """.trimIndent()
        ).use { ps ->
            ps.setString(1, userId)
            ps.setString(2, sessionId)
            ps.executeQuery().use { rs ->
                while (rs.next()) {
                    if (rs.getString(2) == "VALID") {
                        valid += rs.getString(1)
                        rs.getString(3)?.let { superseded += it }
                    }
                }
            }
        }

        val active = valid.filterNot { it in superseded }.sorted()
        if (expected.distinct().size != expected.size || active != expected.sorted()) {
            throw TrainingSyncRejectedException(listOf("CANONICAL_ACTIVE_EVIDENCE_MISMATCH:" + sessionId))
        }
    }

    private fun insertRecord(
        c: Connection,
        userId: String,
        envelope: TrainingSyncEnvelopeRequest,
        event: TrainingSyncEventRequest
    ) {
        val evidenceStatus = if (event.recordType == "EVIDENCE") {
            event.evidenceStatus ?: "VALID"
        } else null

        c.prepareStatement(
            """
            INSERT INTO training_sync_records(
                client_generated_id,user_id,record_type,record_id,session_id,dog_id,
                rule_version_id,policy_version_id,canonical_payload,supersedes_record_id,
                attempt_id,evidence_status,attempt_ids_json,evidence_ids_json,
                basis_evaluation_ids_json,identity_hash
            )
            VALUES (
                ?,?,?,?,?,?,?,?,CAST(? AS jsonb),?,?,?,CAST(? AS jsonb),CAST(? AS jsonb),CAST(? AS jsonb),?
            )
            """.trimIndent()
        ).use { ps ->
            var index = 1
            ps.setString(index++, event.clientGeneratedId)
            ps.setString(index++, userId)
            ps.setString(index++, event.recordType)
            ps.setString(index++, event.recordId)
            ps.setString(index++, envelope.sessionId)
            ps.setString(index++, event.dogId.ifBlank { envelope.dogId })
            ps.setString(index++, event.ruleVersionId)
            ps.setString(index++, event.policyVersionId)
            ps.setString(index++, event.canonicalPayload)
            ps.setString(index++, if (event.recordType == "EVIDENCE") event.supersedesRecordId else null)
            ps.setString(index++, event.attemptId.ifBlank { null })
            ps.setString(index++, evidenceStatus)
            ps.setString(index++, transportJson.encodeToString(event.attemptIds))
            ps.setString(index++, transportJson.encodeToString(event.evidenceIds))
            ps.setString(index++, transportJson.encodeToString(event.basisEvaluationIds))
            ps.setString(index, TrainingSyncTransportPolicy.identityHash(event))
            ps.executeUpdate()
        }
    }

    private fun findClientEvent(c: Connection, userId: String, clientGeneratedId: String): ClientEventRow? =
        c.prepareStatement(
            "SELECT identity_hash FROM training_sync_records WHERE user_id=? AND client_generated_id=?"
        ).use { ps ->
            ps.setString(1, userId)
            ps.setString(2, clientGeneratedId)
            ps.executeQuery().use { rs ->
                if (rs.next()) ClientEventRow(rs.getString(1)) else null
            }
        }
}
