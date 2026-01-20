package de.pmenke.webkt.util

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.flow.*

/**
 * Utility functions for [StateFlow]s.
 */
object StateFlowUtil {

    /**
     * Convert and launch this flow into a [StateFlow] with an initial value of `null`.
     *
     * Shorthand for [stateIn]`(coroutineScope, SharingStarted.Eagerly, null)`.
     */
    fun <T> Flow<T>.launchStateIn(coroutineScope: CoroutineScope) =
        this.stateIn(coroutineScope, SharingStarted.Eagerly, null)

    /**
     * Maps the values of this [StateFlow] using the provided [transform] function,
     * producing a new [StateFlow] of the transformed values.
     */
    @OptIn(ExperimentalForInheritanceCoroutinesApi::class)
    fun <T, R> StateFlow<T>.mapState(transform: (T) -> R): StateFlow<R> = object : StateFlow<R> {
        private var lastInput: T = this@mapState.value
        private var lastValue: R = transform(this@mapState.value)

        override val value: R
            get() = this@mapState.value.let { currentInput ->
                if (currentInput == lastInput) lastValue
                else transform(currentInput).also {
                    lastInput = currentInput
                    lastValue = it
                }
            }

        override suspend fun collect(collector: FlowCollector<R>): Nothing {
            this@mapState.collect { collector.emit(transform(it)) }
        }

        override val replayCache: List<R>
            get() = listOf(value)
    }

    /**
     * Maps the values of this [StateFlow] using the provided [transform] function,
     * which returns another [StateFlow], producing a new [StateFlow] that reflects the
     * latest values from the most recently emitted inner [StateFlow].
     */
    @OptIn(ExperimentalForInheritanceCoroutinesApi::class)
    fun <T, R> StateFlow<T>.flatMapStateLatest(transform: (T) -> StateFlow<R>): StateFlow<R> = object : StateFlow<R> {
        private var lastInput = this@flatMapStateLatest.value
        private var lastSubInput: StateFlow<R> = transform(lastInput)

        override val value: R
            get() = this@flatMapStateLatest.value.let { currentInput ->
                (if (currentInput == lastInput) lastSubInput
                else transform(currentInput).also { currentSubInput ->
                    lastInput = currentInput
                    lastSubInput = currentSubInput
                }).value
            }

        override suspend fun collect(collector: FlowCollector<R>): Nothing {
            this@flatMapStateLatest.collectLatest { transform(it).collect(collector) }
            throw IllegalStateException("unreachable")
        }

        override val replayCache: List<R>
            get() = listOf(value)
    }

    /**
     * Combines this state flow with another [StateFlow], producing a new [StateFlow] whose value is derived from the
     * latest values of both input flows using the provided [combiner] function.
     * The resulting [StateFlow] updates its value whenever either of the input flows emits a new value.
     */
    @OptIn(ExperimentalForInheritanceCoroutinesApi::class)
    fun <A, B, C> StateFlow<A>.stateCombine(flowB: StateFlow<B>, combiner: (A, B) -> C): StateFlow<C> = object : StateFlow<C> {
        private var lastInputs = this@stateCombine.value to flowB.value
        private var lastValue = combiner(lastInputs.first, lastInputs.second)
        // the getter caches the output value of the combiner, so we don't call it unnecessarily often
        override val value: C get() = (this@stateCombine.value to flowB.value).let { currentInputs ->
            if (currentInputs != lastInputs) {
                lastInputs = currentInputs
                lastValue = combiner(lastInputs.first, lastInputs.second)
            }
            lastValue
        }
        override suspend fun collect(collector: FlowCollector<C>): Nothing {
            combine(this@stateCombine, flowB) { a, b -> combiner(a, b) }.collect(collector)
            throw IllegalStateException("unreachable")
        }
        override val replayCache: List<C>
            get() = listOf(value)
    }

    /**
     * Combines this state flow with two other [StateFlow]s, producing a new [StateFlow] whose value is derived from the
     * latest values of all three input flows using the provided [combiner] function.
     * The resulting [StateFlow] updates its value whenever any of the input flows emits a new value.
     */
    @OptIn(ExperimentalForInheritanceCoroutinesApi::class)
    fun <A, B, C, D> StateFlow<A>.stateCombine(
        flowB: StateFlow<B>,
        flowC: StateFlow<C>,
        combiner: (A, B, C) -> D
    ): StateFlow<D> = object : StateFlow<D> {
        private var lastInputs = Triple(this@stateCombine.value, flowB.value, flowC.value)
        private var lastValue = combiner(lastInputs.first, lastInputs.second, lastInputs.third)
        override val value: D get() = Triple(this@stateCombine.value, flowB.value, flowC.value).let { currentInputs ->
            if (currentInputs != lastInputs) {
                lastInputs = currentInputs
                lastValue = combiner(lastInputs.first, lastInputs.second, lastInputs.third)
            }
            lastValue
        }
        override suspend fun collect(collector: FlowCollector<D>): Nothing {
            combine(this@stateCombine, flowB, flowC) { a, b, c -> combiner(a, b, c) }.collect(collector)
            throw IllegalStateException("unreachable")
        }
        override val replayCache: List<D>
            get() = listOf(value)
    }

