// Non-authoritative sandbox checks; see scripts/kotlin-sandbox/README.md before trusting a result.
// Runs the converted utility classes on a desktop JVM against hand-written platform stubs and
// mirrors the repository's own tests for them. It is not the Gradle unit/lint/instrumentation
// gate, and no Android behaviour (Handler, KeyEvent, resources) is exercised here.
import org.shadowice.flocke.andotp.Tasks.AuthenticationError
import org.shadowice.flocke.andotp.Tasks.AuthenticationOutcome
import org.shadowice.flocke.andotp.Tasks.AuthenticationTask
import org.shadowice.flocke.andotp.Tasks.BackupTaskResult
import org.shadowice.flocke.andotp.Tasks.ChangeCredentialsTask
import org.shadowice.flocke.andotp.Tasks.EncryptedBackupTask
import org.shadowice.flocke.andotp.Tasks.EncryptedRestoreTask
import org.shadowice.flocke.andotp.Tasks.FinishIntroTask
import org.shadowice.flocke.andotp.Tasks.GenericBackupTask
import org.shadowice.flocke.andotp.Tasks.GenericRestoreTask
import org.shadowice.flocke.andotp.Tasks.PGPBackupTask
import org.shadowice.flocke.andotp.Tasks.PGPRestoreTask
import org.shadowice.flocke.andotp.Tasks.TaskCancellation
import org.shadowice.flocke.andotp.Tasks.TaskCompletion
import org.shadowice.flocke.andotp.Tasks.TaskController
import org.shadowice.flocke.andotp.Tasks.TaskFailure
import org.shadowice.flocke.andotp.Tasks.TaskOutcome
import org.shadowice.flocke.andotp.Tasks.TaskStart
import org.shadowice.flocke.andotp.Tasks.TaskState
import org.shadowice.flocke.andotp.Tasks.UiBasedBackgroundTask
import org.shadowice.flocke.andotp.Database.Entry
import org.shadowice.flocke.andotp.Database.EntryJson
import org.shadowice.flocke.andotp.Database.EntryJsonError
import org.shadowice.flocke.andotp.Database.EntryJsonResult
import org.shadowice.flocke.andotp.Database.ValidatedEntry
import org.shadowice.flocke.andotp.Utilities.EntryThumbnail
import org.shadowice.flocke.andotp.Utilities.LetterBitmap
import org.shadowice.flocke.andotp.Utilities.BoundedInputReader
import org.shadowice.flocke.andotp.Utilities.ConfirmedPasswordTransformationHelper
import org.shadowice.flocke.andotp.Utilities.DimensionConverter
import org.shadowice.flocke.andotp.Utilities.SecretKeyWrapper
import org.shadowice.flocke.andotp.Utilities.BoundedOutput
import org.shadowice.flocke.andotp.Utilities.EditorActionHelper
import org.shadowice.flocke.andotp.Utilities.EncryptedBackupHeader
import org.shadowice.flocke.andotp.Utilities.HandlerTimerScheduler
import org.shadowice.flocke.andotp.Utilities.InputPolicy
import org.shadowice.flocke.andotp.Utilities.OtpHashAlgorithm
import org.shadowice.flocke.andotp.Utilities.OtpKind
import org.shadowice.flocke.andotp.Utilities.OtpParseResult
import org.shadowice.flocke.andotp.Utilities.OwnedSecret
import org.shadowice.flocke.andotp.Utilities.SessionCommandResult
import org.shadowice.flocke.andotp.Utilities.SessionController
import org.shadowice.flocke.andotp.Utilities.SessionFailure
import org.shadowice.flocke.andotp.Utilities.SessionLockReason
import org.shadowice.flocke.andotp.Utilities.SessionRejection
import org.shadowice.flocke.andotp.Utilities.SessionState
import org.shadowice.flocke.andotp.Utilities.ValidatedOtp
import org.shadowice.flocke.andotp.Utilities.VaultSession
import org.shadowice.flocke.andotp.Utilities.ScreenTimerController
import org.shadowice.flocke.andotp.Utilities.TokenCalculator
import org.shadowice.flocke.andotp.Utilities.Tools
import org.shadowice.flocke.andotp.Utilities.UIHelper
import org.shadowice.flocke.andotp.View.ItemTouchHelper.ItemTouchHelperAdapter
import org.shadowice.flocke.andotp.View.ItemTouchHelper.ItemTouchHelperViewHolder
import org.shadowice.flocke.andotp.View.ItemTouchHelper.SimpleItemTouchHelperCallback
import org.shadowice.flocke.andotp.View.TagsAdapter
import org.shadowice.flocke.andotp.View.ThumbnailSelectionAdapter
import org.shadowice.flocke.andotp.View.AutoFillable.AutoFillableTextInputEditText
import org.shadowice.flocke.andotp.View.SimpleDoubleClickListener
import org.shadowice.flocke.andotp.Preferences.NumberPickerPreference
import org.shadowice.flocke.andotp.Dialogs.HideableDialog
import org.shadowice.flocke.andotp.R

import android.app.AlertDialog
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.graphics.Canvas
import android.net.Uri
import android.os.Handler
import android.os.IBinder
import android.text.method.PasswordTransformationMethod
import android.text.method.TransformationMethod
import android.util.DisplayMetrics
import android.view.View
import android.view.inputmethod.InputMethodManager
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

import org.json.JSONObject

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.GeneralSecurityException
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.util.Arrays
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

private var failures = 0

private fun check(name: String, body: () -> Unit) {
    try {
        body()
        println("PASS  $name")
    } catch (t: Throwable) {
        failures++
        println("FAIL  $name -> $t")
    }
}

private fun expect(condition: Boolean, message: () -> String) {
    if (!condition) throw AssertionError(message())
}

private fun expectThrows(type: Class<out Throwable>, body: () -> Unit) {
    try {
        body()
    } catch (t: Throwable) {
        if (type.isInstance(t)) return
        throw AssertionError("Expected ${type.simpleName}, got ${t.javaClass.name}: ${t.message}")
    }
    throw AssertionError("Expected ${type.simpleName}, nothing was thrown")
}

// --- entry document helpers (the stored JSON format) -------------------------------------------

// The seed "1234567890": canonical, unpadded Base32; every fixture below uses it.
private const val ENTRY_SEED = "GEZDGNBVGY3TQOJQ"

/** The document the previous Java serializer produced for a TOTP entry, in field order. */
private const val STORED_ENTRY_JSON =
    "{\"secret\":\"IRQXGICTPFZXIZLNEBUXG5BAONUWG2DFOI======\",\"issuer\":\"\"," +
            "\"label\":\"5 von 5 Sterne\",\"digits\":6,\"type\":\"TOTP\",\"algorithm\":\"SHA1\"," +
            "\"thumbnail\":\"Default\",\"last_used\":0,\"used_frequency\":0,\"period\":30," +
            "\"tags\":[\"test1\",\"test2\"]}"

private fun entryDocument(secret: String = ENTRY_SEED, label: String = "synthetic"): JSONObject =
    JSONObject().put("secret", secret).put("label", label)

private fun entryValue(json: JSONObject): ValidatedEntry {
    val result = EntryJson.parse(json)
    expect(result is EntryJsonResult.Success, { "expected a parsed entry, got ${result}" })
    return (result as EntryJsonResult.Success).value
}

private fun entryError(json: JSONObject): EntryJsonError {
    val result = EntryJson.parse(json)
    expect(result is EntryJsonResult.Failure, { "expected a rejected entry, got Success" })
    return (result as EntryJsonResult.Failure).error
}

private fun ascii(text: String): ByteArray = text.toByteArray(StandardCharsets.US_ASCII)

// --- independent OTP implementation used to cross-check the converted class -------------------

private fun ownHash(algorithm: String, key: ByteArray, data: ByteArray): ByteArray {
    val mac = Mac.getInstance("Hmac$algorithm")
    mac.init(SecretKeySpec(key, "Hmac$algorithm"))
    return mac.doFinal(data)
}

private fun ownTruncated(algorithm: String, key: ByteArray, counter: Long): Int {
    val hash = ownHash(algorithm, key, ByteBuffer.allocate(8).putLong(counter).array())
    val offset = hash[hash.size - 1].toInt() and 0xF
    return ((hash[offset].toInt() and 0x7F) shl 0x18) or
            ((hash[offset + 1].toInt() and 0xFF) shl 0x10) or
            ((hash[offset + 2].toInt() and 0xFF) shl 0x08) or
            (hash[offset + 3].toInt() and 0xFF)
}

private fun ownTotp(algorithm: String, key: ByteArray, period: Int, time: Long, digits: Int, offset: Int): Int {
    val div = Math.pow(10.0, digits.toDouble()).toInt()
    return ownTruncated(algorithm, key, time / period + offset) % div
}

private fun ownSteam(algorithm: String, key: ByteArray, period: Int, time: Long, offset: Int): String {
    val alphabet = "23456789BCDFGHJKMNPQRTVWXY"
    var fullToken = ownTruncated(algorithm, key, time / period + offset)
    val builder = StringBuilder()
    repeat(5) {
        builder.append(alphabet[fullToken % alphabet.length])
        fullToken /= alphabet.length
    }
    return builder.toString()
}

private fun ownMotp(pin: String, secret: String, epoch: Long, offset: Int): String {
    val text = ((epoch / 10) + offset).toString() + secret + pin
    val digest = MessageDigest.getInstance("MD5").digest(text.toByteArray())
    val hex = StringBuilder()
    for (b in digest) hex.append(String.format("%02x", b.toInt() and 0xFF))
    return hex.substring(0, 6)
}

// --- reflection helpers for the Java-visible API shapes ---------------------------------------

private fun methodSignature(method: Method): String {
    val parameters = method.parameterTypes.joinToString(",") { it.simpleName }
    val prefix = if (Modifier.isStatic(method.modifiers)) "static " else ""
    return "$prefix${method.returnType.simpleName} ${method.name}($parameters)"
}

private fun publicMethods(type: Class<*>): List<String> =
        type.declaredMethods.filter { Modifier.isPublic(it.modifiers) }.map { methodSignature(it) }.sorted()

private val INT_TYPE: Class<*> = Int::class.javaPrimitiveType!!
private val LONG_TYPE: Class<*> = Long::class.javaPrimitiveType!!
private val FLOAT_TYPE: Class<*> = Float::class.javaPrimitiveType!!
private val BOOLEAN_TYPE: Class<*> = Boolean::class.javaPrimitiveType!!

private fun field(type: Class<*>, name: String): java.lang.reflect.Field? =
        type.declaredFields.firstOrNull { it.name == name }

private fun expectField(type: Class<*>, name: String, fieldType: Class<*>, isStatic: Boolean) {
    val found = field(type, name) ?: throw AssertionError("Missing field ${type.simpleName}.$name")
    expect(found.type == fieldType, { "${type.simpleName}.$name type ${found.type} != $fieldType" })
    expect(Modifier.isStatic(found.modifiers) == isStatic, { "${type.simpleName}.$name static mismatch" })
    if (isStatic) expect(Modifier.isFinal(found.modifiers), { "${type.simpleName}.$name is not final" })
}

private fun expectPublicFieldValue(type: Class<*>, name: String, expected: Any, fieldType: Class<*>, isStatic: Boolean) {
    expectField(type, name, fieldType, isStatic)
    val found = field(type, name)!!
    found.isAccessible = true
    val actual = found.get(if (Modifier.isStatic(found.modifiers)) null else null)
    expect(actual == expected, { "${type.simpleName}.$name = $actual, expected $expected" })
}

private fun expectMethod(type: Class<*>, signature: String) {
    expect(publicMethods(type).contains(signature), { "${type.simpleName} is missing [$signature]; has ${publicMethods(type)}" })
}

// --- fake scheduler for the timer controller --------------------------------------------------

private class FakeScheduler : ScreenTimerController.Scheduler {
    private val pending = LinkedHashMap<Runnable, Long>()
    private var now = 0L

    override fun postDelayed(runnable: Runnable, delayMillis: Long) {
        pending[runnable] = now + delayMillis
        lastPosted = runnable
    }

    override fun remove(runnable: Runnable) {
        pending.remove(runnable)
    }

    override fun elapsedRealtime(): Long = now

    /** Runs every callback that comes due, moving the virtual clock forward. */
    fun advance(millis: Long) {
        val target = now + millis
        while (true) {
            val next = pending.entries.filter { it.value <= target }.minByOrNull { it.value } ?: break
            pending.remove(next.key)
            now = next.value
            next.key.run()
        }
        now = target
    }

    /** Moves the clock without running callbacks (device asleep). */
    fun skip(millis: Long) {
        now += millis
    }

    var lastPosted: Runnable? = null
        private set

    fun runNext(): Boolean {
        val next = pending.entries.minByOrNull { it.value } ?: return false
        pending.remove(next.key)
        next.key.run()
        return true
    }

    fun scheduledCount(): Int = pending.size
}

/** Captures what a listener posts; the platform view would schedule it on its handler. */
private class FakeView : View() {
    val posted = ArrayList<Runnable>()
    var lastDelay = -1L
        private set

    override fun postDelayed(runnable: Runnable, delayMillis: Long): Boolean {
        posted.add(runnable)
        lastDelay = delayMillis
        return true
    }
}

private class RecordingListener : SimpleDoubleClickListener() {
    var singles = 0
    var doubles = 0

    override fun onSingleClick(v: View) {
        singles++
    }

    override fun onDoubleClick(v: View) {
        doubles++
    }
}

// --- fakes for the drag & drop, dialog and password helper checks -------------------------------

private class RecordingDragAdapter : ItemTouchHelperAdapter {
    val moves = ArrayList<Pair<Int, Int>>()

    override fun onItemMove(fromPosition: Int, toPosition: Int): Boolean {
        moves.add(Pair(fromPosition, toPosition))
        return true
    }
}

private class DragViewHolder(view: View, viewType: Int, position: Int) : RecyclerView.ViewHolder(view) {
    override val itemViewType: Int = viewType
    override val adapterPosition: Int = position
}

private class SelectableViewHolder(view: View) : RecyclerView.ViewHolder(view), ItemTouchHelperViewHolder {
    var selections = 0
    var clears = 0

    override fun onItemSelected() {
        selections++
    }

    override fun onItemClear() {
        clears++
    }
}

private class RecordingInputMethodManager : InputMethodManager() {
    val shownViews = ArrayList<View>()
    val hiddenTokens = ArrayList<IBinder?>()
    var toggles = 0

    override fun showSoftInput(view: View, flags: Int): Boolean {
        shownViews.add(view)
        return true
    }

    override fun hideSoftInputFromWindow(windowToken: IBinder?, flags: Int): Boolean {
        hiddenTokens.add(windowToken)
        return true
    }

    override fun toggleSoftInput(showFlags: Int, hideFlags: Int) {
        toggles++
    }
}

private class FakeUiContext : Context() {
    val imm = RecordingInputMethodManager()

