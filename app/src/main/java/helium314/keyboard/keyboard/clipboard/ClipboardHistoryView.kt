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
import helium314.keyboard.latin.ClipboardHistoryManager
import helium314.keyboard.latin.R
import helium314.keyboard.latin.common.ColorType
import helium314.keyboard.latin.common.Constants
import helium314.keyboard.latin.database.ClipboardDao
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

    private val clipboardLayoutParams = ClipboardLayoutParams(context)
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

    private enum class InputMode { NONE, NEW_FOLDER, RENAME_FOLDER, SEARCH }

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
            onRenameFolder = { folder ->
                renamingFolderId = folder.id
                startInput(InputMode.RENAME_FOLDER, folder.name)
            }
            onConfirmInput = { confirmInput() }
            onCancelInput = {
                if (inputMode == InputMode.SEARCH || (inputMode == InputMode.NONE && searchQuery != null)) clearSearch()
                else stopInput()
            }
            onSearch = { startInput(InputMode.SEARCH, searchQuery.orEmpty()) }
            onInputClick = {
                if (inputMode == InputMode.NONE && searchQuery != null) startInput(InputMode.SEARCH, searchQuery.orEmpty())
            }
        }
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

        placeholderView.apply {
            KeyboardTypeface.applyToTextView(this)
            setTextColor(params.mTextColor)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, params.mLabelSize.toFloat() * 2)
        }
        clipboardRecyclerView.apply {
            adapter = clipboardAdapter
            val keyboardWidth = ResourceUtils.getKeyboardWidth(context, settings.current)
            layoutParams.width = keyboardWidth
            // new ClipboardLayoutParams means ClipboardAdapter has wrong gaps, but that's ok (only relevant when resizing floating keyboard)
            ClipboardLayoutParams(context).setListProperties(this)

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
        inputMode = InputMode.NONE
        inputText.clear()
        searchQuery = null
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
    override fun onClipInserted(position: Int) {
        refreshList(scrollToId = clipboardHistoryManager.getHistoryEntry(position)?.id)
    }

    override fun onClipsRemoved(position: Int, count: Int) {
        refreshList()
    }

    override fun onClipMoved(oldPosition: Int, newPosition: Int) {
        val movedId = clipboardHistoryManager.getHistoryEntry(newPosition)?.id
        refreshList(scrollToId = if (newPosition < oldPosition) movedId else null)
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
        val visible = ClipboardListFilter.visible(clipboardHistoryManager.getAllEntries(), folder, query)
        clipboardAdapter.submitItems(visible, animate)
        if (!animate) clipboardRecyclerView.scrollToPosition(0)
        val position = scrollToId?.let { clipboardAdapter.positionOf(it) } ?: -1
        if (position >= 0) clipboardRecyclerView.smoothScrollToPosition(position)
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
            inputShown -> {
                val hint = context.getString(if (inputMode == InputMode.SEARCH) R.string.clipboard_search_hint else R.string.clipboard_folder_name_hint)
                folderBar.showInput(text, hint, showConfirm = inputMode != InputMode.SEARCH, confirmEnabled = text.isNotBlank())
            }
            searchQuery != null -> folderBar.showInput(text, context.getString(R.string.clipboard_search_hint), showConfirm = false, confirmEnabled = false)
            else -> refreshFolderBar()
        }
        bottomRowKeyboard.isVisible = !inputShown
        miniKeyboard.isVisible = inputShown
        clipboardLayoutParams.setListProperties(clipboardRecyclerView, inputShown)
    }

    private fun startInput(mode: InputMode, initialText: String) {
        inputMode = mode
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

    private fun handleInputKey(key: String) {
        when (key) {
            ClipboardMiniKeyboard.BACKSPACE_KEY -> if (inputText.isNotEmpty()) inputText.deleteCharAt(inputText.length - 1)
            else -> if (inputText.length < ClipboardDao.MAX_FOLDER_NAME_LENGTH) inputText.append(key)
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
