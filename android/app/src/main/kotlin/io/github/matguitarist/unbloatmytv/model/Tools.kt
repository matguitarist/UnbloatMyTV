package io.github.matguitarist.unbloatmytv.model

import io.github.matguitarist.unbloatmytv.UnbloatApp
import io.github.matguitarist.unbloatmytv.tv.Tv
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Base64

// The tools' readings, the same as the desktop apps'. Reading changes nothing on the TV.

/** Software, update and boot security facts about the TV. */
data class TvInfo(
    val androidVersion: String = "",
    val buildId: String = "",
    val securityPatch: LocalDate? = null,
    /** Null when the TV doesn't say (not every maker sets these). */
    val bootloaderLocked: Boolean? = null,
    /** "green" (original software, verified), "yellow" (own signing key), "orange" (unlocked), "red", or "". */
    val verifiedBoot: String = "",
) {
    /** Whole months between the security patch and today; -1 if unknown. */
    fun patchAgeMonths(today: LocalDate = LocalDate.now()): Int =
        securityPatch?.let { maxOf(0, ChronoUnit.MONTHS.between(it, today).toInt()) } ?: -1

    companion object {
        suspend fun read(tv: Tv): TvInfo {
            val keys = listOf(
                "ro.build.version.release", "ro.build.display.id", "ro.build.version.security_patch",
                "ro.boot.flash.locked", "ro.boot.vbmeta.device_state", "ro.boot.verifiedbootstate",
            )
            val out = tv.shell(keys.joinToString("; ") { "echo \"$it=\$(getprop $it)\"" }).output
            val props = out.lines().filter { '=' in it }.associate { it.substringBefore('=').trim() to it.substringAfter('=').trim() }
            fun p(k: String) = props[k].orEmpty()
            val locked = when (p("ro.boot.flash.locked")) {
                "1" -> true
                "0" -> false
                else -> when (p("ro.boot.vbmeta.device_state")) { "locked" -> true; "unlocked" -> false; else -> null }
            }
            return TvInfo(
                androidVersion = p("ro.build.version.release"),
                buildId = p("ro.build.display.id"),
                securityPatch = runCatching { LocalDate.parse(p("ro.build.version.security_patch")) }.getOrNull(),
                bootloaderLocked = locked,
                verifiedBoot = p("ro.boot.verifiedbootstate"),
            )
        }
    }
}

data class ProcessUse(val name: String, val mb: Long)

data class CrashLoop(val pkg: String, val restarts: Int)

/** A snapshot of how the TV is doing. */
data class HealthReport(
    val memTotalMb: Long = 0,
    val memAvailableMb: Long = 0,
    val storageTotalMb: Long = 0,
    val storageFreeMb: Long = 0,
    val load: Double = 0.0,
    val cores: Int = 1,
    val uptimeHours: Double = 0.0,
    val topMemory: List<ProcessUse> = emptyList(),
    val crashing: List<CrashLoop> = emptyList(),
) {
    companion object {
        /** Restarts in the current log above which an app counts as crash-looping. */
        const val CRASH_THRESHOLD = 3

        suspend fun read(tv: Tv): HealthReport {
            val basics = tv.shell(
                "grep -E 'MemTotal|MemAvailable' /proc/meminfo; echo load \$(cat /proc/loadavg); " +
                    "echo cpus \$(cat /sys/devices/system/cpu/present); echo up \$(cat /proc/uptime)"
            ).output
            fun first(pattern: String) = Regex(pattern).find(basics)?.groupValues?.drop(1).orEmpty()
            val cpus = first("""cpus (\d+)-(\d+)""")
            val cores = if (cpus.size == 2) (cpus[1].toInt() - cpus[0].toInt() + 1) else 1
            val storage = TvDevice.readStorage(tv)

            // "193,472K: com.example.app (pid 4813 / activities)"
            val mem = tv.shell("dumpsys meminfo | sed -n '/Total PSS by process/,/^\$/p' | head -12").output
            val top = Regex("""^\s*([\d,]+)K: ([^\s(]+)""", RegexOption.MULTILINE).findAll(mem).take(8)
                .map { ProcessUse(it.groupValues[2], (it.groupValues[1].replace(",", "").toLongOrNull() ?: 0) / 1024) }.toList()

            // Android logs every restart of a process that died: many restarts means a crash loop.
            val log = tv.shell("logcat -d | grep -E 'Start proc [0-9]+:[^ ]+ for restart'").output
            val crashing = Regex("""Start proc \d+:([^/\s]+)/\S+ for restart""").findAll(log)
                .groupingBy { it.groupValues[1] }.eachCount()
                .filter { it.value >= CRASH_THRESHOLD }
                .map { CrashLoop(it.key, it.value) }.sortedByDescending { it.restarts }

            return HealthReport(
                memTotalMb = (first("""MemTotal:\s+(\d+)""").firstOrNull()?.toLongOrNull() ?: 0) / 1024,
                memAvailableMb = (first("""MemAvailable:\s+(\d+)""").firstOrNull()?.toLongOrNull() ?: 0) / 1024,
                storageTotalMb = storage.first, storageFreeMb = storage.second,
                load = first("""load ([\d.]+)""").firstOrNull()?.toDoubleOrNull() ?: 0.0,
                cores = cores,
                uptimeHours = (first("""up ([\d.]+)""").firstOrNull()?.toDoubleOrNull() ?: 0.0) / 3600,
                topMemory = top, crashing = crashing,
            )
        }
    }
}

