package com.rockyx.app.library

import android.content.Context
import com.rockyx.app.data.content.AssetContentRepository

/**
 * Application-facing Library boundary.
 * The UI resolves lessons by stable IDs from the installed versioned snapshot.
 */
class LibraryService(context: Context) : AutoCloseable {
    private val content = AssetContentRepository(context.applicationContext)
    private val store = LibraryLocalStore(context.applicationContext)

    init {
        val snapshot = LibraryCatalogBuilder.build(content.manifest(), content.courses())
        store.install(snapshot)
    }

    fun activeContentKey(): String? = store.activeContentKey()
    fun lessons(): List<LibraryLessonVersion> = store.lessons()
    fun lesson(courseId: String, chapterId: String, lessonId: String): LibraryLessonVersion? =
        store.lesson(courseId, chapterId, lessonId)
    fun mediaForLesson(lessonVersionId: String): List<LibraryMediaAsset> =
        store.mediaForLesson(lessonVersionId)

    fun mediaAssets(): List<LibraryMediaAsset> =
        lessons().flatMap { store.mediaForLesson(it.id) }

    fun setDownloadStatus(status: LibraryDownloadStatus) =
        store.setDownloadStatus(status)
    fun downloadStatus(mediaId: String): LibraryDownloadStatus? =
        store.downloadStatus(mediaId)

    override fun close() {
        store.close()
    }
}
