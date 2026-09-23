package com.rockyx.app.library

import android.content.Context
import org.robolectric.RuntimeEnvironment
import com.rockyx.app.data.content.ContentManifest
import com.rockyx.app.domain.model.Access
import com.rockyx.app.domain.model.Chapter
import com.rockyx.app.domain.model.Course
import com.rockyx.app.domain.model.Lesson
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class LibraryLocalStoreTest {
    private lateinit var context: Context
    private lateinit var store: LibraryLocalStore

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.deleteDatabase("rockyx_library_v1.db")
        store = LibraryLocalStore(context)
    }

    @After
    fun tearDown() {
        store.close()
        context.deleteDatabase("rockyx_library_v1.db")
    }

    @Test
    fun installIsIdempotentAndHistoricalVersionCannotBeRewritten() {
        val first = snapshot("1.0.0", "fa", "Sit")
        assertEquals(LibraryInstallResult.INSTALLED, store.install(first))
        assertEquals(LibraryInstallResult.ALREADY_PRESENT, store.install(first))
        assertEquals(first.version.key, store.activeContentKey())
        assertEquals("Sit", store.lesson("beginner", "foundation", "sit")?.title)

        val changed = snapshot("1.0.0", "fa", "SIT CHANGED")
        assertThrows(LibraryInvariantException::class.java) { store.install(changed) }
        assertEquals("Sit", store.lesson("beginner", "foundation", "sit")?.title)
    }

    @Test
    fun differentLocaleMayUseSameSemanticContentVersion() {
        val fa = snapshot("1.0.0", "fa", "نشستن")
        val en = snapshot("1.0.0", "en", "Sit")
        store.install(fa)
        store.install(en)

        assertEquals(en.version.key, store.activeContentKey())
        assertEquals("Sit", store.lesson("beginner", "foundation", "sit")?.title,)

        assertEquals(1, store.lessons(fa.version.key).size)
        assertEquals(1, store.lessons(en.version.key).size)
    }

    @Test
    fun downloadStateIsDurableAcrossStoreReopen() {
        val snapshot = snapshot("1.0.0", "fa", "نشستن")
        store.install(snapshot)
        val mediaId = LibraryCatalogBuilder.mediaVersionId(snapshot.lessons.single().id, "sit-video")

        store.setDownloadStatus(
            LibraryDownloadStatus(mediaId, LibraryDownloadState.COMPLETED, 1234L)
        )
        store.close()
        store = LibraryLocalStore(context)

        val status = store.downloadStatus(mediaId)
        assertTrue(status != null)
        assertEquals(LibraryDownloadState.COMPLETED, status!!.state)
        assertEquals(1234L, status.updatedAtEpochMs)
    }

    private fun snapshot(version: String, locale: String, title: String): LibrarySnapshot {
        val courses = listOf(
            Course(
                "beginner",
                "Beginner",
                "",
                Access.FREE,
                false,
                listOf(
                    Chapter(
                        "foundation",
                        "Foundation",
                        listOf(
                            Lesson(
                                "sit",
                                title,
                                "",
                                Access.FREE,
                                4
                            )
                        )
                    )
                )
            )
        )
        return LibraryCatalogBuilder.build(ContentManifest(1, version, locale), courses)
    }
}
