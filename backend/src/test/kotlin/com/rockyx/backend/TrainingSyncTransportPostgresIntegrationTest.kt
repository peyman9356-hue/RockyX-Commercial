package com.rockyx.backend

import com.rockyx.backend.infra.db.Database
import com.rockyx.backend.infra.db.DatabaseConfig
import com.rockyx.backend.training.*
import java.sql.Connection
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

class TrainingSyncTransportPostgresIntegrationTest {

    @Test
    fun authenticatedBoundaryPersistsAndDeduplicatesEvents() {
        val config = DatabaseConfig.fromEnvironment() ?: return
        val database = Database(config)
        database.migrate()
        val ds = database.dataSource()
        val userId = "transport-test-" + UUID.randomUUID()
        val dogId = "dog-" + UUID.randomUUID()
        val sessionId = "session-" + UUID.randomUUID()

        try {
            insertDog(ds.connection, userId, dogId)
            val repository = PostgresTrainingSyncTransportRepository(ds)
            val request = envelope(
                sessionId,
                dogId,
                listOf(sessionEvent(sessionId, dogId), attemptEvent(sessionId, dogId))
            )

            val first = repository.apply(userId, request)
            assertTrue(first.accepted)
            assertEquals(2, first.acceptedEventIds.size)
            assertTrue(first.duplicateEventIds.isEmpty())

            val second = applyWithDiagnostics(repository, userId, request)
            assertTrue(second.accepted)
            assertEquals(setOf("session-event", "attempt-event"), second.duplicateEventIds.toSet())
            assertTrue(second.acceptedEventIds.isEmpty())
        } finally {
            deleteAccount(ds.connection, userId)
            database.close()
        }
    }

    @Test
    fun pinConflictDurablyInvalidatesSession() {
        val config = DatabaseConfig.fromEnvironment() ?: return
        val database = Database(config)
        database.migrate()
        val ds = database.dataSource()
        val userId = "transport-test-" + UUID.randomUUID()
        val dogId = "dog-" + UUID.randomUUID()
        val sessionId = "session-" + UUID.randomUUID()

        try {
            insertDog(ds.connection, userId, dogId)
            ds.connection.use { c ->
                c.prepareStatement(
                    "INSERT INTO training_sync_policy_versions(version_id,status) VALUES (?, 'VALID') ON CONFLICT (version_id) DO NOTHING"
                ).use { ps ->
                    ps.setString(1, "SIT_POLICY:2")
                    ps.executeUpdate()
                }
            }

            val repository = PostgresTrainingSyncTransportRepository(ds)
            val initial = envelope(sessionId, dogId, listOf(sessionEvent(sessionId, dogId)))
            repository.apply(userId, initial)

            val mismatched = initial.copy(
                policyVersionId = "SIT_POLICY:2",
                events = listOf(sessionEvent(sessionId, dogId, policy = "SIT_POLICY:2"))
            )

            assertRejected(repository, userId, mismatched, "INVALID_SESSION:")
            ds.connection.use { c ->
                c.prepareStatement(
                    "SELECT status FROM training_sync_sessions WHERE session_id=? AND user_id=?"
                ).use { ps ->
                    ps.setString(1, sessionId)
                    ps.setString(2, userId)
                    ps.executeQuery().use { rs ->
                        assertTrue(rs.next())
                        assertEquals("INVALID", rs.getString(1))
                    }
                }
            }

            assertRejected(repository, userId, initial, "SYNC_SESSION_INVALID:")
        } finally {
            deleteAccount(ds.connection, userId)
            database.close()
        }
    }

    @Test
    fun canonicalEvidenceAndInvalidSupersederRulesArePreserved() {
        val config = DatabaseConfig.fromEnvironment() ?: return
        val database = Database(config)
        database.migrate()
        val ds = database.dataSource()
        val userId = "transport-test-" + UUID.randomUUID()
        val dogId = "dog-" + UUID.randomUUID()
        val sessionId = "session-" + UUID.randomUUID()

        try {
            insertDog(ds.connection, userId, dogId)
            val repository = PostgresTrainingSyncTransportRepository(ds)

            repository.apply(
                userId,
                envelope(
                    sessionId,
                    dogId,
                    listOf(sessionEvent(sessionId, dogId), attemptEvent(sessionId, dogId))
                )
            )

            repository.apply(
                userId,
                envelope(
                    sessionId,
                    dogId,
                    listOf(evidenceEvent("evidence-1", "evidence-event-1", sessionId, dogId))
                )
            )

            val validEvaluation = evaluationEvent(
                "evaluation-1",
                "evaluation-event-1",
                sessionId,
                dogId,
                listOf("evidence-1")
            )
            assertTrue(repository.apply(userId, envelope(sessionId, dogId, listOf(validEvaluation))).accepted)

            val invalidChild = evidenceEvent(
                "evidence-2",
                "evidence-event-2",
                sessionId,
                dogId,
                status = "INVALID",
                supersedes = "evidence-1"
            )
            assertTrue(repository.apply(userId, envelope(sessionId, dogId, listOf(invalidChild))).accepted)

            val afterInvalidCorrection = evaluationEvent(
                "evaluation-2",
                "evaluation-event-2",
                sessionId,
                dogId,
                listOf("evidence-1")
            )
            assertTrue(repository.apply(userId, envelope(sessionId, dogId, listOf(afterInvalidCorrection))).accepted)

            val missingActiveEvidence = evaluationEvent(
                "evaluation-3",
                "evaluation-event-3",
                sessionId,
                dogId,
                emptyList()
            )
            assertRejected(
                repository,
                userId,
                envelope(sessionId, dogId, listOf(missingActiveEvidence)),
                "MISSING_EVALUATION_REFERENCE:"
            )
        } finally {
            deleteAccount(ds.connection, userId)
            database.close()
        }
    }

