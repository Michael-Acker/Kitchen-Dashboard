package com.lifedashboard.tv.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import com.lifedashboard.tv.ui.theme.ThemeManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException

/**
 * In-app updater. The dashboard can pull new builds itself instead of
 * requiring a full reinstall through Downloader every time.
 *
 * How it works:
 * 1. The user enters their Downloader short code (e.g. "3923958") — or a
 *    direct APK URL — once in Settings → App updates.
 * 2. "Check for updates" resolves the code to its target .apk link, downloads
 *    that APK to the app's cache directory, reads its versionCode straight
 *    out of the package, and compares it with the installed version. Nothing
 *    is installed unless the remote build is actually newer AND is this same
 *    app (package name check).
 * 3. "Install update" fires the system package installer via FileProvider.
 *
 * App data (OAuth tokens, settings, widget layout) is preserved by Android
 * automatically on update installs — as long as every build uses the same
 * applicationId AND the same signing certificate. Never change the release
 * keystore once the first update-capable build ships.
 */
class UpdateManager(private val context: Context) {

    // Bounded waits: a hung socket must never stall a refresh pass forever.
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .writeTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .callTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .build()
    private val prefs = context.getSharedPreferences(ThemeManager.PREFS_NAME, Context.MODE_PRIVATE)

    sealed interface UpdateResult {
        data object UpToDate : UpdateResult
        data class Available(val apkFile: File, val remoteVersionCode: Long) : UpdateResult
        data class Failed(val message: String) : UpdateResult
    }

    /**
     * Where updates come from: either a Downloader short code ("3923958")
     * or a direct APK URL. The code is resolved to its target .apk link
     * on every check, so re-pointing the code at a new build is enough —
     * nothing needs re-typing on the TV.
     */
    fun getUpdateSource(): String? =
        prefs.getString(KEY_UPDATE_SOURCE, null)?.takeIf { it.isNotBlank() }

    fun saveUpdateSource(source: String) {
        prefs.edit().putString(KEY_UPDATE_SOURCE, source.trim()).apply()
    }

    fun currentVersionCode(): Long =
        PackageInfoCompat.getLongVersionCode(
            context.packageManager.getPackageInfo(context.packageName, 0)
        )

    fun currentVersionName(): String =
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""

    /**
     * Downloads the APK from the configured URL and compares versions.
     * [onProgress] is invoked from a background thread.
     */
    suspend fun checkForUpdate(
        onProgress: (bytesRead: Long, totalBytes: Long) -> Unit = { _, _ -> }
    ): UpdateResult = withContext(Dispatchers.IO) {
        val source = getUpdateSource()
            ?: return@withContext UpdateResult.Failed(
                "No update source set. Enter your Downloader code in Settings → App updates."
            )
        val url = try {
            resolveSource(source)
        } catch (e: Exception) {
            return@withContext UpdateResult.Failed(
                "Couldn't resolve that Downloader code: ${e.message ?: "network error"}"
            )
        }
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        val apkFile = File(dir, "life-dashboard-update.apk")
        try {
            download(url, apkFile, onProgress)
        } catch (e: Exception) {
            apkFile.delete()
            return@withContext UpdateResult.Failed(
                "Download failed: ${e.message ?: "network error"}"
            )
        }
        val archive = try {
            context.packageManager.getPackageArchiveInfo(apkFile.absolutePath, 0)
        } catch (_: Exception) {
            null
        }
        if (archive == null) {
            apkFile.delete()
            return@withContext UpdateResult.Failed("Downloaded file is not a valid APK.")
        }
        if (archive.packageName != context.packageName) {
            apkFile.delete()
            return@withContext UpdateResult.Failed(
                "Downloaded file is a different app (${archive.packageName}). Update URL may be wrong."
            )
        }
        val remoteVersion = PackageInfoCompat.getLongVersionCode(archive)
        if (remoteVersion > currentVersionCode()) {
            UpdateResult.Available(apkFile, remoteVersion)
        } else {
            apkFile.delete()
            UpdateResult.UpToDate
        }
    }

    /** Hands the downloaded APK to the system installer. */
    fun installApk(apkFile: File) {
        val uri: Uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apkFile
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)
    }

    private fun download(url: String, dest: File, onProgress: (Long, Long) -> Unit) {
        val request = Request.Builder().url(url).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            val body = response.body ?: throw IOException("Empty response")
            val total = body.contentLength()
            var read = 0L
            dest.outputStream().use { out ->
                body.byteStream().use { input ->
                    val buf = ByteArray(8192)
                    while (true) {
                        val n = input.read(buf)
                        if (n == -1) break
                        out.write(buf, 0, n)
                        read += n
                        onProgress(read, total)
                    }
                }
            }
        }
    }

    companion object {
        private const val KEY_UPDATE_SOURCE = "update_source"

        /** Downloader short-code pages embed the target .apk link in plain HTML. */
        private const val DOWNLOADER_PAGE_URL = "https://go.aftvnews.com/"
        private val APK_URL_REGEX = Regex("https?://[^\\s\"'<>]+\\.apk")
        private val CODE_REGEX = Regex("^\\d+$")
    }

    /**
     * Turns the configured source into a direct APK URL. A numeric code is
     * resolved through the Downloader shortener page; anything else is used
     * as-is.
     */
    @Throws(IOException::class)
    private fun resolveSource(source: String): String {
        val trimmed = source.trim()
        if (!CODE_REGEX.matches(trimmed)) return trimmed
        val request = Request.Builder().url(DOWNLOADER_PAGE_URL + trimmed).build()
        val html = client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            response.body?.string() ?: throw IOException("empty response")
        }
        return APK_URL_REGEX.find(html)?.value
            ?: throw IOException("no APK link found on the code page")
    }
}
