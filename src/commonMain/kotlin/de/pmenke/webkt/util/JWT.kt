package de.pmenke.webkt.util

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import kotlin.io.encoding.Base64
import kotlin.time.Instant

/**
 * The decoded, **unverified** contents of a compact JSON Web Token (JWT).
 *
 * [decode] performs Base64URL, strict UTF-8, and JSON decoding only. It does not verify the signature or algorithm,
 * validate issuer or audience, enforce time claims, or authenticate the token in any other way. Every
 * header and claim exposed by this type is attacker-controlled data until a security-reviewed verifier
 * has performed the cryptographic and application-specific checks required by the caller.
 *
 * Encrypted JWTs (JWE) are not supported.
 */
data class UnverifiedJwt(
    val header: UnverifiedJwtHeader,
    val claims: UnverifiedJwtClaims,
    /** The third compact-serialization segment, without decoding or verification. */
    val signatureSegment: String,
) {
    /**
     * Compatibility name for [signatureSegment].
     *
     * The value is merely the encoded third segment; exposing it does not mean that a signature exists
     * or that any signature has been verified.
     */
    @Deprecated(
        message = "This is an unverified compact segment, not a verified signature; use signatureSegment.",
        replaceWith = ReplaceWith("signatureSegment"),
    )
    val signature: String
        get() = signatureSegment

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /**
         * Decodes an unencrypted compact JWT without verifying it.
         *
         * This performs Base64URL, strict UTF-8, and JSON decoding only. The returned header, claims,
         * and signature segment remain untrusted until verified by a separate security component.
         *
         * @throws IllegalArgumentException if the token does not have three compact parts or its
         * Base64URL, UTF-8, or JSON content cannot be decoded.
         */
        fun decode(compactJwt: String): UnverifiedJwt {
            val parts = compactJwt.split('.')
            if (parts.size != 3) {
                throw IllegalArgumentException("Invalid JWT format")
            }
            return try {
                val b64 = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)
                val headerJson = b64.decode(parts[0]).decodeToString(throwOnInvalidSequence = true)
                val claimsJson = b64.decode(parts[1]).decodeToString(throwOnInvalidSequence = true)
                val header = json.decodeFromString<UnverifiedJwtHeader>(headerJson)
                val claims = json.decodeFromString<JsonObject>(claimsJson)
                UnverifiedJwt(header, UnverifiedJwtClaims(claims), parts[2])
            } catch (exception: Exception) {
                throw IllegalArgumentException("Invalid JWT content", exception)
            }
        }

        /** Compatibility adapter for [decode]. */
        @Deprecated(
            message = "fromString only decodes an unverified token; use UnverifiedJwt.decode.",
            replaceWith = ReplaceWith(
                "UnverifiedJwt.decode(jwt)",
                "de.pmenke.webkt.util.UnverifiedJwt",
            ),
        )
        fun fromString(jwt: String): UnverifiedJwt = decode(jwt)
    }
}

/**
 * Decoded, **unverified** JOSE header values.
 *
 * In particular, [alg] and [kid] are attacker-controlled hints. A verifier must select and constrain
 * acceptable algorithms and keys independently; it must not trust these values by themselves.
 */
@Serializable
data class UnverifiedJwtHeader(
    val alg: String,
    val typ: String? = null,
    val kid: String? = null,
)

/**
 * Read-only access to a decoded, **unverified** JWT claims object.
 *
 * Accessors only convert JSON values. Issuer, audience, and time claims are untrusted data; reading them
 * does not validate their meaning or enforce any authorization or expiry rule.
 */
class UnverifiedJwtClaims(private val content: JsonObject) {
    /** Returns the raw JSON value for [key], or `null` when the claim is absent. */
    operator fun get(key: String) = content[key]

