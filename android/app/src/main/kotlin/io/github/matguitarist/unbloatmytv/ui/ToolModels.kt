package io.github.matguitarist.unbloatmytv.ui

import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.viewModelScope
import io.github.matguitarist.unbloatmytv.R
import io.github.matguitarist.unbloatmytv.UnbloatApp
import io.github.matguitarist.unbloatmytv.model.AccessKind
import io.github.matguitarist.unbloatmytv.model.AppAccess
import io.github.matguitarist.unbloatmytv.model.Backup
import io.github.matguitarist.unbloatmytv.model.Catalog
import io.github.matguitarist.unbloatmytv.model.HealthReport
import io.github.matguitarist.unbloatmytv.model.InstalledApp
import io.github.matguitarist.unbloatmytv.model.PermissionChange
import io.github.matguitarist.unbloatmytv.model.Privacy
import io.github.matguitarist.unbloatmytv.model.TvDevice
import io.github.matguitarist.unbloatmytv.model.TvIcons
import io.github.matguitarist.unbloatmytv.model.TvInfo
import io.github.matguitarist.unbloatmytv.model.UserApps
import io.github.matguitarist.unbloatmytv.str
import io.github.matguitarist.unbloatmytv.tv.Tv
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

// The tools for the connected TV, each one a page. Same rules and wording as the desktop apps.

fun oneDecimal(x: Double): String = String.format(Locale.getDefault(), "%.1f", x)

class Tip(val text: String, val kind: PillKind)

class InfoRow(val label: String, val value: String)

/** Software, updates and boot security, with shortcuts to the TV's own screens and a restart button. */
class TvInfoModel(private val vm: AppViewModel) {
    var loading by mutableStateOf(false); private set
    var info by mutableStateOf<TvInfo?>(null); private set
    var status by mutableStateOf(""); private set
    var statusIsError by mutableStateOf(false); private set
    /** Restart pressed, waiting for a yes. */
    var confirmingRestart by mutableStateOf(false)
    /** The TV is restarting: the page waits for it to come back. */
    var restarting by mutableStateOf(false); private set

    init { refresh() }

    fun refresh() {
        val tv = vm.tv ?: return
        vm.viewModelScope.launch {
            loading = true
            info = runCatching { TvInfo.read(tv) }.getOrNull()
            loading = false
        }
    }

