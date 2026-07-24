package de.pmenke.webkt.testing

import de.pmenke.webkt.Component
import de.pmenke.webkt.ComponentEnvironment
import de.pmenke.webkt.RenderReceiver
import kotlinx.browser.document
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asPromise
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.html.button
import kotlinx.html.div
import kotlinx.html.id
import kotlinx.html.input
import kotlinx.html.option
import kotlinx.html.select
import kotlinx.html.span
import kotlinx.html.textArea
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.HTMLSelectElement
import org.w3c.dom.HTMLTextAreaElement
import org.w3c.dom.events.Event
import kotlin.js.Promise
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ComponentFixtureTest {
    private val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterTest
    fun cleanUp() {
        testScope.cancel()
        val containers = document.querySelectorAll("[data-webkt-fixture]")
        for (index in 0 until containers.length) {
            containers.item(index)?.let { node -> node.parentNode?.removeChild(node) }
        }
    }

    @Test
    fun rendersRootAndScopesQueries() {
        val fixture = renderRootComponent {
            object : Component(ComponentEnvironment.Empty, "fixture-root") {
                override fun RenderReceiver.renderContents() {
                    div {
                        id = "inside"
                        +"fixture contents"
                    }
                }
            }
        }

        try {
            assertEquals("fixture-root", fixture.element.tagName.lowercase())
            assertEquals("fixture contents", fixture.query("#inside").textContent)
            assertSame(fixture.element, fixture.query("fixture-root"))

            val failure = assertFailsWith<IllegalStateException> { fixture.query("#outside") }
            assertTrue(failure.message.orEmpty().contains("#outside"))
        } finally {
            fixture.close()
        }
    }

    @Test
    fun rendersChildWithSyntheticParentAndInheritedEnvironment() {
        val environment = object : ComponentEnvironment {}
        val fixture = renderChildComponent(environment) { parent ->
            object : Component(parent, "fixture-child") {
                override fun RenderReceiver.renderContents() {
                    span { +"child" }
                }
            }
        }

        try {
            assertTrue(fixture.component.parent != null)
            assertSame(environment, fixture.component.environment)
            assertSame(environment, fixture.component.parent!!.environment)
            assertEquals("child", fixture.query("span").textContent)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun clickAndInputHelpersUseDomEvents() {
        val fixture = renderRootComponent {
            object : Component(ComponentEnvironment.Empty, "event-root") {
                override fun RenderReceiver.renderContents() {
                    button {
                        id = "activate"
                        +"Activate"
                    }
                    input { id = "title" }
                    textArea { id = "description" }
                    select {
                        id = "status"
                        option {
                            value = "backlog"
                            +"Backlog"
                        }
                        option {
                            value = "done"
                            +"Done"
                        }
                    }
                }
            }
        }

        try {
            var clicks = 0
            var inputEvents = 0
            var textAreaEvents = 0
            var changeEvents = 0
            fixture.query("#activate").addEventListener("click", { clicks++ })
            fixture.query("#title").addEventListener("input", { inputEvents++ })
            fixture.query("#description").addEventListener("input", { textAreaEvents++ })
            fixture.query("#status").addEventListener("change", { changeEvents++ })

            fixture.click("#activate")
            fixture.input("#title", "Write tests")
            fixture.input("#description", "Exercise real controls")
            fixture.input("#status", "done")

            assertEquals(1, clicks)
            assertEquals(1, inputEvents)
            assertEquals(1, textAreaEvents)
            assertEquals(1, changeEvents)
            assertEquals("Write tests", fixture.queryAs<HTMLInputElement>("#title").value)
            assertEquals(
                "Exercise real controls",
                fixture.queryAs<HTMLTextAreaElement>("#description").value,
            )
            assertEquals("done", fixture.queryAs<HTMLSelectElement>("#status").value)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun dispatchesCallerCreatedEvent() {
        val fixture = renderRootComponent {
            object : Component(ComponentEnvironment.Empty, "dispatch-root") {
                override fun RenderReceiver.renderContents() {
                    div { id = "target" }
                }
            }
        }

        try {
            var received = false
            fixture.query("#target").addEventListener("fixture-event", { received = true })
            assertTrue(fixture.dispatch("#target", Event("fixture-event")))
            assertTrue(received)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun awaitsFlowDrivenDescendantRenderWithoutSleeping(): Promise<JsAny?> = testScope.async {
        val source = MutableStateFlow("first")
        val fixture = renderRootComponent {
            object : Component(ComponentEnvironment.Empty, "flow-root") {
                override fun RenderReceiver.renderContents() {
                    inlineFlowComponent("flow-value", source) { value ->
                        span { +value }
                    }
                }
            }
        }

        try {
            source.value = "second"
            fixture.awaitUntil { fixture.query("flow-value").textContent == "second" }
            assertEquals("second", fixture.query("flow-value").textContent)
        } finally {
            fixture.close()
        }
    }.asPromise()

    @Test
    fun awaitRenderSubscribesBeforeSchedulingTheUpdate(): Promise<JsAny?> = testScope.async {
        class UpdatingComponent : Component(ComponentEnvironment.Empty, "updating-root") {
            private val value = MutableStateFlow(0)

            init {
                coroutineScope.launch(start = CoroutineStart.UNDISPATCHED) {
                    value.drop(1).collect { requestUpdate() }
                }
            }

            override fun RenderReceiver.renderContents() {
                span { +value.value.toString() }
            }

            fun advance() {
                value.value += 1
            }
        }

        val fixture = renderRootComponent { UpdatingComponent() }
        try {
            fixture.awaitRender { fixture.component.advance() }
            assertEquals("1", fixture.query("span").textContent)
        } finally {
            fixture.close()
        }
    }.asPromise()

    @Test
    fun closeIsIdempotentClosesTreeOnceAndRemovesContainer() {
        var disposals = 0
        val fixture = renderRootComponent {
            object : Component(ComponentEnvironment.Empty, "closing-root") {
                init {
                    callbacks.subscribe(Component.Companion.LifecycleCallbacks.Dispose) { disposals++ }
                }

                override fun RenderReceiver.renderContents() = Unit
            }
        }
        val container = fixture.container

        fixture.close()
        fixture.close()

        assertEquals(1, disposals)
        assertNull(container.parentNode)
        assertNull(fixture.component.currentElement)
    }

    @Test
    fun failedConstructionAndRenderingRemoveTemporaryContainers() {
        val initialContainers = fixtureContainerCount()

        assertFailsWith<IllegalStateException> {
            renderRootComponent {
                object : Component(ComponentEnvironment.Empty, "construction-failure") {
                    init {
                        error("constructor failed")
                    }

                    override fun RenderReceiver.renderContents() = Unit
                }
            }
        }
        assertEquals(initialContainers, fixtureContainerCount())

        var renderFailureDisposals = 0
        assertFailsWith<IllegalStateException> {
            renderRootComponent {
                object : Component(ComponentEnvironment.Empty, "render-failure") {
                    init {
                        callbacks.subscribe(Component.Companion.LifecycleCallbacks.Dispose) {
                            renderFailureDisposals++
                        }
                    }

                    override fun RenderReceiver.renderContents() {
                        error("render failed")
                    }
                }
            }
        }

        assertEquals(initialContainers, fixtureContainerCount())
        assertEquals(1, renderFailureDisposals)
    }

    private fun fixtureContainerCount(): Int =
        document.querySelectorAll("[data-webkt-fixture]").length
}
