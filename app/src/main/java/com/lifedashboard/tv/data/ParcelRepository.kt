package com.lifedashboard.tv.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.JavaNetCookieJar
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.io.IOException
import java.net.CookieManager
import java.net.CookiePolicy
import java.net.URLEncoder
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * READ-ONLY Parcel Pending client. The only POST this class ever makes is the
 * login form; it never opens lockers, releases packages, or changes anything
 * server-side.
 *
 * A session cookie jar keeps the login session alive across calls. If the
 * parcel-history page comes back as the login form (redirect to /login, or a
 * password field in the body), we re-login once and retry.
 *
 * Contract: network failures surface as [IOException] (the UI catches them);
 * any HTML parsing problem yields an empty list instead of throwing.
 */
class ParcelRepository(private val context: Context) : ParcelRepo {

    private val cookieManager = CookieManager(null, CookiePolicy.ACCEPT_ALL)
    private val client = OkHttpClient.Builder()
        .cookieJar(JavaNetCookieJar(cookieManager))
        .followRedirects(true)
        .followSslRedirects(true)
        // Bounded waits: a hung socket must never stall a refresh pass forever.
        .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .writeTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .callTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .build()
    private val store = TokenStore(context)

    override fun hasCredentials(): Boolean =
        store.getParcelUsername() != null && store.getParcelPassword() != null

    override fun savedUsername(): String? = store.getParcelUsername()

    override fun saveCredentials(username: String, password: String) =
        store.saveParcelCredentials(username.trim(), password)

    override fun clearCredentials() {
        store.clearParcelCredentials()
        cookieManager.cookieStore.removeAll()
    }

    override suspend fun getParcels(): List<com.lifedashboard.tv.model.ParcelInfo> =
        withContext(Dispatchers.IO) {
            val username = store.getParcelUsername() ?: return@withContext emptyList()
            val password = store.getParcelPassword() ?: return@withContext emptyList()

            // Network/login failures propagate: the widget reports them instead
            // of crashing or silently showing nothing.
            val history = fetchAuthenticatedHistory(username, password)
            try {
                parseParcels(history).sortedWith(
                    compareByDescending<com.lifedashboard.tv.model.ParcelInfo> { it.isPending }
                )
            } catch (e: Exception) {
                // HTML parse problems must never throw out of getParcels.
                emptyList()
            } catch (e: Error) {
                // Even JVM Errors from pathological HTML must not escape.
                emptyList()
            }
        }

    override suspend fun testLogin(): String = withContext(Dispatchers.IO) {
        val username = store.getParcelUsername() ?: return@withContext "No login saved."
        val password = store.getParcelPassword() ?: return@withContext "No login saved."
        try {
            fetchAuthenticatedHistory(username, password)
            "Login OK — connected to Parcel Pending."
        } catch (e: IOException) {
            e.message?.take(140) ?: "Login failed."
        } catch (t: Throwable) {
            "Error: ${(t.message ?: t::class.java.simpleName).take(140)}"
        }
    }

