package app.curbside

import android.app.Activity
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.webkit.WebView
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * Phone-side setup (design section 4): a short guide, a bookmark editor, and troubleshooting.
 * No AppCompat, no layout XML, no view binding; plain platform views keep the APK small.
 */
class SetupActivity : Activity() {

    private val night get() = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    private val bg get() = if (night) 0xFF121417.toInt() else 0xFFF2F4F7.toInt()
    private val surface get() = if (night) 0xFF1F2329.toInt() else Color.WHITE
    private val muted get() = if (night) 0xFFA8B0BA.toInt() else 0xFF5B6470.toInt()
    private val accent = 0xFF3D7BD9.toInt()

    private lateinit var marks: LinearLayout
    private lateinit var home: EditText

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        runCatching { WebView.getCurrentWebViewPackage() } // warm the provider early

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(24))
        }
        column.addView(header())

        column.addView(card("Set up",
            step(1, "In Android Auto settings, tap the version number 10 times to unlock Developer settings, then turn on Unknown sources."),
            step(2, "Connect your phone to the car."),
            step(3, "Open Curbside from the car's app launcher."),
        ))

        home = field(prefs(this).getString("home", null) ?: "", "Default: duckduckgo.com", InputType.TYPE_TEXT_VARIATION_URI)
        column.addView(card("Home page", text("Opened by Home in the car menu.", muted, 14f), home))

        marks = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        Bookmarks.list(this).forEach { addMark(it.title, it.url) }
        column.addView(card("Bookmarks",
            text("Shown in Menu on the car screen. Up to $MAX_BOOKMARKS.", muted, 14f),
            marks,
            LinearLayout(this).apply {
                gravity = Gravity.END
                addView(Button(this@SetupActivity, null, android.R.attr.borderlessButtonStyle).apply {
                    text = "Add"; setTextColor(accent)
                    setOnClickListener { if (marks.childCount < MAX_BOOKMARKS) addMark("", "") }
                })
                addView(Button(this@SetupActivity).apply {
                    text = "Save"; setTextColor(Color.WHITE)
                    background = rounded(accent, 20)
                    setPadding(dp(24), 0, dp(24), 0)
                    setOnClickListener { save() }
                })
            },
        ))

        column.addView(card("On the car screen",
            tip("Address", "type a web address or search, or use voice."),
            tip("Text boxes", "tap one on a page to type with the car keyboard."),
            tip("Arrows", "scroll the page a screen at a time. Drag to scroll freely."),
            tip("Back / Forward", "the arrows at the top."),
            tip("Menu", "Home, Reload, Bookmark this page, Text size, desktop or mobile site, and your bookmarks."),
            tip("Resume", "Curbside reopens the last page you were on."),
        ))

        column.addView(card("Not showing in Android Auto?",
            text("In Android Auto settings, check that Curbside is ticked under Customise launcher, " +
                "and that Unknown sources is still on in Developer settings. Then reconnect. " +
                "If it vanishes after the car restarts, reinstall from a computer with " +
                "adb install -r -i com.android.vending Curbside.apk", muted, 14f),
        ))

        val scroll = ScrollView(this).apply { setBackgroundColor(bg); addView(column) }
        // targetSdk 35 draws edge-to-edge on Android 15+: keep content clear of the system bars.
        scroll.setOnApplyWindowInsetsListener { v, insets ->
            @Suppress("DEPRECATION")
            v.setPadding(0, insets.systemWindowInsetTop, 0, insets.systemWindowInsetBottom)
            insets
        }
        setContentView(scroll)
        window.statusBarColor = bg
        window.navigationBarColor = bg
    }

    override fun onPause() {
        super.onPause()
        save(quiet = true) // don't lose edits when leaving without tapping Save
    }

    private fun header() = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(4), dp(8), 0, dp(16))
        addView(ImageView(this@SetupActivity).apply { setImageResource(R.mipmap.ic_launcher) }, LinearLayout.LayoutParams(dp(56), dp(56)))
        addView(LinearLayout(this@SetupActivity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, 0, 0)
            addView(text("Curbside", null, 26f, bold = true))
            addView(text("Web and video on your Android Auto screen", muted, 14f))
        })
    }

    private fun card(title: String, vararg body: View) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(surface, 16)
        setPadding(dp(16), dp(14), dp(16), dp(14))
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp(12) }
        addView(text(title, null, 18f, bold = true).apply { setPadding(0, 0, 0, dp(8)) })
        body.forEach { addView(it) }
    }

    private fun step(n: Int, body: String) = LinearLayout(this).apply {
        setPadding(0, dp(6), 0, dp(6))
        addView(text("$n", Color.WHITE, 13f, bold = true).apply {
            gravity = Gravity.CENTER
            background = rounded(accent, 12)
        }, LinearLayout.LayoutParams(dp(24), dp(24)).apply { marginEnd = dp(12) })
        addView(text(body, null, 15f), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
    }

    private fun tip(name: String, body: String) = text("", null, 15f).apply {
        text = android.text.SpannableStringBuilder().append(name, android.text.style.StyleSpan(Typeface.BOLD), 0).append(": $body")
        setPadding(0, dp(4), 0, dp(4))
    }

    /** One bookmark row: title and URL fields with a remove button. */
    private fun addMark(title: String, url: String) {
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(4), 0, dp(4)) }
        val fields = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fields.addView(field(title, "Title", InputType.TYPE_TEXT_FLAG_CAP_WORDS))
        fields.addView(field(url, "Address, e.g. youtube.com", InputType.TYPE_TEXT_VARIATION_URI))
        row.addView(fields, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        row.addView(ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            background = null
            contentDescription = "Remove bookmark"
            setOnClickListener { marks.removeView(row) }
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
        marks.addView(row)
    }

    private fun field(value: String, hint: String, type: Int) = EditText(this).apply {
        setText(value)
        this.hint = hint
        inputType = InputType.TYPE_CLASS_TEXT or type
        isSingleLine = true
        textSize = 15f
    }

    private fun save(quiet: Boolean = false) {
        val entries = (0 until marks.childCount).mapNotNull { i ->
            val fields = (marks.getChildAt(i) as LinearLayout).getChildAt(0) as LinearLayout
            val (t, u) = (0..1).map { (fields.getChildAt(it) as EditText).text.toString().trim() }
            if (u.isEmpty()) null else Bookmarks.Entry(t.ifEmpty { u }, toUrl(u))
        }
        Bookmarks.save(this, entries)
        setHomeUrl(this, home.text.toString())
        if (!quiet) Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
    }

    private fun text(s: String, color: Int?, size: Float, bold: Boolean = false) = TextView(this).apply {
        text = s
        textSize = size
        color?.let { setTextColor(it) }
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun rounded(color: Int, radiusDp: Int) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radiusDp).toFloat()
    }
}
