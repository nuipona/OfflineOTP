// Sandbox-only stand-ins, see android_content.kt.
package android.app

import android.content.Context
import android.content.DialogInterface
import android.view.View

/**
 * The dialog only records what the builder wired up so the checks can drive the buttons; no
 * window/loopers are involved.
 */
class AlertDialog private constructor() : DialogInterface {
    var shown = false
        private set
    var titleId = 0
        private set
    var contentView: View? = null
        private set
    var positiveButtonListener: DialogInterface.OnClickListener? = null
        private set
    var negativeButtonListener: DialogInterface.OnClickListener? = null
        private set
    var neutralButtonListener: DialogInterface.OnClickListener? = null
        private set
    var dismissals = 0
        private set

    fun show() {
        shown = true
    }

    override fun dismiss() {
        dismissals++
    }

    class Builder(private val context: Context) {
        private var titleId = 0
        private var messageId = 0
        private var contentView: View? = null
        private var positiveListener: DialogInterface.OnClickListener? = null
        private var negativeListener: DialogInterface.OnClickListener? = null
        private var neutralListener: DialogInterface.OnClickListener? = null

        fun setTitle(titleId: Int): Builder {
            this.titleId = titleId
            return this
        }

        fun setMessage(messageId: Int): Builder {
            this.messageId = messageId
            return this
        }

        fun setView(view: View?): Builder {
            this.contentView = view
            return this
        }

        fun setPositiveButton(textId: Int, listener: DialogInterface.OnClickListener?): Builder {
            this.positiveListener = listener
            return this
        }

        fun setPositiveButton(text: CharSequence?, listener: DialogInterface.OnClickListener?): Builder {
            this.positiveListener = listener
            return this
        }

        fun setNegativeButton(textId: Int, listener: DialogInterface.OnClickListener?): Builder {
            this.negativeListener = listener
            return this
        }

        fun setNegativeButton(text: CharSequence?, listener: DialogInterface.OnClickListener?): Builder {
            this.negativeListener = listener
            return this
        }

        fun setNeutralButton(textId: Int, listener: DialogInterface.OnClickListener?): Builder {
            this.neutralListener = listener
            return this
        }

        fun setNeutralButton(text: CharSequence?, listener: DialogInterface.OnClickListener?): Builder {
            this.neutralListener = listener
            return this
        }

        fun create(): AlertDialog {
            val dialog = AlertDialog()
            dialog.titleId = titleId
            dialog.contentView = contentView
            dialog.positiveButtonListener = positiveListener
            dialog.negativeButtonListener = negativeListener
            dialog.neutralButtonListener = neutralListener
            lastCreated = dialog
            created.add(dialog)
            return dialog
        }

        companion object {
            /** The last dialog a check created; production dialogs are tracked by the OS instead. */
            var lastCreated: AlertDialog? = null

            /** Every dialog a check created, in creation order. */
            val created: MutableList<AlertDialog> = ArrayList()
        }
    }
}

/** Recording stand-in: the checks read back the channels and notifications a call produced. */
open class NotificationManager {
    val channels = ArrayList<NotificationChannel>()
    val posted = ArrayList<Pair<Int, Notification>>()

    fun createNotificationChannel(channel: NotificationChannel) {
        channels.add(channel)
    }

    fun notify(id: Int, notification: Notification) {
        posted.add(id to notification)
    }

    companion object {
        const val IMPORTANCE_LOW = 2
        const val IMPORTANCE_DEFAULT = 3
        const val IMPORTANCE_HIGH = 4
    }
}

class NotificationChannel(private val id: String?, private var name: CharSequence?, private var importance: Int) {
    private var description: String? = null
    fun getId(): String? = id
    fun getName(): CharSequence? = name
    fun getDescription(): String? = description
    fun getImportance(): Int = importance
    fun setName(name: CharSequence?) { this.name = name }
    fun setDescription(description: String?) { this.description = description }
    fun setImportance(importance: Int) { this.importance = importance }
}

/** What the AndroidX builder stand-in recorded; the platform object has no such accessors. */
class Notification(val channelId: String?, val smallIcon: Int, val title: CharSequence?, val bigText: CharSequence?)

/** A context subclass so a fragment can hand one back; no lifecycle is modelled. */
open class Activity : Context()

/** The deprecated platform fragment the settings screen still uses; it only records requests. */
open class Fragment {
    open fun getActivity(): Activity? = null
    val requested = ArrayList<Pair<List<String>, Int>>()
    fun requestPermissions(permissions: Array<String>, requestCode: Int) {
        requested.add(permissions.toList() to requestCode)
    }
}
