package de.pmenke.webkt.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AttributeStoreTest {
    @Test
    fun localNullShadowsAParentValue() {
        val key = AttributeKey<String>("key")
        val parent = HierarchicalAttributeStore().apply { this[key] = "parent" }
        val child = HierarchicalAttributeStore(parent)

        assertEquals("parent", child[key])
        child[key] = null
        assertNull(child[key])
        child.setInherited(key)
        assertEquals("parent", child[key])
    }
}
