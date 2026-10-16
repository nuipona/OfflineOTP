// Sandbox-only stand-ins, see android_content.kt.
package android.widget

import android.content.Context
import android.text.Editable
import android.text.SimpleEditable
import android.text.TextWatcher
import android.text.method.TransformationMethod
import android.view.KeyEvent
import android.view.View

/** Records the resource id of every toast shown, so checks can assert the user-facing message. */
class Toast private constructor(private val resId: Int) {
    fun show() {
        shown.add(resId)
    }

    companion object {
        const val LENGTH_LONG = 1

        @JvmField
        val shown: MutableList<Int> = ArrayList()

        @JvmStatic
        fun makeText(context: Context, resId: Int, duration: Int): Toast = Toast(resId)
    }
}

open class TextView : View() {
    open var text: CharSequence? = null
    open var hint: CharSequence? = null

    private val textWatchers = ArrayList<TextWatcher>()
    private var editorActionListener: OnEditorActionListener? = null

    fun setText(resource: Int) {}
    fun setHint(resource: Int) {
        hint = "res:$resource"
    }

    fun addTextChangedListener(watcher: TextWatcher) {
        textWatchers.add(watcher)
    }

    /** Records the listener; the editor-action checks call the converted helper directly. */
    fun setOnEditorActionListener(listener: OnEditorActionListener?) {
        editorActionListener = listener
    }

    /** The platform interface is Java; a `fun interface` keeps lambda/SAM conversion working. */
    fun interface OnEditorActionListener {
        fun onEditorAction(v: TextView?, actionId: Int, event: KeyEvent?): Boolean
    }
}

/**
 * Stand-in for the masked-input surface used by the password helpers. The selection/transformation
 * members are `open` so the checks can drive a fake field; no text layout is modelled. The text
 * buffer backs both `text` and `editableText` like a real editable field.
 */
open class EditText : TextView {
    constructor() : super()
    constructor(context: Context) : super()

    private val buffer = SimpleEditable()

    open var transformationMethod: TransformationMethod? = null
    open val selectionEnd: Int = -1
    open var inputType: Int = 0

    override var text: CharSequence?
        get() = buffer
        set(value) {
            buffer.set(value?.toString() ?: "")
        }

    open val editableText: Editable get() = buffer

    open fun setSelection(index: Int) {}
}

/** Minimal recording adapter state; observers and platform list layout are not implemented. */
abstract class BaseAdapter {
    var changeNotifications: Int = 0
        private set
    open fun notifyDataSetChanged() { changeNotifications++ }
    abstract fun getCount(): Int
    abstract fun getItem(position: Int): Any?
    abstract fun getItemId(position: Int): Long
    abstract fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View
}

open class ArrayAdapter<T>(val context: Context, resource: Int, objects: MutableList<T>) : BaseAdapter() {
    private val items = objects
    fun clear() { items.clear(); notifyDataSetChanged() }
    fun addAll(values: Collection<T>) { items.addAll(values); notifyDataSetChanged() }
    override fun getCount(): Int = items.size
    override fun getItem(position: Int): T = items[position]
    override fun getItemId(position: Int): Long = position.toLong()
    override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View = View()
}

class CheckedTextView : TextView() {
    var isChecked: Boolean = false
}

/** Base of the list widgets; the converted tag picker only needs the item-click listener slot. */
open class AdapterView<T> : android.view.ViewGroup() {
    fun interface OnItemClickListener {
        fun onItemClick(parent: AdapterView<*>?, view: View, position: Int, id: Long)
    }
}

class ListView(context: Context) : AdapterView<Any?>() {
    var divider: android.graphics.drawable.Drawable? = null
    var adapter: BaseAdapter? = null
    var onItemClickListener: OnItemClickListener? = null
}

class GridView {
    class LayoutParams(width: Int, height: Int) : android.view.ViewGroup.LayoutParams(width, height)
}

class ImageView(context: Context) : View() {
    fun setImageBitmap(bitmap: android.graphics.Bitmap?) {}
}

/** Recording-only preference/dialog widgets; no focus, range enforcement or actual UI. */
class FrameLayout(context: Context) : android.view.ViewGroup() {
    private val children = mutableListOf<View>()
    fun addView(view: View) { children.add(view) }
    fun getChildAt(index: Int): View = children[index]
    val childCount: Int get() = children.size
    class LayoutParams(width: Int, height: Int) : android.view.ViewGroup.LayoutParams(width, height) {
        var gravity = 0
    }
}

class NumberPicker(context: Context) : View() {
    var minValue = 0
    var maxValue = 0
    var wrapSelectorWheel = true
    var value = 0
    var focusCleared = false
        private set
    fun clearFocus() { focusCleared = true }
}

open class CompoundButton : TextView() {
    fun interface OnCheckedChangeListener { fun onCheckedChanged(buttonView: CompoundButton?, isChecked: Boolean) }
    fun setOnCheckedChangeListener(listener: OnCheckedChangeListener?) {}
}
class CheckBox : CompoundButton()
class Button : TextView()
