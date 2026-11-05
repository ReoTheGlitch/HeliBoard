// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.keyboard.clipboard

import android.content.Context
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView

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

    init {
        orientation = VERTICAL
        for (row in ROWS) {
            val rowView = LinearLayout(context).apply { orientation = HORIZONTAL }
            row.forEach { label -> rowView.addView(key(label, 1f)) }
            if (row == ROWS[2]) rowView.addView(key(SPACE_KEY, 2f))
            if (row == ROWS[3]) rowView.addView(key(BACKSPACE_KEY, 1.5f))
            rows.add(rowView)
            addView(rowView)
        }
    }

    fun setStyle(keyBackgroundId: Int, textColor: Int, typeface: Typeface?, textSize: Float, rowHeight: Int) {
        keys.forEach {
            it.setBackgroundResource(keyBackgroundId)
            it.setTextColor(textColor)
            it.typeface = typeface
            it.setTextSize(TypedValue.COMPLEX_UNIT_PX, textSize)
        }
        rows.forEach {
            it.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, rowHeight)
        }
    }

    private fun key(label: String, weight: Float): TextView = TextView(context).apply {
        text = label
        tag = label
        gravity = Gravity.CENTER
        setSingleLine(true)
        isClickable = true
        isFocusable = false
        layoutParams = LayoutParams(0, LayoutParams.MATCH_PARENT, weight).apply {
            setMargins(margin, margin, margin, margin)
        }
        setOnClickListener { onKey?.invoke(label) }
        keys.add(this)
    }

    companion object {
        const val SPACE_KEY = " "
        const val BACKSPACE_KEY = "⌫"
        private val ROWS = listOf(
            "1234567890".map { it.toString() },
            "qwertyuiop".map { it.toString() },
            "asdfghjkl".map { it.toString() },
            "zxcvbnm".map { it.toString() },
        )
    }
}
