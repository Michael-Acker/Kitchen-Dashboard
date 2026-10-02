package com.lifedashboard.tv.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * Google Calendar access for two account slots via the OAuth 2.0 Device
 * Authorization Flow (built for TVs — no browser redirect needed).
 *
 * The OAuth client ID and client secret are entered by the user at runtime in
 * Settings and read from [TokenStore]; nothing about them is baked into the
 * build. Tokens and the cached account display name live in
 * EncryptedSharedPreferences.
 */
class CalendarRepository(private val context: Context) : CalendarRepo {

    // Bounded waits: a hung socket must never stall a refresh pass forever.
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .writeTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .callTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .build()
    private val store = TokenStore(context)

    // ------------------------------------------------------------------
    // CalendarRepo contract
    // ------------------------------------------------------------------

    /**
     * The OAuth client ID, baked in at build time (client IDs are public
     * identifiers, not secrets). The user can override it in Settings, e.g.
     * if the Cloud client is ever recreated; the override wins.
     */
    override fun getClientId(): String? =
        store.getClientId()?.let(::sanitizeClientId) ?: DEFAULT_CLIENT_ID

    override fun saveClientId(clientId: String) = store.saveClientId(clientId)

    override fun getClientSecret(): String? = store.getClientSecret()

    override fun saveClientSecret(clientSecret: String) = store.saveClientSecret(clientSecret)

    override fun isLinked(slot: Int): Boolean = store.isLinked(slot)

    override fun accountName(slot: Int): String? = store.getAccountName(slot)

    /**
     * Starts the TV device flow. Throws [IllegalStateException] when no
     * client ID has been configured in Settings.
     */
    override suspend fun beginDeviceFlow(slot: Int): CalendarRepo.DeviceFlowSession =
        withContext(Dispatchers.IO) {
            val clientId = requireClientId()
            val form = FormBody.Builder()
                .add("client_id", clientId)
                .add("scope", SCOPE_CALENDAR_READONLY)
                .build()
            val request = Request.Builder()
                .url(DEVICE_CODE_URL)
                .post(form)
                .build()
            val json = postJson(request, "device authorization")
            // Google's documented key is "verification_url" (the generic
            // RFC 8628 name "verification_uri" is accepted as a fallback).
            val verificationUrl = json.optString("verification_url")
                .ifBlank { json.optString("verification_uri") }
            if (verificationUrl.isBlank()) {
                throw IOException(
                    "Google device authorization failed: no verification URL in response"
                )
            }
            CalendarRepo.DeviceFlowSession(
                userCode = json.getString("user_code"),
                verificationUrl = verificationUrl,
                deviceCode = json.getString("device_code"),
                intervalSec = json.optLong("interval", DEFAULT_POLL_INTERVAL_SEC)
            )
        }