    val software: List<InfoRow>
        get() {
            val i = info ?: return emptyList()
            val age = i.patchAgeMonths()
            val ago = when (age) { 0 -> str(R.string.Info_ThisMonth); 1 -> str(R.string.Info_OneMonth); else -> str(R.string.Info_MonthsAgo, age) }
            val rows = mutableListOf(InfoRow(str(R.string.Info_Android), str(R.string.Info_AndroidValue, i.androidVersion)))
            vm.device?.fireOsVersion?.takeIf { it.isNotEmpty() }?.let { rows += InfoRow("Fire OS", it.removePrefix("Fire OS ")) }
            rows += InfoRow(str(R.string.Info_Patch), i.securityPatch?.let {
                str(R.string.Info_PatchValue, it.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG)), ago)
            } ?: str(R.string.Info_Unknown))
            rows += InfoRow(str(R.string.Info_Build), i.buildId.ifEmpty { str(R.string.Info_Unknown) })
            return rows
        }

    val boot: List<InfoRow>
        get() {
            val i = info ?: return emptyList()
            val locked = when (i.bootloaderLocked) { true -> str(R.string.Info_Locked); false -> str(R.string.Info_Unlocked); null -> str(R.string.Info_Unknown) }
            return listOf(InfoRow(str(R.string.Info_Bootloader), locked), InfoRow(str(R.string.Info_Verified), verifiedText(i.verifiedBoot)))
        }

    val tips: List<Tip>
        get() {
            val i = info ?: return emptyList()
            val tips = mutableListOf<Tip>()
            val age = i.patchAgeMonths()
            // TVs get fewer updates than phones: a year without one is when to worry.
            when {
                age >= 12 -> tips += Tip(str(R.string.Info_TipPatchVeryOld, age), PillKind.Warn)
                age >= 6 -> tips += Tip(str(R.string.Info_TipPatchOld, age), PillKind.Warn)
                age >= 0 -> tips += Tip(str(R.string.Info_TipPatchOk), PillKind.Ok)
            }
            when {
                i.bootloaderLocked == false -> tips += Tip(str(R.string.Info_TipUnlocked), PillKind.Warn)
                i.verifiedBoot !in setOf("", "green") -> tips += Tip(str(R.string.Info_TipNotVerified, verifiedText(i.verifiedBoot)), PillKind.Warn)
                i.bootloaderLocked == true && i.verifiedBoot == "green" -> tips += Tip(str(R.string.Info_TipLocked), PillKind.Ok)
            }
            return tips
        }

    private fun verifiedText(state: String) = str(
        when (state) {
            "green" -> R.string.Info_VbGreen
            "yellow" -> R.string.Info_VbYellow
            "orange" -> R.string.Info_VbOrange
            "red" -> R.string.Info_VbRed
            else -> R.string.Info_Unknown
        }
    )

    /** Android TV keeps System update under About, so that is the fallback. */
    fun openUpdate() = vm.viewModelScope.launch {
        if (!openOnTv("am start -a android.settings.SYSTEM_UPDATE_SETTINGS", R.string.Info_UpdateOpened))
            openOnTv("am start -a android.settings.DEVICE_INFO_SETTINGS", R.string.Info_AboutOpened)
    }

    /** About, where Reset is. Android doesn't let anything else open the reset screen itself. */
    fun openAbout() = vm.viewModelScope.launch {
        if (!openOnTv("am start -a android.settings.DEVICE_INFO_SETTINGS", R.string.Info_AboutOpened))
            openOnTv("am start -a android.settings.SETTINGS", R.string.Info_SettingsOpened)
    }

    private suspend fun openOnTv(command: String, ok: Int): Boolean {
        val r = vm.tv?.shell(command) ?: return false
        val failed = r.all.contains("error", ignoreCase = true) || "Exception" in r.all
        statusIsError = failed
        status = if (failed) str(R.string.Info_OpenFailed, r.all.lines().lastOrNull().orEmpty()) else str(ok)
        return !failed
    }

    /** Restarts the TV and waits for it, connecting again, like the Apply page does. */
    fun restart() {
        val tv = vm.tv ?: return
        if (!confirmingRestart || restarting) return
        confirmingRestart = false
        restarting = true
        statusIsError = false
        status = str(R.string.Info_Restarting)
        vm.viewModelScope.launch {
            val back = tv.rebootAndWait()
            if (back) {
                runCatching { vm.updateDevice(TvDevice.load(tv)) }
                status = str(R.string.Info_RestartDone)
            } else {
                statusIsError = true
                status = str(R.string.Info_RestartTimeout)
            }
            restarting = false
            if (back) refresh()
        }
    }
}

/** Read-only check-up: memory, storage, processor, biggest apps, crash loops, with advice. */
class HealthModel(private val vm: AppViewModel) {
    var loading by mutableStateOf(false); private set
    var report by mutableStateOf<HealthReport?>(null); private set

    init { refresh() }

    fun refresh() {
        val tv = vm.tv ?: return
        vm.viewModelScope.launch {
            loading = true
            report = runCatching { HealthReport.read(tv) }.getOrNull()
            loading = false
        }
    }

    val memoryUsed: Float get() = report?.let { if (it.memTotalMb > 0) (it.memTotalMb - it.memAvailableMb).toFloat() / it.memTotalMb else 0f } ?: 0f
    val storageUsed: Float get() = report?.let { if (it.storageTotalMb > 0) (it.storageTotalMb - it.storageFreeMb).toFloat() / it.storageTotalMb else 0f } ?: 0f

