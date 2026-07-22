package de.pmenke.webkt.util

import de.pmenke.webkt.Component

object ComponentUtil {

    /**
     * A sequence of all parent components, starting from the immediate parent up to the root component.
     */
    val Component.parents: Sequence<Component>
        get() = generateSequence(parent) { it.parent }

    /** Whether this component is the root of its semantic component tree. */
    val Component.isRoot: Boolean
        get() = parent == null

    /** Finds the closest parent of type [T], or `null` when no such parent exists. */
    inline fun <reified T : Component> Component.findAncestor(): T? =
        parents.filterIsInstance<T>().firstOrNull()

}
