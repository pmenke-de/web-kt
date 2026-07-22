package de.pmenke.webkt.util

import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class JWTTest {
    private val base64Url = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)

    @Test
    fun parsesOptionalTypeAndBothAudienceRepresentations() {
        val singleAudience = token("""{"alg":"none","custom":"ignored"}""", """{"aud":"one","exp":123}""")
        val multipleAudiences = token("""{"alg":"none","typ":"JWT"}""", """{"aud":["one","two"]}""")

        assertNull(singleAudience.header.typ)
        assertEquals(listOf("one"), singleAudience.audiences)
        assertEquals("JWT", multipleAudiences.header.typ)
        assertEquals(listOf("one", "two"), multipleAudiences.audiences)
        assertEquals("one", multipleAudiences.audience)
    }

    @Test
    fun nonStringClaimsAreReportedAsAbsent() {
        val jwt = token("""{"alg":"none"}""", """{"iss":42,"jti":{}}""")

        assertNull(jwt.issuer)
        assertNull(jwt.id)
    }

    @Test
    fun reportsMalformedTokensConsistently() {
        assertFailsWith<IllegalArgumentException> { JWT.fromString("not-a-token") }
        assertFailsWith<IllegalArgumentException> { JWT.fromString("bad.bad.bad") }
    }

    private fun token(header: String, claims: String): JWT = JWT.fromString(
        "${base64Url.encode(header.encodeToByteArray())}.${base64Url.encode(claims.encodeToByteArray())}.signature"
    )
}
