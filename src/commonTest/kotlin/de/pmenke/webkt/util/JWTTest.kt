package de.pmenke.webkt.util

import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.time.Instant

class JWTTest {
    private val base64Url = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)

    @Test
    fun explicitApiDecodesUnverifiedToken() {
        val jwt = token(
            header = """{"alg":"RS256","typ":"JWT","kid":"one","custom":"ignored"}""",
            claims = """{"iss":"issuer","sub":"subject","jti":"id","exp":123}""",
            signatureSegment = "encoded-signature",
        )

        assertEquals("RS256", jwt.header.alg)
        assertEquals("JWT", jwt.header.typ)
        assertEquals("one", jwt.header.kid)
        assertEquals("issuer", jwt.issuer)
        assertEquals("subject", jwt.subject)
        assertEquals("id", jwt.id)
        assertEquals(Instant.fromEpochSeconds(123), jwt.expiresAt)
        assertEquals("encoded-signature", jwt.signatureSegment)
    }

    @Suppress("DEPRECATION")
    @Test
    fun legacyAliasesAndDecoderRemainSourceCompatible() {
        val jwt: JWT = JWT.fromString(compactToken("""{"alg":"none"}""", "{}", "legacy"))
        val header: Header = jwt.header
        val claims: Claims = jwt.claims
        val reconstructed: JWT = JWT(header, claims, jwt.signature)

        assertEquals("none", reconstructed.header.alg)
        assertEquals("legacy", reconstructed.signature)
    }

    @Test
    fun missingAndNonStringOptionalClaimsAreReportedAsAbsent() {
        val jwt = token(
            header = """{"alg":"none"}""",
            claims = """{"iss":42,"sub":false,"aud":{},"nbf":"later","iat":[],"jti":{}}""",
        )

        assertNull(jwt.header.typ)
        assertNull(jwt.header.kid)
        assertNull(jwt.issuer)
        assertNull(jwt.subject)
        assertNull(jwt.audience)
        assertEquals(emptyList(), jwt.audiences)
        assertNull(jwt.notBefore)
        assertNull(jwt.issuedAt)
        assertNull(jwt.id)
        assertNull(jwt.claims["missing"])
        assertFailsWith<IllegalArgumentException> { jwt.expiresAt }
    }

    @Test
    fun supportsStringArrayAndMixedAudienceRepresentations() {
        val single = token("""{"alg":"none"}""", """{"aud":"one"}""")
        val multiple = token("""{"alg":"none"}""", """{"aud":["one","two"]}""")
        val mixed = token(
            """{"alg":"none"}""",
            """{"aud":["one",42,true,null,{},["nested"],"two"]}""",
        )

        assertEquals(listOf("one"), single.audiences)
        assertEquals(listOf("one", "two"), multiple.audiences)
        assertEquals("one", multiple.audience)
        assertEquals(listOf("one", "two"), mixed.audiences)
    }

    @Test
    fun preservesNineFractionalDigitsAtContemporaryEpochMagnitudes() {
        val jwt = token(
            """{"alg":"none"}""",
            """{
                "exp":1700000000.123456789,
                "nbf":-1700000000.123456789,
                "iat":9007199254740993
            }""".trimIndent(),
        )

        assertEquals(Instant.fromEpochSeconds(1_700_000_000, 123_456_789), jwt.expiresAt)
        assertEquals(Instant.fromEpochSeconds(-1_700_000_001, 876_543_211), jwt.notBefore)
        assertEquals(Instant.fromEpochSeconds(9_007_199_254_740_993), jwt.issuedAt)
    }

    @Test
    fun roundsDiscardedDigitsAndNormalizesPositiveAndNegativeCarry() {
        val jwt = token(
            """{"alg":"none"}""",
            """{
                "down":10.1234567894,
                "up":10.1234567895,
                "carry":10.9999999996,
                "negative":-10.1234567896,
                "negativeCarry":-10.9999999996,
                "negativeTie":-0.0000000005
            }""".trimIndent(),
        )

        assertEquals(Instant.fromEpochSeconds(10, 123_456_789), jwt.claims.getInstantOrNull("down"))
        assertEquals(Instant.fromEpochSeconds(10, 123_456_790), jwt.claims.getInstantOrNull("up"))
        assertEquals(Instant.fromEpochSeconds(11), jwt.claims.getInstantOrNull("carry"))
        assertEquals(Instant.fromEpochSeconds(-11, 876_543_210), jwt.claims.getInstantOrNull("negative"))
        assertEquals(Instant.fromEpochSeconds(-11), jwt.claims.getInstantOrNull("negativeCarry"))
        assertEquals(Instant.fromEpochSeconds(-1, 999_999_999), jwt.claims.getInstantOrNull("negativeTie"))
    }

    @Test
    fun appliesDecimalExponentsBeforeNanosecondRounding() {
        val jwt = token(
            """{"alg":"none"}""",
            """{
                "positive":1.23456789e2,
                "negativeExponent":1.23456789e-2,
                "tinyDown":4e-10,
                "tinyUp":5e-10,
                "tinyNegative":-5e-10,
                "hugeZero":0e999999999999999999999999
            }""".trimIndent(),
        )

        assertEquals(Instant.fromEpochSeconds(123, 456_789_000), jwt.claims.getInstantOrNull("positive"))
        assertEquals(Instant.fromEpochSeconds(0, 12_345_679), jwt.claims.getInstantOrNull("negativeExponent"))
        assertEquals(Instant.fromEpochSeconds(0), jwt.claims.getInstantOrNull("tinyDown"))
        assertEquals(Instant.fromEpochSeconds(0, 1), jwt.claims.getInstantOrNull("tinyUp"))
        assertEquals(Instant.fromEpochSeconds(-1, 999_999_999), jwt.claims.getInstantOrNull("tinyNegative"))
        assertEquals(Instant.fromEpochSeconds(0), jwt.claims.getInstantOrNull("hugeZero"))
    }

    @Test
    fun acceptsInstantRangeEdgesAndRejectsRoundedValuesBeyondThem() {
        val maximumInstant = Instant.fromEpochSeconds(Long.MAX_VALUE)
        val minimumInstant = Instant.fromEpochSeconds(Long.MIN_VALUE)
        val maximum = token(
            """{"alg":"none"}""",
            """{"exp":${maximumInstant.epochSeconds}.999999999}""",
        )
        val minimum = token(
            """{"alg":"none"}""",
            """{"exp":${minimumInstant.epochSeconds}}""",
        )
        val aboveMaximum = token(
            """{"alg":"none"}""",
            """{"exp":${maximumInstant.epochSeconds}.9999999995}""",
        )
        val belowMinimum = token(
            """{"alg":"none"}""",
            """{"exp":${minimumInstant.epochSeconds}.000000001}""",
        )

        assertEquals(maximumInstant, maximum.expiresAt)
        assertEquals(minimumInstant, minimum.expiresAt)
        assertNull(aboveMaximum.claims.getInstantOrNull("exp"))
        assertNull(belowMinimum.claims.getInstantOrNull("exp"))
    }

    @Test
    fun invalidNumericDatesAreRejectedWithoutCoercionOrSaturation() {
        val wrongKinds = token(
            """{"alg":"none"}""",
            """{"exp":"123","nbf":true,"iat":null}""",
        )
        val outOfRange = token(
            """{"alg":"none"}""",
            """{"exp":9223372036854775807,"nbf":-9223372036854775808}""",
        )
        val hugeExponent = token(
            """{"alg":"none"}""",
            """{"exp":1e999,"nbf":-1e999}""",
        )

        assertNull(wrongKinds.claims.getInstantOrNull("exp"))
        assertNull(wrongKinds.notBefore)
        assertNull(wrongKinds.issuedAt)
        assertFailsWith<IllegalArgumentException> { wrongKinds.expiresAt }
        assertNull(outOfRange.claims.getInstantOrNull("exp"))
        assertNull(outOfRange.notBefore)
        assertFailsWith<IllegalArgumentException> { outOfRange.expiresAt }
        assertNull(hugeExponent.claims.getInstantOrNull("exp"))
        assertNull(hugeExponent.notBefore)
        assertFailsWith<IllegalArgumentException> { hugeExponent.expiresAt }
    }

    @Test
    fun reportsMalformedCompactBase64AndJsonContentConsistently() {
        val malformed = listOf(
            "not-a-token",
            "one.two.three.four",
            "$.e30.signature",
            compactToken("not json", "{}"),
            compactToken("""{"alg":"none"}""", "not json"),
            compactToken("""{"typ":"JWT"}""", "{}"),
        )

        malformed.forEach { compactJwt ->
            assertFailsWith<IllegalArgumentException>(compactJwt) {
                UnverifiedJwt.decode(compactJwt)
            }
        }
    }

    @Test
    fun rejectsInvalidUtf8InsideOtherwiseValidJsonStrings() {
        val invalidHeader = invalidUtf8JsonString("""{"alg":"""", """"}""")
        val invalidClaims = invalidUtf8JsonString("""{"iss":"""", """"}""")
        val validHeader = """{"alg":"none"}""".encodeToByteArray()
        val validClaims = "{}".encodeToByteArray()

        assertFailsWith<IllegalArgumentException> {
            UnverifiedJwt.decode(compactToken(invalidHeader, validClaims))
        }
        assertFailsWith<IllegalArgumentException> {
            UnverifiedJwt.decode(compactToken(validHeader, invalidClaims))
        }
    }

    private fun token(
        header: String,
        claims: String,
        signatureSegment: String = "signature",
    ): UnverifiedJwt = UnverifiedJwt.decode(compactToken(header, claims, signatureSegment))

    private fun compactToken(
        header: String,
        claims: String,
        signatureSegment: String = "signature",
    ): String {
        val encodedHeader = base64Url.encode(header.encodeToByteArray())
        val encodedClaims = base64Url.encode(claims.encodeToByteArray())
        return "$encodedHeader.$encodedClaims.$signatureSegment"
    }

    private fun compactToken(
        header: ByteArray,
        claims: ByteArray,
        signatureSegment: String = "signature",
    ): String = "${base64Url.encode(header)}.${base64Url.encode(claims)}.$signatureSegment"

    private fun invalidUtf8JsonString(prefix: String, suffix: String): ByteArray =
        prefix.encodeToByteArray() + byteArrayOf(0xC3.toByte(), 0x28) + suffix.encodeToByteArray()
}
