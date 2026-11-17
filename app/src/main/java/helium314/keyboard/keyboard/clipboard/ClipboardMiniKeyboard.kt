// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.keyboard.clipboard

import android.content.Context
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import helium314.keyboard.latin.common.ColorType
import helium314.keyboard.latin.settings.Settings

/**
 * Keyboard inside the clipboard panel, used to type folder names and the search text.
 * It is used because input methods can not get text input focus for an EditText in their own window.
 * It has letters with shift, two pages of digits and symbols, and a page of emoticons.
 */
class ClipboardMiniKeyboard @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {

    /** called with the typed text (one character, or an emoticon), [BACKSPACE_KEY] or [CLEAR_KEY] */
    var onKey: ((String) -> Unit)? = null

    private enum class Page { LETTERS, SYMBOLS, MORE_SYMBOLS, EMOTICONS }
    private var page = Page.LETTERS
    private var shifted = false

    private var keyBackgroundId = 0
    private var textColor = 0
    private var typeface: Typeface? = null
    private var textSize = 0f
    private var rowHeight = 0
    private val margin = (2 * context.resources.displayMetrics.density).toInt()

    init {
        orientation = VERTICAL
    }

    fun setStyle(keyBackgroundId: Int, textColor: Int, typeface: Typeface?, textSize: Float, rowHeight: Int) {
        this.keyBackgroundId = keyBackgroundId
        this.textColor = textColor
        this.typeface = typeface
        this.textSize = textSize
        this.rowHeight = rowHeight
        build()
    }

    /** back to the letters */
    fun reset() {
        if (page == Page.LETTERS && !shifted) return
        page = Page.LETTERS
        shifted = false
        build()
    }

    private fun build() {
        removeAllViews()
        val rows = when (page) {
            Page.LETTERS -> LETTERS.map { row -> row.map { if (shifted) it.uppercase() else it } }
            Page.SYMBOLS -> SYMBOLS
            Page.MORE_SYMBOLS -> MORE_SYMBOLS
            Page.EMOTICONS -> EMOTICONS
        }
        rows.forEachIndexed { index, row ->
            val rowView = newRow()
            if (index == 2) when (page) {
                Page.LETTERS -> rowView.addView(key(SHIFT_KEY, 1.5f, highlighted = shifted))
                Page.SYMBOLS -> rowView.addView(key(MORE_SYMBOLS_KEY, 1.5f))
                Page.MORE_SYMBOLS -> rowView.addView(key(SYMBOLS_KEY, 1.5f))
                Page.EMOTICONS -> Unit
            }
            row.forEach { rowView.addView(key(it, 1f)) }
            if (index == 2) rowView.addView(key(BACKSPACE_KEY, 1.5f))
        }
        // bottom row: page keys, space, some punctuation
        val bottom = newRow()
        bottom.addView(key(if (page == Page.LETTERS) SYMBOLS_KEY else LETTERS_KEY, 1.5f))
        bottom.addView(key(if (page == Page.EMOTICONS) SYMBOLS_KEY else EMOTICONS_KEY, 1.5f))
        bottom.addView(key(",", 1f))
        bottom.addView(key(SPACE_LABEL, 4f))
        bottom.addView(key(".", 1f))
        bottom.addView(key("?", 1f))
    }

    private fun newRow() = LinearLayout(context).apply {
        orientation = HORIZONTAL
        this@ClipboardMiniKeyboard.addView(this, LayoutParams(LayoutParams.MATCH_PARENT, rowHeight))
    }

    private fun onClick(label: String) {
        when (label) {
            SHIFT_KEY -> { shifted = !shifted; build() }
            SYMBOLS_KEY -> { page = Page.SYMBOLS; build() }
            MORE_SYMBOLS_KEY -> { page = Page.MORE_SYMBOLS; build() }
            LETTERS_KEY -> { page = Page.LETTERS; build() }
            EMOTICONS_KEY -> { page = Page.EMOTICONS; build() }
            SPACE_LABEL -> onKey?.invoke(" ")
            else -> {
                onKey?.invoke(label)
                if (shifted && page == Page.LETTERS && label != BACKSPACE_KEY) { // shift is for one letter
                    shifted = false
                    build()
                }
            }
        }
    }

    private fun key(label: String, weight: Float, highlighted: Boolean = false): TextView = TextView(context).apply {
        text = label
        gravity = Gravity.CENTER
        setSingleLine(true)
        isClickable = true
        isFocusable = false
        setTextColor(textColor)
        typeface = this@ClipboardMiniKeyboard.typeface
        // emoticons are longer than one character and need a smaller text
        setTextSize(TypedValue.COMPLEX_UNIT_PX, if (label.length > 2 && page == Page.EMOTICONS) textSize * 0.8f else textSize)
        if (keyBackgroundId != 0) {
            setBackgroundResource(keyBackgroundId)
            Settings.getValues().mColors.setBackground(this, if (highlighted) ColorType.ACTION_KEY_BACKGROUND else ColorType.KEY_BACKGROUND)
        }
        layoutParams = LayoutParams(0, LayoutParams.MATCH_PARENT, weight).apply {
            setMargins(margin, margin, margin, margin)
        }
        setOnClickListener { onClick(label) }
        if (label == BACKSPACE_KEY) setOnLongClickListener {
            onKey?.invoke(CLEAR_KEY) // a long-press removes all the text
            true
        }
    }

    companion object {
        const val BACKSPACE_KEY = "⌫"
        const val CLEAR_KEY = "clear"
        const val ROW_COUNT = 4
        private const val SHIFT_KEY = "⇧"
        private const val SYMBOLS_KEY = "?123"
        private const val MORE_SYMBOLS_KEY = "=\\<"
        private const val LETTERS_KEY = "ABC"
        private const val EMOTICONS_KEY = ":-)"
        private const val SPACE_LABEL = "␣" // shown on the space key, so it is visible in themes without key borders
        private fun row(keys: String) = keys.map { it.toString() }
        private val LETTERS = listOf(row("qwertyuiop"), row("asdfghjkl"), row("zxcvbnm"))
        private val SYMBOLS = listOf(row("1234567890"), row("@#\$_&-+()/"), row("*\"':;!~"))
        private val MORE_SYMBOLS = listOf(row("`|•√π÷×¶∆°"), row("£¥€¢^={}\\%"), row("©®™✓[]<>"))
        private val EMOTICONS = listOf(
            listOf("\\o/", ":3", "TwT", "^_^", ">_<"),
            listOf("T_T", "UwU", "OwO", "xD", "<3"),
            listOf(":)", ":(", ";)", ":D", ":P"),
        )
    }
}
