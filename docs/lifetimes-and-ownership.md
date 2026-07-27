# Lifetimes, ownership, and environments

This guide explains the three ideas that control cleanup in WebKt:

- **ownership** answers “who is responsible for closing this?”;
- a **lifetime** groups work and resources that must end together; and
- an **environment** connects the component tree to an external integration such as Koin.

The names are abstract, but the rule behind them is simple:

> Create work under the owner that should stop it.

If a coroutine, child component, event listener, or integration resource belongs to a component, WebKt
should be able to reach and close it through that component’s ownership tree.

## Why WebKt needs lifetimes

A browser UI continuously replaces parts of itself. Suppose a component renders a child and starts a
coroutine that observes some state. Later, the component renders again.

Without explicit ownership, the old child and coroutine may continue running even though their DOM is gone.
That can cause duplicate requests, updates to detached elements, retained Koin scopes, and memory leaks.

WebKt therefore groups resources by how long they should live:

| Lifetime | Typical contents | Ends when |
| --- | --- | --- |
| Application | Root component, root Koin scope, navigator and other application services | The application shuts down |
| Component | Component coroutine scope, callbacks, persistent children | The component closes |
| Render | Render coroutine scope, render-owned children, render integration resources | The component renders successfully again or closes |

Application ownership is managed by your application. Component and render ownership are managed by WebKt.

## Ownership means responsibility for cleanup

An owner promises to close what it owns. Ownership is not the same as a Kotlin reference:

- A local variable may refer to a component without owning it.
- A parent component owns its persistent children even if some service also refers to them.
- A render owns its render-created children even though their DOM elements are attached elsewhere.
- An already-owned child may be rendered through a descendant presentation component without becoming owned
  by that intermediary.

Ownership forms a tree:

```text
application
└── root component
    ├── component coroutine scope
    ├── persistent child
    │   └── that child's current render
    └── current render
        ├── render coroutine scope
        ├── render-owned child
        └── render environment
```

Closing an owner closes everything below it. Closing the root therefore closes both persistent children and
the currently rendered subtree.

Explicitly closing a child early is safe. WebKt detaches it from its owner so the owner no longer retains it
or tries to close it again.

Ownership and placement are separate. The declared parent determines the child's logical hierarchy and helps
establish its initial owner. Once ownership has been established, the child may be placed through another
descendant of that parent, such as a visibility region or sortable container. The original owner still closes
the child. If the child is not owned yet, its first render must use the declared parent's receiver because an
intermediary's receiver cannot unambiguously choose the intended lifetime.

## A component has two nested lifetimes

Every component has one long-lived **component lifetime** and, after rendering, one shorter-lived
**current render lifetime**.

### Component lifetime

The component lifetime starts during construction and ends when `component.close()` runs. It owns:

- the protected `Component.coroutineScope`;
- lifecycle callback subscriptions;
- children deliberately created as persistent children; and
- integration resources registered with its `ResourceLifetime`.

Use it for state and resources that must survive the component’s own re-renders.

### Render lifetime

A new render lifetime is created for every render attempt. It owns:

- `RenderReceiver.coroutineScope`;
- children resolved through `RenderReceiver.getComponent<T>()`; and
- the `RenderEnvironment` created for that attempt.

Use it for work that only makes sense while the current rendered contents exist.

For example, a collector that updates an element created in `renderContents` belongs to the render lifetime.
When the element is replaced, the collector is cancelled with it.

## What happens during a re-render

WebKt does not immediately destroy the working UI. It prepares a candidate render first:

```text
1. Existing DOM and render lifetime remain active.
2. WebKt creates a candidate render lifetime.
3. The component builds replacement contents in a detached element.
4a. On failure: close the candidate and keep the existing DOM and lifetime.
4b. On success: commit the new DOM, then close the previous render lifetime.
```

For a short time, the old and candidate render lifetimes both exist. This is intentional: the old UI remains
usable until its replacement is known to be valid.

An update error boundary may render fallback contents through `renderFailure`. Initial rendering has no
previous UI to preserve, so an initial failure cleans up and is thrown directly.

## Choosing between render-owned and persistent children

