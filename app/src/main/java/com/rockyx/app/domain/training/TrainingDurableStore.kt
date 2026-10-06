package com.rockyx.app.domain.training

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteOpenHelper
import java.security.MessageDigest

class TrainingDurableStore(context: Context) : SQLiteOpenHelper(context.applicationContext, "rockyx_training.db", null, 3) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("PRAGMA foreign_keys=ON")
        db.execSQL("CREATE TABLE rule_versions (rule_id TEXT NOT NULL, version TEXT NOT NULL, status TEXT NOT NULL, definition TEXT NOT NULL, PRIMARY KEY(rule_id, version))")
        db.execSQL("CREATE TABLE policy_versions (policy_id TEXT NOT NULL, version TEXT NOT NULL, status TEXT NOT NULL, definition TEXT NOT NULL, PRIMARY KEY(policy_id, version))")
        db.execSQL("CREATE TABLE client_events (client_generated_id TEXT PRIMARY KEY, content_hash TEXT NOT NULL, identity_hash TEXT NOT NULL)")
        db.execSQL("CREATE TABLE immutable_records (record_type TEXT NOT NULL, record_id TEXT NOT NULL, payload_hash TEXT NOT NULL, payload TEXT NOT NULL, created_at INTEGER NOT NULL, PRIMARY KEY(record_type, record_id))")
        db.execSQL("CREATE TABLE evidence_successors (parent_id TEXT PRIMARY KEY, child_id TEXT NOT NULL UNIQUE)")
        db.execSQL("CREATE TABLE record_scopes (record_type TEXT NOT NULL, record_id TEXT NOT NULL, session_id TEXT NOT NULL, dog_id TEXT, PRIMARY KEY(record_type, record_id))")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE client_events ADD COLUMN identity_hash TEXT")
            db.execSQL("UPDATE client_events SET identity_hash = content_hash WHERE identity_hash IS NULL")
        }
        if (oldVersion < 3) {
            db.execSQL("CREATE TABLE IF NOT EXISTS record_scopes (record_type TEXT NOT NULL, record_id TEXT NOT NULL, session_id TEXT NOT NULL, dog_id TEXT, PRIMARY KEY(record_type, record_id))")
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
        db.query("client_events", arrayOf("content_hash"), "client_generated_id=?", arrayOf(clientGeneratedId), null, null, null).use {
            if (it.moveToFirst()) {
                require(it.getString(0) == hash) { "CLIENT_ID_REUSE_WITH_DIFFERENT_CONTENT:$clientGeneratedId" }
                return false
            }
        }
        db.insertOrThrow("client_events", null, ContentValues().apply {
            put("client_generated_id", clientGeneratedId); put("content_hash", hash); put("identity_hash", hash)
        })
        return true
    }

    fun appendSession(sessionId: String, dogId: String, canonicalPayload: String, createdAt: Long) {
        appendImmutable("SESSION", sessionId, canonicalPayload, createdAt, sessionId = sessionId, dogId = dogId)
    }

    fun appendAttempt(attemptId: String, clientGeneratedId: String, canonicalPayload: String, createdAt: Long, sessionId: String, dogId: String): Boolean {
        return appendEventAndImmutableRecord(
            eventId = clientGeneratedId,
            recordType = "ATTEMPT",
            recordId = attemptId,
            canonicalPayload = canonicalPayload,
            createdAt = createdAt,
            scopeSessionId = sessionId,
            scopeDogId = dogId
        )
    }

    fun appendImmutable(
        recordType: String,
        recordId: String,
        canonicalPayload: String,
        createdAt: Long,
        sessionId: String? = null,
        dogId: String? = null
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
                putRecordScope(db, recordType, recordId, sessionId, dogId)
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
        scopeDogId: String? = null
    ): Boolean {
        require(eventId.isNotBlank() && recordType.isNotBlank() && recordId.isNotBlank())
        val hash = sha256(canonicalPayload)
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.query("client_events", arrayOf("content_hash"), "client_generated_id=?", arrayOf(eventId), null, null, null).use {
                if (it.moveToFirst()) {
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
                put("client_generated_id", eventId); put("content_hash", hash); put("identity_hash", hash)
            })
            db.insertOrThrow("immutable_records", null, ContentValues().apply {
                put("record_type", recordType); put("record_id", recordId)
                put("payload_hash", hash); put("payload", canonicalPayload); put("created_at", createdAt)
            })
            if (scopeSessionId != null) {
                putRecordScope(db, recordType, recordId, scopeSessionId, scopeDogId)
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
                require(event.recordType in setOf("SESSION", "ATTEMPT", "EVIDENCE", "EVALUATION", "DECISION")) {
                    "UNSUPPORTED_RECORD_TYPE:" + event.recordType
                }
                val payloadHash = sha256(event.canonicalPayload)
                val identityHash = syncEventIdentityHash(event)
                val existingHashes = db.query(
                    "client_events",
                    arrayOf("content_hash", "identity_hash"),
                    "client_generated_id=?",
                    arrayOf(event.clientGeneratedId),
                    null, null, null
                ).use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) to cursor.getString(1) else null
                }
                if (existingHashes != null) {
                    val existingIdentityHash = existingHashes.second ?: existingHashes.first
                    require(existingIdentityHash == identityHash) {
                        "CLIENT_ID_REUSE_WITH_DIFFERENT_CONTENT:" + event.clientGeneratedId
                    }
                    duplicates += event.clientGeneratedId
                    return@forEach
                }
                if (event.recordType != "SESSION") {
                    val sessionExists = db.query(
                        "immutable_records",
                        arrayOf("record_id"),
                        "record_type='SESSION' AND record_id=?",
                        arrayOf(event.sessionId),
                        null, null, null
                    ).use { it.moveToFirst() }
                    require(sessionExists) { "SYNC_SESSION_NOT_FOUND:" + event.recordId }
                }
                when (event.recordType) {
                    "ATTEMPT" -> {
                        requireScopeMatches(db, "SESSION", event.sessionId, event.sessionId)
                    }
                    "EVIDENCE" -> {
                        require(event.attemptId.isNotBlank()) { "SYNC_EVIDENCE_ATTEMPT_REQUIRED:" + event.recordId }
                        requireScopeMatches(db, "ATTEMPT", event.attemptId, event.sessionId)
                    }
                    "EVALUATION" -> {
                        require(event.attemptIds.isNotEmpty()) { "SYNC_EVALUATION_ATTEMPTS_REQUIRED:" + event.recordId }
                        require(event.evidenceIds.isNotEmpty()) { "SYNC_EVALUATION_EVIDENCE_REQUIRED:" + event.recordId }
                        event.attemptIds.forEach { requireScopeMatches(db, "ATTEMPT", it, event.sessionId) }
                        event.evidenceIds.forEach { requireScopeMatches(db, "EVIDENCE", it, event.sessionId) }
                    }
                    "DECISION" -> {
                        require(event.basisEvaluationIds == event.basisEvaluationIds.sorted()) { "BASIS_EVALUATION_IDS_NOT_SORTED" }
                        event.basisEvaluationIds.forEach { requireScopeMatches(db, "EVALUATION", it, event.sessionId) }
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
                    db.insertOrThrow("evidence_successors", null, ContentValues().apply {
                        put("parent_id", event.supersedesRecordId); put("child_id", event.recordId)
                    })
                }
                db.insertOrThrow("client_events", null, ContentValues().apply {
                    put("client_generated_id", event.clientGeneratedId)
                    put("content_hash", payloadHash)
                    put("identity_hash", identityHash)
                })
                db.insertOrThrow("immutable_records", null, ContentValues().apply {
                    put("record_type", event.recordType); put("record_id", event.recordId)
                    put("payload_hash", payloadHash); put("payload", event.canonicalPayload); put("created_at", System.currentTimeMillis())
                })
                putRecordScope(db, event.recordType, event.recordId, event.sessionId, event.dogId.ifBlank { null })
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
            }
        )
    }

    private fun putRecordScope(db: SQLiteDatabase, recordType: String, recordId: String, sessionId: String, dogId: String?) {
        require(recordType.isNotBlank() && recordId.isNotBlank() && sessionId.isNotBlank()) { "INVALID_RECORD_SCOPE" }
        db.insertOrThrow("record_scopes", null, ContentValues().apply {
            put("record_type", recordType)
            put("record_id", recordId)
            put("session_id", sessionId)
            if (dogId != null) put("dog_id", dogId)
        })
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
