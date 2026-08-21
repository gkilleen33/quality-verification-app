package com.qualityverifier.domain

/**
 * The furniture categories a user can evaluate.
 *
 * [id] doubles as the prompt filename slug in the GitHub repo (`prompts/items/<id>.txt`)
 * and, with dashes swapped for underscores, as the drawable name the item grid looks for.
 * Adding a category means adding an entry here and a matching `prompts/items/<id>.txt`.
 *
 * [swahiliName] is the word a Nairobi buyer would actually use, shown under the English
 * label. It is null where a reliable term has not been confirmed by a native speaker
 * rather than guessed at — a wrong word in the user's own language costs more trust than
 * no word at all.
 */
enum class ItemType(
    val id: String,
    val displayName: String,
    val swahiliName: String? = null,
) {
    WOODEN_TABLE("wooden-table", "Table", "Meza"),
    WOODEN_CHAIR("wooden-chair", "Wooden chair", "Kiti"),
    WOODEN_STOOL("wooden-stool", "Stool or bench", "Kigoda"),
    WOODEN_BED("wooden-bed", "Bed", "Kitanda"),
    WOODEN_CABINET("wooden-cabinet", "Cabinet or wardrobe", "Kabati"),
    UPHOLSTERED_SOFA("upholstered-sofa", "Sofa", "Kochi"),
    UPHOLSTERED_CHAIR("upholstered-chair", "Padded chair"),
    OTHER("other", "Something else");

    /** Path of this item's prompt file, relative to the `prompts/` directory. */
    val promptPath: String get() = "items/$id.txt"

    /**
     * Drawable resource name the item card looks up at runtime, e.g. `item_wooden_table`.
     * Resolved by name rather than by `R.drawable.*` so that dropping a photo into
     * `res/drawable/` is the only step needed — no code change, and the app still
     * builds when no photo exists yet.
     */
    val drawableName: String get() = "item_" + id.replace('-', '_')

    companion object {
        fun fromId(id: String): ItemType? = entries.firstOrNull { it.id == id }
    }
}
