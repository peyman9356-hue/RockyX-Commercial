package com.rockyx.app.domain.training

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteOpenHelper
import java.security.MessageDigest
import java.util.UUID
import org.json.JSONArray

class TrainingDurableStore(context: Context) : SQLiteOpenHelper(context.applicationContext, "rockyx_training.db", null, 6) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("PRAGMA foreign_keys=ON")
        db.execSQL("CREATE TABLE rule_versions (rule_id TEXT NOT NULL, version TEXT NOT NULL, status TEXT NOT NULL, definition TEXT NOT NULL, PRIMARY KEY(rule_id, version))")
        db.execSQL("CREATE TABLE policy_versions (policy_id TEXT NOT NULL, version TEXT NOT NULL, status TEXT NOT NULL, definition TEXT NOT NULL, PRIMARY KEY(policy_id, version))")
        db.execSQL("CREATE TABLE client_events (client_generated_id TEXT PRIMARY KEY, content_hash TEXT NOT NULL, identity_hash TEXT NOT NULL, legacy_identity INTEGER NOT NULL DEFAULT 0)")
        db.execSQL("CREATE TABLE immutable_records (record_type TEXT NOT NULL, record_id TEXT NOT NULL, payload_hash TEXT NOT NULL, payload TEXT NOT NULL, created_at INTEGER NOT NULL, PRIMARY KEY(record_type, record_id))")
        db.execSQL("CREATE TABLE evidence_successors (parent_id TEXT PRIMARY KEY, child_id TEXT NOT NULL UNIQUE)")
        db.execSQL("CREATE TABLE record_scopes (record_type TEXT NOT NULL, record_id TEXT NOT NULL, session_id TEXT NOT NULL, dog_id TEXT, rule_version_id TEXT, policy_version_id TEXT, evidence_status TEXT, supersedes_record_id TEXT, session_status TEXT, PRIMARY KEY(record_type, record_id))")
        createTrainingSyncOutbox(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE client_events ADD COLUMN identity_hash TEXT")
            db.execSQL("UPDATE client_events SET identity_hash = content_hash WHERE identity_hash IS NULL")
        }
        if (oldVersion < 3) {
            db.execSQL("CREATE TABLE IF NOT EXISTS record_scopes (record_type TEXT NOT NULL, record_id TEXT NOT NULL, session_id TEXT NOT NULL, dog_id TEXT, PRIMARY KEY(record_type, record_id))")
        }
        if (oldVersion < 4) {
            db.execSQL("ALTER TABLE record_scopes ADD COLUMN rule_version_id TEXT")
            db.execSQL("ALTER TABLE record_scopes ADD COLUMN policy_version_id TEXT")
            db.execSQL("ALTER TABLE record_scopes ADD COLUMN evidence_status TEXT")
            db.execSQL("ALTER TABLE record_scopes ADD COLUMN supersedes_record_id TEXT")
        }
        if (oldVersion < 5) {
            db.execSQL("ALTER TABLE client_events ADD COLUMN legacy_identity INTEGER NOT NULL DEFAULT 1")
            db.execSQL("ALTER TABLE record_scopes ADD COLUMN session_status TEXT")
        }
        if (oldVersion < 6) {
            createTrainingSyncOutbox(db)
        }
    }

    fun registerRule(version: RuleVersion) {
        validateVersion(version.version)
        writableDatabase.insertOrThrow("rule_versions", null, ContentValues().apply {
            put("rule_id", version.ruleId); put("version", version.version)
            put("status", version.status); put("definition", version.definition)
        })
    }

    fun registerPolicy(version: PolicyVersion) {
        validateVersion(version.version)
        writableDatabase.insertOrThrow("policy_versions", null, ContentValues().apply {
            put("policy_id", version.policyId); put("version", version.version)
            put("status", version.status); put("definition", version.definition)
        })
    }

    /**
     * Resolves the newest VALID version currently cached locally.
     * This is used only when creating a new offline session; once the returned
     * exact ID is pinned into a session, validation remains exact and immutable.
     */
    fun requireLatestValidRule(ruleId: String): RuleVersion {
        require(ruleId.isNotBlank()) { "RULE_ID_REQUIRED" }
        return readableDatabase.query(
            "rule_versions", arrayOf("rule_id", "version", "status", "definition"),
            "rule_id=? AND status='VALID'", arrayOf(ruleId), null, null, null
        ).use { cursor ->
            var selected: RuleVersion? = null
            while (cursor.moveToNext()) {
                val candidate = RuleVersion(cursor.getString(0), cursor.getString(1), cursor.getString(2), cursor.getString(3))
                if (selected == null || compareVersion(candidate.version, selected!!.version) > 0) selected = candidate
            }
            requireNotNull(selected) { "No valid locally cached RuleVersion: $ruleId" }
        }
    }

    fun requireRule(exactId: String): RuleVersion {
        val parts = exactId.split(":", limit = 2)
        require(parts.size == 2 && parts[0].isNotBlank() && parts[1].isNotBlank()) { "RULE_PIN_MUST_BE_EXACT" }
        readableDatabase.query(
            "rule_versions", arrayOf("rule_id", "version", "status", "definition"),
            "rule_id=? AND version=? AND status='VALID'", arrayOf(parts[0], parts[1]), null, null, null
        ).use {
            require(it.moveToFirst()) { "Unknown or invalid RuleVersion: $exactId" }
            return RuleVersion(it.getString(0), it.getString(1), it.getString(2), it.getString(3))
        }
    }

    /**
     * Resolves the newest VALID version currently cached locally.
     * This is used only when creating a new offline session; once the returned
     * exact ID is pinned into a session, validation remains exact and immutable.
     */
    fun requireLatestValidPolicy(policyId: String): PolicyVersion {
        require(policyId.isNotBlank()) { "POLICY_ID_REQUIRED" }
        return readableDatabase.query(
            "policy_versions", arrayOf("policy_id", "version", "status", "definition"),
            "policy_id=? AND status='VALID'", arrayOf(policyId), null, null, null
        ).use { cursor ->
            var selected: PolicyVersion? = null
            while (cursor.moveToNext()) {
                val candidate = PolicyVersion(cursor.getString(0), cursor.getString(1), cursor.getString(2), cursor.getString(3))
                if (selected == null || compareVersion(candidate.version, selected!!.version) > 0) selected = candidate
            }
            requireNotNull(selected) { "No valid locally cached PolicyVersion: $policyId" }
        }
    }

    fun requirePolicy(exactId: String): PolicyVersion {
        val parts = exactId.split(":", limit = 2)
        require(parts.size == 2 && parts[0].isNotBlank() && parts[1].isNotBlank()) { "POLICY_PIN_MUST_BE_EXACT" }
        readableDatabase.query(
            "policy_versions", arrayOf("policy_id", "version", "status", "definition"),
            "policy_id=? AND version=? AND status='VALID'", arrayOf(parts[0], parts[1]), null, null, null
        ).use {
            require(it.moveToFirst()) { "Unknown or invalid PolicyVersion: $exactId" }
            return PolicyVersion(it.getString(0), it.getString(1), it.getString(2), it.getString(3))
        }
    }

    fun acceptClientEvent(clientGeneratedId: String, canonicalPayload: String): Boolean {
        require(clientGeneratedId.isNotBlank())
        val hash = sha256(canonicalPayload)
        val db = writableDatabase
        db.query("client_events", arrayOf("content_hash", "legacy_identity"), "client_generated_id=?", arrayOf(clientGeneratedId), null, null, null).use {
            if (it.moveToFirst()) {
                require(it.getInt(1) == 0) { "LEGACY_CLIENT_ID_IDENTITY_UNVERIFIED:$clientGeneratedId" }
                require(it.getString(0) == hash) { "CLIENT_ID_REUSE_WITH_DIFFERENT_CONTENT:$clientGeneratedId" }
                return false
            }
        }
        db.insertOrThrow("client_events", null, ContentValues().apply {
            put("client_generated_id", clientGeneratedId); put("content_hash", hash); put("identity_hash", hash); put("legacy_identity", 0)
        })
        return true
    }

    fun appendSession(
        sessionId: String,
        dogId: String,
        canonicalPayload: String,
        createdAt: Long,
        ruleVersionId: String,
        policyVersionId: String,
        sessionStatus: SessionStatus = SessionStatus.ACTIVE
    ) {
        appendImmutable(
            "SESSION", sessionId, canonicalPayload, createdAt,
            sessionId = sessionId, dogId = dogId,
            ruleVersionId = ruleVersionId, policyVersionId = policyVersionId,
            sessionStatus = sessionStatus,
            outboxClientGeneratedId = "SESSION:" + sessionId
        )
    }

    fun appendAttempt(
        attemptId: String,
        clientGeneratedId: String,
        canonicalPayload: String,
        createdAt: Long,
        sessionId: String,
        dogId: String,
        ruleVersionId: String? = null,
        policyVersionId: String? = null,
        evidenceIds: List<String> = emptyList()
    ): Boolean {
        return appendEventAndImmutableRecord(
            eventId = clientGeneratedId,
            recordType = "ATTEMPT",
            recordId = attemptId,
            canonicalPayload = canonicalPayload,
            createdAt = createdAt,
            scopeSessionId = sessionId,
            scopeDogId = dogId,
            scopeRuleVersionId = ruleVersionId,
            scopePolicyVersionId = policyVersionId,
            outboxEvidenceIds = evidenceIds
        )
    }

    fun appendImmutable(
        recordType: String,
        recordId: String,
        canonicalPayload: String,
        createdAt: Long,
        sessionId: String? = null,
        dogId: String? = null,
        ruleVersionId: String? = null,
        policyVersionId: String? = null,
        evidenceStatus: EvidenceStatus? = null,
        supersedesRecordId: String? = null,
        sessionStatus: SessionStatus? = null,
        outboxClientGeneratedId: String? = null,
        outboxAttemptId: String = "",
        outboxAttemptIds: List<String> = emptyList(),
        outboxEvidenceIds: List<String> = emptyList(),
        outboxBasisEvaluationIds: List<String> = emptyList()
    ) {
        require(recordType.isNotBlank() && recordId.isNotBlank())
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.insertOrThrow("immutable_records", null, ContentValues().apply {
                put("record_type", recordType); put("record_id", recordId)
                put("payload_hash", sha256(canonicalPayload)); put("payload", canonicalPayload); put("created_at", createdAt)
            })
            if (sessionId != null) {
                putRecordScope(
                    db, recordType, recordId, sessionId, dogId,
                    ruleVersionId, policyVersionId, evidenceStatus, supersedesRecordId, sessionStatus
                )
            }
            if (outboxClientGeneratedId != null) {
                enqueueOutboxInTransaction(
                    db = db,
                    clientGeneratedId = outboxClientGeneratedId,
                    recordType = recordType,
                    recordId = recordId,
                    sessionId = requireNotNull(sessionId),
                    dogId = requireNotNull(dogId),
                    canonicalPayload = canonicalPayload,
                    ruleVersionId = requireNotNull(ruleVersionId),
                    policyVersionId = requireNotNull(policyVersionId),
                    supersedesRecordId = supersedesRecordId,
                    attemptId = outboxAttemptId,
                    attemptIds = outboxAttemptIds,
                    evidenceIds = outboxEvidenceIds,
                    basisEvaluationIds = outboxBasisEvaluationIds,
                    evidenceStatus = evidenceStatus
                )
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
    fun appendEventAndImmutableRecord(
        eventId: String,
        recordType: String,
        recordId: String,
        canonicalPayload: String,
        createdAt: Long,
        supersedesEvidenceId: String? = null,
        scopeSessionId: String? = null,
        scopeDogId: String? = null,
        scopeRuleVersionId: String? = null,
        scopePolicyVersionId: String? = null,
        evidenceStatus: EvidenceStatus? = null,
        outboxAttemptId: String = "",
        outboxAttemptIds: List<String> = emptyList(),
        outboxEvidenceIds: List<String> = emptyList(),
        outboxBasisEvaluationIds: List<String> = emptyList()
    ): Boolean {
        require(eventId.isNotBlank() && recordType.isNotBlank() && recordId.isNotBlank())
        val hash = sha256(canonicalPayload)
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.query("client_events", arrayOf("content_hash", "legacy_identity"), "client_generated_id=?", arrayOf(eventId), null, null, null).use {
                if (it.moveToFirst()) {
                    require(it.getInt(1) == 0) { "LEGACY_CLIENT_ID_IDENTITY_UNVERIFIED:$eventId" }
                    require(it.getString(0) == hash) { "CLIENT_ID_REUSE_WITH_DIFFERENT_CONTENT:$eventId" }
                    return false
                }
            }
            if (recordType == "EVIDENCE" && supersedesEvidenceId != null) {
                val exists = db.query("immutable_records", arrayOf("record_id"), "record_type='EVIDENCE' AND record_id=?", arrayOf(supersedesEvidenceId), null, null, null).use { it.moveToFirst() }
                require(exists) { "Superseded evidence must already exist." }
                val successorExists = db.query("evidence_successors", arrayOf("child_id"), "parent_id=?", arrayOf(supersedesEvidenceId), null, null, null).use { it.moveToFirst() }
                require(!successorExists) { "CONCURRENT_SUPERSEDE:$supersedesEvidenceId" }
                db.insertOrThrow("evidence_successors", null, ContentValues().apply {
                    put("parent_id", supersedesEvidenceId); put("child_id", recordId)
                })
            }
            db.insertOrThrow("client_events", null, ContentValues().apply {
                put("client_generated_id", eventId); put("content_hash", hash); put("identity_hash", hash); put("legacy_identity", 0)
            })
            db.insertOrThrow("immutable_records", null, ContentValues().apply {
                put("record_type", recordType); put("record_id", recordId)
                put("payload_hash", hash); put("payload", canonicalPayload); put("created_at", createdAt)
            })
            if (scopeSessionId != null) {
                putRecordScope(
                    db, recordType, recordId, scopeSessionId, scopeDogId,
                    scopeRuleVersionId, scopePolicyVersionId,
                    evidenceStatus,
                    if (recordType == "EVIDENCE") supersedesEvidenceId else null
                )
                // Only complete client-side Gateway writes enter the Outbox.
                // Low-level store callers used by existing sync-ingestion fixtures
                // may intentionally omit outbound transport metadata.
                if (scopeDogId != null && scopeRuleVersionId != null && scopePolicyVersionId != null) {
                    enqueueOutboxInTransaction(
                    db = db,
                    clientGeneratedId = eventId,
                    recordType = recordType,
                    recordId = recordId,
                    sessionId = scopeSessionId,
                    dogId = requireNotNull(scopeDogId),
                    canonicalPayload = canonicalPayload,
                    ruleVersionId = requireNotNull(scopeRuleVersionId),
                    policyVersionId = requireNotNull(scopePolicyVersionId),
                    supersedesRecordId = if (recordType == "EVIDENCE") supersedesEvidenceId else null,
                    attemptId = outboxAttemptId,
                    attemptIds = outboxAttemptIds,
                    evidenceIds = outboxEvidenceIds,
                    basisEvaluationIds = outboxBasisEvaluationIds,
                    evidenceStatus = evidenceStatus
                )
            }
            db.setTransactionSuccessful()
            return true
        } finally { db.endTransaction() }
    }

    fun appendEvidence(evidenceId: String, canonicalPayload: String, createdAt: Long, supersedesEvidenceId: String? = null) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            if (supersedesEvidenceId != null) {
                val exists = db.query("immutable_records", arrayOf("record_id"), "record_type='EVIDENCE' AND record_id=?", arrayOf(supersedesEvidenceId), null, null, null).use { it.moveToFirst() }
                require(exists) { "Superseded evidence must already exist." }
                val successorExists = db.query("evidence_successors", arrayOf("child_id"), "parent_id=?", arrayOf(supersedesEvidenceId), null, null, null).use { it.moveToFirst() }
                require(!successorExists) { "CONCURRENT_SUPERSEDE:$supersedesEvidenceId" }
                db.insertOrThrow("evidence_successors", null, ContentValues().apply {
                    put("parent_id", supersedesEvidenceId); put("child_id", evidenceId)
                })
            }
            db.insertOrThrow("immutable_records", null, ContentValues().apply {
                put("record_type", "EVIDENCE"); put("record_id", evidenceId)
                put("payload_hash", sha256(canonicalPayload)); put("payload", canonicalPayload); put("created_at", createdAt)
            })
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    private fun createTrainingSyncOutbox(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS training_sync_outbox (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                delivery_key TEXT NOT NULL UNIQUE,
                client_generated_id TEXT NOT NULL UNIQUE,
                record_type TEXT NOT NULL,
                record_id TEXT NOT NULL,
                session_id TEXT NOT NULL,
                dog_id TEXT NOT NULL,
                canonical_payload TEXT NOT NULL,
                rule_version_id TEXT NOT NULL,
                policy_version_id TEXT NOT NULL,
                supersedes_record_id TEXT,
                attempt_id TEXT NOT NULL DEFAULT '',
                attempt_ids_json TEXT NOT NULL DEFAULT '[]',
                evidence_ids_json TEXT NOT NULL DEFAULT '[]',
                basis_evaluation_ids_json TEXT NOT NULL DEFAULT '[]',
                evidence_status TEXT,
                state TEXT NOT NULL,
                attempt_count INTEGER NOT NULL DEFAULT 0,
                next_attempt_at INTEGER NOT NULL,
                last_error TEXT,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_training_sync_outbox_ready ON training_sync_outbox(state, next_attempt_at)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_training_sync_outbox_session ON training_sync_outbox(session_id)")
    }

    private fun enqueueOutboxInTransaction(
        db: SQLiteDatabase,
        clientGeneratedId: String,
        recordType: String,
        recordId: String,
        sessionId: String,
        dogId: String,
        canonicalPayload: String,
        ruleVersionId: String,
        policyVersionId: String,
        supersedesRecordId: String?,
        attemptId: String,
        attemptIds: List<String>,
        evidenceIds: List<String>,
        basisEvaluationIds: List<String>,
        evidenceStatus: EvidenceStatus?
    ) {
        require(clientGeneratedId.isNotBlank()) { "OUTBOX_CLIENT_ID_REQUIRED" }
        val now = System.currentTimeMillis()
        db.insertOrThrow("training_sync_outbox", null, ContentValues().apply {
            put("delivery_key", "training:" + UUID.randomUUID().toString())
            put("client_generated_id", clientGeneratedId)
            put("record_type", recordType)
            put("record_id", recordId)
            put("session_id", sessionId)
            put("dog_id", dogId)
            put("canonical_payload", canonicalPayload)
            put("rule_version_id", ruleVersionId)
            put("policy_version_id", policyVersionId)
            if (supersedesRecordId != null) put("supersedes_record_id", supersedesRecordId)
            put("attempt_id", attemptId)
            put("attempt_ids_json", JSONArray(attemptIds).toString())
            put("evidence_ids_json", JSONArray(evidenceIds).toString())
            put("basis_evaluation_ids_json", JSONArray(basisEvaluationIds).toString())
            if (evidenceStatus != null) put("evidence_status", evidenceStatus.name)
            put("state", "PENDING")
            put("attempt_count", 0)
            put("next_attempt_at", now)
            put("created_at", now)
            put("updated_at", now)
        })
    }

    fun readPendingSyncOutbox(limit: Int = 50, now: Long = System.currentTimeMillis()): List<TrainingSyncOutboxEntry> {
        require(limit in 1..200) { "OUTBOX_LIMIT_INVALID" }
        return readableDatabase.query(
            "training_sync_outbox",
            null,
            "state='PENDING' AND next_attempt_at<=?",
            arrayOf(now.toString()),
            null, null, "id ASC",
            limit.toString()
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(TrainingSyncOutboxEntry.fromCursor(cursor))
            }
        }
    }

    fun markSyncOutboxInFlight(id: Long): Boolean =
        writableDatabase.compileStatement(
            "UPDATE training_sync_outbox SET state='IN_FLIGHT', updated_at=? WHERE id=? AND state='PENDING'"
        ).apply {
            bindLong(1, System.currentTimeMillis())
            bindLong(2, id)
        }.executeUpdateDelete() == 1

    fun markSyncOutboxSucceeded(id: Long): Boolean =
        writableDatabase.compileStatement(
            "UPDATE training_sync_outbox SET state='SUCCEEDED', updated_at=?, last_error=NULL WHERE id=? AND state='IN_FLIGHT'"
        ).apply {
            bindLong(1, System.currentTimeMillis())
            bindLong(2, id)
        }.executeUpdateDelete() == 1

    fun markSyncOutboxRetry(id: Long, error: String, nextAttemptAt: Long): Boolean =
        writableDatabase.compileStatement(
            "UPDATE training_sync_outbox SET state='PENDING', attempt_count=attempt_count+1, next_attempt_at=?, last_error=?, updated_at=? WHERE id=? AND state='IN_FLIGHT'"
        ).apply {
            bindLong(1, nextAttemptAt)
            bindString(2, error.take(1000))
            bindLong(3, System.currentTimeMillis())
            bindLong(4, id)
        }.executeUpdateDelete() == 1

    fun readSyncOutbox(id: Long): TrainingSyncOutboxEntry? =
        readableDatabase.query(
            "training_sync_outbox",
            null,
            "id=?",
            arrayOf(id.toString()),
            null, null, null
        ).use { if (it.moveToFirst()) TrainingSyncOutboxEntry.fromCursor(it) else null }

    fun requireImmutableRecord(recordType: String, recordId: String) {
        require(recordType.isNotBlank() && recordId.isNotBlank())
        val exists = readableDatabase.query("immutable_records", arrayOf("record_id"), "record_type=? AND record_id=?", arrayOf(recordType, recordId), null, null, null).use { it.moveToFirst() }
        require(exists) { "UNKNOWN_IMMUTABLE_RECORD:$recordType:$recordId" }
    }

    fun readImmutable(recordType: String, recordId: String): String? =
        readableDatabase.query("immutable_records", arrayOf("payload"), "record_type=? AND record_id=?", arrayOf(recordType, recordId), null, null, null).use {
            if (it.moveToFirst()) it.getString(0) else null
        }

    /** Applies a sync batch atomically after envelope validation. */
    fun appendSyncBatch(events: List<TrainingSyncEvent>): SyncApplyResult {
        val ordered = orderSyncEvents(events)
        val accepted = mutableListOf<String>()
        val duplicates = mutableListOf<String>()
        val db = writableDatabase
        db.beginTransaction()
        try {
            ordered.forEach { event ->
                require(event.clientGeneratedId.isNotBlank()) { "EMPTY_EVENT_ID" }
                require(event.recordId.isNotBlank()) { "EMPTY_RECORD_ID" }
                require(event.recordType in setOf("SESSION", "ATTEMPT", "EVIDENCE", "EVALUATION", "DECISION")) {
                    "UNSUPPORTED_RECORD_TYPE:" + event.recordType
                }
                val payloadHash = sha256(event.canonicalPayload)
                val identityHash = syncEventIdentityHash(event)
                val existingHashes = db.query(
                    "client_events",
                    arrayOf("content_hash", "identity_hash", "legacy_identity"),
                    "client_generated_id=?",
                    arrayOf(event.clientGeneratedId),
                    null, null, null
                ).use { cursor ->
                    if (cursor.moveToFirst()) Triple(cursor.getString(0), cursor.getString(1), cursor.getInt(2)) else null
                }
                if (existingHashes != null) {
                    require(existingHashes.third == 0) {
                        "LEGACY_CLIENT_ID_IDENTITY_UNVERIFIED:" + event.clientGeneratedId
                    }
                    val existingIdentityHash = existingHashes.second ?: existingHashes.first
                    require(existingIdentityHash == identityHash) {
                        "CLIENT_ID_REUSE_WITH_DIFFERENT_CONTENT:" + event.clientGeneratedId
                    }
                    duplicates += event.clientGeneratedId
                    return@forEach
                }
                if (event.recordType != "SESSION") {
                    requireSessionActive(db, event.sessionId)
                    val sessionExists = db.query(
                        "immutable_records",
                        arrayOf("record_id"),
                        "record_type='SESSION' AND record_id=?",
                        arrayOf(event.sessionId),
                        null, null, null
                    ).use { it.moveToFirst() }
                    require(sessionExists) { "SYNC_SESSION_NOT_FOUND:" + event.recordId }
                }

                val persistedSessionPins = if (event.recordType == "SESSION") {
                    event.ruleVersionId to event.policyVersionId
                } else {
                    requireRecordVersionPins(db, "SESSION", event.sessionId)
                }
                if (event.recordType != "SESSION") {
                    require(event.ruleVersionId == persistedSessionPins.first) {
                        "SYNC_SESSION_RULE_VERSION_MISMATCH:" + event.recordId
                    }
                    require(event.policyVersionId == persistedSessionPins.second) {
                        "SYNC_SESSION_POLICY_VERSION_MISMATCH:" + event.recordId
                    }
                }

                val sessionDogId = if (event.recordType == "SESSION") {
                    require(event.dogId.isNotBlank()) { "SYNC_EVENT_DOG_REQUIRED:" + event.recordId }
                    event.dogId
                } else {
                    requireRecordDog(db, "SESSION", event.sessionId)
                }

                when (event.recordType) {
                    "ATTEMPT" -> {
                        requireImmutable(db, "SESSION", event.sessionId)
                        requireScopeMatches(db, "SESSION", event.sessionId, event.sessionId)
                        require(event.dogId.isNotBlank()) { "SYNC_EVENT_DOG_REQUIRED:" + event.recordId }
                        require(event.dogId == sessionDogId) { "SYNC_EVENT_DOG_MISMATCH:" + event.recordId }
                    }
                    "EVIDENCE" -> {
                        require(event.attemptId.isNotBlank()) { "SYNC_EVIDENCE_ATTEMPT_REQUIRED:" + event.recordId }
                        requireImmutable(db, "ATTEMPT", event.attemptId)
                        requireScopeMatches(db, "ATTEMPT", event.attemptId, event.sessionId)
                        require(event.dogId.isNotBlank()) { "SYNC_EVENT_DOG_REQUIRED:" + event.recordId }
                        require(event.dogId == sessionDogId) { "SYNC_EVENT_DOG_MISMATCH:" + event.recordId }
                        requireScopeDogMatches(db, "ATTEMPT", event.attemptId, sessionDogId)
                    }
                    "EVALUATION" -> {
                        require(event.attemptIds.isNotEmpty()) { "SYNC_EVALUATION_ATTEMPTS_REQUIRED:" + event.recordId }
                        require(event.evidenceIds.isNotEmpty()) { "SYNC_EVALUATION_EVIDENCE_REQUIRED:" + event.recordId }
                        event.attemptIds.forEach {
                            requireImmutable(db, "ATTEMPT", it)
                            requireScopeMatches(db, "ATTEMPT", it, event.sessionId)
                            requireScopeDogMatches(db, "ATTEMPT", it, sessionDogId)
                        }
                        event.evidenceIds.forEach {
                            requireImmutable(db, "EVIDENCE", it)
                            requireScopeMatches(db, "EVIDENCE", it, event.sessionId)
                            requireScopeDogMatches(db, "EVIDENCE", it, sessionDogId)
                        }
                        requireCanonicalActiveEvidenceIds(db, event.sessionId, event.evidenceIds)
                    }
                    "DECISION" -> {
                        require(event.basisEvaluationIds.isNotEmpty()) { "DECISION_BASIS_REQUIRED:" + event.recordId }
                        require(event.basisEvaluationIds == event.basisEvaluationIds.sorted()) { "BASIS_EVALUATION_IDS_NOT_SORTED" }
                        require(event.dogId.isNotBlank()) { "SYNC_EVENT_DOG_REQUIRED:" + event.recordId }
                        require(event.dogId == sessionDogId) { "SYNC_EVENT_DOG_MISMATCH:" + event.recordId }
                        require(event.policyVersionId == persistedSessionPins.second) {
                            "SYNC_DECISION_POLICY_VERSION_MISMATCH:" + event.recordId
                        }
                        event.basisEvaluationIds.forEach {
                            requireImmutable(db, "EVALUATION", it)
                            requireScopeMatches(db, "EVALUATION", it, event.sessionId)
                            requireScopeDogMatches(db, "EVALUATION", it, sessionDogId)
                            requireRecordPolicyVersion(db, "EVALUATION", it, persistedSessionPins.second)
                        }
                    }
                }
                if (event.recordType == "EVIDENCE" && event.supersedesRecordId != null) {
                    val parentExists = db.query(
                        "immutable_records",
                        arrayOf("record_id"),
                        "record_type='EVIDENCE' AND record_id=?",
                        arrayOf(event.supersedesRecordId),
                        null, null, null
                    ).use { it.moveToFirst() }
                    require(parentExists) { "Superseded evidence must already exist." }
                    val successorExists = db.query(
                        "evidence_successors",
                        arrayOf("child_id"),
                        "parent_id=?",
                        arrayOf(event.supersedesRecordId),
                        null, null, null
                    ).use { it.moveToFirst() }
                    require(!successorExists) { "CONCURRENT_SUPERSEDE:" + event.supersedesRecordId }
                    requireScopeMatches(db, "EVIDENCE", event.supersedesRecordId, event.sessionId)
                    requireScopeDogMatches(db, "EVIDENCE", event.supersedesRecordId, sessionDogId)
                    db.insertOrThrow("evidence_successors", null, ContentValues().apply {
                        put("parent_id", event.supersedesRecordId); put("child_id", event.recordId)
                    })
                }
                db.insertOrThrow("client_events", null, ContentValues().apply {
                    put("client_generated_id", event.clientGeneratedId)
                    put("content_hash", payloadHash)
                    put("identity_hash", identityHash)
                    put("legacy_identity", 0)
                })
                db.insertOrThrow("immutable_records", null, ContentValues().apply {
                    put("record_type", event.recordType); put("record_id", event.recordId)
                    put("payload_hash", payloadHash); put("payload", event.canonicalPayload); put("created_at", System.currentTimeMillis())
                })
                val persistedDogId = if (event.recordType == "EVALUATION") sessionDogId else event.dogId
                putRecordScope(
                    db, event.recordType, event.recordId, event.sessionId, persistedDogId.ifBlank { null },
                    event.ruleVersionId, event.policyVersionId,
                    if (event.recordType == "EVIDENCE") (event.evidenceStatus ?: EvidenceStatus.VALID) else null,
                    if (event.recordType == "EVIDENCE") event.supersedesRecordId else null,
                    if (event.recordType == "SESSION") SessionStatus.ACTIVE else null
                )
                accepted += event.clientGeneratedId
            }
            db.setTransactionSuccessful()
            return SyncApplyResult(true, accepted, duplicates, emptyList())
        } catch (e: IllegalArgumentException) {
            return SyncApplyResult(false, emptyList(), emptyList(), listOf(e.message ?: "SYNC_REJECTED"))
        } catch (e: SQLiteConstraintException) {
            val message = e.message.orEmpty()
            val rejection = if (message.contains("immutable_records")) {
                "SYNC_RECORD_ID_CONFLICT"
            } else {
                "SYNC_CONSTRAINT_VIOLATION"
            }
            return SyncApplyResult(false, emptyList(), emptyList(), listOf(rejection))
        } finally { db.endTransaction() }
    }

    private fun orderSyncEvents(events: List<TrainingSyncEvent>): List<TrainingSyncEvent> {
        val evidenceEvents = events.filter { it.recordType == "EVIDENCE" }
        val orderedEvidence = topologicalEvidenceOrder(evidenceEvents)
        val evidenceRank = orderedEvidence.withIndex().associate { it.value.clientGeneratedId to it.index }
        return events.sortedWith(
            compareBy<TrainingSyncEvent>(
                { syncRecordRank(it.recordType) },
                { if (it.recordType == "EVIDENCE") evidenceRank[it.clientGeneratedId] ?: Int.MAX_VALUE else Int.MAX_VALUE },
                { it.clientGeneratedId }
            )
        )
    }

    private fun topologicalEvidenceOrder(events: List<TrainingSyncEvent>): List<TrainingSyncEvent> {
        if (events.size < 2) return events.sortedBy { it.clientGeneratedId }
        val byRecordId = events.associateBy { it.recordId }
        val indegree = events.associate { it.clientGeneratedId to 0 }.toMutableMap()
        val children = events.associate { it.clientGeneratedId to mutableListOf<String>() }.toMutableMap()

        events.forEach { event ->
            val parent = event.supersedesRecordId?.let { byRecordId[it] }
            if (parent != null) {
                indegree[event.clientGeneratedId] = (indegree[event.clientGeneratedId] ?: 0) + 1
                children.getValue(parent.clientGeneratedId).add(event.clientGeneratedId)
            }
        }

        val byClientId = events.associateBy { it.clientGeneratedId }
        val available = java.util.PriorityQueue<String>()
        indegree.filterValues { it == 0 }.keys.sorted().forEach { available.add(it) }
        val ordered = mutableListOf<TrainingSyncEvent>()

        while (available.isNotEmpty()) {
            val id = available.remove()
            ordered += byClientId.getValue(id)
            children.getValue(id).sorted().forEach { childId ->
                val next = indegree[childId]!! - 1
                indegree[childId] = next
                if (next == 0) available.add(childId)
            }
        }

        if (ordered.size != events.size) {
            return events.sortedBy { it.clientGeneratedId }
        }
        return ordered
    }

    private fun syncEventIdentityHash(event: TrainingSyncEvent): String {
        fun part(value: String): String = value.length.toString() + ":" + value
        fun list(values: List<String>): String = values.joinToString(prefix = "[", postfix = "]") { part(it) }
        return sha256(
            buildString {
                append("recordType=").append(part(event.recordType))
                append("|recordId=").append(part(event.recordId))
                append("|canonicalPayload=").append(part(event.canonicalPayload))
                append("|ruleVersionId=").append(part(event.ruleVersionId))
                append("|policyVersionId=").append(part(event.policyVersionId))
                append("|supersedesRecordId=").append(part(event.supersedesRecordId ?: ""))
                append("|sessionId=").append(part(event.sessionId))
                append("|dogId=").append(part(event.dogId))
                append("|attemptId=").append(part(event.attemptId))
                append("|evidenceIds=").append(list(event.evidenceIds))
                append("|attemptIds=").append(list(event.attemptIds))
                append("|basisEvaluationIds=").append(list(event.basisEvaluationIds))
                append("|evidenceStatus=").append(part(event.evidenceStatus?.name ?: if (event.recordType == "EVIDENCE") EvidenceStatus.VALID.name else ""))
            }
        )
    }

    private fun putRecordScope(
        db: SQLiteDatabase,
        recordType: String,
        recordId: String,
        sessionId: String,
        dogId: String?,
        ruleVersionId: String? = null,
        policyVersionId: String? = null,
        evidenceStatus: EvidenceStatus? = null,
        supersedesRecordId: String? = null,
        sessionStatus: SessionStatus? = null
    ) {
        require(recordType.isNotBlank() && recordId.isNotBlank() && sessionId.isNotBlank()) { "INVALID_RECORD_SCOPE" }
        db.insertOrThrow("record_scopes", null, ContentValues().apply {
            put("record_type", recordType)
            put("record_id", recordId)
            put("session_id", sessionId)
            if (dogId != null) put("dog_id", dogId)
            if (ruleVersionId != null) put("rule_version_id", ruleVersionId)
            if (policyVersionId != null) put("policy_version_id", policyVersionId)
            if (evidenceStatus != null) put("evidence_status", evidenceStatus.name)
            if (supersedesRecordId != null) put("supersedes_record_id", supersedesRecordId)
            if (sessionStatus != null) put("session_status", sessionStatus.name)
        })
    }

    fun invalidateSessionIfPresent(sessionId: String): Boolean {
        if (sessionId.isBlank()) return false
        val db = writableDatabase
        db.beginTransaction()
        try {
            val updated = db.update(
                "record_scopes",
                ContentValues().apply { put("session_status", SessionStatus.INVALID.name) },
                "record_type='SESSION' AND record_id=?",
                arrayOf(sessionId)
            )
            db.setTransactionSuccessful()
            return updated > 0
        } finally {
            db.endTransaction()
        }
    }

    fun readSessionStatus(sessionId: String): SessionStatus? =
        readableDatabase.query(
            "record_scopes",
            arrayOf("session_status"),
            "record_type='SESSION' AND record_id=?",
            arrayOf(sessionId),
            null, null, null
        ).use {
            if (!it.moveToFirst() || it.isNull(0)) null else SessionStatus.valueOf(it.getString(0))
        }

    fun requireRecordVersionPins(recordType: String, recordId: String): Pair<String, String> =
        readableDatabase.query(
            "record_scopes",
            arrayOf("rule_version_id", "policy_version_id"),
            "record_type=? AND record_id=?",
            arrayOf(recordType, recordId),
            null, null, null
        ).use {
            require(it.moveToFirst() && !it.isNull(0) && !it.isNull(1)) {
                "RECORD_VERSION_SCOPE_UNKNOWN:$recordType:$recordId"
            }
            it.getString(0) to it.getString(1)
        }

    fun requireCanonicalActiveEvidenceIds(sessionId: String, evidenceIds: List<String>) {
        require(sessionId.isNotBlank()) { "SESSION_ID_REQUIRED" }
        requireCanonicalActiveEvidenceIds(readableDatabase, sessionId, evidenceIds)
    }

    fun readRecordDog(recordType: String, recordId: String): String? {
        require(recordType.isNotBlank() && recordId.isNotBlank())
        readableDatabase.query(
            "record_scopes",
            arrayOf("dog_id"),
            "record_type=? AND record_id=?",
            arrayOf(recordType, recordId),
            null, null, null
        ).use {
            require(it.moveToFirst()) { "RECORD_SCOPE_NOT_FOUND:$recordType:$recordId" }
            return if (it.isNull(0)) null else it.getString(0)
        }
    }

    fun requireRecordSession(recordType: String, recordId: String): String {
        require(recordType.isNotBlank() && recordId.isNotBlank())
        readableDatabase.query(
            "record_scopes",
            arrayOf("session_id"),
            "record_type=? AND record_id=?",
            arrayOf(recordType, recordId),
            null, null, null
        ).use {
            require(it.moveToFirst()) { "RECORD_SCOPE_NOT_FOUND:$recordType:$recordId" }
            return it.getString(0)
        }
    }

    private fun requireScopeMatches(db: SQLiteDatabase, recordType: String, recordId: String, expectedSessionId: String) {
        require(recordId.isNotBlank()) { "SYNC_REFERENCE_REQUIRED:$recordType" }
        val scope = db.query(
            "record_scopes",
            arrayOf("session_id"),
            "record_type=? AND record_id=?",
            arrayOf(recordType, recordId),
            null, null, null
        ).use { if (it.moveToFirst()) it.getString(0) else null }
        require(scope != null) { "SYNC_REFERENCE_SCOPE_UNKNOWN:$recordType:$recordId" }
        require(scope == expectedSessionId) { "SYNC_REFERENCE_SESSION_MISMATCH:$recordType:$recordId" }
    }

    fun requireSessionActive(sessionId: String) {
        requireSessionActive(writableDatabase, sessionId)
    }

    private fun requireSessionActive(db: SQLiteDatabase, sessionId: String) {
        require(sessionId.isNotBlank()) { "SESSION_ID_REQUIRED" }
        db.query(
            "record_scopes",
            arrayOf("session_status"),
            "record_type='SESSION' AND record_id=?",
            arrayOf(sessionId),
            null, null, null
        ).use {
            require(it.moveToFirst() && !it.isNull(0) && it.getString(0) == SessionStatus.ACTIVE.name) {
                "SYNC_SESSION_INVALID:$sessionId"
            }
        }
    }

    private fun requireRecordVersionPins(db: SQLiteDatabase, recordType: String, recordId: String): Pair<String, String> =
        db.query(
            "record_scopes",
            arrayOf("rule_version_id", "policy_version_id"),
            "record_type=? AND record_id=?",
            arrayOf(recordType, recordId),
            null, null, null
        ).use {
            require(it.moveToFirst() && !it.isNull(0) && !it.isNull(1)) {
                "RECORD_VERSION_SCOPE_UNKNOWN:$recordType:$recordId"
            }
            it.getString(0) to it.getString(1)
        }

    private fun requireRecordPolicyVersion(db: SQLiteDatabase, recordType: String, recordId: String, expectedPolicyVersionId: String) {
        val pins = requireRecordVersionPins(db, recordType, recordId)
        require(pins.second == expectedPolicyVersionId) {
            "SYNC_REFERENCE_POLICY_VERSION_MISMATCH:$recordType:$recordId"
        }
    }

    private fun requireCanonicalActiveEvidenceIds(db: SQLiteDatabase, sessionId: String, expectedEvidenceIds: List<String>) {
        val expected = expectedEvidenceIds.distinct().sorted()
        require(expected.size == expectedEvidenceIds.size) {
            "CANONICAL_ACTIVE_EVIDENCE_MISMATCH:$sessionId"
        }
        val validEvidenceIds = mutableListOf<String>()
        val validSupersededIds = mutableSetOf<String>()
        db.query(
            "record_scopes",
            arrayOf("record_id", "evidence_status", "supersedes_record_id"),
            "record_type='EVIDENCE' AND session_id=?",
            arrayOf(sessionId),
            null, null, null
        ).use { cursor ->
            while (cursor.moveToNext()) {
                require(!cursor.isNull(1)) { "CANONICAL_EVIDENCE_SCOPE_UNKNOWN:" + cursor.getString(0) }
                if (cursor.getString(1) == EvidenceStatus.VALID.name) {
                    validEvidenceIds += cursor.getString(0)
                    if (!cursor.isNull(2)) validSupersededIds += cursor.getString(2)
                }
            }
        }
        val active = validEvidenceIds.filterNot { it in validSupersededIds }.sorted()
        require(active == expected) { "CANONICAL_ACTIVE_EVIDENCE_MISMATCH:$sessionId" }
    }

    private fun requireRecordDog(db: SQLiteDatabase, recordType: String, recordId: String): String {
        require(recordId.isNotBlank()) { "SYNC_REFERENCE_REQUIRED:$recordType" }
        db.query(
            "record_scopes",
            arrayOf("dog_id"),
            "record_type=? AND record_id=?",
            arrayOf(recordType, recordId),
            null, null, null
        ).use {
            require(it.moveToFirst() && !it.isNull(0)) { "SYNC_RECORD_DOG_SCOPE_UNKNOWN:$recordType:$recordId" }
            return it.getString(0)
        }
    }

    private fun requireScopeDogMatches(db: SQLiteDatabase, recordType: String, recordId: String, expectedDogId: String) {
        require(recordId.isNotBlank()) { "SYNC_REFERENCE_REQUIRED:$recordType" }
        val scope = db.query(
            "record_scopes",
            arrayOf("dog_id"),
            "record_type=? AND record_id=?",
            arrayOf(recordType, recordId),
            null, null, null
        ).use { if (it.moveToFirst() && !it.isNull(0)) it.getString(0) else null }
        require(scope != null) { "SYNC_REFERENCE_DOG_SCOPE_UNKNOWN:$recordType:$recordId" }
        require(scope == expectedDogId) { "SYNC_REFERENCE_DOG_MISMATCH:$recordType:$recordId" }
    }

    private fun requireImmutable(db: SQLiteDatabase, recordType: String, recordId: String) {
        require(recordId.isNotBlank()) { "SYNC_REFERENCE_REQUIRED:" + recordType }
        val exists = db.query("immutable_records", arrayOf("record_id"), "record_type=? AND record_id=?", arrayOf(recordType, recordId), null, null, null).use { it.moveToFirst() }
        require(exists) { "SYNC_REFERENCE_NOT_FOUND:" + recordType + ":" + recordId }
    }

    private fun syncRecordRank(recordType: String): Int = when (recordType) {
        "SESSION" -> 0
        "ATTEMPT" -> 1
        "EVIDENCE" -> 2
        "EVALUATION" -> 3
        "DECISION" -> 4
        else -> 99
    }

    /**
     * Training version ordering is numeric-dotted only.
     * This keeps "latest" deterministic and avoids lexicographic errors such as 10 < 2.
     */
    private fun validateVersion(version: String) {
        require(version.matches(Regex("(0|[1-9][0-9]*)(\\.(0|[1-9][0-9]*))*"))) {
            "INVALID_VERSION_FORMAT:$version"
        }
    }

    private fun compareVersion(left: String, right: String): Int {
        val l = left.split(".")
        val r = right.split(".")
        val count = maxOf(l.size, r.size)
        for (i in 0 until count) {
            val lc = l.getOrNull(i) ?: "0"
            val rc = r.getOrNull(i) ?: "0"
            val ln = lc.toLongOrNull()
            val rn = rc.toLongOrNull()
            val cmp = if (ln != null && rn != null) ln.compareTo(rn) else lc.compareTo(rc)
            if (cmp != 0) return cmp
        }
        return 0
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
