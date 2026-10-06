from pathlib import Path

root = Path("rockyx-src/rev14src")
tracked = Path("backend/src")
target = root / "backend/src"

transport_src = tracked / "main/kotlin/com/rockyx/backend/training"
transport_dst = target / "main/kotlin/com/rockyx/backend/training"
transport_dst.mkdir(parents=True, exist_ok=True)
for source in transport_src.glob("*.kt"):
    destination = transport_dst / source.name
    destination.write_text(source.read_text(encoding="utf-8"), encoding="utf-8")

migration_src = tracked / "main/resources/db/migration/V12__training_sync_transport.sql"
migration_dst = root / "backend/src/main/resources/db/migration/V12__training_sync_transport.sql"
migration_dst.write_text(migration_src.read_text(encoding="utf-8"), encoding="utf-8")

test_src = tracked / "test/kotlin/com/rockyx/backend/TrainingSyncTransportPolicyTest.kt"
test_dst = root / "backend/src/test/kotlin/com/rockyx/backend/TrainingSyncTransportPolicyTest.kt"
test_dst.parent.mkdir(parents=True, exist_ok=True)
test_dst.write_text(test_src.read_text(encoding="utf-8"), encoding="utf-8")

integration_src = tracked / "test/kotlin/com/rockyx/backend/TrainingSyncTransportPostgresIntegrationTest.kt"
integration_dst = root / "backend/src/test/kotlin/com/rockyx/backend/TrainingSyncTransportPostgresIntegrationTest.kt"
integration_dst.write_text(integration_src.read_text(encoding="utf-8"), encoding="utf-8")

routes = target / "main/kotlin/com/rockyx/backend/api/Routes.kt"
text = routes.read_text(encoding="utf-8")

anchor = "import com.rockyx.backend.ai.*\n"
if text.count(anchor) != 1:
    raise SystemExit("Routes import anchor mismatch")
text = text.replace(anchor, anchor + "import com.rockyx.backend.training.*\n", 1)

signature_anchor = "    ecosystem: EcosystemRepository = InMemoryEcosystemRepository()\n"
if text.count(signature_anchor) != 1:
    raise SystemExit("Routes signature anchor mismatch")
text = text.replace(signature_anchor, signature_anchor.rstrip("\n") + ",\n    trainingSync: TrainingSyncTransportRepository? = null\n", 1)

route_anchor = '''        post("/submissions") {
'''
if text.count(route_anchor) != 1:
    raise SystemExit("Routes insertion anchor mismatch")

route = '''        post("/training/sync") {
            val principal = call.requirePrincipal(verifier) ?: return@post call.respond(
                HttpStatusCode.Unauthorized,
                ApiError("AUTH_REQUIRED", "Authentication required", call.requestId(), category = FailureCategory.AUTH)
            )
            if (!call.requireScope(principal, "sync:write")) return@post

            val transport = trainingSync ?: return@post call.respond(
                HttpStatusCode.ServiceUnavailable,
                ApiError("TRAINING_SYNC_NOT_CONFIGURED", "Training sync transport is not configured", call.requestId(), category = FailureCategory.SYNC)
            )

            val idempotencyKey = call.request.headers["Idempotency-Key"]
            if (!SecurityPolicy.validIdempotencyKey(idempotencyKey)) return@post call.respond(
                HttpStatusCode.BadRequest,
                ApiError("INVALID_IDEMPOTENCY_KEY", "Invalid Idempotency-Key", call.requestId(), category = FailureCategory.SYNC)
            )

            val body = runCatching { call.receive<TrainingSyncEnvelopeRequest>() }.getOrElse {
                return@post call.respond(
                    HttpStatusCode.BadRequest,
                    ApiError("INVALID_TRAINING_SYNC_REQUEST", "Invalid training sync envelope", call.requestId(), category = FailureCategory.SYNC)
                )
            }

            val validationErrors = TrainingSyncTransportPolicy.validateEnvelope(body)
            if (validationErrors.isNotEmpty()) return@post call.respond(
                HttpStatusCode.BadRequest,
                ApiError(
                    "INVALID_TRAINING_SYNC_REQUEST",
                    "Training sync envelope rejected",
                    call.requestId(),
                    category = FailureCategory.SYNC,
                    details = mapOf("rejections" to validationErrors.joinToString("|"))
                )
            )

            val key = idempotencyKey!!
            idempotency.get(principal.userId, key)?.let { cached ->
                return@post call.respondText(cached, ContentType.Application.Json)
            }

            val response = try {
                transport.apply(principal.userId, body)
            } catch (e: TrainingSyncRejectedException) {
                return@post call.respond(
                    HttpStatusCode.Conflict,
                    ApiError(
                        "TRAINING_SYNC_REJECTED",
                        "Training sync was rejected",
                        call.requestId(),
                        retryable = false,
                        category = FailureCategory.SYNC,
                        details = mapOf("rejections" to e.codes.joinToString("|"))
                    )
                )
            }

            val responseJson = json.encodeToString(response)
            idempotency.put(principal.userId, key, responseJson)
            telemetry.count("training.sync.accepted")
            call.respond(response)
        }

'''
text = text.replace(route_anchor, route + route_anchor, 1)
routes.write_text(text, encoding="utf-8")

application = target / "main/kotlin/com/rockyx/backend/Application.kt"
text = application.read_text(encoding="utf-8")
anchor = "import com.rockyx.backend.observability.*\n"
if text.count(anchor) != 1:
    raise SystemExit("Application import anchor mismatch")
text = text.replace(anchor, anchor + "import com.rockyx.backend.training.*\n", 1)

ds_anchor = "        val ds = database.dataSource()\n"
if text.count(ds_anchor) != 1:
    raise SystemExit("Application data source anchor mismatch")
text = text.replace(ds_anchor, ds_anchor + "        val trainingSyncTransport = PostgresTrainingSyncTransportRepository(ds)\n", 1)

call_anchor = "PostgresEcosystemRepository(ds))"
if text.count(call_anchor) != 1:
    raise SystemExit("Application route call anchor mismatch")
text = text.replace(call_anchor, "PostgresEcosystemRepository(ds), trainingSync = trainingSyncTransport)", 1)

application.write_text(text, encoding="utf-8")

print("Production training sync transport overlay applied.")
# Gate2 transport overlay includes PostgreSQL integration diagnostics.
# Gate2 parameter-binding fix included in tracked repository overlay.
# Gate2 canonical JSON parameter position fix included.