    private fun sessionEvent(
        sessionId: String,
        dogId: String,
        policy: String = "SIT_POLICY:1"
    ) = TrainingSyncEventRequest(
        clientGeneratedId = "session-event",
        recordType = "SESSION",
        recordId = sessionId,
        canonicalPayload = "{}",
        ruleVersionId = "SIT_AGGREGATION_V1_PROTOTYPE:1",
        policyVersionId = policy,
        sessionId = sessionId,
        dogId = dogId
    )

    private fun attemptEvent(sessionId: String, dogId: String) = TrainingSyncEventRequest(
        clientGeneratedId = "attempt-event",
        recordType = "ATTEMPT",
        recordId = "attempt-1",
        canonicalPayload = "{}",
        ruleVersionId = "SIT_AGGREGATION_V1_PROTOTYPE:1",
        policyVersionId = "SIT_POLICY:1",
        sessionId = sessionId,
        dogId = dogId
    )

    private fun evidenceEvent(
        evidenceId: String,
        clientId: String,
        sessionId: String,
        dogId: String,
        status: String = "VALID",
        supersedes: String? = null
    ) = TrainingSyncEventRequest(
        clientGeneratedId = clientId,
        recordType = "EVIDENCE",
        recordId = evidenceId,
        canonicalPayload = "{}",
        ruleVersionId = "SIT_AGGREGATION_V1_PROTOTYPE:1",
        policyVersionId = "SIT_POLICY:1",
        supersedesRecordId = supersedes,
        sessionId = sessionId,
        dogId = dogId,
        attemptId = "attempt-1",
        evidenceStatus = status
    )

    private fun evaluationEvent(
        evaluationId: String,
        clientId: String,
        sessionId: String,
        dogId: String,
        evidenceIds: List<String>
    ) = TrainingSyncEventRequest(
        clientGeneratedId = clientId,
        recordType = "EVALUATION",
        recordId = evaluationId,
        canonicalPayload = "{}",
        ruleVersionId = "SIT_AGGREGATION_V1_PROTOTYPE:1",
        policyVersionId = "SIT_POLICY:1",
        sessionId = sessionId,
        dogId = dogId,
        attemptIds = listOf("attempt-1"),
        evidenceIds = evidenceIds
    )

    private fun envelope(
        sessionId: String,
        dogId: String,
        events: List<TrainingSyncEventRequest>,
        rule: String = "SIT_AGGREGATION_V1_PROTOTYPE:1",
        policy: String = "SIT_POLICY:1"
    ) = TrainingSyncEnvelopeRequest(sessionId, dogId, rule, policy, events)

    private fun assertRejected(
        repository: PostgresTrainingSyncTransportRepository,
        userId: String,
        envelope: TrainingSyncEnvelopeRequest,
        expectedPrefix: String
    ) {
        try {
            repository.apply(userId, envelope)
            fail("Expected rejection starting with " + expectedPrefix)
        } catch (e: TrainingSyncRejectedException) {
            assertTrue(
                e.codes.any { it.startsWith(expectedPrefix) },
                "Actual rejections: " + e.codes
            )
        }
    }

    private fun insertDog(connection: Connection, userId: String, dogId: String) {
        connection.use { c ->
            c.prepareStatement(
                "INSERT INTO accounts(user_id) VALUES (?) ON CONFLICT (user_id) DO NOTHING"
            ).use { ps ->
                ps.setString(1, userId)
                ps.executeUpdate()
            }
            c.prepareStatement(
                "INSERT INTO dogs(dog_id,owner_user_id,name) VALUES (?,?,?)"
            ).use { ps ->
                ps.setString(1, dogId)
                ps.setString(2, userId)
                ps.setString(3, "Transport Test Dog")
                ps.executeUpdate()
            }
        }
    }

    private fun deleteAccount(connection: Connection, userId: String) {
        connection.use { c ->
            c.prepareStatement("DELETE FROM accounts WHERE user_id=?").use { ps ->
                ps.setString(1, userId)
                ps.executeUpdate()
            }
        }
    }
}
