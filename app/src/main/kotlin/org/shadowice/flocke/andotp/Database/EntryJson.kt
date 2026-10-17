package org.shadowice.flocke.andotp.Database

import org.apache.commons.codec.binary.Base32
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.shadowice.flocke.andotp.Utilities.InputPolicy
import org.shadowice.flocke.andotp.Utilities.OtpHashAlgorithm
import org.shadowice.flocke.andotp.Utilities.OtpKind
import org.shadowice.flocke.andotp.Utilities.TokenCalculator

import java.util.Locale

/**
 * Android-free entry document boundary: the JSON object one entry is stored as in the vault and in
 * backups. [parse] reads an untrusted object, [serialize] writes the same format; both are the only
 * place the field names, their order and the format defaults are defined.
 *
 * Expected malformed input produces a [EntryJsonResult.Failure], not an exception. The wire format
 * is byte-for-byte the one the Java `Entry` parser and serializer used, including the field order
 * the stored fixtures and the JSON round-trip test depend on.
 */
object EntryJson {
    private const val JSON_SECRET = "secret"
    private const val JSON_ISSUER = "issuer"
    private const val JSON_LABEL = "label"
    private const val JSON_PERIOD = "period"
    private const val JSON_COUNTER = "counter"
    private const val JSON_DIGITS = "digits"
    private const val JSON_TYPE = "type"
    private const val JSON_ALGORITHM = "algorithm"
    private const val JSON_TAGS = "tags"
    private const val JSON_THUMBNAIL = "thumbnail"
    private const val JSON_LAST_USED = "last_used"
    private const val JSON_USED_FREQUENCY = "used_frequency"
    private const val JSON_PIN = "pin"

    // Older documents predate these fields; the Java parser defaulted them the same way.
    private const val DEFAULT_PERIOD = TokenCalculator.TOTP_DEFAULT_PERIOD
    private val INTEGER_TEXT = Regex("-?[0-9]+")
    private val BASE32_TEXT = Regex("[A-Z2-7]+={0,6}")

    /** Validates one untrusted entry object. The returned value owns its own seed copy. */
    @JvmStatic
    fun parse(json: JSONObject): EntryJsonResult {
        return try {
            EntryJsonResult.Success(parsed(json))
        } catch (invalid: Rejected) {
            EntryJsonResult.Failure(invalid.error)
        }
    }

    private fun parsed(json: JSONObject): ValidatedEntry {
        if (!json.has(JSON_SECRET)) reject(EntryJsonError.MISSING_SECRET)
        val seed = decodeBase32Secret(text(json, JSON_SECRET)) ?: reject(EntryJsonError.INVALID_SECRET)
        try {
            // Field order of the checks follows the previous Java constructor.
            val label = text(json, JSON_LABEL)
            // Older backups did not store the issuer separately.
            val issuer = if (json.has(JSON_ISSUER)) text(json, JSON_ISSUER) else ""
            val kind = if (json.has(JSON_TYPE)) kind(json) else OtpKind.TOTP
            val period = if (json.has(JSON_PERIOD)) int(json, JSON_PERIOD) else DEFAULT_PERIOD
            if (kind == OtpKind.HOTP && !json.has(JSON_COUNTER)) reject(EntryJsonError.MISSING_COUNTER)
            val counter = if (json.has(JSON_COUNTER)) number(json, JSON_COUNTER) else 0L
            val digits = if (json.has(JSON_DIGITS)) int(json, JSON_DIGITS)
                else if (kind == OtpKind.STEAM) TokenCalculator.STEAM_DEFAULT_DIGITS
                else TokenCalculator.TOTP_DEFAULT_DIGITS
            val algorithm = if (json.has(JSON_ALGORITHM)) algorithm(json) else OtpHashAlgorithm.SHA1
            val tags = tags(json)
            val thumbnailName = thumbnailName(json)
            val lastUsed = usage(json, JSON_LAST_USED)
            val usedFrequency = usage(json, JSON_USED_FREQUENCY)
            // The mOTP PIN was never stored before, so a document written by an older version (and
            // every non-mOTP document) simply has no such key and reads back as an empty PIN.
            val pin = if (json.has(JSON_PIN)) text(json, JSON_PIN) else ""

            // The bounds the Java model ran in validate() after reading every field, in its order.
            if (seed.isEmpty() || seed.size > InputPolicy.MAX_SECRET_BYTES) reject(EntryJsonError.INVALID_SECRET)
            try {
                InputPolicy.tokenParameters(period, digits, counter)
            } catch (invalid: IllegalArgumentException) {
                reject(EntryJsonError.INVALID_PARAMETERS)
            }
            if (kind == OtpKind.STEAM && digits != TokenCalculator.STEAM_DEFAULT_DIGITS)
                reject(EntryJsonError.INVALID_PARAMETERS)
            bounded(issuer, InputPolicy.MAX_TEXT_CHARS)
            bounded(label, InputPolicy.MAX_TEXT_CHARS)
            bounded(pin, InputPolicy.MAX_TAG_CHARS)
            if (tags.size > InputPolicy.MAX_TAGS) reject(EntryJsonError.TOO_MANY_TAGS)
            for (tag in tags) bounded(tag, InputPolicy.MAX_TAG_CHARS)

            return ValidatedEntry.create(kind, seed, period, digits, counter, issuer, label, algorithm,
                thumbnailName, lastUsed, usedFrequency, tags, pin)
        } catch (invalid: Rejected) {
            seed.fill(0)
            throw invalid
        } catch (invalid: IllegalArgumentException) {
            // Defensive only: the value re-checks the bounds reported above; nothing else in this
            // block throws an IllegalArgumentException.
            seed.fill(0)
            throw Rejected(EntryJsonError.INVALID_PARAMETERS)
        }
    }

