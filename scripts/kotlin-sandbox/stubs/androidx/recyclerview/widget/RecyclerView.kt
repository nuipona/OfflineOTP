// Sandbox-only stand-ins, see android_content.kt.
package androidx.recyclerview.widget

import android.graphics.Canvas
import android.view.View

class RecyclerView {
    var layoutManager: LayoutManager? = null

    open class LayoutManager

    /**
     * The platform class is abstract with final type/position getters; the stand-in leaves the
     * values open so the checks can build fakes, and no recycling/adapter machinery is modelled.
     */
    abstract class ViewHolder(val itemView: View) {
        open val itemViewType: Int = 0
        open val adapterPosition: Int = 0
    }
}

class GridLayoutManager : RecyclerView.LayoutManager()
class LinearLayoutManager : RecyclerView.LayoutManager()

/**
 * The constants and the nested callback surface used by the converted drag & drop helper.
 * `makeMovementFlags` replicates the documented androidx composition
 * (`(swipe|drag) | swipe << 8 | drag << 16`), the same caveat as the TypedValue replication.
 */
class ItemTouchHelper private constructor() {
    companion object {
        const val UP = 1
        const val DOWN = 2
        const val LEFT = 4
        const val RIGHT = 8
        const val ACTION_STATE_IDLE = 0
        const val ACTION_STATE_SWIPE = 1
        const val ACTION_STATE_DRAG = 2
    }

    abstract class Callback {
        open fun isLongPressDragEnabled(): Boolean = true
        open fun isItemViewSwipeEnabled(): Boolean = true
        abstract fun onMove(recyclerView: RecyclerView, source: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean
        abstract fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int)
        open fun getMovementFlags(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder): Int = 0
        open fun onChildDraw(c: Canvas, recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder,
                             dX: Float, dY: Float, actionState: Int, isCurrentlyActive: Boolean) {}
        open fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {}
        open fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {}

        fun makeMovementFlags(dragFlags: Int, swipeFlags: Int): Int {
            return makeFlag(ACTION_STATE_IDLE, swipeFlags or dragFlags) or
                    makeFlag(ACTION_STATE_SWIPE, swipeFlags) or
                    makeFlag(ACTION_STATE_DRAG, dragFlags)
        }

        private fun makeFlag(actionState: Int, directions: Int): Int = directions shl (actionState * 8)
    }
}
