from pathlib import Path

root = Path("rockyx-src/rev14src")
main = root / "app/src/main/java/com/rockyx/app/MainActivity.kt"
controller = root / "app/src/main/java/com/rockyx/app/ui/AppController.kt"

def replace_once(path, old, new, name):
    text = path.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{name}: expected 1 match, found {count}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")

replace_once(
    controller,
    "import com.rockyx.app.ai.LocalPersonalizationProvider\n",
    "import com.rockyx.app.ai.LocalPersonalizationProvider\nimport com.rockyx.app.library.LibraryService\n",
    "controller import"
)

replace_once(
    controller,
    "    private val content = LocalContentRepository(context)\n",
    "    private val content = LocalContentRepository(context)\n    val library = LibraryService(context)\n",
    "controller library"
)

replace_once(
    main,
    "import com.rockyx.app.media.MediaDescriptor\n",
    "import com.rockyx.app.media.MediaDescriptor\nimport com.rockyx.app.library.LibraryLessonVersion\n",
    "main library import"
)

replace_once(
    main,
    "    override fun onDestroy() { billingScope.cancel(); billingClient?.disconnect(); billingClient = null; mediaEngine?.release(); mediaEngine = null; app.api.close(); super.onDestroy() }\n",
    "    override fun onDestroy() { billingScope.cancel(); billingClient?.disconnect(); billingClient = null; mediaEngine?.release(); mediaEngine = null; app.library.close(); app.api.close(); super.onDestroy() }\n",
    "main library close"
)

old = '''    private fun libraryCategory(title: String, children: List<String> = emptyList()): LinearLayout {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        var expanded = false
        val header = menuRow(title, {}, R.drawable.ic_chevron)
        header.setOnClickListener {
            expanded = !expanded
            for (i in 1 until box.childCount) box.getChildAt(i).visibility = if (expanded) View.VISIBLE else View.GONE
        }
        box.addView(header, LinearLayout.LayoutParams(-1, dp(48)).apply { setMargins(0, dp(2), 0, dp(2)) })
        children.forEach { child ->
            val row = menuRow(child, { openLibraryLesson(child) })
            row.setPadding(dp(18), 0, dp(4), 0)
            box.addView(row, LinearLayout.LayoutParams(-1, dp(44)))
        }
        for (i in 1 until box.childCount) box.getChildAt(i).visibility = View.GONE
        return box
    }

    private fun openLibraryLesson(label: String) {
        val match = app.catalog().asSequence()
            .flatMap { course -> course.chapters.asSequence().flatMap { it.lessons.asSequence().map { lesson -> course to lesson } } }
            .firstOrNull { (_, lesson) -> lesson.title.contains(label, ignoreCase = true) }
        if (match == null) {
            Toast.makeText(this, "این بخش هنوز محتوای آموزشی ثبت‌شده ندارد.", Toast.LENGTH_SHORT).show()
            return
        }
        recentLessonIds.remove(match.second.id)
        recentLessonIds.addFirst(match.second.id)
        while (recentLessonIds.size > 8) recentLessonIds.removeLast()
        shellState.closePanels()
        removeOverlay()
        showLesson(match.first, match.second)
    }
'''
new = '''    private fun libraryCategory(title: String, children: List<String> = emptyList()): LinearLayout {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        var expanded = false
        val header = menuRow(title, {}, R.drawable.ic_chevron)
        header.setOnClickListener {
            expanded = !expanded
            for (i in 1 until box.childCount) box.getChildAt(i).visibility = if (expanded) View.VISIBLE else View.GONE
        }
        box.addView(header, LinearLayout.LayoutParams(-1, dp(48)).apply { setMargins(0, dp(2), 0, dp(2)) })
        children.forEach { child ->
            val row = menuRow(child, {
                Toast.makeText(this, "این بخش هنوز محتوای آموزشی ثبت‌شده ندارد.", Toast.LENGTH_SHORT).show()
            })
            row.setPadding(dp(18), 0, dp(4), 0)
            box.addView(row, LinearLayout.LayoutParams(-1, dp(44)))
        }
        for (i in 1 until box.childCount) box.getChildAt(i).visibility = View.GONE
        return box
    }

    private fun libraryLessonCategory(title: String, children: List<LibraryLessonVersion>): LinearLayout {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        var expanded = false
        val header = menuRow(title, {}, R.drawable.ic_chevron)
        header.setOnClickListener {
            expanded = !expanded
            for (i in 1 until box.childCount) box.getChildAt(i).visibility = if (expanded) View.VISIBLE else View.GONE
        }
        box.addView(header, LinearLayout.LayoutParams(-1, dp(48)).apply { setMargins(0, dp(2), 0, dp(2)) })
        children.forEach { child ->
            val row = menuRow(child.title, { openLibraryLessonById(child.lessonId) })
            row.setPadding(dp(18), 0, dp(4), 0)
            box.addView(row, LinearLayout.LayoutParams(-1, dp(44)))
        }
        for (i in 1 until box.childCount) box.getChildAt(i).visibility = View.GONE
        return box
    }

    private fun openLibraryLessonById(lessonId: String) {
        val libraryLesson = app.library.lessons().firstOrNull { it.lessonId == lessonId }
        if (libraryLesson == null) {
            Toast.makeText(this, "این درس در نسخه فعال Library ثبت نشده است.", Toast.LENGTH_SHORT).show()
            return
        }
        val course = app.catalog().firstOrNull { it.id == libraryLesson.courseId }
        val lesson = course?.chapters?.firstOrNull { it.id == libraryLesson.chapterId }?.lessons?.firstOrNull { it.id == libraryLesson.lessonId }
        if (course == null || lesson == null) {
            Toast.makeText(this, "درس ثبت‌شده Library با محتوای اپ همگام نیست.", Toast.LENGTH_SHORT).show()
            return
        }
        recentLessonIds.remove(libraryLesson.lessonId)
        recentLessonIds.addFirst(libraryLesson.lessonId)
        while (recentLessonIds.size > 8) recentLessonIds.removeLast()
        shellState.closePanels()
        removeOverlay()
        showLesson(course, lesson)
    }
'''
replace_once(main, old, new, "library navigation")

