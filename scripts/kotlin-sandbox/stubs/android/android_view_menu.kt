// Sandbox-only stand-ins, see android_content.kt.
package android.view

/**
 * The menu of the per-entry popup. The stand-in records the items that were added; nothing is
 * rendered.
 */
open class Menu {
    /** The items of the menu, in order. */
    val items: MutableList<MenuItem> = ArrayList()

    open fun add(groupId: Int, itemId: Int, order: Int, title: CharSequence): MenuItem {
        val item = MenuItem(itemId, title)
        items.add(item)
        return item
    }

    open fun getItem(index: Int): MenuItem = items[index]

    open fun findItem(id: Int): MenuItem? = items.firstOrNull { it.getItemId() == id }
}

/** One entry of a menu. */
open class MenuItem(private val id: Int, private val title: CharSequence?) {
    /**
     * The platform's `isVisible()`/`setVisible(boolean)` pair is a pair of Java methods, so the
     * stand-in keeps them as functions and stores the state privately.
     */
    private var visibleFlag: Boolean = true

    /** The platform's `isChecked()`/`setChecked(boolean)` pair; see [visibleFlag]. */
    private var checkedFlag: Boolean = false

    /** The icon resource that was set. */
    var icon: Int = 0
        private set

    /** The platform's `getActionView()`/`setActionView(View)` pair; see [visibleFlag]. */
    private var actionViewRef: View? = null

    /** The expand listener that was set. */
    var onActionExpandListener: OnActionExpandListener? = null
        private set

    /** Whether the action view was expanded. */
    var expanded: Boolean = false
        private set

    fun setIcon(icon: Int) {
        this.icon = icon
    }

    fun setChecked(checked: Boolean) {
        checkedFlag = checked
    }

    fun isChecked(): Boolean = checkedFlag

    fun setActionView(view: View?) {
        actionViewRef = view
    }

    fun getActionView(): View? = actionViewRef

    fun expandActionView() {
        expanded = true
    }

    fun collapseActionView() {
        expanded = false
    }

    fun setOnActionExpandListener(listener: OnActionExpandListener?) {
        onActionExpandListener = listener
    }

    /** The expand callbacks of a menu item. */
    interface OnActionExpandListener {
        fun onMenuItemActionExpand(item: MenuItem): Boolean
        fun onMenuItemActionCollapse(item: MenuItem): Boolean
    }

    /** The item id. */
    fun getItemId(): Int = id

    /** The item title. */
    fun getTitle(): CharSequence? = title

    fun setVisible(visible: Boolean) {
        visibleFlag = visible
    }

    fun isVisible(): Boolean = visibleFlag

    /** The click listener of a menu item. */
    fun interface OnMenuItemClickListener {
        fun onMenuItemClick(item: MenuItem): Boolean
    }
}

/** The inflater of the popup menu. */
open class MenuInflater {
    /** The resource that was inflated. */
    var inflatedResource = 0
        private set

    open fun inflate(resId: Int, menu: Menu) {
        inflatedResource = resId
    }
}
