package com.rockyx.app.library

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.rockyx.app.domain.model.Access
import com.rockyx.app.domain.model.ContentType

class LibraryInvariantException(message: String) : IllegalStateException(message)

class LibraryLocalStore(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION), LibraryRepository {
    companion object {
        private const val DB_NAME = "rockyx_library_v1.db"
        private const val DB_VERSION = 2
    }

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE library_manifest (
                content_key TEXT PRIMARY KEY NOT NULL,
                content_version TEXT NOT NULL,
                schema_version INTEGER NOT NULL,
                locale TEXT NOT NULL,
                content_hash TEXT NOT NULL,
                installed_at INTEGER NOT NULL
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX idx_library_manifest_version ON library_manifest(content_version)")

        db.execSQL("""
            CREATE TABLE library_lesson_version (
                id TEXT PRIMARY KEY NOT NULL,
                content_key TEXT NOT NULL,
                content_version TEXT NOT NULL,
                course_id TEXT NOT NULL,
                chapter_id TEXT NOT NULL,
                lesson_id TEXT NOT NULL,
                title TEXT NOT NULL,
                summary TEXT NOT NULL,
                access TEXT NOT NULL,
                duration_minutes INTEGER NOT NULL,
                sort_index INTEGER NOT NULL,
                prerequisite_ids TEXT NOT NULL,
                goal_tags TEXT NOT NULL,
                behavior_tags TEXT NOT NULL,
                recommended_levels TEXT NOT NULL,
                FOREIGN KEY(content_key) REFERENCES library_manifest(content_key)
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX idx_library_lesson_content ON library_lesson_version(content_key)")
        db.execSQL("CREATE INDEX idx_library_lesson_lookup ON library_lesson_version(course_id, chapter_id, lesson_id, content_key)")

        db.execSQL("""
            CREATE TABLE library_media (
                id TEXT PRIMARY KEY NOT NULL,
                source_media_id TEXT NOT NULL,
                lesson_version_id TEXT NOT NULL,
                type TEXT NOT NULL,
                uri TEXT NOT NULL,
                thumbnail_uri TEXT,
                downloadable INTEGER NOT NULL,
                sha256 TEXT,
                size_bytes INTEGER,
                FOREIGN KEY(lesson_version_id) REFERENCES library_lesson_version(id)
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX idx_library_media_lesson ON library_media(lesson_version_id)")

        db.execSQL("""
            CREATE TABLE library_download_state (
                media_id TEXT PRIMARY KEY NOT NULL,
                state TEXT NOT NULL,
                updated_at INTEGER NOT NULL,
                error_code TEXT
            )
        """.trimIndent())

        db.execSQL("""
            CREATE TABLE library_active_manifest (
                singleton_id INTEGER PRIMARY KEY CHECK(singleton_id = 1),
                content_key TEXT NOT NULL,
                FOREIGN KEY(content_key) REFERENCES library_manifest(content_key)
            )
        """.trimIndent())
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE library_media ADD COLUMN sha256 TEXT")
            db.execSQL("ALTER TABLE library_media ADD COLUMN size_bytes INTEGER")
        }
        if (newVersion != DB_VERSION) {
            throw LibraryInvariantException("Unsupported Library DB upgrade: $oldVersion -> $newVersion")
        }
    }

    @Synchronized
    override fun install(snapshot: LibrarySnapshot, installedAtEpochMs: Long): LibraryInstallResult {
        LibraryValidator.validate(snapshot)
        val db = writableDatabase
        db.beginTransaction()
        try {
            val existingHash = db.query(
                "library_manifest", arrayOf("content_hash"), "content_key = ?",
                arrayOf(snapshot.version.key), null, null, null
            ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }

            if (existingHash != null) {
                if (existingHash != snapshot.version.contentHash) {
                    throw LibraryInvariantException("Immutable Library version changed: ${snapshot.version.key}")
                }
                setActiveInternal(db, snapshot.version.key)
                db.setTransactionSuccessful()
                return LibraryInstallResult.ALREADY_PRESENT
            }

            db.insertOrThrow("library_manifest", null, ContentValues().apply {
                put("content_key", snapshot.version.key)
                put("content_version", snapshot.version.contentVersion)
                put("schema_version", snapshot.version.schemaVersion)
                put("locale", snapshot.version.locale)
                put("content_hash", snapshot.version.contentHash)
                put("installed_at", installedAtEpochMs)
            })

            snapshot.lessons.sortedBy { it.id }.forEach { lesson ->
                db.insertOrThrow("library_lesson_version", null, ContentValues().apply {
                    put("id", lesson.id)
                    put("content_key", snapshot.version.key)
                    put("content_version", lesson.contentVersion)
                    put("course_id", lesson.courseId)
                    put("chapter_id", lesson.chapterId)
                    put("lesson_id", lesson.lessonId)
                    put("title", lesson.title)
                    put("summary", lesson.summary)
                    put("access", lesson.access.name)
                    put("duration_minutes", lesson.durationMinutes)
                    put("sort_index", lesson.sortIndex)
                    put("prerequisite_ids", lesson.prerequisiteLessonIds.joinToString("\u001f"))
                    put("goal_tags", lesson.goalTags.joinToString("\u001f"))
                    put("behavior_tags", lesson.behaviorTags.joinToString("\u001f"))
                    put("recommended_levels", lesson.recommendedLevels.joinToString("\u001f"))
                })
            }

            snapshot.media.sortedBy { it.id }.forEach { media ->
                db.insertOrThrow("library_media", null, ContentValues().apply {
                    put("id", media.id)
                    put("source_media_id", media.sourceMediaId)
                    put("lesson_version_id", media.lessonVersionId)
                    put("type", media.type.name)
                    put("uri", media.uri)
                    put("thumbnail_uri", media.thumbnailUri)
                    put("downloadable", if (media.downloadable) 1 else 0)
                    put("sha256", media.sha256)
                    media.sizeBytes?.let { put("size_bytes", it) }
                })
            }

            setActiveInternal(db, snapshot.version.key)
            db.setTransactionSuccessful()
            return LibraryInstallResult.INSTALLED
        } finally {
            db.endTransaction()
        }
    }

    override fun activeContentKey(): String? = readableDatabase.query(
        "library_active_manifest", arrayOf("content_key"), "singleton_id = 1", null, null, null, null
    ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }

    override fun lesson(
        courseId: String,
        chapterId: String,
        lessonId: String,
        contentKey: String?
    ): LibraryLessonVersion? {
        if (contentKey == null) return null
        return readableDatabase.query(
            "library_lesson_version", null,
            "course_id = ? AND chapter_id = ? AND lesson_id = ? AND content_key = ?",
            arrayOf(courseId, chapterId, lessonId, contentKey), null, null, null, "1"
        ).use { cursor -> if (cursor.moveToFirst()) decodeLesson(cursor) else null }
    }

    override fun lessons(contentKey: String?): List<LibraryLessonVersion> {
        if (contentKey == null) return emptyList()
        return readableDatabase.query(
            "library_lesson_version", null, "content_key = ?", arrayOf(contentKey), null, null,
            "course_id, chapter_id, sort_index, lesson_id"
        ).use { cursor ->
            buildList { while (cursor.moveToNext()) add(decodeLesson(cursor)) }
        }
    }

    override fun mediaForLesson(lessonVersionId: String): List<LibraryMediaAsset> = readableDatabase.query(
        "library_media", null, "lesson_version_id = ?", arrayOf(lessonVersionId), null, null, "id"
    ).use { cursor ->
        buildList { while (cursor.moveToNext()) add(decodeMedia(cursor)) }
    }

    @Synchronized
    override fun setDownloadStatus(status: LibraryDownloadStatus) {
        val values = ContentValues().apply {
            put("media_id", status.mediaId)
            put("state", status.state.name)
            put("updated_at", status.updatedAtEpochMs)
            put("error_code", status.errorCode)
        }
        writableDatabase.insertWithOnConflict(
            "library_download_state", null, values, SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    override fun downloadStatus(mediaId: String): LibraryDownloadStatus? = readableDatabase.query(
        "library_download_state", null, "media_id = ?", arrayOf(mediaId), null, null, null
    ).use { cursor ->
        if (!cursor.moveToFirst()) return null
        LibraryDownloadStatus(
            mediaId = cursor.getString(cursor.getColumnIndexOrThrow("media_id")),
            state = LibraryDownloadState.valueOf(cursor.getString(cursor.getColumnIndexOrThrow("state"))),
            updatedAtEpochMs = cursor.getLong(cursor.getColumnIndexOrThrow("updated_at")),
            errorCode = cursor.getString(cursor.getColumnIndexOrThrow("error_code"))
        )
    }

    private fun setActiveInternal(db: SQLiteDatabase, contentKey: String) {
        db.insertWithOnConflict("library_active_manifest", null, ContentValues().apply {
            put("singleton_id", 1)
            put("content_key", contentKey)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    private fun decodeLesson(cursor: android.database.Cursor): LibraryLessonVersion = LibraryLessonVersion(
        id = cursor.getString(cursor.getColumnIndexOrThrow("id")),
        contentVersion = cursor.getString(cursor.getColumnIndexOrThrow("content_version")),
        courseId = cursor.getString(cursor.getColumnIndexOrThrow("course_id")),
        chapterId = cursor.getString(cursor.getColumnIndexOrThrow("chapter_id")),
        lessonId = cursor.getString(cursor.getColumnIndexOrThrow("lesson_id")),
        title = cursor.getString(cursor.getColumnIndexOrThrow("title")),
        summary = cursor.getString(cursor.getColumnIndexOrThrow("summary")),
        access = Access.valueOf(cursor.getString(cursor.getColumnIndexOrThrow("access"))),
        durationMinutes = cursor.getInt(cursor.getColumnIndexOrThrow("duration_minutes")),
        sortIndex = cursor.getInt(cursor.getColumnIndexOrThrow("sort_index")),
        prerequisiteLessonIds = split(cursor.getString(cursor.getColumnIndexOrThrow("prerequisite_ids"))),
        goalTags = split(cursor.getString(cursor.getColumnIndexOrThrow("goal_tags"))),
        behaviorTags = split(cursor.getString(cursor.getColumnIndexOrThrow("behavior_tags"))),
        recommendedLevels = split(cursor.getString(cursor.getColumnIndexOrThrow("recommended_levels")))
    )

    private fun decodeMedia(cursor: android.database.Cursor): LibraryMediaAsset = LibraryMediaAsset(
        id = cursor.getString(cursor.getColumnIndexOrThrow("id")),
        sourceMediaId = cursor.getString(cursor.getColumnIndexOrThrow("source_media_id")),
        lessonVersionId = cursor.getString(cursor.getColumnIndexOrThrow("lesson_version_id")),
        type = ContentType.valueOf(cursor.getString(cursor.getColumnIndexOrThrow("type"))),
        uri = cursor.getString(cursor.getColumnIndexOrThrow("uri")),
        thumbnailUri = cursor.getString(cursor.getColumnIndexOrThrow("thumbnail_uri")),
        downloadable = cursor.getInt(cursor.getColumnIndexOrThrow("downloadable")) == 1,
        sha256 = cursor.getString(cursor.getColumnIndexOrThrow("sha256")),
        sizeBytes = cursor.getLong(cursor.getColumnIndexOrThrow("size_bytes")).takeIf { !cursor.isNull(cursor.getColumnIndexOrThrow("size_bytes")) }
    )

    private fun split(value: String): List<String> = value.takeIf { it.isNotEmpty() }?.split('\u001f').orEmpty()
}
