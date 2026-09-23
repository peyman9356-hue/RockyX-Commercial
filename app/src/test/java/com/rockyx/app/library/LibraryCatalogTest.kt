package com.rockyx.app.library

import com.rockyx.app.data.content.ContentManifest
import com.rockyx.app.domain.model.Access
import com.rockyx.app.domain.model.Chapter
import com.rockyx.app.domain.model.ContentType
import com.rockyx.app.domain.model.Course
import com.rockyx.app.domain.model.Lesson
import com.rockyx.app.domain.model.MediaRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryCatalogTest {
    @Test
    fun builderCreatesLocaleAndVersionScopedImmutableIds() {
        val manifest = ContentManifest(1, "1.0.0", "fa")
        val media = MediaRef("sit-video", ContentType.VIDEO, "https://example.test/sit.m3u8", true)
        val courses = listOf(
            Course("beginner", "Beginner", "", Access.FREE, false, listOf(
                Chapter("foundation", "Foundation", listOf(
                    Lesson("sit", "نشستن", "", Access.FREE, 4, media = listOf(media))
                ))
            ))
        )

        val snapshot = LibraryCatalogBuilder.build(manifest, courses)
        assertEquals("fa::1.0.0", snapshot.version.key)
        assertEquals("fa::1.0.0::beginner::foundation::sit", snapshot.lessons.single().id)
        assertEquals("fa::1.0.0::beginner::foundation::sit::sit-video", snapshot.media.single().id)
        assertEquals(64, snapshot.version.contentHash.length)
    }

    @Test
    fun validatorRejectsMediaWithoutLessonReference() {
        val version = LibraryContentVersion(1, "1.0.0", "fa", "0".repeat(64))
        val snapshot = LibrarySnapshot(
            version,
            listOf(LibraryLessonVersion("lesson", "1.0.0", "c", "ch", "l", "L", "", Access.FREE, 1, 0, emptyList(), emptyList(), emptyList(), emptyList())),
            listOf(LibraryMediaAsset("media", "source", "missing", ContentType.VIDEO, "u", null, true))
        )
        assertThrows(IllegalArgumentException::class.java) { LibraryValidator.validate(snapshot) }
    }

    @Test
    fun sameContentVersionCanExistForDifferentLocales() {
        val courses = listOf(
            Course("beginner", "Beginner", "", Access.FREE, false, listOf(
                Chapter("foundation", "Foundation", listOf(Lesson("sit", "Sit", "", Access.FREE, 4)))
            ))
        )
        val fa = LibraryCatalogBuilder.build(ContentManifest(1, "1.0.0", "fa"), courses)
        val en = LibraryCatalogBuilder.build(ContentManifest(1, "1.0.0", "en"), courses)
        assertTrue(fa.version.key != en.version.key)
        assertTrue(fa.lessons.single().id != en.lessons.single().id)
    }
}