    val cpuText: String
        get() {
            val r = report ?: return ""
            val h = r.uptimeHours
            val up = when {
                h < 0.5 -> str(R.string.Health_UnderHour)
                h < 1.5 -> str(R.string.Health_OneHour)
                h < 48 -> str(R.string.Health_Hours, Math.round(h))
                else -> str(R.string.Health_Days, Math.round(h / 24))
            }
            // The load average is how many tasks want the processor at once, not a percentage.
            val perCore = if (r.cores > 0) r.load / r.cores else 0.0
            val level = when { perCore <= 0.7 -> R.string.Health_CpuQuiet; perCore <= 1.0 -> R.string.Health_CpuBusy; else -> R.string.Health_CpuOverloaded }
            return str(R.string.Health_CpuText, str(level), oneDecimal(r.load), r.cores, up)
        }

    val tips: List<Tip>
        get() {
            val r = report ?: return emptyList()
            val dev = vm.device
            val tips = mutableListOf<Tip>()
            if (r.memTotalMb > 0 && r.memAvailableMb * 100 < r.memTotalMb * 15) tips += Tip(str(R.string.Health_TipMemory), PillKind.Warn)
            if (r.storageTotalMb > 0 && r.storageFreeMb * 100 < r.storageTotalMb * 15) tips += Tip(str(R.string.Health_TipStorage), PillKind.Warn)
            if (r.cores > 0 && r.load / r.cores > 1.0) tips += Tip(str(R.string.Health_TipCpu), PillKind.Warn)
            for (c in r.crashing) {
                val name = Catalog.nameOf(c.pkg) ?: c.pkg
                val key = when {
                    Catalog.isKeep(c.pkg) -> R.string.Health_TipCrashKeep
                    dev != null && Catalog.isOffered(dev, c.pkg) -> R.string.Health_TipCrashOffered
                    else -> R.string.Health_TipCrash
                }
                tips += Tip(str(key, name, c.restarts), PillKind.Warn)
            }
            if (tips.isEmpty()) tips += Tip(str(R.string.Health_TipAllGood), PillKind.Ok)
            return tips
        }

    companion object {
        fun gb(mb: Long) = oneDecimal(mb / 1024.0)
    }
}

class AccessRow(val access: AppAccess, val name: String) {
    var removed by mutableStateOf(false)
    var working by mutableStateOf(false)
    val pkg: String get() = access.pkg
}

class AccessGroup(val kind: AccessKind, val rows: List<AccessRow>)

/** Which apps can use the microphone, camera, location and personal data, with one tap to remove an access. */
class PrivacyModel(private val vm: AppViewModel) {
    var loading by mutableStateOf(false); private set
    var groups by mutableStateOf<List<AccessGroup>>(emptyList()); private set
    var status by mutableStateOf(""); private set
    /** One backup per visit, started on the first removal. */
    private var backup: Backup? = null

    init { refresh() }

    fun refresh() {
        val tv = vm.tv ?: return
        val dev = vm.device ?: return
        vm.viewModelScope.launch {
            loading = true
            val all = runCatching { Privacy.read(tv, dev) }.getOrDefault(emptyList())
            fun name(a: AppAccess) = Catalog.nameOf(a.pkg) ?: a.pkg
            groups = Privacy.kinds.mapNotNull { kind ->
                val rows = all.filter { it.kind == kind }
                    .sortedWith(compareBy({ if (it.isThirdParty) 0 else 1 }, { name(it).lowercase() }))
                    .map { AccessRow(it, name(it)) }
                if (rows.isEmpty()) null else AccessGroup(kind, rows)
            }
            loading = false
        }
    }

    fun remove(row: AccessRow) {
        val tv = vm.tv ?: return
        val dev = vm.device ?: return
        if (row.removed || row.working) return
        row.working = true
        status = ""
        vm.viewModelScope.launch {
            val b = backup ?: Backup.start(dev).also { backup = it }
            var error: String? = null
            for (perm in row.access.granted) {
                // Saved before the change, like every other change, so even an interrupted run can be undone.
                b.revokedPermissions += PermissionChange(row.pkg, perm)
                runCatching { b.save() }
                error = Privacy.revoke(tv, row.pkg, perm)
                if (error == null) continue
                b.revokedPermissions.removeAt(b.revokedPermissions.lastIndex)
                runCatching { b.save() }
                break
            }
            row.working = false
            if (error != null) {
                status = str(R.string.Privacy_Failed, row.name, error)
                return@launch
            }
            row.removed = true
            // Removing location also removed "all the time".
            if (row.access.kind.key == "Location")
                groups.filter { it.kind.key == "BgLocation" }.flatMap { it.rows }.filter { it.pkg == row.pkg }.forEach { it.removed = true }
        }
    }
}

