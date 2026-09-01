package com.qualityverifier.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ItemTypeTest {

    @Test
    fun `the home grid offers one chair, not two`() {
        // "Padded chair" beside "Wooden chair" was easy to miss, and it asked a buyer to
        // classify their own chair before the app had asked them anything.
        assertFalse(ItemType.UPHOLSTERED_CHAIR in ItemType.homeChoices)
        assertTrue(ItemType.WOODEN_CHAIR in ItemType.homeChoices)
        assertEquals("Chair", ItemType.WOODEN_CHAIR.homeLabel)
        assertEquals(ItemType.entries.size - 1, ItemType.homeChoices.size)
    }

    @Test
    fun `the full name survives for everywhere that is not the grid`() {
        // Reports rows and share messages should still distinguish the two, since by then
        // the intake has settled which it is.
        assertEquals("Wooden chair", ItemType.WOODEN_CHAIR.displayName)
        assertEquals("Padded chair", ItemType.UPHOLSTERED_CHAIR.displayName)
    }

    @Test
    fun `only the chairs need the upholstery question`() {
        // A sofa is always upholstered and a table never is; asking would be noise.
        ItemType.entries.forEach { itemType ->
            val expected = itemType == ItemType.WOODEN_CHAIR ||
                itemType == ItemType.UPHOLSTERED_CHAIR
            assertEquals(itemType.name, expected, itemType.needsUpholsteryQuestion)
        }
    }

    @Test
    fun `the upholstery answer picks the protocol`() {
        assertEquals(
            ItemType.UPHOLSTERED_CHAIR,
            ItemType.WOODEN_CHAIR.withUpholstery(upholstered = true),
        )
        assertEquals(
            ItemType.WOODEN_CHAIR,
            ItemType.WOODEN_CHAIR.withUpholstery(upholstered = false),
        )
        // Reversible, so backing up in the intake and changing the answer works.
        assertEquals(
            ItemType.WOODEN_CHAIR,
            ItemType.UPHOLSTERED_CHAIR.withUpholstery(upholstered = false),
        )
    }

    @Test
    fun `anything else is unchanged by an upholstery answer`() {
        ItemType.entries.filterNot { it.needsUpholsteryQuestion }.forEach { itemType ->
            assertEquals(itemType, itemType.withUpholstery(upholstered = true))
            assertEquals(itemType, itemType.withUpholstery(upholstered = false))
        }
    }

    @Test
    fun `every home choice still has a protocol file behind it`() {
        // A grid entry whose prompt does not exist would 404 and fall back to master
        // alone, silently dropping the whole guided walkthrough for that category.
        ItemType.homeChoices.forEach { itemType ->
            assertEquals("items/${itemType.id}.txt", itemType.promptPath)
        }
    }
}
