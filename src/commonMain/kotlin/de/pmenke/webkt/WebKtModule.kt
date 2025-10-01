package de.pmenke.webkt

import de.pmenke.webkt.util.ComponentCoroutineScope
import org.koin.dsl.module

/**
 * Koin module for WebKt.
 */
val webKtModule = module {
    scope<Component> {
        scoped {
            // NOTE: The checkNotNull is just a safeguard against bugs / bad usage.
            //       A [Component] should always set itself as its scope's source-value.
            ComponentCoroutineScope(checkNotNull(getSource<Component>()) { "ComponentCoroutineScope needs a Component as source" })
        }
    }
}