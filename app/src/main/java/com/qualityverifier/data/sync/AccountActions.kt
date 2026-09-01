package com.qualityverifier.data.sync

/**
 * The two things somebody can do to their own account.
 *
 * A thin pass-through over [SyncClient], so the settings screen depends on this rather
 * than on an HTTP client — and so the wording for each outcome lives in one place instead
 * of being reconstructed from status codes in a composable.
 */
class AccountActions(private val client: SyncClient) {

    suspend fun changePassword(current: String, new: String): PasswordOutcome =
        client.changePassword(current, new)

    /**
     * Marks the account deleted. The server revokes every token in the same transaction,
     * so this is the last authenticated call the phone will make.
     */
    suspend fun deleteAccount(): Boolean = client.deleteAccount()
}