Most children declared inside `renderContents` should be render-owned:

```kotlin
class CustomerPage(
    parent: Component,
) : Component(parent, "customer-page") {
    override fun RenderReceiver.renderContents() {
        render(getComponent<CustomerList>())
    }
}
```

Here `getComponent<CustomerList>()` is the `RenderReceiver` extension. The child belongs to the current render
and closes when `CustomerPage` renders successfully again.

Use a persistent child when its identity or component state must survive parent re-renders:

```kotlin
class CustomerPage(
    parent: Component,
) : Component(parent, "customer-page") {
    private val searchBox by lazy {
        getComponent<SearchBox>()
    }

    override fun RenderReceiver.renderContents() {
        render(searchBox)
        render(getComponent<CustomerList>())
    }
}
```

The property initializer calls the `Component.getComponent<T>()` extension, because its receiver is the
`CustomerPage` instance. `searchBox` is owned by the component lifetime and reused. `CustomerList` is still
owned by each render.

Persistent ownership does **not** cache resolutions. This is incorrect:

```kotlin
override fun RenderReceiver.renderContents() {
    render(this@CustomerPage.getComponent<SearchBox>())
}
```

Every render creates another persistent child, all of which remain owned until `CustomerPage` closes. Resolve
or construct a persistent child once into a property or `lazy` value.

You can also construct a persistent child without Koin:

```kotlin
private val toolbar = constructComponent {
    Toolbar(this)
}
```

Direct component construction must always use `constructComponent { ... }`. If a subclass initializer throws,
the construction transaction closes every provisional component instead of leaking partially created resources.

## Choosing the right coroutine scope

Ask what should cancel the coroutine:

| The coroutine should stop when… | Use |
| --- | --- |
| The component closes | protected `Component.coroutineScope` |
| The component renders successfully again | `RenderReceiver.coroutineScope` |
| The application shuts down | an application-owned scope |

`inlineFlowComponent` uses the current render lifetime automatically. You normally do not need to launch its
collector yourself.

Avoid `GlobalScope` for component work. It has no connection to the component tree and therefore cannot be
cancelled by component cleanup.

## What an environment is

A `ComponentEnvironment` is the integration configuration shared by one component tree. It exists so the
component kernel can work with Koin—or another external framework—without depending on that framework
directly. It is a **bridge**, not another component lifetime and not the owner of the component tree.

The root chooses the environment. For example:

```kotlin
val environment = KoinComponentEnvironment(rootScope)
val app = constructComponent { App(environment) }
app.renderTo(document.body!!)
```

Constructing `App` stores the environment but does not attach the root to the integration yet. Attachment
is attempted during the root’s first `renderTo(...)` call, after the first two steps below succeed:

1. WebKt builds the initial contents.
2. It creates the root element through the target DOM consumer.
3. It calls `environment.attachComponent(root, componentLifetime)` to connect the root lifetime.

If building the initial contents or creating the root element fails, WebKt closes the failed render attempt and
does not call `attachComponent(...)`. If `attachComponent(...)` itself fails, WebKt closes the root and
propagates the failure from `renderTo(...)`. Later renders do not call it again.

For example, `KoinComponentEnvironment` uses root attachment to observe the caller-owned Koin scope. If that
scope closes, the adapter closes the root’s component lifetime. The environment does not take ownership of
the caller’s Koin scope.

### How children receive the environment

When you write a child component, you do **not manually add and forward an environment constructor
parameter**.

A root constructor receives the environment explicitly:

```kotlin
class App(
    environment: ComponentEnvironment,
) : Component(environment, "app-root")
```

A child constructor receives only its non-null parent:

```kotlin
class Toolbar(
    parent: Component,
) : Component(parent, "app-toolbar")
```

The `Component(parent, ...)` constructor copies `parent.environment` into the child’s inherited public
`environment` property. Consequently:

- the child has access to the same `ComponentEnvironment` instance as its parent;
- adapter functions can inspect that environment, as the Koin helpers do; and
- the child constructor does not need a separate `ComponentEnvironment` parameter.

“Inherits” therefore means automatic propagation through the `Component` base constructor. It does **not**
mean that the environment is hidden from children.

