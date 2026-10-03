package com.lifedashboard.tv.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.lifedashboard.tv.util.AppLog

/**
 * EncryptedSharedPreferences-backed credential store. File name is
 * "lifedashboard_secure"; every value is encrypted at rest. The client secret
 * and tokens are never baked into the build — they are written at runtime
 * from Settings. (The client ID ships as a build-time default because it is
 * a public identifier, not a secret; a Settings override takes precedence.)
 *
 * Key layout:
 * - "google_oauth_client_id"            Google OAuth client ID (user-entered)
 * - "google_oauth_client_secret"        Google OAuth client secret (user-entered,
 *                                       required by Google's token endpoint for
 *                                       TV/limited-input clients)
 * - "slot{1,2}_access_token"            Google OAuth access token
 * - "slot{1,2}_refresh_token"           Google OAuth refresh token
 * - "slot{1,2}_expiry_epoch_ms"         access-token expiry, epoch millis
 * - "slot{1,2}_account_name"            cached Google account display name
 * - "slot{1,2}_selected_calendars"       calendar ids picked in Settings →
 *                                       Google Calendar → Calendars (empty /
 *                                       absent = primary calendar only)
 * - "parcel_username" / "parcel_password" Parcel Pending login credentials
 */
class TokenStore(context: Context) {

    private val appContext = context.applicationContext
    private val prefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            appContext,
            SECURE_FILE,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    // ------------------------------------------------------------------
    // Google OAuth client ID (runtime-configured, shared by both slots)
    // ------------------------------------------------------------------

    fun getClientId(): String? =
        prefs.getString(KEY_CLIENT_ID, null)?.takeIf { it.isNotBlank() }

    fun saveClientId(clientId: String) {
        prefs.edit().putString(KEY_CLIENT_ID, clientId.trim()).apply()
    }

    fun getClientSecret(): String? =
        prefs.getString(KEY_CLIENT_SECRET, null)?.takeIf { it.isNotBlank() }

    fun saveClientSecret(clientSecret: String) {
        prefs.edit().putString(KEY_CLIENT_SECRET, clientSecret.trim()).apply()
    }

    // ------------------------------------------------------------------
    // Per-slot Google account tokens (slot is 1 or 2)
    // ------------------------------------------------------------------

    fun isLinked(slot: Int): Boolean =
        getAccessToken(slot) != null && getRefreshToken(slot) != null

    fun getAccessToken(slot: Int): String? =
        prefs.getString(slotKey(slot, KEY_ACCESS_TOKEN), null)

    fun getRefreshToken(slot: Int): String? =
        prefs.getString(slotKey(slot, KEY_REFRESH_TOKEN), null)

    /** Access-token expiry as epoch millis, 0 when unknown. */
    fun getExpiryEpochMs(slot: Int): Long =
        prefs.getLong(slotKey(slot, KEY_EXPIRY), 0L)

    fun getAccountName(slot: Int): String? =
        prefs.getString(slotKey(slot, KEY_ACCOUNT_NAME), null)

    fun saveAccountName(slot: Int, accountName: String) {
        prefs.edit().putString(slotKey(slot, KEY_ACCOUNT_NAME), accountName).apply()
    }

    /**
     * Stores a fresh token pair. [refreshToken] may be null on refresh
     * responses that omit it — the previously stored one is kept.
     */
    fun saveTokens(
        slot: Int,
        accessToken: String,
        refreshToken: String?,
        expiresInSec: Long,
        accountName: String? = null
    ) {
        val expiryEpochMs = System.currentTimeMillis() + expiresInSec.coerceAtLeast(0) * 1000L
        prefs.edit()
            .putString(slotKey(slot, KEY_ACCESS_TOKEN), accessToken)
            .putLong(slotKey(slot, KEY_EXPIRY), expiryEpochMs)
            .also { editor ->
                if (refreshToken != null) {
                    editor.putString(slotKey(slot, KEY_REFRESH_TOKEN), refreshToken)
                }
                if (accountName != null) {
                    editor.putString(slotKey(slot, KEY_ACCOUNT_NAME), accountName)
                }
            }
            .apply()
    }

