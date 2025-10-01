package de.pmenke.webkt.js_interop

import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.js.Promise

/**
 * Utility functions for interface with JS Promises.
 */
object PromiseUtil {
    // Kotlin/JS Promise extension functions
    // -> JS Promises auto-flatten nested promises
    @Suppress("UNCHECKED_CAST")
    fun <T: JsAny?, S : JsAny?> Promise<T>.flatThen(onFulfilled: ((T) -> Promise<S>)?): Promise<S>
            = this.then(onFulfilled, null) as Promise<S>

    @Suppress("UNCHECKED_CAST")
    fun <T: JsAny?, S : JsAny?> Promise<T>.flatThen(onFulfilled: ((T) -> Promise<S>)?, onFailed: ((JsAny)->Promise<S>)?): Promise<S>
            = this.then(onFulfilled, onFailed) as Promise<S>

    @Suppress("UNCHECKED_CAST")
    fun <T: JsAny?, S : JsAny?> Promise<T>.flatThen(onFulfilled: ((T) -> Promise<S>)?, onFailed: ((JsAny)->Promise<Nothing>)?): Promise<S>
            = this.then(onFulfilled, onFailed) as Promise<S>

    suspend fun <T: JsAny?> Promise<T>.await(): T = suspendCancellableCoroutine { cont: CancellableContinuation<T> ->
        this@await.then(
            onFulfilled = { cont.resume(it); null },
            onRejected = { cont.resumeWithException(it.toThrowableOrNull() ?: error("Unexpected non-Kotlin exception $it")); null }
        )
    }
}