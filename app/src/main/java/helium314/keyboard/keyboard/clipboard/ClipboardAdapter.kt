// SPDX-License-Identifier: GPL-3.0-only

package helium314.keyboard.keyboard.clipboard

import android.annotation.SuppressLint
import android.graphics.Typeface
import android.text.format.DateUtils
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import helium314.keyboard.latin.ClipboardHistoryEntry
import helium314.keyboard.latin.ClipboardHistoryManager
import helium314.keyboard.latin.R
import helium314.keyboard.latin.common.ColorType
import helium314.keyboard.latin.settings.Settings

class ClipboardAdapter(
       val clipboardLayoutParams: ClipboardLayoutParams,
       val keyEventListener: OnKeyEventListener
) : RecyclerView.Adapter<ClipboardAdapter.ViewHolder>() {

    var clipboardHistoryManager: ClipboardHistoryManager? = null

    // the entries currently shown (after folder and search filter), positions refer to this list
    private var items: List<ClipboardHistoryEntry> = emptyList()

    // multi-select: while selecting, a tap toggles the clip instead of pasting it
    private var selectionMode = false
    private var selectedIds: Set<Long> = emptySet()
    var onSelectionToggle: ((Long) -> Unit)? = null
    /** called on long-press with the clip id and its position, starts the selection or adds to it */
    var onSelectionStart: ((Long, Int) -> Unit)? = null
    val isSelecting get() = selectionMode

    /** folder id to name, the name is shown next to the date of clips in these folders */
    var folderNames: Map<Long, String> = emptyMap()

    var pinnedIconResId = 0
    var itemBackgroundId = 0
    var itemTypeFace: Typeface? = null
    var itemTextColor = 0
    var itemTextSize = 0f

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.clipboard_entry_key, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.setContent(entryAt(position))
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.isEmpty()) onBindViewHolder(holder, position)
        else holder.setSelected(entryAt(position)) // only the selection changed
    }

    override fun getItemCount() = items.size

    fun entryAt(position: Int): ClipboardHistoryEntry? = items.getOrNull(position)

    fun positionOf(id: Long): Int = items.indexOfFirst { it.id == id }

    /** the shown entries that are in [ids], in display order */
    fun entriesInOrder(ids: Set<Long>): List<ClipboardHistoryEntry> = items.filter { it.id in ids }

    fun shownIds(): List<Long> = items.map { it.id }

    /** ids of the shown entries from one position to another, both included, in any order */
    fun idsBetween(from: Int, to: Int): List<Long> {
        if (items.isEmpty()) return emptyList()
        val first = minOf(from, to).coerceIn(0, items.lastIndex)
        val last = maxOf(from, to).coerceIn(0, items.lastIndex)
        return items.subList(first, last + 1).map { it.id }
    }

    private var recyclerView: RecyclerView? = null

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        this.recyclerView = recyclerView
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        this.recyclerView = null
    }

    // rows that come back from the cache of the list are not bound again, so their selection state is set here
    override fun onViewAttachedToWindow(holder: ViewHolder) {
        holder.setSelected(entryAt(holder.bindingAdapterPosition))
    }

    /**
     * Only the rows on screen are updated, without telling the list that items changed.
     * This is fast with thousands of clips and while the selection changes on every move of the finger.
     */
    fun setSelection(selecting: Boolean, ids: Set<Long>) {
        selectionMode = selecting
        selectedIds = ids
        val list = recyclerView ?: return
        for (i in 0 until list.childCount) {
            val holder = list.getChildViewHolder(list.getChildAt(i)) as? ViewHolder ?: continue
            holder.setSelected(entryAt(holder.bindingAdapterPosition))
        }
    }

    /** called after a clip was swiped away, it is removed from the shown list only */
    var onEntrySwipedAway: ((ClipboardHistoryEntry) -> Unit)? = null

    /**
     * Replaces the shown entries.
     * With [animate] the change is computed with DiffUtil, which is fast when only a few clips changed.
     * Without it (filter or folder changed) the list is simply reloaded.
     */
    fun submitItems(newItems: List<ClipboardHistoryEntry>, animate: Boolean) {
        val oldItems = items
        // comparing lists that differ a lot is slow and the animation is of no use then
        if (!animate || kotlin.math.abs(newItems.size - oldItems.size) > 30) {
            items = newItems
            notifyDataSetChanged()
            return
        }
        val result = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = oldItems.size
            override fun getNewListSize() = newItems.size
            override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int) =
                oldItems[oldItemPosition].id == newItems[newItemPosition].id
            override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                val old = oldItems[oldItemPosition]
                val new = newItems[newItemPosition]
                return old.isPinned == new.isPinned && old.timeStamp == new.timeStamp && old.folderId == new.folderId && old.text == new.text
            }
        })
        items = newItems
        result.dispatchUpdatesTo(this)
    }

    /** removes a clip from the shown list, pinned clips are kept. [onEntrySwipedAway] removes it from the history. */
    fun removeEntryAt(position: Int) {
        val entry = items.getOrNull(position) ?: return
        if (entry.isPinned) return
        items = items.toMutableList().apply { this.removeAt(position) }
        notifyItemRemoved(position)
        onEntrySwipedAway?.invoke(entry)
    }

    /** pins or unpins a clip after it was swiped, the clip stays in the list */
    fun togglePinnedAt(position: Int) {
        val entry = items.getOrNull(position) ?: return
        // the history listener updates the shown list and calls entryChanged, which brings the swiped view back
        clipboardHistoryManager?.toggleClipPinned(entry.id)
    }

    /** shows the clip again after its pin state or date changed */
    fun entryChanged(id: Long) {
        val position = positionOf(id)
        if (position >= 0) notifyItemChanged(position)
    }

    inner class ViewHolder(
            view: View
    ) : RecyclerView.ViewHolder(view), View.OnClickListener, View.OnTouchListener, View.OnLongClickListener {

        private val pinnedIconView: ImageView
        private val contentTextView: TextView
        private val contentImageView: ImageView
        private val dateView: TextView
        private val selectedMarkView: TextView

        init {
            view.apply {
                setOnClickListener(this@ViewHolder)
                setOnTouchListener(this@ViewHolder)
                setOnLongClickListener(this@ViewHolder)
                setBackgroundResource(itemBackgroundId)
                isHapticFeedbackEnabled = false
            }
            Settings.getValues().mColors.setBackground(view, ColorType.KEY_BACKGROUND)
            pinnedIconView = view.findViewById<ImageView>(R.id.clipboard_entry_pinned_icon).apply {
                visibility = View.GONE
                setImageResource(pinnedIconResId)
            }
            contentTextView = view.findViewById<TextView>(R.id.clipboard_entry_text_content).apply {
                typeface = itemTypeFace
                setTextColor(itemTextColor)
                setTextSize(TypedValue.COMPLEX_UNIT_PX, itemTextSize)
            }
            contentImageView = view.findViewById(R.id.clipboard_entry_image_content)
            dateView = view.findViewById<TextView>(R.id.clipboard_entry_date).apply {
                typeface = itemTypeFace
                setTextColor(itemTextColor)
                setTextSize(TypedValue.COMPLEX_UNIT_PX, itemTextSize * 0.7f)
            }
            selectedMarkView = view.findViewById<TextView>(R.id.clipboard_entry_selected_mark).apply {
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(itemTextColor)
                setTextSize(TypedValue.COMPLEX_UNIT_PX, itemTextSize)
            }
            clipboardLayoutParams.setItemProperties(view)
            val colors = Settings.getValues().mColors
            colors.setColor(pinnedIconView, ColorType.CLIPBOARD_PIN)
        }

        fun setContent(historyEntry: ClipboardHistoryEntry?) {
            if (historyEntry == null) return
            itemView.tag = historyEntry.id
            if (historyEntry.filename != null) {
                historyEntry.setImageAndDescription(contentImageView, contentTextView)
            } else {
                contentTextView.text = historyEntry.text?.take(1000) // truncate displayed text for performance reasons
            }
            val date = DateUtils.formatDateTime(itemView.context, historyEntry.timeStamp,
                DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_MONTH)
            val folderName = historyEntry.folderId?.let { folderNames[it] }
            dateView.text = if (folderName == null) date else "$date · $folderName"
            pinnedIconView.visibility = if (historyEntry.isPinned) View.VISIBLE else View.GONE
            contentImageView.visibility = if (historyEntry.filename != null) View.VISIBLE else View.GONE
            contentTextView.visibility = if (contentTextView.text.isNullOrEmpty()) View.GONE else View.VISIBLE
            setSelected(historyEntry)
        }

        fun setSelected(historyEntry: ClipboardHistoryEntry?) {
            // selected clips have a check mark, the others are dimmed while selecting
            val selected = selectionMode && historyEntry != null && historyEntry.id in selectedIds
            selectedMarkView.visibility = if (selected) View.VISIBLE else View.GONE
            itemView.alpha = if (selectionMode && !selected) 0.6f else 1f
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouch(view: View, event: MotionEvent): Boolean {
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                keyEventListener.onKeyDown(view.tag as Long)
            }
            return false
        }

        override fun onClick(view: View) {
            if (selectionMode) onSelectionToggle?.invoke(view.tag as Long)
            else keyEventListener.onKeyUp(view.tag as Long)
        }

        override fun onLongClick(view: View): Boolean {
            onSelectionStart?.invoke(view.tag as Long, bindingAdapterPosition)
            return true
        }
    }

    companion object {
        private const val SELECTION_CHANGED = "selection"
    }
}
