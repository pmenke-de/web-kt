package de.pmenke.webkt.util

import de.pmenke.webkt.util.StateFlowUtil.mapState
import de.pmenke.webkt.util.StateFlowUtil.times
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asPromise
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class ObservableValueTest {

    @Test
    fun constructionAndSynchronousReadsDoNotCollectSources() {
        var subscriptions = 0
        var sourceValue = 2
        val source = object : ObservableValue<Int> {
            override val value: Int
                get() = sourceValue
            override val updates: Flow<Int> = flow {
                subscriptions++
                emit(sourceValue)
            }
        }

        val mapped = source.mapValue { it * 3 }
        val combined = mapped.combineValues(source) { a, b -> a + b }

        assertEquals(0, subscriptions)
        assertEquals(8, combined.value)
        assertEquals(0, subscriptions)
        sourceValue = 4
        assertEquals(16, combined.value)
        assertEquals(0, subscriptions)
    }

    @Test
    fun mapReusesAllocatedResultsAcrossReadsAndCollectors(): Promise<JsAny?> =
        CoroutineScope(Dispatchers.Main).async {
            val source = MutableStateFlow(1)
            var transformations = 0
            val mapped = source.asObservableValue().mapValue {
                transformations++
                Any()
            }

            assertEquals(0, transformations)
            val initial = mapped.value
            assertSame(initial, mapped.value)
            assertEquals(1, transformations)

            val firstCollector = mutableListOf<Any>()
            val secondCollector = mutableListOf<Any>()
            val firstJob = launch { mapped.updates.collect { firstCollector += it } }
            val secondJob = launch { mapped.updates.collect { secondCollector += it } }
            delay(10)

            assertEquals(1, transformations)
            assertSame(initial, firstCollector.single())
            assertSame(initial, secondCollector.single())

            source.value = 2
            delay(10)
            assertEquals(2, transformations)
            assertSame(firstCollector.last(), secondCollector.last())
            assertSame(firstCollector.last(), mapped.value)

            firstJob.cancel()
            secondJob.cancel()
        }.asPromise()

    @Test
    fun latestMappingReusesTheSelectedObservableForAnEqualOuterSnapshot(): Promise<JsAny?> =
        CoroutineScope(Dispatchers.Main).async {
            val first = MutableStateFlow(1)
            val second = MutableStateFlow(2)
            val selectFirst = MutableStateFlow(true)
            var selections = 0
            val selected = selectFirst.asObservableValue().flatMapLatestValue {
                selections++
                (if (it) first else second).asObservableValue()
            }

            assertEquals(1, selected.value)
            assertEquals(1, selections)
            val values = mutableListOf<Int>()
            val job = launch { selected.updates.collect { values += it } }
            delay(10)
            assertEquals(1, selections)

            selectFirst.value = false
            delay(10)
            assertEquals(2, selections)
            assertEquals(listOf(1, 2), values)
            job.cancel()
        }.asPromise()

    @Test
    fun fixedAndIterableCompositionShareAllocatedResults(): Promise<JsAny?> =
        CoroutineScope(Dispatchers.Main).async {
            val first = MutableStateFlow(1)
            val second = MutableStateFlow(2)
            var fixedTransforms = 0
            var iterableTransforms = 0
            val fixed = first.asObservableValue().combineValues(second.asObservableValue()) { _, _ ->
                fixedTransforms++
                Any()
            }
            val iterable = listOf(first.asObservableValue(), second.asObservableValue()).combineValues {
                iterableTransforms++
                Any()
            }

            val fixedInitial = fixed.value
            val iterableInitial = iterable.value
            val fixedUpdates = mutableListOf<Any>()
            val iterableUpdates = mutableListOf<Any>()
            val fixedJob = launch { fixed.updates.collect { fixedUpdates += it } }
            val iterableJob = launch { iterable.updates.collect { iterableUpdates += it } }
            delay(10)

            assertEquals(1, fixedTransforms)
            assertEquals(1, iterableTransforms)
            assertSame(fixedInitial, fixedUpdates.single())
            assertSame(iterableInitial, iterableUpdates.single())

            second.value = 3
            delay(10)
            assertEquals(2, fixedTransforms)
            assertEquals(2, iterableTransforms)
            assertSame(fixed.value, fixedUpdates.last())
            assertSame(iterable.value, iterableUpdates.last())

            fixedJob.cancel()
            iterableJob.cancel()
        }.asPromise()

    @Test
    fun mappedUpdatesStartCurrentAndSuppressEqualResults(): Promise<JsAny?> = CoroutineScope(Dispatchers.Main).async {
        val source = MutableStateFlow(1)
        val mapped = source.asObservableValue().mapValue { it / 2 }
        val collected = mutableListOf<Int>()
        val job = launch { mapped.updates.collect { collected += it } }

        yield()
        source.value = 2
        delay(10)
        source.value = 3 // maps to the same result as 2
        delay(10)
        source.value = 4
        delay(10)

        assertEquals(2, mapped.value)
        assertEquals(listOf(0, 1, 2), collected)
        job.cancel()
    }.asPromise()

    @Test
    fun latestMappingSwitchesInputsAndCancelsThePreviousOne(): Promise<JsAny?> =
        CoroutineScope(Dispatchers.Main).async {
            val first = MutableStateFlow(1)
            val second = MutableStateFlow(10)
            val selected = MutableStateFlow(first)
            val latest = selected.asObservableValue().flatMapLatestValue { it.asObservableValue() }
            val collected = mutableListOf<Int>()
            val job = launch { latest.updates.collect { collected += it } }

            delay(10)
            first.value = 2
            delay(10)
            selected.value = second
            delay(10)
            first.value = 3
            second.value = 11
            delay(10)

            assertEquals(11, latest.value)
            assertEquals(listOf(1, 2, 10, 11), collected)
            job.cancel()
        }.asPromise()

    @Test
    fun iterableCompositionSnapshotsInputsAndHandlesEmptyInput(): Promise<JsAny?> =
        CoroutineScope(Dispatchers.Main).async {
            val first = MutableStateFlow(1)
            val second = MutableStateFlow(2)
            val mutableInputs = mutableListOf(first.asObservableValue())
            val sum = mutableInputs.combineValues { it.sum() }
            mutableInputs += second.asObservableValue()

            val empty = emptyList<ObservableValue<Int>>().combineValues { it.sum() }
            val emptyUpdates = mutableListOf<Int>()
            val job = launch { empty.updates.collect { emptyUpdates += it } }

            yield()
            assertEquals(1, sum.value)
            second.value = 20
            assertEquals(1, sum.value)
            first.value = 3
            assertEquals(3, sum.value)
            assertEquals(0, empty.value)
            assertEquals(listOf(0), emptyUpdates)
            job.cancel()
        }.asPromise()

    @Test
    fun dynamicallySelectedInputCollectionsAreDeterministic(): Promise<JsAny?> =
        CoroutineScope(Dispatchers.Main).async {
            val first = MutableStateFlow(1)
            val second = MutableStateFlow(2)
            val selectedInputs = MutableStateFlow(listOf(first))
            val sum = selectedInputs.asObservableValue().flatMapLatestValue { inputs ->
                inputs.map { it.asObservableValue() }.combineValues { it.sum() }
            }
            val collected = mutableListOf<Int>()
            val job = launch { sum.updates.collect { collected += it } }

            delay(10)
            selectedInputs.value = emptyList()
            delay(10)
            selectedInputs.value = listOf(first, second)
            delay(10)
            second.value = 3
            delay(10)

            assertEquals(4, sum.value)
            assertEquals(listOf(1, 0, 3, 4), collected)
            job.cancel()
        }.asPromise()

    @Test
    fun fixedArityCompositionReadsAndObservesAllInputs(): Promise<JsAny?> =
        CoroutineScope(Dispatchers.Main).async {
            val first = MutableStateFlow("A")
            val second = MutableStateFlow("B")
            val third = MutableStateFlow("C")
            val fourth = MutableStateFlow("D")
            val combined = first.asObservableValue().combineValues(
                second.asObservableValue(),
                third.asObservableValue(),
                fourth.asObservableValue(),
            ) { a, b, c, d -> "$a$b$c$d" }
            val collected = mutableListOf<String>()
            val job = launch { combined.updates.collect { collected += it } }

            delay(10)
            third.value = "3"
            delay(10)

            assertEquals("AB3D", combined.value)
            assertEquals(listOf("ABCD", "AB3D"), collected)
            job.cancel()
        }.asPromise()

    @Suppress("DEPRECATION")
    @Test
    fun legacyOperatorsReturnObservableValues(): Promise<JsAny?> = CoroutineScope(Dispatchers.Main).async {
        val first = MutableStateFlow("A")
        val second = MutableStateFlow("B")
        val third = MutableStateFlow("C")
        val combined = first * second * third
        val mapped = first.mapState { it.lowercase() }
        val collected = mutableListOf<Triple<String, String, String>>()
        val job = launch { combined.updates.collect { collected += it } }

        delay(10)
        second.value = "2"
        delay(10)

        assertEquals("a", mapped.value)
        assertEquals(Triple("A", "2", "C"), combined.value)
        assertEquals(listOf(Triple("A", "B", "C"), Triple("A", "2", "C")), collected)
        job.cancel()
    }.asPromise()

    @Suppress("DEPRECATION")
    @Test
    fun legacyTimesOverloadsPreserveFlatTupleShapes() {
        val first = MutableStateFlow("A")
        val second = MutableStateFlow(2)
        val third = MutableStateFlow(true)
        val fourth = MutableStateFlow(4L)
        val fifth = MutableStateFlow('E')

        val genericObservablePair: ObservableValue<Pair<Int, Int>> =
            first.asObservableValue().mapValue { it.length } * second
        val chainedTriple: ObservableValue<Triple<String, Int, Boolean>> = first * second * third
        val observablePairTriple: ObservableValue<Triple<String, Int, Boolean>> =
            MutableStateFlow("A" to 2).asObservableValue() * third
        val statePairTriple: ObservableValue<Triple<String, Int, Boolean>> =
            MutableStateFlow("A" to 2) * third
        val stateTripleFour: ObservableValue<Tuple4<String, Int, Boolean, Long>> =
            MutableStateFlow(Triple("A", 2, true)) * fourth
        val stateTupleFive: ObservableValue<Tuple5<String, Int, Boolean, Long, Char>> =
            MutableStateFlow(Tuple4("A", 2, true, 4L)) * fifth

        assertEquals(1 to 2, genericObservablePair.value)
        assertEquals(Triple("A", 2, true), chainedTriple.value)
        assertEquals(Triple("A", 2, true), observablePairTriple.value)
        assertEquals(Triple("A", 2, true), statePairTriple.value)
        assertEquals(Tuple4("A", 2, true, 4L), stateTripleFour.value)
        assertEquals(Tuple5("A", 2, true, 4L, 'E'), stateTupleFive.value)
    }

    @Test
    fun filterControlsTrackDynamicElementsWithoutOwningAScope(): Promise<JsAny?> =
        CoroutineScope(Dispatchers.Main).async {
            val controls = FilterControls<Int>()
            val observedMatches = mutableListOf<List<Int>>()
            val job = launch {
                controls.filter.updates.collect { predicate ->
                    observedMatches += listOf(1, 2, 3, 4).filter(predicate)
                }
            }

            delay(10)
            val parity = controls.addOptionFilter(
                FilterOption("even") { it % 2 == 0 },
                FilterOption("odd") { it % 2 != 0 },
            )
            delay(10)
            parity.options.first().selected.value = true
            delay(10)

            assertEquals(listOf(2, 4), listOf(1, 2, 3, 4).filter(controls.filter.value))
            assertEquals(
                listOf(listOf(1, 2, 3, 4), listOf(1, 2, 3, 4), listOf(2, 4)),
                observedMatches,
            )
            job.cancel()
        }.asPromise()

    @Test
    fun sortControlsExposeSynchronousAndObservableComparators(): Promise<JsAny?> =
        CoroutineScope(Dispatchers.Main).async {
            val controls = SortControls<Int>()
            val sort = controls.addElement(naturalOrder())
            val observed = mutableListOf<List<Int>>()
            val job = launch {
                controls.comparator.updates.collect { comparator ->
                    observed += listOf(2, 1).sortedWith(comparator)
                }
            }

            delay(10)
            sort.cycle()
            delay(10)
            sort.cycle()
            delay(10)

            assertEquals(listOf(2, 1), listOf(2, 1).sortedWith(controls.comparator.value))
            assertEquals(listOf(listOf(2, 1), listOf(1, 2), listOf(2, 1)), observed)
            job.cancel()
        }.asPromise()
}
