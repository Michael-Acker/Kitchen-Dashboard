package com.lifedashboard.tv.data

import com.lifedashboard.tv.model.CalendarEvent
import com.lifedashboard.tv.model.NewsHeadline
import com.lifedashboard.tv.model.ParcelInfo
import com.lifedashboard.tv.model.WeatherData

/**
 * Repository contracts. Implementations live in this package as
 * WeatherRepository, CalendarRepository, ParcelRepository, NewsRepository —
 * each a public class with a single `context: Context` constructor parameter.
 * All suspend functions perform network I/O and must be called off the main thread.
 *
 * NOTHING secret is configured at build time: the Google OAuth client secret
 * and all credentials are entered by the user at runtime in Settings and
 * stored in EncryptedSharedPreferences. (The client ID ships as a baked-in
 * default — it is a public identifier, not a secret — with a Settings
 * override available.)
 */

interface WeatherRepo {
    suspend fun refresh(): WeatherData
}

interface CalendarRepo {
    data class DeviceFlowSession(
        val userCode: String,
        val verificationUrl: String,
        val deviceCode: String,
        /** Seconds to wait between token polls. */
        val intervalSec: Long
    )

    /**
     * Google OAuth client ID: the baked-in default from the build, unless the
     * user saved an override in Settings → Google Calendar. Client IDs are
     * public identifiers, so shipping a default is safe; the override path
     * exists in case the Cloud client is ever recreated.
     */
    fun getClientId(): String?
    fun saveClientId(clientId: String)
    fun clientIdConfigured(): Boolean = getClientId().isNullOrBlank().not()

    /**
     * Google OAuth client secret, entered by the user at runtime alongside the
     * client ID. Google's token endpoint requires it for TV/limited-input
     * clients — without it every token exchange fails with
     * "Missing required parameter: client_secret".
     */
    fun getClientSecret(): String?
    fun saveClientSecret(clientSecret: String)
    fun clientSecretConfigured(): Boolean = getClientSecret().isNullOrBlank().not()

    /** True once both the client ID and the client secret are saved. */
    fun googleConfigured(): Boolean = clientIdConfigured() && clientSecretConfigured()

    /** slot is 1 or 2. */
    fun isLinked(slot: Int): Boolean
    fun accountName(slot: Int): String?

    /**
     * Starts the TV device-authorization flow for one account slot,
     * using the runtime client ID. Throws IllegalStateException if none configured.
     */
    suspend fun beginDeviceFlow(slot: Int): DeviceFlowSession

    /**
     * Polls the token endpoint until the user approves (or a timeout elapses).
     * Returns true if tokens were obtained and stored.
     */
    suspend fun pollForToken(slot: Int, deviceCode: String, intervalSec: Long): Boolean

    /** Upcoming events merged from both linked accounts, sorted by start time. */
    suspend fun getUpcomingEvents(): List<CalendarEvent>

    fun unlink(slot: Int)
}

interface ParcelRepo {
    fun hasCredentials(): Boolean
    fun saveCredentials(username: String, password: String)
    fun clearCredentials()

    /** The saved username, if any (never the password). */
    fun savedUsername(): String?

    /**
     * READ-ONLY. Logs in with the saved credentials when needed (re-login on
     * session expiry) and returns parcels, pending first. Never opens lockers
     * or changes anything server-side.
     */
    suspend fun getParcels(): List<ParcelInfo>

    /**
     * READ-ONLY login check: attempts the login flow with the saved
     * credentials and reports the outcome as a human-readable string
     * ("Login OK …" or the specific failure). Never throws.
     */
    suspend fun testLogin(): String

    /**
     * READ-ONLY structural dump of the parcel-history results table: header
     * names plus the first few rows' cell text (truncated). Used to diagnose
     * parsing mismatches against the live site. Never throws.
     */
    suspend fun debugTableDump(): String
}

interface NewsRepo {
    /** Top 5 headlines. */
    suspend fun getHeadlines(): List<NewsHeadline>
}