    override fun getSystemService(name: String): Any? {
        return if (name == Context.INPUT_METHOD_SERVICE) imm else null
    }
}

private class FakePasswordInput : TextInputEditText() {
    val selections = ArrayList<Int>()

    override var transformationMethod: TransformationMethod? = null
    override val selectionEnd: Int = 7

    override fun setSelection(index: Int) {
        selections.add(index)
    }
}

// --- fakes for the background runner checks ----------------------------------------------------

/** Waits until the worker thread has queued its delivery on the fake main loop. */
private fun awaitQueuedDelivery() {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
    while (Handler.posted.isEmpty() && System.nanoTime() < deadline) Thread.sleep(10)
    expect(!Handler.posted.isEmpty(), { "the runner never queued a delivery" })
}

/** Synchronously runs what the runner posted until the fake main queue stays empty. */
private fun drainMainThreadDeliveries() {
    var rounds = 0
    while (rounds++ < 50) {
        var ran = false
        while (true) {
            val runnable = Handler.posted.poll() ?: break
            ran = true
            runnable.run()
        }
        if (!ran) break
    }
}

private class BlockingRunnerTask(fallback: String) : UiBasedBackgroundTask<String>(fallback) {
    val started = CountDownLatch(1)
    val release = CountDownLatch(1)
    val discarded = AtomicInteger(0)
    val discardedOnce = CountDownLatch(1)
    val cleanupRuns = AtomicInteger(0)

    override fun doInBackground(): String {
        started.countDown()
        try {
            release.await(5, TimeUnit.SECONDS)
        } catch (interrupted: InterruptedException) {
            // Unwind after the interrupt; the late value must still be disposed, like on device.
        }
        return "late-value"
    }

    override fun discardResult(result: String) {
        if (discarded.incrementAndGet() == 1) discardedOnce.countDown()
    }

    override fun onCancellation() {
        cleanupRuns.incrementAndGet()
    }
}

private class FailingRunnerTask(fallback: String, private val failure: IllegalStateException) : UiBasedBackgroundTask<String>(fallback) {
    override fun doInBackground(): String = throw failure
}

private class NullRunnerTask(fallback: String?) : UiBasedBackgroundTask<String?>(fallback) {
    override fun doInBackground(): String? = null
}

/** Recording preference over the fake superclass, not a real PreferenceManager/persistence test. */
private class SandboxNumberPreference(context: Context) : NumberPickerPreference(context, null) {
    val persisted = mutableListOf<Int>()
    var restoredValue: Int? = null
    var persistedFallback: Int? = null
    var allowChange = true
    val proposed = mutableListOf<Int>()
    lateinit var widget: android.widget.NumberPicker

    override fun persistInt(value: Int): Boolean { persisted.add(value); return true }
    override fun getPersistedInt(defaultValue: Int): Int {
        persistedFallback = defaultValue
        return restoredValue ?: defaultValue
    }
    override fun callChangeListener(value: Any?): Boolean {
        expect(widget.focusCleared, { "picker text must be committed before listener dispatch" })
        proposed.add(value as Int)
        return allowChange
    }
    fun createAndBind(): android.widget.FrameLayout {
        val root = onCreateDialogView() as android.widget.FrameLayout
        widget = root.getChildAt(0) as android.widget.NumberPicker
        onBindDialogView(root)
        return root
    }
    fun close(positive: Boolean) = onDialogClosed(positive)
    fun initialize(restore: Boolean, value: Any?) = onSetInitialValue(restore, value)
    fun defaultValue(array: android.content.res.TypedArray, index: Int): Any = onGetDefaultValue(array, index)
}

