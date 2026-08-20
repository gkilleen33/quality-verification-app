package com.qualityverifier.domain

/**
 * The furniture categories a user can evaluate.
 *
 * [id] doubles as the prompt filename slug in the GitHub repo (`prompts/items/<id>.txt`)
 * and, with dashes swapped for underscores, as the drawable name the item grid looks for.
 * Adding a category means adding an entry here and a matching `prompts/items/<id>.txt`.
 */
enum class ItemType(val id: String, val displayName: String) {
    WOODEN_TABLE("wooden-table", "Wooden Table"),
    WOODEN_CHAIR("wooden-chair", "Wooden Chair"),
    WOODEN_BED("wooden-bed", "Wooden Bed"),
    UPHOLSTERED_CHAIR("upholstered-chair", "Upholstered Chair"),
    UPHOLSTERED_SOFA("upholstered-sofa", "Upholstered Sofa"),
    OTHER("other", "Other");

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