/** One installed app. Uninstall waits for a yes on the row. */
class AppRow(app: InstalledApp, val isProtected: Boolean) {
    var app by mutableStateOf(app)
    var name by mutableStateOf(Catalog.nameOf(app.pkg) ?: app.pkg)
    /** The banner or icon as the TV draws it; null until it arrives, or when the TV can't give one. */
    var icon by mutableStateOf<ImageBitmap?>(null)
    var gone by mutableStateOf(false)
    var working by mutableStateOf(false)
    var confirming by mutableStateOf(false)
    val pkg: String get() = app.pkg

    val hasFriendlyName: Boolean get() = name != pkg
    val isOff: Boolean get() = !app.isEnabled && !gone
    /** From a file or another source, not from Google Play, the Amazon Appstore or the TV's maker. */
    val isOutside: Boolean get() = !gone && !app.fromStore && !app.byMaker
    private val ready: Boolean get() = !gone && !working
    val canOpen: Boolean get() = ready && app.isEnabled && app.hasIcon
    val canStop: Boolean get() = ready && app.isEnabled
    val canTurnOff: Boolean get() = ready && app.isEnabled && !isProtected
    val canTurnOn: Boolean get() = ready && !app.isEnabled
    val canRemove: Boolean get() = ready && !isProtected

    /** The name on the TV wins over the catalog's. */
    fun setFromTv(e: TvIcons.Entry) {
        if (e.label.isNotEmpty() && e.label != pkg) name = e.label
        if (icon == null) e.png?.let { png -> BitmapFactory.decodeByteArray(png, 0, png.size)?.let { icon = it.asImageBitmap() } }
    }

    /** "version 4.16.8 · 245 MB · Google Play · installed 11 Sep 2025" */
    val details: String
        get() {
            val parts = mutableListOf<String>()
            if (app.version.isNotEmpty()) parts += str(R.string.Apps_Version, app.version)
            if (app.totalBytes > 0) parts += size(app.totalBytes)
            parts += origin(app)
            app.installed?.let { parts += str(R.string.Apps_InstalledOn, it.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))) }
            return parts.joinToString(" · ")
        }

    companion object {
        fun size(bytes: Long): String {
            val mb = bytes / 1024.0 / 1024.0
            return when {
                mb < 1 -> str(R.string.Apps_LessThanMb)
                mb < 1000 -> str(R.string.Apps_Mb, String.format(Locale.getDefault(), "%.0f", mb))
                else -> str(R.string.Apps_Gb, oneDecimal(mb / 1024))
            }
        }

        fun origin(app: InstalledApp): String {
            if (app.byMaker) return str(R.string.Apps_FromMaker)
            return when (app.installer) {
                "com.android.vending" -> "Google Play"
                "com.amazon.venezia" -> "Amazon Appstore"
                "", "com.android.shell", "com.google.android.packageinstaller", "com.android.packageinstaller" -> str(R.string.Apps_FromFile)
                "com.esaba.downloader" -> "Downloader"
                "com.aurora.store" -> "Aurora Store"
                "org.fdroid.fdroid", "org.fdroid.basic" -> "F-Droid"
                else -> app.installer
            }
        }
    }
}

/** The apps the owner installed: size, origin, and open, stop, turn off or uninstall. */
class AppsModel(private val vm: AppViewModel) {
    enum class Sort { Name, Size, Newest }

