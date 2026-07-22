package de.pmenke.webkt.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CallbacksTest {
    @Test
    fun changesDuringNotificationApplyToTheNextNotification() {
        val callbacks = Callbacks()
        val key = CallbackKey<String>("event")
        val calls = mutableListOf<String>()
        lateinit var secondHandle: CallbackHandle

        callbacks.subscribe(key) { calls += "first:$it"; secondHandle.unsubscribe() }
        secondHandle = callbacks.subscribe(key) { calls += "second:$it" }

        callbacks.notify(key, "one")
        callbacks.notify(key, "two")

        assertEquals(listOf("first:one", "second:one", "first:two"), calls)
    }

    @Test
    fun nestedNotificationsAreSafe() {
        val callbacks = Callbacks()
        val key = CallbackKey("event")
        val calls = mutableListOf<String>()
        var nested = false

        callbacks.subscribe(key) {
            calls += if (nested) "nested" else "outer"
            if (!nested) {
                nested = true
                callbacks.notify(key)
            }
        }

        callbacks.notify(key)

        assertEquals(listOf("outer", "nested"), calls)
    }

    @Test
    fun notificationPropagatesTheFirstCallbackFailure() {
        val callbacks = Callbacks()
        val key = CallbackKey<String>("event")
        val calls = mutableListOf<String>()

        callbacks.subscribe(key) { throw IllegalStateException(it) }
        callbacks.subscribe(key) { calls += it }

        val failure = assertFailsWith<IllegalStateException> {
            callbacks.notify(key, "failed")
        }

        assertEquals("failed", failure.message)
        assertEquals(emptyList<String>(), calls)
    }

    @Test
    fun explicitErrorHandlingContinuesNotification() {
        val callbacks = Callbacks()
        val key = CallbackKey<String>("event")
        val calls = mutableListOf<String>()
        val failures = mutableListOf<String?>()

        callbacks.subscribe(key) { throw IllegalStateException(it) }
        callbacks.subscribe(key) { throw IllegalArgumentException("second:$it") }
        callbacks.subscribe(key) { calls += it }

        callbacks.notifyCatching(key, "handled") { failures += it.message }

        assertEquals(listOf("handled"), calls)
        assertEquals(listOf<String?>("handled", "second:handled"), failures)
    }

    @Suppress("DEPRECATION")
    @Test
    fun legacyPayloadErrorHandlerDelegatesToTheExplicitBehaviors() {
        val callbacks = Callbacks()
        val key = CallbackKey<String>("event")
        val failures = mutableListOf<String?>()

        callbacks.subscribe(key) { throw IllegalStateException(it) }

        callbacks.notify(key, "handled") { failures += it.message }
        val propagated = assertFailsWith<IllegalStateException> {
            callbacks.notify(key, "propagated", null)
        }

        assertEquals(listOf<String?>("handled"), failures)
        assertEquals("propagated", propagated.message)
    }
}
