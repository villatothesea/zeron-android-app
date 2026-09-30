package sh.zeron.android.core

import sh.zeron.android.R
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import sh.zeron.android.BuildConfig
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL

/**
 * In-app updates from GitHub Releases (villatothesea/zeron-android-app).
 *
 * Release contract (see scripts/android/release.md): tag `roundN`, one APK
 * asset named `zeron-android-roundN.apk`, and a `versionCode: NNN` line in the
 * release notes. Every release is signed with the same key, so the system
 * installer accepts it as an update.
 */
class Updater(private val context: Context) {
    data class Release(
        val tag: String,
        val name: String,
        val notes: String,
        val versionCode: Long,
        val assetName: String,
        val assetApiUrl: String,
        val downloadUrl: String,
        val size: Long,
        val htmlUrl: String,
    ) {
        val newer: Boolean get() = versionCode > BuildConfig.VERSION_CODE.toLong()
    }

    class UpdateError(message: String) : IOException(message)

    private fun str(id: Int, vararg args: Any): String = AppLanguage.string(context, id, *args)

    private val prefs = context.getSharedPreferences("zeron-update", 0)
    private val secrets = SecretStore(context)

    var token: String?
        get() = secrets.get("github-token")
        set(value) = secrets.put("github-token", value?.trim())

    /** Optional prefix for mainland mirrors, e.g. `https://ghfast.top/`. */
    var mirror: String?
        get() = prefs.getString("mirror", null)?.ifBlank { null }
        set(value) = prefs.edit().putString("mirror", value?.trim()).apply()

    var lastCheckMs: Long
        get() = prefs.getLong("lastCheck", 0)
        set(value) = prefs.edit().putLong("lastCheck", value).apply()

    fun dueForQuietCheck(now: Long = System.currentTimeMillis()) = now - lastCheckMs > DAY_MS

    suspend fun latest(): Release = withContext(Dispatchers.IO) {
        val conn = open(URL("$API/repos/$REPO/releases/latest"), json = true, auth = true)
        val release = try {
            val code = conn.responseCode
            if (code == 404) throw UpdateError(str(R.string.update_err_no_release))
            if (code == 401) throw UpdateError(str(R.string.update_err_401))
            if (code == 403 || code == 429) {
                // The unauthenticated API allows 60 calls/hour per IP, and
                // carrier NAT shares that IP widely. The web redirect has no
                // such limit, so fall back to it before giving up.
                if (!token.isNullOrBlank()) throw UpdateError(str(R.string.update_err_rate_token))
                null
            } else {
                if (code !in 200..299) throw UpdateError(str(R.string.update_err_http, code))
                parse(JSONObject(conn.inputStream.bufferedReader().use { it.readText() }))
            }
        } finally {
            conn.disconnect()
        }
        (release ?: latestViaWeb()).also { lastCheckMs = System.currentTimeMillis() }
    }

    /**
     * No-API fallback: `github.com/<repo>/releases/latest` redirects to the
     * latest tag, and the asset URL is predictable from the contract. The
     * version comes from the tag (`roundN[-P]` -> N*100 + P).
     */
    private fun latestViaWeb(): Release {
        val conn = open(URL("$WEB/$REPO/releases/latest"), json = false, auth = false)
        val location = try {
            if (conn.responseCode !in 300..399) {
                throw UpdateError(str(R.string.update_err_rate_redirect))
            }
            conn.getHeaderField("Location")
        } finally {
            conn.disconnect()
        }
        val tag = location?.substringAfter("/releases/tag/", "")?.substringBefore('?')?.let { Uri.decode(it) }
        if (tag.isNullOrBlank()) throw UpdateError(str(R.string.update_err_no_release))
        val asset = "zeron-android-$tag.apk"
        return Release(
            tag = tag,
            name = tag,
            notes = "",
            versionCode = versionFromTag(tag),
            assetName = asset,
            assetApiUrl = "",
            downloadUrl = "$WEB/$REPO/releases/download/$tag/$asset",
            size = 0,
            htmlUrl = location,
        )
    }

    private fun parse(o: JSONObject): Release {
        val tag = o.optString("tag_name")
        val notes = o.optString("body")
        val assets = o.optJSONArray("assets")
        var asset: JSONObject? = null
        for (i in 0 until (assets?.length() ?: 0)) {
            val a = assets!!.getJSONObject(i)
            if (a.optString("name").endsWith(".apk")) {
                if (asset == null || a.optString("name") == "zeron-android-$tag.apk") asset = a
            }
        }
        asset ?: throw UpdateError(str(R.string.update_err_no_apk, tag))
        val code = Regex("""versionCode\s*[:=]\s*(\d+)""").find(notes)?.groupValues?.get(1)?.toLongOrNull()
            ?: versionFromTag(tag)
        return Release(
            tag = tag,
            name = o.optString("name").ifBlank { tag },
            notes = notes.lines().filterNot { it.trim().startsWith("versionCode") }.joinToString("\n").trim(),
            versionCode = code,
            assetName = asset.optString("name"),
            assetApiUrl = asset.optString("url"),
            downloadUrl = asset.optString("browser_download_url"),
            size = asset.optLong("size"),
            htmlUrl = o.optString("html_url"),
        )
    }