    var loading by mutableStateOf(false); private set
    var rows by mutableStateOf<List<AppRow>>(emptyList()); private set
    var status by mutableStateOf(""); private set
    var statusIsError by mutableStateOf(false); private set
    var search by mutableStateOf("")
    var sort by mutableStateOf(Sort.Name)
    /** The list shows only the apps that didn't come from a store. */
    var onlyOutside by mutableStateOf(false)
    /** One backup per visit, started on the first app turned off or on. */
    private var backup: Backup? = null

    init { refresh() }

    val present: List<AppRow> get() = rows.filter { !it.gone }
    val summary: String get() = str(R.string.Apps_Summary, present.size, AppRow.size(present.sumOf { it.app.totalBytes }))
    val outside: List<AppRow> get() = rows.filter { it.isOutside }.sortedBy { it.name.lowercase() }

    val visible: List<AppRow>
        get() {
            val q = search.trim()
            val only = onlyOutside && rows.any { it.isOutside }
            val found = rows.filter {
                (!only || it.isOutside) && (q.isEmpty() || it.name.contains(q, ignoreCase = true) || it.pkg.contains(q, ignoreCase = true))
            }
            return when (sort) {
                Sort.Name -> found.sortedBy { it.name.lowercase() }
                Sort.Size -> found.sortedByDescending { it.app.totalBytes }
                Sort.Newest -> found.sortedByDescending { it.app.installed }
            }
        }

    fun refresh() {
        val tv = vm.tv ?: return
        val dev = vm.device ?: return
        vm.viewModelScope.launch {
            loading = true
            status = ""
            val apps = runCatching { UserApps.read(tv, dev) }.getOrDefault(emptyList())
            val list = apps.map { AppRow(it, isNeeded(dev, it)) }
            rows = list
            loading = false
            // Pictures and names from the TV, after the list shows: they take a few seconds the first time.
            if (list.isEmpty()) return@launch
            val found = TvIcons.read(tv, apps)
            if (rows !== list) return@launch // refreshed meanwhile
            for (r in list) found[r.pkg]?.let(r::setFromTv)
        }
    }

    /**
     * The home screen, keyboard and so on, Amazon's apps on Fire TV, and the parts of Android that Google
     * installs through Play: those have no icon and aren't in the catalog.
     */
    private fun isNeeded(dev: TvDevice, a: InstalledApp) =
        dev.isProtected(a.pkg) || Catalog.isKeep(a.pkg) ||
            (!a.hasIcon && a.pkg.startsWith("com.google.android.") && Catalog.nameOf(a.pkg) == null)

    private fun report(error: String?, ok: Int, row: AppRow) {
        statusIsError = error != null
        status = if (error != null) str(R.string.Apps_Failed, row.name, error) else str(ok, row.name)
    }

    fun run(row: AppRow, ok: Int, action: suspend (Tv, String) -> String?) {
        val tv = vm.tv ?: return
        if (row.working) return
        row.working = true
        vm.viewModelScope.launch {
            val error = action(tv, row.pkg)
            row.working = false
            report(error, ok, row)
        }
    }

    /** Saved in a backup before the change, like every other change, so Restore can undo it. */
    fun setEnabled(row: AppRow, on: Boolean) {
        val tv = vm.tv ?: return
        val dev = vm.device ?: return
        if (row.working) return
        row.working = true
        vm.viewModelScope.launch {
            val b = backup ?: Backup.start(dev).also { backup = it }
            val list = if (on) b.enabledPackages else b.disabledPackages
            list += row.pkg
            runCatching { b.save() }
            val error = UserApps.setEnabled(tv, row.pkg, on)
            row.working = false
            if (error != null) {
                list.removeAt(list.lastIndex)
                runCatching { b.save() }
            } else {
                row.app = row.app.copy(isEnabled = on)
                // Keep the connected TV's lists in step, for the other pages.
                vm.device?.let { d ->
                    vm.updateDevice(
                        if (on) d.copy(disabled = d.disabled - row.pkg, enabled = d.enabled + row.pkg)
                        else d.copy(enabled = d.enabled - row.pkg, disabled = d.disabled + row.pkg)
                    )
                }
            }
            report(error, if (on) R.string.Apps_TurnedOn else R.string.Apps_TurnedOff, row)
        }
    }

