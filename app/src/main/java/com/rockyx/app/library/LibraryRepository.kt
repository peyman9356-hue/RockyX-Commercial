package com.rockyx.app.library

interface LibraryRepository {
    fun install(snapshot: LibrarySnapshot, installedAtEpochMs: Long = System.currentTimeMillis()): LibraryInstallResult
    fun activeContentKey(): String?
    fun lesson(courseId: String, chapterId: String, lessonId: String, contentKey: String? = activeContentKey()): LibraryLessonVersion?
    fun lessons(contentKey: String? = activeContentKey()): List<LibraryLessonVersion>
    fun mediaForLesson(lessonVersionId: String): List<LibraryMediaAsset>
    fun setDownloadStatus(status: LibraryDownloadStatus)
    fun downloadStatus(mediaId: String): LibraryDownloadStatus?
}
