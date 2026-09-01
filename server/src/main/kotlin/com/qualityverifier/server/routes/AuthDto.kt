package com.qualityverifier.server.routes

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class RegisterRequest(
    @SerialName("invite_code") val inviteCode: String = "",
    /** E.164, e.g. +256700123456. The sign-in identifier. */
    val phone: String = "",
    val password: String = "",
    val name: String = "",
    /** "individual" or "business". */
    @SerialName("account_type") val accountType: String = "",
    @SerialName("business_name") val businessName: String? = null,
    /** Only when they are at the business as they register. All three or none. */
    val latitude: Double? = null,
    val longitude: Double? = null,
    @SerialName("accuracy_m") val accuracyMetres: Double? = null,
    @SerialName("user_agent") val userAgent: String? = null,
)

@Serializable
data class SignInRequest(
    val phone: String = "",
    val password: String = "",
    @SerialName("user_agent") val userAgent: String? = null,
)

@Serializable
data class RefreshRequest(
    @SerialName("refresh_token") val refreshToken: String = "",
    @SerialName("user_agent") val userAgent: String? = null,
)

@Serializable
data class TokenResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("expires_in") val expiresIn: Long,
    @SerialName("refresh_token") val refreshToken: String,
    @SerialName("user_id") val userId: String,
)

@Serializable
data class MeResponse(
    @SerialName("user_id") val userId: String,
    val name: String?,
    @SerialName("account_type") val accountType: String?,
    @SerialName("business_name") val businessName: String?,
)

@Serializable
data class ErrorResponse(val error: String, val detail: String? = null)

/**
 * Validation, separated from the route so it can be tested without a server or a
 * database. Returns the first problem rather than collecting them: these are four
 * fields on a form the app itself controls, and a client that sends a bad one has a
 * bug rather than a user who needs a helpful list.
 */
fun RegisterRequest.validate(): String? = when {
    inviteCode.isBlank() -> "invite_code is required"
    validatePhone(phone) != null -> validatePhone(phone)
    // Length only, no composition rules. Forcing a symbol and a digit produces
    // Password1! and a sticky note; length is what actually costs an attacker.
    password.length < 8 -> "password must be at least 8 characters"
    password.length > 200 -> "password is too long"
    name.isBlank() -> "name is required"
    name.length > 120 -> "name is too long"
    accountType !in setOf("individual", "business") ->
        "account_type must be individual or business"
    accountType == "business" && businessName.isNullOrBlank() ->
        "business_name is required for a business account"
    businessName != null && businessName.length > 200 -> "business_name is too long"
    // A partial fix is not a fix. Accepting two of the three would store a point whose
    // accuracy we do not know, which is the one thing the accuracy column exists to
    // prevent.
    listOfNotNull(latitude, longitude, accuracyMetres).size !in setOf(0, 3) ->
        "latitude, longitude and accuracy_m must be sent together or not at all"
    latitude != null && latitude !in -90.0..90.0 -> "latitude out of range"
    longitude != null && longitude !in -180.0..180.0 -> "longitude out of range"
    accuracyMetres != null && accuracyMetres <= 0 -> "accuracy_m must be positive"
    // 5km is not a location, it is a district. Storing it would let a later "workshops
    // near me" put a shop on the wrong side of Kampala.
    accuracyMetres != null && accuracyMetres > 5000 -> "accuracy_m is too coarse to store"
    else -> null
}

/**
 * E.164 only. Deliberately strict rather than forgiving: accepting "0700123456" and
 * guessing the country would silently create two accounts for one person the first
 * time somebody typed it the other way, and a phone number is the sign-in identifier.
 * libphonenumber should replace this before anything wider than a pilot.
 */
fun validatePhone(phone: String): String? = when {
    phone.isBlank() -> "phone is required"
    !phone.startsWith("+") -> "phone must be in international format, starting with +"
    !Regex("""^\+[1-9]\d{7,14}$""").matches(phone) -> "phone is not a valid number"
    else -> null
}

fun SignInRequest.validate(): String? = when {
    phone.isBlank() -> "phone is required"
    password.isBlank() -> "password is required"
    else -> null
}
