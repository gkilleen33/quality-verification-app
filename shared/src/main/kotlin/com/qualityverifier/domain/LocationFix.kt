package com.qualityverifier.domain

/**
 * Where an assessment was made, when the customer agreed to record it.
 *
 * Kept as a triple rather than a bare point, because a point on its own is a false
 * precision: a fix good to five metres and one good to two kilometres look identical
 * once the accuracy is dropped, and only one of them can place a workshop. The same
 * reasoning as `users.business_location_accuracy_m` — see V2__profiles_and_retention.
 *
 * [capturedAt] is when the fix was taken, not when the assessment started. A cached fix
 * carries no hint of its age, and a stale one is more dangerous than an imprecise one
 * because the accuracy figure makes it look trustworthy.
 */
data class LocationFix(
    val latitude: Double,
    val longitude: Double,
    val accuracyMetres: Double,
    val capturedAt: Long,
) {
    /**
     * Whether this is worth storing at all.
     *
     * The bounds match what the server will accept, so a fix is rejected on the handset
     * rather than by a 400 the customer would never see. Anything coarser than five
     * kilometres names a district, not a shop, and is not what this was collected for.
     */
    val isUsable: Boolean
        get() = latitude in -90.0..90.0 &&
            longitude in -180.0..180.0 &&
            accuracyMetres > 0 &&
            accuracyMetres <= MAX_ACCURACY_METRES

    companion object {
        const val MAX_ACCURACY_METRES = 5000.0
    }
}