    /** Returns a string claim or `null` when it is absent or not a JSON string. */
    fun getStringOrNull(key: String): String? =
        (content[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    /**
     * Returns a NumericDate claim as an [Instant], or `null` when absent or invalid.
     *
     * Both integral and finite fractional JSON numbers are supported. Decimal digits beyond nanosecond
     * precision are rounded to the nearest nanosecond, with exact ties rounded away from zero. Parsing
     * uses the JSON number's decimal representation directly, so it does not lose precision through a
     * floating-point conversion. JSON strings and values outside [Instant]'s representable range are
     * rejected rather than coerced or saturated.
     */
    fun getInstantOrNull(key: String): Instant? =
        (content[key] as? JsonPrimitive)?.toNumericDateInstantOrNull()

    /** Returns a claim encoded as either one string or an array of strings. */
    fun getStringListOrEmpty(key: String): List<String> = when (val value = content[key]) {
        is JsonPrimitive -> value.takeIf { it.isString }?.contentOrNull?.let(::listOf).orEmpty()
        is JsonArray -> value.mapNotNull { (it as? JsonPrimitive)?.takeIf { item -> item.isString }?.contentOrNull }
        else -> emptyList()
    }
}

private fun JsonPrimitive.toNumericDateInstantOrNull(): Instant? {
    if (isString) return null

    longOrNull?.let { epochSeconds ->
        return runCatching { Instant.fromEpochSeconds(epochSeconds) }
            .getOrNull()
            ?.takeIf { it.epochSeconds == epochSeconds && it.nanosecondsOfSecond == 0 }
    }

    val parsed = parseFractionalNumericDate(content) ?: return null
    return runCatching { Instant.fromEpochSeconds(parsed.epochSeconds, parsed.nanosecondsOfSecond) }
        .getOrNull()
        ?.takeIf {
            it.epochSeconds == parsed.epochSeconds &&
                it.nanosecondsOfSecond == parsed.nanosecondsOfSecond
        }
}

private data class ParsedNumericDate(
    val epochSeconds: Long,
    val nanosecondsOfSecond: Int,
)

private fun parseFractionalNumericDate(value: String): ParsedNumericDate? {
    var numberStart = 0
    val isNegative = value.startsWith('-')
    if (isNegative) numberStart++

    val exponentMarker = value.indexOfAny(charArrayOf('e', 'E'), startIndex = numberStart)
    val mantissaEnd = exponentMarker.takeIf { it >= 0 } ?: value.length
    val decimalPoint = value.indexOf('.', startIndex = numberStart).takeIf { it >= 0 && it < mantissaEnd }
    if (numberStart >= mantissaEnd) return null

    val integerEnd = decimalPoint ?: mantissaEnd
    val integerDigits = value.substring(numberStart, integerEnd)
    val fractionDigits = if (decimalPoint == null) "" else value.substring(decimalPoint + 1, mantissaEnd)
    if (integerDigits.isEmpty() || integerDigits.any { it !in '0'..'9' }) return null
    if (decimalPoint != null && (fractionDigits.isEmpty() || fractionDigits.any { it !in '0'..'9' })) return null

    val exponent = if (exponentMarker < 0) {
        0L
    } else {
        parseSaturatedExponent(value.substring(exponentMarker + 1)) ?: return null
    }
    val coefficient = integerDigits + fractionDigits
    val firstNonZero = coefficient.indexOfFirst { it != '0' }
    if (firstNonZero < 0) return ParsedNumericDate(0, 0)

    val significantDigits = coefficient.substring(firstNonZero)
    val decimalPosition = saturatedAdd(
        saturatedAdd(integerDigits.length.toLong(), exponent),
        -firstNonZero.toLong(),
    )
    if (decimalPosition > MAX_WHOLE_SECOND_DIGITS) return null

    var wholeSecondsMagnitude = 0L
    if (decimalPosition > 0) {
        repeat(decimalPosition.toInt()) { index ->
            val digit = significantDigits.getOrNull(index)?.let { it - '0' } ?: 0
            if (wholeSecondsMagnitude > (Long.MAX_VALUE - digit) / 10) return null
            wholeSecondsMagnitude = wholeSecondsMagnitude * 10 + digit
        }
    }

    var nanosecondsMagnitude = 0
    repeat(NANOSECOND_DIGITS) { fractionalIndex ->
        val coefficientIndex = saturatedAdd(decimalPosition, fractionalIndex.toLong())
        val digit = coefficientIndex
            .takeIf { it >= 0 && it < significantDigits.length }
            ?.toInt()
            ?.let { significantDigits[it] - '0' }
            ?: 0
        nanosecondsMagnitude = nanosecondsMagnitude * 10 + digit
    }

    val firstDiscardedIndex = saturatedAdd(decimalPosition, NANOSECOND_DIGITS.toLong())
    val firstDiscardedDigit = firstDiscardedIndex
        .takeIf { it >= 0 && it < significantDigits.length }
        ?.toInt()
        ?.let { significantDigits[it] - '0' }
        ?: 0
    if (firstDiscardedDigit >= 5) {
        nanosecondsMagnitude++
        if (nanosecondsMagnitude == NANOSECONDS_PER_SECOND) {
            if (wholeSecondsMagnitude == Long.MAX_VALUE) return null
            wholeSecondsMagnitude++
            nanosecondsMagnitude = 0
        }
    }

    if (!isNegative) return ParsedNumericDate(wholeSecondsMagnitude, nanosecondsMagnitude)
    if (nanosecondsMagnitude == 0) {
        return ParsedNumericDate(-wholeSecondsMagnitude, 0)
    }
    if (wholeSecondsMagnitude == Long.MAX_VALUE) return null
    return ParsedNumericDate(
        epochSeconds = -(wholeSecondsMagnitude + 1),
        nanosecondsOfSecond = NANOSECONDS_PER_SECOND - nanosecondsMagnitude,
    )
}

private fun parseSaturatedExponent(value: String): Long? {
    if (value.isEmpty()) return null
    var index = 0
    val isNegative = value[0] == '-'
    if (isNegative || value[0] == '+') index++
    if (index == value.length) return null

    var magnitude = 0L
    var saturated = false
    while (index < value.length) {
        val character = value[index]
        if (character !in '0'..'9') return null
        val digit = character - '0'
        if (!saturated && magnitude > (Long.MAX_VALUE - digit) / 10) {
            saturated = true
        } else if (!saturated) {
            magnitude = magnitude * 10 + digit
        }
        index++
    }
    if (saturated) return if (isNegative) Long.MIN_VALUE else Long.MAX_VALUE
    return if (isNegative) -magnitude else magnitude
}

private fun saturatedAdd(left: Long, right: Long): Long = when {
    right > 0 && left > Long.MAX_VALUE - right -> Long.MAX_VALUE
    right < 0 && left < Long.MIN_VALUE - right -> Long.MIN_VALUE
    else -> left + right
}

private const val MAX_WHOLE_SECOND_DIGITS = 19L
private const val NANOSECOND_DIGITS = 9
private const val NANOSECONDS_PER_SECOND = 1_000_000_000

/**
 * Compatibility alias for [UnverifiedJwt]. This decoder has never authenticated tokens.
 */
@Deprecated(
    message = "JWT is decoded but unverified; use UnverifiedJwt and verify it separately.",
    replaceWith = ReplaceWith("UnverifiedJwt", "de.pmenke.webkt.util.UnverifiedJwt"),
)
typealias JWT = UnverifiedJwt

/** Compatibility alias for the attacker-controlled [UnverifiedJwtHeader]. */
@Deprecated(
    message = "Header values are unverified; use UnverifiedJwtHeader.",
    replaceWith = ReplaceWith("UnverifiedJwtHeader", "de.pmenke.webkt.util.UnverifiedJwtHeader"),
)
typealias Header = UnverifiedJwtHeader

/** Compatibility alias for the attacker-controlled [UnverifiedJwtClaims]. */
@Deprecated(
    message = "Claims are unverified; use UnverifiedJwtClaims.",
    replaceWith = ReplaceWith("UnverifiedJwtClaims", "de.pmenke.webkt.util.UnverifiedJwtClaims"),
)
typealias Claims = UnverifiedJwtClaims

/**
 * The "iss" (issuer) claim identifies the principal that issued the JWT. The processing of this
 * claim is generally application specific. The "iss" value is a case-sensitive string containing a
 * StringOrURI value.
 *
 * This accessor returns unverified input; it does not establish who issued the token.
 *
 * @see <a href="https://www.rfc-editor.org/rfc/rfc7519.html#section-4.1.1">RFC7519, Section
 *   4.1.1</a>
 */
val UnverifiedJwt.issuer: String?
    get() = claims.issuer

val UnverifiedJwtClaims.issuer: String?
    get() = getStringOrNull("iss")

/**
 * The "sub" (subject) claim identifies the principal that is the subject of the JWT. The claims in
 * a JWT are normally statements about the subject. The subject value MUST either be scoped to be
 * locally unique in the context of the issuer or be globally unique. The processing of this claim
 * is generally application specific. The "sub" value is a case-sensitive string containing a
 * StringOrURI value.
 *
 * This accessor returns unverified input; it does not authenticate the subject.
 *
 * @see <a href="https://www.rfc-editor.org/rfc/rfc7519.html#section-4.1.2">RFC7519, Section
 *   4.1.2</a>
 */
val UnverifiedJwt.subject: String?
    get() = claims.subject

val UnverifiedJwtClaims.subject: String?
    get() = getStringOrNull("sub")

/**
 * The "aud" (audience) claim identifies the recipients that the JWT is intended for. Each principal
 * intended to process the JWT MUST identify itself with a value in the audience claim. If the
 * principal processing the claim does not identify itself with a value in the "aud" claim when this
 * claim is present, then the JWT MUST be rejected. In the general case, the "aud" value is an array
 * of case-sensitive strings, each containing a StringOrURI value. In the special case when the JWT
 * has one audience, the "aud" value MAY be a single case-sensitive string containing a StringOrURI
 * value. The interpretation of audience values is generally application specific.
 *
 * This accessor returns unverified input; it does not check that the caller is an intended audience.
 *
 * @see <a href="https://www.rfc-editor.org/rfc/rfc7519.html#section-4.1.3">RFC7519, Section
 *   4.1.3</a>
 */
val UnverifiedJwt.audience: String?
    get() = claims.audience

val UnverifiedJwtClaims.audience: String?
    get() = audiences.firstOrNull()

/** All values from the JWT `aud` claim, supporting both its string and array representations. */
val UnverifiedJwt.audiences: List<String>
    get() = claims.audiences

/** All values from the `aud` claim, supporting both its string and array representations. */
val UnverifiedJwtClaims.audiences: List<String>
    get() = getStringListOrEmpty("aud")

/**
 * The "exp" (expiration time) claim identifies the expiration time on or after which the JWT MUST
 * NOT be accepted for processing. The processing of the "exp" claim requires that the current
 * date/time MUST be before the expiration date/time listed in the "exp" claim. Implementers MAY
 * provide for some small leeway, usually no more than a few minutes, to account for clock skew. Its
 * value MUST be a number containing a NumericDate value.
 *
 * This accessor converts unverified input to an [Instant]; it does not enforce token expiry.
 *
 * @see <a href="https://www.rfc-editor.org/rfc/rfc7519.html#section-4.1.4">RFC7519, Section
 *   4.1.4</a>
 */
val UnverifiedJwt.expiresAt: Instant
    get() = claims.expiresAt

val UnverifiedJwtClaims.expiresAt: Instant
    get() = getInstantOrNull("exp") ?:
        throw IllegalArgumentException("JWT does not contain 'exp' claim or it is invalid")

/**
 * The "nbf" (not before) claim identifies the time before which the JWT MUST NOT be accepted for
 * processing. The processing of the "nbf" claim requires that the current date/time MUST be after
 * or equal to the not-before date/time listed in the "nbf" claim. Implementers MAY provide for some
 * small leeway, usually no more than a few minutes, to account for clock skew. Its value MUST be a
 * number containing a NumericDate value.
 *
 * This accessor converts unverified input to an [Instant]; it does not enforce the not-before rule.
 *
 * @see <a href="https://www.rfc-editor.org/rfc/rfc7519.html#section-4.1.5">RFC7519, Section
 *   4.1.5</a>
 */
val UnverifiedJwt.notBefore: Instant?
    get() = claims.notBefore

val UnverifiedJwtClaims.notBefore: Instant?
    get() = getInstantOrNull("nbf")

/**
 * The "iat" (issued at) claim identifies the time at which the JWT was issued. This claim can be
 * used to determine the age of the JWT. Its value MUST be a number containing a NumericDate value.
 *
 * This accessor converts unverified input to an [Instant]; it does not establish when the token was issued.
 *
 * @see <a href="https://www.rfc-editor.org/rfc/rfc7519.html#section-4.1.6">RFC7519, Section
 *   4.1.6</a>
 */
val UnverifiedJwt.issuedAt: Instant?
    get() = claims.issuedAt

val UnverifiedJwtClaims.issuedAt: Instant?
    get() = getInstantOrNull("iat")

/**
 * The "jti" (JWT ID) claim provides a unique identifier for the JWT. The identifier value MUST be
 * assigned in a manner that ensures that there is a negligible probability that the same value will
 * be accidentally assigned to a different data object; if the application uses multiple issuers,
 * collisions MUST be prevented among values produced by different issuers as well. The "jti" claim
 * can be used to prevent the JWT from being replayed. The "jti" value is a case-sensitive string.
 *
 * This accessor returns unverified input; it does not provide replay protection by itself.
 *
 * @see <a href="https://www.rfc-editor.org/rfc/rfc7519.html#section-4.1.7">RFC7519, Section
 *   4.1.7</a>
 */
val UnverifiedJwt.id: String?
    get() = claims.id

val UnverifiedJwtClaims.id: String?
    get() = getStringOrNull("jti")
