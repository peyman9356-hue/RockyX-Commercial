package com.rockyx.app.domain.training

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.security.MessageDigest

class TrainingDurableStore(context: Context) : SQLiteOpenHelper(context.applicationContext, "rockyx_training.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("PRAGMA foreign_keys=ON")
        db.execSQL("CREATE TABLE rule_versions (rule_id TEXT NOT NULL, version TEXT NOT NULL, status TEXT NOT NULL, definition TEXT NOT NULL, PRIMARY KEY(rule_id, version))")
        db.execSQL("CREATE TABLE policy_versions (policy_id TEXT NOT NULL, version TEXT NOT NULL, status TEXT NOT NULL, definition TEXT NOT NULL, PRIMARY KEY(policy_id, version))")
        db.execSQL("CREATE TABLE client_events (client_generated_id TEXT PRIMARY KEY, content_hash TEXT NOT NULL)")
        db.execSQL("CREATE TABLE immutable_records (record_type TEXT NOT NULL, record_id TEXT NOT NULL, payload_hash TEXT NOT NULL, payload TEXT NOT NULL, created_at INTEGER NOT NULL, PRIMARY KEY(record_type, record_id))")
        db.execSQL("CREATE TABLE evidence_successors (parent_id TEXT PRIMARY KEY, child_id TEXT NOT NULL UNIQUE)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun registerRule(version: RuleVersion) {
        writableDatabase.insertOrThrow("rule_versions", null, ContentValues().apply {
            put("rule_id", version.ruleId); put("version", version.version)
            put("status", version.status); put("definition", version.definition)
        })
    }

    fun registerPolicy(version: PolicyVersion) {
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
            put("client_generated_id", clientGeneratedId); put("content_hash", hash)
        })
        return true
    }

    fun appendSession(sessionId: String, canonicalPayload: String, createdAt: Long) {
        appendImmutable("SESSION", sessionId, canonicalPayload, createdAt)
    }

    fun appendAttempt(attemptId: String, clientGeneratedId: String, canonicalPayload: String, createdAt: Long): Boolean {
        return appendEventAndImmutableRecord(
            eventId = clientGeneratedId,
            recordType = "ATTEMPT",
            recordId = attemptId,
            canonicalPayload = canonicalPayload,
            createdAt = createdAt
        )
    }

    fun appendImmutable(recordType: String, recordId: String, canonicalPayload: String, createdAt: Long) {
        require(recordType.isNotBlank() && recordId.isNotBlank())
        writableDatabase.insertOrThrow("immutable_records", null, ContentValues().apply {
            put("record_type", recordType); put("record_id", recordId)
            put("payload_hash", sha256(canonicalPayload)); put("payload", canonicalPayload); put("created_at", createdAt)
        })
    }
    fun appendEventAndImmutableRecord(eventId: String, recordType: String, recordId: String, canonicalPayload: String, createdAt: Long, supersedesEvidenceId: String? = null): Boolean {
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
                put("client_generated_id", eventId); put("content_hash", hash)
            })
            db.insertOrThrow("immutable_records", null, ContentValues().apply {
                put("record_type", recordType); put("record_id", recordId)
                put("payload_hash", hash); put("payload", canonicalPayload); put("created_at", createdAt)
            })
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
