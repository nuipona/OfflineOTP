// Sandbox-only stand-ins, see android_content.kt.
package android.app

import android.content.Context
import android.content.DialogInterface

/**
 * The dialog only records what the builder wired up so the checks can drive the positive button;
 * no window/loopers are involved.
 */
class AlertDialog private constructor() : DialogInterface {
    var shown = false
        private set
    var positiveButtonListener: DialogInterface.OnClickListener? = null
        private set

    fun show() {
        shown = true
    }

    class Builder(private val context: Context) {
        private var titleId = 0
        private var messageId = 0
        private var positiveListener: DialogInterface.OnClickListener? = null

        fun setTitle(titleId: Int): Builder {
            this.titleId = titleId
            return this
        }

        fun setMessage(messageId: Int): Builder {
            this.messageId = messageId
            return this
        }

        fun setPositiveButton(textId: Int, listener: DialogInterface.OnClickListener): Builder {
            this.positiveListener = listener
            return this
        }

        fun create(): AlertDialog {
            val dialog = AlertDialog()
            dialog.positiveButtonListener = positiveListener
            lastCreated = dialog
            return dialog
        }

        companion object {
            /** The last dialog a check created; production dialogs are tracked by the OS instead. */
            var lastCreated: AlertDialog? = null
        }
    }
}
