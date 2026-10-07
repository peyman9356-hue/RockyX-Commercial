package com.rockyx.backend

import com.rockyx.backend.training.TrainingSyncEnvelopeRequest
import com.rockyx.backend.training.TrainingSyncEventRequest
import com.rockyx.backend.training.TrainingSyncTransportPolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TrainingSyncTransportPolicyTest {
    private fun event(
        clientId: String = "event-1",
        recordType: String = "SESSION",
        recordId: String = "session-1",
        payload: String = "{}",
        sessionId: String = "session-1",
        dogId: String = "dog-1",
        rule: String = "SIT_AGGREGATION_V1_PROTOTYPE:1",
        policy: String = "SIT_POLICY:1"
    ) = TrainingSyncEventRequest(
        clientGeneratedId = clientId,
        recordType = recordType,
        recordId = recordId,
        canonicalPayload = payload,
        ruleVersionId = rule,
        policyVersionId = policy,
        sessionId = sessionId,
        dogId = dogId
    )

    private fun envelope(events: List<TrainingSyncEventRequest>) = TrainingSyncEnvelopeRequest(
        sessionId = "session-1",
        dogId = "dog-1",
        ruleVersionId = "SIT_AGGREGATION_V1_PROTOTYPE:1",
        policyVersionId = "SIT_POLICY:1",
        events = events
    )

    @Test
    fun rejectsUnsupportedRecordType() {
        val errors = TrainingSyncTransportPolicy.validateEnvelope(
            envelope(listOf(event(recordType = "UNKNOWN")))
        )
        assertTrue(errors.any { it.startsWith("UNSUPPORTED_RECORD_TYPE") })
    }

    @Test
    fun rejectsEventVersionMismatch() {
        val errors = TrainingSyncTransportPolicy.validateEnvelope(
            envelope(listOf(event(rule = "OTHER:1")))
        )
        assertTrue(errors.any { it.startsWith("EVENT_VERSION_MISMATCH") })
    }

    @Test
    fun identityHashIsStableAndSensitiveToCanonicalPayload() {
        val a = event(payload = "{}")
        val b = event(payload = "{}")
        val c = event(payload = """{"changed":true}""")
        assertEquals(
            TrainingSyncTransportPolicy.identityHash(a),
            TrainingSyncTransportPolicy.identityHash(b)
        )
        assertTrue(
            TrainingSyncTransportPolicy.identityHash(a) !=
                TrainingSyncTransportPolicy.identityHash(c)
        )
    }

    @Test
    fun orderingPreservesSessionBeforeDependentRecords() {
        val attempt = event(
            clientId = "b",
            recordType = "ATTEMPT",
            recordId = "attempt-1",
            sessionId = "session-1"
        )
        val session = event(clientId = "a")
        val ordered = TrainingSyncTransportPolicy.orderEvents(listOf(attempt, session))
        assertEquals(listOf("SESSION", "ATTEMPT"), ordered.map { it.recordType })
    }
}
