package de.pmenke.webkt

/**
 * Constructs a component with rollback for resources created before a subclass constructor fails.
 *
 * Rendering and environment-adapter resolution establish this boundary automatically. Every direct
 * component construction must use this function; component constructors enforce that requirement so
 * rollback cannot be bypassed accidentally. A returned child and children constructed during a
 * returned component's initializer become persistent children owned by their parents.
 */
fun <T : Component> constructComponent(factory: () -> T): T =
    ComponentConstruction.run(factory) { component -> ComponentConstruction.completeStandalone(component) }

/** Single-threaded construction transaction; Kotlin/Wasm component construction is synchronous. */
internal object ComponentConstruction {
    private class Transaction {
        val provisional = mutableListOf<Component>()
    }

    private var current: Transaction? = null

    fun register(component: Component) {
        check(current != null) {
            "New-model components must be constructed with constructComponent, " +
                "during rendering, or through a component-environment adapter"
        }
        current!!.provisional.add(component)
    }

    fun release(component: Component) {
        current?.provisional?.remove(component)
    }

    /** Retains [component] and adopts children created in its successful initializer. */
    fun completeStandalone(component: Component) {
        completeAdoption(component) {
            val parent = component.parent
            if (parent == null) release(component) else parent.adoptPersistentChild(component)
        }
    }

    /**
     * Adopts [component], then persistently adopts descendants created by its initializer.
     *
     * The snapshot prevents each adoption's removal from mutating the collection being traversed.
     * Construction order guarantees parents precede their descendants.
     */
    fun completeAdoption(component: Component, adoptComponent: () -> Unit) {
        val transaction = current
        val descendants = transaction?.provisional?.filter { candidate ->
            generateSequence(candidate.parent) { it.parent }.any { it === component }
        }.orEmpty()
        adoptComponent()
        for (child in descendants) {
            if (transaction?.provisional?.contains(child) == true) {
                child.parent!!.adoptPersistentChild(child)
            }
        }
    }

    fun <T> run(block: () -> T, afterSuccess: (T) -> Unit = {}): T {
        if (current != null) {
            return block().also(afterSuccess)
        }

        val transaction = Transaction()
        current = transaction
        var failure: Throwable? = null
        try {
            return block().also(afterSuccess)
        } catch (exception: Throwable) {
            failure = exception
            throw exception
        } finally {
            current = null
            val cleanupFailure = closeProvisional(transaction)
            if (cleanupFailure != null) {
                if (failure == null) throw cleanupFailure
                failure.addSuppressed(cleanupFailure)
            }
        }
    }

    private fun closeProvisional(transaction: Transaction): Throwable? {
        var failure: Throwable? = null
        for (component in transaction.provisional.asReversed()) {
            try {
                component.close()
            } catch (exception: Throwable) {
                if (failure == null) failure = exception else failure.addSuppressed(exception)
            }
        }
        transaction.provisional.clear()
        return failure
    }
}