fun main() {
    val keySha1 = ascii("12345678901234567890")
    val keySha256 = ascii("12345678901234567890123456789012")
    val keySha512 = ascii("1234567890123456789012345678901234567890123456789012345678901234")

    check("RFC 6238 TOTP vectors (SHA-1/256/512, eight digits)") {
        val vectors = listOf(
                59L to Triple(94287082, 46119246, 90693936),
                1111111109L to Triple(7081804, 68084774, 25091201),
                1111111111L to Triple(14050471, 67062674, 99943326),
                1234567890L to Triple(89005924, 91819424, 93441116),
                2000000000L to Triple(69279037, 90698825, 38618901),
                20000000000L to Triple(65353130, 77737706, 47863826)
        )
        for ((time, expected) in vectors) {
            val period = TokenCalculator.TOTP_DEFAULT_PERIOD
            expect(TokenCalculator.TOTP_RFC6238(keySha1, period, time, 8, TokenCalculator.HashAlgorithm.SHA1) == expected.first,
                    { "SHA1 @$time" })
            expect(TokenCalculator.TOTP_RFC6238(keySha256, period, time, 8, TokenCalculator.HashAlgorithm.SHA256) == expected.second,
                    { "SHA256 @$time" })
            expect(TokenCalculator.TOTP_RFC6238(keySha512, period, time, 8, TokenCalculator.HashAlgorithm.SHA512) == expected.third,
                    { "SHA512 @$time" })
        }
    }

    check("RFC 4226 HOTP vectors") {
        val expected = listOf("755224", "287082", "359152", "969429", "338314",
                "254676", "287922", "162583", "399871", "520489")
        expected.forEachIndexed { counter, token ->
            expect(TokenCalculator.HOTP(keySha1, counter.toLong(), 6, TokenCalculator.HashAlgorithm.SHA1) == token,
                    { "HOTP counter $counter" })
        }
    }

    check("TOTP/HOTP agree with an independent HMAC implementation") {
        val algorithms = mapOf(
                TokenCalculator.HashAlgorithm.SHA1 to "SHA1",
                TokenCalculator.HashAlgorithm.SHA256 to "SHA256",
                TokenCalculator.HashAlgorithm.SHA512 to "SHA512")
        for ((algorithm, name) in algorithms) {
            val key = when (algorithm) {
                TokenCalculator.HashAlgorithm.SHA1 -> keySha1
                TokenCalculator.HashAlgorithm.SHA256 -> keySha256
                else -> keySha512
            }
            for (time in listOf(0L, 1L, 59L, 1111111109L, 1234567890L, 2000000000L)) {
                for (period in listOf(1, 30, 60, 86400)) {
                    expect(TokenCalculator.TOTP_RFC6238(key, period, time, 8, algorithm) == ownTotp(name, key, period, time, 8, 0),
                            { "$name period $period time $time" })
                }
                expect(TokenCalculator.TOTP_RFC6238(key, 30, time, 8, algorithm, 1) == ownTotp(name, key, 30, time, 8, 1),
                        { "$name offset step at $time" })
                expect(TokenCalculator.TOTP_RFC6238(key, 30, time, 8, algorithm, -1) == ownTotp(name, key, 30, time, 8, -1),
                        { "$name negative offset at $time" })
            }
        }
    }

    check("token strings keep leading zeros and reject unsafe parameters") {
        expect(Tools.formatTokenString(7081804, 8) == "07081804", { Tools.formatTokenString(7081804, 8) })
        expect(Tools.formatTokenString(0, 6) == "000000", { Tools.formatTokenString(0, 6) })
        val padded = TokenCalculator.TOTP_RFC6238(keySha1, 30, 1111111109L, 8, TokenCalculator.HashAlgorithm.SHA1)
        expect(Tools.formatTokenString(padded, 8) == "07081804", { Tools.formatTokenString(padded, 8) })
        expectThrows(IllegalArgumentException::class.java) {
            TokenCalculator.TOTP_RFC6238(keySha1, 0, 59L, 6, TokenCalculator.HashAlgorithm.SHA1)
        }
        expectThrows(IllegalArgumentException::class.java) {
            TokenCalculator.HOTP(keySha1, Long.MAX_VALUE, 6, TokenCalculator.HashAlgorithm.SHA1)
        }
        expectThrows(IllegalArgumentException::class.java) {
            TokenCalculator.HOTP(keySha1, 0L, 10, TokenCalculator.HashAlgorithm.SHA1)
        }
    }

    check("Steam tokens match the alphabet mapping of the same truncation") {
        val before = System.currentTimeMillis() / 1000
        val token = TokenCalculator.TOTP_Steam(keySha1, TokenCalculator.TOTP_DEFAULT_PERIOD,
                TokenCalculator.STEAM_DEFAULT_DIGITS, TokenCalculator.HashAlgorithm.SHA1, 0)
        val after = System.currentTimeMillis() / 1000
        expect(token.length == 5, { token })
        expect(token.all { it in "23456789BCDFGHJKMNPQRTVWXY" }, { token })
        val expected = setOf(
                ownSteam("SHA1", keySha1, 30, before, 0),
                ownSteam("SHA1", keySha1, 30, after, 0))
        expect(expected.contains(token), { "Steam token $token not in $expected" })
    }

    check("mOTP matches an independent MD5 computation") {
        val epoch = 1697055600L
        expect(TokenCalculator.MOTP("1234", "0123456789abcdef", epoch, 0) == ownMotp("1234", "0123456789abcdef", epoch, 0),
                { TokenCalculator.MOTP("1234", "0123456789abcdef", epoch, 0) })
        expect(TokenCalculator.MOTP("1234", "0123456789abcdef", epoch, 1) == ownMotp("1234", "0123456789abcdef", epoch, 1),
                { TokenCalculator.MOTP("1234", "0123456789abcdef", epoch, 1) })
        expect(TokenCalculator.MOTP("p", "s", epoch, 0).length == 6, { TokenCalculator.MOTP("p", "s", epoch, 0) })
    }

    check("input policy keeps the documented bounds") {
        expect(!InputPolicy.validImage(0, 100), { "zero width" })
        expect(!InputPolicy.validImage(-1, -1), { "negative" })
        expect(!InputPolicy.validImage(Int.MAX_VALUE, Int.MAX_VALUE), { "overflow" })
        expect(!InputPolicy.validImage(8192, 8192), { "pixel ceiling" })
        expect(InputPolicy.validImage(2000, 2000), { "accepted" })
        expect(!InputPolicy.validImage(2001, 2000), { "over pixel ceiling" })

        val deep = StringBuilder()
        repeat(InputPolicy.MAX_JSON_DEPTH + 1) { deep.append('[') }
        expectThrows(IllegalArgumentException::class.java) { InputPolicy.jsonDepth(deep.toString()) }
        InputPolicy.jsonDepth("""[{"label":"brackets [] {} and \"escaped quote"}]""")
        expectThrows(IllegalArgumentException::class.java) { InputPolicy.jsonDepth("[{}") }
        expectThrows(IllegalArgumentException::class.java) { InputPolicy.jsonDepth("{'single':1}") }
        expectThrows(IllegalArgumentException::class.java) { InputPolicy.jsonDepth("// comment") }

        expectThrows(IllegalArgumentException::class.java) { InputPolicy.tokenParameters(0, 6, 0) }
        expectThrows(IllegalArgumentException::class.java) { InputPolicy.tokenParameters(86401, 6, 0) }
        expectThrows(IllegalArgumentException::class.java) { InputPolicy.tokenParameters(30, 10, 0) }
        expectThrows(IllegalArgumentException::class.java) { InputPolicy.tokenParameters(30, 6, -1) }
        expectThrows(IllegalArgumentException::class.java) { InputPolicy.tokenParameters(30, 6, Long.MAX_VALUE) }
        InputPolicy.tokenParameters(1, 1, 0)
        InputPolicy.tokenParameters(86400, 9, Long.MAX_VALUE - 1)

        expect(BoundedInputReader.MAX_DOCUMENT_BYTES == 16 * 1024 * 1024, { "document byte limit" })
    }

    check("entry documents keep the stored format, field order and defaults") {
        val parsed = entryValue(JSONObject(STORED_ENTRY_JSON))
        try {
            expect(parsed.kind == OtpKind.TOTP, { "kind" })
            expect(parsed.label == "5 von 5 Sterne", { "label" })
            expect(parsed.issuer.isEmpty(), { "issuer" })
            expect(parsed.period == 30 && parsed.digits == 6 && parsed.counter == 0L, { "OTP parameters" })
            expect(parsed.algorithm == OtpHashAlgorithm.SHA1, { "algorithm" })
            expect(parsed.thumbnailName == "Default", { "thumbnail" })
            expect(parsed.tags == listOf("test1", "test2"), { "tags" })
            expect(parsed.lastUsed == 0L && parsed.usedFrequency == 0L, { "usage metadata" })
            expect(parsed.copySecret().contentEquals("Das System ist sicher".toByteArray(StandardCharsets.UTF_8)),
                    { "seed" })

            // The stored format is the Java serializer's: same names, same order, same values.
            val written = EntryJson.serialize(parsed.kind, parsed.copySecret(), parsed.issuer, parsed.label,
                    parsed.digits, parsed.period, parsed.counter, parsed.algorithm, parsed.thumbnailName!!,
                    parsed.lastUsed, parsed.usedFrequency, parsed.tags)
            expect(written.toString() == STORED_ENTRY_JSON, { "stored form changed: $written" })
            expect(written.keys().asSequence().toList() == listOf("secret", "issuer", "label", "digits", "type",
                    "algorithm", "thumbnail", "last_used", "used_frequency", "period", "tags"),
                    { "field order changed: ${written.keys().asSequence().toList()}" })
        } finally {
            parsed.close()
        }

        // A legacy document that predates most fields keeps the documented defaults.
        val legacy = entryValue(JSONObject().put("secret", ENTRY_SEED).put("label", "legacy"))
        try {
            expect(legacy.kind == OtpKind.TOTP && legacy.period == 30 && legacy.digits == 6, { "legacy defaults" })
            expect(legacy.counter == 0L && legacy.algorithm == OtpHashAlgorithm.SHA1, { "legacy parameters" })
            expect(legacy.issuer.isEmpty() && legacy.tags.isEmpty(), { "legacy text" })
            expect(legacy.thumbnailName == null && legacy.lastUsed == 0L && legacy.usedFrequency == 0L,
                    { "legacy metadata" })
        } finally {
            legacy.close()
        }

        // Steam keeps its own digit default, and a document may carry numbers as strings.
        val steam = entryValue(entryDocument().put("type", "STEAM"))
        try {
            expect(steam.kind == OtpKind.STEAM && steam.digits == 5, { "Steam digit default" })
        } finally {
            steam.close()
        }
        val strings = entryValue(entryDocument().put("period", "0030").put("digits", "8"))
        try {
            expect(strings.period == 30 && strings.digits == 8, { "numeric strings" })
        } finally {
            strings.close()
        }

        // HOTP writes its counter and no period; TOTP writes its period and no counter.
        val hotp = EntryJson.serialize(OtpKind.HOTP, ascii("12345678901234567890"), "", "hotp", 8, 30, 42L,
                OtpHashAlgorithm.SHA256, "GitHub", 5L, 7L, listOf("work"))
        expect(hotp.getString("type") == "HOTP" && hotp.getString("algorithm") == "SHA256", { "HOTP fields" })
        expect(hotp.getLong("counter") == 42L && !hotp.has("period"), { "HOTP counter/period" })
        expect(hotp.getLong("last_used") == 5L && hotp.getLong("used_frequency") == 7L, { "usage metadata" })
        expect(hotp.getJSONArray("tags").getString(0) == "work", { "tags array" })
        val totp = EntryJson.serialize(OtpKind.TOTP, ascii("12345678901234567890"), "ACME", "totp", 6, 60, 0L,
                OtpHashAlgorithm.SHA1, "Default", 0L, 0L, emptyList())
        expect(totp.getInt("period") == 60 && !totp.has("counter"), { "TOTP period/counter" })
        expect(totp.getString("issuer") == "ACME" && totp.getJSONArray("tags").length() == 0, { "TOTP fields" })
    }

    check("entry documents reject malformed fields with a closed error set") {
        expect(EntryJsonError.values().map { it.name } == listOf("INVALID_TEXT", "TEXT_TOO_LONG",
                "MISSING_SECRET", "INVALID_SECRET", "INVALID_NUMBER", "MISSING_COUNTER",
                "INVALID_PARAMETERS", "UNSUPPORTED_TYPE", "INVALID_ALGORITHM", "INVALID_TAGS", "TOO_MANY_TAGS"),
                { "the error set is no longer closed: ${EntryJsonError.values().map { it.name }}" })

        // Same cases the device test EntryInputSecurityTest asserts an exception for.
        expect(entryError(entryDocument().put("period", 0)) == EntryJsonError.INVALID_PARAMETERS, { "period 0" })
        expect(entryError(entryDocument().put("period", -1)) == EntryJsonError.INVALID_PARAMETERS, { "period -1" })
        expect(entryError(entryDocument().put("period", 86401)) == EntryJsonError.INVALID_PARAMETERS, { "period max" })
        expect(entryError(entryDocument().put("period", 4294967326L)) == EntryJsonError.INVALID_NUMBER,
                { "period beyond an int" })
        expect(entryError(entryDocument().put("period", 1.5)) == EntryJsonError.INVALID_NUMBER, { "period 1.5" })
        expect(entryError(entryDocument().put("period", "1.5")) == EntryJsonError.INVALID_NUMBER,
                { "period \"1.5\"" })
        expect(entryError(entryDocument().put("period", "9223372036854775808")) == EntryJsonError.INVALID_NUMBER,
                { "period overflow" })
        expect(entryError(entryDocument().put("digits", 10)) == EntryJsonError.INVALID_PARAMETERS, { "digits 10" })
        expect(entryError(entryDocument().put("digits", 0)) == EntryJsonError.INVALID_PARAMETERS, { "digits 0" })
        expect(entryError(entryDocument().put("type", "STEAM").put("digits", 6)) == EntryJsonError.INVALID_PARAMETERS,
                { "Steam digits" })

        // Counter-based entries require a usable counter.
        expect(entryError(entryDocument().put("type", "HOTP")) == EntryJsonError.MISSING_COUNTER,
                { "missing HOTP counter" })
        expect(entryError(entryDocument().put("type", "HOTP").put("counter", Long.MAX_VALUE)) ==
                EntryJsonError.INVALID_PARAMETERS, { "HOTP counter ceiling" })
        expect(entryError(entryDocument().put("type", "HOTP").put("counter", -1)) == EntryJsonError.INVALID_PARAMETERS,
                { "negative HOTP counter" })
        val counter = entryValue(entryDocument().put("type", "HOTP").put("counter", 42L))
        try {
            expect(counter.kind == OtpKind.HOTP && counter.counter == 42L, { "HOTP counter" })
        } finally {
            counter.close()
        }

        // Text fields: missing, JSON null and non-string values all reject, names stay case-sensitive.
        expect(entryError(JSONObject().put("label", "no secret")) == EntryJsonError.MISSING_SECRET,
                { "missing secret" })
        expect(entryError(entryDocument().put("secret", JSONObject.NULL)) == EntryJsonError.INVALID_TEXT,
                { "null secret" })
        expect(entryError(entryDocument().put("secret", 5)) == EntryJsonError.INVALID_TEXT, { "numeric secret" })
        expect(entryError(JSONObject().put("secret", ENTRY_SEED)) == EntryJsonError.INVALID_TEXT, { "missing label" })
        expect(entryError(entryDocument().put("label", 5)) == EntryJsonError.INVALID_TEXT, { "numeric label" })
        expect(entryError(entryDocument().put("type", "totp")) == EntryJsonError.UNSUPPORTED_TYPE,
                { "lowercase type" })
        expect(entryError(entryDocument().put("type", 7)) == EntryJsonError.INVALID_TEXT, { "numeric type" })
        expect(entryError(entryDocument().put("algorithm", "sha1")) == EntryJsonError.INVALID_ALGORITHM,
                { "lowercase algorithm" })
        expect(entryError(entryDocument().put("algorithm", 5)) == EntryJsonError.INVALID_TEXT, { "numeric algorithm" })
        expect(entryError(entryDocument().put("label", "x".repeat(InputPolicy.MAX_TEXT_CHARS + 1))) ==
                EntryJsonError.TEXT_TOO_LONG, { "oversized label" })

        // Seeds must be canonical Base32 and stay within the seed bounds.
        expect(entryError(entryDocument(secret = "")) == EntryJsonError.INVALID_SECRET, { "empty seed" })
        expect(entryError(entryDocument(secret = "!!!")) == EntryJsonError.INVALID_SECRET, { "non-Base32 seed" })
        expect(entryError(entryDocument(secret = "$ENTRY_SEED=")) == EntryJsonError.INVALID_SECRET,
                { "non-canonical seed" })
        expect(entryError(entryDocument(secret = "A".repeat(InputPolicy.MAX_SECRET_BYTES * 2 + 1))) ==
                EntryJsonError.INVALID_SECRET, { "oversized seed" })

        // Tags: count, element type and length are all bounded.
        val tooManyTags = org.json.JSONArray()
        for (index in 0..InputPolicy.MAX_TAGS) tooManyTags.put("tag")
        expect(entryError(entryDocument().put("tags", tooManyTags)) == EntryJsonError.TOO_MANY_TAGS, { "tag count" })
        expect(entryError(entryDocument().put("tags", org.json.JSONArray().put(42))) == EntryJsonError.INVALID_TAGS,
                { "numeric tag" })
        expect(entryError(entryDocument().put("tags", "not an array")) == EntryJsonError.INVALID_TAGS,
                { "tags not an array" })
        expect(entryError(entryDocument().put("tags", org.json.JSONArray().put("x".repeat(InputPolicy.MAX_TAG_CHARS + 1)))) ==
                EntryJsonError.TEXT_TOO_LONG, { "oversized tag" })

        // Seeds are accepted in either letter case and without padding.
        val lower = entryValue(entryDocument(secret = ENTRY_SEED.lowercase()))
        try {
            expect(lower.copySecret().contentEquals(ascii("1234567890")), { "lowercase seed" })
        } finally {
            lower.close()
        }

        // Display metadata is best-effort: an unknown/oversized/absent name is not a rejection.
        val unknownThumbnail = entryValue(entryDocument().put("thumbnail", "NotAThumbnail"))
        try {
            expect(unknownThumbnail.thumbnailName == "NotAThumbnail", { "unknown thumbnail name carried" })
        } finally {
            unknownThumbnail.close()
        }
        val oversizedThumbnail = entryValue(entryDocument().put("thumbnail", "x".repeat(InputPolicy.MAX_TEXT_CHARS + 1)))
        try {
            expect(oversizedThumbnail.thumbnailName == null, { "oversized thumbnail name dropped" })
        } finally {
            oversizedThumbnail.close()
        }
        val unreadableUsage = entryValue(entryDocument().put("last_used", "x").put("used_frequency", 1.5))
        try {
            expect(unreadableUsage.lastUsed == 0L && unreadableUsage.usedFrequency == 1L,
                    { "usage fallbacks: ${unreadableUsage.lastUsed}/${unreadableUsage.usedFrequency}" })
        } finally {
            unreadableUsage.close()
        }
    }

    check("entry document values are one-use, redacted and wipe their seed") {
        val value = entryValue(entryDocument(label = "redact-me"))
        val seedField = ValidatedEntry::class.java.getDeclaredField("seed")
        seedField.isAccessible = true
        val seed = seedField.get(value) as ByteArray
        expect(seed.contentEquals(ascii("1234567890")), { "stored seed" })

        val copy = value.copySecret()
        copy.fill(0)
        expect(value.copySecret().contentEquals(ascii("1234567890")),
                { "a returned copy must not be the stored seed" })
        expect(!value.toString().contains("redact-me") && !value.toString().contains("GEZD"),
                { "value diagnostics leaked: $value" })
        value.close()
        expect(seed.all { it == 0.toByte() }, { "close() did not wipe the stored seed" })
        value.close()
        expectThrows(IllegalStateException::class.java) { value.copySecret() }

        val success = EntryJsonResult.Success(entryValue(entryDocument()))
        expect(!success.toString().contains("GEZD") && !success.toString().contains("synthetic"),
                { "success diagnostics leaked: $success" })
        (success.value).close()

        val failure = EntryJson.parse(entryDocument(secret = "not-a-seed"))
        expect(failure is EntryJsonResult.Failure, { "invalid seed accepted" })
        expect(failure.toString() == "Failure(error=INVALID_SECRET)", { "failure diagnostics: $failure" })
    }

    check("entry secret checks keep the Base32 and mOTP hex rules") {
        expect(Entry.validateSecret(ENTRY_SEED, Entry.OTPType.TOTP), { "canonical Base32" })
        expect(Entry.validateSecret(ENTRY_SEED.lowercase(), Entry.OTPType.TOTP), { "lowercase Base32" })
        expect(Entry.validateSecret("IRQXGICTPFZXIZLNEBUXG5BAONUWG2DFOI======", Entry.OTPType.TOTP),
                { "padded Base32" })
        expect(!Entry.validateSecret("$ENTRY_SEED=", Entry.OTPType.TOTP), { "non-canonical Base32" })
        expect(!Entry.validateSecret("", Entry.OTPType.TOTP), { "empty Base32" })
        expect(!Entry.validateSecret(null, Entry.OTPType.TOTP), { "null Base32" })
        expect(!Entry.validateSecret("!!!", Entry.OTPType.TOTP), { "invalid Base32" })
        expect(!Entry.validateSecret("A".repeat(InputPolicy.MAX_SECRET_BYTES * 2 + 1), Entry.OTPType.TOTP),
                { "oversized Base32" })
        expect(!Entry.validateSecret("GEZDGNBVGY3TQOJQ", Entry.OTPType.MOTP), { "Base32 is not mOTP hex" })
        expect(Entry.validateSecret("0123456789abcdef", Entry.OTPType.MOTP), { "mOTP hex" })
        expect(Entry.validateSecret("0123456789ABCDEF", Entry.OTPType.MOTP), { "uppercase mOTP hex" })
        expect(!Entry.validateSecret("012", Entry.OTPType.MOTP), { "odd mOTP hex" })
        // The historical check accepted an empty mOTP secret; commons-codec decodes it to nothing.
        expect(Entry.validateSecret("", Entry.OTPType.MOTP), { "empty mOTP hex" })

        expect(EntryJson.decodeBase32Secret(ENTRY_SEED)!!.contentEquals(ascii("1234567890")),
                { "decodeBase32Secret" })
        expect(EntryJson.decodeBase32Secret(null) == null, { "null seed" })
        expect(EntryJson.decodeBase32Secret("$ENTRY_SEED=") == null, { "non-canonical seed" })
    }

    check("the converted entry class keeps its Java-visible shapes") {
        val entry = Entry::class.java
        expect(Modifier.isPublic(entry.modifiers), { "Entry is not public" })
        expect(!Modifier.isFinal(entry.modifiers), { "Entry must stay extendable like the Java class" })
        expect(Entry.OTPType.values().map { it.name } == listOf("TOTP", "HOTP", "MOTP", "STEAM"),
                { "OTPType constants changed" })
        expect(Entry.COLOR_RED == 1, { "COLOR_RED" })

        fun constructorOf(vararg parameters: Class<*>): java.lang.reflect.Constructor<*> =
            entry.declaredConstructors.firstOrNull { it.parameterTypes.contentEquals(parameters) }
                ?: throw AssertionError("missing Entry constructor ${parameters.map { it.simpleName }}")

        expect(entry.declaredConstructors.any { it.parameterTypes.isEmpty() }, { "missing Entry()" })
        constructorOf(Entry.OTPType::class.java, String::class.java, Int::class.javaPrimitiveType!!,
                Int::class.javaPrimitiveType!!, String::class.java, String::class.java,
                TokenCalculator.HashAlgorithm::class.java, java.util.List::class.java)
        constructorOf(Entry.OTPType::class.java, String::class.java, LONG_TYPE, Int::class.javaPrimitiveType!!,
                String::class.java, String::class.java, TokenCalculator.HashAlgorithm::class.java,
                java.util.List::class.java)
        constructorOf(Entry.OTPType::class.java, String::class.java, String::class.java, String::class.java,
                java.util.List::class.java)

        // The legacy adapters keep declaring the checked exception their Java callers catch.
        for (parameters in listOf(arrayOf<Class<*>>(String::class.java), arrayOf<Class<*>>(JSONObject::class.java))) {
            val constructor = constructorOf(*parameters)
            expect(constructor.exceptionTypes.contains(Exception::class.java),
                    { "Entry(${parameters.first().simpleName}) no longer declares throws Exception" })
        }

        expect(entry.getMethod("getThumbnail").returnType == EntryThumbnail.EntryThumbnails::class.java,
                { "thumbnail getter type changed" })
    }

    check("thumbnail names retain exact and ordered whole-word lookup semantics") {
        val thumbnails = EntryThumbnail.EntryThumbnails.values()
        for (thumbnail in thumbnails) {
            expect(EntryThumbnail.EntryThumbnails.valueOfIgnoreCase(thumbnail.name.lowercase()) == thumbnail,
                    { "case-insensitive lookup: ${thumbnail.name}" })
            expect(thumbnail.assetType == EntryThumbnail.AssetType.Vector, { "asset default" })
        }
        expect(EntryThumbnail.EntryThumbnails.valueOfFuzzy("GitHub Inc.") == EntryThumbnail.EntryThumbnails.GitHub,
                { "whole-word lookup" })
        // Declaration order, not textual position or longest match: Git precedes GitHub.
        expect(EntryThumbnail.EntryThumbnails.valueOfFuzzy("GitHub and Git") == EntryThumbnail.EntryThumbnails.Git,
                { "lookup order changed" })
        for (unknown in listOf("not-a-brand", "MyGitHubAccount", "GitHub_extra", "")) {
            expectThrows(IllegalArgumentException::class.java) { EntryThumbnail.EntryThumbnails.valueOfFuzzy(unknown) }
        }
        for (unknown in listOf(null, " GitHub", "GitHub ", "unknown")) {
            expectThrows(IllegalArgumentException::class.java) { EntryThumbnail.EntryThumbnails.valueOfIgnoreCase(unknown) }
        }
    }

    check("thumbnail helpers retain their Java-visible entry points") {
        val thumbnail = EntryThumbnail::class.java
        expect(!Modifier.isFinal(thumbnail.modifiers), { "EntryThumbnail was extendable" })
        thumbnail.getConstructor()
        val graphic = thumbnail.getMethod("getThumbnailGraphic", Context::class.java, String::class.java,
                String::class.java, Int::class.javaPrimitiveType!!, EntryThumbnail.EntryThumbnails::class.java)
        expect(Modifier.isStatic(graphic.modifiers) && graphic.returnType == android.graphics.Bitmap::class.java,
                { "static graphic API" })
        val enum = EntryThumbnail.EntryThumbnails::class.java
        for (name in listOf("valueOfIgnoreCase", "valueOfFuzzy")) {
            val method = enum.getMethod(name, String::class.java)
            expect(Modifier.isStatic(method.modifiers) && method.returnType == enum, { name })
        }
        expect(enum.getMethod("getResource").returnType == Int::class.javaPrimitiveType, { "resource getter" })
        expect(enum.getMethod("getAssetType").returnType == EntryThumbnail.AssetType::class.java, { "asset getter" })
        LetterBitmap::class.java.getConstructor(Context::class.java)
        val tile = LetterBitmap::class.java.getMethod("getLetterTile", String::class.java, String::class.java,
                Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!)
        expect(tile.returnType == android.graphics.Bitmap::class.java, { "tile API" })
    }

    check("tag lists preserve sorted names, borrowed state and copied snapshots") {
        val states = hashMapOf<String, Boolean?>("zeta" to false, "Alpha" to true, "alpha" to false)
        val adapter = TagsAdapter(Context(), states)
        expect(adapter.getTags() == listOf("Alpha", "alpha", "zeta"), { "natural sort" })
        expect(adapter.getActiveTags() == listOf("Alpha"), { "active sort" })
        expect(adapter.getTagState("missing") == false && adapter.getTagState(null) == false, { "unknown tag" })
        val before = adapter.changeNotifications
        adapter.setTagState("missing", true)
        expect(!states.containsKey("missing") && adapter.changeNotifications > before, { "unknown toggle" })
        adapter.setTagState("zeta", true)
        expect(states["zeta"] == true, { "constructor must borrow state map" })
        states["alpha"] = true
        expect(adapter.allTagsActive(), { "external state changes are still visible" })
        adapter.getTagsWithState()["alpha"] = false
        expect(adapter.getTagState("alpha") == true, { "snapshot must be independent" })
        val row = adapter.getView(0, null, android.view.ViewGroup()) as android.widget.CheckedTextView
        expect(row.text == "Alpha" && row.isChecked, { "row bind" })
        val reused = adapter.getView(2, row, android.view.ViewGroup())
        expect(reused === row && row.text == "zeta" && row.isChecked, { "row reuse" })
        val replacement = hashMapOf<String, Boolean?>("two" to false, "one" to true)
        adapter.setTags(replacement)
        expect(adapter.getTags() == listOf("one", "two") && adapter.getCount() == 2, { "replace, not merge" })
        expect(adapter.getItem(0) == "one" && adapter.getActiveTags() == listOf("one"), { "replacement data" })
        adapter.setTagState("two", true)
        expect(replacement["two"] == true && adapter.allTagsActive(), { "replacement map must be borrowed" })
    }

    check("empty tags remain all-active and boxed null states are not silently unchecked") {
        val adapter = TagsAdapter(Context(), hashMapOf())
        expect(adapter.allTagsActive() && adapter.getActiveTags().isEmpty(), { "empty tags" })
        adapter.setTags(hashMapOf("tag" to true))
        adapter.setTagState("tag", null)
        expect(adapter.getTagState("tag") == null, { "boxed null state" })
        expectThrows(NullPointerException::class.java) { adapter.getActiveTags() }
        expectThrows(NullPointerException::class.java) { adapter.allTagsActive() }
    }

    check("autofill delegates before callbacks and ignores non-text values and cleared listeners") {
        val field = AutoFillableTextInputEditText(Context())
        val text = android.view.autofill.AutofillValue.forText("preview")
        var calls = 0
        field.setAutoFillTextListener {
            expect(field.autofilledValue === text, { "super.autofill must run first" })
            expect(it.toString() == "preview", { "callback text" })
            calls++
        }
        field.autofill(text)
        field.autofill(android.view.autofill.AutofillValue.forToggle(true))
        field.autofill(null) // Only the fake superclass tolerates null; not a platform null guarantee.
        expect(calls == 1, { "only text should trigger callbacks" })
        field.setAutoFillTextListener(null)
        field.autofill(text)
        expect(calls == 1 && field.autofilledValue === text, { "listener clearing must not disable platform autofill" })
    }

    check("autofill and thumbnail picker keep the Java-visible API shapes") {
        val picker = ThumbnailSelectionAdapter::class.java
        expect(!Modifier.isFinal(picker.modifiers), { "picker was extendable" })
        picker.getConstructor(Context::class.java, String::class.java, String::class.java)
        picker.getMethod("filter", String::class.java)
        expect(picker.getMethod("getItem", Int::class.javaPrimitiveType!!).returnType == Any::class.java,
                { "getItem Object return type" })
        expect(picker.getMethod("getItemId", Int::class.javaPrimitiveType!!).returnType == LONG_TYPE, { "item id" })
        val field = AutoFillableTextInputEditText::class.java
        expect(!Modifier.isFinal(field.modifiers), { "autofill widget was extendable" })
        field.getConstructor(Context::class.java)
        field.getConstructor(Context::class.java, android.util.AttributeSet::class.java)
        field.getConstructor(Context::class.java, android.util.AttributeSet::class.java, Int::class.javaPrimitiveType!!)
        field.getMethod("setAutoFillTextListener", AutoFillableTextInputEditText.AutoFillTextListener::class.java)
        val listener = AutoFillableTextInputEditText.AutoFillTextListener::class.java
        expect(listener.isInterface && listener.declaredMethods.size == 1, { "Java SAM listener" })
        listener.getMethod("onTextAutoFilled", CharSequence::class.java)
    }

    check("number picker keeps default and configured bounds, layout and summary") {
        val defaults = SandboxNumberPreference(Context())
        defaults.setValue(12)
        val root = defaults.createAndBind()
        expect(root.childCount == 1, { "one picker per dialog" })
        expect(defaults.widget.minValue == 0 && defaults.widget.maxValue == 100 && defaults.widget.wrapSelectorWheel,
                { "default bounds and wrap" })
        expect(defaults.widget.value == 12 && defaults.summary.toString() == "12", { "bind current value" })
        val params = defaults.widget.layoutParams as android.widget.FrameLayout.LayoutParams
        expect(params.width == -2 && params.height == -2 && params.gravity == android.view.Gravity.CENTER,
                { "wrap-content, centered picker" })
        var recycled = false
        val attrs = object : android.content.res.TypedArray() {
            override fun getInteger(index: Int, defValue: Int): Int = when (index) {
                R.styleable.NumberPickerPreference_minValue -> 3
                R.styleable.NumberPickerPreference_maxValue -> 9
                else -> throw AssertionError("unexpected integer attribute")
            }
            override fun getBoolean(index: Int, defValue: Boolean): Boolean {
                expect(index == R.styleable.NumberPickerPreference_wrapSelectorWheel, { "wrap attribute" })
                return false
            }
            override fun recycle() { recycled = true }
        }
        val context = object : Context() {
            override fun obtainStyledAttributes(attrsSet: android.util.AttributeSet?, styleable: IntArray): android.content.res.TypedArray {
                expect(styleable === R.styleable.NumberPickerPreference, { "styleable array" })
                return attrs
            }
        }
        val configured = SandboxNumberPreference(context)
        expect(recycled, { "typed array must be recycled after reading" })
        configured.setValue(5)
        configured.createAndBind()
        expect(configured.widget.minValue == 3 && configured.widget.maxValue == 9 && !configured.widget.wrapSelectorWheel,
                { "configured bounds" })
    }

    check("number picker cancellation and veto do not persist a proposed edit") {
        val pref = SandboxNumberPreference(Context())
        pref.setValue(5)
        pref.createAndBind()
        pref.widget.value = 7
        val persistedBefore = pref.persisted.size
        pref.close(false)
        expect(!pref.widget.focusCleared && pref.proposed.isEmpty() && pref.persisted.size == persistedBefore,
                { "cancel must not commit or notify" })
        pref.allowChange = false
        pref.close(true)
        expect(pref.widget.focusCleared && pref.proposed == listOf(7), { "veto must be offered committed edit" })
        expect(pref.getValue() == 5 && pref.persisted.size == persistedBefore && pref.summary.toString() == "5",
                { "veto must preserve value and summary" })
        pref.allowChange = true
        pref.close(true)
        expect(pref.getValue() == 7 && pref.persisted.last() == 7 && pref.summary.toString() == "7", { "accepted edit" })
    }

    check("number preference keeps default parsing and restore-versus-initial-value semantics") {
        val pref = SandboxNumberPreference(Context())
        val missingDefault = object : android.content.res.TypedArray() {
            override fun getInt(index: Int, defValue: Int): Int {
                expect(index == 4 && defValue == 0, { "typed-array index and min fallback" })
                return 8
            }
        }
        expect(pref.defaultValue(missingDefault, 4) == 8, { "boxed default value" })
        pref.initialize(false, 8)
        expect(pref.getValue() == 8 && pref.persisted.last() == 8 && pref.summary.toString() == "8", { "initial default" })
        pref.restoredValue = 19
        pref.initialize(true, null)
        expect(pref.getValue() == 19 && pref.persistedFallback == 0 && pref.summary.toString() == "19", { "restore persisted" })
        pref.restoredValue = null
        pref.initialize(true, 99)
        expect(pref.getValue() == 0 && pref.summary.toString() == "0", { "missing persisted value uses min, not default object" })
    }

    check("number preference and hideable dialog keep their Java entry points") {
        val number = NumberPickerPreference::class.java
        expect(!Modifier.isFinal(number.modifiers), { "preference must remain extendable" })
        number.getConstructor(Context::class.java, android.util.AttributeSet::class.java)
        number.getConstructor(Context::class.java, android.util.AttributeSet::class.java, Int::class.javaPrimitiveType!!)
        expect(number.getField("DEFAULT_MAX_VALUE").getInt(null) == 100, { "max constant" })
        expect(number.getField("DEFAULT_MIN_VALUE").getInt(null) == 0, { "min constant" })
        expect(number.getField("DEFAULT_WRAP_SELECTOR_WHEEL").getBoolean(null), { "wrap constant" })
        number.getMethod("setValue", Int::class.javaPrimitiveType!!)
        expect(number.getMethod("getValue").returnType == Int::class.javaPrimitiveType, { "value getter" })
        for (method in number.declaredMethods.filter { it.name.startsWith("on") }) {
            expect(Modifier.isProtected(method.modifiers) && !Modifier.isFinal(method.modifiers), { "overridable protected hook ${method.name}" })
        }
        val dialog = HideableDialog::class.java
        expect(!Modifier.isFinal(dialog.modifiers), { "dialog must remain extendable" })
        dialog.getConstructor(Context::class.java, Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!)
        val show = dialog.getMethod("ShowHidableDialog", Context::class.java, Int::class.javaPrimitiveType!!,
                Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!)
        expect(Modifier.isStatic(show.modifiers), { "legacy Java show spelling/static API" })
        expect(View.OnClickListener::class.java.isAssignableFrom(dialog), { "click listener" })
        expect(android.widget.CompoundButton.OnCheckedChangeListener::class.java.isAssignableFrom(dialog), { "checkbox listener" })
    }

    check("bounded document reads reject oversize input and keep the caller's stream") {
        expect(BoundedInputReader.read(ByteArrayInputStream(ByteArray(0)), 0).isEmpty(), { "empty" })
        val exact = byteArrayOf(0, 1, 255.toByte())
        expect(BoundedInputReader.read(ByteArrayInputStream(exact), exact.size).contentEquals(exact), { "exact" })
        expectThrows(IOException::class.java) { BoundedInputReader.read(ByteArrayInputStream(ByteArray(4)), 3) }
        expectThrows(IOException::class.java) { BoundedInputReader.read(ByteArrayInputStream(ByteArray(1)), 0) }
        expectThrows(IOException::class.java) { BoundedInputReader.read(null, 10) }
        expectThrows(IllegalArgumentException::class.java) { BoundedInputReader.read(ByteArrayInputStream(ByteArray(0)), -1) }
        expectThrows(IllegalArgumentException::class.java) { BoundedInputReader.read(ByteArrayInputStream(ByteArray(0)), Int.MAX_VALUE) }

        var consumed = 0
        val unending = object : InputStream() {
            override fun read(): Int {
                consumed++
                return 7
            }
        }
        expectThrows(IOException::class.java) { BoundedInputReader.read(unending, 9000) }
        expect(consumed == 9001, { "consumed $consumed" })

        val data = ByteArray(9000) { 7 }
        val shortReader = object : ByteArrayInputStream(data) {
            private var returnZero = true
            override fun read(bytes: ByteArray, offset: Int, count: Int): Int {
                if (returnZero) {
                    returnZero = false
                    return 0
                }
                return super.read(bytes, offset, minOf(count, 3))
            }
        }
        expect(BoundedInputReader.read(shortReader, data.size).contentEquals(data), { "short reads" })

        val broken = object : InputStream() {
            override fun read(): Int = throw IOException("provider failed")
        }
        expectThrows(IOException::class.java) { BoundedInputReader.read(broken, 10) }

        var closed = false
        val owned = object : ByteArrayInputStream(byteArrayOf(1)) {
            override fun close() {
                closed = true
            }
        }
        BoundedInputReader.read(owned, 1)
        expect(!closed, { "caller stream was closed" })
    }

    check("bounded provider output cannot become partial data") {
        val output = BoundedOutput(3)
        output.write(byteArrayOf(1, 2, 3), 0, 3)
        expectThrows(IllegalStateException::class.java) { output.write(4) }
        expect(output.overflowed(), { "overflow flag" })
        expect(output.size() == 3, { "size ${output.size()}" })

        val fresh = BoundedOutput(0)
        expect(!fresh.overflowed(), { "flag before writes" })
        fresh.write(ByteArray(0), 0, 0)
        expectThrows(IllegalStateException::class.java) { fresh.write(byteArrayOf(1), 0, 1) }
        expect(fresh.overflowed() && fresh.size() == 0, { "size after rejected write" })
        expectThrows(IllegalArgumentException::class.java) { BoundedOutput(-1) }

        val negative = BoundedOutput(2)
        expectThrows(IllegalStateException::class.java) { negative.write(byteArrayOf(1), 0, -1) }
        expect(negative.overflowed(), { "negative length counts as overflow" })
        // A negative offset is still the superclass's business (platform IndexOutOfBoundsException).
        val offset = BoundedOutput(2)
        expectThrows(IndexOutOfBoundsException::class.java) { offset.write(byteArrayOf(1), -1, 1) }
    }

    check("encrypted backup header framing is validated before derivation") {
        fun backup(iterations: Int): ByteArray {
            val data = ByteArray(EncryptedBackupHeader.MIN_BACKUP_BYTES)
            ByteBuffer.wrap(data).putInt(iterations)
            Arrays.fill(data, 4, data.size, 7.toByte())
            return data
        }
        expect(EncryptedBackupHeader.MIN_BACKUP_BYTES == 44, { "minimum size" })
        for (count in intArrayOf(1, 10000, 140000, 160000, EncryptedBackupHeader.MAX_ITERATIONS)) {
            val header = EncryptedBackupHeader.parse(backup(count))
            expect(header.getIterations() == count, { "iterations $count" })
            expect(header.getSalt().size == 12, { "salt size" })
            expect(header.getEncrypted().size == 28, { "cipher size" })
        }
        expectThrows(IllegalArgumentException::class.java) { EncryptedBackupHeader.parse(null) }
        for (length in 0 until EncryptedBackupHeader.MIN_BACKUP_BYTES) {
            val truncated = Arrays.copyOf(backup(140000), length)
            expectThrows(IllegalArgumentException::class.java) { EncryptedBackupHeader.parse(truncated) }
        }
        for (count in intArrayOf(Int.MIN_VALUE, -1, 0, EncryptedBackupHeader.MAX_ITERATIONS + 1, Int.MAX_VALUE)) {
            expectThrows(IllegalArgumentException::class.java) { EncryptedBackupHeader.parse(backup(count)) }
        }
        val oversized = ByteArray(BoundedInputReader.MAX_DOCUMENT_BYTES + 1)
        ByteBuffer.wrap(oversized).putInt(140000)
        expectThrows(IllegalArgumentException::class.java) { EncryptedBackupHeader.parse(oversized) }

        val data = backup(140000)
        val header = EncryptedBackupHeader.parse(data)
        Arrays.fill(data, 0)
        expect(header.getIterations() == 140000, { "iterations after source wipe" })
        expect(header.getSalt()[0] == 7.toByte(), { "salt after source wipe" })
        expect(header.getEncrypted()[0] == 7.toByte(), { "cipher after source wipe" })
        header.getSalt()[0] = 0
        header.getEncrypted()[0] = 0
        expect(header.getSalt()[0] == 7.toByte(), { "salt copy" })
        expect(header.getEncrypted()[0] == 7.toByte(), { "cipher copy" })
    }

    check("screen timers stay single-loop, cancellable and permanently closed") {
        var ticks = 0
        var expiries = 0
        val scheduler = FakeScheduler()
        val timers = ScreenTimerController(scheduler, Runnable { ticks++ }, Runnable { expiries++ })

        timers.startTicks()
        timers.startTicks()
        scheduler.advance(3000)
        expect(ticks == 4, { "ticks after 3s with double start: $ticks" })

        timers.pause()
        val ticksAfterPause = ticks
        scheduler.advance(5000)
        expect(ticks == ticksAfterPause, { "ticks after pause: $ticks" })

        timers.startTicks()
        scheduler.advance(2000)
        expect(ticks == ticksAfterPause + 3, { "ticks after resume: $ticks" })

        timers.restartInactivity(5000)
        scheduler.advance(4999)
        expect(expiries == 0, { "expired early: $expiries" })
        scheduler.advance(1)
        expect(expiries == 1, { "expiry missing: $expiries" })

        timers.restartInactivity(1000)
        timers.restartInactivity(10000)
        scheduler.advance(2000)
        expect(expiries == 1, { "superseded inactivity fired: $expiries" })

        timers.cancelInactivity()
        scheduler.advance(60000)
        expect(expiries == 1, { "cancelled inactivity fired: $expiries" })

        timers.close()
        val ticksAfterClose = ticks
        timers.startTicks()
        timers.restartInactivity(1)
        scheduler.advance(60000)
        expect(ticks == ticksAfterClose && expiries == 1, { "closed controller restarted" })
    }

    check("stale timer callbacks cannot restart a stopped or closed controller") {
        var ticks = 0
        val scheduler = FakeScheduler()
        val timers = ScreenTimerController(scheduler, Runnable { ticks++ }, Runnable {})
        timers.startTicks()
        val stale = scheduler.runNext()
        expect(stale && ticks == 1, { "first tick" })
        val dequeued = scheduler.lastPosted!!
        timers.stopTicks()
        dequeued.run()
        expect(ticks == 1, { "dequeued tick still ran the callback" })
        expect(!scheduler.runNext(), { "stopped timer rescheduled itself" })

        var expired = 0
        val closing = FakeScheduler()
        lateinit var closeTimers: ScreenTimerController
        closeTimers = ScreenTimerController(closing, Runnable { closeTimers.stopTicks() }, Runnable { expired++ })
        closeTimers.startTicks()
        closing.advance(5000)
        expect(ticks == 1, { "self-stopping tick loop kept running" })

        closeTimers.restartInactivity(3000)
        val pendingExpiry = closing.lastPosted!!
        closing.skip(10000)
        closeTimers.close()
        pendingExpiry.run()
        expect(expired == 0, { "closed controller expired the session: $expired" })
    }

    check("inactivity uses elapsed time and survives a device sleep") {
        var expired = 0
        val scheduler = FakeScheduler()
        val timers = ScreenTimerController(scheduler, Runnable {}, Runnable { expired++ })
        timers.restartInactivity(5000)
        scheduler.advance(1000)
        expect(expired == 0, { "early expiry" })
        scheduler.skip(60000)
        scheduler.advance(0)
        expect(expired == 1, { "expiry missed after sleep: $expired" })

        var disabled = 0
        val other = FakeScheduler()
        val zero = ScreenTimerController(other, Runnable {}, Runnable { disabled++ })
        zero.restartInactivity(0)
        other.advance(60000)
        expect(disabled == 0, { "zero delay expired" })
        zero.restartInactivity(-5)
        other.advance(60000)
        expect(disabled == 0, { "negative delay expired" })
    }

    check("session contract keeps one authority and one-use secrets") {
        val session = SessionController(Any())
        expect(session.snapshot() is SessionState.Locked, { "initial state" })
        val attempt = session.beginUnlock()
        expect(session.currentKey() == null, { "key before activation" })
        val first = SecretKeySpec(ByteArray(16), "AES")
        expect(session.activate(first, attempt.generation) is SessionCommandResult.Applied, { "activation" })
        expect(session.currentKey() === first, { "current key" })
        val epoch = session.snapshot().generation
        session.activate(first, epoch)
        expect(session.snapshot().generation == epoch, { "same key advanced the generation" })
        session.activate(SecretKeySpec(ByteArray(16), "AES"), epoch)
        expect(!session.isCurrent(epoch), { "stale generation is still current" })
    }

    check("stale activation and a failed session never install a key") {
        val session = SessionController(Any())
        val epoch = session.beginUnlock().generation
        session.lock(SessionLockReason.SCREEN_OFF)
        val stale = session.activate(SecretKeySpec(ByteArray(16), "AES"), epoch) as SessionCommandResult.Rejected
        expect(stale.reason == SessionRejection.STALE_GENERATION, { "stale rejection" })
        session.fail(SessionFailure.RECOVERY_REQUIRED)
        val rejected = session.activate(SecretKeySpec(ByteArray(16), "AES"),
                session.snapshot().generation) as SessionCommandResult.Rejected
        expect(rejected.reason == SessionRejection.FAILED_SESSION, { "failed rejection" })
        expect(session.currentKey() == null, { "key installed into a failed session" })
        session.beginUnlock()
        expect(session.snapshot() is SessionState.Unlocking, { "a fresh attempt is explicit" })
    }

    check("owned secrets are one-use and redacted") {
        val source = byteArrayOf(1, 2, 3)
        val secret = OwnedSecret.copyOf(source)
        source.fill(0)
        expect(secret.toString() == "OwnedSecret(redacted)", { secret.toString() })
        val transferred = secret.takeBytes()
        expect(transferred.contentEquals(byteArrayOf(1, 2, 3)), { "transferred bytes" })
        secret.close()
        expect(transferred.contentEquals(byteArrayOf(1, 2, 3)), { "ownership stayed with the caller" })
        expectThrows(IllegalStateException::class.java) { secret.takeBytes() }
        transferred.fill(0)

        val closed = OwnedSecret.copyOf(ByteArray(16))
        closed.close()
        closed.close()
        expectThrows(IllegalStateException::class.java) { closed.takeBytes() }
        expectThrows(IllegalArgumentException::class.java) { OwnedSecret.copyOf(ByteArray(0)) }
        expectThrows(IllegalArgumentException::class.java) { OwnedSecret.copyOf(ByteArray(65)) }
    }

    check("task lifecycle is one-shot and revocation replaces pending completion") {
        val task = TaskController(10)
        expect(task.snapshot() is TaskState.Ready, { "ready" })
        expect(task.start() == TaskStart.STARTED, { "start" })
        expect(task.snapshot() is TaskState.Running, { "running" })
        expect(task.start() == TaskStart.ALREADY_SUBMITTED, { "double start" })
        expect(task.complete(TaskCompletion.VALUE), { "complete" })
        expect(task.claimDelivery(), { "claim delivery" })
        expect((task.snapshot() as TaskState.Delivered).completion == TaskCompletion.VALUE, { "delivered value" })
        expect(task.deliveryConsumed(), { "delivery consumed" })
        expect(!task.claimDelivery(), { "second claim" })
        expect(!task.cancel(TaskCancellation.EXPLICIT), { "cancel after delivery" })

        val revoked = TaskController(5)
        revoked.start()
        revoked.complete(TaskCompletion.VALUE)
        revoked.observeGeneration(6)
        expect((revoked.snapshot() as TaskState.Cancelled).reason == TaskCancellation.SESSION_REVOKED, { "revocation" })
        expect(revoked.claimDelivery(), { "cancellation delivery" })
        expect(!revoked.complete(TaskCompletion.FAILURE), { "complete after revocation" })

        val delivered = TaskController(5)
        delivered.start()
        delivered.complete(TaskCompletion.VALUE)
        delivered.claimDelivery()
        delivered.observeGeneration(6)
        expect(delivered.snapshot() is TaskState.Delivered, { "delivered state must survive revocation" })

        val preStart = TaskController(0)
        expect(preStart.cancel(TaskCancellation.EXPLICIT), { "pre-start cancel" })
        expect(preStart.start() == TaskStart.CANCELED, { "start after cancel" })
        expect(!preStart.complete(TaskCompletion.VALUE), { "complete without start" })
        expect(preStart.claimDelivery(), { "first cancellation delivery" })
        expect(!preStart.claimDelivery(), { "second cancellation delivery" })
        expect(preStart.start() == TaskStart.ALREADY_SUBMITTED, { "restart after cancel" })

        val firstReason = TaskController(1)
        firstReason.cancel(TaskCancellation.EXPLICIT)
        firstReason.observeGeneration(2)
        expect((firstReason.snapshot() as TaskState.Cancelled).reason == TaskCancellation.EXPLICIT,
                { "the first cancellation reason wins" })
        expect(!firstReason.cancel(TaskCancellation.SESSION_REVOKED), { "second cancellation" })
    }

    check("outcome and OTP diagnostics never print secrets") {
        expect(TaskOutcome.Value("synthetic-secret").toString() == "TaskOutcome.Value(redacted)",
                { TaskOutcome.Value("synthetic-secret").toString() })
        expect(TaskOutcome.Failure<String>(TaskFailure.NULL_RESULT).error == TaskFailure.NULL_RESULT, { "failure error" })
        val otp = ValidatedOtp.create(OtpKind.TOTP, byteArrayOf(1, 2), 30, 6, 0, "issuer", "label",
                OtpHashAlgorithm.SHA1, emptyList())
        expect(otp.toString() == "ValidatedOtp(redacted)", { otp.toString() })
        expect(OtpParseResult.Success(otp).toString() == "OtpParseResult.Success(redacted)",
                { OtpParseResult.Success(otp).toString() })
        expect(otp.copySecret().contentEquals(byteArrayOf(1, 2)), { "secret copy" })
        otp.close()
        expectThrows(IllegalStateException::class.java) { otp.copySecret() }
        val verified = AuthenticationOutcome.verified(ByteArray(32), 7)
        expect(verified.toString() == "AuthenticationOutcome.Verified(redacted)", { verified.toString() })
        expect(verified.generation == 7L, { "outcome generation" })
        verified.close()
        expect(AuthenticationOutcome.Rejected(AuthenticationError.DENIED).error == AuthenticationError.DENIED,
                { "rejected outcome" })
    }

    check("secret key wrapper round-trips AES keys through real RSA wrapping") {
        val generator = KeyPairGenerator.getInstance("RSA")
        generator.initialize(2048)
        val pair = generator.generateKeyPair()
        val wrapper = SecretKeyWrapper(pair)

        val raw = ByteArray(16) { (it + 1).toByte() }
        val wrapped = wrapper.wrap(SecretKeySpec(raw, "AES"))
        expect(wrapped.size == 256, { "wrapped length ${wrapped.size} is not the RSA modulus size" })
        expect(!wrapped.contentEquals(raw), { "wrapped blob exposes the key" })

        val unwrapped = wrapper.unwrap(wrapped)
        expect(unwrapped.algorithm == "AES", { "unwrapped algorithm ${unwrapped.algorithm}" })
        expect(unwrapped.encoded.contentEquals(raw), { "round trip changed the key bytes" })

        // Proves the transformation is the mirrored RSA/PKCS#1 v1.5 constant, not just any round trip.
        val manual = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        manual.init(Cipher.DECRYPT_MODE, pair.private)
        expect(manual.doFinal(wrapped).contentEquals(raw), { "wrapped bytes are not RSA/PKCS1Padding output" })

        val tampered = wrapped.copyOf()
        tampered[10] = (tampered[10].toInt() xor 0x01).toByte()
        expectThrows(GeneralSecurityException::class.java) { wrapper.unwrap(tampered) }
        expectThrows(GeneralSecurityException::class.java) { SecretKeyWrapper(generator.generateKeyPair()).unwrap(wrapped) }
        expectThrows(GeneralSecurityException::class.java) { wrapper.unwrap(ByteArray(8)) }
        expectThrows(GeneralSecurityException::class.java) { wrapper.unwrap(ByteArray(300)) }

        // A 32-byte key (the largest AES size used by the vault) still round-trips.
        val longKey = ByteArray(32) { (it * 7 + 3).toByte() }
        expect(wrapper.unwrap(wrapper.wrap(SecretKeySpec(longKey, "AES"))).encoded.contentEquals(longKey),
                { "AES-256 round trip changed the key bytes" })
    }

    check("double-click listener separates single and double taps") {
        val view = FakeView()
        val listener = RecordingListener()

        listener.onClick(view)
        expect(listener.singles == 0 && listener.doubles == 0, { "a tap fired immediately" })
        expect(view.posted.size == 1 && view.lastDelay == 300L, { "single tap not delayed by 300ms" })
        view.posted[0].run()
        expect(listener.singles == 1 && listener.doubles == 0, { "pending single click did not fire" })

        val doubleView = FakeView()
        val doubleListener = RecordingListener()
        doubleListener.onClick(doubleView)
        doubleListener.onClick(doubleView)
        expect(doubleListener.doubles == 1 && doubleListener.singles == 0, { "second tap did not become a double click" })
        doubleView.posted.forEach { it.run() }
        expect(doubleListener.singles == 0, { "suppressed single click still fired" })

        // A third rapid tap is not a second double click, matching the original firstTap guard.
        doubleListener.onClick(doubleView)
        expect(doubleListener.doubles == 1, { "third rapid tap produced another double click" })

        // A pause longer than the delta makes the next tap a fresh single click.
        Thread.sleep(350)
        doubleListener.onClick(doubleView)
        doubleView.posted.last().run()
        expect(doubleListener.singles == 1, { "slow tap after a double click was not a single click" })
    }

    check("dimension strings convert with the documented units and rounding") {
        val metrics = DisplayMetrics()
        metrics.density = 2.0f
        metrics.scaledDensity = 2.5f
        metrics.xdpi = 320f
        metrics.ydpi = 320f

        expect(DimensionConverter.stringToDimensionPixelSize("12dp", metrics) == 24, { "12dp" })
        expect(DimensionConverter.stringToDimensionPixelSize("12dip", metrics) == 24, { "12dip" })
        expect(DimensionConverter.stringToDimensionPixelSize("12px", metrics) == 12, { "12px" })
        expect(DimensionConverter.stringToDimensionPixelSize("12DP", metrics) == 24, { "12DP" })
        expect(DimensionConverter.stringToDimensionPixelSize(" 12 dp ", metrics) == 24, { "padded input" })
        expect(DimensionConverter.stringToDimensionPixelSize("1.5sp", metrics) == 4, { "1.5sp rounding" })
        expect(DimensionConverter.stringToDimensionPixelSize("2in", metrics) == 640, { "2in" })
        expect(DimensionConverter.stringToDimensionPixelSize("72pt", metrics) == 320, { "72pt" })
        expect(DimensionConverter.stringToDimensionPixelSize("25.4mm", metrics) == 320, { "25.4mm" })
        expect(DimensionConverter.stringToDimensionPixelSize("0dp", metrics) == 0, { "0dp" })
        expect(DimensionConverter.stringToDimension("12dp", metrics) == 24.0f, { "stringToDimension" })

        // Values that round to zero pixels still become one pixel when positive.
        val sparse = DisplayMetrics()
        sparse.density = 0.001f
        expect(DimensionConverter.stringToDimensionPixelSize("12dp", sparse) == 1, { "sub-pixel value" })

        for (invalid in listOf("12", "12zz", "", "dp12", "12.5.5dp", "-12dp", "12 dp x")) {
            expectThrows(NumberFormatException::class.java) {
                DimensionConverter.stringToDimensionPixelSize(invalid, metrics)
            }
        }

        val lookup = DimensionConverter.dimensionConstantLookup
        expect(lookup.size == 7, { "lookup size ${lookup.size}" })
        expect(lookup["px"] == 0 && lookup["dip"] == 1 && lookup["dp"] == 1 && lookup["sp"] == 2
                && lookup["pt"] == 3 && lookup["in"] == 4 && lookup["mm"] == 5, { "unit mapping changed" })
        expectThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (lookup as MutableMap<String, Int>)["xx"] = 1
        }
    }

    check("drag & drop callback forwards moves and wires the view holder callbacks") {
        val recyclerView = RecyclerView()
        recyclerView.layoutManager = LinearLayoutManager()
        val adapter = RecordingDragAdapter()
        val callback = SimpleItemTouchHelperCallback(adapter)

        // Long-press dragging starts enabled and can be switched off, like the sorted/search flows do.
        expect(callback.isLongPressDragEnabled(), { "dragging must start enabled" })
        callback.setDragEnabled(false)
        expect(!callback.isLongPressDragEnabled(), { "setDragEnabled(false) did not disable dragging" })
        callback.setDragEnabled(true)
        expect(callback.isLongPressDragEnabled(), { "setDragEnabled(true) did not re-enable dragging" })
        expect(!callback.isItemViewSwipeEnabled(), { "swiping must stay disabled" })

        // Vertical list: UP|DOWN only; grid: all four axes. The flag composition lives in the
        // stub (documented replication), so the assertions decode the documented bit fields:
        // the low byte carries swipe|drag and the swipe byte (bits 8-15) must stay empty.
        val listFlags = callback.getMovementFlags(recyclerView, DragViewHolder(View(), 0, 0))
        expect(((listFlags shr 16) and 0xFF) == (ItemTouchHelper.UP or ItemTouchHelper.DOWN), { "list drag flags $listFlags" })
        expect((listFlags and 0xFF) == (ItemTouchHelper.UP or ItemTouchHelper.DOWN) &&
                ((listFlags shr 8) and 0xFF) == 0, { "list must not enable swiping" })
        recyclerView.layoutManager = GridLayoutManager()
        val gridFlags = callback.getMovementFlags(recyclerView, DragViewHolder(View(), 0, 0))
        expect(((gridFlags shr 16) and 0xFF) ==
                (ItemTouchHelper.UP or ItemTouchHelper.DOWN or ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT),
                { "grid drag flags $gridFlags" })
        expect(((gridFlags shr 8) and 0xFF) == 0, { "grid must not enable swiping" })

        // Moves between items of the same type are forwarded; mixed types are rejected.
        val source = DragViewHolder(View(), 0, 2)
        val target = DragViewHolder(View(), 0, 5)
        expect(callback.onMove(recyclerView, source, target), { "same-type move rejected" })
        expect(adapter.moves == listOf(Pair(2, 5)), { "move not forwarded: ${adapter.moves}" })
        val foreign = DragViewHolder(View(), 1, 6)
        expect(!callback.onMove(recyclerView, source, foreign), { "cross-type move accepted" })
        expect(adapter.moves.size == 1, { "cross-type move was forwarded" })

        // Select/clear lifecycle only fires for item-touch-holders and non-idle states.
        val selectable = SelectableViewHolder(View())
        callback.onSelectedChanged(selectable, ItemTouchHelper.ACTION_STATE_DRAG)
        expect(selectable.selections == 1, { "active item was not selected" })
        callback.onSelectedChanged(selectable, ItemTouchHelper.ACTION_STATE_IDLE)
        expect(selectable.selections == 1, { "idle state re-selected the item" })
        callback.onSelectedChanged(null, ItemTouchHelper.ACTION_STATE_DRAG)
        selectable.itemView.alpha = 0.3f
        callback.clearView(recyclerView, selectable)
        expect(selectable.clears == 1 && selectable.itemView.alpha == SimpleItemTouchHelperCallback.ALPHA_FULL,
                { "clearView did not restore the idle alpha" })

        // The swipe fade (unreachable while swiping is disabled) keeps its arithmetic, and the
        // non-swipe branch still delegates to the super class.
        val swiped = SelectableViewHolder(View())
        swiped.itemView.width = 200
        callback.onChildDraw(Canvas(), recyclerView, swiped, -50f, 0f, ItemTouchHelper.ACTION_STATE_SWIPE, true)
        expect(swiped.itemView.alpha == 0.75f, { "swipe alpha ${swiped.itemView.alpha}" })
        expect(swiped.itemView.translationX == -50f, { "swipe translation" })
        callback.onChildDraw(Canvas(), recyclerView, swiped, -50f, 0f, ItemTouchHelper.ACTION_STATE_DRAG, true)
        expect(swiped.itemView.translationX == -50f, { "drag draw changed the view" })
        callback.onSwiped(swiped, ItemTouchHelper.DOWN) // disabled feature: must stay a no-op
    }

    check("generic dialogs and keyboard helpers keep their wiring") {
        val context = FakeUiContext()
        val view = FakeView()

        var okRuns = 0
        UIHelper.showGenericDialog(context, 11, 13)
        val plain = AlertDialog.Builder.lastCreated ?: throw AssertionError("no dialog was created")
        expect(plain.shown, { "dialog was not shown" })
        plain.positiveButtonListener!!.onClick(plain, DialogInterface.BUTTON_POSITIVE)
        expect(okRuns == 0, { "a missing handler was invoked" })

        UIHelper.showGenericDialog(context, 11, 13) { okRuns++ }
        val withOk = AlertDialog.Builder.lastCreated ?: throw AssertionError("no second dialog")
        withOk.positiveButtonListener!!.onClick(withOk, DialogInterface.BUTTON_POSITIVE)
        expect(okRuns == 1, { "confirmed action did not run" })

        UIHelper.showKeyboard(context, view)
        expect(context.imm.shownViews == listOf<View>(view) && context.imm.toggles == 0,
                { "default showKeyboard must request, not toggle" })

        UIHelper.showKeyboard(context, view, true)
        expect(context.imm.toggles == 1, { "forced showKeyboard must toggle" })

        // A null flag was never passed; the Java unboxing would have thrown for one.
        expectThrows(NullPointerException::class.java) { UIHelper.showKeyboard(context, view, null) }

        UIHelper.hideKeyboard(context, view)
        expect(context.imm.hiddenTokens.size == 1, { "hideKeyboard did not hide" })

        // A missing view stays a no-op, exactly like the original null guards.
        UIHelper.showKeyboard(context, null)
        UIHelper.hideKeyboard(context, null)
        expect(context.imm.shownViews.size == 1 && context.imm.hiddenTokens.size == 1 && context.imm.toggles == 1,
                { "a missing view must stay a no-op" })
    }

    check("password visibility toggles keep both fields and the cursor in sync") {
        val layout = TextInputLayout()
        val password = FakePasswordInput()
        val confirm = FakePasswordInput()

        ConfirmedPasswordTransformationHelper.setup(layout, password, confirm)
        expect(password.transformationMethod is PasswordTransformationMethod &&
                confirm.transformationMethod is PasswordTransformationMethod,
                { "setup must start with both fields masked" })
        expect(layout.endIconClickListener != null, { "no end-icon listener was installed" })

        val listener = layout.endIconClickListener!!
        listener.onClick(layout)
        expect(password.transformationMethod == null && confirm.transformationMethod == null,
                { "first toggle must unmask both fields" })
        expect(password.selections == listOf(7) && confirm.selections == listOf(7),
                { "cursor position was not restored" })
        expect(layout.refreshCount == 1, { "layout was not refreshed" })

        listener.onClick(layout)
        expect(password.transformationMethod is PasswordTransformationMethod &&
                confirm.transformationMethod is PasswordTransformationMethod,
                { "second toggle must re-mask both fields" })
        expect(password.selections == listOf(7, 7), { "cursor position was not kept on re-mask" })
        expect(layout.refreshCount == 2, { "second toggle did not refresh the layout" })
    }

    check("backup task results keep their factories, defaults and redaction") {
        val success = BackupTaskResult.success(BackupTaskResult.ResultType.BACKUP, "andotp-backup.json")
        expect(success.success && success.resultType == BackupTaskResult.ResultType.BACKUP,
                { "success reports the wrong state" })
        expect(success.payload == "andotp-backup.json", { "file name payload changed" })
        expect(success.messageId == R.string.backup_toast_export_success, { "success message id changed" })
        expect(!success.isPGP && success.decryptIntent == null && success.uri == null, { "PGP defaults changed" })

        val failure = BackupTaskResult.failure(BackupTaskResult.ResultType.RESTORE, 4242)
        expect(!failure.success && failure.payload == null && failure.messageId == 4242,
                { "failure reports the wrong state" })

        val decryptIntent = Intent()
        val uri = Uri()
        val pgp = BackupTaskResult(BackupTaskResult.ResultType.RESTORE, true, "decrypted", 0, true, decryptIntent, uri)
        expect(pgp.isPGP && pgp.decryptIntent === decryptIntent && pgp.uri === uri, { "PGP constructor changed" })
        pgp.isPGP = false
        expect(!pgp.isPGP, { "isPGP is no longer writable" })

        // payload can be the whole parsed database; the default toString must not broadcast it.
        expect(!success.toString().contains("andotp-backup"), { "payload leaked through toString" })
        expect(BackupTaskResult.ResultType.values().map { it.name } == listOf("NONE", "BACKUP", "RESTORE"),
                { "ResultType constants changed" })
        expect(BackupTaskResult.ResultType.valueOf("NONE") == BackupTaskResult.ResultType.NONE, { "valueOf broke" })
    }

    check("background runner delivers one owned value through the fake main loop") {
        Handler.posted.clear()
        val task = BlockingRunnerTask("fallback")
        val delivered = ArrayList<TaskOutcome<String>>()
        task.setOutcomeCallback { delivered.add(it) }
        task.setOutcomeCallback(null) // rebinding before start must be a no-op, not a delivery leak
        task.setOutcomeCallback { delivered.add(it) }
        task.execute()
        expect(task.started.await(5, TimeUnit.SECONDS), { "background work never started" })
        task.release.countDown()
        awaitQueuedDelivery()
        drainMainThreadDeliveries()
        expect(delivered.size == 1, { "delivered ${delivered.size} outcomes" })
        val value = delivered[0] as? TaskOutcome.Value<*> ?: throw AssertionError("not a value: ${delivered[0]}")
        expect(value.value == "late-value", { "wrong payload ${value.value}" })
        expect(task.getState() is TaskState.Delivered, { "state ${task.getState()}" })

        // A binding after the delivery was consumed is ignored instead of replaying it.
        val late = ArrayList<TaskOutcome<String>>()
        task.setOutcomeCallback { late.add(it) }
        drainMainThreadDeliveries()
        expect(late.isEmpty(), { "a consumed delivery was replayed" })
        expectThrows(IllegalStateException::class.java) { task.execute() }
    }

    check("null and thrown results become failures and legacy clients get the fallback") {
        Handler.posted.clear()
        val nullTask = NullRunnerTask("fallback")
        val outcomes = ArrayList<TaskOutcome<String?>>()
        nullTask.setOutcomeCallback { outcomes.add(it) }
        nullTask.execute()
        awaitQueuedDelivery()
        drainMainThreadDeliveries()
        expect(outcomes.size == 1 && outcomes[0] is TaskOutcome.Failure, { "a null result was not a typed failure" })
        expect((outcomes[0] as TaskOutcome.Failure<*>).error == TaskFailure.NULL_RESULT, { "wrong failure code" })

        val legacyNull = NullRunnerTask("fallback")
        var legacyResult: String? = "unset"
        legacyNull.setCallback { legacyResult = it }
        legacyNull.execute()
        awaitQueuedDelivery()
        drainMainThreadDeliveries()
        expect(legacyResult == "fallback", { "legacy callback received $legacyResult" })

        val failing = FailingRunnerTask("legacy-fallback", IllegalStateException("synthetic-provider-failure"))
        val typed = ArrayList<TaskOutcome<String>>()
        failing.setOutcomeCallback { typed.add(it) }
        failing.execute()
        awaitQueuedDelivery()
        drainMainThreadDeliveries()
        expect(typed.size == 1 && typed[0] is TaskOutcome.Failure, { "a thrown result was not a typed failure" })
        expect((typed[0] as TaskOutcome.Failure<*>).error == TaskFailure.UNEXPECTED_EXCEPTION,
                { "wrong failure kind ${typed[0]}" })
        expect(failing.getState() is TaskState.Delivered, { "failure was not delivered" })
    }

    check("cancellation is one-shot, cleans up once and disposes the fallback") {
        val task = BlockingRunnerTask("fallback")
        val delivered = ArrayList<TaskOutcome<String>>()
        task.setOutcomeCallback { delivered.add(it) }
        task.cancel()
        expect(task.cleanupRuns.get() == 1, { "cancel() did not run owned-input cleanup" })
        task.release.countDown() // the worker never started; this is harmless
        drainMainThreadDeliveries()
        expect(delivered.size == 1 && delivered[0] is TaskOutcome.Cancelled, { "cancel did not deliver cancellation" })
        expect((delivered[0] as TaskOutcome.Cancelled<*>).reason == TaskCancellation.EXPLICIT, { "wrong reason" })

        // A cancelled task can still be started once: the CANCELED path disposes the fallback,
        // keeps the cleanup idempotent and does not deliver again (the callback was consumed).
        task.execute()
        expect(task.cleanupRuns.get() == 1, { "cleanup must stay idempotent" })
        expect(task.discarded.get() == 1, { "the cancelled start did not dispose the fallback" })
        drainMainThreadDeliveries()
        expect(delivered.size == 1, { "a cancelled start delivered again" })
        expectThrows(IllegalStateException::class.java) { task.execute() }
    }

    check("explicit cancellation disposes the late result and never calls the legacy callback") {
        val task = BlockingRunnerTask("fallback")
        val legacy = AtomicInteger(0)
        task.setCallback { legacy.incrementAndGet() }
        task.execute()
        expect(task.started.await(5, TimeUnit.SECONDS), { "work never started" })
        task.cancel()
        task.release.countDown()
        expect(task.discardedOnce.await(5, TimeUnit.SECONDS), { "the late value was never disposed" })
        drainMainThreadDeliveries()
        expect(legacy.get() == 0, { "a cancelled task delivered a legacy result" })
        expect(task.discarded.get() == 1, { "unexpected disposal count ${task.discarded.get()}" })
    }

    check("session revocation cancels in-flight work and disposes its result") {
        val task = BlockingRunnerTask("fallback")
        val delivered = ArrayList<TaskOutcome<String>>()
        task.setOutcomeCallback { delivered.add(it) }
        task.execute()
        expect(task.started.await(5, TimeUnit.SECONDS), { "work never started" })
        VaultSession.lock() // advances the generation; the captured one is stale now
        task.release.countDown()
        expect(task.discardedOnce.await(5, TimeUnit.SECONDS), { "the produced value was never disposed" })
        drainMainThreadDeliveries()
        expect(delivered.size == 1 && delivered[0] is TaskOutcome.Cancelled, { "revocation did not deliver cancellation" })
        expect((delivered[0] as TaskOutcome.Cancelled<*>).reason == TaskCancellation.SESSION_REVOKED, { "wrong reason" })
        expect(task.cleanupRuns.get() == 1, { "revocation cleanup ran ${task.cleanupRuns.get()} times" })
    }

    check("a task captured before revocation is cancelled before its work begins") {
        val task = BlockingRunnerTask("fallback")
        VaultSession.lock() // stale generation before start
        val delivered = ArrayList<TaskOutcome<String>>()
        task.setOutcomeCallback { delivered.add(it) }
        task.execute()
        drainMainThreadDeliveries()
        expect(task.cleanupRuns.get() == 1, { "cancelled start did not run cleanup" })
        expect(delivered.size == 1 && delivered[0] is TaskOutcome.Cancelled, { "stale start was not cancelled" })
        expect((delivered[0] as TaskOutcome.Cancelled<*>).reason == TaskCancellation.SESSION_REVOKED, { "wrong reason" })
        expect(task.discarded.get() == 1, { "the fallback was not disposed on the cancelled start" })
    }

    check("converted classes keep the Java-visible API shapes") {
        expectPublicFieldValue(BoundedInputReader::class.java, "MAX_DOCUMENT_BYTES", 16 * 1024 * 1024, INT_TYPE, true)
        expectMethod(BoundedInputReader::class.java, "static byte[] read(InputStream,int)")
        val read = BoundedInputReader::class.java.declaredMethods.first { it.name == "read" }
        expect(read.exceptionTypes.contains(IOException::class.java), { "read() no longer declares IOException" })

        expectPublicFieldValue(EncryptedBackupHeader::class.java, "MIN_BACKUP_BYTES", 44, INT_TYPE, true)
        expectPublicFieldValue(EncryptedBackupHeader::class.java, "MAX_ITERATIONS", 1000000, INT_TYPE, true)
        expectMethod(EncryptedBackupHeader::class.java, "static EncryptedBackupHeader parse(byte[])")
        expectMethod(EncryptedBackupHeader::class.java, "int getIterations()")
        expectMethod(EncryptedBackupHeader::class.java, "byte[] getSalt()")
        expectMethod(EncryptedBackupHeader::class.java, "byte[] getEncrypted()")
        val constructors = EncryptedBackupHeader::class.java.declaredConstructors
        expect(constructors.filter { !it.isSynthetic }.all { Modifier.isPrivate(it.modifiers) },
                { "header constructor is public: ${constructors.filter { !it.isSynthetic }.map { it.toGenericString() }}" })

        expectPublicFieldValue(TokenCalculator::class.java, "TOTP_DEFAULT_PERIOD", 30, INT_TYPE, true)
        expectPublicFieldValue(TokenCalculator::class.java, "TOTP_DEFAULT_DIGITS", 6, INT_TYPE, true)
        expectPublicFieldValue(TokenCalculator::class.java, "HOTP_INITIAL_COUNTER", 1, INT_TYPE, true)
        expectPublicFieldValue(TokenCalculator::class.java, "STEAM_DEFAULT_DIGITS", 5, INT_TYPE, true)
        expectField(TokenCalculator::class.java, "DEFAULT_ALGORITHM",
                TokenCalculator.HashAlgorithm::class.java, true)
        expect(!publicMethods(TokenCalculator::class.java).any { it.contains("getDEFAULT_ALGORITHM") },
                { "DEFAULT_ALGORITHM is no longer a field" })
        expectMethod(TokenCalculator::class.java, "static int TOTP_RFC6238(byte[],int,long,int,HashAlgorithm)")
        expectMethod(TokenCalculator::class.java, "static int TOTP_RFC6238(byte[],int,long,int,HashAlgorithm,int)")
        expectMethod(TokenCalculator::class.java, "static String TOTP_RFC6238(byte[],int,int,HashAlgorithm,int)")
        expectMethod(TokenCalculator::class.java, "static String TOTP_Steam(byte[],int,int,HashAlgorithm,int)")
        expectMethod(TokenCalculator::class.java, "static String HOTP(byte[],long,int,HashAlgorithm)")
        expectMethod(TokenCalculator::class.java, "static String MOTP(String,String,long,int)")
        expect(TokenCalculator.HashAlgorithm::class.java.isEnum, { "HashAlgorithm is not an enum" })
        expect(TokenCalculator.HashAlgorithm.values().map { it.name } == listOf("SHA1", "SHA256", "SHA512"),
                { "HashAlgorithm constants changed" })
        expect(TokenCalculator.HashAlgorithm.valueOf("SHA1") == TokenCalculator.DEFAULT_ALGORITHM, { "default algorithm" })

        expectMethod(BoundedOutput::class.java, "void write(int)")
        expectMethod(BoundedOutput::class.java, "void write(byte[],int,int)")
        expectMethod(BoundedOutput::class.java, "boolean overflowed()")
        val boundedWrites = BoundedOutput::class.java.declaredMethods.filter { it.name == "write" || it.name == "overflowed" }
        expect(boundedWrites.all { Modifier.isSynchronized(it.modifiers) },
                { "BoundedOutput lost its monitor: ${boundedWrites.map { methodSignature(it) }}" })
        expect(BoundedOutput::class.java.declaredConstructors.any { it.parameterTypes.contentEquals(arrayOf<Class<*>>(INT_TYPE)) },
                { "missing BoundedOutput(int)" })

        val wrapper = SecretKeyWrapper::class.java
        expect(Modifier.isPublic(wrapper.modifiers), { "SecretKeyWrapper is not public" })
        expect(!Modifier.isFinal(wrapper.modifiers), { "SecretKeyWrapper must stay extendable like the Java class" })
        val wrapperConstructor = wrapper.declaredConstructors.single { !it.isSynthetic }
        expect(Modifier.isPublic(wrapperConstructor.modifiers), { "SecretKeyWrapper constructor is not public" })
        expect(wrapperConstructor.parameterTypes.contentEquals(arrayOf<Class<*>>(java.security.KeyPair::class.java)),
                { "SecretKeyWrapper constructor parameter changed" })
        expect(wrapperConstructor.exceptionTypes.contains(GeneralSecurityException::class.java)
                && wrapperConstructor.exceptionTypes.contains(java.io.IOException::class.java),
                { "SecretKeyWrapper constructor lost its checked exceptions" })
        expectMethod(wrapper, "byte[] wrap(SecretKey)")
        expectMethod(wrapper, "SecretKey unwrap(byte[])")
        for (name in listOf("wrap", "unwrap")) {
            val method = wrapper.declaredMethods.first { it.name == name }
            expect(method.exceptionTypes.contains(GeneralSecurityException::class.java),
                    { "SecretKeyWrapper.$name lost its checked exception" })
        }

        val listener = SimpleDoubleClickListener::class.java
        expect(Modifier.isAbstract(listener.modifiers), { "SimpleDoubleClickListener is not abstract" })
        expect(View.OnClickListener::class.java.isAssignableFrom(listener),
                { "SimpleDoubleClickListener no longer implements View.OnClickListener" })
        expect(listener.declaredConstructors.any { Modifier.isPublic(it.modifiers) && it.parameterCount == 0 },
                { "SimpleDoubleClickListener lost its public no-argument constructor" })
        expectMethod(listener, "void onSingleClick(View)")
        expectMethod(listener, "void onDoubleClick(View)")
        expectMethod(listener, "void onClick(View)")
        expect(listener.declaredMethods.filter { Modifier.isPublic(it.modifiers) && it.name.startsWith("on") }
                .all { !Modifier.isStatic(it.modifiers) },
                { "public click callbacks must stay instance methods" })
        expect(listener.declaredMethods.filter { it.name == "onSingleClick" || it.name == "onDoubleClick" }
                .all { Modifier.isAbstract(it.modifiers) },
                { "click callbacks must stay abstract for Java subclasses" })
        // Kotlin's SAM conversion of the posted Runnable adds a private static lambda helper, like
        // the lambdas already used by the Java sources; it must stay private.
        expect(listener.declaredMethods.filter { it.name.contains("\$") }.all { Modifier.isPrivate(it.modifiers) },
                { "synthetic lambda helpers must stay private" })

        val dimensions = DimensionConverter::class.java
        expectMethod(dimensions, "static int stringToDimensionPixelSize(String,DisplayMetrics)")
        expectMethod(dimensions, "static float stringToDimension(String,DisplayMetrics)")
        expectPublicFieldValue(dimensions, "dimensionConstantLookup", DimensionConverter.dimensionConstantLookup,
                Map::class.java, true)
        // Converting a static-only utility to a Kotlin object removes the public constructor and
        // makes the class final. That is the deliberate, documented delta shared by this conversion
        // and the earlier Tools/InputPolicy ones; every call site uses only the static members.
        for (utility in listOf(dimensions, InputPolicy::class.java, TokenCalculator::class.java)) {
            expect(utility.declaredConstructors.all { Modifier.isPrivate(it.modifiers) },
                    { "${utility.simpleName} should not be instantiable after conversion" })
            expect(Modifier.isFinal(utility.modifiers), { "${utility.simpleName} object is not final" })
            expect(utility.declaredFields.any { it.name == "INSTANCE" },
                    { "${utility.simpleName} object is missing INSTANCE" })
        }

        expectMethod(OwnedSecret::class.java, "static OwnedSecret copyOf(byte[])")
        expectMethod(SessionController::class.java, "SessionState snapshot()")
        expectMethod(SessionController::class.java, "boolean isCurrent(long)")
        expectMethod(SessionController::class.java, "SessionCommandResult activate(SecretKey,long)")
        expectMethod(TaskController::class.java, "TaskStart start()")
        expectMethod(AuthenticationOutcome::class.java, "static Verified verified(byte[],long)")

        expectMethod(EditorActionHelper::class.java, "static boolean isActionDoneOrKeyboardEnter(int,KeyEvent)")
        expectMethod(EditorActionHelper::class.java, "static boolean isActionUpKeyboardEnter(KeyEvent)")

        expect(ScreenTimerController.Scheduler::class.java.isAssignableFrom(HandlerTimerScheduler::class.java),
                { "HandlerTimerScheduler no longer implements Scheduler" })
        expectMethod(HandlerTimerScheduler::class.java, "void postDelayed(Runnable,long)")
        expectMethod(HandlerTimerScheduler::class.java, "void remove(Runnable)")
        expectMethod(HandlerTimerScheduler::class.java, "long elapsedRealtime()")

        expectPublicFieldValue(InputPolicy::class.java, "MAX_URI_CHARS", 16384, INT_TYPE, true)
        expectPublicFieldValue(InputPolicy::class.java, "MAX_ENTRIES", 2000, INT_TYPE, true)
        expectPublicFieldValue(InputPolicy::class.java, "MAX_JSON_DEPTH", 32, INT_TYPE, true)
        expectPublicFieldValue(InputPolicy::class.java, "MAX_TEXT_CHARS", 1024, INT_TYPE, true)
        expectPublicFieldValue(InputPolicy::class.java, "MAX_TAGS", 32, INT_TYPE, true)
        expectPublicFieldValue(InputPolicy::class.java, "MAX_TAG_CHARS", 128, INT_TYPE, true)
        expectPublicFieldValue(InputPolicy::class.java, "MAX_SECRET_BYTES", 2048, INT_TYPE, true)
        expectPublicFieldValue(InputPolicy::class.java, "MAX_PERIOD_SECONDS", 86400, INT_TYPE, true)
        expectPublicFieldValue(InputPolicy::class.java, "MAX_IMAGE_DIMENSION", 8192, INT_TYPE, true)
        expectPublicFieldValue(InputPolicy::class.java, "MAX_IMAGE_PIXELS", 4_000_000L, LONG_TYPE, true)
        expectMethod(InputPolicy::class.java, "static void text(String,int)")
        expectMethod(InputPolicy::class.java, "static void jsonDepth(String)")
        expectMethod(InputPolicy::class.java, "static boolean validImage(int,int)")
        expectMethod(InputPolicy::class.java, "static void tokenParameters(int,int,long)")
    }

    check("the drag/drop trio and dialog/task helpers keep the Java-visible API shapes") {
        val moveAdapter = ItemTouchHelperAdapter::class.java
        expect(moveAdapter.isInterface, { "ItemTouchHelperAdapter is no longer an interface" })
        expectMethod(moveAdapter, "boolean onItemMove(int,int)")

        val selectHolder = ItemTouchHelperViewHolder::class.java
        expect(selectHolder.isInterface, { "ItemTouchHelperViewHolder is no longer an interface" })
        expectMethod(selectHolder, "void onItemSelected()")
        expectMethod(selectHolder, "void onItemClear()")

        val callback = SimpleItemTouchHelperCallback::class.java
        expect(!Modifier.isFinal(callback.modifiers), { "SimpleItemTouchHelperCallback must stay extendable" })
        expect(ItemTouchHelper.Callback::class.java.isAssignableFrom(callback), { "callback lost its super class" })
        expectPublicFieldValue(callback, "ALPHA_FULL", 1.0f, FLOAT_TYPE, true)
        expectMethod(callback, "void setDragEnabled(boolean)")
        expectMethod(callback, "boolean isLongPressDragEnabled()")
        expectMethod(callback, "boolean isItemViewSwipeEnabled()")
        val callbackConstructors = callback.declaredConstructors.filter { !it.isSynthetic }
        expect(callbackConstructors.size == 1 &&
                callbackConstructors[0].parameterTypes.contentEquals(arrayOf<Class<*>>(ItemTouchHelperAdapter::class.java)),
                { "callback constructor parameter changed" })

        // Static-only utilities became objects: the same deliberate, documented delta as the
        // earlier Tools/InputPolicy/DimensionConverter conversions.
        for (utility in listOf(UIHelper::class.java, ConfirmedPasswordTransformationHelper::class.java)) {
            expect(Modifier.isFinal(utility.modifiers), { "${utility.simpleName} object is not final" })
            expect(utility.declaredFields.any { it.name == "INSTANCE" },
                    { "${utility.simpleName} object is missing INSTANCE" })
            expect(utility.declaredConstructors.all { Modifier.isPrivate(it.modifiers) },
                    { "${utility.simpleName} should not be instantiable after conversion" })
        }
        expectMethod(UIHelper::class.java, "static void showGenericDialog(Context,int,int)")
        expectMethod(UIHelper::class.java, "static void showGenericDialog(Context,int,int,Runnable)")
        expectMethod(UIHelper::class.java, "static void showKeyboard(Context,View)")
        expectMethod(UIHelper::class.java, "static void showKeyboard(Context,View,Boolean)")
        expectMethod(UIHelper::class.java, "static void hideKeyboard(Context,View)")
        expectMethod(ConfirmedPasswordTransformationHelper::class.java,
                "static void setup(TextInputLayout,TextInputEditText,EditText)")

        val result = BackupTaskResult::class.java
        expect(!Modifier.isFinal(result.modifiers), { "BackupTaskResult must stay extendable" })
        expectField(result, "success", BOOLEAN_TYPE, false)
        expectField(result, "payload", String::class.java, false)
        expectField(result, "messageId", INT_TYPE, false)
        expectField(result, "resultType", BackupTaskResult.ResultType::class.java, false)
        val isPgp = field(result, "isPGP") ?: throw AssertionError("Missing field BackupTaskResult.isPGP")
        expect(isPgp.type == BOOLEAN_TYPE && !Modifier.isFinal(isPgp.modifiers),
                { "isPGP must stay a writable public field" })
        expectField(result, "decryptIntent", Intent::class.java, false)
        expectField(result, "uri", Uri::class.java, false)
        expectMethod(result, "static BackupTaskResult success(ResultType,String)")
        expectMethod(result, "static BackupTaskResult failure(ResultType,int)")
        expect(result.declaredConstructors.any { !it.isSynthetic && it.parameterCount == 7 },
                { "missing the seven-argument constructor" })
        expect(result.declaredConstructors.any { !it.isSynthetic && it.parameterCount == 4 },
                { "missing the four-argument constructor" })
        expect(BackupTaskResult.ResultType::class.java.isEnum, { "ResultType is not an enum" })
    }

    check("the background runner keeps its Java-visible API shapes") {
        val runner = UiBasedBackgroundTask::class.java
        expect(!Modifier.isFinal(runner.modifiers), { "the runner must stay extendable by task subclasses" })
        val constructors = runner.declaredConstructors.filter { !it.isSynthetic }
        expect(constructors.size == 1 &&
                constructors[0].parameterTypes.contentEquals(arrayOf<Class<*>>(Object::class.java)),
                { "the runner constructor changed: ${constructors.map { it.toGenericString() }}" })
        expectMethod(runner, "void setCallback(UiCallback)")
        expectMethod(runner, "void setOutcomeCallback(OutcomeCallback)")
        expectMethod(runner, "TaskState getState()")
        expectMethod(runner, "boolean isCanceled()")
        // The Java method was overridable and EncryptedBackupTask overrides it; a final isCanceled
        // would not even compile against the (Java or Kotlin) subclass.
        expect(!Modifier.isFinal(runner.declaredMethods.first { it.name == "isCanceled" }.modifiers),
                { "isCanceled() must stay overridable for broadcast-mode tasks" })
        expectMethod(runner, "void cancel()")
        expectMethod(runner, "void execute()")
        expectMethod(runner, "void disposeOutcome(TaskOutcome)")

        val background = runner.declaredMethods.first { it.name == "doInBackground" }
        expect(Modifier.isProtected(background.modifiers) && Modifier.isAbstract(background.modifiers),
                { "doInBackground must stay protected and abstract" })
        expect(background.exceptionTypes.contains(Exception::class.java), { "doInBackground lost its throws clause" })
        for (overridable in listOf("onCancellation", "discardResult")) {
            val method = runner.declaredMethods.first { it.name == overridable }
            expect(Modifier.isProtected(method.modifiers) && !Modifier.isFinal(method.modifiers) &&
                    !Modifier.isStatic(method.modifiers),
                    { "$overridable must stay protected and overridable" })
        }
        for (helper in listOf("wasCanceled", "taskGeneration")) {
            val method = runner.declaredMethods.first { it.name == helper }
            expect(Modifier.isProtected(method.modifiers) && Modifier.isFinal(method.modifiers),
                    { "$helper must stay protected final" })
        }

        val nested = runner.declaredClasses.filter { it.isInterface }.map { it.simpleName }.sorted()
        expect(nested == listOf("OutcomeCallback", "UiCallback"), { "nested interfaces changed: $nested" })
        val outcomeCallback = runner.declaredClasses.first { it.simpleName == "OutcomeCallback" }
        expect(outcomeCallback.declaredMethods.single { !it.isSynthetic && Modifier.isAbstract(it.modifiers) }.name == "onOutcome",
                { "OutcomeCallback abstract method changed" })
        val uiCallback = runner.declaredClasses.first { it.simpleName == "UiCallback" }
        expect(uiCallback.declaredMethods.single { !it.isSynthetic && Modifier.isAbstract(it.modifiers) }.name == "onResult",
                { "UiCallback abstract method changed" })

        // The runner reads the live session through the real VaultSession facade.
        expectField(VaultSession::class.java, "MutationLock", Any::class.java, true)
        expectMethod(VaultSession::class.java, "static long generation()")
        expectMethod(VaultSession::class.java, "static void addChangeListener(Runnable)")
        expectMethod(VaultSession::class.java, "static void removeChangeListener(Runnable)")
    }

    check("converted task subclasses keep their Java-visible API shapes") {
        /*
         * Reflection can only see signatures whose referenced types are on this classpath: the
         * Java resolution sources (Settings, EncryptionHelper, Constants, Entry, ...) are read by
         * kotlinc but produce no class files. Members mentioning them (getBackupType's
         * Constants.BackupType, the credential tasks' constructors) are covered by the Python
         * source guards instead. Generic erasure is why EncryptedBackupTask's constructors are
         * still checkable: ArrayList<Entry> erases to ArrayList.
         */
        val runner = UiBasedBackgroundTask::class.java
        // ChangeEncryptionTask implements the Java-only EncryptionHelper.EncryptionChangeCallback;
        // merely defining its Class object resolves that interface, which has no class file here.
        // It is covered by the Python source guards instead.
        for (task in listOf(AuthenticationTask::class.java, GenericBackupTask::class.java,
                GenericRestoreTask::class.java, PGPBackupTask::class.java, PGPRestoreTask::class.java,
                EncryptedBackupTask::class.java, EncryptedRestoreTask::class.java,
                ChangeCredentialsTask::class.java, FinishIntroTask::class.java)) {
            expect(Modifier.isPublic(task.modifiers), { "${task.simpleName} is not public" })
            expect(!Modifier.isFinal(task.modifiers),
                    { "${task.simpleName} must stay extendable like the Java class" })
            expect(runner.isAssignableFrom(task), { "${task.simpleName} no longer extends the runner" })
        }
        expect(Modifier.isAbstract(GenericBackupTask::class.java.modifiers), { "GenericBackupTask must stay abstract" })
        expect(Modifier.isAbstract(GenericRestoreTask::class.java.modifiers), { "GenericRestoreTask must stay abstract" })

        fun constructorParameters(type: Class<*>, parameterCount: Int): List<Class<*>> {
            val constructor = type.declaredConstructors.single { !it.isSynthetic && it.parameterCount == parameterCount }
            return constructor.parameterTypes.toList()
        }

        // The backup bases keep the (Context, Uri) constructor the concrete tasks delegate to.
        for (base in listOf(GenericBackupTask::class.java, GenericRestoreTask::class.java)) {
            expect(constructorParameters(base, 2) == listOf(Context::class.java, Uri::class.java),
                    { "${base.simpleName} constructor changed" })
        }

        // EncryptedBackupTask: both Java constructors survive (erasure drops the Entry element type).
        expect(constructorParameters(EncryptedBackupTask::class.java, 4) ==
                listOf(Context::class.java, java.util.ArrayList::class.java, String::class.java, Uri::class.java),
                { "EncryptedBackupTask(Context,ArrayList,String,Uri) changed" })
        expect(constructorParameters(EncryptedBackupTask::class.java, 6) ==
                listOf(Context::class.java, java.util.ArrayList::class.java, String::class.java, Uri::class.java,
                        EncryptedBackupTask.Mode::class.java, java.util.function.BooleanSupplier::class.java),
                { "EncryptedBackupTask(Context,ArrayList,String,Uri,Mode,BooleanSupplier) changed" })
        expect(EncryptedBackupTask.Mode::class.java.isEnum, { "Mode is not an enum" })
        expect(EncryptedBackupTask.Mode.values().map { it.name } == listOf("USER", "AUTO_EDIT", "BROADCAST"),
                { "Mode constants changed" })

        // Tasks whose signatures reference only compiled types keep their full member shapes.
        expect(constructorParameters(AuthenticationTask::class.java, 4) ==
                listOf(Context::class.java, BOOLEAN_TYPE, String::class.java, String::class.java),
                { "AuthenticationTask(Context,boolean,String,String) changed" })
        expect(constructorParameters(EncryptedRestoreTask::class.java, 4) ==
                listOf(Context::class.java, Uri::class.java, String::class.java, BOOLEAN_TYPE),
                { "EncryptedRestoreTask(Context,Uri,String,boolean) changed" })
        expect(constructorParameters(PGPRestoreTask::class.java, 3) ==
                listOf(Context::class.java, Uri::class.java, Intent::class.java),
                { "PGPRestoreTask(Context,Uri,Intent) changed" })
        expect(constructorParameters(PGPBackupTask::class.java, 3) ==
                listOf(Context::class.java, String::class.java, Uri::class.java),
                { "PGPBackupTask(Context,String,Uri) changed" })

        fun expectProtected(type: Class<*>, name: String, returnType: Class<*>) {
            // Generic overrides also produce public volatile bridge methods; assert the real one.
            val method = type.declaredMethods.first { it.name == name && !it.isBridge && !it.isSynthetic }
            expect(Modifier.isProtected(method.modifiers) && !Modifier.isStatic(method.modifiers),
                    { "${type.simpleName}.$name must stay a protected instance method" })
            expect(method.returnType == returnType, { "${type.simpleName}.$name return type changed" })
            expect(!Modifier.isFinal(method.modifiers), { "${type.simpleName}.$name must stay overridable" })
        }
        expectProtected(AuthenticationTask::class.java, "doInBackground", AuthenticationOutcome::class.java)
        expectProtected(AuthenticationTask::class.java, "discardResult", Void.TYPE)
        expectProtected(EncryptedRestoreTask::class.java, "doInBackground", BackupTaskResult::class.java)
        expectProtected(PGPRestoreTask::class.java, "doInBackground", BackupTaskResult::class.java)

        // The intro result keeps both Java constructors and its public final fields.
        val introResult = FinishIntroTask.Result::class.java
        expect(constructorParameters(introResult, 2) == listOf(BOOLEAN_TYPE, ByteArray::class.java),
                { "FinishIntroTask.Result(boolean,byte[]) changed" })
        expect(constructorParameters(introResult, 3) ==
                listOf(BOOLEAN_TYPE, ByteArray::class.java, LONG_TYPE),
                { "FinishIntroTask.Result(boolean,byte[],long) changed" })
        for ((name, fieldType) in listOf("saveSuccessful" to BOOLEAN_TYPE,
                "encryptionKey" to ByteArray::class.java, "generation" to LONG_TYPE)) {
            val found = introResult.declaredFields.first { it.name == name }
            expect(Modifier.isPublic(found.modifiers) && Modifier.isFinal(found.modifiers),
                    { "FinishIntroTask.Result.$name must stay a public final field" })
            expect(found.type == fieldType, { "FinishIntroTask.Result.$name type changed" })
        }
    }

    if (failures == 0) {
        println("\nAll sandbox checks passed.")
    } else {
        println("\n$failures sandbox check(s) failed.")
        kotlin.system.exitProcess(1)
    }
}
