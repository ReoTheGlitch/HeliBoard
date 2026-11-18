// SPDX-License-Identifier: GPL-3.0-only

package helium314.keyboard.keyboard.clipboard

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import kotlin.math.abs
import helium314.keyboard.event.HapticEvent
import helium314.keyboard.keyboard.KeyboardActionListener
import helium314.keyboard.keyboard.KeyboardElement
import helium314.keyboard.keyboard.KeyboardLayoutSet
import helium314.keyboard.keyboard.KeyboardSwitcher
import helium314.keyboard.keyboard.KeyboardTypeface
import helium314.keyboard.keyboard.MainKeyboardView
import helium314.keyboard.keyboard.PointerTracker
import helium314.keyboard.keyboard.internal.KeyDrawParams
import helium314.keyboard.keyboard.internal.KeyVisualAttributes
import helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode
import helium314.keyboard.latin.ClipboardHistoryEntry
import helium314.keyboard.latin.ClipboardHistoryManager
import helium314.keyboard.latin.R
import helium314.keyboard.latin.common.ColorType
import helium314.keyboard.latin.common.Constants
import helium314.keyboard.latin.database.ClipboardDao
import helium314.keyboard.latin.database.ClipboardFolder
import helium314.keyboard.latin.utils.Log
import android.content.Intent
import android.os.Looper
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.ResourceUtils
import helium314.keyboard.latin.utils.ToolbarKey
import helium314.keyboard.latin.utils.createToolbarKey
import helium314.keyboard.latin.utils.getEnabledClipboardToolbarKeys
import helium314.keyboard.latin.utils.onClickToolbarKey
import helium314.keyboard.latin.utils.onLongClickToolbarKey
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.latin.utils.setToolbarButtonsActivatedStateOnPrefChange

