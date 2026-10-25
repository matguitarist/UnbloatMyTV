package io.github.matguitarist.unbloatmytv.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.matguitarist.unbloatmytv.LocText
import io.github.matguitarist.unbloatmytv.R
import io.github.matguitarist.unbloatmytv.UnbloatApp
import io.github.matguitarist.unbloatmytv.str
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Updates from the project's GitHub releases, like the desktop apps: the latest release, its
 * UnbloatMyTV-android.apk, checked against the SHA-256 GitHub publishes, then installed by Android,
 * which asks the person to confirm. Only the same signing key can update the app.
 */
object Updater {
    private const val REPO = "matguitarist/UnbloatMyTV"
    private const val ASSET = "UnbloatMyTV-android.apk"

    data class Info(val version: String, val notes: String, val url: String, val size: Long, val sha256: String, val page: String)

    /** What Android answered after an install was started: shown by the update dialog. */
    var installError by mutableStateOf<String?>(null)

    val current: String
        get() = runCatching {
            UnbloatApp.instance.packageManager.getPackageInfo(UnbloatApp.instance.packageName, 0).versionName
        }.getOrNull().orEmpty()

    /** The latest release if it is newer than this copy, otherwise null. Throws when GitHub can't be reached. */
    suspend fun check(): Info? = withContext(Dispatchers.IO) {
        val root = release("latest")
        val version = root.str("tag_name").trimStart('v', 'V')
        if (!isNewer(version, current)) return@withContext null
        val asset = root["assets"]?.jsonArray?.map { it.jsonObject }?.firstOrNull { it.str("name") == ASSET } ?: return@withContext null
        val digest = asset.str("digest")
        if (!digest.startsWith("sha256:")) return@withContext null // no checksum, no update
        Info(
            version = version,
            notes = cleanNotes(root.str("body")),
            url = asset.str("browser_download_url"),
            size = asset["size"]?.jsonPrimitive?.longOrNull ?: 0,
            sha256 = digest.removePrefix("sha256:").lowercase(),
            page = root.str("html_url").ifEmpty { "https://github.com/$REPO/releases/latest" },
        )
    }

    /** The notes of this version's release, in the phone's language, for "What's new" after an update. */
    suspend fun notesFor(version: String): String = withContext(Dispatchers.IO) {
        cleanNotes(release("tags/v$version").str("body"))
    }

    private fun release(which: String): JsonObject {
        val c = URL("https://api.github.com/repos/$REPO/releases/$which").openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 20_000
        c.setRequestProperty("Accept", "application/vnd.github+json")
        c.setRequestProperty("User-Agent", "UnbloatMyTV-Android/$current")
        try {
            if (c.responseCode != 200) throw IOException("HTTP ${c.responseCode}")
            return Json.parseToJsonElement(c.inputStream.bufferedReader().readText()).jsonObject
        } finally {
            c.disconnect()
        }
    }

    /** Downloads the update into the app's cache. Throws if it doesn't match the published checksum. */
    suspend fun download(info: Info, onProgress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        val file = File(UnbloatApp.instance.cacheDir, "update.apk")
        val sha = MessageDigest.getInstance("SHA-256")
        val c = URL(info.url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 30_000
        c.setRequestProperty("User-Agent", "UnbloatMyTV-Android/$current")
        try {
            c.inputStream.use { input ->
                file.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        sha.update(buf, 0, n)
                        done += n
                        if (info.size > 0) onProgress(done.toFloat() / info.size)
                    }
                }
            }
        } finally {
            c.disconnect()
        }
        val hex = sha.digest().joinToString("") { "%02x".format(it) }
        if (hex != info.sha256) {
            file.delete()
            throw SecurityException(str(R.string.Update_ChecksumFailed))
        }
        file
    }

    /** Whether Android lets this app install updates; the first time, the person allows it in Settings. */
    fun canInstall(ctx: Context): Boolean = ctx.packageManager.canRequestPackageInstalls()

    /** Hands the file to Android's installer. The answer comes back to [InstallReceiver]. */
    fun install(ctx: Context, apk: File) {
        installError = null
        val installer = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(ctx.packageName)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("update.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
            val intent = PendingIntent.getBroadcast(ctx, id, Intent(ctx, InstallReceiver::class.java), flags)
            session.commit(intent.intentSender)
        }
    }

    /** "1.10.0" is newer than "1.9.2". */
    fun isNewer(a: String, b: String): Boolean {
        val x = a.split('.').map { it.toIntOrNull() ?: 0 }
        val y = b.split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(x.size, y.size)) {
            val d = x.getOrElse(i) { 0 } - y.getOrElse(i) { 0 }
            if (d != 0) return d > 0
        }
        return false
    }

    /**
     * Release notes are Markdown with the English first and a "Français :" part after: show the
     * phone's language, as plain text.
     */
    private fun cleanNotes(md: String): String {
        val lines = md.replace("\r", "").lines()
        val fr = lines.indexOfFirst { it.trim().trimStart('#', '*', ' ').startsWith("Français") }
        val part = when {
            fr < 0 -> lines
            LocText.isFrench -> lines.drop(fr + 1)
            else -> lines.take(fr)
        }
        return part.joinToString("\n")
            .replace(Regex("^#+\\s*", RegexOption.MULTILINE), "")
            .replace(Regex("^\\s*[-*]\\s+", RegexOption.MULTILINE), "• ")
            .replace("**", "").replace("`", "")
            .replace(Regex("\\[([^\\]]+)\\]\\([^)]+\\)"), "$1")
            .trim()
    }

    private fun JsonObject.str(key: String) = this[key]?.jsonPrimitive?.content.orEmpty()
}

/** Android's answer about an update: ask the person to confirm, or report a failure. */
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            PackageInstaller.STATUS_SUCCESS -> Unit // the app is replaced and closes; it opens as the new version
            else -> Updater.installError = str(
                R.string.Update_InstallFailed,
                intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "status ${intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)}",
            )
        }
    }
}
