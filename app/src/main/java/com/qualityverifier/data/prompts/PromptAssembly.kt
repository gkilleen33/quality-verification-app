package com.qualityverifier.data.prompts

/**
 * Builds the system prompt sent to Claude: the master prompt, then the item-specific
 * prompt separated by a blank line.
 *
 * Item prompts are currently empty placeholders, so the separator is omitted when the
 * item text is blank — otherwise every request would carry trailing whitespace.
 * Pure function, unit-tested.
 */
fun assembleSystemPrompt(master: String, itemPrompt: String): String {
    val m = master.trimEnd()
    val i = itemPrompt.trim()
    return if (i.isEmpty()) m else "$m\n\n$i"
}
