package de.pmenke.webkt.util

import de.pmenke.webkt.util.StateFlowUtil.times
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asPromise
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals

class StateFlowUtilTest {

    @Test
    fun combineFlows() {
        val flowA = MutableStateFlow("A1")
        val flowB = MutableStateFlow("B1")
        val flowC = MutableStateFlow("C1")

        val combined = flowA * flowB * flowC

        assertEquals(Triple("A1", "B1", "C1"), combined.value)
        flowB.value = "B2"
        assertEquals(Triple("A1", "B2", "C1"), combined.value)
        flowA.value = "A2"
        assertEquals(Triple("A2", "B2", "C1"), combined.value)
        flowC.value = "C2"
        assertEquals(Triple("A2", "B2", "C2"), combined.value)
        flowB.value = "B3"
        assertEquals(Triple("A2", "B3", "C2"), combined.value)
    }

    @Test
    fun combineFlowsAsync(): Promise<JsAny?> {
        val flowA = MutableStateFlow("A1")
        val flowB = MutableStateFlow("B1")
        val flowC = MutableStateFlow("C1")

        val results = mutableListOf<Triple<String, String, String>>()
        val coroutineScope = CoroutineScope(Dispatchers.Main)
        coroutineScope.launch {
            (flowA * flowB * flowC).collect { results.add(it) }
        }
        return coroutineScope.async {
            // delay, so that we actually see all intermediate values.
            // normally we would just observe the current value
            delay(10)
            flowB.value = "B2"
            delay(10)
            flowA.value = "A2"
            delay(10)
            flowC.value = "C2"
            delay(10)
            flowB.value = "B3"
            delay(10)

            assertEquals(listOf(
                Triple("A1", "B1", "C1"),
                Triple("A1", "B2", "C1"),
                Triple("A2", "B2", "C1"),
                Triple("A2", "B2", "C2"),
                Triple("A2", "B3", "C2")
            ), results)
        }.asPromise()
    }

}