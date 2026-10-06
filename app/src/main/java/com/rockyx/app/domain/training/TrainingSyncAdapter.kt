package com.rockyx.app.domain.training

data class TrainingSyncDeliveryResult(
    val accepted: Boolean,
    val retryable: Boolean = false,
    val error: String? = null
)

/** Transport-only boundary; it does not evaluate or mutate Gate 1 semantics. */
fun interface TrainingSyncSender {
    suspend fun send(envelope: TrainingSyncEnvelope, idempotencyKey: String): TrainingSyncDeliveryResult
}

class TrainingSyncAdapter(
    private val store: TrainingDurableStore,
    private val sender: TrainingSyncSender,
    private val now: () -> Long = { System.currentTimeMillis() }
) {
    suspend fun drain(limit: Int = 20): Int {
        require(limit in 1..200) { "OUTBOX_LIMIT_INVALID" }
        var delivered = 0
        store.readPendingSyncOutbox(limit = limit, now = now()).forEach { entry ->
            if (!store.markSyncOutboxInFlight(entry.id)) return@forEach

            val envelope = TrainingSyncEnvelope(
                sessionId = entry.sessionId,
                dogId = entry.dogId,
                ruleVersionId = entry.ruleVersionId,
                policyVersionId = entry.policyVersionId,
                events = listOf(entry.toSyncEvent())
            )

            val result = runCatching {
                sender.send(envelope, entry.deliveryKey)
            }.getOrElse { throwable ->
                TrainingSyncDeliveryResult(
                    accepted = false,
                    retryable = true,
                    error = throwable.message ?: throwable::class.simpleName ?: "TRAINING_SYNC_TRANSPORT_ERROR"
                )
            }

            if (result.accepted) {
                if (store.markSyncOutboxSucceeded(entry.id)) delivered++
            } else {
                val next = now() + retryDelayMillis(entry.attemptCount)
                store.markSyncOutboxRetry(
                    id = entry.id,
                    error = result.error ?: "TRAINING_SYNC_REJECTED",
                    nextAttemptAt = next
                )
            }
        }
        return delivered
    }

    private fun retryDelayMillis(attemptCount: Int): Long {
        val shift = attemptCount.coerceIn(0, 18)
        val delay = 15_000L * (1L shl shift)
        return delay.coerceAtMost(6 * 60 * 60 * 1000L)
    }
}
