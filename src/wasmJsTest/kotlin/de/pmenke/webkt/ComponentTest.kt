package de.pmenke.webkt

import de.pmenke.webkt.koin_interop.KoinComponentEnvironment
import de.pmenke.webkt.koin_interop.koinScope
import de.pmenke.webkt.util.ComponentUtil.findAncestor
import de.pmenke.webkt.util.ComponentUtil.isRoot
import de.pmenke.webkt.util.ComponentUtil.parents
import de.pmenke.webkt.util.asObservableValue
import de.pmenke.webkt.util.mapValue
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
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ComponentTest {
    val coroutineScope = CoroutineScope(Dispatchers.Default)
    lateinit var application: KoinApplication
    lateinit var testRoot: Element

    @BeforeTest
    fun setup() {
        ComponentRenderHooks.reset()
        application = startKoin { }
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
        ComponentRenderHooks.reset()
        stopKoin()
        document.getElementById("test-root")?.remove()
    }

    @Test
    fun testFlowUpdate(): Promise<JsAny?> {
        val comp = constructComponent { FlowUpdateTestComponent() }
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
        class AppTest : Component(ComponentEnvironment.Empty, "app-test") {
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
            constructComponent { AppTest() }.renderTo(this)
            finalize()
        }
        assertEquals(true, asserted)
    }

    @Test
    fun rootParentageAndAncestorTraversalAreTruthful() {
        lateinit var child: Component
        lateinit var grandchild: Component
        class AppTest : Component(ComponentEnvironment.Empty, "app-test") {
            override fun RenderReceiver.renderContents() {
                child = InlineComponent(this@AppTest, "app-child", emptyMap()) {
                    grandchild = InlineComponent(component, "app-grandchild", emptyMap()) {}
                    grandchild.renderTo(this)
                }
                child.renderTo(this)
            }
        }

        val root = constructComponent { AppTest() }
        document.createTree().run { root.renderTo(this); finalize() }

        assertNull(root.parent)
        assertTrue(root.isRoot)
        assertEquals(listOf(child, root), grandchild.parents.toList())
        assertEquals(root, grandchild.findAncestor<AppTest>())
        assertNull(root.findAncestor<AppTest>())
    }

    @Test
    fun rerenderClosesOldRenderCoroutineAndSubtreeExactlyOnce(): Promise<JsAny?> = coroutineScope.async {
        var childDisposals = 0
        lateinit var renderWork: Job

        class Child(parent: Component) : Component(parent, "app-child") {
            init {
                callbacks.subscribe(Component.Companion.LifecycleCallbacks.Dispose) { childDisposals++ }
            }

            override fun RenderReceiver.renderContents() = Unit
        }

        class AppTest : Component(ComponentEnvironment.Empty, "app-test") {
            override fun RenderReceiver.renderContents() {
                renderWork = coroutineScope.launch(start = CoroutineStart.UNDISPATCHED) { awaitCancellation() }
                Child(this@AppTest).renderTo(this)
            }

            fun rerender() = updateContents()
        }

        val component = constructComponent { AppTest() }
        document.createTree().run { component.renderTo(this); finalize() }
        val firstRenderWork = renderWork

        component.rerender()
        firstRenderWork.join()

        assertTrue(firstRenderWork.isCancelled)
        assertEquals(1, childDisposals)
        component.rerender()
        assertEquals(2, childDisposals)
    }.asPromise()

    @Test
    fun closingRenderKoinScopeClosesItsLifetimeAndSubtreeOnlyOnce(): Promise<JsAny?> = coroutineScope.async {
        val componentScope = application.koin.createScope<Unit>("external-render-scope-close-test")
        var renderScope: Scope? = null
        var renderWork: Job? = null
        var childDisposals = 0

        class Child(parent: Component) : Component(parent, "app-child") {
            init {
                callbacks.subscribe(Component.Companion.LifecycleCallbacks.Dispose) { childDisposals++ }
            }

            override fun RenderReceiver.renderContents() = Unit
        }

        class AppTest : Component(KoinComponentEnvironment(componentScope), "app-test") {
            override fun RenderReceiver.renderContents() {
                renderScope = koinScope()
                renderWork = coroutineScope.launch(start = CoroutineStart.UNDISPATCHED) { awaitCancellation() }
                Child(this@AppTest).renderTo(this)
            }

            fun rerender() = updateContents()
        }

        val component = constructComponent { AppTest() }
        document.createTree().run { component.renderTo(this); finalize() }
        val firstRenderWork = requireNotNull(renderWork)

        renderScope!!.close()
        firstRenderWork.join()

        assertTrue(firstRenderWork.isCancelled)
        assertEquals(1, childDisposals)

        component.rerender()
        assertEquals(1, childDisposals, "rerender must not dispose the already closed subtree again")
        val secondRenderWork = requireNotNull(renderWork)

        componentScope.close()
        secondRenderWork.join()

        assertTrue(secondRenderWork.isCancelled)
        assertEquals(2, childDisposals)
        componentScope.close()
        assertEquals(2, childDisposals, "owner closure must remain idempotent")
    }.asPromise()

    @Test
    fun testInlineFlowRenderCount(): Promise<JsAny?> {
        var renderCountA = 0
        var renderCountB = 0
        class AppTest : Component(ComponentEnvironment.Empty, "app-test") {
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
            constructComponent { AppTest() }.renderTo(this)
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
        class AppTest : Component(ComponentEnvironment.Empty, "app-test") {
            override fun RenderReceiver.renderContents() {
                inlineFlowComponent("app-null", MutableStateFlow<String?>(null)) {
                    renderCount++
                }
            }
        }

        document.createTree().run {
            constructComponent { AppTest() }.renderTo(this)
            finalize()
        }

        return coroutineScope.async {
            delay(100)
            assertEquals(1, renderCount)
        }.asPromise()
    }

    @Test
    fun observableValueUsesAndReleasesTheRenderLifetime(): Promise<JsAny?> = coroutineScope.async {
        val source = MutableStateFlow("first")
        var inlineRenders = 0

        class AppTest : Component(ComponentEnvironment.Empty, "app-test") {
            override fun RenderReceiver.renderContents() {
                inlineFlowComponent("app-observable", source.asObservableValue()) { current ->
                    inlineRenders++
                    span { +current }
                }
            }

            fun rerender() = updateContents()
        }

        val component = constructComponent { AppTest() }
        val rendered = document.createTree().run { component.renderTo(this); finalize() }
        testRoot.append(rendered)
        delay(30)

        assertEquals(1, inlineRenders, "the current value must not trigger a duplicate initial render")
        assertEquals(1, source.subscriptionCount.value)

        source.value = "second"
        delay(50)
        assertEquals(2, inlineRenders)
        assertTrue(testRoot.innerHTML.contains("second"))

        component.rerender()
        delay(30)
        assertEquals(3, inlineRenders)
        assertEquals(1, source.subscriptionCount.value, "the replaced render collector must be cancelled")

        component.close()
        delay(10)
        assertEquals(0, source.subscriptionCount.value)
    }.asPromise()

    @Test
    fun allocatingObservableTransformDoesNotCauseADuplicateInitialRender(): Promise<JsAny?> = coroutineScope.async {
        val source = MutableStateFlow(1)
        val functionValue = source.asObservableValue().mapValue { current -> { current } }
        var inlineRenders = 0

        class AppTest : Component(ComponentEnvironment.Empty, "app-test") {
            override fun RenderReceiver.renderContents() {
                inlineFlowComponent("app-function", functionValue) { current ->
                    inlineRenders++
                    span { +current().toString() }
                }
            }
        }

        val component = constructComponent { AppTest() }
        val rendered = document.createTree().run { component.renderTo(this); finalize() }
        testRoot.append(rendered)
        delay(30)

        assertEquals(1, inlineRenders)
        assertTrue(testRoot.innerHTML.contains(">1<"))

        source.value = 2
        delay(50)
        assertEquals(2, inlineRenders)
        assertTrue(testRoot.innerHTML.contains(">2<"))
        component.close()
    }.asPromise()

    @Test
    fun failedInitialRenderClosesPartialLifetimeAndThrowsSynchronously(): Promise<JsAny?> = coroutineScope.async {
        var childDisposals = 0
        var childAfterRenders = 0
        var afterRenders = 0
        lateinit var attemptedWork: Job

        class Child(parent: Component) : Component(parent, "app-child") {
            init {
                callbacks.subscribe(Component.Companion.LifecycleCallbacks.Dispose) { childDisposals++ }
                callbacks.subscribe(Component.Companion.LifecycleCallbacks.AfterRender) { childAfterRenders++ }
            }

            override fun RenderReceiver.renderContents() = Unit
        }

        class AppTest : Component(ComponentEnvironment.Empty, "app-test") {
            override fun RenderReceiver.renderContents() {
                attemptedWork = coroutineScope.launch(start = CoroutineStart.UNDISPATCHED) { awaitCancellation() }
                render(Child(this@AppTest))
                error("initial render failed")
            }
        }

        val component = constructComponent { AppTest() }
        component.callbacks.subscribe(Component.Companion.LifecycleCallbacks.AfterRender) { afterRenders++ }
        val failure = assertFailsWith<IllegalStateException> {
            document.createTree().run { component.renderTo(this); finalize() }
        }
        attemptedWork.join()

        assertEquals("initial render failed", failure.message)
        assertTrue(attemptedWork.isCancelled)
        assertEquals(1, childDisposals)
        assertEquals(0, childAfterRenders, "a child in a discarded parent render never commits")
        assertEquals(0, afterRenders)
        assertNull(component.currentElement)
    }.asPromise()

    @Test
    fun failedUpdateKeepsLastDomAndLifetimeAndClosesOnlyTheAttempt(): Promise<JsAny?> = coroutineScope.async {
        val renderWork = mutableListOf<Job>()
        var childDisposals = 0
        var childAfterRenders = 0
        var afterRenders = 0
        var fail = false
        var stableChildElement: HTMLElement? = null
        var attemptedChildElement: HTMLElement? = null

        class Child(parent: Component) : Component(parent, "app-child") {
            init {
                callbacks.subscribe(Component.Companion.LifecycleCallbacks.Dispose) { childDisposals++ }
                callbacks.subscribe(Component.Companion.LifecycleCallbacks.AfterRender) { childAfterRenders++ }
            }

            override fun RenderReceiver.renderContents() {
                span { +(if (fail) "attempt" else "stable") }
            }
        }

        class AppTest : Component(ComponentEnvironment.Empty, "app-test") {
            override fun RenderReceiver.renderContents() {
                renderWork += coroutineScope.launch(start = CoroutineStart.UNDISPATCHED) { awaitCancellation() }
                val child = Child(this@AppTest)
                render(child)
                if (fail) attemptedChildElement = child.currentElement else stableChildElement = child.currentElement
                if (fail) error("update failed")
            }

            fun rerender() = updateContents()
        }

        val component = constructComponent { AppTest() }
        component.callbacks.subscribe(Component.Companion.LifecycleCallbacks.AfterRender) { afterRenders++ }
        val rendered = document.createTree().run { component.renderTo(this); finalize() }
        testRoot.append(rendered)
        val stableHtml = rendered.innerHTML
        val stableWork = renderWork.single()

        fail = true
        val failure = assertFailsWith<IllegalStateException> { component.rerender() }
        renderWork.last().join()

        assertEquals("update failed", failure.message)
        assertEquals(stableHtml, rendered.innerHTML)
        assertFalse(stableWork.isCancelled, "the last successful render must remain active")
        assertTrue(renderWork.last().isCancelled, "work from the discarded attempt must be cancelled")
        assertEquals(1, childDisposals, "only the attempted child must be disposed")
        assertEquals(1, childAfterRenders, "the child in the failed attempt never commits")
        assertEquals(1, afterRenders, "failed updates must not emit AfterRender")
        assertNull(assertNotNull(attemptedChildElement).componentKt)
        assertNotNull(assertNotNull(stableChildElement).componentKt)
        assertEquals(component, (rendered as HTMLElement).componentKt)
        component.close()
        stableWork.join()
    }.asPromise()

    @Test
    fun successfulUpdateSwapsDomBeforeDisposingOldRenderAndNotifying(): Promise<JsAny?> = coroutineScope.async {
        val events = mutableListOf<String>()
        var value = "old"
        lateinit var component: Component
        var oldChildElement: HTMLElement? = null

        class Child(parent: Component, private val renderedValue: String) :
            Component(parent, "app-child") {
            init {
                callbacks.subscribe(Component.Companion.LifecycleCallbacks.Dispose) {
                    events += "dispose:${component.currentElement?.innerHTML}"
                }
            }

            override fun RenderReceiver.renderContents() {
                span { +renderedValue }
            }
        }

        class AppTest : Component(ComponentEnvironment.Empty, "app-test") {
            override fun RenderReceiver.renderContents() {
                val child = Child(this@AppTest, value)
                render(child)
                if (value == "old") oldChildElement = child.currentElement
            }

            fun rerender() = updateContents()
        }

        component = constructComponent { AppTest() }
        val rendered = document.createTree().run { component.renderTo(this); finalize() }
        testRoot.append(rendered)
        component.callbacks.subscribe(Component.Companion.LifecycleCallbacks.AfterRender) { events += "after" }

        value = "new"
        component.rerender()

        assertEquals("<app-child><span>new</span></app-child>", rendered.innerHTML)
        assertEquals(listOf("dispose:<app-child><span>new</span></app-child>", "after"), events)
        assertNull(assertNotNull(oldChildElement).componentKt)
        assertNotNull((rendered.firstChild as HTMLElement).componentKt)
        component.close()
    }.asPromise()

    @Test
    fun commitRunsLaterCleanupAndCallbacksAfterEarlierFailures() {
        val componentScope = application.koin.createScope<Unit>("render-commit-failure-test")
        val events = mutableListOf<String>()
        var version = 1
        var failOldClose = true

        class Child(parent: Component, private val childVersion: Int) :
            Component(parent, "app-child") {
            init {
                callbacks.subscribe(Component.Companion.LifecycleCallbacks.AfterRender) {
                    if (childVersion == 2) {
                        events += "nested-after"
                        error("nested callback failed")
                    }
                }
            }

            override fun RenderReceiver.renderContents() {
                span { +childVersion.toString() }
            }
        }

        class AppTest : Component(KoinComponentEnvironment(componentScope), "app-test") {
            override fun RenderReceiver.renderContents() {
                if (version == 1) {
                    koinScope().registerCallback(object : ScopeCallback {
                        override fun onScopeClose(scope: Scope) {
                            if (failOldClose) {
                                failOldClose = false
                                events += "old-close"
                                error("old render cleanup failed")
                            }
                        }
                    })
                }
                render(Child(this@AppTest, version))
            }

            fun rerender() = updateContents()
        }

        val component = constructComponent { AppTest() }
        val rendered = document.createTree().run { component.renderTo(this); finalize() }
        testRoot.append(rendered)
        component.callbacks.subscribe(Component.Companion.LifecycleCallbacks.AfterRender) {
            events += "parent-after"
        }

        version = 2
        val failure = assertFailsWith<IllegalStateException> { component.rerender() }

        assertEquals("nested callback failed", failure.message)
        assertEquals(listOf("nested-after", "old-close", "parent-after"), events)
        assertEquals("<app-child><span>2</span></app-child>", rendered.innerHTML)
        assertEquals("old render cleanup failed", failure.suppressedExceptions.single().message)
    }

    @Test
    fun nativeReplacementFailureDiscardsPreparedUpdate(): Promise<JsAny?> = coroutineScope.async {
        var value = "stable"
        val work = mutableListOf<Job>()
        var childDisposals = 0
        var attemptedChildElement: HTMLElement? = null

        class Child(parent: Component) : Component(parent, "app-child") {
            init {
                callbacks.subscribe(Component.Companion.LifecycleCallbacks.Dispose) { childDisposals++ }
            }

            override fun RenderReceiver.renderContents() {
                span { +value }
            }
        }

        class AppTest : Component(ComponentEnvironment.Empty, "app-test") {
            override fun RenderReceiver.renderContents() {
                work += coroutineScope.launch(start = CoroutineStart.UNDISPATCHED) { awaitCancellation() }
                val child = Child(this@AppTest)
                render(child)
                if (value == "candidate") attemptedChildElement = child.currentElement
            }

            fun rerender() = updateContents()
        }

        val component = constructComponent { AppTest() }
        val rendered = document.createTree().run { component.renderTo(this); finalize() }
        testRoot.append(rendered)
        val stableHtml = rendered.innerHTML
        val stableWork = work.single()
        value = "candidate"
        ComponentRenderHooks.replaceChildren = { _, _ -> error("native replacement failed") }

        val failure = try {
            assertFailsWith<IllegalStateException> { component.rerender() }
        } finally {
            ComponentRenderHooks.reset()
        }
        work.last().join()

        assertEquals("native replacement failed", failure.message)
        assertEquals(stableHtml, rendered.innerHTML)
        assertFalse(stableWork.isCancelled)
        assertTrue(work.last().isCancelled)
        assertEquals(1, childDisposals)
        assertNull(assertNotNull(attemptedChildElement).componentKt)
        component.close()
        stableWork.join()
    }.asPromise()

    @Test
    fun standaloneMaterializationFailureDiscardsNestedStateAndLifetime() {
        var childDisposals = 0
        var candidateChildElement: HTMLElement? = null

        class Child(parent: Component) : Component(parent, "app-child") {
            init {
                callbacks.subscribe(Component.Companion.LifecycleCallbacks.Dispose) { childDisposals++ }
            }

            override fun RenderReceiver.renderContents() = Unit
        }

        class AppTest : Component(ComponentEnvironment.Empty, "app-test") {
            override fun RenderReceiver.renderContents() {
                val child = Child(this@AppTest)
                render(child)
                candidateChildElement = child.currentElement
            }
        }

        val component = constructComponent { AppTest() }
        val materializeRoot = ComponentRenderHooks.materializeRoot
        ComponentRenderHooks.materializeRoot = { candidateTag, consumer, attributes ->
            if (candidateTag == "app-test") error("root materialization failed")
            materializeRoot(candidateTag, consumer, attributes)
        }
        val failure = try {
            assertFailsWith<IllegalStateException> {
                document.createTree().run { component.renderTo(this); finalize() }
            }
        } finally {
            ComponentRenderHooks.reset()
        }

        assertEquals("root materialization failed", failure.message)
        assertEquals(1, childDisposals)
        assertNull(assertNotNull(candidateChildElement).componentKt)
        assertNull(component.currentElement)
    }

    @Test
    fun unhandledFallbackRestoresTentativePersistentDescendantState() {
        var fail = false
        var childValue = "stable"
        var fallbackCandidate: HTMLElement? = null

        class PersistentChild(parent: Component) : Component(parent, "app-persistent") {
            override fun RenderReceiver.renderContents() {
                span { +childValue }
            }
        }

        lateinit var persistentChild: PersistentChild
        class Boundary : Component(ComponentEnvironment.Empty, "app-boundary") {
            override fun RenderReceiver.renderContents() {
                if (fail) error("update failed")
                render(persistentChild)
            }

            override fun RenderReceiver.renderFailure(exception: Throwable): Boolean {
                render(persistentChild)
                fallbackCandidate = persistentChild.currentElement
                return false
            }

            fun rerender() = updateContents()
        }

        val component = constructComponent { Boundary() }
        persistentChild = constructComponent { PersistentChild(component) }
        val rendered = document.createTree().run { component.renderTo(this); finalize() }
        testRoot.append(rendered)
        val stableChildElement = assertNotNull(persistentChild.currentElement)
        val stableHtml = rendered.innerHTML

        fail = true
        childValue = "candidate"
        val failure = assertFailsWith<IllegalStateException> { component.rerender() }

        assertEquals("update failed", failure.message)
        assertEquals(stableHtml, rendered.innerHTML)
        assertTrue(persistentChild.currentElement === stableChildElement)
        assertEquals(persistentChild, stableChildElement.componentKt)
        assertNull(assertNotNull(fallbackCandidate).componentKt)
    }

    @Test
    fun componentClosureDuringRenderDiscardsInitialAndUpdateAttempts(): Promise<JsAny?> = coroutineScope.async {
        lateinit var initialWork: Job
        lateinit var initial: Component

        class InitialClose : Component(ComponentEnvironment.Empty, "app-initial-close") {
            override fun RenderReceiver.renderContents() {
                initialWork = coroutineScope.launch(start = CoroutineStart.UNDISPATCHED) { awaitCancellation() }
                initial.close()
                span { +"candidate" }
            }
        }

        initial = constructComponent { InitialClose() }
        val initialFailure = assertFailsWith<IllegalStateException> {
            document.createTree().run { initial.renderTo(this); finalize() }
        }
        initialWork.join()
        assertEquals("Component '${initial.id}' was disposed while rendering", initialFailure.message)
        assertTrue(initialWork.isCancelled)
        assertNull(initial.currentElement)

        var closeDuringRender = false
        lateinit var updateWork: Job
        class UpdateClose : Component(ComponentEnvironment.Empty, "app-update-close") {
            override fun RenderReceiver.renderContents() {
                if (closeDuringRender) {
                    updateWork = coroutineScope.launch(start = CoroutineStart.UNDISPATCHED) { awaitCancellation() }
                    this@UpdateClose.close()
                    span { +"candidate" }
                } else {
                    span { +"stable" }
                }
            }

            fun rerender() = updateContents()
        }

        val update = constructComponent { UpdateClose() }
        val rendered = document.createTree().run { update.renderTo(this); finalize() } as HTMLElement
        testRoot.append(rendered)
        val stableHtml = rendered.innerHTML
        closeDuringRender = true
        val updateFailure = assertFailsWith<IllegalStateException> { update.rerender() }
        updateWork.join()

        assertEquals("Component '${update.id}' was disposed while rendering", updateFailure.message)
        assertTrue(updateWork.isCancelled)
        assertEquals(stableHtml, rendered.innerHTML)
        assertNull(rendered.componentKt)
        assertNull(update.currentElement)
    }.asPromise()

    @Test
    fun componentClosureInsideDomCommitHooksDiscardsCandidateLifetimes(): Promise<JsAny?> = coroutineScope.async {
        lateinit var initialWork: Job
        class InitialHookClose : Component(ComponentEnvironment.Empty, "app-initial-hook-close") {
            override fun RenderReceiver.renderContents() {
                initialWork = coroutineScope.launch(start = CoroutineStart.UNDISPATCHED) { awaitCancellation() }
                span { +"candidate" }
            }
        }

        val initial = constructComponent { InitialHookClose() }
        val materializeRoot = ComponentRenderHooks.materializeRoot
        ComponentRenderHooks.materializeRoot = { tagName, consumer, attributes ->
            initial.close()
            materializeRoot(tagName, consumer, attributes)
        }
        val initialFailure = try {
            assertFailsWith<IllegalStateException> {
                document.createTree().run { initial.renderTo(this); finalize() }
            }
        } finally {
            ComponentRenderHooks.reset()
        }
        initialWork.join()

        assertEquals("Component '${initial.id}' was disposed while rendering", initialFailure.message)
        assertTrue(initialWork.isCancelled)
        assertNull(initial.currentElement)

        var updating = false
        lateinit var updateWork: Job
        class UpdateHookClose : Component(ComponentEnvironment.Empty, "app-update-hook-close") {
            override fun RenderReceiver.renderContents() {
                if (updating) {
                    updateWork = coroutineScope.launch(start = CoroutineStart.UNDISPATCHED) { awaitCancellation() }
                    span { +"candidate" }
                } else {
                    span { +"stable" }
                }
            }

            fun rerender() = updateContents()
        }

        val update = constructComponent { UpdateHookClose() }
        val rendered = document.createTree().run { update.renderTo(this); finalize() } as HTMLElement
        testRoot.append(rendered)
        updating = true
        val replaceChildren = ComponentRenderHooks.replaceChildren
        ComponentRenderHooks.replaceChildren = { element, replacement ->
            update.close()
            replaceChildren(element, replacement)
        }
        val updateFailure = try {
            assertFailsWith<IllegalStateException> { update.rerender() }
        } finally {
            ComponentRenderHooks.reset()
        }
        updateWork.join()

        assertEquals("Component '${update.id}' was disposed while rendering", updateFailure.message)
        assertTrue(updateWork.isCancelled)
        assertNull(update.currentElement)
        assertNull(rendered.componentKt)
    }.asPromise()

    @Test
    fun ancestorBoundaryCanReplaceAFailedDescendantUpdateWithFallback() {
        var afterRenders = 0
        var fail = false

        class BrokenChild(parent: Component) : Component(parent, "app-broken") {
            override fun RenderReceiver.renderContents() {
                span { +(if (fail) "partial" else "stable") }
                if (fail) error("descendant failed")
            }
        }

        class Boundary : Component(ComponentEnvironment.Empty, "app-boundary") {
            override fun RenderReceiver.renderContents() {
                render(BrokenChild(this@Boundary))
            }

            override fun RenderReceiver.renderFailure(exception: Throwable): Boolean {
                assertEquals("descendant failed", exception.message)
                span { +"fallback" }
                return true
            }

            fun rerender() = updateContents()
        }

        val component = constructComponent { Boundary() }
        component.callbacks.subscribe(Component.Companion.LifecycleCallbacks.AfterRender) { afterRenders++ }
        val rendered = document.createTree().run { component.renderTo(this); finalize() }
        fail = true
        component.rerender()

        assertEquals("<span>fallback</span>", rendered.innerHTML)
        assertEquals(2, afterRenders)
    }

    @Test
    fun initialFailureIsCleanedAndNeverOfferedToAnUpdateBoundary(): Promise<JsAny?> = coroutineScope.async {
        var boundaryCalls = 0
        lateinit var attemptedWork: Job

        class Boundary : Component(ComponentEnvironment.Empty, "app-boundary") {
            override fun RenderReceiver.renderContents() {
                attemptedWork = coroutineScope.launch(start = CoroutineStart.UNDISPATCHED) { awaitCancellation() }
                error("initial failure")
            }

            override fun RenderReceiver.renderFailure(exception: Throwable): Boolean {
                boundaryCalls++
                span { +"must not render" }
                return true
            }
        }

        val component = constructComponent { Boundary() }
        val failure = assertFailsWith<IllegalStateException> {
            document.createTree().run { component.renderTo(this); finalize() }
        }
        attemptedWork.join()

        assertEquals("initial failure", failure.message)
        assertEquals(0, boundaryCalls)
        assertTrue(attemptedWork.isCancelled)
        assertNull(component.currentElement)
    }.asPromise()

    @Test
    fun failedBoundaryFallbackIsCleanedAndSurfacesBothFailures(): Promise<JsAny?> = coroutineScope.async {
        lateinit var fallbackWork: Job
        var fail = false

        class Boundary : Component(ComponentEnvironment.Empty, "app-boundary") {
            override fun RenderReceiver.renderContents() {
                if (fail) error("render failed")
                span { +"stable" }
            }

            override fun RenderReceiver.renderFailure(exception: Throwable): Boolean {
                fallbackWork = coroutineScope.launch(start = CoroutineStart.UNDISPATCHED) { awaitCancellation() }
                error("fallback failed")
            }

            fun rerender() = updateContents()
        }

        val component = constructComponent { Boundary() }
        document.createTree().run { component.renderTo(this); finalize() }
        fail = true
        val failure = assertFailsWith<IllegalStateException> {
            component.rerender()
        }
        fallbackWork.join()

        assertEquals("fallback failed", failure.message)
        assertTrue(fallbackWork.isCancelled)
        assertEquals("render failed", failure.suppressedExceptions.single().message)
    }.asPromise()

    @Test
    fun renderingAgainReleasesTheOldElementBackReference() {
        class AppTest : Component(ComponentEnvironment.Empty, "app-test") {
            override fun RenderReceiver.renderContents() = Unit
        }
        val component = constructComponent { AppTest() }
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

        class AppTest : Component(KoinComponentEnvironment(componentScope), "app-test") {
            override fun RenderReceiver.renderContents() {
                koinScope().registerCallback(object : ScopeCallback {
                    override fun onScopeClose(scope: Scope) {
                        renderScopeClosures++
                    }
                })
            }

            fun startWork() = coroutineScope.launch(start = CoroutineStart.UNDISPATCHED) {
                awaitCancellation()
            }
        }

        val component = constructComponent { AppTest() }
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

        class AppTest : Component(KoinComponentEnvironment(componentScope), "app-test") {
            override fun RenderReceiver.renderContents() {
                koinScope().registerCallback(object : ScopeCallback {
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

        val component = constructComponent { AppTest() }
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
class FlowUpdateTestComponent : Component(ComponentEnvironment.Empty, "app-test") {

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
