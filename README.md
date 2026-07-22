[![GitHub license](https://img.shields.io/badge/license-Apache%20License%202.0-green.svg?style=flat)](LICENSE.txt)

# WebKt

WebKt is a small, experimental component framework for Kotlin/Wasm browser applications. It combines
the type-safe `kotlinx.html` DSL with Koin scopes and coroutine `Flow`s. It is intentionally a focused
library rather than a complete application platform.

WebKt is pre-1.0 software. The two applications in `dist/` are its compatibility fixtures and examples,
but are not part of the library build.

## What it provides

- A component tree with deterministic per-render child lifecycles.
- Inline components that update from `Flow`, `StateFlow`, and scope-free observable values.
- Koin-assisted child construction and component-local coroutine scopes.
- SPA path/hash navigation built on the browser History API.
- Route matching with path parameters and accumulated tags.
- Cached server-data flows, keyed flow caches, form bindings, filtering, and sorting.
- Small wrappers for browser DOM and JavaScript interop.

## Requirements

- JDK 21 or newer. The project currently builds with JDK 25.
- The checked-in Gradle wrapper.
- A browser supported by the Kotlin/Wasm toolchain.

## Build and test

```shell
./gradlew build
```

The build compiles the Wasm library and runs common and browser tests. To publish the current snapshot
for a local consumer:

```shell
./gradlew publishToMavenLocal
```

The coordinates are `de.pmenke:web-kt:0.0.1-SNAPSHOT`.

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

WebKt publishes its public Koin, coroutine, HTML, browser, datetime, and serialization dependencies as
API dependencies. Consumers do not need to redeclare them merely to use types exposed by WebKt.

## Minimal application

Register components as Koin factories because each position in the component tree needs its own instance:

```kotlin
val appModule = module {
    factoryOf(::Greeting)
    single<Component>(named("root-component")) {
        Root(getKoin().createScope<Component>("root-scope"))
    }
}

class Root(scope: Scope) : Component(null, scope, "app-root") {
    override fun RenderReceiver.renderContents() {
        h1 { +"WebKt" }
        render(getComponent<Greeting>())
    }
}

class Greeting(parent: Component, scope: Scope) : Component(parent, scope, "app-greeting") {
    private val name = MutableStateFlow("world")

    override fun RenderReceiver.renderContents() {
        inlineFlowComponent("span", name) { +"Hello, $it!" }
    }
}
```

Render and insert the root element:

```kotlin
val application = startKoin { modules(appModule) }
val root = application.koin.get<Component>(named("root-component"))
val element = document.createTree().let { consumer ->
    root.renderTo(consumer)
    consumer.finalize()
}
document.body?.append(element)
```

## Component lifecycle

Every `Component` belongs to the Koin `Scope` passed to its constructor. Closing that scope disposes the
component. Each render creates a shorter-lived scope for children obtained through
`RenderReceiver.getComponent`; re-rendering closes the previous render scope and therefore disposes those
children and cancels their render-scoped coroutines.

Use the protected component `coroutineScope` for work that should live as long as the component. Use the
`RenderReceiver.coroutineScope` for work created inside `renderContents` that should stop on the next
render. Register explicit resource cleanup with:

```kotlin
callbacks.subscribe(Component.LifecycleCallbacks.Dispose) {
    resource.dispose()
}
```

`Callbacks.notify(key)` propagates the first subscriber failure. Code that must continue notifying the
remaining subscribers can opt into explicit error handling with
`Callbacks.notifyCatching(key, onError = { ... })`. The distinct method name prevents a trailing lambda
on a parameterless notification from being mistaken for a subscription.

`AfterRender` runs after the element has been created or updated. During the first call, the caller may
not yet have inserted the returned element into the document.

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

Route parameters are isolated per branch, so a failed candidate cannot leak values into the selected
route. Parameter segments must use a non-empty `{name}` form.

`NavigatorService` exposes `path` and `hash` state flows and respects the document's `<base href>`. It
intercepts only unmodified, primary-button, same-origin links within that base path. Modified clicks,
downloads, external targets, and links outside the application remain browser-native.

## Observable values

`ObservableValue<T>` represents derived UI state that reads synchronously through `value` and can be
observed lazily through `updates`. Creating or reading one does not launch a coroutine; collection begins
only when a caller with a suitable lifetime collects `updates`.

```kotlin
import de.pmenke.webkt.util.asObservableValue
import de.pmenke.webkt.util.combineValues
import de.pmenke.webkt.util.mapValue

val displayName = user.asObservableValue().mapValue { it.name }
val combined = firstName.asObservableValue().combineValues(
    lastName.asObservableValue(),
) { first, last -> "$first $last" }

val currentName = displayName.value
displayName.updates.onEach(::showName).launchIn(ownerScope)
```

`mapValue`, `flatMapLatestValue`, fixed-arity `combineValues`, and iterable `combineValues` retain
synchronous access without `stateIn`. Every update collector receives the current value first and then
distinct changes. Mapping functions should be pure; they run when the input snapshot changes, while equal
snapshots reuse the same derived result across direct reads and collectors. `inlineFlowComponent` owns
collection in its current render lifetime:

```kotlin
inlineFlowComponent("app-name", displayName) { name -> +name }
```

The older `StateFlowUtil` mapping and combination helpers remain as deprecated source adapters. They now
return `ObservableValue`, so use `.updates` when passing their result to ordinary Flow operators.

## Cached server data

```kotlin
val mutableUsers = MutableCachingFlow(
    supplier = { api.fetchUsers() },
    validity = 10.minutes,
)
val users: CachingFlow<Result<List<User>>> = mutableUsers.asCachingFlow()
```

A caching flow replays only its current value. The first subscriber refreshes an empty or expired cache;
concurrent automatic refresh checks are serialized. A cached `Result.failure` is retried for the next
subscriber. `clear()` invalidates without fetching, while `refresh()` fetches immediately.

Use `MutableCachingFlowMap` for keyed resources. `keepAlive` controls how long the map keeps a strong
reference to an otherwise unused entry; zero uses weak entries immediately and infinity keeps them for the
map's lifetime.

## Form values

`ControlValue` synchronizes a Kotlin value and a DOM property:

```kotlin
val name = ControlValue("")
inputElement.bind(name)

name.value = "Ada"          // updates the DOM and marks the value dirty
name.valueState.collect { } // observes DOM and Kotlin changes
name.unbind()                // removes listeners and releases the element
```

Bindings track `dirty` and `touched` state. Rebinding automatically removes the previous listeners.

## Logging

Logging is disabled until a matching name prefix or aspect is configured:

```kotlin
LoggingConfig.setLevel("de.example", LogLevel.INFO)
LoggingConfig.setAspectLevel(LoggingAspect.HTTP_REQUEST, LogLevel.DEBUG)

private val LOG = Logger("de.example.CustomerService")
LOG.info { "loaded customer" }
```

`LoggingConfig.clear()` resets global logging configuration, which is useful between tests or applications
sharing the same page.

## JWT safety

`JWT.fromString` is a decoder, not a verifier. It does not validate signatures, algorithms, issuers,
audiences, expiry, or any other trust property. Use a security-reviewed verifier before treating token
content as authentic. The `audiences` property supports both standards-compliant string and array claims.

## Documentation

- [Architecture and lifecycle](docs/architecture.md)
- [Downstream compatibility and migration notes](docs/downstream-compatibility.md)

Public APIs also carry KDoc next to their implementation. The repository intentionally keeps documentation
close to the behavior it describes.

## Status and compatibility

WebKt uses semantic-version-shaped coordinates but has not reached a stable API. Before 1.0, incompatible
changes may occur when they fix lifecycle or correctness defects. Such changes must be recorded in the
downstream compatibility document. The current version remains source-compatible with the two checked-in
consumers; identified consumer-side issues are documented rather than edited.

## License

Apache License 2.0. See [LICENSE.txt](LICENSE.txt).