    private fun open(url: URL, json: Boolean, auth: Boolean, octet: Boolean = false): HttpURLConnection {
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = 30_000
        conn.readTimeout = 60_000
        conn.instanceFollowRedirects = false
        conn.setRequestProperty("User-Agent", "zeron-android/${BuildConfig.VERSION_NAME}")
        if (json) {
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        }
        if (octet) conn.setRequestProperty("Accept", "application/octet-stream")
        val t = token
        if (auth && !t.isNullOrBlank() && url.host.endsWith("github.com")) {
            conn.setRequestProperty("Authorization", "Bearer $t")
        }
        return conn
    }

    /**
     * Download the APK (resumable via Range) into the app cache. With a token
     * the API asset endpoint is used; the redirect to GitHub's CDN is followed
     * WITHOUT the token. A mirror prefix wraps the public download URL.
     */
    suspend fun download(release: Release, progress: (Long, Long) -> Unit): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.filter { !it.name.startsWith(release.tag) }?.forEach { it.delete() }
        val done = File(dir, "${release.tag}.apk")
        if (done.exists() && release.size > 0 && done.length() == release.size) return@withContext done
        val part = File(dir, "${release.tag}.apk.part")
        val start: URL
        val useToken: Boolean
        val m = mirror
        if (m != null) {
            start = URL(m + release.downloadUrl)
            useToken = false
        } else if (!token.isNullOrBlank() && release.assetApiUrl.isNotEmpty()) {
            start = URL(release.assetApiUrl)
            useToken = true
        } else {
            start = URL(release.downloadUrl)
            useToken = false
        }
        var attempt = 0
        while (true) {
            try {
                fetch(start, useToken, part, release.size, progress)
                break
            } catch (e: IOException) {
                if (e is UpdateError || ++attempt >= 4) {
                    throw if (e is UpdateError) e else UpdateError(
                        str(if (m == null) R.string.update_err_interrupted_mirror else R.string.update_err_interrupted, e.message ?: e.javaClass.simpleName),
                    )
                }
                Thread.sleep(2_000L * attempt)
            }
        }
        if (!part.renameTo(done)) throw UpdateError(str(R.string.update_err_save))
        done
    }

    private fun fetch(start: URL, useToken: Boolean, part: File, total: Long, progress: (Long, Long) -> Unit) {
        var url = start
        var auth = useToken
        var hops = 0
        while (true) {
            val have = if (part.exists()) part.length() else 0L
            if (total > 0 && have >= total) return
            val conn = open(url, json = false, auth = auth, octet = auth)
            if (have > 0) conn.setRequestProperty("Range", "bytes=$have-")
            val code = conn.responseCode
            if (code in 300..399) {
                val next = conn.getHeaderField("Location") ?: throw UpdateError(str(R.string.update_err_redirect))
                conn.disconnect()
                url = URL(url, next)
                auth = false // never send the token to the CDN
                if (++hops > 6) throw UpdateError(str(R.string.update_err_redirects))
                continue
            }
            if (code == 416) {
                part.delete()
                conn.disconnect()
                continue
            }
            if (code !in 200..299) {
                conn.disconnect()
                throw UpdateError(str(R.string.update_err_download_http, code))
            }
            val append = code == 206
            val base = if (append) have else 0L
            val length = conn.contentLengthLong.let { if (it > 0) it + base else total }
            RandomAccessFile(part, "rw").use { out ->
                if (!append) out.setLength(0)
                out.seek(base)
                var written = base
                val buf = ByteArray(64 * 1024)
                conn.inputStream.use { input ->
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        written += n
                        progress(written, length)
                    }
                }
                if (length > 0 && written < length) throw IOException("connection closed early")
            }
            conn.disconnect()
            return
        }
    }

    /** Android 8+: the per-app "install unknown apps" switch. */
    fun canInstall(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

    fun unknownSourcesIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun installIntent(apk: File): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", apk)
        return Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    companion object {
        /** `round5` -> 500, `round5-2` / `round5.2` -> 502, else 0. */
        fun versionFromTag(tag: String): Long {
            val m = Regex("""round(\d+)(?:[.-](\d+))?""").find(tag) ?: return 0L
            val round = m.groupValues[1].toLongOrNull() ?: return 0L
            val patch = m.groupValues[2].toLongOrNull() ?: 0L
            return round * 100 + patch
        }

        const val WEB = "https://github.com"
        const val REPO = "villatothesea/zeron-android-app"
        const val API = "https://api.github.com"
        const val DAY_MS = 24L * 60 * 60 * 1000
    }
}
