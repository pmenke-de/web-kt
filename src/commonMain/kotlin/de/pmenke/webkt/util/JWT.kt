package de.pmenke.webkt.util

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlin.io.encoding.Base64
import kotlin.let
import kotlin.text.decodeToString
import kotlin.text.split
import kotlin.time.Instant

/**
 * A parsed JSON Web Token (JWT).
 * Doesn't support encrypted JWTs (JWE) and doesn't validate the signature.
 */
data class JWT(
    val header: Header,
    val claims: Claims,
    val signature: String,
) {
    companion object {
        /**
         * Parses a JWT from its string representation.
         */
        fun fromString(jwt: String): JWT {
            val parts = jwt.split('.')
            if (parts.size != 3) {
                throw IllegalArgumentException("Invalid JWT format")
            }
            val b64 = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)
            val header = Json.decodeFromString<Header>(b64.decode(parts[0]).decodeToString())
            val claims = Json.decodeFromString<JsonObject>(b64.decode(parts[1]).decodeToString())
            val signature = parts[2]

            return JWT(header, Claims(claims), signature)
        }
    }
}

@Serializable
data class Header(
    val alg: String,
    val typ: String,
    val kid: String? = null,
)

class Claims(private val content: JsonObject) {
    operator fun get(key: String) = content[key]

    fun getStringOrNull(key: String): String? =
        content[key]?.jsonPrimitive?.contentOrNull

    fun getInstantOrNull(key: String): Instant? =
        content[key]?.jsonPrimitive?.longOrNull?.let { Instant.fromEpochSeconds(it) }
}

/**
 * The "iss" (issuer) claim identifies the principal that issued the JWT. The processing of this
 * claim is generally application specific. The "iss" value is a case-sensitive string containing a
 * StringOrURI value.
 *
 * @see <a href="https://www.rfc-editor.org/rfc/rfc7519.html#section-4.1.1">RFC7519, Section
 *   4.1.1</a>
 */
val JWT.issuer: String?
    get() = claims.issuer

val Claims.issuer: String?
    get() = getStringOrNull("iss")

/**
 * The "sub" (subject) claim identifies the principal that is the subject of the JWT. The claims in
 * a JWT are normally statements about the subject. The subject value MUST either be scoped to be
 * locally unique in the context of the issuer or be globally unique. The processing of this claim
 * is generally application specific. The "sub" value is a case-sensitive string containing a
 * StringOrURI value.
 *
 * @see <a href="https://www.rfc-editor.org/rfc/rfc7519.html#section-4.1.2">RFC7519, Section
 *   4.1.2</a>
 */
val JWT.subject: String?
    get() = claims.subject

val Claims.subject: String?
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
 * @see <a href="https://www.rfc-editor.org/rfc/rfc7519.html#section-4.1.3">RFC7519, Section
 *   4.1.3</a>
 */
val JWT.audience: String?
    get() = claims.audience

val Claims.audience: String?
    get() = getStringOrNull("aud")

/**
 * The "exp" (expiration time) claim identifies the expiration time on or after which the JWT MUST
 * NOT be accepted for processing. The processing of the "exp" claim requires that the current
 * date/time MUST be before the expiration date/time listed in the "exp" claim. Implementers MAY
 * provide for some small leeway, usually no more than a few minutes, to account for clock skew. Its
 * value MUST be a number containing a NumericDate value.
 *
 * @see <a href="https://www.rfc-editor.org/rfc/rfc7519.html#section-4.1.4">RFC7519, Section
 *   4.1.4</a>
 */
val JWT.expiresAt: Instant
    get() = claims.expiresAt

val Claims.expiresAt: Instant
    get() = getInstantOrNull("exp") ?:
        throw IllegalArgumentException("JWT does not contain 'exp' claim or it is invalid")

/**
 * The "nbf" (not before) claim identifies the time before which the JWT MUST NOT be accepted for
 * processing. The processing of the "nbf" claim requires that the current date/time MUST be after
 * or equal to the not-before date/time listed in the "nbf" claim. Implementers MAY provide for some
 * small leeway, usually no more than a few minutes, to account for clock skew. Its value MUST be a
 * number containing a NumericDate value.
 *
 * @see <a href="https://www.rfc-editor.org/rfc/rfc7519.html#section-4.1.5">RFC7519, Section
 *   4.1.5</a>
 */
val JWT.notBefore: Instant?
    get() = claims.notBefore

val Claims.notBefore: Instant?
    get() = getInstantOrNull("nbf")

/**
 * The "iat" (issued at) claim identifies the time at which the JWT was issued. This claim can be
 * used to determine the age of the JWT. Its value MUST be a number containing a NumericDate value.
 *
 * @see <a href="https://www.rfc-editor.org/rfc/rfc7519.html#section-4.1.6">RFC7519, Section
 *   4.1.6</a>
 */
val JWT.issuedAt: Instant?
    get() = claims.issuedAt

val Claims.issuedAt: Instant?
    get() = getInstantOrNull("iat")

/**
 * The "jti" (JWT ID) claim provides a unique identifier for the JWT. The identifier value MUST be
 * assigned in a manner that ensures that there is a negligible probability that the same value will
 * be accidentally assigned to a different data object; if the application uses multiple issuers,
 * collisions MUST be prevented among values produced by different issuers as well. The "jti" claim
 * can be used to prevent the JWT from being replayed. The "jti" value is a case-sensitive string.
 *
 * @see <a href="https://www.rfc-editor.org/rfc/rfc7519.html#section-4.1.7">RFC7519, Section
 *   4.1.7</a>
 */
val JWT.id: String?
    get() = claims.id

val Claims.id: String?
    get() = get("jti")?.jsonPrimitive?.contentOrNull