/** One kind of access an app can be given ("Location", "Microphone"…) and the permissions behind it. */
data class AccessKind(val key: String, val permissions: List<String>)

/** An app that has one kind of access, with the granted permissions that removing it would revoke. */
data class AppAccess(val pkg: String, val kind: AccessKind, val granted: List<String>, val isThirdParty: Boolean)

/** Which apps can use location, camera, microphone and personal data. */
object Privacy {
    private const val PREFIX = "android.permission."

    /** In the order shown. "BgLocation" removes only "all the time", so the app keeps it while open. */
    val kinds = listOf(
        AccessKind("Microphone", listOf("RECORD_AUDIO")),
        AccessKind("Camera", listOf("CAMERA")),
        AccessKind("BgLocation", listOf("ACCESS_BACKGROUND_LOCATION")),
        AccessKind("Location", listOf("ACCESS_FINE_LOCATION", "ACCESS_COARSE_LOCATION", "ACCESS_BACKGROUND_LOCATION")),
        AccessKind("Contacts", listOf("READ_CONTACTS", "WRITE_CONTACTS")),
        AccessKind("Calendar", listOf("READ_CALENDAR", "WRITE_CALENDAR")),
    )

    /**
     * Apps you installed and preinstalled apps with an icon. System parts without an icon (voice search,
     * TV inputs…) and everything the TV needs are left out: removing their access breaks things.
     */
    suspend fun read(tv: Tv, dev: TvDevice): List<AppAccess> {
        val withIcon = UserApps.withIcon(tv)
        val wanted = kinds.flatMap { it.permissions }.distinct().sorted().joinToString("|")
        val dump = tv.shell(
            "dumpsys package packages | grep -E '^Hidden|^  Package \\[|^    User [0-9]+:|permission\\.($wanted): granted=true'"
        ).output.replace("\r", "")
        // Only the installed packages, not the original copies of updated system apps listed after them.
        val text = "\n" + dump.substringBefore("\nHidden")

        val result = mutableListOf<AppAccess>()
        for (block in text.split("\n  Package [").drop(1)) {
            val pkg = block.substringBefore(']', "")
            if (pkg.isEmpty()) continue
            val thirdParty = pkg in dev.thirdParty
            if (pkg !in dev.enabled || dev.isProtected(pkg) || Catalog.isKeep(pkg)) continue
            if (!thirdParty && pkg !in withIcon) continue

            // Main user's permissions only: "    User 0: …" up to the next user.
            val user0 = block.split("\n    User ").firstOrNull { it.startsWith("0:") } ?: continue
            val granted = user0.lines()
                .filter { "granted=true" in it && PREFIX in it && "SYSTEM_FIXED" !in it && "POLICY_FIXED" !in it }
                .map { it.substringAfter(PREFIX).substringBefore(':') }.toSet()

            for (kind in kinds) {
                val g = kind.permissions.filter { it in granted }
                // Location counts only for precise or approximate; background alone is shown under "all the time".
                if (kind.key == "Location" && g.none { it != "ACCESS_BACKGROUND_LOCATION" }) continue
                if (g.isNotEmpty()) result += AppAccess(pkg, kind, g.map { PREFIX + it }, thirdParty)
            }
        }
        return result
    }

    /** Null when it worked, else the TV's error. */
    suspend fun revoke(tv: Tv, pkg: String, permission: String): String? {
        val r = tv.shell("pm revoke --user 0 $pkg $permission")
        return if (r.ok && "Exception" !in r.all) null else r.all
    }
}

