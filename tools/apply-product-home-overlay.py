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

        val hero = FrameLayout(this).apply {
            setBackgroundColor(Color.rgb(7, 18, 28))
        }
        val livingGem = com.rockyx.livinggem.LivingGemView(this).apply {
            setRockyDrawable()
            setState(com.rockyx.livinggem.TrainingUiState.READY)
            contentDescription = "Rocky — Living Training Gem"
        }
        hero.addView(livingGem, FrameLayout.LayoutParams(-1, dp(318)))

        val stateLabel = TextView(this).apply {
            text = if (nextLesson != null) "تمرکز فعلی  •  ${nextLesson.second.title}" else "آماده برای تمرین"
            textSize = 13f
            setTextColor(Color.rgb(232, 220, 190))
            gravity = Gravity.RIGHT
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
        }
        hero.addView(stateLabel, FrameLayout.LayoutParams(-2, dp(36), Gravity.BOTTOM or Gravity.RIGHT).apply {
            setMargins(dp(10), 0, dp(10), dp(10))
        })
        pageRoot.addView(hero, LinearLayout.LayoutParams(-1, dp(318)).apply {
            setMargins(0, 0, 0, dp(10))
        })

        val start = Button(this).apply {
            text = "شروع تمرین امروز  ›"
            textSize = 18f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            setTextColor(Color.rgb(242, 224, 188))
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = dp(29).toFloat()
                setColor(Color.argb(24, 232, 214, 178))
                setStroke(dp(1), Color.argb(210, 232, 214, 178))
            }
            elevation = 0f
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
            background = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
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
            background = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
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

        fun applyReferenceShellTheme() {
            val surface = Color.rgb(5, 12, 20)
            val surface2 = Color.rgb(7, 18, 28)
            val gold = Color.rgb(236, 208, 157)
            val ivory = Color.rgb(224, 226, 226)
            val darkFlags = (View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or
                View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR).inv()

            window.statusBarColor = surface
            window.navigationBarColor = surface
            window.decorView.systemUiVisibility =
                window.decorView.systemUiVisibility and darkFlags
            if (android.os.Build.VERSION.SDK_INT >= 28) {
                window.navigationBarDividerColor = surface
            }
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
            shellRoot.setBackgroundColor(surface)

            fun styleComposerNode(v: View) {
                if (v is android.widget.EditText) {
                    val hint = v.hint?.toString().orEmpty()
                    val content = v.text?.toString().orEmpty()
                    if (hint.contains("Rocky X", true) || content.contains("Rocky X", true) ||
                        v.contentDescription?.toString()?.contains("Rocky X", true) == true) {
                        v.setTextColor(ivory)
                        v.setHintTextColor(Color.rgb(145, 151, 156))
                        v.background = android.graphics.drawable.GradientDrawable().apply {
                            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                            cornerRadius = dp(28).toFloat()
                            setColor(Color.argb(18, 236, 208, 157))
                            setStroke(dp(1), Color.argb(175, 236, 208, 157))
                        }
                        v.setPadding(dp(18), 0, dp(18), 0)
                    }
                }
                if (v is android.widget.ImageButton) {
                    v.setColorFilter(gold, android.graphics.PorterDuff.Mode.SRC_IN)
                    v.background = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
                }
                if (v is ViewGroup) {
                    for (i in 0 until v.childCount) styleComposerNode(v.getChildAt(i))
                }
            }

            fun styleHeaderNode(v: View) {
                if (v is android.widget.ImageButton) {
                    v.setColorFilter(gold, android.graphics.PorterDuff.Mode.SRC_IN)
                    v.background = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
                } else if (v is android.widget.TextView) {
                    v.setTextColor(ivory)
                }
                if (v is ViewGroup) {
                    for (i in 0 until v.childCount) styleHeaderNode(v.getChildAt(i))
                }
            }

            fun visit(v: View) {
                val rootHeight = shellRoot.height
                val topBand = v.top <= dp(124) && v.height <= dp(140) && v.width >= shellRoot.width * 0.70f
                val bottomBand = v.bottom >= rootHeight - dp(210) && v.height <= dp(190) && v.width >= shellRoot.width * 0.70f
                if (topBand) {
                    v.setBackgroundColor(surface)
                    styleHeaderNode(v)
                }
                if (bottomBand) {
                    v.setBackgroundColor(surface)
                    styleComposerNode(v)
                }
                if (v is ViewGroup) {
                    for (i in 0 until v.childCount) visit(v.getChildAt(i))
                }
            }

            visit(shellRoot)
            styleComposerNode(shellRoot)
        }

        setContentView(shellRoot)
        shellRoot.post { applyReferenceShellTheme() }
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

# Living Training Gem prototype integration.
# The renderer is kept outside the archived source package so this overlay remains reversible.
import base64

LIVING_GEM_SRC = Path("tools/living-gem")
LIVING_GEM_DEST = ROOT / "app/src/main/java/com/rockyx/livinggem"
LIVING_GEM_DEST.mkdir(parents=True, exist_ok=True)
for filename in ("VisualState.kt", "GemMesh.kt", "LivingGemView.kt"):
    source = LIVING_GEM_SRC / filename
    if not source.is_file():
        raise SystemExit(f"Missing Living Gem source: {source}")
    shutil.copyfile(source, LIVING_GEM_DEST / filename)

# Small transparent prototype Rocky asset. This is a replaceable prototype input,
# not the final commercial Rocky media asset.
ROCKY_CUTOUT_B64 = """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"""
ROCKY_ASSET = ROOT / "app/src/main/res/drawable-nodpi/rocky_home_rocky_cutout.webp"
ROCKY_ASSET.parent.mkdir(parents=True, exist_ok=True)
ROCKY_ASSET.write_bytes(base64.b64decode(ROCKY_CUTOUT_B64))

print("Living Training Gem renderer + prototype Rocky asset integrated into extracted source.")

