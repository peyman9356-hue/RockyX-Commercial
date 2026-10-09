from pathlib import Path
import base64
import shutil

from home_reference_asset_validation import decode_validated_webp_base64_file

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

        val lessons = app.catalog().flatMap { course -> course.chapters.flatMap { it.lessons } }
        val nextLesson = app.catalog().asSequence()
            .flatMap { course -> app.personalized(course.id, 1).asSequence().map { course to it.lesson } }
            .firstOrNull()
        val recent = recentLessonIds.mapNotNull { id -> lessons.firstOrNull { it.id == id } }.take(2)

        val pageRoot = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(6, 12, 20))
            setPadding(dp(10), 0, dp(10), dp(18))
        }

        val hero = com.rockyx.home.reference.ReferenceHomeVisualView(this)
        pageRoot.addView(hero, LinearLayout.LayoutParams(-1, -2).apply {
            setMargins(0, 0, 0, dp(2))
        })

        var trainingStartPending = false
        val startButton = Button(this).apply {
            text = "شروع تمرین امروز  ›"
            textSize = 18f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
            setTextColor(Color.rgb(243, 224, 188))
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = dp(29).toFloat()
                setColor(Color.argb(16, 236, 208, 157))
                setStroke(dp(1), Color.argb(210, 236, 208, 157))
            }
            elevation = 0f
            setPadding(dp(18), 0, dp(18), 0)
            setOnClickListener {
                if (trainingStartPending) return@setOnClickListener
                if (nextLesson != null) {
                    trainingStartPending = true
                    val trainingIntentEventId = java.util.UUID.randomUUID().toString()
                    hero.playTrainingIntentPulse(trainingIntentEventId) {
                        shellState.closePanels()
                        removeOverlay()
                        showLesson(nextLesson.first, nextLesson.second)
                    }
                } else {
                    Toast.makeText(this@MainActivity, "هنوز تمرین بعدی قابل تعیین نیست.", Toast.LENGTH_SHORT).show()
                }
            }
        }
        pageRoot.addView(startButton, LinearLayout.LayoutParams(-1, dp(58)).apply {
            setMargins(dp(8), 0, dp(8), dp(2))
        })

        pageRoot.addView(TextView(this).apply {
            text = "تثبیت تمرکز در آرامش  •  ۷ دقیقه"
            textSize = 11.5f
            setTextColor(Color.rgb(177, 168, 150))
            gravity = Gravity.CENTER
            setPadding(0, dp(3), 0, dp(8))
        }, LinearLayout.LayoutParams(-1, dp(30)))

        pageRoot.addView(TextView(this).apply {
            text = "مسیر پیوسته تمرین"
            textSize = 16.5f
            setTextColor(Color.rgb(242, 239, 231))
            gravity = Gravity.RIGHT
            setPadding(dp(8), dp(2), dp(8), dp(2))
        }, LinearLayout.LayoutParams(-1, dp(34)))

        val continuum = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(4), dp(2), dp(4), dp(4))
            background = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
        }
        listOf("پایه‌های تثبیت‌شده", "تمرکز در آرامش", "حواس‌پرتی کوچک", "محیط‌های شلوغ").forEachIndexed { index, label ->
            val cell = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
            }
            cell.addView(TextView(this).apply {
                text = if (index == 0) "●" else "○"
                textSize = 14f
                setTextColor(
                    if (index == 0) Color.rgb(239, 210, 158)
                    else if (index == 1) Color.rgb(167, 198, 196)
                    else Color.rgb(108, 146, 151)
                )
                gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(-1, dp(22)))
            cell.addView(TextView(this).apply {
                text = label
                textSize = 9.2f
                setTextColor(Color.rgb(171, 176, 181))
                gravity = Gravity.CENTER
                maxLines = 2
            }, LinearLayout.LayoutParams(-1, dp(28)))
            continuum.addView(cell, LinearLayout.LayoutParams(0, dp(52), 1f))
        }
        pageRoot.addView(continuum, LinearLayout.LayoutParams(-1, dp(58)).apply {
            setMargins(dp(4), 0, dp(4), dp(8))
        })

        pageRoot.addView(View(this).apply {
            setBackgroundColor(Color.argb(70, 220, 219, 213))
        }, LinearLayout.LayoutParams(-1, dp(1)).apply {
            setMargins(dp(8), 0, dp(8), dp(4))
        })

        pageRoot.addView(TextView(this).apply {
            text = "دیدگاه مربی"
            textSize = 15.5f
            setTextColor(Color.rgb(242, 239, 231))
            gravity = Gravity.RIGHT
            setPadding(dp(8), dp(4), dp(8), dp(2))
        }, LinearLayout.LayoutParams(-1, dp(28)))

        pageRoot.addView(TextView(this).apply {
            text = "تمرکز در محیط آرام پایدار است. امروز یک حواس‌پرتی کوچک اضافه کن."
            textSize = 13.3f
            setTextColor(Color.rgb(213, 215, 214))
            gravity = Gravity.RIGHT
            setPadding(dp(8), dp(2), dp(8), dp(8))
        }, LinearLayout.LayoutParams(-1, dp(45)))

        pageRoot.addView(View(this).apply {
            setBackgroundColor(Color.argb(70, 220, 219, 213))
        }, LinearLayout.LayoutParams(-1, dp(1)).apply {
            setMargins(dp(8), 0, dp(8), dp(5))
        })

        pageRoot.addView(TextView(this).apply {
            text = "فعالیت‌های اخیر"
            textSize = 15.5f
            setTextColor(Color.rgb(242, 239, 231))
            gravity = Gravity.RIGHT
            setPadding(dp(8), dp(2), dp(8), dp(2))
        }, LinearLayout.LayoutParams(-1, dp(28)))

        pageRoot.addView(TextView(this).apply {
            text = if (recent.isNotEmpty()) "•  ${recent.first().title}  •  ۱۰ دقیقه پیش" else "•  تمرین صبحگاهی  •  ۱۰ دقیقه پیش"
            textSize = 12.5f
            setTextColor(Color.rgb(177, 184, 188))
            gravity = Gravity.RIGHT
            setPadding(dp(8), 0, dp(8), dp(6))
        }, LinearLayout.LayoutParams(-1, dp(30)))

        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.rgb(6, 12, 20))
            clipToPadding = false
            isFillViewport = true
            addView(pageRoot)
        }
        shellContent.removeAllViews()
        shellContent.addView(scroll, FrameLayout.LayoutParams(-1, -1))

        fun referenceHeaderDrawable(label: String, tint: Int): android.graphics.drawable.Drawable =
            object : android.graphics.drawable.Drawable() {
                private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                    color = tint
                    textSize = dp(15).toFloat()
                    textAlign = android.graphics.Paint.Align.CENTER
                    typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
                }
                override fun draw(canvas: android.graphics.Canvas) {
                    val b = bounds
                    canvas.drawText(label, b.exactCenterX(), b.centerY() - (paint.ascent() + paint.descent()) / 2f, paint)
                }
                override fun setAlpha(alpha: Int) { paint.alpha = alpha }
                override fun setColorFilter(filter: android.graphics.ColorFilter?) { paint.colorFilter = filter }
                override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
            }

        fun applyReferenceShellTheme() {
            val surface = Color.rgb(5, 12, 20)
            val gold = Color.rgb(239, 213, 165)
            val ivory = Color.rgb(228, 226, 218)

            window.statusBarColor = surface
            window.navigationBarColor = surface
            window.decorView.systemUiVisibility =
                window.decorView.systemUiVisibility and
                    (View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR).inv()
            if (android.os.Build.VERSION.SDK_INT >= 28) {
                window.navigationBarDividerColor = surface
            }
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
            shellRoot.setBackgroundColor(surface)

            fun styleComposer(v: View) {
                if (v is android.widget.EditText) {
                    val hint = v.hint?.toString().orEmpty()
                    if (hint.contains("Rocky X", true) || v.contentDescription?.toString()?.contains("Rocky X", true) == true) {
                        v.setTextColor(ivory)
                        v.setHintTextColor(Color.rgb(147, 151, 155))
                        v.background = android.graphics.drawable.GradientDrawable().apply {
                            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                            cornerRadius = dp(23).toFloat()
                            setColor(Color.argb(16, 239, 213, 165))
                            setStroke(dp(1), Color.argb(185, 239, 213, 165))
                        }
                        v.setPadding(dp(16), 0, dp(16), 0)
                    }
                }
                if (v is android.widget.ImageButton) {
                    v.background = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
                    v.setColorFilter(gold, android.graphics.PorterDuff.Mode.SRC_IN)
                }
                if (v is ViewGroup) for (i in 0 until v.childCount) styleComposer(v.getChildAt(i))
            }

            fun styleHeader(v: View) {
                if (v is android.widget.ImageButton) {
                    v.background = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
                    v.setImageDrawable(referenceHeaderDrawable(if (v.left < shellRoot.width / 2) "بیشتر" else "کتابخانه", gold))
                }
                if (v is ViewGroup) for (i in 0 until v.childCount) styleHeader(v.getChildAt(i))
            }

            fun visit(v: View) {
                if (v is ViewGroup) {
                    if (v.bottom >= shellRoot.height - dp(220)) v.setBackgroundColor(surface)
                    for (i in 0 until v.childCount) visit(v.getChildAt(i))
                }
                if (v.top <= dp(120) && v.height <= dp(120) && v.width >= shellRoot.width * 0.70f) {
                    v.setBackgroundColor(surface)
                    styleHeader(v)
                }
                if (v.bottom >= shellRoot.height - dp(220) && v.width >= shellRoot.width * 0.70f) {
                    v.setBackgroundColor(surface)
                    styleComposer(v)
                }
            }

            visit(shellRoot)
            styleComposer(shellRoot)
        }

        setContentView(shellRoot)
        shellRoot.post { applyReferenceShellTheme() }
    }

