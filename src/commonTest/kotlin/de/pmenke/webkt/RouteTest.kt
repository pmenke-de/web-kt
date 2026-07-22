package de.pmenke.webkt

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class RouteTest {

    @Test
    fun matchesNestedRoutesAndExtractsParameters() {
        val routes = route {
            route("/app") {
                route("/") {
                    onSelect { _, _ -> "A" }
                }
                route("/customers/{customerId}") {
                    route("/") {
                        onSelect { params, _ -> "B ${params["customerId"]}" }
                    }
                    route("/orders") {
                        onSelect { params, _ -> "C ${params["customerId"]}" }
                    }
                }
            }
        }

        // Testing
        assertEquals("C 123", routes.enter("/app/customers/123/orders"))
        assertEquals("B 123", routes.enter("/app/customers/123/"))
        assertEquals("A", routes.enter("/app/"))
    }

    @Test
    fun doesNotLeakParametersFromAFailedBranch() {
        val routes = route<String> {
            route("/{first}/missing") { onSelect { params, _ -> "wrong ${params["first"]}" } }
            route("/{second}/target") { onSelect { params, _ -> "right ${params["second"]} ${params["first"]}" } }
        }

        assertEquals("right value null", routes.enter("/value/target"))
    }

    @Test
    fun accumulatesTagsOnlyAlongTheSelectedBranch() {
        val routes = route<String> {
            tag("root")
            route("/a") {
                tag("a")
                route("/missing") { tag("wrong"); onSelect { _, tags -> tags.sorted().joinToString() } }
            }
            route("/a/target") {
                tag("target")
                onSelect { _, tags -> tags.sorted().joinToString() }
            }
        }

        assertEquals("root, target", routes.enter("/a/target"))
        assertNull(routes.enter("/unknown"))
    }

    @Test
    fun rejectsMalformedParameterSegments() {
        assertFailsWith<IllegalArgumentException> { Route<String>("/{}/value") }
        assertFailsWith<IllegalArgumentException> { Route<String>("/{id/value") }
        assertFailsWith<IllegalArgumentException> { Route<String>("/id}/value") }
    }
}
