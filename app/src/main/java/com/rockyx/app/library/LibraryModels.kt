package com.rockyx.app.library

import com.rockyx.app.domain.model.Access
import com.rockyx.app.domain.model.ContentType

/** Immutable identity for one published Library content generation. */
data class LibraryContentVersion(
    val schemaVersion: Int,
    val contentVersion: String,
    val locale: String,
    val contentHash: String
) {
    val key: String get() = "$locale::$contentVersion"

    init {
        require(schemaVersion >= 1) { "schemaVersion must be >= 1" }
        require(contentVersion.isNotBlank()) { "contentVersion must not be blank" }
        require(locale.isNotBlank()) { "locale must not be blank" }
        require(contentHash.matches(Regex("^[0-9a-f]{64}$"))) { "contentHash must be lowercase SHA-256" }
    }
}

data class LibraryLessonVersion(
    val id: String,
    val contentVersion: String,
    val courseId: String,
    val chapterId: String,
    val lessonId: String,
    val title: String,
    val summary: String,
    val access: Access,
    val durationMinutes: Int,
    val sortIndex: Int,
    val prerequisiteLessonIds: List<String>,
    val goalTags: List<String>,
    val behaviorTags: List<String>,
    val recommendedLevels: List<String>
)

data class LibraryMediaAsset(
    val id: String,
    val sourceMediaId: String,
    val lessonVersionId: String,
    val type: ContentType,
    val uri: String,
    val thumbnailUri: String?,
    val downloadable: Boolean,
    val sha256: String? = null,
    val sizeBytes: Long? = null
)

enum class LibraryDownloadState { NOT_REQUESTED, QUEUED, DOWNLOADING, COMPLETED, FAILED, REMOVED }

data class LibraryDownloadStatus(
    val mediaId: String,
    val state: LibraryDownloadState,
    val updatedAtEpochMs: Long,
    val errorCode: String? = null
)

data class LibrarySnapshot(
    val version: LibraryContentVersion,
    val lessons: List<LibraryLessonVersion>,
    val media: List<LibraryMediaAsset>
)

enum class LibraryInstallResult { INSTALLED, ALREADY_PRESENT }


data class LibraryLessonRef(
    val courseId: String,
    val chapterId: String,
    val lessonId: String,
    val title: String
)
