// SPDX-License-Identifier: GPL-3.0-only

package helium314.keyboard.keyboard.clipboard

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import helium314.keyboard.latin.ClipboardHistoryManager
import androidx.core.view.isVisible
import androidx.core.view.isInvisible

class ClipboardHistoryRecyclerView @JvmOverloads constructor(
        context: Context,
        attrs: AttributeSet? = null,
        defStyleAttr: Int = 0
) : RecyclerView(context, attrs, defStyleAttr) {

    var placeholderView: View? = null
    val historyManager: ClipboardHistoryManager? get() = (adapter as? ClipboardAdapter?)?.clipboardHistoryManager
    @Suppress("unused")
    private val touchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT) {
        override fun onMove(recyclerView: RecyclerView, viewHolder: ViewHolder, target: ViewHolder) = false
        override fun getSwipeDirs(recyclerView: RecyclerView, viewHolder: ViewHolder): Int {
            if ((adapter as? ClipboardAdapter)?.isSelecting == true)
                return 0 // no swipe while selecting
            if ((adapter as? ClipboardAdapter)?.entryAt(viewHolder.absoluteAdapterPosition)?.isPinned == true)
                return ItemTouchHelper.RIGHT // pinned items can't be deleted, only unpinned
            return super.getSwipeDirs(recyclerView, viewHolder)
        }
        // swipe left deletes the clip, swipe right pins or unpins it
        override fun onSwiped(viewHolder: ViewHolder, dir: Int) {
            if (dir == ItemTouchHelper.RIGHT)
                (adapter as? ClipboardAdapter)?.togglePinnedAt(viewHolder.absoluteAdapterPosition)
            else
                (adapter as? ClipboardAdapter)?.removeEntryAt(viewHolder.absoluteAdapterPosition)
        }
    }).attachToRecyclerView(this)

    /** called while the finger is dragged over the clips after a long-press, with the positions of the first and the current clip */
    var onDragSelect: ((Int, Int) -> Unit)? = null
    private var dragAnchor = NO_POSITION // not NO_POSITION while drag-selecting
    private var dragLastPosition = NO_POSITION
    private var dragY = 0f
    private val dragEdgeSize = (48 * resources.displayMetrics.density).toInt()
    private val dragScrollStep = (12 * resources.displayMetrics.density).toInt()

    // scrolls while the finger rests near the top or bottom edge during drag-select
    private val dragScroller = object : Runnable {
        override fun run() {
            if (dragAnchor == NO_POSITION) return
            val dy = when {
                dragY < dragEdgeSize -> -dragScrollStep
                dragY > height - dragEdgeSize -> dragScrollStep
                else -> 0
            }
            if (dy != 0 && canScrollVertically(dy)) {
                scrollBy(0, dy)
                updateDragSelection(lastDragX, dragY)
            }
            postOnAnimation(this)
        }
    }
    private var lastDragX = 0f

    /** starts drag-select at this position, call it from the long-press while the finger is still down */
    fun startDragSelect(position: Int) {
        if (position == NO_POSITION) return
        dragAnchor = position
        dragLastPosition = position
        parent?.requestDisallowInterceptTouchEvent(true)
        removeCallbacks(dragScroller)
        postOnAnimation(dragScroller)
    }

    private fun stopDragSelect() {
        dragAnchor = NO_POSITION
        removeCallbacks(dragScroller)
    }

    private fun updateDragSelection(x: Float, y: Float) {
        val child = findChildViewUnder(x, y.coerceIn(0f, height - 1f)) ?: return
        val position = getChildAdapterPosition(child)
        if (position == NO_POSITION || position == dragLastPosition) return
        dragLastPosition = position
        onDragSelect?.invoke(dragAnchor, position)
    }

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_DOWN) stopDragSelect()
        if (dragAnchor != NO_POSITION) {
            if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) {
                stopDragSelect()
                return super.onInterceptTouchEvent(e)
            }
            return true // the list takes the touch, so the clip under the finger is not clicked
        }
        return super.onInterceptTouchEvent(e)
    }

    @Suppress("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (dragAnchor == NO_POSITION) return super.onTouchEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                lastDragX = e.x
                dragY = e.y
                updateDragSelection(e.x, e.y)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                stopDragSelect()
                // let the list finish the gesture without scrolling or flinging
                val cancel = MotionEvent.obtain(e)
                cancel.action = MotionEvent.ACTION_CANCEL
                super.onTouchEvent(cancel)
                cancel.recycle()
            }
        }
        return true
    }

    override fun onDetachedFromWindow() {
        stopDragSelect()
        super.onDetachedFromWindow()
    }

    private val adapterDataObserver: AdapterDataObserver = object : AdapterDataObserver() {
        override fun onChanged() {
            checkAdapterContentChange()
        }

        override fun onItemRangeChanged(positionStart: Int, itemCount: Int) {
            checkAdapterContentChange()
        }

        override fun onItemRangeInserted(positionStart: Int, itemCount: Int) {
            checkAdapterContentChange()
        }

        override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) {
            checkAdapterContentChange()
        }
    }

    private fun checkAdapterContentChange() {
        if (placeholderView == null) return
        val adapterIsEmpty = adapter == null || adapter?.itemCount == 0
        if (isVisible && adapterIsEmpty) {
            placeholderView!!.visibility = VISIBLE
            visibility = INVISIBLE
        } else if (isInvisible && !adapterIsEmpty) {
            placeholderView!!.visibility = INVISIBLE
            visibility = VISIBLE
        }
    }

    override fun setAdapter(adapter: Adapter<*>?) {
        this.adapter?.unregisterAdapterDataObserver(adapterDataObserver)
        super.setAdapter(adapter)
        checkAdapterContentChange()
        adapter?.registerAdapterDataObserver(adapterDataObserver)
    }

}
