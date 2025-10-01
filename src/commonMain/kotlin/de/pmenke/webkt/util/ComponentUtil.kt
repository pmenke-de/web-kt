package de.pmenke.webkt.util

import de.pmenke.webkt.Component

object ComponentUtil {

    /**
     * A sequence of all parent components, starting from the immediate parent up to the root component.
     */
    val Component.parents: Sequence<Component>
        get() = sequence {
            var current = this@parents
            while (current.parent != current) { // root is its own parent
                yield(current.parent)
                current = current.parent
            }
        }

}