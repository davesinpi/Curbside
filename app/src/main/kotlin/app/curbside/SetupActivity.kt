package app.curbside

import android.app.Activity
import android.os.Bundle
import android.text.InputType
import android.webkit.WebView
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * Phone-side first-run only (design section 4): a short enable guide and a bookmark editor.
 * No AppCompat, no layout XML, no view binding; plain platform views keep the APK small.
 */
class SetupActivity : Activity() {

    private lateinit var editor: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        runCatching { WebView.getCurrentWebViewPackage() } // warm the provider early

        val pad = (16 * resources.displayMetrics.density).toInt()
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        column.addView(TextView(this).apply {
            text = "Curbside"
            textSize = 24f
        })
        column.addView(TextView(this).apply {
            text = GUIDE
            setPadding(0, pad, 0, pad)
        })
        column.addView(TextView(this).apply {
            text = "Bookmarks (one per line, \"Title | url\", up to $MAX_BOOKMARKS):"
        })

        editor = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            isSingleLine = false
            minLines = 4
            setText(Bookmarks.list(this@SetupActivity).joinToString("\n") { "${it.title} | ${it.url}" })
        }
        column.addView(editor)

        column.addView(Button(this).apply {
            text = "Save bookmarks"
            setOnClickListener { save() }
        })

        setContentView(ScrollView(this).apply { addView(column) })
    }

    private fun save() {
        val entries = editor.text.lines().mapNotNull { line ->
            line.split("|", limit = 2).map { it.trim() }.takeIf { it.size == 2 && it[1].isNotEmpty() }
                ?.let { Bookmarks.Entry(it[0].ifEmpty { it[1] }, toUrl(it[1])) }
        }
        Bookmarks.save(this, entries)
        Toast.makeText(this, "Saved ${entries.size}", Toast.LENGTH_SHORT).show()
    }

    private companion object {
        const val GUIDE =
            "Curbside shows a web browser on your car screen through Android Auto.\n\n" +
            "To use it:\n" +
            "1. In the Android Auto app, open Settings and enable developer / unknown sources.\n" +
            "2. Connect your phone to the car (or start the Desktop Head Unit).\n" +
            "3. Open Curbside from the car's app launcher.\n\n" +
            "Type an address or search with the car's keyboard or voice. Tap, scroll and " +
            "pinch-zoom work from the car screen."
    }
}
