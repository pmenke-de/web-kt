[![GitHub license](https://img.shields.io/badge/license-Apache%20License%202.0-green.svg?style=flat)](LICENSE.txt)

# WebKt

WebKt is a small, experimental component framework for Kotlin/Wasm browser applications. It combines the
type-safe `kotlinx.html` DSL with coroutine flows, explicit lifecycle ownership, and optional Koin-assisted
component resolution. It is a focused library rather than a complete application platform.

WebKt is pre-1.0 software. The two applications in `dist/` are compatibility fixtures and examples, but are
not part of the library build.

## What it provides

- A component tree with transactional raw-DOM rendering and deterministic lifecycles.
- DI-neutral component environments plus an optional Koin adapter.
- Render-owned and persistent child components.
- Inline components driven by `Flow`, `StateFlow`, or scope-free `ObservableValue` state.
- Closeable SPA navigation, route matching, cached server data, form bindings, filtering, and sorting.
- Small wrappers for browser DOM, JavaScript interop, logging, and unverified JWT decoding.

## Requirements

- JDK 21 or newer. The project currently builds with JDK 25.
- The checked-in Gradle wrapper.
- A browser supported by the Kotlin/Wasm toolchain.

## Build and test

```shell
./gradlew build
```

The build compiles the Wasm library and runs common and browser tests. To publish the current snapshot for a
local consumer:

```shell
./gradlew publishToMavenLocal
```

The coordinates are `de.pmenke:web-kt:0.0.1-SNAPSHOT`.

Public ABI changes are checked against the reference dump during `check`. After an intentional API change,
review the diff produced by `./gradlew updateLegacyAbi` and commit the updated dump with the implementation.

## Add the dependency

```kotlin
repositories {
    mavenCentral()
    mavenLocal() // only while consuming a locally published snapshot
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation("de.pmenke:web-kt:0.0.1-SNAPSHOT")
        }
    }
}
```

WebKt publishes dependencies exposed by its public API as API dependencies. Consumers do not need to
redeclare them merely to use WebKt types.

## Minimal Koin application

Components receive only their environment or parent. Koin remains in the construction adapter:

```kotlin
import de.pmenke.webkt.Component
import de.pmenke.webkt.ComponentEnvironment
import de.pmenke.webkt.RenderReceiver
import de.pmenke.webkt.constructComponent
import de.pmenke.webkt.koin_interop.KoinComponentEnvironment
import de.pmenke.webkt.koin_interop.getComponent
import kotlinx.browser.document
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.html.dom.createTree
import kotlinx.html.h1
import org.koin.core.context.startKoin
import org.koin.core.module.dsl.factoryOf
import org.koin.dsl.module
import web.events.addHandler
import web.window.pageHideEvent
import web.window.window

val appModule = module {
    factoryOf(::Greeting)
}

class App(environment: ComponentEnvironment) : Component(environment, "app-root") {
    override fun RenderReceiver.renderContents() {
        h1 { +"WebKt" }
        render(getComponent<Greeting>())
    }
}

class Greeting(parent: Component) : Component(parent, "app-greeting") {
    private val name = MutableStateFlow("world")

    override fun RenderReceiver.renderContents() {
        inlineFlowComponent("span", name) { +"Hello, $it!" }
    }
}
```

Create the root inside a construction transaction, render it, and close the component, its Koin scope, and the
Koin application during shutdown:

```kotlin
fun main() {
    val application = startKoin { modules(appModule) }
    val rootScope = application.koin.createScope<Component>("root-scope")
    val root = constructComponent { App(KoinComponentEnvironment(rootScope)) }

    val element = document.createTree().let { consumer ->
        root.renderTo(consumer)
        consumer.finalize()
    }
    document.body?.append(element)

    var shutdownStarted = false
    lateinit var removePageHideListener: () -> Unit
    fun shutdown() {
        if (shutdownStarted) return
        shutdownStarted = true
        var shutdownFailure: Throwable? = null
        fun attemptClose(close: () -> Unit) {
            try {
                close()
            } catch (failure: Throwable) {
                shutdownFailure?.addSuppressed(failure) ?: run { shutdownFailure = failure }
            }
        }
        attemptClose(removePageHideListener)
        attemptClose(root::close)
        attemptClose(rootScope::close)
        attemptClose(application::close)
        shutdownFailure?.let { throw it }
    }

    removePageHideListener = window.pageHideEvent.addHandler { event ->
        if (!event.persisted) shutdown()
    }
}
```

## Component lifecycle

If ownership, lifetimes, and environments are new concepts, start with
[Lifetimes, ownership, and environments](docs/lifetimes-and-ownership.md). It explains the model with concrete
examples and a decision guide.

A root `Component(environment, tagName)` has no parent. A child `Component(parent, tagName)` inherits its
parent's environment while keeping a non-null constructor parameter. Direct construction must use
`constructComponent { ... }`, which cleans up partially initialized components if construction fails.

`RenderReceiver.getComponent<T>()` resolves a render-owned Koin child. Re-rendering closes those children and
cancels their render-scoped coroutines. `Component.getComponent<T>()` resolves a persistent child owned until
the parent closes. Persistence describes ownership, not identity caching: resolve the child once into a property
or `lazy` value and render that same instance. Calling it on every render creates another persistent child.
Direct `constructComponent { Child(this) }` construction has the same ownership rule. Use the protected
component `coroutineScope` for work lasting until component closure and `RenderReceiver.coroutineScope` for work
lasting until the next render.

Register explicit cleanup with:

```kotlin
callbacks.subscribe(Component.LifecycleCallbacks.Dispose) {
    resource.dispose()
}
```

`Callbacks.notify(key)` propagates the first subscriber failure. Use `notifyCatching(key, onError = { ... })`
when notification must continue after subscriber failures.

Rendering is transactional at each component root. WebKt prepares replacement children away from the live
element, commits them only after rendering succeeds, and then closes the previous render lifetime. It does not
diff successful renders. An application update error boundary can render fallback contents explicitly:

```kotlin
override fun RenderReceiver.renderFailure(exception: Throwable): Boolean {
    p(classes = "render-error") { +"This section could not be rendered." }
    return true
}
```

Initial render failures always clean up and throw synchronously. `AfterRender` runs after commit, so callback
failure is propagated without rolling committed DOM back.

## Routing and navigation

```kotlin
val routes = route<String> {
    tag("authenticated")
    "/customers/{customerId}" {
        onSelect { parameters, tags -> "${parameters["customerId"]}:$tags" }
    }
}

val selected = routes.enter("/customers/42")
```

Route parameters are isolated per branch. Parameter segments use a non-empty `{name}` form.

`NavigatorService` exposes `path` and `hash` state flows and respects the document's `<base href>`. It
intercepts only ordinary same-origin links within that base path. The application owner must call `close()` at
shutdown to remove its document and window listeners. Closure is idempotent; navigation after shutdown begins
fails fast.

## Observable values

`ObservableValue<T>` provides synchronous `value` access and a cold `updates` flow. Creating or reading one
does not launch a coroutine; collection starts only in the caller's scope.

```kotlin
val displayName = user.asObservableValue().mapValue { it.name }
val fullName = firstName.asObservableValue().combineValues(
    lastName.asObservableValue(),
) { first, last -> "$first $last" }

val currentName = displayName.value
displayName.updates.onEach(::showName).launchIn(ownerScope)
```

`mapValue`, `flatMapLatestValue`, fixed-arity `combineValues`, and iterable `combineValues` retain synchronous
access without `stateIn`. `inlineFlowComponent` owns collection in the current render lifetime:

```kotlin
inlineFlowComponent("app-name", displayName) { name -> +name }
```

## Cached server data

`CachingFlow` composes a `values` stream with explicit cache operations; it is not itself a `Flow`:

```kotlin
val mutableUsers = MutableCachingFlow(
    supplier = { api.fetchUsers() },
    validity = 10.minutes,
)
val users: CachingFlow<Result<List<User>>> = mutableUsers.asCachingFlow()

users.values.onEach(::showUsers).launchIn(ownerScope)
```

The stream replays one current value. Its first subscriber refreshes an empty or expired cache; concurrent
automatic refresh checks are serialized. `clear()` invalidates without fetching, while `refresh()` fetches
immediately.

For keyed resources, finite non-zero keep-alive maintenance needs an explicitly owned scope:

```kotlin
val cacheOwner = CoroutineScope(SupervisorJob() + Dispatchers.Default)
val usersById = MutableCachingFlowMap(
    coroutineScope = cacheOwner,
    supplier = api::fetchUser,
    validity = 10.minutes,
    keepAlive = 10.minutes,
)

// During owner shutdown:
usersById.close()
cacheOwner.cancel()
```

Zero and infinite keep-alive modes start no maintenance coroutine but still need explicit `close()` for
deterministic entry release. Closing a map never cancels its caller-owned scope.

## Form values

`ControlValue` synchronizes a Kotlin value and a DOM property:

```kotlin
val name = ControlValue("")
inputElement.bind(name)

name.value = "Ada"
name.valueState.collect { }
name.unbind()
```

Bindings track `dirty` and `touched` state. Rebinding removes the previous listeners.

## Logging

```kotlin
LoggingConfig.setLevel("de.example", LogLevel.INFO)
LoggingConfig.setAspectLevel(LoggingAspect.HTTP_REQUEST, LogLevel.DEBUG)

private val LOG = Logger("de.example.CustomerService")
LOG.info { "loaded customer" }
```

Logging is disabled until a matching name prefix or aspect is configured. `LoggingConfig.clear()` resets the
global configuration.

## JWT safety

```kotlin
val token = UnverifiedJwt.decode(compactJwt)
val claimedSubject = token.subject
val encodedThirdSegment = token.signatureSegment
```

Decoding performs Base64URL, strict UTF-8, and JSON decoding only. It does not validate signatures,
algorithms, issuers, audiences, expiry, or any other trust property. Use a security-reviewed verifier before
treating token content as authentic or making authorization decisions.

## Documentation

- [Lifetimes, ownership, and environments](docs/lifetimes-and-ownership.md) — newcomer-oriented explanation
- [Architecture and lifecycle](docs/architecture.md)

Public APIs also carry KDoc next to their implementation.

## Status

WebKt has not reached a stable 1.0 API. Incompatible changes may still occur when they improve correctness,
lifecycle ownership, or the clarity of the public surface.

## License

Apache License 2.0. See [LICENSE.txt](LICENSE.txt).
