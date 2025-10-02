package de.pmenke.webkt

import kotlin.test.Test
import kotlin.test.assertEquals

class RouteTest {

    @Test
    fun testRoute() {
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
}