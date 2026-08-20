#!/usr/bin/env python3
"""Generates DefaultPrompts.kt from the files under prompts/.

The compiled-in copies exist so a fresh install with no connectivity still has a
usable prompt. Generating them keeps the Kotlin byte-identical to the repo files
instead of relying on someone remembering to edit both.

Run from the repo root after changing anything in prompts/:
    python3 tools/generate_default_prompts.py
"""
import pathlib
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
PROMPTS = ROOT / "prompts"
OUT = ROOT / "app/src/main/java/com/qualityverifier/data/prompts/DefaultPrompts.kt"

# Sequences that would break a Kotlin raw string or the enclosing KDoc block.
FORBIDDEN = {
    '"""': "ends the raw string early",
    "$": "is string interpolation in a Kotlin raw string",
}


def check(name, text):
    for bad, why in FORBIDDEN.items():
        if bad in text:
            sys.exit(f"error: {name} contains {bad!r}, which {why}. Rephrase it.")


def read(path):
    text = path.read_text().rstrip("\n")
    check(path.name, text)
    return text


master = read(PROMPTS / "master.txt")

items = []
for path in sorted((PROMPTS / "items").glob("*.txt")):
    text = read(path)
    if text.strip():
        items.append((path.stem, text))

entries = "\n".join(
    f'        "{slug}" to """\n{text}\n""".trimIndent(),' for slug, text in items
)
if not entries:
    entries = "        // No item prompt has content yet."

OUT.write_text(f'''package com.qualityverifier.data.prompts

import com.qualityverifier.domain.ItemType

/**
 * Compiled-in fallback prompts.
 *
 * GENERATED FILE - do not edit by hand. Regenerate after changing anything under
 * `prompts/` with: python3 tools/generate_default_prompts.py
 *
 * These are used only when a prompt has never been fetched and nothing is cached, so a
 * fresh install with no connectivity still behaves correctly. The files in the repo stay
 * the source of truth: a value fetched from GitHub always wins, including an empty one.
 */
object DefaultPrompts {{
    val MASTER: String = """
{master}
""".trimIndent()

    /** Keyed by [ItemType.id]. Items with an empty prompt file are simply absent. */
    private val ITEMS: Map<String, String> = mapOf(
{entries}
    )

    fun forItem(itemType: ItemType): String = ITEMS[itemType.id].orEmpty()
}}
''')
print(f"wrote {OUT.relative_to(ROOT)}: master={len(master)} chars, items={[s for s, _ in items]}")
