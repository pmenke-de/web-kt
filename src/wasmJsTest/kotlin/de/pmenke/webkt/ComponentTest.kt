package de.pmenke.webkt

import kotlinx.browser.document
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.html.div
import kotlinx.html.dom.createTree
import kotlinx.html.id
import kotlinx.html.span
import org.koin.core.KoinApplication
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.core.scope.Scope
import org.koin.core.scope.ScopeCallback
import org.w3c.dom.Element
import org.w3c.dom.HTMLElement
import kotlin.js.Promise
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ComponentTest {
    val coroutineScope = CoroutineScope(Dispatchers.Default)
    lateinit var application: KoinApplication
    lateinit var rootScope: Scope
    lateinit var testRoot: Element

    @BeforeTest
    fun setup() {
        application = startKoin { }
        rootScope = application.koin.getOrCreateScope<Unit>("_root_")
        // Create a root element for testing
        document.getElementById("test-root")?.remove()
        testRoot = document.createTree().run {
            div {
                id = "test-root"
            }
        }
        document.body!!.appendChild(testRoot)
    }

    @AfterTest
    fun teardown() {
        stopKoin()
        document.getElementById("test-root")?.remove()
    }

    @Test
    fun testFlowUpdate(): Promise<JsAny?> {
        val comp = FlowUpdateTestComponent(rootScope)
        val compElement = document.createTree().run {
            comp.renderTo(this)
            finalize()
        }
        testRoot.append(compElement)
        return coroutineScope.async {
            assertEquals(
                "<app-test><app-foo><span>0</span></app-foo></app-test>",
                testRoot.innerHTML)
            delay(150)
            assertEquals(
                "<app-test><app-foo><span>1</span></app-foo></app-test>",
                testRoot.innerHTML)
            delay(150)
            assertEquals(
                "<app-test><app-foo><span>2</span></app-foo></app-test>",
                testRoot.innerHTML)
        }.asPromise()
    }

    @Test
    fun testNestedInline() {
        var asserted = false
        class AppTest : Component(null, rootScope, "app-test") {
            override fun RenderReceiver.renderContents() {
                inlineFlowComponent("app-foo", MutableStateFlow("foo")) {
                    val appFooComponent = component
                    assertEquals(this@AppTest, component.parent)
                    inlineFlowComponent("app-bar", MutableStateFlow("bar")) {
                        assertEquals(appFooComponent, component.parent)
                        asserted = true
                    }
                }
            }
        }
        document.createTree().run {
            AppTest().renderTo(this)
            finalize()
        }
        assertEquals(true, asserted)
    }

    @Test
    fun testInlineFlowRenderCount(): Promise<JsAny?> {
        var renderCountA = 0
        var renderCountB = 0
        class AppTest : Component(null, rootScope, "app-test") {
            override fun RenderReceiver.renderContents() {
                inlineFlowComponent("app-foo", MutableStateFlow("foo")) {
                    renderCountA++
                }
                inlineFlowComponent("app-foo", flowOf(), "foo") {
                    renderCountB++
                }
            }
        }
        document.createTree().run {
            AppTest().renderTo(this)
            finalize()
        }
        return coroutineScope.async {
            delay(100)
            assertEquals(1, renderCountA, "Inline flow component (StateFlow) should render exactly once initially")
            assertEquals(1, renderCountB, "Inline flow component (empty normal Flow) should render exactly once initially")
        }.asPromise()
    }

    @Test
    fun nullableStateFlowRendersOnlyOnceInitially(): Promise<JsAny?> {
        var renderCount = 0
        class AppTest : Component(null, rootScope, "app-test") {
            override fun RenderReceiver.renderContents() {
                inlineFlowComponent("app-null", MutableStateFlow<String?>(null)) {
                    renderCount++
                }
            }
        }

        document.createTree().run {
            AppTest().renderTo(this)
            finalize()
        }

        return coroutineScope.async {
            delay(100)
            assertEquals(1, renderCount)
        }.asPromise()
    }

    @Test
    fun renderingAgainReleasesTheOldElementBackReference() {
        class AppTest : Component(null, rootScope, "app-test") {
            override fun RenderReceiver.renderContents() = Unit
        }
        val component = AppTest()
        val first = document.createTree().run { component.renderTo(this); finalize() } as HTMLElement
        val second = document.createTree().run { component.renderTo(this); finalize() } as HTMLElement

        assertNull(first.componentKt)
        assertEquals(component, second.componentKt)
    }

    @Test
    fun closingOwningKoinScopeClosesTheComponentLifetime(): Promise<JsAny?> = coroutineScope.async {
        val componentScope = application.koin.createScope<Unit>("component-lifetime-test")
        var disposeCallbacks = 0
        var renderScopeClosures = 0

        class AppTest : Component(null, componentScope, "app-test") {
            override fun RenderReceiver.renderContents() {
                scope.registerCallback(object : ScopeCallback {
                    override fun onScopeClose(scope: Scope) {
                        renderScopeClosures++
                    }
                })
            }

            fun startWork() = coroutineScope.launch(start = CoroutineStart.UNDISPATCHED) {
                awaitCancellation()
            }
        }

        val component = AppTest()
        component.callbacks.subscribe(Component.Companion.LifecycleCallbacks.Dispose) { disposeCallbacks++ }
        document.createTree().run {
            component.renderTo(this)
            finalize()
        }
        val work = component.startWork()

        componentScope.close()
        work.join()

        assertTrue(work.isCancelled)
        assertEquals(1, disposeCallbacks)
        assertEquals(1, renderScopeClosures)
        assertNull(component.currentElement)
    }.asPromise()

    @Test
    fun disposalContinuesWhenClosingTheRenderScopeFails(): Promise<JsAny?> = coroutineScope.async {
        val componentScope = application.koin.createScope<Unit>("component-disposal-failure-test")
        val callbackAfterDisposal = de.pmenke.webkt.util.CallbackKey("after-disposal")
        var callbackNotifications = 0
        var updateCount = 0
        var failRenderScopeClose = true

        class AppTest : Component(null, componentScope, "app-test") {
            override fun RenderReceiver.renderContents() {
                scope.registerCallback(object : ScopeCallback {
                    override fun onScopeClose(scope: Scope) {
                        if (failRenderScopeClose) {
                            failRenderScopeClose = false
                            error("render scope close failed")
                        }
                    }
                })
            }

            override fun updateContents() {
                updateCount++
                super.updateContents()
            }
        }

        val component = AppTest()
        component.callbacks.subscribe(callbackAfterDisposal) { callbackNotifications++ }
        val renderedElement = document.createTree().run {
            component.renderTo(this)
            finalize()
        } as HTMLElement
        component.requestUpdate()

        val failure = assertFailsWith<IllegalStateException> { componentScope.close() }
        delay(50)
        component.callbacks.notify(callbackAfterDisposal)

        assertEquals("render scope close failed", failure.message)
        assertEquals(0, updateCount)
        assertEquals(0, callbackNotifications)
        assertNull(renderedElement.componentKt)
        assertNull(component.currentElement)
    }.asPromise()
}

// a test component that generates three different states over time
// initially "0", after 100ms "1", after 200ms "2"
class FlowUpdateTestComponent(scope: Scope) : Component(null, scope, "app-test") {

    override fun RenderReceiver.renderContents() {
        val timedFlow = flow {
            delay(100)
            emit("1")
            delay(100)
            emit("2")
        }
        inlineFlowComponent("app-foo", timedFlow, "0") { timedValue ->
            span { +timedValue } // (0, 1, 2)
        }
    }
}