'''
replace_once(MAIN, old_home, new_home, "Home replacement")

replace_once(MAIN, 'val lp = FrameLayout.LayoutParams(width, height, Gravity.START or Gravity.TOP).apply {', 'val lp = FrameLayout.LayoutParams(width, height, Gravity.LEFT or Gravity.TOP).apply {', "More physical-left placement")
replace_once(MAIN, 'val lp = FrameLayout.LayoutParams(width, -1, Gravity.END)', 'val lp = FrameLayout.LayoutParams(width, -1, Gravity.RIGHT)', "Library physical-right placement")
# Library safe-area handling is owned by the Library UI overlay; Home only normalizes the global shell theme.

# Living Training Gem prototype integration.
# The renderer is kept outside the archived source package so this overlay remains reversible.

LIVING_GEM_SRC = Path("tools/living-gem")
LIVING_GEM_DEST = ROOT / "app/src/main/java/com/rockyx/livinggem"
REFERENCE_HOME_DEST = ROOT / "app/src/main/java/com/rockyx/home/reference"
REFERENCE_HOME_DEST.mkdir(parents=True, exist_ok=True)
REFERENCE_HOME_ASSET = ROOT / "app/src/main/res/drawable-nodpi/rocky_home_reference_hero.webp"
REFERENCE_HOME_B64 = Path("tools/home-reference/rocky_home_reference_hero.webp.b64")
REFERENCE_HOME_ASSET.parent.mkdir(parents=True, exist_ok=True)
REFERENCE_HOME_ASSET.write_bytes(decode_validated_webp_base64_file(REFERENCE_HOME_B64))
LIVING_GEM_DEST.mkdir(parents=True, exist_ok=True)
for filename in ("VisualState.kt", "GemMesh.kt", "LivingGemView.kt", "ReferenceHomeVisualView.kt"):
    source = Path("tools/home-reference/ReferenceHomeVisualView.kt") if filename == "ReferenceHomeVisualView.kt" else LIVING_GEM_SRC / filename
    if not source.is_file():
        raise SystemExit(f"Missing Living Gem source: {source}")
    destination = REFERENCE_HOME_DEST / filename if filename == "ReferenceHomeVisualView.kt" else LIVING_GEM_DEST / filename
    shutil.copyfile(source, destination)

# Small transparent prototype Rocky asset. This is a replaceable prototype input,
# not the final commercial Rocky media asset.
ROCKY_CUTOUT_B64 = """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"""
ROCKY_ASSET = ROOT / "app/src/main/res/drawable-nodpi/rocky_home_rocky_cutout.webp"
ROCKY_ASSET.parent.mkdir(parents=True, exist_ok=True)
ROCKY_ASSET.write_bytes(base64.b64decode(ROCKY_CUTOUT_B64))

print("Living Training Gem renderer + prototype Rocky asset integrated into extracted source.")

