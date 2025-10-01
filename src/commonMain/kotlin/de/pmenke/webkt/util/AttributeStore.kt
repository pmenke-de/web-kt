package de.pmenke.webkt.util

import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * A store for attributes identified by keys of type [AttributeKey].
 * Intended to be used with singleton-object keys:
 * ```
 * var MyKey : AttributeKey<String>("my-key")
 * val store: AttributeStore = ...
 * store[MyKey] = "value"
 * ```
 */
interface AttributeStore {
    operator fun <T> get(key: AttributeKey<T>): T?
    operator fun <T> set(key: AttributeKey<T>, value: T?)

    fun <T> require(key: AttributeKey<T>): T {
        return get(key) ?: throw NoSuchElementException("No value for key ${key.name} / ${key::class}")
    }
}

// NOTE: must not be a data class, as we want reference equality only
/* non-data */ class AttributeKey<T>(val name: String)

/**
 * An [AttributeStore] that supports hierarchical attribute lookup through an optional parent store.
 * If an attribute is not found in the current store, the parent store (if any) is queried.
 *
 * Modifications to attributes in this store do not affect the parent store, but can shadow them.
 */
@OptIn(ExperimentalAtomicApi::class)
class HierarchicalAttributeStore(
    private val parent: AttributeStore? = null
) : AttributeStore {
    // Note: Using lazy initialization to avoid unnecessary allocation of the map if no attributes are set.
    // Note 2: We allow null-values, so we can shadow-remove attributes from parent stores.
    private var attributes: AtomicReference<MutableMap<AttributeKey<*>, Any?>?> = AtomicReference(null)

    override fun <T> get(key: AttributeKey<T>): T? {
        @Suppress("UNCHECKED_CAST")
        return (attributes.load()?.get(key) as T?) ?: parent?.get(key)
    }

    override fun <T> set(key: AttributeKey<T>, value: T?) {
        var map = attributes.load()
        if (map == null) {
            attributes.compareAndSet(null, mutableMapOf())
            map = attributes.load()!!
        }
        map[key] = value
    }

    /**
     * Sets this key to be inherited from the parent store (removes any local value).
     */
    fun setInherited(key: AttributeKey<*>) {
        attributes.load()?.remove(key)
    }
}