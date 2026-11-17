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
 * Small keyboard inside the clipboard panel, used to type folder names and the search text.
 * It is used because input methods can not get text input focus for an EditText in their own window.
 */
class ClipboardMiniKeyboard @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {

    /** called with the typed character, [SPACE_KEY] or [BACKSPACE_KEY] */
    var onKey: ((String) -> Unit)? = null

    private val rows = mutableListOf<LinearLayout>()
    private val keys = mutableListOf<TextView>()
    private val margin = (2 * context.resources.displayMetrics.density).toInt()

    private val topRowKeys = mutableListOf<TextView>() // show letters or digits
    private var digitsShown = false

    init {
        orientation = VERTICAL
        ROWS.forEachIndexed { index, row ->
            val rowView = LinearLayout(context).apply { orientation = HORIZONTAL }
            if (index == 2) rowView.addView(key(DIGITS_KEY, 1.5f))
            row.forEach { label -> rowView.addView(key(label, 1f).also { if (index == 0) topRowKeys.add(it) }) }
            if (index == 1) rowView.addView(key(SPACE_KEY, 2f))
            if (index == 2) rowView.addView(key(BACKSPACE_KEY, 1.5f))
            rows.add(rowView)
            addView(rowView)
        }
    }

    /** the top row shows the letters again */
    fun reset() {
        if (digitsShown) toggleDigits()
    }

    private fun toggleDigits() {
        digitsShown = !digitsShown
        val labels = if (digitsShown) DIGITS else ROWS[0]
        topRowKeys.forEachIndexed { index, key -> key.text = labels[index] }
    }

    fun setStyle(keyBackgroundId: Int, textColor: Int, typeface: Typeface?, textSize: Float, rowHeight: Int) {
        keys.forEach {
            it.setBackgroundResource(keyBackgroundId)
            Settings.getValues().mColors.setBackground(it, ColorType.KEY_BACKGROUND)
            it.setTextColor(textColor)
            it.typeface = typeface
            it.setTextSize(TypedValue.COMPLEX_UNIT_PX, textSize)
        }
        rows.forEach {
            it.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, rowHeight)
        }
    }

    private fun key(label: String, weight: Float): TextView = TextView(context).apply {
        text = if (label == SPACE_KEY) SPACE_LABEL else label
        gravity = Gravity.CENTER
        setSingleLine(true)
        isClickable = true
        isFocusable = false
        layoutParams = LayoutParams(0, LayoutParams.MATCH_PARENT, weight).apply {
            setMargins(margin, margin, margin, margin)
        }
        setOnClickListener {
            if (label == DIGITS_KEY) toggleDigits()
            else onKey?.invoke(if (label == SPACE_KEY) SPACE_KEY else text.toString())
        }
        keys.add(this)
    }

    companion object {
        const val SPACE_KEY = " "
        const val BACKSPACE_KEY = "⌫"
        private const val DIGITS_KEY = "123"
        private const val SPACE_LABEL = "␣" // shown on the space key, so it is visible in themes without key borders
        const val ROW_COUNT = 3
        private val DIGITS = "1234567890".map { it.toString() }
        private val ROWS = listOf(
            "qwertyuiop".map { it.toString() },
            "asdfghjkl".map { it.toString() },
            "zxcvbnm".map { it.toString() },
        )
    }
}
