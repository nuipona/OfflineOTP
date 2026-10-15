package org.shadowice.flocke.andotp.Utilities

import org.apache.commons.codec.binary.Base32
import org.junit.Assert.*
import org.junit.Test
import java.nio.charset.StandardCharsets.UTF_8
import java.util.Random

class OtpUriParserTest {
    private val seed = "GEZDGNBVGY3TQOJQ"
    private val totp = "otpauth://totp/synthetic?secret=$seed"

    private fun success(uri: String): ValidatedOtp {
        val result = OtpUriParser.parse(uri)
        assertTrue(result.toString(), result is OtpParseResult.Success)
        return (result as OtpParseResult.Success).value
    }

    private fun failure(uri: String?, error: OtpParseError) {
        val result = OtpUriParser.parse(uri)
        assertTrue("Expected failure, got $result", result is OtpParseResult.Failure)
        assertEquals(error, (result as OtpParseResult.Failure).error)
    }

    @Test fun rfcSeedDefaultsAndMissingIssuer() {
        success(totp).use {
            assertArrayEquals("1234567890".toByteArray(UTF_8), it.copySecret())
            assertEquals(OtpKind.TOTP, it.kind)
            assertEquals(OtpHashAlgorithm.SHA1, it.algorithm)
            assertEquals(30, it.period)
            assertEquals(6, it.digits)
            assertEquals(0L, it.counter)
            assertEquals("", it.issuer)
            assertEquals("synthetic", it.label)
            assertTrue(it.tags.isEmpty())
        }
    }

    @Test fun utf8LabelsIssuerStrippingAndQueryPlusRules() {
        success("otpauth://totp/ACME%3A%20alice%2B%CE%B1?secret=$seed&issuer=ACME&tags=work+space&tags=a%2Bb").use {
            assertEquals("ACME", it.issuer)
            assertEquals("alice+α", it.label)
            assertEquals(listOf("work space", "a+b"), it.tags)
        }
        success("otpauth://totp/a+b?secret=$seed").use { assertEquals("a+b", it.label) }
        success("otpauth://totp/ACME%3Aalice?secret=$seed").use { assertEquals("ACME:alice", it.label) }
        success("otpauth://totp/%252F?secret=$seed").use { assertEquals("%2F", it.label) }
    }

    @Test fun supportedVariantsAndBoundaryNumbers() {
        success("otpauth://hotp/a?secret=$seed&counter=9223372036854775806&digits=9&algorithm=sha512").use {
            assertEquals(OtpKind.HOTP, it.kind)
            assertEquals(Long.MAX_VALUE - 1, it.counter)
            assertEquals(9, it.digits)
            assertEquals(OtpHashAlgorithm.SHA512, it.algorithm)
        }
        success("$totp&period=1&digits=1").use { assertEquals(1, it.period) }
        success("$totp&period=86400&digits=9").use { assertEquals(86400, it.period) }
        success("otpauth://steam/a?secret=$seed").use { assertEquals(5, it.digits) }
        success("otpauth://motp/a?secret=0123456789abcdef").use {
            assertEquals(OtpKind.MOTP, it.kind)
            assertArrayEquals("0123456789abcdef".toByteArray(UTF_8), it.copySecret())
        }
        success("$totp&future-option=yes").close()
    }

    @Test fun invalidStructuresAndTypesHaveExplicitErrors() {
        for (uri in listOf(null, "", "https://totp/a?secret=$seed", "otpauth:totp?secret=$seed",
            "otpauth://user@totp/a?secret=$seed", "otpauth://totp:80/a?secret=$seed", "$totp#", "$totp&")) {
            failure(uri, OtpParseError.INVALID_URI)
        }
        failure("otpauth://unknown/a?secret=$seed", OtpParseError.UNSUPPORTED_TYPE)
        failure("otpauth://totp/a", OtpParseError.MISSING_SECRET)
        failure("otpauth://totp/a?secret=", OtpParseError.MISSING_SECRET)
        failure("otpauth://hotp/a?secret=$seed", OtpParseError.MISSING_COUNTER)
    }

    @Test fun singletonCollisionsAreCheckedAfterDecoding() {
        failure("$totp&secret=$seed", OtpParseError.DUPLICATE_PARAMETER)
        failure("$totp&%73ecret=$seed", OtpParseError.DUPLICATE_PARAMETER)
        failure("$totp&period=30&period=30", OtpParseError.DUPLICATE_PARAMETER)
        failure("$totp&issuer=a&issuer=b", OtpParseError.DUPLICATE_PARAMETER)
        failure("$totp&unknown=a&unknown=b", OtpParseError.DUPLICATE_PARAMETER)
        success("$totp&tags=a&tags=a").use { assertEquals(listOf("a", "a"), it.tags) }
    }

    @Test fun malformedEscapesAndUtf8AreNeverReplaced() {
        for (part in listOf("%", "%0", "%GG", "%C0%AF", "%ED%A0%80", "%FF", "%E2%82", "\uD800")) {
            failure("otpauth://totp/$part?secret=$seed", OtpParseError.INVALID_URI)
            failure("$totp&issuer=$part", OtpParseError.INVALID_URI)
        }
    }