    /**
     * Polls the token endpoint until the user approves on another device.
     * Returns true once tokens are stored, false only on genuine timeout
     * (~10 minutes). Throws [IOException] with Google's actual error detail
     * when the request is rejected (wrong secret, denied access, …) so the
     * UI never mislabels a config error as "not approved in time".
     * [IllegalStateException] if no client ID/secret is configured.
     */
    override suspend fun pollForToken(
        slot: Int,
        deviceCode: String,
        intervalSec: Long
    ): Boolean = withContext(Dispatchers.IO) {
        val clientId = requireClientId()
        val clientSecret = requireClientSecret()
        var interval = intervalSec.coerceAtLeast(1)
        val deadline = System.currentTimeMillis() + POLL_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            delay(interval * 1000)
            val form = FormBody.Builder()
                .add("client_id", clientId)
                .add("client_secret", clientSecret)
                .add("device_code", deviceCode)
                .add("grant_type", GRANT_TYPE_DEVICE_CODE)
                .build()
            val request = Request.Builder()
                .url(TOKEN_URL)
                .post(form)
                .build()
            val (statusCode, json) = try {
                postJsonWithStatus(request)
            } catch (e: IOException) {
                // Transient network blip — keep waiting.
                continue
            }
            if (statusCode == 200) {
                store.saveTokens(
                    slot = slot,
                    accessToken = json.getString("access_token"),
                    refreshToken = json.optString("refresh_token").takeIf { it.isNotBlank() },
                    expiresInSec = json.optLong("expires_in", 3600)
                )
                return@withContext true
            }
            when (val error = json.optString("error")) {
                "authorization_pending" -> { /* keep waiting */ }
                "slow_down" -> interval += SLOW_DOWN_EXTRA_SEC
                "access_denied" -> throw IOException(
                    "Sign-in was denied in the browser. Try again and choose Allow."
                )
                "expired_token" -> throw IOException(
                    "The sign-in code expired before approval. Press Cancel and try again."
                )
                else -> {
                    val detail = json.optString("error_description")
                        .ifBlank { error.ifBlank { "HTTP $statusCode" } }
                    throw IOException(
                        "Google rejected the sign-in request" +
                            (if (error.isNotBlank()) " ($error)" else "") +
                            ": $detail. Check that the client ID and secret in " +
                            "Settings → Google Calendar are from the same OAuth client, " +
                            "and that the client is a “TVs and Limited Input devices” " +
                            "type in Google Cloud Console."
                    )
                }
            }
        }
        false
    }

    /**
     * Events in the displayed week (previous Sunday .. next Sunday) from
     * both linked slots, merged and sorted by start. Window-anchored so
     * past events in the week are included.
     */
    override suspend fun getUpcomingEvents(): List<com.lifedashboard.tv.model.CalendarEvent> =
        withContext(Dispatchers.IO) {
            val merged = ArrayList<com.lifedashboard.tv.model.CalendarEvent>()
            for (slot in 1..2) {
                if (!isLinked(slot)) continue
                val token = getValidAccessToken(slot) ?: continue
                val accountName = fetchPrimaryAccountName(token)
                    ?: store.getAccountName(slot)
                    ?: "Account $slot"
                store.saveAccountName(slot, accountName)
                merged += fetchEvents(token, slot.toString(), accountName)
            }
            merged.sortBy { it.start }
            merged
        }

    override fun unlink(slot: Int) = store.clearSlot(slot)

    // ------------------------------------------------------------------
    // Token helpers
    // ------------------------------------------------------------------

    private fun requireClientId(): String =
        getClientId() ?: throw IllegalStateException(
            "Google OAuth client ID is not configured. Enter it in Settings → Google Calendar."
        )

    private fun requireClientSecret(): String =
        getClientSecret() ?: throw IllegalStateException(
            "Google OAuth client secret is not configured. Enter it in Settings → Google Calendar."
        )

    /**
     * Tolerates pasting the ID with a scheme or trailing slash
     * ("http://….apps.googleusercontent.com/").
     */
    private fun sanitizeClientId(raw: String): String =
        raw.trim().removePrefix("https://").removePrefix("http://").trimEnd('/')

    /**
     * Returns a usable access token, refreshing it first when expired
     * (60-second leeway). Returns null when refresh is impossible.
     */
    private fun getValidAccessToken(slot: Int): String? {
        val stored = store.getAccessToken(slot) ?: return null
        val expiry = store.getExpiryEpochMs(slot)
        if (expiry > 0 && System.currentTimeMillis() < expiry - REFRESH_LEEWAY_MS) {
            return stored
        }
        val refreshToken = store.getRefreshToken(slot) ?: return null
        return try {
            refreshAccessToken(slot, refreshToken)
        } catch (e: Exception) {
            null
        }
    }

    private fun refreshAccessToken(slot: Int, refreshToken: String): String {
        val clientId = requireClientId()
        val clientSecret = requireClientSecret()
        val form = FormBody.Builder()
            .add("client_id", clientId)
            .add("client_secret", clientSecret)
            .add("grant_type", GRANT_TYPE_REFRESH_TOKEN)
            .add("refresh_token", refreshToken)
            .build()
        val request = Request.Builder()
            .url(TOKEN_URL)
            .post(form)
            .build()
        val json = postJson(request, "token refresh")
        val accessToken = json.getString("access_token")
        store.saveTokens(
            slot = slot,
            accessToken = accessToken,
            refreshToken = json.optString("refresh_token").takeIf { it.isNotBlank() },
            expiresInSec = json.optLong("expires_in", 3600)
        )
        return accessToken
    }

    // ------------------------------------------------------------------
    // Calendar API
    // ------------------------------------------------------------------

    /** The "primary" calendar entry's summary is the Google account name. */
    private fun fetchPrimaryAccountName(bearerToken: String): String? {
        val request = authedGet(CALENDAR_LIST_URL, bearerToken)
        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val items = JSONObject(response.body?.string() ?: "")
                    .optJSONArray("items") ?: return null
                for (i in 0 until items.length()) {
                    val entry = items.getJSONObject(i)
                    if (entry.optBoolean("primary", false)) {
                        return entry.optString("summary", null).takeIf { it.isNotBlank() }
                    }
                }
                null
            }
        } catch (e: IOException) {
            null
        }
    }

    private fun fetchEvents(
        bearerToken: String,
        accountId: String,
        accountName: String
    ): List<com.lifedashboard.tv.model.CalendarEvent> {
        // Anchor the fetch to the displayed week (previous Sunday .. next
        // Sunday), not to "now", so the whole week — including events that
        // have already passed — stays populated all day. Bounded on both
        // ends so a large maxResults only covers the strip.
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now()
        val timeMin = URLEncoder.encode(
            CalendarWindow.fetchStart(today, zone).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
            Charsets.UTF_8.name()
        )
        val timeMax = URLEncoder.encode(
            CalendarWindow.fetchEnd(today, zone).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
            Charsets.UTF_8.name()
        )
        val url = "$EVENTS_URL?timeMin=$timeMin&timeMax=$timeMax&singleEvents=true&orderBy=startTime&maxResults=100"
        val request = authedGet(url, bearerToken)
        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("Calendar events request failed: HTTP ${response.code}")
                }
                parseEvents(JSONObject(response.body?.string() ?: ""), accountId, accountName)
            }
        } catch (e: IOException) {
            throw e
        } catch (e: Exception) {
            throw IOException("Failed to parse calendar events", e)
        }
    }

    private fun parseEvents(
        root: JSONObject,
        accountId: String,
        accountName: String
    ): List<com.lifedashboard.tv.model.CalendarEvent> {
        val items = root.optJSONArray("items") ?: return emptyList()
        val events = ArrayList<com.lifedashboard.tv.model.CalendarEvent>(items.length())
        for (i in 0 until items.length()) {
            val item = items.getJSONObject(i)
            if (item.optString("status") == "cancelled") continue
            val startObj = item.optJSONObject("start") ?: continue
            val endObj = item.optJSONObject("end") ?: continue

            val allDay = startObj.has("date") && !startObj.has("dateTime")
            val start = parseEventTime(startObj, allDay)
            val end = parseEventTime(endObj, allDay)
            if (start == null || end == null) continue

            events += com.lifedashboard.tv.model.CalendarEvent(
                id = item.optString("id", "$accountId-$i"),
                accountId = accountId,
                accountName = accountName,
                title = item.optString("summary", "No title").ifBlank { "No title" },
                start = start,
                end = end,
                allDay = allDay
            )
        }
        return events
    }

    /**
     * Google returns either start.dateTime ("2026-09-25T10:00:00-04:00") or
     * start.date ("2026-09-26", all-day; end date is exclusive).
     */
    private fun parseEventTime(obj: JSONObject, allDay: Boolean): ZonedDateTime? {
        return try {
            if (allDay) {
                val date = obj.optString("date", "")
                    .takeIf { it.isNotBlank() }
                    ?.let(LocalDate::parse)
                    ?: return null
                date.atStartOfDay(ZoneId.systemDefault())
            } else {
                val dateTime = obj.optString("dateTime", "")
                    .takeIf { it.isNotBlank() } ?: return null
                ZonedDateTime.parse(dateTime)
            }
        } catch (e: Exception) {
            null
        }
    }

    // ------------------------------------------------------------------
    // HTTP plumbing
    // ------------------------------------------------------------------

    private fun authedGet(url: String, bearerToken: String): Request =
        Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $bearerToken")
            .build()

    /** POST that must succeed; throws IOException otherwise. */
    private fun postJson(request: Request, what: String): JSONObject {
        val (status, json) = postJsonWithStatus(request)
        if (status != 200) {
            val detail = json.optString("error_description", json.optString("error", "HTTP $status"))
            throw IOException("Google $what failed: $detail")
        }
        return json
    }

    private fun postJsonWithStatus(request: Request): Pair<Int, JSONObject> {
        client.newCall(request).execute().use { response ->
            val json = JSONObject(response.body?.string() ?: "{}")
            return response.code to json
        }
    }

    companion object {
        /** Baked-in default; a Settings override (if any) takes precedence. */
        const val DEFAULT_CLIENT_ID =
            "89894688120-7r59eh2r3b5p0ifga7hj1riduavhjf1s.apps.googleusercontent.com"

        private const val DEVICE_CODE_URL = "https://oauth2.googleapis.com/device/code"
        private const val TOKEN_URL = "https://oauth2.googleapis.com/token"
        private const val CALENDAR_LIST_URL =
            "https://www.googleapis.com/calendar/v3/users/me/calendarList"
        private const val EVENTS_URL =
            "https://www.googleapis.com/calendar/v3/calendars/primary/events"
        private const val SCOPE_CALENDAR_READONLY =
            "https://www.googleapis.com/auth/calendar.readonly"
        private const val GRANT_TYPE_DEVICE_CODE =
            "urn:ietf:params:oauth:grant-type:device_code"
        private const val GRANT_TYPE_REFRESH_TOKEN = "refresh_token"

        private const val DEFAULT_POLL_INTERVAL_SEC = 5L
        private const val SLOW_DOWN_EXTRA_SEC = 5L
        private const val POLL_TIMEOUT_MS = 10 * 60 * 1000L
        private const val REFRESH_LEEWAY_MS = 60 * 1000L
    }
}
