package com.rockyx.backend

import com.rockyx.backend.auth.AccessTokenVerifier
import com.rockyx.backend.auth.AuthenticatedPrincipal
import com.rockyx.backend.infra.InMemoryIdempotencyRepository
import com.rockyx.backend.training.TrainingSyncApplyResponse
import com.rockyx.backend.training.TrainingSyncEnvelopeRequest
import com.rockyx.backend.training.TrainingSyncEventRequest
import com.rockyx.backend.training.TrainingSyncRejectedException
import com.rockyx.backend.training.TrainingSyncTransportRepository
import com.rockyx.backend.training.registerTrainingSyncHttpRoute
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.testing.testApplication
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private fun Application.configureTrainingSyncTest(
    verifier: AccessTokenVerifier,
    repository: TrainingSyncTransportRepository,
    idempotency: InMemoryIdempotencyRepository
) {
    install(io.ktor.server.plugins.contentnegotiation.ContentNegotiation) { json() }
    routing {
        route("/api/v1") {
            registerTrainingSyncHttpRoute(verifier, repository, idempotency)
        }
    }
}

class TrainingSyncHttpRouteTest {
    private val json = Json { encodeDefaults = true }
    private val verifier = object : AccessTokenVerifier {
        override suspend fun verify(bearerToken: String): AuthenticatedPrincipal? =
            if (bearerToken == "valid-token") AuthenticatedPrincipal(
                "user-1", "test", scopes = setOf("sync:write")
            ) else null
    }

    private fun envelope() = TrainingSyncEnvelopeRequest(
        sessionId = "session-1",
        dogId = "dog-1",
        ruleVersionId = "SIT_AGGREGATION_V1_PROTOTYPE:1",
        policyVersionId = "SIT_POLICY:1",
        events = listOf(
            TrainingSyncEventRequest(
                clientGeneratedId = "event-1",
                recordType = "SESSION",
                recordId = "session-1",
                canonicalPayload = "{}",
                ruleVersionId = "SIT_AGGREGATION_V1_PROTOTYPE:1",
                policyVersionId = "SIT_POLICY:1",
                sessionId = "session-1",
                dogId = "dog-1"
            )
        )
    )

    @Test
    fun rejectsMissingAuthentication() = testApplication {
        application {
            configureTrainingSyncTest(verifier, fakeRepository(), InMemoryIdempotencyRepository())
        }
        val response = client.post("/api/v1/training/sync") {
            contentType(ContentType.Application.Json)
            header("Idempotency-Key", "idem-1")
            setBody(json.encodeToString(envelope()))
        }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun rejectsMissingIdempotencyKey() = testApplication {
        application {
            configureTrainingSyncTest(verifier, fakeRepository(), InMemoryIdempotencyRepository())
        }
        val response = client.post("/api/v1/training/sync") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer valid-token")
            setBody(json.encodeToString(envelope()))
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(response.bodyAsText().contains("INVALID_IDEMPOTENCY_KEY"))
    }

    @Test
    fun acceptsAuthenticatedEnvelopeAndPreservesTransportResponse() = testApplication {
        val repository = fakeRepository()
        application {
            configureTrainingSyncTest(verifier, repository, InMemoryIdempotencyRepository())
        }
        val response = client.post("/api/v1/training/sync") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer valid-token")
            header("Idempotency-Key", "idem-1")
            setBody(json.encodeToString(envelope()))
        }
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("event-1"))
        assertEquals("user-1", repository.userId)
        val replay = client.post("/api/v1/training/sync") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer valid-token")
            header("Idempotency-Key", "idem-1")
            setBody(json.encodeToString(envelope()))
        }
        assertEquals(HttpStatusCode.OK, replay.status)
        assertEquals(response.bodyAsText(), replay.bodyAsText())
    }

    @Test
    fun mapsInvalidSessionToConflict() = testApplication {
        val repository = object : TrainingSyncTransportRepository {
            override fun apply(
                userId: String,
                envelope: TrainingSyncEnvelopeRequest
            ): TrainingSyncApplyResponse =
                throw TrainingSyncRejectedException(listOf("INVALID_SESSION:session-1"))
        }
        application {
            install(io.ktor.server.plugins.contentnegotiation.ContentNegotiation) { json() }
            io.ktor.server.routing.routing {
                route("/api/v1") { registerTrainingSyncHttpRoute(verifier, repository, InMemoryIdempotencyRepository()) }
            }
        }
        val response = client.post("/api/v1/training/sync") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer valid-token")
            header("Idempotency-Key", "idem-1")
            setBody(json.encodeToString(envelope()))
        }
        assertEquals(HttpStatusCode.Conflict, response.status)
        assertTrue(response.bodyAsText().contains("TRAINING_SYNC_REJECTED"))
    }

    private fun fakeRepository() = object : TrainingSyncTransportRepository {
        var userId: String? = null
        override fun apply(
            userId: String,
            envelope: TrainingSyncEnvelopeRequest
        ): TrainingSyncApplyResponse {
            this.userId = userId
            return TrainingSyncApplyResponse(
                accepted = true,
                acceptedEventIds = envelope.events.map { it.clientGeneratedId }
            )
        }
    }
}
