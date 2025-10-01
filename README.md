[![GitHub license](https://img.shields.io/badge/license-Apache%20License%202.0-green.svg?style=flat)](https://www.apache.org/licenses/LICENSE-2.0)
# WebKt: experimental web framework for Kotlin

`WebKt` is an experimental, pre-alpha web framework for Kotlin, written in Kotlin.

Like most SPA frameworks (probably), its core feature is a component-model.
In this case it builds on [kotlinx.html](https://github.com/kotlin/kotlinx.html)'s
DSL for HTML, allowing you to write your UI in pure Kotlin.

[koin](https://github.com/InsertKoinIO/koin) is used for component instantiation and dependency injection.

Though not enforced by the framework, [kotlinx.coroutines](https://github.com/Kotlin/kotlinx.coroutines)
[Flow](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines.flow/-flow/)s
are the supposed way to handle state and events and trigger automatic updates of components on data-change.

**Migration work in progress:** This project is currently being refactored out of 
another (private) project of mine, for which I created this "framework" initially.