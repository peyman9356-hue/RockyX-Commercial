package com.rockyx.app.domain.training

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import org.robolectric.RuntimeEnvironment
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
    private val context: Context = RuntimeEnvironment.getApplication()
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
    @Test fun legacyV3DataFailsClosedAfterMigration() {
        val db = context.openOrCreateDatabase("rockyx_training.db", Context.MODE_PRIVATE, null)
        db.execSQL("CREATE TABLE rule_versions (rule_id TEXT NOT NULL, version TEXT NOT NULL, status TEXT NOT NULL, definition TEXT NOT NULL, PRIMARY KEY(rule_id, version))")
        db.execSQL("CREATE TABLE policy_versions (policy_id TEXT NOT NULL, version TEXT NOT NULL, status TEXT NOT NULL, definition TEXT NOT NULL, PRIMARY KEY(policy_id, version))")
        db.execSQL("CREATE TABLE client_events (client_generated_id TEXT PRIMARY KEY, content_hash TEXT NOT NULL, identity_hash TEXT NOT NULL)")
        db.execSQL("CREATE TABLE immutable_records (record_type TEXT NOT NULL, record_id TEXT NOT NULL, payload_hash TEXT NOT NULL, payload TEXT NOT NULL, created_at INTEGER NOT NULL, PRIMARY KEY(record_type, record_id))")
        db.execSQL("CREATE TABLE evidence_successors (parent_id TEXT PRIMARY KEY, child_id TEXT NOT NULL UNIQUE)")
        db.execSQL("CREATE TABLE record_scopes (record_type TEXT NOT NULL, record_id TEXT NOT NULL, session_id TEXT NOT NULL, dog_id TEXT, PRIMARY KEY(record_type, record_id))")
        db.execSQL("INSERT INTO rule_versions VALUES ('LEGACY','1','VALID','legacy')")
        db.execSQL("INSERT INTO policy_versions VALUES ('LEGACY_POLICY','1','VALID','legacy')")
        db.execSQL("INSERT INTO client_events VALUES ('legacy-event','legacy-payload','legacy-payload')")
        db.execSQL("INSERT INTO immutable_records VALUES ('SESSION','legacy-session','hash','legacy-session',1)")
        db.execSQL("INSERT INTO record_scopes VALUES ('SESSION','legacy-session','legacy-session','d1')")
        db.execSQL("PRAGMA user_version=3")
        db.close()

        TrainingDurableStore(context).use { migrated ->
            assertThrows(IllegalArgumentException::class.java) {
                migrated.requireRecordVersionPins("SESSION","legacy-session")
            }
            assertThrows(IllegalArgumentException::class.java) {
                migrated.acceptClientEvent("legacy-event","legacy-payload")
            }
        }

        TrainingPersistenceGateway(context).use { g ->
            val attempt = TrainingAttempt(
                "legacy-attempt","legacy-session",1,emptyList(),"d1",2L,"legacy-attempt"
            )
            assertThrows(IllegalArgumentException::class.java) {
                g.appendAttempt(attempt,"legacy-attempt")
            }
        }
    }


}