The shared component environment provides two integration hooks:

1. `attachComponent(...)` connects the root lifetime to an external owner during the first `renderTo(...)`
   call, after the initial contents and root element have been created.
2. `createRenderEnvironment(...)` creates a closeable, integration-specific resource for each render attempt.

The second hook is per-render. For Koin, it supplies the private scope used to resolve render-owned child
components. WebKt closes that render environment when the attempt fails, is replaced, or its component closes.

If no integration is needed, a root can use `ComponentEnvironment.Empty`:

```kotlin
class App : Component(ComponentEnvironment.Empty, "app-root")
```

## How the Koin environment fits

`KoinComponentEnvironment(rootScope)` adapts a caller-owned Koin scope to the component tree:

```text
caller-owned Koin root scope ── observed by ── KoinComponentEnvironment
                                          └── private Koin scope per render
```

The private render scope is why `RenderReceiver.getComponent<T>()` can resolve children without putting a
Koin `Scope` in every component constructor. The adapter prepends the non-null parent component to Koin’s
constructor parameters.

Closing a render closes its private Koin scope. Closing the caller-owned root Koin scope closes the attached
root component. The application should still explicitly close its root component, root scope, and Koin
application during orderly shutdown so cleanup order and failures remain visible.

The environment is shared by the tree, but it does not decide whether a child is render-owned or persistent.
The API used to create the child decides that.

## `ResourceLifetime` and `RenderEnvironment`

Most application authors only use `ComponentEnvironment` through `KoinComponentEnvironment` or
`ComponentEnvironment.Empty`. `ResourceLifetime` and `RenderEnvironment` mainly matter when writing another
integration adapter.

`ResourceLifetime` is the narrow ownership contract WebKt gives an adapter. It provides:

- a coroutine scope tied to the owner;
- `isClosed`; and
- `onClose { ... }` for registering cleanup.

An adapter must register every acquired resource with the supplied lifetime. It must not create an unrelated,
unowned coroutine scope.

`RenderEnvironment` is an opaque closeable value returned by the adapter for one render attempt. WebKt owns it
and closes it on failed rendering, successful replacement, or component closure.

## Root components and application shutdown

A root has no parent, so it receives an environment:

```kotlin
class App(
    environment: ComponentEnvironment,
) : Component(environment, "app-root")
```

A normal child has a non-null parent and inherits the environment:

```kotlin
class Toolbar(
    parent: Component,
) : Component(parent, "app-toolbar")
```

The public `parent` property is nullable because traversal can reach the root. This does not force ordinary
child constructors to accept a nullable parent.

The application owns the root and must close it. With Koin, shutdown normally proceeds from the UI inward to
the external container:

```text
1. Stop the application's shutdown listener.
2. Close the root component.
3. Close the caller-owned root Koin scope.
4. Close the Koin application.
```

See the complete startup and shutdown example in the [project README](../README.md).

## A practical decision guide

When creating a resource, ask these questions in order:

1. **Should it outlive the component?**
   If yes, give it to an application owner. Otherwise, continue.
2. **Should it survive this component’s re-renders?**
   If yes, use the component lifetime or create one persistent child. Otherwise, continue.
3. **Does it exist only because the current rendered DOM exists?**
   If yes, use the render lifetime or a render-owned child.
4. **Does an external framework create or retain it?**
   If yes, register its cleanup through the supplied `ResourceLifetime` in an environment adapter.

If the answer to “who closes this?” is unclear, the resource does not yet have a safe owner.

## Common mistakes

- **Calling a component constructor directly.** Use `constructComponent`, rendering, or an environment adapter.
- **Creating a persistent child during every render.** Resolve it once and reuse it.
- **Launching render-specific work in the component scope.** It will survive after its DOM is replaced.
- **Launching component work in an application or global scope.** It may retain a closed component.
- **Passing `ComponentEnvironment` through child constructors.** Children inherit it from their parent.
- **Treating an environment as the owner.** WebKt owns component and render lifetimes; the environment only
  attaches integration resources to them.
- **Keeping a `RenderReceiver` or `RenderEnvironment` for later use.** Both belong to one render attempt.
