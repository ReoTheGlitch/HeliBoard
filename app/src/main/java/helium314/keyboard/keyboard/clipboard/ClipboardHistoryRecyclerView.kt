// SPDX-License-Identifier: GPL-3.0-only

package helium314.keyboard.keyboard.clipboard

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.abs
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
    private var dragMoved = false // the finger moved after the long-press, only then the list scrolls at its edges
    private var dragStartY = 0f
    private var touchDown = false
    private var touchX = 0f
    private var touchY = 0f
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val dragEdgeSize = (48 * resources.displayMetrics.density).toInt()
    private val dragScrollStep = (8 * resources.displayMetrics.density).toInt()

    // scrolls while the finger rests near the top or bottom edge during drag-select
    private val dragScroller = object : Runnable {
        override fun run() {
            if (dragAnchor == NO_POSITION || height <= 0) return
            val edge = minOf(dragEdgeSize, height / 4)
            val dy = when {
                !dragMoved -> 0
                touchY < edge -> -dragScrollStep
                touchY > height - edge -> dragScrollStep
                else -> 0
            }
            if (dy != 0 && canScrollVertically(dy)) {
                scrollBy(0, dy)
                updateDragSelection()
            }
            postOnAnimation(this)
        }
    }

    /** starts drag-select at this position, call it from the long-press while the finger is still down */
    fun startDragSelect(position: Int) {
        if (position == NO_POSITION || !touchDown) return
        dragAnchor = position
        dragLastPosition = position
        dragMoved = false
        dragStartY = touchY
        parent?.requestDisallowInterceptTouchEvent(true)
        removeCallbacks(dragScroller)
        postOnAnimation(dragScroller)
    }

    private fun stopDragSelect() {
        dragAnchor = NO_POSITION
        removeCallbacks(dragScroller)
    }

    private fun updateDragSelection() {
        if (height <= 0) return
        val child = findChildViewUnder(touchX, touchY.coerceIn(0f, height - 1f)) ?: return
        val position = getChildAdapterPosition(child)
        if (position == NO_POSITION || position == dragLastPosition) return
        dragLastPosition = position
        onDragSelect?.invoke(dragAnchor, position)
    }

    override fun dispatchTouchEvent(e: MotionEvent): Boolean {
        touchX = e.x
        touchY = e.y
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                stopDragSelect()
                touchDown = true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> touchDown = false
        }
        if (dragAnchor != NO_POSITION && abs(e.y - dragStartY) > touchSlop) dragMoved = true
        val result = super.dispatchTouchEvent(e)
        if (!touchDown) stopDragSelect()
        return result
    }

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        if (dragAnchor != NO_POSITION && e.actionMasked == MotionEvent.ACTION_MOVE) {
            updateDragSelection()
            return true // the list takes the touch, so the clip under the finger is not clicked
        }
        return super.onInterceptTouchEvent(e)
    }

    @Suppress("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (dragAnchor == NO_POSITION) return super.onTouchEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_MOVE -> updateDragSelection()
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
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
        stopDragSelect()
        this.adapter?.unregisterAdapterDataObserver(adapterDataObserver)
        super.setAdapter(adapter)
        checkAdapterContentChange()
        adapter?.registerAdapterDataObserver(adapterDataObserver)
    }

}
