package com.rockyx.backend.training

import com.rockyx.backend.api.SecurityPolicy
import com.rockyx.backend.infra.IdempotencyRepository
import com.rockyx.backend.api.requestId
import com.rockyx.backend.auth.AccessTokenVerifier
import com.rockyx.backend.auth.requirePrincipal
import com.rockyx.backend.domain.ApiError
import com.rockyx.backend.domain.FailureCategory
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Production HTTP adapter for the Gate 1 Training Sync transport.
 * Domain/persistence semantics remain owned by TrainingSyncTransportRepository.
 */
private val trainingSyncJson = Json { encodeDefaults = true; ignoreUnknownKeys = true }

fun Route.registerTrainingSyncHttpRoute(
    verifier: AccessTokenVerifier,
    transport: TrainingSyncTransportRepository,
    idempotency: IdempotencyRepository
) {
    route("/training") {
        post("/sync") {
            val principal = call.requirePrincipal(verifier)
                ?: return@post call.respond(
                    HttpStatusCode.Unauthorized,
                    ApiError("AUTH_REQUIRED", "Authentication required", call.requestId(), category = FailureCategory.AUTH)
                )

            if (!principal.hasScope("sync:write")) {
                return@post call.respond(
                    HttpStatusCode.Forbidden,
                    ApiError("INSUFFICIENT_SCOPE", "Insufficient permissions", call.requestId(), category = FailureCategory.AUTH)
                )
            }

            val idempotencyKey = call.request.headers["Idempotency-Key"]
            if (!SecurityPolicy.validIdempotencyKey(idempotencyKey)) {
                return@post call.respond(
                    HttpStatusCode.BadRequest,
                    ApiError("INVALID_IDEMPOTENCY_KEY", "Invalid Idempotency-Key", call.requestId(), category = FailureCategory.CONTENT)
                )
            }

            val envelope = try {
                call.receive<TrainingSyncEnvelopeRequest>()
            } catch (_: Exception) {
                return@post call.respond(
                    HttpStatusCode.BadRequest,
                    ApiError("INVALID_TRAINING_SYNC_REQUEST", "Invalid training sync request", call.requestId(), category = FailureCategory.SYNC)
                )
            }

            val errors = TrainingSyncTransportPolicy.validateEnvelope(envelope)
            if (errors.isNotEmpty()) {
                return@post call.respond(
                    HttpStatusCode.BadRequest,
                    ApiError(
                        "INVALID_TRAINING_SYNC_REQUEST",
                        "Training sync envelope rejected",
                        call.requestId(),
                        category = FailureCategory.SYNC,
                        details = mapOf("rejections" to errors.joinToString("|"))
                    )
                )
            }

            val key = idempotencyKey!!
            idempotency.get(principal.userId, key)?.let { cached ->
                return@post call.respondText(cached, ContentType.Application.Json)
            }

            try {
                val response = transport.apply(principal.userId, envelope)
                idempotency.put(principal.userId, key, trainingSyncJson.encodeToString(response))
                call.respond(response)
            } catch (e: TrainingSyncRejectedException) {
                val status = when {
                    e.codes.any { it.startsWith("INVALID_SESSION:") || it.startsWith("SYNC_SESSION_INVALID:") } -> HttpStatusCode.Conflict
                    e.codes.any { it.startsWith("DOG_NOT_OWNED:") } -> HttpStatusCode.Forbidden
                    e.codes.any { it.startsWith("VERSION_INVALID:") } -> HttpStatusCode.Conflict
                    e.codes.any { it.startsWith("CLIENT_ID_REUSE_WITH_DIFFERENT_CONTENT:") } -> HttpStatusCode.Conflict
                    else -> HttpStatusCode.UnprocessableEntity
                }
                call.respond(
                    status,
                    ApiError(
                        "TRAINING_SYNC_REJECTED",
                        "Training sync rejected",
                        call.requestId(),
                        category = FailureCategory.SYNC,
                        details = mapOf("rejections" to e.codes.joinToString("|"))
                    )
                )
            }
        }
    }
}
