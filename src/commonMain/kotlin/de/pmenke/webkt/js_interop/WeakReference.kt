package de.pmenke.webkt.js_interop

// Original JS reference
private external class WeakRef(target: JsAny) {
    fun deref(): JsAny?
}

/**
 * A Kotlin wrapper for JavaScript's WeakRef, allowing weak references to objects.
 */
// Kotlin wrapper with a type parameter
class WeakReference<T : Any> private constructor(private val ref: WeakRef) {
    constructor(target: T): this(WeakRef(target.toJsReference()))
    fun deref(): T? = ref.deref()?.unsafeCast<JsReference<T>>()?.get()
}