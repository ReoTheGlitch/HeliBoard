// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.keyboard.clipboard

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.isGone
import androidx.core.view.isVisible
import helium314.keyboard.latin.R
import helium314.keyboard.latin.common.ColorType
import helium314.keyboard.latin.database.ClipboardFolder
import helium314.keyboard.latin.settings.Settings

/**
 * Bar above the clipboard list. Shows the folder chips and the search button,
 * or the text field while a folder name or search text is typed,
 * or the actions for the selected clips.
 */
class ClipboardFolderBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {

    var onFilterSelected: ((FolderFilter) -> Unit)? = null
    var onNewFolder: (() -> Unit)? = null
    /** long-press on a folder chip, for renaming or deleting the folder */
    var onFolderLongClick: ((ClipboardFolder) -> Unit)? = null
    var onSelectionPaste: (() -> Unit)? = null
    var onSelectionEdit: (() -> Unit)? = null
    var onConfirmInput: (() -> Unit)? = null
    var onCancelInput: (() -> Unit)? = null
    var onSearch: (() -> Unit)? = null
    var onInputClick: (() -> Unit)? = null
    var onSelectionPin: (() -> Unit)? = null
    var onSelectionMove: (() -> Unit)? = null
    var onSelectionCopy: (() -> Unit)? = null
    var onSelectionCancel: (() -> Unit)? = null
    var onSelectAll: (() -> Unit)? = null
    /** called with the folder to move the selected clips to, null for no folder */
    var onMoveTarget: ((Long?) -> Unit)? = null
    /** the selected clips are moved to a folder that is created first */
    var onMoveToNewFolder: (() -> Unit)? = null

    private var textColor = Color.BLACK
    private var accentColor = Color.BLUE
    private var onAccentColor = Color.WHITE
    private var typeface: Typeface? = null
    private var textSize = 0f
    private val density = context.resources.displayMetrics.density
    private val gap = (3 * density).toInt()
    private val chipPadding = (12 * density).toInt()
    private val iconPadding = (8 * density).toInt()

