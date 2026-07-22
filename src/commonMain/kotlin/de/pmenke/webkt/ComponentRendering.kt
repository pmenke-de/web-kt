package de.pmenke.webkt

import kotlinx.html.HTMLTag
import kotlinx.html.TagConsumer
import kotlinx.html.visitAndFinalize
import org.w3c.dom.Element
import org.w3c.dom.HTMLElement
import org.w3c.dom.Node

@JsFun("(element, replacement) => element.replaceChildren(replacement)")
private external fun replaceChildrenNative(element: HTMLElement, replacement: Node)

private fun materializeRootNative(
    tagName: String,
    consumer: TagConsumer<Element>,
    initialAttributes: Map<String, String>,
): HTMLElement {
    val tag = HTMLTag(tagName, consumer, initialAttributes, inlineTag = false, emptyTag = false)
    return tag.visitAndFinalize(consumer) {} as HTMLElement
}

/** Internal failure-injection seam for transactional DOM commit tests. */
internal object ComponentRenderHooks {
    var replaceChildren: (HTMLElement, Node) -> Unit = ::replaceChildrenNative
    var materializeRoot: (String, TagConsumer<Element>, Map<String, String>) -> HTMLElement =
        ::materializeRootNative

    fun reset() {
        replaceChildren = ::replaceChildrenNative
        materializeRoot = ::materializeRootNative
    }
}

/** Runs every cleanup action and rethrows all failures as one exception with suppressed causes. */
internal fun runAllRenderActions(vararg actions: () -> Unit) {
    var failure: Throwable? = null
    actions.forEach { action ->
        try {
            action()
        } catch (exception: Throwable) {
            if (failure == null) failure = exception else failure.addSuppressed(exception)
        }
    }
    failure?.let { throw it }
}

/** Carries post-commit work through nested component renders without exposing it in [RenderReceiver]. */
internal interface TransactionalRenderConsumer {
    val renderTransaction: RenderTransaction
}

/** Accumulates reversible render work until the outermost DOM operation commits. */
internal class RenderTransaction {
    private data class Entry(
        val commit: () -> Unit,
        val rollback: () -> Unit,
    )

    private val entries = mutableListOf<Entry>()

    fun checkpoint(): Int = entries.size

    fun rollbackTo(checkpoint: Int) {
        var failure: Throwable? = null
        while (entries.size > checkpoint) {
            try {
                entries.removeLast().rollback()
            } catch (exception: Throwable) {
                if (failure == null) failure = exception else failure.addSuppressed(exception)
            }
        }
        failure?.let { throw it }
    }

    fun afterCommit(block: () -> Unit) {
        entries += Entry(commit = block, rollback = {})
    }

    fun onCommit(commit: () -> Unit, rollback: () -> Unit) {
        entries += Entry(commit, rollback)
    }

    fun commit() {
        val committedEntries = entries.toList()
        entries.clear()
        var failure: Throwable? = null
        committedEntries.forEach { entry ->
            try {
                entry.commit()
            } catch (exception: Throwable) {
                if (failure == null) failure = exception else failure.addSuppressed(exception)
            }
        }
        failure?.let { throw it }
    }
}

/** Finalizes a DOM consumer while allowing a component to render no child tags. */
internal fun TagConsumer<Element>.finalizeAllowingEmptyContents() {
    try {
        finalize()
    } catch (exception: IllegalStateException) {
        // kotlinx.html rejects an empty DOM consumer even though empty component contents are valid.
        if (exception.message != "We can't finalize as there was no tags") throw exception
    }
}