/** An app the owner installed, with what the TV says about it. */
data class InstalledApp(
    val pkg: String,
    val version: String = "",
    /** The app store or app that installed it ("com.android.vending" for Google Play); "" when unknown. */
    val installer: String = "",
    val installed: LocalDateTime? = null,
    val updated: LocalDateTime? = null,
    val appBytes: Long = 0,
    val dataBytes: Long = 0,
    val cacheBytes: Long = 0,
    val isEnabled: Boolean = true,
    val hasIcon: Boolean = false,
    /** Installed by a part of the TV's own software (the maker's updater), not by a store or from a file. */
    val byMaker: Boolean = false,
) {
    val totalBytes: Long get() = appBytes + dataBytes + cacheBytes
    /** Google Play, or the Amazon Appstore on Fire TV. */
    val fromStore: Boolean get() = installer in STORES

    companion object {
        val STORES = setOf("com.android.vending", "com.amazon.venezia")
        /** System apps that install files someone picked, rather than updates from the maker. */
        val FILE_INSTALLERS = setOf("com.android.shell", "com.android.packageinstaller", "com.google.android.packageinstaller")
    }
}

/** The apps installed by the TV's owner, and what can be done with them. */
object UserApps {
    /** Apps in the TV's app row (leanback) and phone-style apps someone installed anyway. */
    private const val LAUNCHER_CATEGORIES = "-c android.intent.category.LEANBACK_LAUNCHER -c android.intent.category.LAUNCHER"

    /** Packages with an icon on the home screen or in the app list. */
    suspend fun withIcon(tv: Tv): Set<String> {
        val found = mutableSetOf<String>()
        for (category in listOf("LEANBACK_LAUNCHER", "LAUNCHER")) {
            found += tv.shell("cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.$category")
                .output.lines().map { it.trim() }.filter { '/' in it && ' ' !in it }.map { it.substringBefore('/') }
        }
        return found
    }

    suspend fun read(tv: Tv, dev: TvDevice): List<InstalledApp> {
        // Listed again rather than taken from the device: apps may have come and gone since it was read.
        val ours = list(tv, "-3")
        val off = list(tv, "-d")
        val icons = withIcon(tv)

        val dump = tv.shell(
            "dumpsys package packages | grep -E '^Hidden|^  Package \\[|^    versionName=|^    lastUpdateTime=|" +
                "^    installerPackageName=|^    User [0-9]+:|^      firstInstallTime='"
        ).output.replace("\r", "")
        val text = "\n" + dump.substringBefore("\nHidden")
        data class Facts(val version: String, val installer: String, val installed: LocalDateTime?, val updated: LocalDateTime?)
        val facts = mutableMapOf<String, Facts>()
        for (block in text.split("\n  Package [").drop(1)) {
            val pkg = block.substringBefore(']', "")
            if (pkg !in ours) continue
            // The install date is per user: the owner's is the first one after "User 0:".
            val user0 = block.indexOf("\n    User 0:").let { if (it < 0) "" else block.substring(it) }
            val installer = field(block, "installerPackageName")
            facts[pkg] = Facts(field(block, "versionName"), if (installer == "null") "" else installer,
                date(field(user0, "firstInstallTime")), date(field(block, "lastUpdateTime")))
        }

        val sizes = readSizes(tv)
        return ours.map { pkg ->
            val f = facts[pkg] ?: Facts("", "", null, null)
            val s = sizes[pkg] ?: Triple(0L, 0L, 0L)
            // Maker apps on operator boxes come through the maker's updater, a system app.
            val byMaker = dev.isInstalled(f.installer) && f.installer !in ours &&
                f.installer !in InstalledApp.FILE_INSTALLERS && f.installer !in InstalledApp.STORES
            InstalledApp(pkg, f.version, f.installer, f.installed, f.updated, s.first, s.second, s.third,
                isEnabled = pkg !in off, hasIcon = pkg in icons, byMaker = byMaker)
        }
    }

    /**
     * Sizes from "dumpsys diskstats", which the TV works out from time to time (not live).
     * "Package Names: [...]", then "App Sizes", "App Data Sizes" and "Cache Sizes" in the same order.
     */
    private suspend fun readSizes(tv: Tv): Map<String, Triple<Long, Long, Long>> {
        val out = tv.shell("dumpsys diskstats").output.lines()
        fun list(name: String): List<String> {
            val line = out.firstOrNull { it.startsWith("$name: [") } ?: return emptyList()
            return line.substringAfter('[').substringBeforeLast(']').split(',').map { it.trim('"', ' ') }
        }
        fun at(l: List<String>, i: Int) = l.getOrNull(i)?.toLongOrNull() ?: 0
        val names = list("Package Names")
        val app = list("App Sizes"); val data = list("App Data Sizes"); val cache = list("Cache Sizes")
        return names.withIndex().associate { (i, n) -> n to Triple(at(app, i), at(data, i), at(cache, i)) }
    }

    /** Apps installed in the TV's other profiles (a work profile, another person), which the list leaves out. */
    suspend fun inOtherProfiles(tv: Tv): Int {
        val users = Regex("UserInfo\\{(\\d+):").findAll(tv.shell("pm list users").output)
            .map { it.groupValues[1] }.filter { it != "0" }.toList()
        return users.flatMap { list(tv, "-3", it) }.toSet().size
    }

