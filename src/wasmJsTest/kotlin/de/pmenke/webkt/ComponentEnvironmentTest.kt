package de.pmenke.webkt

import de.pmenke.webkt.koin_interop.KoinComponentEnvironment
import de.pmenke.webkt.koin_interop.ComponentScopeHooks
import de.pmenke.webkt.koin_interop.getComponent as getKoinComponent
import de.pmenke.webkt.koin_interop.koinScope
import kotlinx.browser.document
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.html.dom.createTree
import org.koin.core.context.stopKoin
import org.koin.dsl.koinApplication
import org.koin.core.parameter.parametersOf
import org.koin.dsl.module
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ComponentEnvironmentTest {
    @AfterTest
    fun stopDependencyInjection() {
        runCatching { stopKoin() }
    }

    @Test
    fun parentOnlyChildrenInheritEnvironmentAndFollowRenderOwnership() {
        class RecordingEnvironment : ComponentEnvironment {
            var attachedComponents = 0
            var createdRenders = 0
            var closedRenders = 0

            override fun attachComponent(component: Component, lifetime: ResourceLifetime) {
                attachedComponents++
            }

            override fun createRenderEnvironment(
                component: Component,
                lifetime: ResourceLifetime,
            ): RenderEnvironment {
                createdRenders++
                return object : RenderEnvironment {
                    override fun close() {
                        closedRenders++
                    }
                }
            }
        }

        val environment = RecordingEnvironment()
        var childDisposals = 0
        var initializerDescendantDisposals = 0

        class InitializerDescendant(parent: Component) : Component(parent, "initializer-descendant") {
            init {
                callbacks.subscribe(Component.Companion.LifecycleCallbacks.Dispose) {
                    initializerDescendantDisposals++
                }
            }

            override fun RenderReceiver.renderContents() = Unit
        }

        class Child(parent: Component) : Component(parent, "app-child") {
            @Suppress("unused")
            val persistentInitializerChild = InitializerDescendant(this)

            init {
                assertSame(parent.environment, environment)
                callbacks.subscribe(Component.Companion.LifecycleCallbacks.Dispose) { childDisposals++ }
            }

            override fun RenderReceiver.renderContents() = Unit
        }

        class Root : Component(environment, "app-root") {
            override fun RenderReceiver.renderContents() {
                render(Child(this@Root))
            }

            fun rerender() = updateContents()
        }

        val root = constructComponent { Root() }
        document.createTree().run { root.renderTo(this); finalize() }

        assertEquals(1, environment.attachedComponents, "only the root attaches to the tree environment")
        assertEquals(2, environment.createdRenders)

        root.rerender()

        assertEquals(4, environment.createdRenders)
        assertEquals(2, environment.closedRenders)
        assertEquals(1, childDisposals)
        assertEquals(1, initializerDescendantDisposals)

        root.close()

        assertEquals(4, environment.closedRenders)
        assertEquals(2, childDisposals)
        assertEquals(2, initializerDescendantDisposals)
        assertEquals(null, root.currentElement)
    }

    @Test
    fun failedNewModelConstructorsCloseProvisionalComponentResources() {
        class RecordingEnvironment : ComponentEnvironment {
            var attachments = 0
            override fun attachComponent(component: Component, lifetime: ResourceLifetime) {
                attachments++
            }
        }

        val environment = RecordingEnvironment()
        var rootDisposals = 0
        lateinit var rootWork: Job

        class BrokenRoot : Component(environment, "broken-root") {
            init {
                callbacks.subscribe(Component.Companion.LifecycleCallbacks.Dispose) { rootDisposals++ }
                rootWork = coroutineScope.launch(start = CoroutineStart.UNDISPATCHED) { awaitCancellation() }
                error("root construction failed")
            }

            override fun RenderReceiver.renderContents() = Unit
        }

        val rootFailure = assertFailsWith<IllegalStateException> {
            constructComponent { BrokenRoot() }
        }
        assertEquals("root construction failed", rootFailure.message)
        assertTrue(rootWork.isCancelled)
        assertEquals(1, rootDisposals)
        assertEquals(0, environment.attachments, "a failed root is never adopted")

        var childDisposals = 0
        lateinit var childWork: Job
        class BrokenChild(parent: Component) : Component(parent, "broken-child") {
            init {
                callbacks.subscribe(Component.Companion.LifecycleCallbacks.Dispose) { childDisposals++ }
                childWork = coroutineScope.launch(start = CoroutineStart.UNDISPATCHED) { awaitCancellation() }
                error("child construction failed")
            }

            override fun RenderReceiver.renderContents() = Unit
        }
        class Root : Component(environment, "app-root") {
            override fun RenderReceiver.renderContents() {
                BrokenChild(this@Root)
            }
        }

        val root = constructComponent { Root() }
        val childFailure = assertFailsWith<IllegalStateException> {
            document.createTree().run { root.renderTo(this); finalize() }
        }
        assertEquals("child construction failed", childFailure.message)
        assertTrue(childWork.isCancelled)
        assertEquals(1, childDisposals)
        assertEquals(0, environment.attachments, "a root with a failed initial render is never adopted")
        root.close()
    }

    @Test
    fun renderEnvironmentCreationFailureClosesThePartialLifetimeAndSuccessfulCloseIsOrdered() {
        val failureOrder = mutableListOf<String>()
        lateinit var failedWork: Job
        val failingEnvironment = object : ComponentEnvironment {
            override fun createRenderEnvironment(
                component: Component,
                lifetime: ResourceLifetime,
            ): RenderEnvironment {
                failedWork = lifetime.launch(start = CoroutineStart.UNDISPATCHED) { awaitCancellation() }
                lifetime.onClose {
                    assertTrue(failedWork.isCancelled)
                    failureOrder += "partial-cleanup"
                }
                error("environment creation failed")
            }
        }
        class FailingRoot : Component(failingEnvironment, "failing-root") {
            override fun RenderReceiver.renderContents() = Unit
        }

        val failingRoot = constructComponent { FailingRoot() }
        val failure = assertFailsWith<IllegalStateException> {
            document.createTree().run { failingRoot.renderTo(this); finalize() }
        }
        assertEquals("environment creation failed", failure.message)
        assertEquals(listOf("partial-cleanup"), failureOrder)
        failingRoot.close()

        val closeOrder = mutableListOf<String>()
        lateinit var successfulWork: Job
        val successfulEnvironment = object : ComponentEnvironment {
            override fun createRenderEnvironment(
                component: Component,
                lifetime: ResourceLifetime,
            ): RenderEnvironment {
                successfulWork = lifetime.launch(start = CoroutineStart.UNDISPATCHED) { awaitCancellation() }
                lifetime.onClose { closeOrder += "earlier-cleanup" }
                return object : RenderEnvironment {
                    override fun close() {
                        assertTrue(successfulWork.isCancelled)
                        closeOrder += "render-environment"
                    }
                }
            }
        }
        class SuccessfulRoot : Component(successfulEnvironment, "successful-root") {
            override fun RenderReceiver.renderContents() = Unit
        }

        val successfulRoot = constructComponent { SuccessfulRoot() }
        document.createTree().run { successfulRoot.renderTo(this); finalize() }
        successfulRoot.close()
        assertEquals(listOf("render-environment", "earlier-cleanup"), closeOrder)
    }

    @Test
    fun koinAdapterUsesItsSuppliedKoinLinkedScopeAndCustomParameters() {
        class RootScope
        data class Dependency(val source: String)
        var initializerDescendantDisposals = 0
        class InitializerDescendant(parent: Component) : Component(parent, "koin-initializer-descendant") {
            init {
                callbacks.subscribe(Component.Companion.LifecycleCallbacks.Dispose) {
                    initializerDescendantDisposals++
                }
            }

            override fun RenderReceiver.renderContents() = Unit
        }
        class Child(
            parent: Component,
            val label: String,
            val dependency: Dependency,
        ) : Component(parent, "app-child") {
            @Suppress("unused")
            val persistentInitializerChild = InitializerDescendant(this)

            override fun RenderReceiver.renderContents() = Unit
        }

        val application = koinApplication {
            modules(module {
                scope<RootScope> {
                    scoped { Dependency("supplied-scope") }
                }
                factory { parameters ->
                    Child(parameters.get(), parameters.get(), get())
                }
            })
        }
        val rootScope = application.koin.createScope<RootScope>("environment-root")
        val renderScopeIds = mutableListOf<String>()
        val resolved = mutableListOf<Child>()

        class Root : Component(KoinComponentEnvironment(rootScope), "app-root") {
            override fun RenderReceiver.renderContents() {
                renderScopeIds += koinScope().id
                val child = if (resolved.isEmpty()) {
                    getKoinComponent<Child> { parametersOf("first") }
                } else {
                    getKoinComponent(Child::class) { parametersOf("second") }
                }
                resolved += child
                render(child)
            }

            fun rerender() = updateContents()
        }

        val root = constructComponent { Root() }
        document.createTree().run { root.renderTo(this); finalize() }
        root.rerender()

        assertEquals(2, resolved.size)
        assertSame(root, resolved[0].parent)
        assertSame(root.environment, resolved[0].environment)
        assertEquals(listOf("first", "second"), resolved.map { it.label })
        assertEquals("supplied-scope", resolved[0].dependency.source)
        assertSame(resolved[0].dependency, resolved[1].dependency)
        assertNotEquals(renderScopeIds[0], renderScopeIds[1])
        assertEquals(1, initializerDescendantDisposals)

        rootScope.close()
        assertEquals(null, root.currentElement)
        assertEquals(2, initializerDescendantDisposals)
    }

    @Test
    fun failedKoinRenderScopeConstructionClosesTheCreatedScope() {
        val application = koinApplication { }
        val rootScope = application.koin.createScope<Unit>("rollback-root")
        lateinit var createdScopeId: String
        ComponentScopeHooks.afterScopeLinked = { scope ->
            createdScopeId = scope.id
            error("scope initialization failed")
        }

        class Root : Component(KoinComponentEnvironment(rootScope), "app-root") {
            override fun RenderReceiver.renderContents() = Unit
        }

        val root = constructComponent { Root() }
        try {
            val failure = assertFailsWith<IllegalStateException> {
                document.createTree().run { root.renderTo(this); finalize() }
            }
            assertEquals("scope initialization failed", failure.message)
            assertEquals(null, application.koin.getScopeOrNull(createdScopeId))
        } finally {
            ComponentScopeHooks.reset()
            root.close()
            rootScope.close()
        }
    }

    @Test
    fun failedRootAttachmentIsTerminalButPostCommitFailureKeepsTheRootOwned() {
        var partialAttachmentCleanup = 0
        var failedRenderResourceCleanup = 0
        val rejectingEnvironment = object : ComponentEnvironment {
            override fun attachComponent(component: Component, lifetime: ResourceLifetime) {
                lifetime.onClose { partialAttachmentCleanup++ }
                error("attachment rejected")
            }

            override fun createRenderEnvironment(
                component: Component,
                lifetime: ResourceLifetime,
            ): RenderEnvironment = object : RenderEnvironment {
                override fun close() {
                    failedRenderResourceCleanup++
                }
            }
        }
        class RejectedRoot : Component(rejectingEnvironment, "rejected-root") {
            override fun RenderReceiver.renderContents() = Unit
        }

        val rejectedRoot = constructComponent { RejectedRoot() }
        val attachmentFailure = assertFailsWith<IllegalStateException> {
            document.createTree().run { rejectedRoot.renderTo(this); finalize() }
        }
        assertEquals("attachment rejected", attachmentFailure.message)
        assertEquals(1, partialAttachmentCleanup)
        assertEquals(1, failedRenderResourceCleanup)
        assertFailsWith<IllegalStateException> {
            document.createTree().run { rejectedRoot.renderTo(this); finalize() }
        }

        var attachments = 0
        val acceptingEnvironment = object : ComponentEnvironment {
            override fun attachComponent(component: Component, lifetime: ResourceLifetime) {
                attachments++
            }
        }
        class CommittedRoot : Component(acceptingEnvironment, "committed-root") {
            override fun RenderReceiver.renderContents() = Unit
        }
        val committedRoot = constructComponent {
            CommittedRoot().also { root ->
                root.callbacks.subscribe(Component.Companion.LifecycleCallbacks.AfterRender) {
                    error("post-commit failure")
                }
            }
        }
        val commitFailure = assertFailsWith<IllegalStateException> {
            document.createTree().run { committedRoot.renderTo(this); finalize() }
        }
        assertEquals("post-commit failure", commitFailure.message)
        assertEquals(1, attachments)
        assertTrue(committedRoot.currentElement != null)
        committedRoot.close()
        assertEquals(null, committedRoot.currentElement)
    }

    @Test
    fun componentResolutionOutsideRenderingCreatesAPersistentChild() {
        class Child(parent: Component) : Component(parent, "persistent-child") {
            override fun RenderReceiver.renderContents() = Unit
        }
        var brokenDisposals = 0
        lateinit var brokenWork: Job
        class BrokenChild(parent: Component) : Component(parent, "broken-persistent-child") {
            init {
                callbacks.subscribe(Component.Companion.LifecycleCallbacks.Dispose) { brokenDisposals++ }
                brokenWork = coroutineScope.launch(start = CoroutineStart.UNDISPATCHED) { awaitCancellation() }
                error("persistent construction failed")
            }

            override fun RenderReceiver.renderContents() = Unit
        }
        val application = koinApplication {
            modules(module {
                factory { parameters -> Child(parameters.get()) }
                factory { parameters -> BrokenChild(parameters.get()) }
            })
        }
        val rootScope = application.koin.createScope<Unit>("persistent-root")
        var childDisposals = 0
        var directChildDisposals = 0

        class Root : Component(KoinComponentEnvironment(rootScope), "app-root") {
            override fun RenderReceiver.renderContents() = Unit
            fun rerender() = updateContents()
        }

        val root = constructComponent { Root() }
        document.createTree().run { root.renderTo(this); finalize() }
        val child = root.getKoinComponent<Child>()
        child.callbacks.subscribe(Component.Companion.LifecycleCallbacks.Dispose) { childDisposals++ }
        val directChild = constructComponent { Child(root) }
        directChild.callbacks.subscribe(Component.Companion.LifecycleCallbacks.Dispose) { directChildDisposals++ }

        assertFailsWith<Throwable> { root.getKoinComponent<BrokenChild>() }
        assertTrue(brokenWork.isCancelled)
        assertEquals(1, brokenDisposals)

        root.rerender()
        assertEquals(0, childDisposals)
        assertEquals(0, directChildDisposals)
        root.close()
        assertEquals(1, childDisposals)
        assertEquals(1, directChildDisposals)
    }

    @Test
    fun persistentChildCanRenderAcrossParentRendersWithoutChangingOwnership() {
        var childRenders = 0
        var childDisposals = 0

        class Child(parent: Component) : Component(parent, "persistent-rendered-child") {
            init {
                callbacks.subscribe(Component.Companion.LifecycleCallbacks.Dispose) { childDisposals++ }
            }

            override fun RenderReceiver.renderContents() {
                childRenders++
            }
        }

        class Root : Component(ComponentEnvironment.Empty, "app-root") {
            val persistentChild = Child(this)

            override fun RenderReceiver.renderContents() {
                render(persistentChild)
            }

            fun rerender() = updateContents()
        }

        val root = constructComponent { Root() }
        document.createTree().run { root.renderTo(this); finalize() }
        assertEquals(1, childRenders)
        assertEquals(0, childDisposals)

        root.rerender()
        assertEquals(2, childRenders)
        assertEquals(0, childDisposals, "parent rerender must not close its persistent child")

        root.close()
        assertEquals(1, childDisposals)
        root.close()
        assertEquals(1, childDisposals, "persistent child closes exactly once with its parent")
    }

    @Test
    fun explicitlyClosedPersistentChildDetachesFromItsParentLifetime() {
        var childDisposals = 0

        class Child(parent: Component) : Component(parent, "detachable-persistent-child") {
            init {
                callbacks.subscribe(Component.Companion.LifecycleCallbacks.Dispose) { childDisposals++ }
            }

            override fun RenderReceiver.renderContents() = Unit
        }

        class Root : Component(ComponentEnvironment.Empty, "app-root") {
            val persistentChild = Child(this)

            override fun RenderReceiver.renderContents() = Unit
        }

        val root = constructComponent { Root() }
        document.createTree().run { root.renderTo(this); finalize() }

        root.persistentChild.close()
        assertEquals(1, childDisposals)

        root.close()
        assertEquals(1, childDisposals, "parent closure must remain safe after child detachment")
    }
}
