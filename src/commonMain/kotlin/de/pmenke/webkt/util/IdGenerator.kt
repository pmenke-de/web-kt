package de.pmenke.webkt.util

import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi

@OptIn(ExperimentalAtomicApi::class)
object IdGenerator {
    private val value = AtomicLong(0L)
    val next: String get() = value.addAndFetch(1).toString()
}