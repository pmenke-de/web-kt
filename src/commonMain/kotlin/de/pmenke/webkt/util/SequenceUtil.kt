package de.pmenke.webkt.util

object SequenceUtil {

    /**
     * Returns the first element of the sequence that is an instance of the specified type [R].
     */
    inline fun <reified R> Sequence<*>.firstInstance(): R = filterIsInstance<R>().first()

    /**
     * Returns the first element of the sequence that is an instance of the specified type [R], or `null` if no such element is found.
     */
    inline fun <reified R> Sequence<*>.firstInstanceOrNull(): R? = filterIsInstance<R>().firstOrNull()

}