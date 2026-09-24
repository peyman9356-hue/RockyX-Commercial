package com.rockyx.app.domain.training

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TrainingDurableStoreTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val dbFile: File = context.getDatabasePath("rockyx_training.db")

    @After fun cleanup() {
        context.deleteDatabase("rockyx_training.db")
    }

    @Test fun versionsSurviveStoreReopenAndRequireExactPins() {
        TrainingDurableStore(context).use { store ->
            store.registerRule(RuleVersion("SIT", "1", "VALID", "sit-rule"))
            store.registerPolicy(PolicyVersion("SIT_POLICY", "1", "VALID", "policy"))
        }
        TrainingDurableStore(context).use { reopened ->
            assertEquals("1", reopened.requireRule("SIT:1").version)
            assertEquals("1", reopened.requirePolicy("SIT_POLICY:1").version)
            assertThrows(IllegalArgumentException::class.java) { reopened.requireRule("1") }
            assertThrows(IllegalArgumentException::class.java) { reopened.requirePolicy("SIT_POLICY") }
        }
        assertTrue(dbFile.exists())
    }

    @Test fun clientEventIsDurablyIdempotentAndRejectsContentReuse() {
        TrainingDurableStore(context).use { store ->
            assertTrue(store.acceptClientEvent("evt-1", "canonical-A"))
        }
        TrainingDurableStore(context).use { reopened ->
            assertFalse(reopened.acceptClientEvent("evt-1", "canonical-A"))
            assertThrows(IllegalArgumentException::class.java) { reopened.acceptClientEvent("evt-1", "canonical-B") }
        }
    }

    @Test fun evidenceIsAppendOnlyAndConcurrentSupersedeIsRejected() {
        TrainingDurableStore(context).use { store ->
            store.appendEvidence("e1", "evidence-1", 1L)
            store.appendEvidence("e2", "evidence-2", 2L, "e1")
            assertThrows(IllegalArgumentException::class.java) {
                store.appendEvidence("e3", "evidence-3", 3L, "e1")
            }
            assertEquals("evidence-1", store.readImmutable("EVIDENCE", "e1"))
        }
    }

    @Test fun evaluationAndDecisionAreImmutable() {
        TrainingDurableStore(context).use { store ->
            store.appendImmutable("EVALUATION", "eval-1", "evaluation", 1L)
            store.appendImmutable("DECISION", "decision-1", "decision", 2L)
            assertThrows(android.database.SQLException::class.java) {
                store.appendImmutable("EVALUATION", "eval-1", "changed", 3L)
            }
            assertThrows(android.database.SQLException::class.java) {
                store.appendImmutable("DECISION", "decision-1", "changed", 4L)
            }
        }
    }
}