    @Test fun numericSyntaxOverflowAndDomainLimitsAreDistinct() {
        for (value in listOf("", "1.5", "1e2", "%2B1", "9223372036854775808", "abc")) {
            failure("$totp&period=$value", OtpParseError.INVALID_NUMBER)
        }
        for (value in listOf("0", "-1", "86401", "4294967326")) {
            failure("$totp&period=$value", OtpParseError.INVALID_PARAMETERS)
        }
        for (value in listOf("0", "-1", "10", "2147483648")) {
            failure("$totp&digits=$value", OtpParseError.INVALID_PARAMETERS)
        }
        failure("otpauth://hotp/a?secret=$seed&counter=-1", OtpParseError.INVALID_PARAMETERS)
        failure("otpauth://hotp/a?secret=$seed&counter=9223372036854775807", OtpParseError.INVALID_PARAMETERS)
        failure("otpauth://hotp/a?secret=$seed&counter=9223372036854775808", OtpParseError.INVALID_NUMBER)
        failure("otpauth://steam/a?secret=$seed&digits=6", OtpParseError.INVALID_PARAMETERS)
        failure("$totp&counter=-1", OtpParseError.INVALID_PARAMETERS) // No ignoring bad unused fields.
        failure("$totp&algorithm=MD5", OtpParseError.INVALID_ALGORITHM)
    }

    @Test fun base32IsCanonicalButCaseAndOptionalPaddingRemainCompatible() {
        success("otpauth://totp/a?secret=${seed.lowercase()}").use {
            assertArrayEquals("1234567890".toByteArray(UTF_8), it.copySecret())
        }
        for (value in listOf("MY======", "MY")) {
            success("otpauth://totp/a?secret=$value").use { assertArrayEquals(byteArrayOf(102), it.copySecret()) }
        }
        for (value in listOf("A", "MZ", "MY=", "A!", "MY%20", "mı", "$seed======")) {
            failure("otpauth://totp/a?secret=$value", OtpParseError.INVALID_SECRET)
        }
        failure("otpauth://motp/a?secret=abc", OtpParseError.INVALID_SECRET)
        failure("otpauth://motp/a?secret=not-hex", OtpParseError.INVALID_SECRET)
    }

    @Test fun uriTextTagAndDecodedSeedLimitsAreEnforced() {
        val exactUri = totp + "&extra=" + "x".repeat(InputPolicy.MAX_URI_CHARS - totp.length - 7)
        assertEquals(InputPolicy.MAX_URI_CHARS, exactUri.length)
        success(exactUri).close()
        failure(exactUri + "x", OtpParseError.INPUT_TOO_LONG)
        val text = "x".repeat(InputPolicy.MAX_TEXT_CHARS)
        success("otpauth://totp/$text?secret=$seed&issuer=$text").close()
        failure("$totp&issuer=${text}x", OtpParseError.TEXT_TOO_LONG)
        failure("otpauth://totp/${text}x?secret=$seed", OtpParseError.TEXT_TOO_LONG)
        val tag = "x".repeat(InputPolicy.MAX_TAG_CHARS)
        success(totp + "&tags=$tag".repeat(InputPolicy.MAX_TAGS)).close()
        failure(totp + "&tags=x".repeat(InputPolicy.MAX_TAGS + 1), OtpParseError.TOO_MANY_TAGS)
        failure("$totp&tags=${tag}x", OtpParseError.TEXT_TOO_LONG)
        val maxSeed = Base32().encodeAsString(ByteArray(InputPolicy.MAX_SECRET_BYTES))
        success("otpauth://totp/a?secret=$maxSeed").use { assertEquals(InputPolicy.MAX_SECRET_BYTES, it.copySecret().size) }
        val oversizedSeed = Base32().encodeAsString(ByteArray(InputPolicy.MAX_SECRET_BYTES + 1))
        failure("otpauth://totp/a?secret=$oversizedSeed", OtpParseError.INVALID_SECRET)
    }

    @Test fun valuesOwnTheirSeedAndTagsAndDoNotPrintSecrets() {
        val result = OtpUriParser.parse("$totp&tags=work") as OtpParseResult.Success
        val value = result.value
        val firstCopy = value.copySecret()
        firstCopy.fill(0)
        assertArrayEquals("1234567890".toByteArray(UTF_8), value.copySecret())
        assertThrows(UnsupportedOperationException::class.java) { (value.tags as MutableList<String>).add("bad") }
        assertFalse(result.toString().contains(seed))
        assertFalse(value.toString().contains("synthetic"))
        assertFalse(value.toString().contains("1234567890"))
        value.close()
        value.close()
        assertThrows(IllegalStateException::class.java) { value.copySecret() }
        val failure = OtpUriParser.parse("$totp&secret=private-user-input").toString()
        assertFalse(failure.contains("private-user-input"))
    }

    @Test fun internalBuilderCannotBypassInvariantsOrAliasCallerStorage() {
        val source = byteArrayOf(1, 2, 3)
        val tags = arrayListOf("tag")
        val value = ValidatedOtp.create(OtpKind.TOTP, source, 30, 6, 0, "", "", OtpHashAlgorithm.SHA1, tags)
        source.fill(0)
        tags.clear()
        value.use {
            assertArrayEquals(byteArrayOf(1, 2, 3), it.copySecret())
            assertEquals(listOf("tag"), it.tags)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ValidatedOtp.create(OtpKind.TOTP, source, 0, 6, 0, "", "", OtpHashAlgorithm.SHA1, tags)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ValidatedOtp.create(OtpKind.STEAM, source, 30, 6, 0, "", "", OtpHashAlgorithm.SHA1, tags)
        }
    }

    @Test(timeout = 10000) fun deterministicMalformedCorpusNeverLeaksAnInputException() {
        val random = Random(0)
        val characters = "otpauth:/?&=%+#@0123456789AZaz []\u0000\uD800\uDC00"
        repeat(2000) {
            val suffix = buildString { repeat(random.nextInt(128)) { append(characters[random.nextInt(characters.length)]) } }
            for (input in listOf(suffix, "$totp&issuer=$suffix")) {
                when (val result = OtpUriParser.parse(input)) {
                    is OtpParseResult.Success -> result.value.close()
                    is OtpParseResult.Failure -> assertNotNull(result.error)
                }
            }
        }
    }
}