replace_once(
    main,
    '        val beginner = app.catalog().firstOrNull { it.id == "beginner" }?.chapters.orEmpty().flatMap { it.lessons }\n        val pro = app.catalog().firstOrNull { it.id == "pro-path" }?.chapters.orEmpty().flatMap { it.lessons }\n        content.addView(libraryCategory("دانش و سلامت سگ", listOf("بیماری سگ‌ها", "واکسن‌های سگ")))\n        content.addView(libraryCategory("نژاد سگ‌ها", listOf("سگ‌های بزرگ", "سگ‌های متوسط", "سگ‌های کوچک")))\n        content.addView(libraryCategory("آموزش مبتدی", beginner.map { it.title }))\n        content.addView(libraryCategory("آموزش متوسط PRO", pro.filter { it.id == "focus-distraction" }.map { it.title }))\n        content.addView(libraryCategory("آموزش پیشرفته PRO", pro.filter { it.id == "recall-pro" }.map { it.title }))\n',
    '        val libraryLessons = app.library.lessons()\n        val beginner = libraryLessons.filter { it.courseId == "beginner" }.sortedBy { it.id }\n        val pro = libraryLessons.filter { it.courseId == "pro-path" }.sortedBy { it.id }\n        content.addView(libraryCategory("دانش و سلامت سگ", listOf("بیماری سگ‌ها", "واکسن‌های سگ")))\n        content.addView(libraryCategory("نژاد سگ‌ها", listOf("سگ‌های بزرگ", "سگ‌های متوسط", "سگ‌های کوچک")))\n        content.addView(libraryLessonCategory("آموزش مبتدی", beginner))\n        content.addView(libraryLessonCategory("آموزش متوسط PRO", pro.filter { it.lessonId == "focus-distraction" }))\n        content.addView(libraryLessonCategory("آموزش پیشرفته PRO", pro.filter { it.lessonId == "recall-pro" }))\n',
    "library panel lesson lists"
)

replace_once(main, "menuRow(lesson.title, { openLibraryLesson(lesson.title) })", "menuRow(lesson.title, { openLibraryLessonById(lesson.id) })", "recent lesson action")
replace_once(main, "all.forEach { lesson -> root.addView(menuRow(lesson.title, { openLibraryLesson(lesson.title) }), LinearLayout.LayoutParams(-1, dp(48))) }", "all.forEach { lesson -> root.addView(menuRow(lesson.title, { openLibraryLessonById(lesson.id) }), LinearLayout.LayoutParams(-1, dp(48))) }", "recent all action")

print("Library UI integration overlay applied.")