@SuppressLint("CustomViewStyleable")
class ClipboardHistoryView @JvmOverloads constructor(
        context: Context,
        attrs: AttributeSet?,
        defStyle: Int = R.attr.clipboardHistoryViewStyle
) : LinearLayout(context, attrs, defStyle), View.OnClickListener,
    ClipboardDao.Listener, OnKeyEventListener,
    View.OnLongClickListener, SharedPreferences.OnSharedPreferenceChangeListener {

    private var clipboardLayoutParams = ClipboardLayoutParams(context)
    private val pinIconId: Int
    private val keyBackgroundId: Int

    private lateinit var clipboardRecyclerView: ClipboardHistoryRecyclerView
    private lateinit var placeholderView: TextView
    private val toolbarKeys = mutableListOf<ImageButton>()
    private lateinit var clipboardAdapter: ClipboardAdapter
    private lateinit var folderBar: ClipboardFolderBar
    private lateinit var miniKeyboard: ClipboardMiniKeyboard
    private lateinit var bottomRowKeyboard: MainKeyboardView

    lateinit var keyboardActionListener: KeyboardActionListener
    private lateinit var clipboardHistoryManager: ClipboardHistoryManager

    // which clips are shown, and the text typed in the folder name or search input
    private var folderFilter: FolderFilter = FolderFilter.All
    private var searchQuery: String? = null // not null while searching, then all folders are searched
    private var inputMode = InputMode.NONE
    private var renamingFolderId = 0L
    private val inputText = StringBuilder()

    // multi-select: long-press starts it, a tap toggles a clip, then pin, move or copy all the selected clips
    private var selectionActive = false
    private var moveTargetMode = false // selecting the folder to move the selected clips to
    private val selectedIds = mutableSetOf<Long>()
    private var selectedBeforeDrag: Set<Long> = emptySet() // drag-select adds the clips under the finger to these

    // a swiped clip is only hidden at first, and removed when the time for undo is over
    private var pendingDelete: ClipboardHistoryEntry? = null
    private val commitDeleteRunnable = Runnable { commitPendingDelete() }

    private var folderForActions: ClipboardFolder? = null // long-pressed folder, the bar shows rename and delete
    private var confirmFolderDelete = false
    private var listHasInputHeight: Boolean? = null

    private enum class InputMode { NONE, NEW_FOLDER, RENAME_FOLDER, SEARCH }

    companion object {
        private const val UNDO_TIME_MS = 5000L
        private const val MAX_SEARCH_LENGTH = 100
        private const val MAX_SCROLL_DISTANCE = 20
        private const val EMPTY_PLACEHOLDER = "¯\\_(ツ)_/¯" // same as in the layout
    }

    init {
        val clipboardViewAttr = context.obtainStyledAttributes(attrs,
                R.styleable.ClipboardHistoryView, defStyle, R.style.ClipboardHistoryView)
        pinIconId = clipboardViewAttr.getResourceId(R.styleable.ClipboardHistoryView_iconPinnedClip, 0)
        clipboardViewAttr.recycle()
        @SuppressLint("UseKtx") // suggestion does not work
        val keyboardViewAttr = context.obtainStyledAttributes(attrs, R.styleable.KeyboardView, defStyle, R.style.KeyboardView)
        keyBackgroundId = keyboardViewAttr.getResourceId(R.styleable.KeyboardView_keyBackground, 0)
        keyboardViewAttr.recycle()
        fitsSystemWindows = true
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val res = context.resources
        // The main keyboard expands to the entire this {@link KeyboardView}.
        val width = ResourceUtils.getKeyboardWidth(context, Settings.getValues()) + paddingLeft + paddingRight
        val height = ResourceUtils.getSecondaryKeyboardHeight(res, Settings.getValues()) + paddingTop + paddingBottom
        setMeasuredDimension(width, height)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun initialize() { // needs to be delayed for access to ClipboardStrip, which is not a child of this view
        if (this::clipboardAdapter.isInitialized) return
        val colors = Settings.getValues().mColors
        clipboardAdapter = ClipboardAdapter(clipboardLayoutParams, this).apply {
            itemBackgroundId = keyBackgroundId
            pinnedIconResId = pinIconId
        }
        placeholderView = findViewById(R.id.clipboard_empty_view)
        clipboardRecyclerView = findViewById<ClipboardHistoryRecyclerView>(R.id.clipboard_list).apply {
            val colCount = resources.getInteger(R.integer.config_clipboard_keyboard_col_count)
            layoutManager = StaggeredGridLayoutManager(colCount, StaggeredGridLayoutManager.VERTICAL)
            @Suppress("deprecation") // "no cache" should be fine according to warning in https://developer.android.com/reference/android/view/ViewGroup#setPersistentDrawingCache(int)
            persistentDrawingCache = PERSISTENT_NO_CACHE
            clipboardLayoutParams.setListProperties(this)
            placeholderView = this@ClipboardHistoryView.placeholderView
        }
        bottomRowKeyboard = findViewById(R.id.bottom_row_keyboard)
        miniKeyboard = findViewById<ClipboardMiniKeyboard>(R.id.clipboard_mini_keyboard).apply {
            onKey = { handleInputKey(it) }
        }
        folderBar = findViewById<ClipboardFolderBar>(R.id.clipboard_folder_bar).apply {
            layoutParams.height = clipboardLayoutParams.headerHeight
            onFilterSelected = { filter ->
                folderFilter = filter
                refreshFolderBar()
                refreshList(animate = false)
            }
            onNewFolder = { startInput(InputMode.NEW_FOLDER, "") }
            onFolderLongClick = { folder ->
                folderForActions = folder
                confirmFolderDelete = false
                updateInputState()
            }
            onSelectionPaste = { safely { pasteSelection() } }
            onSelectionEdit = { editSelection() }
            onConfirmInput = { confirmInput() }
            onCancelInput = {
                when {
                    folderForActions != null -> { folderForActions = null; confirmFolderDelete = false; updateInputState() }
                    pendingDelete != null && !selectionActive && inputMode == InputMode.NONE && searchQuery == null -> commitPendingDelete()
                    selectionActive && moveTargetMode -> { moveTargetMode = false; updateSelectionUi() }
                    selectionActive -> endSelection()
                    inputMode == InputMode.SEARCH || (inputMode == InputMode.NONE && searchQuery != null) -> clearSearch()
                    else -> stopInput()
                }
            }
            onSearch = { startInput(InputMode.SEARCH, searchQuery.orEmpty()) }
            onInputClick = {
                if (inputMode == InputMode.NONE && searchQuery != null) startInput(InputMode.SEARCH, searchQuery.orEmpty())
            }
            onSelectionPin = { safely { pinSelection() } }
            onSelectionMove = { moveTargetMode = true; updateSelectionUi() }
            onSelectionCopy = { safely { copySelection() } }
            onSelectionCancel = { endSelection() }
            onSelectAll = { selectAllShown() }
            onMoveTarget = { safely { moveSelectionTo(it) } }
        }
        clipboardAdapter.onSelectionStart = { id, position ->
            startSelection(id)
            clipboardRecyclerView.startDragSelect(position)
        }
        clipboardRecyclerView.onDragSelect = { from, to -> dragSelect(from, to) }
        clipboardAdapter.onEntrySwipedAway = { entry ->
            commitPendingDelete()
            pendingDelete = entry
            postDelayed(commitDeleteRunnable, UNDO_TIME_MS)
            updateInputState()
        }
        clipboardAdapter.onSelectionToggle = { toggleSelection(it) }
        val clipboardStrip = KeyboardSwitcher.getInstance().clipboardStrip!!
        getEnabledClipboardToolbarKeys(context.prefs()).forEach {
            val key = createToolbarKey(context, it)
            clipboardStrip.addView(key)
            key.setOnClickListener(this@ClipboardHistoryView)
            key.setOnLongClickListener(this@ClipboardHistoryView)
            colors.setColor(key, ColorType.TOOL_BAR_KEY)
            colors.setBackground(key, ColorType.STRIP_BACKGROUND)
            toolbarKeys.add(key)
        }
    }

    private fun setupClipKey(params: KeyDrawParams) {
        clipboardAdapter.apply {
            itemBackgroundId = keyBackgroundId
            itemTypeFace = params.mTypeface
            itemTextColor = params.mTextColor
            itemTextSize = params.mLabelSize.toFloat()
        }
    }

    private fun setupToolbarKeys() {
        // set layout params
        val toolbarKeyLayoutParams = LayoutParams(resources.getDimensionPixelSize(R.dimen.config_suggestions_strip_edge_key_width), LayoutParams.MATCH_PARENT)
        toolbarKeys.forEach { it.layoutParams = toolbarKeyLayoutParams }
    }

    private fun setupBottomRowKeyboard(editorInfo: EditorInfo, listener: KeyboardActionListener) {
        bottomRowKeyboard.setKeyboardActionListener(listener)
        PointerTracker.switchTo(bottomRowKeyboard)
        val kls = KeyboardLayoutSet.Builder.buildEmojiClipBottomRow(context, editorInfo)
        val keyboard = kls.getKeyboard(KeyboardElement.CLIPBOARD_BOTTOM_ROW)
        bottomRowKeyboard.setKeyboard(keyboard)
    }

    fun setHardwareAcceleratedDrawingEnabled(enabled: Boolean) {
        if (!enabled) return
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    fun startClipboardHistory(
            historyManager: ClipboardHistoryManager,
            keyVisualAttr: KeyVisualAttributes?,
            editorInfo: EditorInfo,
            keyboardActionListener: KeyboardActionListener
    ) {
        clipboardHistoryManager = historyManager
        initialize()
        // the keyboard size may have changed since the view was created (the adapter keeps the old gaps, that's ok)
        clipboardLayoutParams = ClipboardLayoutParams(context)
        folderBar.layoutParams.height = clipboardLayoutParams.headerHeight
        setupToolbarKeys()
        historyManager.prepareClipboardHistory()
        historyManager.setHistoryChangeListener(this)
        clipboardAdapter.clipboardHistoryManager = historyManager

        val params = KeyDrawParams()
        params.updateParams(clipboardLayoutParams.bottomRowKeyboardHeight, keyVisualAttr)
        val settings = Settings.getInstance()
        KeyboardTypeface.customTypeface()?.let { params.mTypeface = it }
        setupClipKey(params)
        setupBottomRowKeyboard(editorInfo, keyboardActionListener)
        folderBar.setStyle(keyBackgroundId, params.mTextColor, params.mTypeface, params.mLabelSize.toFloat())
        miniKeyboard.setStyle(keyBackgroundId, params.mTextColor, params.mTypeface, params.mLabelSize.toFloat(), clipboardLayoutParams.inputRowHeight)
        folderFilter = FolderFilter.All
        searchQuery = null
        inputMode = InputMode.NONE
        inputText.clear()
        selectionActive = false
        moveTargetMode = false
        selectedIds.clear()
        clipboardAdapter.setSelection(false, emptySet())
        listHasInputHeight = null

        placeholderView.apply {
            KeyboardTypeface.applyToTextView(this)
            setTextColor(params.mTextColor)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, params.mLabelSize.toFloat() * 2)
        }
        clipboardRecyclerView.apply {
            adapter = clipboardAdapter
            val keyboardWidth = ResourceUtils.getKeyboardWidth(context, settings.current)
            layoutParams.width = keyboardWidth
            clipboardLayoutParams.setListProperties(this)

            // set side padding
            val keyboardAttr = context.obtainStyledAttributes(
                null, R.styleable.Keyboard, R.attr.keyboardStyle, R.style.Keyboard)
            val leftPadding = (keyboardAttr.getFraction(R.styleable.Keyboard_keyboardLeftPadding,
                keyboardWidth, keyboardWidth, 0f)
                    * settings.current.mSidePaddingScale).toInt()
            val rightPadding =  (keyboardAttr.getFraction(R.styleable.Keyboard_keyboardRightPadding,
                keyboardWidth, keyboardWidth, 0f)
                    * settings.current.mSidePaddingScale).toInt()
            keyboardAttr.recycle()
            setPadding(leftPadding, paddingTop, rightPadding, paddingBottom)
        }
        updateInputState()
        refreshList(animate = false)

        // absurd workaround so Android sets the correct color from stateList (depending on "activated")
        toolbarKeys.forEach { it.isEnabled = false; it.isEnabled = true }
    }

    fun stopClipboardHistory() {
        if (!this::clipboardAdapter.isInitialized) return
        commitPendingDelete()
        folderForActions = null
        confirmFolderDelete = false
        inputMode = InputMode.NONE
        inputText.clear()
        searchQuery = null
        selectionActive = false
        moveTargetMode = false
        selectedIds.clear()
        clipboardAdapter.setSelection(false, emptySet())
        clipboardRecyclerView.adapter = null
        clipboardHistoryManager.setHistoryChangeListener(null)
        clipboardAdapter.clipboardHistoryManager = null
    }

    override fun onClick(view: View) {
        if (view.tag is ToolbarKey) {
            onClickToolbarKey(view) {
                keyboardActionListener.onCodeInput(it, Constants.NOT_A_COORDINATE, Constants.NOT_A_COORDINATE, false)
            }
        }
    }

    override fun onLongClick(view: View): Boolean {
        if (view.tag is ToolbarKey) {
            onLongClickToolbarKey(view) { code, isRepeat ->
                keyboardActionListener.onCodeInput(code, Constants.NOT_A_COORDINATE, Constants.NOT_A_COORDINATE, isRepeat)
            }
            return true
        }
        return false
    }

    override fun onKeyDown(clipId: Long) {
        keyboardActionListener.onPressKey(KeyCode.NOT_SPECIFIED, 0, 1, HapticEvent.KEY_PRESS)
    }

    override fun onKeyUp(clipId: Long) {
        val clipContent = clipboardHistoryManager.getHistoryEntryContent(clipId)
        if (clipContent?.filename != null) keyboardActionListener.onContent(clipContent.getContentInfo(context))
        else keyboardActionListener.onTextInput(clipContent?.text)
        keyboardActionListener.onReleaseKey(KeyCode.NOT_SPECIFIED, false)
        if (Settings.getValues().mAlphaAfterClipHistoryEntry)
            keyboardActionListener.onCodeInput(KeyCode.ALPHA, Constants.NOT_A_COORDINATE, Constants.NOT_A_COORDINATE, false)
    }

    // positions from the DAO refer to all clips, so the shown list is rebuilt from the DAO
    override fun onClipInserted(position: Int) = onMainThread {
        refreshList(scrollToId = clipboardHistoryManager.getHistoryEntry(position)?.id)
    }

    override fun onClipsRemoved(position: Int, count: Int) = onMainThread {
        refreshList()
    }

    override fun onClipMoved(oldPosition: Int, newPosition: Int) = onMainThread {
        val movedId = clipboardHistoryManager.getHistoryEntry(newPosition)?.id
        // a clip that is copied again while its removal can still be undone is kept
        if (movedId != null && movedId == pendingDelete?.id) {
            removeCallbacks(commitDeleteRunnable)
            pendingDelete = null
            updateInputState()
        }
        refreshList(scrollToId = if (newPosition < oldPosition) movedId else null)
        movedId?.let { clipboardAdapter.entryChanged(it) }
    }

    // the list must only be changed on the main thread, and never while the panel is closed
    private fun onMainThread(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) safely(action)
        else post { if (clipboardRecyclerView.adapter != null) safely(action) }
    }

    /** an error in a clipboard action must not close the keyboard */
    private fun safely(action: () -> Unit) {
        try {
            action()
        } catch (e: Exception) {
            Log.e("ClipboardHistoryView", "clipboard action failed", e)
            Toast.makeText(context, R.string.clipboard_action_failed, Toast.LENGTH_SHORT).show()
            runCatching {
                selectionActive = false
                moveTargetMode = false
                selectedIds.clear()
                clipboardAdapter.setSelection(false, selectedIds)
                updateInputState()
                refreshList(animate = false)
            }
        }
    }

    /** removes the swiped clip from the history, it can't be brought back after that */
    private fun commitPendingDelete() {
        removeCallbacks(commitDeleteRunnable)
        val entry = pendingDelete ?: return
        pendingDelete = null
        if (::clipboardHistoryManager.isInitialized) clipboardHistoryManager.removeEntry(entry.id)
        if (clipboardRecyclerView.adapter != null) updateInputState()
    }

    private fun undoDelete() {
        removeCallbacks(commitDeleteRunnable)
        val id = pendingDelete?.id ?: return
        pendingDelete = null
        updateInputState()
        refreshList(scrollToId = id)
    }

    override fun onSharedPreferenceChanged(prefs: SharedPreferences?, key: String?) {
        setToolbarButtonsActivatedStateOnPrefChange(KeyboardSwitcher.getInstance().clipboardStrip!!, key)

        // The setting can only be changed from a settings screen, but adding it to this listener seems necessary: https://github.com/HeliBorg/HeliBoard/pull/1903#issuecomment-3478424606
        if (::clipboardHistoryManager.isInitialized && key == Settings.PREF_CLIPBOARD_HISTORY_PINNED_FIRST) {
            // Ensure settings are reloaded first
            Settings.getInstance().onSharedPreferenceChanged(prefs, key)
            clipboardHistoryManager.sortHistoryEntries()
            refreshList(animate = false)
        }
    }

    /** rebuilds the shown clips from the history. While searching, all folders are searched. */
    private fun refreshList(scrollToId: Long? = null, animate: Boolean = true) {
        val query = searchQuery
        val folder = if (query != null) FolderFilter.All else folderFilter
        val hiddenId = pendingDelete?.id
        val visible = ClipboardListFilter.visible(clipboardHistoryManager.getAllEntries(), folder, query)
            .let { clips -> if (hiddenId == null) clips else clips.filter { it.id != hiddenId } }
        // the folder of a clip is shown where clips of several folders are listed
        clipboardAdapter.folderNames = if (folder == FolderFilter.All) clipboardHistoryManager.getFolders().associate { it.id to it.name } else emptyMap()
        clipboardAdapter.submitItems(visible, animate)
        placeholderView.text = if (query.isNullOrBlank()) EMPTY_PLACEHOLDER else context.getString(R.string.clipboard_no_results)
        if (!animate) clipboardRecyclerView.scrollToPosition(0)
        val position = scrollToId?.let { clipboardAdapter.positionOf(it) } ?: -1
        if (position >= 0) {
            // only scroll to a clip that is near, a long scroll would bind every row on the way and lose the place of the user
            val first = (clipboardRecyclerView.layoutManager as? StaggeredGridLayoutManager)
                ?.findFirstVisibleItemPositions(null)?.minOrNull() ?: 0
            if (abs(position - first) <= MAX_SCROLL_DISTANCE) clipboardRecyclerView.smoothScrollToPosition(position)
        }
        // clips that are gone can't stay selected, and the search line shows "All" only if there are results
        if (selectionActive && selectedIds.retainAll(clipboardAdapter.shownIds().toSet())) {
            selectedBeforeDrag = selectedIds.toSet()
            updateSelectionUi()
        } else if (!selectionActive && searchQuery != null && inputMode == InputMode.NONE) {
            updateInputState()
        }
    }

    private fun refreshFolderBar() {
        folderBar.showChips(clipboardHistoryManager.getFolders(), folderFilter)
    }

    /**
     * Shows the folder chips, or the text line while a name or search text is typed or a search is active,
     * and sets the list height to match.
     */
    private fun updateInputState() {
        val inputShown = inputMode != InputMode.NONE
        val text = if (inputShown) inputText.toString() else searchQuery.orEmpty()
        when {
            selectionActive -> {
                if (moveTargetMode) folderBar.showMoveTargets(clipboardHistoryManager.getFolders())
                else folderBar.showSelection(selectedIds.size, canEdit = selectedTextClip() != null)
            }
            folderForActions != null -> showFolderActions(folderForActions!!)
            inputShown -> {
                val hint = context.getString(if (inputMode == InputMode.SEARCH) R.string.clipboard_search_hint else R.string.clipboard_folder_name_hint)
                val isSearch = inputMode == InputMode.SEARCH
                folderBar.showInput(text, hint, editing = true, isSearch = isSearch, showConfirm = !isSearch, confirmEnabled = text.isNotBlank(),
                    showSelectAll = isSearch && clipboardAdapter.itemCount > 0)
            }
            searchQuery != null -> folderBar.showInput(text, context.getString(R.string.clipboard_search_hint), editing = false, isSearch = true,
                showConfirm = false, confirmEnabled = false, showSelectAll = clipboardAdapter.itemCount > 0)
            pendingDelete != null -> folderBar.showActions(context.getString(R.string.clipboard_clip_deleted), listOf(
                ClipboardFolderBar.BarAction(R.drawable.ic_undo, R.string.undo) { undoDelete() }
            ))
            else -> refreshFolderBar()
        }
        bottomRowKeyboard.isVisible = !inputShown
        miniKeyboard.isVisible = inputShown
        if (listHasInputHeight != inputShown) { // changing the height lays out the list again, only do it when necessary
            listHasInputHeight = inputShown
            clipboardLayoutParams.setListProperties(clipboardRecyclerView, inputShown)
        }
    }

    private fun showFolderActions(folder: ClipboardFolder) {
        if (confirmFolderDelete) {
            folderBar.showActions(context.getString(R.string.clipboard_folder_delete_question, folder.name), listOf(
                ClipboardFolderBar.BarAction(R.drawable.sym_keyboard_done_lxx, R.string.delete) {
                    safely {
                        clipboardHistoryManager.deleteFolder(folder.id)
                        if (folderFilter == FolderFilter.Folder(folder.id)) folderFilter = FolderFilter.All
                        folderForActions = null
                        confirmFolderDelete = false
                        updateInputState()
                        refreshList(animate = false)
                    }
                }
            ))
            return
        }
        folderBar.showActions(folder.name, listOf(
            ClipboardFolderBar.BarAction(R.drawable.ic_edit, R.string.clipboard_folder_rename) {
                folderForActions = null
                renamingFolderId = folder.id
                startInput(InputMode.RENAME_FOLDER, folder.name)
            },
            ClipboardFolderBar.BarAction(R.drawable.ic_clipboard_delete, R.string.clipboard_folder_delete) {
                confirmFolderDelete = true
                updateInputState()
            },
        ))
    }

    /** the selected clip, if exactly one text clip is selected */
    private fun selectedTextClip(): ClipboardHistoryEntry? {
        if (selectedIds.size != 1) return null
        val id = selectedIds.first()
        return clipboardHistoryManager.getAllEntries().firstOrNull { it.id == id }?.takeIf { it.filename == null && it.text != null }
    }

    /** opens the selected clip in a text field, the normal keyboard is used there */
    private fun editSelection() {
        val clip = selectedTextClip() ?: return
        endSelection()
        safely {
            context.startActivity(Intent(context, ClipboardEditActivity::class.java)
                .putExtra(ClipboardEditActivity.EXTRA_CLIP_ID, clip.id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK))
        }
    }

    /** types the text of the selected text clips, one clip per line, in the order shown */
    private fun pasteSelection() {
        val text = ClipboardListFilter.textToCopy(clipboardAdapter.entriesInOrder(selectedIds))
        if (text.isEmpty()) return
        endSelection()
        keyboardActionListener.onTextInput(text)
    }

    private fun startInput(mode: InputMode, initialText: String) {
        inputMode = mode
        miniKeyboard.reset()
        inputText.setLength(0)
        inputText.append(initialText)
        if (mode == InputMode.SEARCH) {
            searchQuery = initialText
            refreshList(animate = false)
        }
        updateInputState()
    }

    private fun stopInput() {
        inputMode = InputMode.NONE
        inputText.setLength(0)
        updateInputState()
    }

    /** ends the search, shows all clips of the selected folder again */
    private fun clearSearch() {
        inputMode = InputMode.NONE
        inputText.setLength(0)
        searchQuery = null
        updateInputState()
        refreshList(animate = false)
    }

    /** long-press on a clip: starts the selection, or adds the clip if the selection is active already */
    private fun startSelection(id: Long) {
        if (inputMode != InputMode.NONE) stopInput() // a search text stays active, the keyboard closes
        if (!selectionActive) selectedIds.clear()
        selectionActive = true
        moveTargetMode = false
        folderForActions = null
        selectedIds.add(id)
        selectedBeforeDrag = selectedIds.toSet()
        updateSelectionUi()
    }

    /** the finger was dragged after the long-press: all clips between the first and the current one are selected */
    private fun dragSelect(from: Int, to: Int) {
        if (!selectionActive) return
        selectedIds.clear()
        selectedIds.addAll(selectedBeforeDrag)
        selectedIds.addAll(clipboardAdapter.idsBetween(from, to))
        updateSelectionUi()
    }

    private fun toggleSelection(id: Long) {
        if (!selectionActive) return
        if (!selectedIds.add(id)) selectedIds.remove(id)
        updateSelectionUi()
    }

    /**
     * selects all shown clips (the folder, or the search results), also starts the selection.
     * If all of them are selected already, they are unselected.
     */
    private fun selectAllShown() {
        val shown = clipboardAdapter.shownIds()
        if (shown.isEmpty()) return
        if (inputMode != InputMode.NONE) stopInput() // a search text stays active, the keyboard closes
        if (!selectionActive) selectedIds.clear()
        if (selectionActive && selectedIds.containsAll(shown)) selectedIds.removeAll(shown.toSet())
        else selectedIds.addAll(shown)
        selectionActive = true
        moveTargetMode = false
        selectedBeforeDrag = selectedIds.toSet()
        updateSelectionUi()
    }

    private fun endSelection() {
        selectionActive = false
        moveTargetMode = false
        selectedIds.clear()
        updateSelectionUi()
    }

    private fun updateSelectionUi() {
        clipboardAdapter.setSelection(selectionActive, selectedIds)
        updateInputState()
    }

    /** pins all selected clips, or unpins them if all of them are pinned already */
    private fun pinSelection() {
        val selected = clipboardHistoryManager.getAllEntries().filter { it.id in selectedIds }
        if (selected.isEmpty()) return
        clipboardHistoryManager.setClipsPinned(selected.map { it.id }, pinned = selected.any { !it.isPinned })
        endSelection()
        refreshList(animate = false)
    }

    /** copies the text of the selected text clips, one clip per line, in the order shown */
    private fun copySelection() {
        val text = ClipboardListFilter.textToCopy(clipboardAdapter.entriesInOrder(selectedIds))
        if (text.isEmpty()) return
        val copied = clipboardHistoryManager.copyTextToClipboard(text)
        Toast.makeText(context, if (copied) R.string.toast_msg_clipboard_copy else R.string.clipboard_copy_too_long, Toast.LENGTH_SHORT).show()
        endSelection()
    }

    private fun moveSelectionTo(folderId: Long?) {
        clipboardHistoryManager.moveClipsToFolder(selectedIds.toList(), folderId)
        endSelection()
        refreshList(animate = false)
    }

    private fun handleInputKey(key: String) {
        when (key) {
            ClipboardMiniKeyboard.BACKSPACE_KEY -> if (inputText.isNotEmpty()) inputText.setLength(inputText.offsetByCodePoints(inputText.length, -1))
            ClipboardMiniKeyboard.CLEAR_KEY -> inputText.setLength(0)
            else -> {
                val maxLength = if (inputMode == InputMode.SEARCH) MAX_SEARCH_LENGTH else ClipboardDao.MAX_FOLDER_NAME_LENGTH
                if (inputText.length + key.length <= maxLength) inputText.append(key)
            }
        }
        if (inputMode == InputMode.SEARCH) {
            searchQuery = inputText.toString()
            refreshList(animate = false)
        }
        updateInputState()
    }

    private fun confirmInput() {
        val name = inputText.toString()
        val saved = when (inputMode) {
            InputMode.NEW_FOLDER -> clipboardHistoryManager.createFolder(name)?.also { folderFilter = FolderFilter.Folder(it.id) } != null
            InputMode.RENAME_FOLDER -> clipboardHistoryManager.renameFolder(renamingFolderId, name)
            InputMode.NONE, InputMode.SEARCH -> false
        }
        if (!saved) {
            Toast.makeText(context, R.string.clipboard_folder_name_invalid, Toast.LENGTH_SHORT).show()
            return
        }
        stopInput()
        refreshList(animate = false)
    }
}
