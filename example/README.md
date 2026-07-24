# Flowboard example

Flowboard is a runnable Kotlin/Wasm Kanban application built with WebKt. It is intentionally small
enough to read end to end, but dynamic enough to demonstrate application structure rather than
isolated widgets.

It includes:

- a three-column board with drag-and-drop and keyboard move actions;
- a synchronized task table with search, status and priority filters, and sortable columns;
- one task editor shared by both views;
- browser `localStorage` persistence and cross-tab updates;
- explicit component, render, repository, navigation, and application lifetimes;
- pure repository tests and browser component tests using `web-kt-testing`.

## Run it

From the repository root, start the browser development server:

```shell
./gradlew :example:wasmJsBrowserDevelopmentRun
```

The development server falls back to `index.html` for application routes, so opening `/board` or
`/tasks` directly works as well as following in-app navigation.

Run the example's common and browser tests:

```shell
./gradlew :example:allTests
./gradlew :example:wasmJsBrowserTest
```

Create the development bundle without starting a server:

```shell
./gradlew :example:wasmJsBrowserDevelopmentWebpack
```

The browser application does not require a backend or any external service.

## Where to start reading

1. [`main.kt`](src/wasmJsMain/kotlin/de/pmenke/webkt/example/main.kt) starts Koin, constructs the
   root transactionally, renders it, and closes every application resource.
2. [`AppModule.kt`](src/wasmJsMain/kotlin/de/pmenke/webkt/example/AppModule.kt) is the composition
   boundary for application services and component factories.
3. [`App.kt`](src/wasmJsMain/kotlin/de/pmenke/webkt/example/components/App.kt) owns the shell,
   routing, repository feedback, reset action, and persistent editor.
4. [`KanbanBoard.kt`](src/wasmJsMain/kotlin/de/pmenke/webkt/example/components/KanbanBoard.kt) shows
   render-owned components, flow-driven rendering, drag-and-drop, and keyboard alternatives.
5. [`TaskTable.kt`](src/wasmJsMain/kotlin/de/pmenke/webkt/example/components/TaskTable.kt) shows
   `ControlValue`, `FilterControls`, `SortControls`, and scope-free derived state.
6. [`TaskEditor.kt`](src/wasmJsMain/kotlin/de/pmenke/webkt/example/components/TaskEditor.kt) shows a
   persistent stateful component and DOM control bindings.
7. [`PersistentTaskRepository.kt`](src/commonMain/kotlin/de/pmenke/webkt/example/repository/PersistentTaskRepository.kt)
   contains the platform-independent persistence and mutation rules.

## State and ownership

The repository is the application-owned source of truth. Its `StateFlow<List<Task>>` always has a
current board snapshot and emits after a complete board has been saved. The board and table both
observe that same flow, so a move or edit appears in both views without a synchronization layer.

The task table derives its visible rows from four inputs: repository tasks, a search `ControlValue`,
the `FilterControls` predicate, and the `SortControls` comparator. These inputs produce one
`ObservableValue<List<Task>>`. Reading it does not start a coroutine. Its cold update stream is
collected only by `inlineFlowComponent`, in the current render lifetime, and is cancelled when that
render is replaced. There is no application scope passed into the table and no `stateIn` merely to
materialize derived UI state.

`CachingFlow` is intentionally absent. `localStorage` is the primary persistence mechanism, not a
cache of remote data, and the repository already owns an immediately available state snapshot.

The component ownership map is:

| Owner | Resource | Lifetime and reason |
|---|---|---|
| `main` | Koin application, root scope, repository, navigator, `App` | Application lifetime; closed on non-persisted page hide or failed startup |
| `App` | `TaskEditor` | Persistent child; one draft must survive shell and route renders |
| Current `App` render | Routed board, table, or not-found page | Render-owned; route changes replace and close the previous page |
| Current board render | Columns, task cards, inline task-flow component | Render-owned; repository updates replace the rendered card tree |
| Current table render | Inline derived-results component | Render-owned; its collector ends when the table render is replaced |
| `TaskEditor` | Its `ControlValue` fields | Component-owned; bindings are replaced on render and released on close |

`Component.getComponent()` in the lazy editor property creates a persistent child. Calls to
`RenderReceiver.getComponent()` inside `renderContents` create render-owned routed pages and task
cards. This distinction prevents stale page components from accumulating while preserving an
unfinished editor draft.

## Rendering failures

`App.renderFailure` provides fallback contents if an update render of `App` itself fails. It is not
a React-style boundary around every future descendant update: an `inlineFlowComponent` observes its
flow and schedules later renders independently, after its parent render has completed. An unhandled
failure in such a later render is logged and reported to the browser by WebKt.

Repository and storage failures are expected application state instead. The repository publishes
them through `problem`, and the shell shows them without throwing from rendering. Components that
need recovery from their own asynchronous render failures should implement `renderFailure` on the
component that owns that update.

## Persistence and reset

The repository serializes the complete, versioned board to the
`de.pmenke.webkt.example.kanban.v1` local-storage key before publishing a mutation. Changes therefore
survive reloads. A storage write failure leaves the observable task list unchanged and appears in
the application problem banner.

Missing storage is seeded with the bundled sample board. Malformed, unsupported, or invalid stored
data is left untouched for diagnosis while the application shows sample tasks and a problem.
“Reset all data” asks for confirmation, then replaces the stored value with the exact bundled sample
board. Browser `storage` events keep another open tab synchronized, and the repository removes that
global listener when it closes.

## Component tests

`web-kt-testing` renders real components into isolated browser DOM containers. A fixture owns the
temporary root, offers scoped queries and event helpers, waits for renders or eventual DOM
conditions, and closes the complete component tree during cleanup.

The example browser tests are:

- [`KanbanBoardTest.kt`](src/wasmJsTest/kotlin/de/pmenke/webkt/example/components/KanbanBoardTest.kt),
  which verifies keyboard movement, native drag-and-drop ordering, and persistent editor draft state;
- [`TaskTableTest.kt`](src/wasmJsTest/kotlin/de/pmenke/webkt/example/components/TaskTableTest.kt),
  which drives search, filtering, sorting, and the shared edit callback.

They use an in-memory storage implementation, so tests never read or modify the developer's
`localStorage`. They wait with `awaitRender` or `awaitUntil`; fixed delays are unnecessary. Repository
rules and serialization are covered separately in
[`PersistentTaskRepositoryTest.kt`](src/commonTest/kotlin/de/pmenke/webkt/example/repository/PersistentTaskRepositoryTest.kt).

For the fixture API itself, see
[`ComponentFixture.kt`](../web-kt-testing/src/wasmJsMain/kotlin/de/pmenke/webkt/testing/ComponentFixture.kt).
