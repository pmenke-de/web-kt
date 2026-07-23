# Architecture and lifecycle

WebKt is a small Kotlin/Wasm component kernel built directly on the browser DOM and `kotlinx.html`.
Its core does not depend on a dependency-injection framework. State observation, browser services, and
integration adapters are separate utilities around that kernel.

This document is a compact architecture reference. New users should first read
[Lifetimes, ownership, and environments](lifetimes-and-ownership.md), which explains the terminology and
shows how to choose an owner in application code.

## Component tree

Every component owns one custom HTML element and has either:

- no parent, when it is the root; or
- a non-null parent supplied to its child constructor.

Root and child classes therefore use different protected `Component` constructors without introducing a
second public component type:

```kotlin
class App(environment: ComponentEnvironment) : Component(environment, "app-root")

class Toolbar(parent: Component) : Component(parent, "app-toolbar")
```

The public `parent` property remains nullable because code traversing an arbitrary component can reach the
root. Child constructors need not accept a nullable parent. `parents`, `isRoot`, and `findAncestor<T>()`
provide tree traversal.

Direct construction must run inside `constructComponent { ... }`. This creates a construction transaction,
so resources belonging to a partially initialized component are closed if a subclass initializer throws.
Rendering and environment adapters establish the same boundary automatically.

## Environments and ownership

`ComponentEnvironment` is the integration boundary for a component tree. A root receives one explicitly;
the `Component(parent, ...)` base constructor automatically copies that same environment into every child.
Children can access the inherited public `environment` property, but their constructors do not need a separate
environment parameter. The environment may connect a root to an external owner after the initial render
contents and root element have been created. It also creates a `RenderEnvironment` for each render attempt.

An environment is an adapter, not an additional owner. The component kernel owns component and render
lifetimes; the environment attaches external resources to those lifetimes.

```text
application owner
├── ComponentEnvironment
└── root component lifetime
    ├── component coroutine scope and persistent children
    └── successful render lifetime
        ├── RenderEnvironment
        ├── render coroutine scope
        └── render-owned children
```

`ResourceLifetime` is the DI-neutral contract exposed to integrations. It owns a coroutine job and runs
registered cleanup in reverse order when closed. `RenderEnvironment` is an opaque, closeable integration
resource owned by one render attempt.

The Koin adapter is `KoinComponentEnvironment`. Its caller-owned Koin scope is attached to the root lifetime,
and each render gets a private Koin child-resolution scope. Components do not receive Koin scopes in their
constructors. The adapter extensions `RenderReceiver.getComponent<T>()` and `Component.getComponent<T>()`
resolve children and prepend their non-null parent to custom Koin parameters.

## Render-owned and persistent children

`RenderReceiver.getComponent<T>()` creates a render-owned child. It is closed when that successful render is
replaced or its parent closes. This is the default for components declared in `renderContents`.

`Component.getComponent<T>()` creates a persistent child owned by the parent component lifetime. Direct child
construction through `constructComponent` is persistent as well. A persistent child may be rendered during
multiple parent renders while keeping its component state; replacing a render still replaces that child's own
render lifetime. Persistent ownership does not memoize resolution: applications should resolve or construct
each persistent child once into a property or `lazy` value and reuse that instance. Creating one during every
render accumulates distinct children until the parent closes.

Closing a component deterministically closes its active render, descendants, coroutines, callbacks, and
integration resources. Component-lifetime work uses the protected `coroutineScope`; work launched through
`RenderReceiver.coroutineScope` ends when that render is replaced.

## Transactional raw-DOM rendering

WebKt has no virtual DOM and performs no diffing. It prepares a component's replacement contents in a detached
element. Only a successful attempt replaces the live element's children, after which the previous render
lifetime is closed. A failed attempt closes only its candidate lifetime and leaves the previous DOM and
lifetime active.

`AfterRender` callbacks run after the DOM commit. Their failures cannot roll the commit back. Cleanup and
callback failures are aggregated after the remaining commit actions have been attempted.

Initial render failures always clean up and throw synchronously. During an update, an application component
can override `RenderReceiver.renderFailure(Throwable)`, render fallback contents, and return `true`. Returning
`false` preserves the previous render and propagates the original failure. Unhandled animation-frame failures
are logged and rethrown for browser error reporting.

## Observable values

`ObservableValue<T>` is the scope-free derived-state abstraction. It combines synchronous field-like access
through `value` with a cold `updates` flow. Creating or reading an observable value launches no coroutine;
collection belongs to the caller's scope.

`mapValue`, `flatMapLatestValue`, fixed-arity `combineValues`, iterable `combineValues`, and
`StateFlow.asObservableValue()` preserve synchronous reads while composing update streams. Each collector
receives the current value first and then distinct changes. `inlineFlowComponent` renders the current value
synchronously and owns update collection in the current render lifetime.

## Cache composition and ownership

`CachingFlow<T>` is a cache control object, not a subtype of `Flow`. Its `values: SharedFlow<T>` property is
the only observation surface; `refresh()` and `clear()` remain explicit cache operations. This composition
prevents unrelated `SharedFlow` operations from becoming part of the cache API.

`MutableCachingFlow` serializes refreshes and rechecks freshness after acquiring its mutex. It retains one
value, refreshes an empty or expired cache when `values` gains a subscriber, and retries a cached
`Result.failure` for the next subscriber. `setValue` also establishes the new freshness timestamp.

`MutableCachingFlowMap` uses weak entries by default. Infinite keep-alive retains entries until the map closes.
A finite non-zero keep-alive requires a caller-owned, `Job`-bearing coroutine scope for maintenance. The map
uses a private supervisor: maintenance failure or `close()` does not cancel the caller's scope. Cancelling the
owner closes a finite maintained map. Zero and infinite modes run no maintenance job, so their owners must
still call `close()` for deterministic release.

## Browser boundaries

`NavigatorService` is an application-lifetime, closeable service. It exposes base-relative `path` and `hash`
state flows, preserves query strings in browser history, and intercepts only unmodified primary-button,
same-origin links within the document base path. Modified clicks, downloads, external targets, and links
outside the application retain browser-native behavior.

The navigator owns document click and window `popstate` listeners. Its owner must call `close()` at shutdown.
Closure permanently disables navigation, removes both listeners, and is idempotent. If one cleanup step fails,
a later `close()` retries only unfinished cleanup.

`ControlValue` similarly owns exact DOM listener instances. `unbind()` removes them and releases its bound DOM
property reference; rebinding first removes the previous binding.

## Trust and failure boundaries

`UnverifiedJwt.decode` performs compact-token splitting, Base64URL decoding, strict UTF-8 decoding, and JSON
parsing only. Every header and claim remains attacker-controlled. Cryptographic verification and issuer,
audience, algorithm, and time-policy enforcement belong in a separate security-reviewed verifier.

Explicit cache refresh and parsing failures propagate to callers. When a request failure is UI state rather
than an exceptional control-flow event, applications can cache `Result<T>` explicitly.
