// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.keyboard.clipboard

import android.content.Context
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isGone
import androidx.core.view.isVisible
import helium314.keyboard.latin.R
import helium314.keyboard.latin.database.ClipboardFolder

/**
 * Bar above the clipboard list. Shows the folder chips, or the text field line while a name is typed.
 */
class ClipboardFolderBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {

    var onFilterSelected: ((FolderFilter) -> Unit)? = null
    var onNewFolder: (() -> Unit)? = null
    var onRenameFolder: ((ClipboardFolder) -> Unit)? = null
    var onConfirmInput: (() -> Unit)? = null
    var onCancelInput: (() -> Unit)? = null

    private var keyBackgroundId = 0
    private var textColor = 0
    private var typeface: Typeface? = null
    private var textSize = 0f
    private val margin = (4 * context.resources.displayMetrics.density).toInt()
    private val padding = (10 * context.resources.displayMetrics.density).toInt()

    private val chipScroll = HorizontalScrollView(context)
    private val chipRow = LinearLayout(context).apply { orientation = HORIZONTAL }
    private val inputLabel = TextView(context).apply {
        gravity = Gravity.CENTER_VERTICAL
        setSingleLine(true)
        isGone = true
    }
    private val confirmButton = keyText("✓").apply { isGone = true; setOnClickListener { onConfirmInput?.invoke() } }
    private val cancelButton = keyText("✕").apply { isGone = true; setOnClickListener { onCancelInput?.invoke() } }

    init {
        orientation = HORIZONTAL
        chipScroll.addView(chipRow)
        addView(chipScroll, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        addView(inputLabel, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        addView(confirmButton, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
        addView(cancelButton, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
    }

    fun setStyle(keyBackgroundId: Int, textColor: Int, typeface: Typeface?, textSize: Float) {
        this.keyBackgroundId = keyBackgroundId
        this.textColor = textColor
        this.typeface = typeface
        this.textSize = textSize
        inputLabel.setTextColor(textColor)
        inputLabel.typeface = typeface
        inputLabel.setTextSize(TypedValue.COMPLEX_UNIT_PX, textSize)
        listOf(confirmButton, cancelButton).forEach {
            it.setTextColor(textColor)
            it.typeface = typeface
            it.setTextSize(TypedValue.COMPLEX_UNIT_PX, textSize)
            it.setBackgroundResource(keyBackgroundId)
        }
    }

    /** shows the folder chips. [selected] is highlighted. */
    fun showChips(folders: List<ClipboardFolder>, selected: FolderFilter) {
        chipScroll.isVisible = true
        inputLabel.isGone = true
        confirmButton.isGone = true
        cancelButton.isGone = true
        chipRow.removeAllViews()
        chipRow.addView(chip(context.getString(R.string.clipboard_folder_all), selected == FolderFilter.All) {
            onFilterSelected?.invoke(FolderFilter.All)
        })
        chipRow.addView(chip(context.getString(R.string.clipboard_folder_unfiled), selected == FolderFilter.Unfiled) {
            onFilterSelected?.invoke(FolderFilter.Unfiled)
        })
        folders.forEach { folder ->
            val folderChip = chip(folder.name, selected == FolderFilter.Folder(folder.id)) {
                onFilterSelected?.invoke(FolderFilter.Folder(folder.id))
            }
            folderChip.setOnLongClickListener {
                onRenameFolder?.invoke(folder)
                true
            }
            chipRow.addView(folderChip)
        }
        chipRow.addView(chip("+", false) { onNewFolder?.invoke() }.apply {
            contentDescription = context.getString(R.string.clipboard_folder_new)
        })
    }

    /** shows the text field line with [text] while a name is typed */
    fun showInput(text: String, confirmEnabled: Boolean) {
        chipScroll.isGone = true
        inputLabel.text = text.ifEmpty { context.getString(R.string.clipboard_folder_name_hint) }
        inputLabel.alpha = if (text.isEmpty()) 0.5f else 1f
        inputLabel.isVisible = true
        confirmButton.isVisible = true
        confirmButton.alpha = if (confirmEnabled) 1f else 0.4f
        cancelButton.isVisible = true
    }

    private fun chip(label: String, selected: Boolean, onClick: () -> Unit): TextView =
        keyText(label).apply {
            alpha = if (selected) 1f else 0.6f
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT).apply {
                marginEnd = margin
                marginStart = margin
            }
        }

    private fun keyText(label: String): TextView = TextView(context).apply {
        text = label
        gravity = Gravity.CENTER
        setSingleLine(true)
        setPadding(padding, 0, padding, 0)
        setBackgroundResource(keyBackgroundId)
        setTextColor(textColor)
        this.typeface = this@ClipboardFolderBar.typeface
        setTextSize(TypedValue.COMPLEX_UNIT_PX, textSize)
        layoutParams = LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT)
        isClickable = true
        isFocusable = false
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
    }
}