    /**
     * Structural dump of the parcel-history results table for diagnosing
     * parsing mismatches against the live site: final URL, header names,
     * row count, and the first 3 data rows' cell text (truncated). The
     * parsed view of those rows is appended for comparison. Never throws.
     */
    override suspend fun debugTableDump(): String = withContext(Dispatchers.IO) {
        val username = store.getParcelUsername() ?: return@withContext "No login saved."
        val password = store.getParcelPassword() ?: return@withContext "No login saved."
        try {
            val page = fetchAuthenticatedHistory(username, password)
            val sb = StringBuilder()
            sb.appendLine("URL: ${page.url}")
            val table = findResultsTable(page.doc) ?: return@withContext sb
                .appendLine("No results table found.")
                .toString()
            val headerRow = table.selectFirst("thead tr") ?: table.selectFirst("tr")
            val headers = headerRow?.select("th, td")?.eachText() ?: emptyList()
            sb.appendLine("HEADERS (${headers.size}):")
            headers.forEachIndexed { i, h -> sb.appendLine("  [$i] ${h.take(60)}") }
            val cols = headerColumnMap(table)
            sb.appendLine("COLUMN MAP: package=${cols.packageCode} status=${cols.status} " +
                "kiosk=${cols.kiosk} deliveredAt=${cols.deliveredAt} recipient=${cols.recipient}")
            val rows = (table.select("tbody tr").ifEmpty { table.select("tr") })
                .toList() // Elements.filter(NodeFilter) shadows stdlib filter; use a plain list
                .filter { it.select("th").isEmpty() }
            sb.appendLine("DATA ROWS: ${rows.size} (showing up to 3)")
            rows.take(3).forEachIndexed { ri, row ->
                val cells = row.select("td")
                sb.appendLine("ROW $ri classes='${row.classNames().joinToString(",").take(80)}' cells=${cells.size}")
                cells.forEachIndexed { ci, td ->
                    sb.appendLine("  cell[$ci]: ${td.text().take(150)}")
                }
                val parsed = runCatching { rowToParcel(row, cells, cols) }.getOrNull()
                sb.appendLine("  PARSED: code='${parsed?.packageCode}' status=${parsed?.statusCode} " +
                    "(${parsed?.statusLabel}) pickup='${parsed?.pickupCode}' " +
                    "courier='${parsed?.courier}' box='${parsed?.lockerBox}' " +
                    "kiosk='${parsed?.kioskName}' to='${parsed?.recipient}' date='${parsed?.deliveredAt}'" +
                    if (parsed == null) " [SKIPPED - no details cell]" else "")
            }
            sb.toString()
        } catch (e: IOException) {
            "Fetch failed: ${e.message?.take(140)}"
        } catch (t: Throwable) {
            "Error: ${(t.message ?: t::class.java.simpleName).take(140)}"
        }
    }

    /**
     * Returns the parcel-history page, logging in first when the site shows
     * us an unauthenticated page. Throws IOException on network failures or
     * when the login is not accepted.
     */
    private fun fetchAuthenticatedHistory(username: String, password: String): Page {
        var history = getHistoryPage()
        if (isLoginPage(history)) {
            login(username, password)
            history = getHistoryPage()
            if (isLoginPage(history)) {
                // Login did not take (bad credentials or site changed).
                throw IOException(
                    "Parcel Pending login was not accepted — check the username and password."
                )
            }
        }
        return history
    }

    // ------------------------------------------------------------------
    // HTTP
    // ------------------------------------------------------------------

    private data class Page(val url: String, val doc: Document)

