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
    /**
     * What the home grid calls this, where it differs from [displayName].
     *
     * The two chair protocols are one entry on that grid. "Padded chair" sitting beside
     * "Wooden chair" was easy to miss, and it asked a buyer to classify their own chair
     * before the app had asked them anything — so the grid offers "Chair" and the intake
     * settles which protocol applies.
     */
    private val gridLabel: String? = null,
) {
    WOODEN_TABLE("wooden-table", "Table", "Meza"),
    WOODEN_CHAIR("wooden-chair", "Wooden chair", "Kiti", gridLabel = "Chair"),
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

    /** Label for the home grid. Falls back to [displayName] for everything else. */
    val homeLabel: String get() = gridLabel ?: displayName

    /**
     * True where one grid entry covers more than one protocol, so the intake has to ask
     * which. Only the chairs: a sofa is always upholstered and a table never is.
     */
    val needsUpholsteryQuestion: Boolean
        get() = this == WOODEN_CHAIR || this == UPHOLSTERED_CHAIR

    /** Resolves the grid's single chair entry to the protocol that actually applies. */
    fun withUpholstery(upholstered: Boolean): ItemType = when {
        !needsUpholsteryQuestion -> this
        upholstered -> UPHOLSTERED_CHAIR
        else -> WOODEN_CHAIR
    }

    companion object {
        fun fromId(id: String): ItemType? = entries.firstOrNull { it.id == id }

        /**
         * What the home grid offers, which is not the same as the protocol list:
         * [UPHOLSTERED_CHAIR] is reachable only by answering the intake's upholstery
         * question, so it is absent here.
         */
        val homeChoices: List<ItemType> = entries.filterNot { it == UPHOLSTERED_CHAIR }
    }
}
