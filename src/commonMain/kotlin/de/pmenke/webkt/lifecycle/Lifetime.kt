package de.pmenke.webkt.lifecycle

import de.pmenke.webkt.ResourceLifetime
import de.pmenke.webkt.js_interop.JsObject
import de.pmenke.webkt.js_interop.WeakReference
import js.memory.FinalizationRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/**
 * Internal owner for coroutines and resources that share a deterministic lifetime.
 *
 * Closing cancels the lifetime's coroutine job before running registered cleanup actions in
 * reverse registration order. Cleanup is attempted exactly once, even when actions close the
 * lifetime recursively or throw. The first failure is rethrown after the remaining actions have
 * run, with later failures attached as suppressed exceptions.
 */
internal class Lifetime(
    context: CoroutineContext = EmptyCoroutineContext,
    private val cancellationMessage: String = "Lifetime closed",
    finalizationCanary: JsAny? = null,
) : ResourceLifetime {
    init {
        require(context[Job] == null) {
            "A Lifetime owns an unparented Job; close it explicitly from its owning lifetime"
        }
    }

    private val job = SupervisorJob()
    private val cleanupActions = mutableListOf<CleanupRegistration>()
    private var closed = false

    /**
     * A cleanup entry which can be detached until owner-driven cleanup starts executing it.
     *
     * Clearing [cleanup] before invoking it makes closing the registration from inside its own
     * cleanup safe. It also lets an earlier cleanup detach a later snapshot entry while [close]
     * is already in progress.
     */
    private inner class CleanupRegistration(
        private var cleanup: (() -> Unit)?,
    ) : AutoCloseable {
        override fun close() {
            if (cleanup == null) return
            cleanup = null
            cleanupActions.remove(this)
        }

        fun runFromOwner() {
            val action = cleanup ?: return
            cleanup = null
            action()
        }
    }

    /*
     * The registry must not hold the component, canary, Lifetime, or Job strongly. Its held value
     * is only a Kotlin wrapper around JavaScript WeakRef<Job>, so registry reachability cannot
     * keep the owner alive. If the Job remains reachable (for example through an active coroutine),
     * finalization requests cancellation. Arbitrary cleanup stays on the deterministic close path.
     */
    private val finalizationToken = finalizationCanary?.let { canary ->
        JsObject().also { token ->
            lifetimeFinalizationRegistry.register(
                canary,
                WeakReference<Job>(job).toJsReference(),
                token,
            )
        }
    }

    override val coroutineContext: CoroutineContext = context.minusKey(Job) + job

    /** Whether [close] has started. */
    override val isClosed: Boolean
        get() = closed

    /**
     * Registers [cleanup] to run when this lifetime closes.
     *
     * If closure has already started, [cleanup] runs immediately so late registration cannot
     * leak its resource.
     */
    override fun onClose(cleanup: () -> Unit) {
        onCloseRemovable(cleanup)
    }

    /**
     * Registers [cleanup] and returns a handle which detaches it before this lifetime closes.
     *
     * This is deliberately internal: public resource cleanup remains owner-bound, while component
     * ownership needs to detach an explicitly closed child so the owner does not retain it.
     */
    internal fun onCloseRemovable(cleanup: () -> Unit): AutoCloseable {
        if (closed) {
            cleanup()
            return NoOpCloseable
        }

        return CleanupRegistration(cleanup).also(cleanupActions::add)
    }

    override fun close() {
        if (closed) return
        closed = true

        finalizationToken?.let(lifetimeFinalizationRegistry::unregister)
        job.cancel(CancellationException(cancellationMessage))

        val actions = cleanupActions.asReversed().toList()
        cleanupActions.clear()

        var failure: Throwable? = null
        for (action in actions) {
            try {
                action.runFromOwner()
            } catch (exception: Throwable) {
                if (failure == null) {
                    failure = exception
                } else {
                    failure.addSuppressed(exception)
                }
            }
        }
        failure?.let { throw it }
    }
}

private object NoOpCloseable : AutoCloseable {
    override fun close() = Unit
}

/** Cancellation-only fallback used by [lifetimeFinalizationRegistry] and its contract test. */
internal fun cancelLifetimeAfterOwnerFinalization(jobRef: WeakReference<Job>) {
    jobRef.deref()?.cancel(CancellationException("Lifetime owner finalized"))
}

private val lifetimeFinalizationRegistry = FinalizationRegistry<JsReference<WeakReference<Job>>> {
    cancelLifetimeAfterOwnerFinalization(it.get())
}
