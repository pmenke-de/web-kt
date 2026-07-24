package de.pmenke.webkt.example.repository

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BrowserTaskRepositoryTest {
    @Test
    fun storageChangeRequiresMatchingKeyAndStorageIdentity() {
        val localStorage = Any()
        val otherStorage = Any()

        assertTrue(
            isExpectedStorageChange(
                expectedKey = "board",
                eventKey = "board",
                eventStorage = localStorage,
                expectedStorage = { localStorage },
            ),
        )
        assertFalse(
            isExpectedStorageChange(
                expectedKey = "board",
                eventKey = "other",
                eventStorage = localStorage,
                expectedStorage = { localStorage },
            ),
        )
        assertFalse(
            isExpectedStorageChange(
                expectedKey = "board",
                eventKey = "board",
                eventStorage = otherStorage,
                expectedStorage = { localStorage },
            ),
        )
        assertFalse(
            isExpectedStorageChange(
                expectedKey = "board",
                eventKey = "board",
                eventStorage = null,
                expectedStorage = { localStorage },
            ),
        )
    }

    @Test
    fun mismatchedKeyDoesNotAccessStorageAndDeniedAccessRejectsEvent() {
        val eventStorage = Any()
        var storageAccesses = 0

        assertFalse(
            isExpectedStorageChange(
                expectedKey = "board",
                eventKey = "other",
                eventStorage = eventStorage,
                expectedStorage = {
                    storageAccesses++
                    eventStorage
                },
            ),
        )
        assertTrue(storageAccesses == 0)

        assertFalse(
            isExpectedStorageChange(
                expectedKey = "board",
                eventKey = "board",
                eventStorage = eventStorage,
                expectedStorage = {
                    storageAccesses++
                    error("Storage access denied")
                },
            ),
        )
        assertTrue(storageAccesses == 1)
    }
}
