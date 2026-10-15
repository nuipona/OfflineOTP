// Sandbox-only stand-ins, see android_content.kt.
package android.widget

import android.content.Context
import android.text.method.TransformationMethod
import android.view.View

class Toast private constructor() {
    fun show() {}

    companion object {
        const val LENGTH_LONG = 1

        @JvmStatic
        fun makeText(context: Context, resId: Int, duration: Int): Toast = Toast()
    }
}

/**
 * Stand-in for the masked-input surface used by the password helper. The selection/transformation
 * members are `open` so the checks can drive a fake field; no text layout is modelled.
 */
open class EditText : View() {
    open var transformationMethod: TransformationMethod? = null
    open val selectionEnd: Int = -1
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

class CheckedTextView : View() {
    var text: CharSequence? = null
    var isChecked: Boolean = false
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

open class TextView : View() { fun setText(resource: Int) {} }
open class CompoundButton : TextView() {
    fun interface OnCheckedChangeListener { fun onCheckedChanged(buttonView: CompoundButton?, isChecked: Boolean) }
    fun setOnCheckedChangeListener(listener: OnCheckedChangeListener?) {}
}
class CheckBox : CompoundButton()
class Button : TextView() { fun setOnClickListener(listener: View.OnClickListener?) {} }