    /** After the yes on the row. Can't be undone from Restore. */
    fun uninstall(row: AppRow) {
        val tv = vm.tv ?: return
        row.confirming = false
        if (!row.canRemove) return
        row.working = true
        vm.viewModelScope.launch {
            val error = UserApps.uninstall(tv, row.pkg)
            row.working = false
            if (error == null) {
                row.gone = true
                vm.device?.let { d ->
                    vm.updateDevice(d.copy(enabled = d.enabled - row.pkg, disabled = d.disabled - row.pkg, thirdParty = d.thirdParty - row.pkg))
                }
            }
            report(error, R.string.Apps_UninstalledDone, row)
        }
    }
}

/** Live view of the TV screen and a remote control. */
class RemoteModel(private val vm: AppViewModel) {
    var screen by mutableStateOf<ImageBitmap?>(null); private set
    var live by mutableStateOf(true)
    var textToSend by mutableStateOf("")
    private var loop: Job? = null

    /** Runs while the page shows: a new picture as soon as the last one arrived, a few per second. */
    fun start() {
        val tv = vm.tv ?: return
        loop?.cancel()
        loop = vm.viewModelScope.launch {
            while (isActive) {
                if (live) grab(tv)
                delay(if (live) 300 else 500)
            }
        }
    }

    fun stop() {
        loop?.cancel()
        loop = null
    }

    fun refreshOnce() {
        val tv = vm.tv ?: return
        vm.viewModelScope.launch { grab(tv) }
    }

    private suspend fun grab(tv: Tv) {
        val png = tv.execBytes("screencap -p") ?: return
        withContext(Dispatchers.Default) { BitmapFactory.decodeByteArray(png, 0, png.size) }?.let { screen = it.asImageBitmap() }
    }

    /** Android key codes: 19-22 arrows, 23 OK, 4 Back, 3 Home, 82 Menu, 85 Play/pause, 24/25 volume. */
    fun key(code: Int) {
        val tv = vm.tv ?: return
        vm.viewModelScope.launch {
            tv.shell("input keyevent $code")
            if (!live) grab(tv)
        }
    }

    /** Type into whatever text field is open on the TV (search boxes, passwords). */
    fun sendText() {
        val tv = vm.tv ?: return
        if (textToSend.isEmpty()) return
        // "input text" takes %s for spaces; the rest is passed quoted.
        val text = textToSend.replace("%", "\\%").replace(" ", "%s").replace("'", "'\\''")
        textToSend = ""
        vm.viewModelScope.launch { tv.shell("input text '$text'") }
    }
}

/** Installs an .apk picked on the phone. */
class InstallModel(private val vm: AppViewModel) {
    var installing by mutableStateOf(false); private set
    var status by mutableStateOf(""); private set
    var statusIsError by mutableStateOf(false); private set

    fun install(uri: Uri) {
        val tv = vm.tv ?: return
        if (installing) return
        val ctx = UnbloatApp.instance
        val name = runCatching {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull() ?: uri.lastPathSegment.orEmpty().substringAfterLast('/')
        if (!name.endsWith(".apk", ignoreCase = true)) {
            statusIsError = true
            status = str(R.string.Install_NotApk)
            return
        }
        installing = true
        statusIsError = false
        status = str(R.string.Install_Installing, name)
        vm.viewModelScope.launch {
            // The file picker gives a stream: a copy in the app's cache is sent to the TV, then deleted.
            val copy = File(ctx.cacheDir, "install.apk")
            val error = try {
                withContext(Dispatchers.IO) {
                    ctx.contentResolver.openInputStream(uri)!!.use { i -> copy.outputStream().use { i.copyTo(it) } }
                }
                tv.install(copy)
            } catch (e: Exception) {
                e.message ?: e.javaClass.simpleName
            } finally {
                copy.delete()
            }
            statusIsError = error != null
            status = if (error == null) str(R.string.Install_Done, name) else str(R.string.Install_Failed, error)
            installing = false
            if (error == null) runCatching { vm.updateDevice(TvDevice.load(tv)) }
        }
    }
}
