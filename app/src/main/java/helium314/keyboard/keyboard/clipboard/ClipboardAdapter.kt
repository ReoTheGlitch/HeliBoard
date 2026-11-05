// SPDX-License-Identifier: GPL-3.0-only

package helium314.keyboard.keyboard.clipboard

import android.annotation.SuppressLint
import android.graphics.Typeface
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
    var onSelectionStart: ((Long) -> Unit)? = null

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

    override fun getItemCount() = items.size

    fun entryAt(position: Int): ClipboardHistoryEntry? = items.getOrNull(position)

    fun positionOf(id: Long): Int = items.indexOfFirst { it.id == id }

    /** the shown entries that are in [ids], in display order */
    fun entriesInOrder(ids: Set<Long>): List<ClipboardHistoryEntry> = items.filter { it.id in ids }

    fun setSelection(selecting: Boolean, ids: Set<Long>) {
        selectionMode = selecting
        selectedIds = ids
        notifyDataSetChanged()
    }

    /**
     * Replaces the shown entries.
     * With [animate] the change is computed with DiffUtil, which is fast when only a few clips changed.
     * Without it (filter or folder changed) the list is simply reloaded.
     */
    fun submitItems(newItems: List<ClipboardHistoryEntry>, animate: Boolean) {
        val oldItems = items
        if (!animate) {
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

    /** removes a clip from history and from the shown list, pinned clips are kept */
    fun removeEntryAt(position: Int) {
        val entry = items.getOrNull(position) ?: return
        if (entry.isPinned) return
        clipboardHistoryManager?.removeEntry(entry.id)
        items = items.toMutableList().apply { this.removeAt(position) }
        notifyItemRemoved(position)
    }

    inner class ViewHolder(
            view: View
    ) : RecyclerView.ViewHolder(view), View.OnClickListener, View.OnTouchListener, View.OnLongClickListener {

        private val pinnedIconView: ImageView
        private val contentTextView: TextView
        private val contentImageView: ImageView

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
            pinnedIconView.visibility = if (historyEntry.isPinned) View.VISIBLE else View.GONE
            contentImageView.visibility = if (historyEntry.filename != null) View.VISIBLE else View.GONE
            contentTextView.visibility = if (contentTextView.text.isNullOrEmpty()) View.GONE else View.VISIBLE
            itemView.alpha = if (selectionMode && historyEntry.id in selectedIds) 0.5f else 1f
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
            onSelectionStart?.invoke(view.tag as Long)
            return true
        }
    }
}