    /** Clears every stored value for the given slot. */
    fun clearSlot(slot: Int) {
        prefs.edit()
            .remove(slotKey(slot, KEY_ACCESS_TOKEN))
            .remove(slotKey(slot, KEY_REFRESH_TOKEN))
            .remove(slotKey(slot, KEY_EXPIRY))
            .remove(slotKey(slot, KEY_ACCOUNT_NAME))
            .remove(slotKey(slot, KEY_SELECTED_CALENDARS))
            .apply()
    }

    // ------------------------------------------------------------------
    // Per-slot calendar selection (Settings → Google Calendar → Calendars)
    // ------------------------------------------------------------------

    /**
     * Calendar ids the user checked for this slot, or null when never
     * picked (meaning: primary calendar only).
     *
     * Stored as a single comma-joined string (see
     * [CalendarSelection.serializeIds]) — the EncryptedSharedPreferences
     * string-set round-trip silently dropped selections on-device, while
     * plain strings use the same mechanism as the working token storage.
     * Falls back to the legacy string-set key (build 34) for migration.
     */
    fun getSelectedCalendarIds(slot: Int): Set<String>? {
        prefs.getString(slotKey(slot, KEY_SELECTED_CALENDARS), null)?.let {
            return CalendarSelection.parseIds(it)
        }
        return prefs.getStringSet(slotKey(slot, KEY_SELECTED_CALENDARS), null)?.toSet()
    }

    /** Persists the picker selection. An empty set means primary only. */
    fun saveSelectedCalendarIds(slot: Int, ids: Set<String>) {
        val raw = CalendarSelection.serializeIds(ids)
        val editor = prefs.edit()
        if (raw.isEmpty()) {
            editor.remove(slotKey(slot, KEY_SELECTED_CALENDARS))
        } else {
            editor.putString(slotKey(slot, KEY_SELECTED_CALENDARS), raw)
        }
        editor.apply()
        AppLog.log("Calendar", "slot $slot saved ${ids.size} calendar(s)")
    }

    // ------------------------------------------------------------------
    // Parcel Pending credentials
    // ------------------------------------------------------------------

    fun getParcelUsername(): String? = prefs.getString(KEY_PARCEL_USERNAME, null)
    fun getParcelPassword(): String? = prefs.getString(KEY_PARCEL_PASSWORD, null)

    fun saveParcelCredentials(username: String, password: String) {
        prefs.edit()
            .putString(KEY_PARCEL_USERNAME, username)
            .putString(KEY_PARCEL_PASSWORD, password)
            .apply()
    }

    fun clearParcelCredentials() {
        prefs.edit()
            .remove(KEY_PARCEL_USERNAME)
            .remove(KEY_PARCEL_PASSWORD)
            .apply()
    }

    private fun slotKey(slot: Int, name: String): String {
        val s = slot.coerceIn(1, 2)
        return "slot${s}_$name"
    }

    companion object {
        private const val SECURE_FILE = "lifedashboard_secure"

        /** Google OAuth client ID, entered by the user at runtime. */
        const val KEY_CLIENT_ID = "google_oauth_client_id"

        /** Google OAuth client secret, entered by the user at runtime. */
        const val KEY_CLIENT_SECRET = "google_oauth_client_secret"

        private const val KEY_ACCESS_TOKEN = "access_token"
        private const val KEY_REFRESH_TOKEN = "refresh_token"
        private const val KEY_EXPIRY = "expiry_epoch_ms"
        private const val KEY_ACCOUNT_NAME = "account_name"
        private const val KEY_SELECTED_CALENDARS = "selected_calendars"

        private const val KEY_PARCEL_USERNAME = "parcel_username"
        private const val KEY_PARCEL_PASSWORD = "parcel_password"
    }
}