    /**
     * Combines this state flow with three other [StateFlow]s, producing a new [StateFlow] whose value is derived from the
     * latest values of all four input flows using the provided [combiner] function.
     * The resulting [StateFlow] updates its value whenever any of the input flows emits a new value.
     */
    @OptIn(ExperimentalForInheritanceCoroutinesApi::class)
    fun <A, B, C, D, E> StateFlow<A>.stateCombine(
        flowB: StateFlow<B>,
        flowC: StateFlow<C>,
        flowD: StateFlow<D>,
        combiner: (A, B, C, D) -> E
    ): StateFlow<E> = object : StateFlow<E> {
        private var lastInputs = Tuple4(this@stateCombine.value, flowB.value, flowC.value, flowD.value)
        private var lastValue = combiner(lastInputs.first, lastInputs.second, lastInputs.third, lastInputs.fourth)
        override val value: E get() = Tuple4(this@stateCombine.value, flowB.value, flowC.value, flowD.value).let { currentInputs ->
            if (currentInputs != lastInputs) {
                lastInputs = currentInputs
                lastValue = combiner(lastInputs.first, lastInputs.second, lastInputs.third, lastInputs.fourth)
            }
            lastValue
        }
        override suspend fun collect(collector: FlowCollector<E>): Nothing {
            combine(this@stateCombine, flowB, flowC, flowD) { a, b, c, d -> combiner(a, b, c, d) }.collect(collector)
            throw IllegalStateException("unreachable")
        }
        override val replayCache: List<E>
            get() = listOf(value)
    }

    @OptIn(ExperimentalForInheritanceCoroutinesApi::class)
    fun <T, R> Iterable<StateFlow<T>>.stateCombine(combiner: (List<T>)->R): StateFlow<R> = object : StateFlow<R> {
        private var lastInputs: List<T> = this@stateCombine.map { it.value }
        private var lastValue: R = combiner(lastInputs)
        override val value: R get() = this@stateCombine.map { it.value }.let { currentInputs ->
            if (currentInputs != lastInputs) {
                lastInputs = currentInputs
                lastValue = combiner(lastInputs)
            }
            lastValue
        }
        override suspend fun collect(collector: FlowCollector<R>): Nothing {
            // combine wants reified T to construct a typed array.
            // we just trust, that Array<Any?> will do.
            @Suppress("UNCHECKED_CAST")
            combine<Any?, R>(this@stateCombine) { items ->
                combiner(items.toList() as List<T>)
            }.collect(collector)
            throw IllegalStateException("unreachable")
        }
        override val replayCache: List<R>
            get() = listOf(value)
    }

    /**
     * Combines this state flow with another [StateFlow], producing a new [StateFlow] whose value is a [Pair] of the
     * latest values from both input flows.
     * The resulting [StateFlow] updates its value whenever either of the input flows emits a new value.
     */
    operator fun <A, B> StateFlow<A>.times(other: StateFlow<B>): StateFlow<Pair<A, B>> = stateCombine(other) { a, b -> a to b }

    /**
     * Combines this state flow, which holds a [Pair], with another [StateFlow], producing a new [StateFlow] whose value is a
     * [Triple] of the values from the input flows.
     * The resulting [StateFlow] updates its value whenever either of the input flows emits a new value.
     */
    operator fun <A, B, C> StateFlow<Pair<A, B>>.times(other: StateFlow<C>): StateFlow<Triple<A, B, C>> =
        stateCombine(other) { (a, b), c -> Triple(a, b, c) }

    /**
     * Combines this state flow, which holds a [Triple], with another [StateFlow], producing a new [StateFlow] whose value is a
     * [Tuple4] of the values from the input flows.
     * The resulting [StateFlow] updates its value whenever either of the input flows emits a new value.
     */
    operator fun <A, B, C, D> StateFlow<Triple<A, B, C>>.times(other: StateFlow<D>): StateFlow<Tuple4<A, B, C, D>> =
        stateCombine(other) { (a, b, c), d -> Tuple4(a, b, c, d) }

    /**
     * Combines this state flow, which holds a [Tuple4], with another [StateFlow], producing a new [StateFlow] whose value is a
     * [Tuple5] of the values from the input flows.
     * The resulting [StateFlow] updates its value whenever either of the input flows emits a new value.
     */
    operator fun <A, B, C, D, E> StateFlow<Tuple4<A, B, C, D>>.times(other: StateFlow<E>): StateFlow<Tuple5<A, B, C, D, E>> =
        stateCombine(other) { (a, b, c, d), e -> Tuple5(a, b, c, d, e) }
}