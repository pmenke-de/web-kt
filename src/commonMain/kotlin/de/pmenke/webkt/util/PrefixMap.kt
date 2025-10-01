package de.pmenke.webkt.util

import kotlin.text.iterator

internal data class TrieNode<T: Any>(
    val children: MutableMap<Char, TrieNode<T>>,
    var value: T? = null,
)

internal data class PrefixMap<T: Any>(val root: TrieNode<T> = TrieNode(mutableMapOf())) {

    fun insert(key: String, value: T) {
        var currentNode = root
        for (char in key) {
            currentNode = currentNode.children.getOrPut(char) { TrieNode(mutableMapOf()) }
        }
        currentNode.value = value
    }

    fun longestPrefixMatch(key: String): T? {
        var currentNode = root
        var longestValue: T? = root.value
        for (char in key) {
            currentNode = currentNode.children[char] ?: break
            if (currentNode.value != null) {
                longestValue = currentNode.value
            }
        }
        return longestValue
    }
}