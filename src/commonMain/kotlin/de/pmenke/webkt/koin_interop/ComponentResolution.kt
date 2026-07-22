package de.pmenke.webkt.koin_interop

import de.pmenke.webkt.Component
import de.pmenke.webkt.ComponentConstruction
import de.pmenke.webkt.RenderReceiver
import org.koin.core.parameter.ParametersDefinition
import org.koin.core.parameter.parametersOf
import org.koin.core.scope.Scope
import kotlin.reflect.KClass

/** Returns the Koin scope belonging to this render, or fails for a non-Koin environment. */
fun RenderReceiver.koinScope(): Scope =
    (environment as? KoinRenderEnvironment)?.scope
        ?: error("This render does not use a KoinComponentEnvironment")

/** Returns the caller-owned Koin scope backing this component tree. */
fun Component.koinScope(): Scope =
    (environment as? KoinComponentEnvironment)?.scope
        ?: error("This component does not use a KoinComponentEnvironment")

/**
 * Resolves a child component in the current render scope and supplies its non-null parent first.
 *
 * New component constructors accept the parent followed by their injected dependencies; they do
 * not receive a Koin [Scope]. Only after resolution returns successfully is the component adopted
 * by the current render lifetime.
 */
inline fun <reified T : Component> RenderReceiver.getComponent(
    noinline parameters: ParametersDefinition? = null,
): T = getComponent(T::class, parameters)

/** Dynamic-type counterpart of [getComponent]. */
fun <T : Component> RenderReceiver.getComponent(
    type: KClass<T>,
    parameters: ParametersDefinition? = null,
): T = ComponentConstruction.run(
    block = { koinScope().get(type, parameters = childParameters(component, parameters)) },
    afterSuccess = { it.adoptInto(this) },
)

/** Resolves a persistent child through the component tree's caller-owned Koin scope. */
inline fun <reified T : Component> Component.getComponent(
    noinline parameters: ParametersDefinition? = null,
): T = getComponent(T::class, parameters)

/** Dynamic-type counterpart of [getComponent]. */
fun <T : Component> Component.getComponent(
    type: KClass<T>,
    parameters: ParametersDefinition? = null,
): T = ComponentConstruction.run(
    block = { koinScope().get(type, parameters = childParameters(this, parameters)) },
    afterSuccess = { adoptPersistentTree(it) },
)

@PublishedApi
internal fun childParameters(
    parent: Component,
    parameters: ParametersDefinition?,
): ParametersDefinition = {
    if (parameters == null) parametersOf(parent)
    else parameters().insert(0, parent)
}
