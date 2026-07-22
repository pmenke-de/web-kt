package de.pmenke.webkt.util

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

/** Compatibility extensions for the former StateFlow-specific derived-value API. */
object StateFlowUtil {

    /** Convert and eagerly launch this flow as nullable state in the caller-owned [coroutineScope]. */
    fun <T> Flow<T>.launchStateIn(coroutineScope: CoroutineScope) =
        stateIn(coroutineScope, SharingStarted.Eagerly, null)

    /**
     * Maps this state as a lazy [ObservableValue].
     *
     * @see mapValue
     */
    @Deprecated("Use asObservableValue().mapValue(transform)")
    fun <T, R> StateFlow<T>.mapState(transform: (T) -> R): ObservableValue<R> =
        asObservableValue().mapValue(transform)

    /** Maps an already-derived observable value. */
    @Deprecated("Use mapValue(transform)")
    fun <T, R> ObservableValue<T>.mapState(transform: (T) -> R): ObservableValue<R> = mapValue(transform)

    /**
     * Switches to the state selected by the latest outer state.
     *
     * @see flatMapLatestValue
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Deprecated("Use asObservableValue().flatMapLatestValue { it.asObservableValue() }")
    fun <T, R> StateFlow<T>.flatMapStateLatest(
        transform: (T) -> StateFlow<R>,
    ): ObservableValue<R> = asObservableValue().flatMapLatestValue { transform(it).asObservableValue() }

    /** Combines two state flows as a scope-free [ObservableValue]. */
    @Deprecated("Adapt the inputs with asObservableValue() and use combineValues")
    fun <A, B, R> StateFlow<A>.stateCombine(
        flowB: StateFlow<B>,
        combiner: (A, B) -> R,
    ): ObservableValue<R> = asObservableValue().combineValues(flowB.asObservableValue(), combiner)

    /** Combines three state flows as a scope-free [ObservableValue]. */
    @Deprecated("Adapt the inputs with asObservableValue() and use combineValues")
    fun <A, B, C, R> StateFlow<A>.stateCombine(
        flowB: StateFlow<B>,
        flowC: StateFlow<C>,
        combiner: (A, B, C) -> R,
    ): ObservableValue<R> = asObservableValue().combineValues(
        flowB.asObservableValue(),
        flowC.asObservableValue(),
        combiner,
    )

    /** Combines four state flows as a scope-free [ObservableValue]. */
    @Deprecated("Adapt the inputs with asObservableValue() and use combineValues")
    fun <A, B, C, D, R> StateFlow<A>.stateCombine(
        flowB: StateFlow<B>,
        flowC: StateFlow<C>,
        flowD: StateFlow<D>,
        combiner: (A, B, C, D) -> R,
    ): ObservableValue<R> = asObservableValue().combineValues(
        flowB.asObservableValue(),
        flowC.asObservableValue(),
        flowD.asObservableValue(),
        combiner,
    )

    /** Combines a snapshot of state flows as a scope-free [ObservableValue]. */
    @Deprecated("Adapt the inputs with asObservableValue() and use combineValues")
    fun <T, R> Iterable<StateFlow<T>>.stateCombine(combiner: (List<T>) -> R): ObservableValue<R> =
        map { it.asObservableValue() }.combineValues(combiner)

    /** Combines two states into a pair while retaining synchronous value access. */
    @Deprecated("Adapt the inputs with asObservableValue() and use combineValues")
    operator fun <A, B> StateFlow<A>.times(other: StateFlow<B>): ObservableValue<Pair<A, B>> =
        asObservableValue().combineValues(other.asObservableValue()) { a, b -> a to b }

    /** Combines an observable value and a state flow into a pair. */
    @Deprecated("Use combineValues")
    operator fun <A, B> ObservableValue<A>.times(other: StateFlow<B>): ObservableValue<Pair<A, B>> =
        combineValues(other.asObservableValue()) { a, b -> a to b }

    /** Appends a state to an observable pair. */
    @Deprecated("Use combineValues")
    operator fun <A, B, C> ObservableValue<Pair<A, B>>.times(other: StateFlow<C>): ObservableValue<Triple<A, B, C>> =
        combineValues(other.asObservableValue()) { (a, b), c -> Triple(a, b, c) }

    /** Appends a state to an observable triple. */
    @Deprecated("Use combineValues")
    operator fun <A, B, C, D> ObservableValue<Triple<A, B, C>>.times(other: StateFlow<D>): ObservableValue<Tuple4<A, B, C, D>> =
        combineValues(other.asObservableValue()) { (a, b, c), d -> Tuple4(a, b, c, d) }

    /** Appends a state to an observable four-tuple. */
    @Deprecated("Use combineValues")
    operator fun <A, B, C, D, E> ObservableValue<Tuple4<A, B, C, D>>.times(
        other: StateFlow<E>,
    ): ObservableValue<Tuple5<A, B, C, D, E>> = combineValues(other.asObservableValue()) { (a, b, c, d), e ->
        Tuple5(a, b, c, d, e)
    }

    /** Appends a state to a pair already stored in a state flow. */
    @Deprecated("Adapt the inputs with asObservableValue() and use combineValues")
    operator fun <A, B, C> StateFlow<Pair<A, B>>.times(other: StateFlow<C>): ObservableValue<Triple<A, B, C>> =
        asObservableValue().combineValues(other.asObservableValue()) { (a, b), c -> Triple(a, b, c) }

    /** Appends a state to a triple already stored in a state flow. */
    @Deprecated("Adapt the inputs with asObservableValue() and use combineValues")
    operator fun <A, B, C, D> StateFlow<Triple<A, B, C>>.times(
        other: StateFlow<D>,
    ): ObservableValue<Tuple4<A, B, C, D>> = asObservableValue().combineValues(other.asObservableValue()) { (a, b, c), d ->
        Tuple4(a, b, c, d)
    }

    /** Appends a state to a four-tuple already stored in a state flow. */
    @Deprecated("Adapt the inputs with asObservableValue() and use combineValues")
    operator fun <A, B, C, D, E> StateFlow<Tuple4<A, B, C, D>>.times(
        other: StateFlow<E>,
    ): ObservableValue<Tuple5<A, B, C, D, E>> = asObservableValue().combineValues(other.asObservableValue()) { (a, b, c, d), e ->
        Tuple5(a, b, c, d, e)
    }
}