    /**
     * Writes one entry in the stored format. The field order is part of that format: the vault
     * fixtures and the JSON round-trip test compare the serialized text.
     */
    @JvmStatic
    @Throws(JSONException::class)
    fun serialize(kind: OtpKind, secret: ByteArray, issuer: String, label: String, digits: Int,
                  period: Int, counter: Long, algorithm: OtpHashAlgorithm, thumbnailName: String,
                  lastUsed: Long, usedFrequency: Long, tags: List<String>, pin: String): JSONObject {
        val jsonObj = JSONObject()
        jsonObj.put(JSON_SECRET, String(Base32().encode(secret)))
        jsonObj.put(JSON_ISSUER, issuer)
        jsonObj.put(JSON_LABEL, label)
        jsonObj.put(JSON_DIGITS, digits)
        jsonObj.put(JSON_TYPE, kind.name)
        jsonObj.put(JSON_ALGORITHM, algorithm.name)
        jsonObj.put(JSON_THUMBNAIL, thumbnailName)
        jsonObj.put(JSON_LAST_USED, lastUsed)
        jsonObj.put(JSON_USED_FREQUENCY, usedFrequency)

        if (kind == OtpKind.TOTP)
            jsonObj.put(JSON_PERIOD, period)
        else if (kind == OtpKind.STEAM && period != DEFAULT_PERIOD)
            // Only a non-default Steam period is written, so default Steam documents stay byte-identical.
            jsonObj.put(JSON_PERIOD, period)
        else if (kind == OtpKind.HOTP)
            jsonObj.put(JSON_COUNTER, counter)

        // Same rule for the mOTP PIN: only an mOTP entry that actually has one carries the field,
        // so every other document - and every mOTP document written before the field existed -
        // stays byte-for-byte what the Java serializer produced.
        if (kind == OtpKind.MOTP && pin.isNotEmpty())
            jsonObj.put(JSON_PIN, pin)

        val tagsArray = JSONArray()
        for (tag in tags) {
            tagsArray.put(tag)
        }
        jsonObj.put(JSON_TAGS, tagsArray)

        return jsonObj
    }