    private suspend fun list(tv: Tv, flag: String, user: String = "0"): Set<String> =
        tv.shell("pm list packages --user $user $flag").output.lines().map { it.trim() }
            .filter { it.startsWith("package:") }.map { it.removePrefix("package:") }.toSet()

    private fun field(block: String, name: String): String {
        val i = block.indexOf("$name=")
        return if (i < 0) "" else block.substring(i + name.length + 1).substringBefore('\n').trim()
    }

    private val tvTime = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    /** "2025-09-11 13:37:13" in the TV's time. Never-set dates read as 1970 (or the day before, west of Greenwich). */
    private fun date(s: String): LocalDateTime? =
        runCatching { LocalDateTime.parse(s, tvTime) }.getOrNull()?.takeIf { it.year > 1970 }

    // Each returns null when it worked, else the TV's answer.

    /** Starts the app on the TV, as if it was picked on the home screen. */
    suspend fun open(tv: Tv, pkg: String) = run(tv, "monkey -p $pkg $LAUNCHER_CATEGORIES 1", expected = "Events injected: 1")

    /** The app's page in the TV's Settings (storage, notifications, permissions…). */
    suspend fun showInfo(tv: Tv, pkg: String) = run(tv, "am start -a android.settings.APPLICATION_DETAILS_SETTINGS -d package:$pkg")

    suspend fun stop(tv: Tv, pkg: String) = run(tv, "am force-stop --user 0 $pkg")

    suspend fun setEnabled(tv: Tv, pkg: String, on: Boolean) =
        run(tv, if (on) "pm enable --user 0 $pkg" else "pm disable-user --user 0 $pkg", expected = "new state:")

    /** For the main user: the same app in other profiles stays. */
    suspend fun uninstall(tv: Tv, pkg: String) = run(tv, "pm uninstall --user 0 $pkg", expected = "Success")

    private suspend fun run(tv: Tv, command: String, expected: String? = null): String? {
        val r = tv.shell(command)
        val all = r.all
        val failed = !r.ok || "Exception" in all || all.contains("error", ignoreCase = true) || "Failure" in all ||
            (expected != null && expected !in all)
        if (!failed) return null
        return all.lines().lastOrNull { it.isNotBlank() }?.trim() ?: all
    }
}

/**
 * App names and pictures as the TV shows them, from Android itself. A tiny helper (umtv-helper.dex, the
 * desktop apps' own) is copied to the TV's temp folder, run with app_process, and deleted: nothing is installed.
 */
object TvIcons {
    /** [label] is the app's name in the TV's language, "" when the TV didn't give one; [png] its banner or icon, 16:9. */
    class Entry(val label: String, val png: ByteArray?)

    const val HEIGHT = 90
    private const val REMOTE = "/data/local/tmp/umtv-helper.dex"
    /** Keyed by TV, package and last update, so a refresh only asks for what changed. */
    private val cache = mutableMapOf<String, Entry>()

    private fun key(serial: String, a: InstalledApp) = "$serial|${a.pkg}|${a.updated}"

    suspend fun read(tv: Tv, apps: List<InstalledApp>): Map<String, Entry> {
        val missing = apps.filter { key(tv.serial, it) !in cache }
        if (missing.isNotEmpty()) runCatching { fetch(tv, missing) }
        return apps.mapNotNull { a -> cache[key(tv.serial, a)]?.let { a.pkg to it } }.toMap()
    }

    private suspend fun fetch(tv: Tv, apps: List<InstalledApp>) {
        val local = File(UnbloatApp.instance.cacheDir, "umtv-helper.dex")
        if (!local.exists()) UnbloatApp.instance.assets.open("umtv-helper.dex").use { i -> local.outputStream().use { i.copyTo(it) } }
        try {
            tv.push(local, REMOTE)
            // A few dozen at a time keeps the command line short on older TVs.
            for (chunk in apps.chunked(40)) {
                val byPkg = chunk.associateBy { it.pkg }
                val out = tv.shell("CLASSPATH=$REMOTE app_process / app.unbloatmytv.AppIcons $HEIGHT ${chunk.joinToString(" ") { it.pkg }}").output
                for (line in out.lines()) {
                    val f = line.split('\t')
                    val app = if (f.size >= 3) byPkg[f[0]] else null
                    if (app == null) continue
                    val png = f[2].trim().takeIf { it.isNotEmpty() }?.let { runCatching { Base64.getDecoder().decode(it) }.getOrNull() }
                    cache[key(tv.serial, app)] = Entry(f[1].trim(), png)
                }
            }
        } finally {
            tv.shell("rm -f $REMOTE")
        }
    }
}
