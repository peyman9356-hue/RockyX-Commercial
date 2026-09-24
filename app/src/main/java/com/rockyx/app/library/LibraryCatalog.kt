package com.rockyx.app.library

import com.rockyx.app.data.content.ContentManifest
import com.rockyx.app.domain.model.Course
import java.security.MessageDigest

/** Converts the existing asset/CMS-shaped content model into an immutable Library snapshot. */
object LibraryCatalogBuilder {
    fun build(manifest: ContentManifest, courses: List<Course>): LibrarySnapshot {
        val lessons = buildList {
            courses.sortedBy { it.id }.forEach { course ->
                course.chapters.sortedBy { it.id }.forEach { chapter ->
                    chapter.lessons.sortedBy { it.id }.forEachIndexed { index, lesson ->
                        add(
                            LibraryLessonVersion(
                                id = lessonVersionId(manifest.locale, manifest.contentVersion, course.id, chapter.id, lesson.id),
                                contentVersion = manifest.contentVersion,
                                courseId = course.id,
                                chapterId = chapter.id,
                                lessonId = lesson.id,
                                title = lesson.title,
                                summary = lesson.summary,
                                access = lesson.access,
                                durationMinutes = lesson.durationMinutes,
                                sortIndex = index,
                                prerequisiteLessonIds = lesson.prerequisiteLessonIds.sorted(),
                                goalTags = lesson.goalTags.sorted(),
                                behaviorTags = lesson.behaviorTags.sorted(),
                                recommendedLevels = lesson.recommendedLevels.sorted()
                            )
                        )
                    }
                }
            }
        }

        val lessonLookup = lessons.associateBy { it.lessonId }
        val media = buildList {
            courses.sortedBy { it.id }.forEach { course ->
                course.chapters.sortedBy { it.id }.forEach { chapter ->
                    chapter.lessons.sortedBy { it.id }.forEach { lesson ->
                        val lessonVersionId = lessonVersionId(manifest.locale, manifest.contentVersion, course.id, chapter.id, lesson.id)
                        lesson.media.sortedBy { it.id }.forEach { ref ->
                            val uri = ref.uri ?: error("${ref.id} in ${lesson.id} has no URI")
                            add(
                                LibraryMediaAsset(
                                    id = mediaVersionId(lessonVersionId, ref.id),
                                    sourceMediaId = ref.id,
                                    lessonVersionId = lessonVersionId,
                                    type = ref.type,
                                    uri = uri,
                                    thumbnailUri = ref.thumbnailUri,
                                    downloadable = ref.downloadable,
                                    sha256 = ref.sha256,
                                    sizeBytes = ref.sizeBytes
                                )
                            )
                        }
                        lesson.exercises.sortedBy { it.id }.forEach { exercise ->
                            exercise.media.sortedBy { it.id }.forEach { ref ->
                                val uri = ref.uri ?: error("${ref.id} in exercise ${exercise.id} has no URI")
                                add(
                                    LibraryMediaAsset(
                                        id = mediaVersionId(lessonVersionId, "exercise:${exercise.id}:${ref.id}"),
                                        sourceMediaId = ref.id,
                                        lessonVersionId = lessonVersionId,
                                        type = ref.type,
                                        uri = uri,
                                        thumbnailUri = ref.thumbnailUri,
                                        downloadable = ref.downloadable,
                                        sha256 = ref.sha256,
                                        sizeBytes = ref.sizeBytes
                                    )
                                )
                            }
                        }
                    }
                }
            }
        }

        val contentHash = sha256(canonicalPayload(manifest, lessons, media))
        val version = LibraryContentVersion(manifest.schemaVersion, manifest.contentVersion, manifest.locale, contentHash)
        val snapshot = LibrarySnapshot(version, lessons, media)
        LibraryValidator.validate(snapshot)
        check(lessonLookup.size == lessons.size) { "Lesson IDs must be globally unique" }
        return snapshot
    }

    fun lessonVersionId(locale: String, contentVersion: String, courseId: String, chapterId: String, lessonId: String): String =
        "$locale::$contentVersion::$courseId::$chapterId::$lessonId"

    fun mediaVersionId(lessonVersionId: String, sourceMediaId: String): String =
        "$lessonVersionId::$sourceMediaId"

    private fun canonicalPayload(
        manifest: ContentManifest,
        lessons: List<LibraryLessonVersion>,
        media: List<LibraryMediaAsset>
    ): String = buildString {
        append("schema=").append(manifest.schemaVersion).append('\n')
        append("contentVersion=").append(manifest.contentVersion).append('\n')
        append("locale=").append(manifest.locale).append('\n')
        lessons.sortedBy { it.id }.forEach { lesson ->
            append("L|").append(lesson.id).append('|')
                .append(lesson.courseId).append('|').append(lesson.chapterId).append('|')
                .append(lesson.lessonId).append('|').append(lesson.title).append('|')
                .append(lesson.summary).append('|').append(lesson.access.name).append('|')
                .append(lesson.durationMinutes).append('|').append(lesson.sortIndex).append('|')
                .append(lesson.prerequisiteLessonIds.joinToString(",")).append('|')
                .append(lesson.goalTags.joinToString(",")).append('|')
                .append(lesson.behaviorTags.joinToString(",")).append('|')
                .append(lesson.recommendedLevels.joinToString(",")).append('\n')
        }
        media.sortedBy { it.id }.forEach { asset ->
            append("M|").append(asset.id).append('|').append(asset.sourceMediaId).append('|')
                .append(asset.lessonVersionId).append('|').append(asset.type.name).append('|')
                .append(asset.uri).append('|').append(asset.thumbnailUri.orEmpty()).append('|')
                .append(asset.downloadable).append('|').append(asset.sha256.orEmpty()).append('|')
                .append(asset.sizeBytes ?: -1L).append('\n')
        }
    }

    private fun sha256(input: String): String = MessageDigest.getInstance("SHA-256")
        .digest(input.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}

object LibraryValidator {
    fun validate(snapshot: LibrarySnapshot) {
        require(snapshot.lessons.map { it.id }.distinct().size == snapshot.lessons.size) { "Duplicate lesson version ID" }
        require(snapshot.media.map { it.id }.distinct().size == snapshot.media.size) { "Duplicate media ID" }
        val lessonIds = snapshot.lessons.map { it.id }.toSet()
        require(snapshot.lessons.all { it.contentVersion == snapshot.version.contentVersion }) { "Lesson content version mismatch" }
        require(snapshot.media.all { it.lessonVersionId in lessonIds }) { "Media references unknown lesson version" }
        require(snapshot.lessons.all { it.durationMinutes in 1..1440 }) { "Invalid lesson duration" }
        require(snapshot.media.all { it.uri.isNotBlank() }) { "Media URI must not be blank" }
        require(snapshot.media.all { it.sha256 == null || it.sha256.matches(Regex("^[0-9a-f]{64}$")) }) {
            "Media sha256 must be lowercase SHA-256 when present"
        }
        require(snapshot.media.all { it.sizeBytes == null || it.sizeBytes > 0L }) {
            "Media sizeBytes must be positive when present"
        }
        require(snapshot.media.all { !it.downloadable || (it.sha256 != null && it.sizeBytes != null) }) {
            "Downloadable media requires integrity metadata"
        }
    }
}
