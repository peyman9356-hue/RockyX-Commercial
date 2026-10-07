from pathlib import Path
import shutil

ROOT = Path("rockyx-src/rev14src")
MAIN = ROOT / "app/src/main/java/com/rockyx/app/MainActivity.kt"

def replace_once(path, old, new, label):
    text = path.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly 1 match, found {count}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")

old_home = '''    private fun showHome() {
        setScreen("home")
        val root = FrameLayout(this).apply {
            setBackgroundColor(bgColor)
            minimumHeight = dp(420)
        }
        // Reference-driven Home: intentionally no cards, buttons, or dashboard options.
        // The active surface remains visually empty; interaction lives in the shell composer.
        page(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            addView(root, LinearLayout.LayoutParams(-1, 0, 1f))
        })
    }
'''
new_home = '''    private fun showHome() {
        setScreen("home")

        val dog = app.dog.current()
        val lessons = app.catalog().flatMap { course -> course.chapters.flatMap { it.lessons } }
        val nextLesson = app.catalog().asSequence()
            .flatMap { course -> app.personalized(course.id, 1).asSequence().map { course to it.lesson } }
            .firstOrNull()
        val recent = recentLessonIds.mapNotNull { id -> lessons.firstOrNull { it.id == id } }.take(3)

        val pageRoot = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(6, 15, 24))
            setPadding(dp(12), dp(4), dp(12), dp(18))
        }

        val identity = TextView(this).apply {
            text = "ROCKY X  •  ${dog.name}"
            textSize = 12f
            letterSpacing = 0.16f
            setTextColor(Color.rgb(190, 198, 202))
            gravity = Gravity.RIGHT
            setPadding(dp(4), dp(4), dp(4), dp(6))
        }
        pageRoot.addView(identity, LinearLayout.LayoutParams(-1, dp(30)))

        val hero = FrameLayout(this).apply {
            setBackgroundColor(Color.rgb(7, 18, 28))
        }
        val rocky = ImageView(this).apply {
            setImageResource(R.drawable.rocky_home_gem_visual)
            scaleType = ImageView.ScaleType.CENTER_CROP
            contentDescription = "Rocky — Living Training Gem"
        }
        hero.addView(rocky, FrameLayout.LayoutParams(-1, dp(318)))

        val stateLabel = TextView(this).apply {
            text = if (nextLesson != null) "تمرکز فعلی  •  ${nextLesson.second.title}" else "آماده برای تمرین"
            textSize = 13f
            setTextColor(Color.rgb(232, 220, 190))
            gravity = Gravity.RIGHT
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = rounded(Color.argb(205, 7, 18, 28), 18f)
        }
        hero.addView(stateLabel, FrameLayout.LayoutParams(-2, dp(42), Gravity.BOTTOM or Gravity.RIGHT).apply {
            setMargins(dp(10), 0, dp(10), dp(10))
        })
        pageRoot.addView(hero, LinearLayout.LayoutParams(-1, dp(318)).apply {
            setMargins(0, 0, 0, dp(10))
        })

        val start = Button(this).apply {
            text = "شروع تمرین امروز  ›"
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.rgb(18, 20, 22))
            background = rounded(Color.rgb(232, 214, 178), 26f)
            setPadding(dp(18), 0, dp(18), 0)
            setOnClickListener {
                if (nextLesson != null) {
                    shellState.closePanels()
                    removeOverlay()
                    showLesson(nextLesson.first, nextLesson.second)
                } else {
                    Toast.makeText(this@MainActivity, "هنوز تمرین بعدی قابل تعیین نیست.", Toast.LENGTH_SHORT).show()
                }
            }
        }
        pageRoot.addView(start, LinearLayout.LayoutParams(-1, dp(58)).apply {
            setMargins(dp(8), 0, dp(8), dp(12))
        })

        val continuumTitle = TextView(this).apply {
            text = "مسیر پیوسته تمرین"
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            gravity = Gravity.RIGHT
            setPadding(dp(4), dp(4), dp(4), dp(6))
        }
        pageRoot.addView(continuumTitle, LinearLayout.LayoutParams(-1, dp(34)))

        val continuum = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(8), dp(6), dp(8), dp(6))
            background = rounded(Color.rgb(12, 27, 39), 22f)
        }
        val pathLessons = lessons.take(3)
        pathLessons.forEachIndexed { index, lesson ->
            val node = TextView(this).apply {
                text = if (index == 0) "●" else "○"
                textSize = 18f
                setTextColor(if (index == 0) Color.rgb(235, 209, 161) else Color.rgb(92, 157, 160))
                gravity = Gravity.CENTER
                contentDescription = lesson.title
            }
            continuum.addView(node, LinearLayout.LayoutParams(dp(30), dp(34)))
            if (index < pathLessons.lastIndex) {
                val label = TextView(this).apply {
                    text = lesson.title
                    textSize = 11f
                    setTextColor(Color.rgb(190, 198, 202))
                    gravity = Gravity.CENTER
                    maxLines = 2
                }
                continuum.addView(label, LinearLayout.LayoutParams(0, dp(40), 1f))
            }
        }
        pageRoot.addView(continuum, LinearLayout.LayoutParams(-1, dp(58)).apply {
            setMargins(0, 0, 0, dp(12))
        })

        val insight = TextView(this).apply {
            text = if (nextLesson != null)
                "دیدگاه مربی\\nپیشنهاد امروز بر اساس مسیر آموزشی فعلی: ${nextLesson.second.title}"
            else
                "دیدگاه مربی\\nبرای تعیین قدم بعدی، یک تمرین را شروع کن."
            textSize = 14f
            setTextColor(Color.rgb(218, 222, 224))
            gravity = Gravity.RIGHT
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = rounded(Color.rgb(10, 22, 33), 18f)
        }
        pageRoot.addView(insight, LinearLayout.LayoutParams(-1, dp(78)).apply {
            setMargins(0, 0, 0, dp(12))
        })

        if (recent.isNotEmpty()) {
            pageRoot.addView(TextView(this).apply {
                text = "فعالیت‌های اخیر"
                textSize = 16f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.WHITE)
                gravity = Gravity.RIGHT
            }, LinearLayout.LayoutParams(-1, dp(30)))
            recent.forEach { lesson ->
                pageRoot.addView(TextView(this).apply {
                    text = "•  ${lesson.title}"
                    textSize = 13f
                    setTextColor(Color.rgb(180, 190, 195))
                    gravity = Gravity.RIGHT
                    setPadding(dp(6), dp(4), dp(6), dp(4))
                }, LinearLayout.LayoutParams(-1, dp(30)))
            }
        }

        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.rgb(6, 15, 24))
            clipToPadding = false
            addView(pageRoot)
        }
        shellContent.removeAllViews()
        shellContent.addView(scroll, FrameLayout.LayoutParams(-1, -1))
        setContentView(shellRoot)
    }
'''
replace_once(MAIN, old_home, new_home, "Home replacement")

