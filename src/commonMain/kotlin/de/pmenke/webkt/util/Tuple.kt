package de.pmenke.webkt.util

/** Two-value tuple alias for API symmetry with [Tuple4] and [Tuple5]. */
typealias Tuple2<A, B> = Pair<A, B>
/** Three-value tuple alias for API symmetry with [Tuple4] and [Tuple5]. */
typealias Tuple3<A, B, C> = Triple<A, B, C>
/** Immutable four-value tuple. */
data class Tuple4<out A, out B, out C, out D>(
    val first: A,
    val second: B,
    val third: C,
    val fourth: D
)
/** Immutable five-value tuple. */
data class Tuple5<out A, out B, out C, out D, out E>(
    val first: A,
    val second: B,
    val third: C,
    val fourth: D,
    val fifth: E
)
