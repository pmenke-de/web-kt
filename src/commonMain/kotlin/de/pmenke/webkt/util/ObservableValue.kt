package de.pmenke.webkt.util

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * A value that can be read synchronously and observed without owning a coroutine scope.
 *
 * [updates] is cold: creating an observable value and reading [value] do not launch coroutines. Every
 * collection immediately emits the current value and then distinct subsequent values. Implementations
 * should therefore derive [value] and [updates] from the same sources.
 *
 * Unlike [StateFlow], this interface is owned by WebKt and is not a mutable or hot-stream contract.
 * Convert to a long-lived [StateFlow] explicitly at the boundary that owns the required coroutine scope.
 */
interface ObservableValue<out T> {
    /** The value derived from the sources at the time it is read. */
    val value: T

    /** A lazy stream that starts with the current value and then emits distinct changes. */
    val updates: Flow<T>
}

private class DerivedObservableValue<T>(
    private val currentValue: () -> T,
    override val updates: Flow<T>,
) : ObservableValue<T> {
    override val value: T
        get() = currentValue()
}

/** Reuses one derived object while the input snapshot remains equal. */
private class SnapshotTransform<I, O>(private val transform: (I) -> O) {
    private var initialized = false
    private var lastInput: I? = null
    private var lastOutput: O? = null

    @Suppress("UNCHECKED_CAST")
    fun apply(input: I): O {
        if (initialized && input == lastInput) return lastOutput as O
        return transform(input).also { output ->
            lastInput = input
            lastOutput = output
            initialized = true
        }
    }
}

private fun <I, O> derivedObservableValue(
    currentInput: () -> I,
    inputUpdates: Flow<I>,
    transform: (I) -> O,
): ObservableValue<O> {
    // Reads and every collector intentionally share this transform cache. Besides avoiding duplicate work,
    // this keeps allocated results (notably predicates and comparators) referentially stable for one input.
    val snapshotTransform = SnapshotTransform(transform)
    return DerivedObservableValue(
        currentValue = { snapshotTransform.apply(currentInput()) },
        updates = inputUpdates.map(snapshotTransform::apply).distinctUntilChanged(),
    )
}

/** Exposes this state flow through the scope-free [ObservableValue] contract. */
fun <T> StateFlow<T>.asObservableValue(): ObservableValue<T> = DerivedObservableValue(
    currentValue = { value },
    updates = this,
)

/** Lazily maps this value, reusing the result while the input remains equal. */
fun <T, R> ObservableValue<T>.mapValue(transform: (T) -> R): ObservableValue<R> = derivedObservableValue(
    currentInput = { value },
    inputUpdates = updates,
    transform = transform,
)

/**
 * Lazily switches to the observable value selected by the latest outer value.
 *
 * Collection of a newly selected value starts with that value's current state. Collection of the previous
 * selection is cancelled. [transform] should be pure because synchronous reads and collectors invoke it
 * through a shared input-snapshot cache.
 */
@OptIn(ExperimentalCoroutinesApi::class)
fun <T, R> ObservableValue<T>.flatMapLatestValue(
    transform: (T) -> ObservableValue<R>,
): ObservableValue<R> {
    val selectedValue = SnapshotTransform(transform)
    return DerivedObservableValue(
        currentValue = { selectedValue.apply(value).value },
        updates = updates.flatMapLatest { selectedValue.apply(it).updates }.distinctUntilChanged(),
    )
}

/** Combines two observable values without starting collection until [ObservableValue.updates] is collected. */
fun <A, B, R> ObservableValue<A>.combineValues(
    other: ObservableValue<B>,
    transform: (A, B) -> R,
): ObservableValue<R> = derivedObservableValue(
    currentInput = { value to other.value },
    inputUpdates = combine(updates, other.updates) { first, second -> first to second },
    transform = { (first, second) -> transform(first, second) },
)

/** Combines three observable values while retaining synchronous access to their latest values. */
fun <A, B, C, R> ObservableValue<A>.combineValues(
    second: ObservableValue<B>,
    third: ObservableValue<C>,
    transform: (A, B, C) -> R,
): ObservableValue<R> = derivedObservableValue(
    currentInput = { Triple(value, second.value, third.value) },
    inputUpdates = combine(updates, second.updates, third.updates, ::Triple),
    transform = { (first, secondValue, thirdValue) -> transform(first, secondValue, thirdValue) },
)

/** Combines four observable values while retaining synchronous access to their latest values. */
fun <A, B, C, D, R> ObservableValue<A>.combineValues(
    second: ObservableValue<B>,
    third: ObservableValue<C>,
    fourth: ObservableValue<D>,
    transform: (A, B, C, D) -> R,
): ObservableValue<R> = derivedObservableValue(
    currentInput = { listOf(value, second.value, third.value, fourth.value) },
    inputUpdates = combine(updates, second.updates, third.updates, fourth.updates) { a,b,c,d -> listOf(a,b,c,d) },
    transform = { (first, secondValue, thirdValue, fourthValue) ->
        @Suppress("UNCHECKED_CAST")
        transform(first as A, secondValue as B, thirdValue as C, fourthValue as D)
    },
)

/**
 * Combines a snapshot of observable inputs.
 *
 * Later changes to the iterable itself are intentionally ignored. An empty snapshot is a constant observable
 * whose stream emits the transformed empty list once for every collector.
 */
fun <T, R> Iterable<ObservableValue<T>>.combineValues(
    transform: (List<T>) -> R,
): ObservableValue<R> {
    val inputs = toList()
    if (inputs.isEmpty()) {
        val constant = transform(emptyList())
        return DerivedObservableValue({ constant }, flowOf(constant))
    }

    // combine requires a homogeneous array internally. Values are returned to callers only as List<T>.
    @Suppress("UNCHECKED_CAST")
    val combinedUpdates = combine<Any?, List<T>>(inputs.map { it.updates }) { values ->
        values.toList() as List<T>
    }
    return derivedObservableValue(
        currentInput = { inputs.map { it.value } },
        inputUpdates = combinedUpdates,
        transform = transform,
    )
}
