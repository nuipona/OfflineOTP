// Non-authoritative sandbox checks; see scripts/kotlin-sandbox/README.md before trusting a result.
// Runs the converted utility classes on a desktop JVM against hand-written platform stubs and
// mirrors the repository's own tests for them. It is not the Gradle unit/lint/instrumentation
// gate, and no Android behaviour (Handler, KeyEvent, resources) is exercised here.
import org.shadowice.flocke.andotp.Tasks.AuthenticationError
import org.shadowice.flocke.andotp.Tasks.AuthenticationOutcome
import org.shadowice.flocke.andotp.Tasks.TaskCancellation
import org.shadowice.flocke.andotp.Tasks.TaskCompletion
import org.shadowice.flocke.andotp.Tasks.TaskController
import org.shadowice.flocke.andotp.Tasks.TaskFailure
import org.shadowice.flocke.andotp.Tasks.TaskOutcome
import org.shadowice.flocke.andotp.Tasks.TaskStart
import org.shadowice.flocke.andotp.Tasks.TaskState
import org.shadowice.flocke.andotp.Utilities.BoundedInputReader
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
import org.shadowice.flocke.andotp.Utilities.ScreenTimerController
import org.shadowice.flocke.andotp.Utilities.TokenCalculator
import org.shadowice.flocke.andotp.Utilities.Tools
import org.shadowice.flocke.andotp.View.SimpleDoubleClickListener

import android.util.DisplayMetrics
import android.view.View

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

    if (failures == 0) {
        println("\nAll sandbox checks passed.")
    } else {
        println("\n$failures sandbox check(s) failed.")
        kotlin.system.exitProcess(1)
    }
}
