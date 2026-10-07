package com.rockyx.app.domain.training

import android.content.Context
import com.rockyx.app.domain.training.TrainingSyncDeliveryResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TrainingSyncOutboxTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    @After fun cleanup() {
        context.deleteDatabase("rockyx_training.db")
    }

    @Test fun sessionWriteCreatesAtomicOutboxWithDeterministicClientIdentity() {
        TrainingDurableStore(context).use { store ->
            store.appendSession(
                sessionId = "session-1",
                dogId = "dog-1",
                canonicalPayload = "session-payload",
                createdAt = 10L,
                ruleVersionId = "SIT:1",
                policyVersionId = "SIT_POLICY:1"
            )

            val entries = store.readPendingSyncOutbox()
            assertEquals(1, entries.size)
            assertEquals("SESSION:session-1", entries.single().clientGeneratedId)
            assertEquals("SESSION", entries.single().recordType)
            assertEquals("session-1", entries.single().recordId)
            assertEquals("SIT:1", entries.single().ruleVersionId)
            assertEquals("SIT_POLICY:1", entries.single().policyVersionId)
            assertTrue(entries.single().deliveryKey.startsWith("training:"))
            assertEquals("PENDING", entries.single().state)
        }
    }

    @Test fun adapterReusesSameDeliveryKeyAcrossRetryAndDoesNotResendSucceededRow() = runBlocking {
        var now = System.currentTimeMillis() + 60_000L
        var calls = 0
        val keys = mutableListOf<String>()
        var shouldSucceed = false

        TrainingDurableStore(context).use { store ->
            store.appendSession("session-2", "dog-1", "payload", 10L, "SIT:1", "SIT_POLICY:1")
            val adapter = TrainingSyncAdapter(
                store = store,
                sender = TrainingSyncSender { _, key ->
                    calls++
                    keys += key
                    if (shouldSucceed) {
                        TrainingSyncDeliveryResult(accepted = true)
                    } else {
                        TrainingSyncDeliveryResult(accepted = false, retryable = true, error = "NETWORK")
                    }
                },
                now = { now }
            )

            assertEquals(0, adapter.drain())
            val afterRetry = store.readPendingSyncOutbox(now = now + 15_001L)
            assertEquals(1, afterRetry.size)
            assertEquals(1, afterRetry.single().attemptCount)
            assertEquals("PENDING", afterRetry.single().state)

            shouldSucceed = true
            now += 15_001L
            assertEquals(1, adapter.drain())
            assertEquals(2, calls)
            assertEquals(keys[0], keys[1])
            assertEquals("SUCCEEDED", store.readSyncOutbox(afterRetry.single().id)?.state)

            now += 60_000L
            assertEquals(0, adapter.drain())
            assertEquals(2, calls)
        }
    }

    @Test fun outboxStateMachineRequiresPendingBeforeInFlightAndSucceeded() {
        TrainingDurableStore(context).use { store ->
            store.appendSession("session-3", "dog-1", "payload", 10L, "SIT:1", "SIT_POLICY:1")
            val entry = store.readPendingSyncOutbox().single()

            assertTrue(store.markSyncOutboxInFlight(entry.id))
            assertFalse(store.markSyncOutboxInFlight(entry.id))
            assertTrue(store.markSyncOutboxSucceeded(entry.id))
            assertFalse(store.markSyncOutboxRetry(entry.id, "late", 1_000_000L))
            assertEquals("SUCCEEDED", store.readSyncOutbox(entry.id)?.state)
        }
    }
}
