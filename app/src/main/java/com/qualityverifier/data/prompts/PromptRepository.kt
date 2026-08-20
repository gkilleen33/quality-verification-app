package com.qualityverifier.data.prompts

import com.qualityverifier.domain.ItemType

/**
 * Supplies the system prompt for a conversation.
 *
 * Phase 1 fetches from raw GitHub URLs. Phase 2 swaps in a server-backed
 * implementation; callers are unaffected because they only ever ask for the
 * assembled prompt for an item type.
 */
interface PromptRepository {
    suspend fun systemPromptFor(itemType: ItemType): String

    /** Drop cached copies so the next read re-fetches. */
    suspend fun clearCache()
}