replace_once(MAIN, 'val lp = FrameLayout.LayoutParams(width, height, Gravity.START or Gravity.TOP).apply {', 'val lp = FrameLayout.LayoutParams(width, height, Gravity.LEFT or Gravity.TOP).apply {', "More physical-left placement")
replace_once(MAIN, 'val lp = FrameLayout.LayoutParams(width, -1, Gravity.END)', 'val lp = FrameLayout.LayoutParams(width, -1, Gravity.RIGHT)', "Library physical-right placement")
replace_once(MAIN, '        panel.addView(bottomBar, LinearLayout.LayoutParams(-1, dp(64)))\n        val width = (resources.displayMetrics.widthPixels * 0.58f).roundToInt()', '''        panel.addView(bottomBar, LinearLayout.LayoutParams(-1, dp(64)))
        ViewCompat.setOnApplyWindowInsetsListener(bottomBar) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(dp(14), dp(8), dp(14), dp(10) + bars.bottom)
            insets
        }
        val width = (resources.displayMetrics.widthPixels * 0.58f).roundToInt()''', "Library bottom safe-area")

asset_src = Path("app/src/main/res/drawable-nodpi/rocky_home_gem_visual.jpg")
asset_dst = ROOT / "app/src/main/res/drawable-nodpi/rocky_home_gem_visual.jpg"
if not asset_src.is_file():
    raise SystemExit(f"Missing Home asset: {asset_src}")
asset_dst.parent.mkdir(parents=True, exist_ok=True)
shutil.copyfile(asset_src, asset_dst)
print("Product Home + shell physical-side/safe-area overlay applied.")