    /**
     * Decodes the canonical padded/unpadded Base32 text a seed is stored as, or returns null when
     * the text is not a usable seed. Shared with [Entry]'s manual-entry path and its secret check.
     */
    @JvmStatic
    fun decodeBase32Secret(encoded: String?): ByteArray? {
        if (encoded == null || encoded.length > InputPolicy.MAX_SECRET_BYTES * 2) return null
        val normalized = encoded.uppercase(Locale.ROOT)
        if (!BASE32_TEXT.matches(normalized)) return null
        val codec = Base32()
        val bytes = codec.decode(normalized)
        val canonical = codec.encodeAsString(bytes)
        if (!(normalized == canonical || normalized == canonical.replace("=", ""))) return null
        return bytes
    }

    /** A required text field: missing keys, JSON null and non-string values all reject. */
    private fun text(json: JSONObject, name: String): String {
        val value = try {
            json.get(name)
        } catch (missing: JSONException) {
            reject(EntryJsonError.INVALID_TEXT)
        }
        return value as? String ?: reject(EntryJsonError.INVALID_TEXT)
    }

    /** A numeric field in the Java parser's shape: digits only, no coercion from floats. */
    private fun number(json: JSONObject, name: String): Long {
        val value = try {
            json.get(name)
        } catch (missing: JSONException) {
            reject(EntryJsonError.INVALID_NUMBER)
        }
        val text = value.toString()
        if (!INTEGER_TEXT.matches(text)) reject(EntryJsonError.INVALID_NUMBER)
        return text.toLongOrNull() ?: reject(EntryJsonError.INVALID_NUMBER)
    }

    /** A numeric field that must fit an int, as `Math.toIntExact` required. */
    private fun int(json: JSONObject, name: String): Int {
        val value = number(json, name)
        if (value < Int.MIN_VALUE || value > Int.MAX_VALUE) reject(EntryJsonError.INVALID_NUMBER)
        return value.toInt()
    }

    private fun kind(json: JSONObject): OtpKind {
        return when (val name = text(json, JSON_TYPE)) {
            "TOTP" -> OtpKind.TOTP
            "HOTP" -> OtpKind.HOTP
            "MOTP" -> OtpKind.MOTP
            "STEAM" -> OtpKind.STEAM
            else -> reject(EntryJsonError.UNSUPPORTED_TYPE)
        }
    }

    private fun algorithm(json: JSONObject): OtpHashAlgorithm {
        val name = text(json, JSON_ALGORITHM)
        return OtpHashAlgorithm.entries.firstOrNull { it.name == name } ?: reject(EntryJsonError.INVALID_ALGORITHM)
    }

    private fun tags(json: JSONObject): List<String> {
        if (!json.has(JSON_TAGS)) return emptyList()
        val array = try {
            json.getJSONArray(JSON_TAGS)
        } catch (invalid: JSONException) {
            reject(EntryJsonError.INVALID_TAGS)
        }
        if (array.length() > InputPolicy.MAX_TAGS) reject(EntryJsonError.TOO_MANY_TAGS)
        val tags = ArrayList<String>(array.length())
        for (index in 0 until array.length()) {
            val tag = try {
                array.get(index)
            } catch (invalid: JSONException) {
                reject(EntryJsonError.INVALID_TAGS)
            }
            if (tag !is String) reject(EntryJsonError.INVALID_TAGS)
            tags.add(tag)
        }
        return tags
    }

    /**
     * Best-effort display metadata: a missing, non-text or oversized name falls back to the default
     * icon, exactly as the Java parser's value lookup catch did. No thumbnail name is longer than
     * the entry text bound, so an oversized name can only be an unknown one.
     */
    private fun thumbnailName(json: JSONObject): String? {
        val value = try {
            json.getString(JSON_THUMBNAIL)
        } catch (invalid: Exception) {
            return null
        }
        return if (value.length > InputPolicy.MAX_TEXT_CHARS) null else value
    }

    /** Best-effort usage metadata: the Java parser defaulted unreadable values to zero. */
    private fun usage(json: JSONObject, name: String): Long {
        return try {
            json.getLong(name)
        } catch (invalid: Exception) {
            0L
        }
    }

    private fun bounded(value: String, limit: Int) {
        if (value.length > limit) reject(EntryJsonError.TEXT_TOO_LONG)
    }

    private class Rejected(val error: EntryJsonError) : IllegalArgumentException(error.name)
    private fun reject(error: EntryJsonError): Nothing = throw Rejected(error)
}