    private fun get(url: String): Page {
        val request = Request.Builder().url(url).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Parcel Pending request failed: HTTP ${response.code}")
            }
            val body = response.body?.string() ?: throw IOException("Empty Parcel Pending response")
            return Page(
                url = response.request.url.toString(),
                doc = Jsoup.parse(body, response.request.url.toString())
            )
        }
    }

    /** The filtered history view: last 90 days, newest deliveries first. */
    private fun getHistoryPage(): Page {
        val fmt = DateTimeFormatter.ofPattern("MM/dd/yyyy")
        val end = LocalDate.now().format(fmt)
        val start = LocalDate.now().minusDays(90).format(fmt)
        val enc = Charsets.UTF_8.name()
        val url = "$HISTORY_URL" +
            "?parcel_delivery_date_start=${URLEncoder.encode(start, enc)}" +
            "&parcel_delivery_date_end=${URLEncoder.encode(end, enc)}" +
            "&sort_by=deliveryDate&sort_order=DESC"
        return get(url)
    }

    private fun isLoginPage(page: Page): Boolean =
        page.url.contains("/login", ignoreCase = true) ||
            // Unauthenticated history requests land here instead of /login.
            page.url.contains("/first-visit", ignoreCase = true) ||
            page.doc.selectFirst("input[type=password]") != null

    /**
     * Logs in by POSTing the site's own login form. Hidden inputs (e.g. CSRF
     * tokens) are harvested from the login page and echoed back; only
     * username/password are filled in by us.
     */
    private fun login(username: String, password: String) {
        val loginPage = get(LOGIN_URL)
        val form = loginPage.doc.selectFirst("form") ?: throw IOException("Login form not found")

        val formBody = FormBody.Builder()
        // Echo hidden fields (authenticity tokens, etc.) unchanged.
        form.select("input[type=hidden]").forEach { input ->
            val name = input.attr("name")
            if (name.isNotBlank()) formBody.add(name, input.attr("value"))
        }
        // Fill in our credentials under the site's own field names.
        formBody.add(usernameFieldName(form), username)
        formBody.add(passwordFieldName(form), password)

        val action = form.attr("abs:action").ifBlank { LOGIN_URL }
        val request = Request.Builder()
            .url(action)
            .post(formBody.build())
            .build()
        client.newCall(request).execute().use { response ->
            response.body?.close()
            if (!response.isSuccessful) {
                throw IOException("Parcel Pending login failed: HTTP ${response.code}")
            }
        }
    }

    /** Finds the username/email field name on the login form, defensively. */
    private fun usernameFieldName(form: Element): String {
        // Elements has a member filter(NodeFilter) that shadows Kotlin's stdlib filter;
        // copy to a plain list first.
        val candidates = form.select("input").toList().filter {
            val type = it.attr("type").lowercase()
            type != "password" && type != "hidden" && type != "submit" && type != "checkbox"
        }
        val byName = candidates.firstOrNull {
            val n = it.attr("name").lowercase()
            n.contains("user") || n.contains("email") || n.contains("login")
        }
        return (byName ?: candidates.firstOrNull())?.attr("name")?.ifBlank { "username" }
            ?: "username"
    }

    private fun passwordFieldName(form: Element): String =
        form.selectFirst("input[type=password]")?.attr("name")?.ifBlank { "password" }
            ?: "password"

    // ------------------------------------------------------------------
    // HTML parsing (defensive — the table layout may change)
    // ------------------------------------------------------------------

    private fun parseParcels(page: Page): List<com.lifedashboard.tv.model.ParcelInfo> {
        val table = findResultsTable(page.doc) ?: return emptyList()
        val rows = table.select("tbody tr").ifEmpty { table.select("tr") }
        if (rows.isEmpty()) return emptyList()

        val headerMap = headerColumnMap(table)
        val parcels = ArrayList<com.lifedashboard.tv.model.ParcelInfo>()
        for (row in rows) {
            if (row.select("th").isNotEmpty()) continue // header row
            val cells = row.select("td")
            if (cells.isEmpty()) continue
            parcels += rowToParcel(row, cells, headerMap) ?: continue
        }
        return parcels
    }

    /** Picks the table that looks like the parcel-history results grid. */
    private fun findResultsTable(doc: Document): Element? {
        val tables = doc.select("table")
        if (tables.isEmpty()) return null
        return tables.firstOrNull { table ->
            val headerText = table.select("thead th, tr:first-child th, tr:first-child td")
                .eachText().joinToString(" ").lowercase()
            (headerText.contains("package") || headerText.contains("tracking")) &&
                (headerText.contains("status") || headerText.contains("deliver"))
        } ?: tables.maxByOrNull { it.select("tbody tr").size }
    }

    /**
     * Maps logical columns to cell indexes from the table's header row, by
     * keyword matching. Unknown columns map to -1.
     *
     * The site renders a per-row details cell ("Package Status: … Kiosk: …
     * Locker Box #: …") whose header can contain the same keywords, so every
     * finder below excludes "detail*" headers — details are parsed separately
     * by [parseDetails], never through the column map.
     */
    private data class ColumnMap(
        val packageCode: Int,
        val status: Int,
        val kiosk: Int,
        val deliveredAt: Int,
        val recipient: Int
    )

    private fun headerColumnMap(table: Element): ColumnMap {
        val headerRow = table.selectFirst("thead tr") ?: table.selectFirst("tr")
        val headers = headerRow?.select("th, td")?.eachText()?.map { it.lowercase() }
            ?: emptyList()
        fun find(vararg keywords: String): Int =
            headers.indexOfFirst { h ->
                keywords.any { h.contains(it) } && !h.contains("detail")
            }
        // The delivery date column: prefer an explicit date/time header. A
        // bare "deliver*" header must ALSO name a date/time — otherwise it
        // is the "Delivered To" (recipient) column in disguise, and the date
        // falls back to the Activity cell's "Delivered: …" timestamp.
        val deliveredAt = find("date", "time").let { dateIdx ->
            if (dateIdx >= 0) dateIdx
            else headers.indexOfFirst { h ->
                h.contains("deliver") && (h.contains("date") || h.contains("time")) &&
                    !h.contains("detail") && !h.contains("code") &&
                    !h.contains("package") && !h.contains("status")
            }
        }
        return ColumnMap(
            packageCode = find("tracking", "parcel").let { idx ->
                // "package" last: "Delivered Package Code" lives in details.
                if (idx >= 0) idx else headers.indexOfFirst { h ->
                    h.contains("package") && !h.contains("detail") &&
                        !h.contains("deliver") && !h.contains("status")
                }
            },
            status = find("status"),
            kiosk = find("kiosk", "locker", "location", "room"),
            deliveredAt = deliveredAt,
            recipient = find("recipient", "resident", "name", "tenant")
        )
    }

    private fun rowToParcel(
        row: Element,
        cells: org.jsoup.select.Elements,
        cols: ColumnMap
    ): com.lifedashboard.tv.model.ParcelInfo? {
        // A real package row always carries the "Package Status: …" details
        // dump. Expandable sub-rows / activity detail rows don't — they must
        // be SKIPPED, never defaulted to pending (that produced the bogus
        // "30 packages waiting": every sub-row counted as a waiting parcel).
        val detailsText = cells.firstOrNull { isDetailsCell(it.text()) }?.text()
            ?: return null
        val details = parseDetails(detailsText)

        // Only "Delivered" rows are waiting at the kiosk. Anything else
        // (Picked up, etc.) is skipped entirely — never defaulted to pending.
        if (!details.status.equals("Delivered", ignoreCase = true)) return null

        val statusCode = detectStatusCode(row, "", details.status)
        // The kiosk pickup code ("Package Code:" in the details dump) is the
        // parcel's identity; the table's "Package ID" column is the site's
        // internal row id and is useless at the kiosk.
        val packageCode = details.pickupCode.ifBlank {
            cellText(cells, cols.packageCode).ifBlank { cellText(cells, 0) }
        }
        if (packageCode.isBlank()) return null

        return com.lifedashboard.tv.model.ParcelInfo(
            packageCode = packageCode,
            statusCode = statusCode,
            kioskName = details.kiosk.ifBlank { cellText(cells, cols.kiosk) },
            deliveredAt = extractDeliveredAt(cells),
            recipient = extractRecipient(cells).takeIf { it.isNotBlank() },
            pickupCode = details.pickupCode,
            lockerBox = details.lockerBox,
            courier = details.courier
        )
    }

    /**
     * A details cell carries the whole "Package Status: … Kiosk: …" dump;
     * it must never leak into the scalar columns.
     */
    private fun cellText(cells: org.jsoup.select.Elements, index: Int): String {
        if (index !in cells.indices) return ""
        val text = cells[index].text().trim()
        return if (isDetailsCell(text)) "" else text
    }

    /** True when a cell holds the row's "Package Status: …" details dump. */
    private fun isDetailsCell(text: String): Boolean = text.contains("Package Status:")

    /**
     * Splits a details dump on its known field labels. Values run from one
     * label to the next, so wrapped text inside a value survives.
     */
    private data class Details(
        val pickupCode: String,
        val status: String,
        val kiosk: String,
        val lockerBox: String,
        val courier: String
    )

    private fun parseDetails(text: String): Details {
        val blank = Details("", "", "", "", "")
        if (text.isBlank()) return blank
        // Labels as the site renders them (from the live table dump
        // 2026-09-26): "Package Code:" also matches the older "Delivered
        // Package Code:" form, since it is a substring at the same value
        // position. "Kiosk Label:" must be listed explicitly — a bare
        // "Label:" entry would match inside it and let "Kiosk:" swallow
        // "A Kiosk" as its value.
        val labels = listOf(
            "Package Code:", "Package Status:", "Kiosk:",
            "Kiosk Label:", "Locker Box #:", "Courier:",
            "Master Tracking:", "Locker Description:"
        )
        val positions = labels.mapNotNull { label ->
            val idx = text.indexOf(label)
            if (idx >= 0) label to idx else null
        }.sortedBy { it.second }
        if (positions.isEmpty()) return blank
        fun value(label: String): String {
            val pos = positions.indexOfFirst { it.first == label }
            if (pos < 0) return ""
            val start = positions[pos].second + label.length
            val end = if (pos + 1 < positions.size) positions[pos + 1].second else text.length
            return text.substring(start, end).trim()
        }
        return Details(
            pickupCode = value("Package Code:"),
            status = value("Package Status:"),
            kiosk = value("Kiosk:"),
            lockerBox = value("Locker Box #:"),
            courier = value("Courier:")
        )
    }

    /**
     * The "Delivered: MM/dd/yyyy h:mm:ss a" timestamp in the row's Activity
     * cell, formatted for the widget ("Sep 25, 6:04 PM"). Falls back to any
     * date-like string in the row when the Activity cell has none.
     */
    private val DELIVERED_RE =
        Regex("""Delivered:\s*(\d{1,2}/\d{1,2}/\d{2,4}\s+\d{1,2}:\d{2}(?::\d{2})?\s*[APap][Mm])""")

    private fun extractDeliveredAt(cells: org.jsoup.select.Elements): String {
        for (cell in cells) {
            val text = cell.text()
            if (isDetailsCell(text)) continue
            DELIVERED_RE.find(text)?.let { return formatDeliveredAt(it.groupValues[1]) }
        }
        return findDateInRow(cells)
    }

    private fun formatDeliveredAt(raw: String): String {
        return try {
            val parser = java.time.format.DateTimeFormatterBuilder()
                .parseCaseInsensitive()
                .appendPattern("M/d/yyyy h:mm[:ss] a")
                .toFormatter(java.util.Locale.US)
            val parsed = java.time.LocalDateTime.parse(raw.trim(), parser)
            parsed.format(java.time.format.DateTimeFormatter.ofPattern("MMM d, h:mm a", java.util.Locale.US))
        } catch (_: Exception) {
            raw.trim()
        }
    }

    /**
     * The recipient name from the row's "Delivered To" cell, which reads
     * like "Angela Pu P Property: ... Unit: ... Email: ... Phone: ...".
     * Only the name is kept; the property/email/phone dump is not shown.
     */
    private fun extractRecipient(cells: org.jsoup.select.Elements): String {
        for (cell in cells) {
            val text = cell.text()
            if (isDetailsCell(text)) continue
            if (text.contains("Property:") || text.contains("Email:")) {
                return text.substringBefore("Property:")
                    .substringBefore("Email:")
                    .substringBefore("Phone:")
                    .trim()
                    .take(40)
            }
        }
        return ""
    }

    /** Falls back to a date-like string in any non-details cell of the row. */
    private val DATE_RE = Regex("""\d{1,2}/\d{1,2}/\d{2,4}(?:\s+\d{1,2}:\d{2}\s*[AP]M)?""")

    private fun findDateInRow(cells: org.jsoup.select.Elements): String {
        for (cell in cells) {
            val text = cell.text()
            if (isDetailsCell(text)) continue
            DATE_RE.find(text)?.let { return it.value.trim() }
        }
        return ""
    }

    /**
     * Derives the status code from the status cell, the details dump's
     * "Package Status:", row classes/text, defensively:
     * 1001=Pending, 1002=Picked Up, 1003=Oversized-Pending, 1004=Oversized-Picked Up.
     *
     * On the site "Delivered" means delivered TO the locker (awaiting
     * pickup) — it counts as pending, same as "Pending". Only an explicit
     * picked-up/collected signal flips a parcel to 1002/1004.
     */
    private fun detectStatusCode(row: Element, statusCell: String, detailsStatus: String): Int {
        val marker = (row.classNames().joinToString(" ") + " " +
            statusCell + " " + detailsStatus).lowercase()
        // Literal status codes sometimes appear in row classes or text.
        for (code in listOf(1001, 1002, 1003, 1004)) {
            if (marker.contains(code.toString())) return code
        }
        val oversized = marker.contains("oversize")
        // "Ready for pickup" / "awaiting pickup" contain "pickup" but mean
        // the parcel is still waiting — check those before the picked-up test.
        val awaitingPickup = marker.contains("ready for pickup") ||
            marker.contains("awaiting pickup") ||
            marker.contains("not picked up") ||
            marker.contains("not collected")
        val pickedUp = !awaitingPickup && (
            marker.contains("picked up") ||
                marker.contains("picked-up") ||
                marker.contains("collected") ||
                marker.contains("retrieved") ||
                // bare "pickup" (e.g. "pickup date") only counts when it does
                // not read as awaiting pickup — handled above.
                marker.contains("pickup")
            )
        return when {
            oversized && pickedUp -> 1004
            pickedUp -> 1002
            oversized -> 1003
            else -> 1001 // pending/delivered/awaiting/unknown: still waiting
        }
    }

    companion object {
        private const val HISTORY_URL = "https://my.parcelpending.com/parcel-history"
        private const val LOGIN_URL = "https://my.parcelpending.com/login"
    }
}
