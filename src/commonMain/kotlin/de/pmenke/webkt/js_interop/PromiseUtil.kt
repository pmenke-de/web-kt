package de.pmenke.webkt.js_interop

import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.js.Promise

/**
 * Utility functions for interoperability with JavaScript promises.
 */
object PromiseUtil {
    // Kotlin/JS Promise extension functions
    // -> JS Promises auto-flatten nested promises
    @Suppress("UNCHECKED_CAST")
    /** Chains a promise-returning success handler, preserving JavaScript promise flattening. */
    fun <T: JsAny?, S : JsAny?> Promise<T>.flatThen(onFulfilled: ((T) -> Promise<S>)?): Promise<S>
            = this.then(onFulfilled, null) as Promise<S>

    @Suppress("UNCHECKED_CAST")
    /** Chains promise-returning success and failure handlers. */
    fun <T: JsAny?, S : JsAny?> Promise<T>.flatThen(onFulfilled: ((T) -> Promise<S>)?, onFailed: ((JsAny)->Promise<S>)?): Promise<S>
            = this.then(onFulfilled, onFailed) as Promise<S>

    @Suppress("UNCHECKED_CAST")
    /** Chains a success handler and a failure handler that never resolves successfully. */
    fun <T: JsAny?, S : JsAny?> Promise<T>.flatThen(onFulfilled: ((T) -> Promise<S>)?, onFailed: ((JsAny)->Promise<Nothing>)?): Promise<S>
            = this.then(onFulfilled, onFailed) as Promise<S>

    /** Suspends until this JavaScript promise fulfills or rejects. Cancellation cannot cancel the underlying promise. */
    suspend fun <T: JsAny?> Promise<T>.await(): T = suspendCancellableCoroutine { cont: CancellableContinuation<T> ->
        this@await.then(
            onFulfilled = { cont.resume(it); null },
            onRejected = {
                val exception = it.toThrowableOrNull()
                    ?: IllegalStateException("JavaScript promise rejected with a non-Kotlin value: $it")
                cont.resumeWithException(exception)
                null
            }
        )
    }
}