    private val chipScroll = HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false }
    private val chipRow = LinearLayout(context).apply { orientation = HORIZONTAL }
    private val searchButton = iconButton(R.drawable.sym_keyboard_search_lxx, R.string.clipboard_search) { onSearch?.invoke() }

    // text field line: icon, text, then the buttons
    private val inputField = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        isGone = true
        setOnClickListener { onInputClick?.invoke() }
    }
    private val inputIcon = ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
    private val inputLabel = TextView(context).apply {
        gravity = Gravity.CENTER_VERTICAL
        setSingleLine(true)
        ellipsize = TextUtils.TruncateAt.START // the end of the typed text stays visible
    }
    private val selectResultsButton = iconButton(R.drawable.ic_select_all, R.string.clipboard_selection_all) { onSelectAll?.invoke() }
    private val confirmButton = iconButton(R.drawable.sym_keyboard_done_lxx, android.R.string.ok) { onConfirmInput?.invoke() }
    private val cancelButton = iconButton(R.drawable.ic_close, R.string.clipboard_close) { onCancelInput?.invoke() }

    // selection line: close, count, then the actions
    private val selectionRow = LinearLayout(context).apply { orientation = HORIZONTAL; isGone = true }
    private val closeSelectionButton = iconButton(R.drawable.ic_close, R.string.clipboard_close) { onSelectionCancel?.invoke() }
    private val selectionCount = TextView(context).apply {
        gravity = Gravity.CENTER_VERTICAL
        setSingleLine(true)
        ellipsize = TextUtils.TruncateAt.END
    }
    private val allButton = iconButton(R.drawable.ic_select_all, R.string.clipboard_selection_all) { onSelectAll?.invoke() }
    private val pinButton = iconButton(R.drawable.ic_clipboard_pin_lxx, R.string.clipboard_selection_pin) { onSelectionPin?.invoke() }
    private val moveButton = iconButton(R.drawable.ic_clipboard_folder_move, R.string.clipboard_selection_move) { onSelectionMove?.invoke() }
    private val copyButton = iconButton(R.drawable.sym_keyboard_copy, R.string.clipboard_selection_copy) { onSelectionCopy?.invoke() }
    private val pasteButton = iconButton(R.drawable.sym_keyboard_paste, R.string.clipboard_selection_paste) { onSelectionPaste?.invoke() }
    private val editButton = iconButton(R.drawable.ic_edit, R.string.clipboard_edit_clip) { onSelectionEdit?.invoke() }
    // the actions scroll if they do not fit, close and count always stay visible
    private val selectionActions = LinearLayout(context).apply { orientation = HORIZONTAL }

    // a text with some actions, used for the folder actions, questions and undo
    private val actionRow = LinearLayout(context).apply { orientation = HORIZONTAL; isGone = true }
    private val actionLabel = TextView(context).apply {
        gravity = Gravity.CENTER_VERTICAL
        setSingleLine(true)
        ellipsize = TextUtils.TruncateAt.END
    }
    private val actionButtons = LinearLayout(context).apply { orientation = HORIZONTAL }

    /** an action shown by [showActions] */
    class BarAction(val iconId: Int, val descriptionId: Int, val onClick: () -> Unit)
    private val moveLabel = TextView(context).apply {
        gravity = Gravity.CENTER_VERTICAL
        setSingleLine(true)
        setText(R.string.clipboard_move_to)
        isGone = true
    }

    private val iconButtons get() = listOf(searchButton, selectResultsButton, confirmButton, cancelButton,
        closeSelectionButton, allButton, pinButton, moveButton, copyButton, pasteButton, editButton)

    init {
        orientation = HORIZONTAL
        setPadding(gap, 0, gap, 0)
        val wrap = LayoutParams.WRAP_CONTENT
        val match = LayoutParams.MATCH_PARENT
        addView(moveLabel, LayoutParams(wrap, match).apply { marginStart = chipPadding / 2; marginEnd = chipPadding / 2 })
        chipScroll.addView(chipRow, LayoutParams(wrap, match))
        addView(chipScroll, LayoutParams(0, match, 1f))
        addView(searchButton)
        inputField.addView(inputIcon)
        inputField.addView(inputLabel, LayoutParams(0, match, 1f))
        addView(inputField, LayoutParams(0, match, 1f).apply { setMargins(gap, gap, gap, gap) })
        addView(selectResultsButton)
        addView(confirmButton)
        addView(cancelButton)
        selectionRow.addView(closeSelectionButton)
        selectionRow.addView(selectionCount, LayoutParams(wrap, match).apply { marginStart = chipPadding / 3; marginEnd = chipPadding / 2 })
        listOf(allButton, editButton, pinButton, moveButton, copyButton, pasteButton).forEach { selectionActions.addView(it) }
        val actionScroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            isFillViewport = true
            addView(selectionActions.apply { gravity = Gravity.END }, LayoutParams(wrap, match))
        }
        selectionRow.addView(actionScroll, LayoutParams(0, match, 1f))
        addView(selectionRow, LayoutParams(0, match, 1f))
        actionRow.addView(actionLabel, LayoutParams(0, match, 1f).apply { marginStart = chipPadding })
        actionRow.addView(actionButtons, LayoutParams(wrap, match))
        addView(actionRow, LayoutParams(0, match, 1f))
    }

    @Suppress("UNUSED_PARAMETER") // the key background is not used any more, chips have their own shape
    fun setStyle(keyBackgroundId: Int, textColor: Int, typeface: Typeface?, textSize: Float) {
        this.textColor = textColor
        this.typeface = typeface
        this.textSize = textSize
        val colors = Settings.getValues().mColors
        accentColor = colors.get(ColorType.ACTION_KEY_BACKGROUND)
        onAccentColor = colors.get(ColorType.ACTION_KEY_ICON)
        listOf(inputLabel, selectionCount, moveLabel, actionLabel).forEach {
            it.setTextColor(textColor)
            it.typeface = typeface
            it.setTextSize(TypedValue.COMPLEX_UNIT_PX, textSize)
        }
        moveLabel.alpha = 0.7f
        iconButtons.forEach {
            it.setColorFilter(textColor)
            it.background = pressedBackground()
        }
        inputIcon.setColorFilter(textColor)
        inputIcon.alpha = 0.7f
        val iconSize = (textSize * 1.2f).toInt()
        inputIcon.layoutParams = LayoutParams(iconSize, iconSize).apply { marginStart = chipPadding; marginEnd = chipPadding / 2 }
        inputField.background = pill(overlay(0.10f))
        inputLabel.setPadding(0, 0, chipPadding, 0)
    }

    /** shows the folder chips. [selected] is highlighted. */
    fun showChips(folders: List<ClipboardFolder>, selected: FolderFilter) {
        show(chips = true, search = true)
        chipRow.removeAllViews()
        chipRow.addView(chip(context.getString(R.string.clipboard_folder_all), selected == FolderFilter.All) {
            onFilterSelected?.invoke(FolderFilter.All)
        })
        chipRow.addView(chip(context.getString(R.string.clipboard_folder_unfiled), selected == FolderFilter.Unfiled) {
            onFilterSelected?.invoke(FolderFilter.Unfiled)
        })
        folders.forEach { folder ->
            val isSelected = selected == FolderFilter.Folder(folder.id)
            val folderChip = chip(folder.name, isSelected, R.drawable.ic_clipboard_folder) {
                onFilterSelected?.invoke(FolderFilter.Folder(folder.id))
            }
            folderChip.setOnLongClickListener {
                onFolderLongClick?.invoke(folder)
                true
            }
            chipRow.addView(folderChip)
            if (isSelected) chipScroll.post { chipScroll.requestChildRectangleOnScreen(folderChip, android.graphics.Rect(0, 0, folderChip.width, folderChip.height), true) }
        }
        chipRow.addView(iconButton(R.drawable.ic_plus, R.string.clipboard_folder_new) { onNewFolder?.invoke() }.apply {
            setColorFilter(textColor)
            background = pressedBackground()
        })
    }

    /**
     * shows the text field with [text] instead of the chips. [hint] is shown when [text] is empty.
     * [editing] shows the cursor. [isSearch] selects the search icon, else the folder icon is shown.
     * [showConfirm] adds the confirm button, [confirmEnabled] tells whether it can be used.
     * [showSelectAll] adds the button that selects all shown clips (the search results).
     */
    fun showInput(text: String, hint: String, editing: Boolean, isSearch: Boolean, showConfirm: Boolean, confirmEnabled: Boolean, showSelectAll: Boolean = false) {
        show(input = true)
        inputIcon.setImageResource(if (isSearch) R.drawable.sym_keyboard_search_lxx else R.drawable.ic_clipboard_folder)
        val shown = SpannableStringBuilder()
        if (text.isEmpty()) {
            if (editing) shown.append("|", ForegroundColorSpan(accentColor), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            shown.append(hint, ForegroundColorSpan(ColorUtils.setAlphaComponent(textColor, 128)), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        } else {
            shown.append(text)
            if (editing) shown.append("|", ForegroundColorSpan(accentColor), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        inputLabel.text = shown
        inputLabel.contentDescription = text.ifEmpty { hint }
        selectResultsButton.isVisible = showSelectAll
        confirmButton.isVisible = showConfirm
        confirmButton.alpha = if (confirmEnabled) 1f else 0.4f
    }

    /** shows the selection actions instead of the chips. [canEdit] shows the edit action (one text clip is selected). */
    fun showSelection(count: Int, canEdit: Boolean) {
        show(selection = true)
        selectionCount.text = count.toString()
        selectionCount.contentDescription = context.getString(R.string.clipboard_selected_count, count)
        editButton.isVisible = canEdit
        listOf(pinButton, moveButton, copyButton, pasteButton).forEach {
            it.alpha = if (count > 0) 1f else 0.4f
            it.isEnabled = count > 0
        }
    }

    /** shows [label] with [actions] and the close button, instead of the chips */
    fun showActions(label: String, actions: List<BarAction>) {
        show(actions = true)
        actionLabel.text = label
        actionButtons.removeAllViews()
        actions.forEach { action ->
            actionButtons.addView(iconButton(action.iconId, action.descriptionId, action.onClick).apply {
                setColorFilter(textColor)
                background = pressedBackground()
            })
        }
    }

    /** shows the folders as targets for moving the selected clips, with a cancel button */
    fun showMoveTargets(folders: List<ClipboardFolder>) {
        show(chips = true, moveTargets = true)
        chipRow.removeAllViews()
        chipRow.addView(chip(context.getString(R.string.clipboard_folder_new_entry), false) { onMoveToNewFolder?.invoke() })
        chipRow.addView(chip(context.getString(R.string.clipboard_selection_no_folder), false) { onMoveTarget?.invoke(null) })
        folders.forEach { folder ->
            chipRow.addView(chip(folder.name, false, R.drawable.ic_clipboard_folder) { onMoveTarget?.invoke(folder.id) })
        }
        chipScroll.scrollTo(0, 0)
    }

    private fun show(chips: Boolean = false, search: Boolean = false, input: Boolean = false, selection: Boolean = false,
                     moveTargets: Boolean = false, actions: Boolean = false) {
        actionRow.isVisible = actions
        chipScroll.isVisible = chips
        searchButton.isVisible = search
        inputField.isVisible = input
        cancelButton.isVisible = input || moveTargets || actions
        moveLabel.isVisible = moveTargets
        selectionRow.isVisible = selection
        if (!input) {
            selectResultsButton.isGone = true
            confirmButton.isGone = true
        }
    }

    private fun overlay(alpha: Float) = ColorUtils.setAlphaComponent(textColor, (alpha * 255).toInt())

    private fun pill(color: Int): Drawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = 100 * density
        setColor(color)
    }

    private fun pressedBackground(): Drawable = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_pressed), pill(overlay(0.2f)))
        addState(intArrayOf(), pill(Color.TRANSPARENT))
    }

    private fun chip(label: String, selected: Boolean, iconId: Int = 0, onClick: () -> Unit): TextView = TextView(context).apply {
        text = label
        gravity = Gravity.CENTER
        setSingleLine(true)
        maxWidth = (160 * density).toInt()
        ellipsize = TextUtils.TruncateAt.END
        setPadding(chipPadding, 0, chipPadding, 0)
        val foreground = if (selected) onAccentColor else textColor
        setTextColor(foreground)
        this.typeface = this@ClipboardFolderBar.typeface
        setTextSize(TypedValue.COMPLEX_UNIT_PX, textSize)
        background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), pill(if (selected) accentColor else overlay(0.25f)))
            addState(intArrayOf(), pill(if (selected) accentColor else overlay(0.10f)))
        }
        if (iconId != 0) ContextCompat.getDrawable(context, iconId)?.mutate()?.let {
            val size = (textSize * 0.9f).toInt()
            it.setBounds(0, 0, size, size)
            it.setTint(foreground)
            it.alpha = 190
            setCompoundDrawablesRelative(it, null, null, null)
            compoundDrawablePadding = chipPadding / 3
        }
        isSelected = selected
        isClickable = true
        isFocusable = false
        setOnClickListener { onClick() }
        layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT).apply { setMargins(gap, gap * 2, gap, gap * 2) }
    }

    // icon only, a long-press shows what it does
    private fun iconButton(iconId: Int, descriptionId: Int, onClick: () -> Unit): ImageView = ImageView(context).apply {
        setImageResource(iconId)
        scaleType = ImageView.ScaleType.FIT_CENTER
        contentDescription = context.getString(descriptionId)
        setPadding(iconPadding, iconPadding, iconPadding, iconPadding)
        isClickable = true
        isFocusable = false
        setOnClickListener { onClick() }
        setOnLongClickListener {
            Toast.makeText(context, contentDescription, Toast.LENGTH_SHORT).show()
            true
        }
        layoutParams = LayoutParams((48 * density).toInt(), LayoutParams.MATCH_PARENT)
    }
}
