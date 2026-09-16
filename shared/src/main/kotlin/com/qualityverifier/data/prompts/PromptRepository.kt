package com.qualityverifier.data.prompts

import com.qualityverifier.domain.Audience
import com.qualityverifier.domain.ItemType

/**
 * Supplies the system prompt for a conversation.
 *
 * Phase 1 fetches from raw GitHub URLs. Phase 2 swaps in a server-backed
 * implementation; callers are unaffected because they only ever ask for the
 * assembled prompt for an item type.
 */
interface PromptRepository {
    /**
     * @param audience which half of the project is asking. Selects the master prompt and
     *   nothing else: the item protocols are shared, because what to photograph on a
     *   table and which hands-on tests apply to it do not depend on whether a buyer or
     *   its maker is holding the phone.
     *
     *   Defaulted so the buyer-facing caller reads exactly as it did.
     */
    suspend fun systemPromptFor(
        itemType: ItemType,
        audience: Audience = Audience.BUYER,
    ): String

    /** Drop cached copies so the next read re-fetches. */
    suspend fun clearCache()
}
