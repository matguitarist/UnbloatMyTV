package io.github.matguitarist.unbloatmytv.ui

import android.app.Application
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.matguitarist.unbloatmytv.R
import io.github.matguitarist.unbloatmytv.WorkService
import io.github.matguitarist.unbloatmytv.model.ApplyContext
import io.github.matguitarist.unbloatmytv.model.Backup
import io.github.matguitarist.unbloatmytv.model.Catalog
import io.github.matguitarist.unbloatmytv.model.FirePrivacy
import io.github.matguitarist.unbloatmytv.model.OptimizeAppsTweak
import io.github.matguitarist.unbloatmytv.model.PackageTweak
import io.github.matguitarist.unbloatmytv.model.ReturnCheck
import io.github.matguitarist.unbloatmytv.model.ReturnedItems
import io.github.matguitarist.unbloatmytv.model.SettingsIO
import io.github.matguitarist.unbloatmytv.model.TrimCachesTweak
import io.github.matguitarist.unbloatmytv.model.Tweak
import io.github.matguitarist.unbloatmytv.model.TweakGroup
import io.github.matguitarist.unbloatmytv.model.TvDevice
import io.github.matguitarist.unbloatmytv.str
import io.github.matguitarist.unbloatmytv.tv.Mdns
import io.github.matguitarist.unbloatmytv.tv.Scanner
import io.github.matguitarist.unbloatmytv.tv.Tv
import io.github.matguitarist.unbloatmytv.tv.WirelessNotPaired
import io.github.matguitarist.unbloatmytv.update.Updater
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class Screen {
    Welcome, Connect, Choose, Apply, Restore,
    // Tools for the connected TV, from the menu.
    Info, Health, Privacy, Apps, Remote, Install;

    val isTool: Boolean get() = ordinal >= Restore.ordinal
}

enum class StepState { Pending, Running, Done, Failed, Skipped }

class StepItem(val text: String) {
    var state by mutableStateOf(StepState.Pending)
    var detail by mutableStateOf("")
}

data class RecentTv(val host: String, val name: String)

/** The whole clean-up flow, one page at a time, like the desktop apps. */
class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("app", Context.MODE_PRIVATE)

    var screen by mutableStateOf(if (prefs.getBoolean("welcomed", false)) Screen.Connect else Screen.Welcome)
        private set

    var tv: Tv? = null
        private set
    var device by mutableStateOf<TvDevice?>(null)
        private set

    fun go(s: Screen) {
        if (s == Screen.Connect) prefs.edit().putBoolean("welcomed", true).apply()
        screen = s
    }

    /** A tool changed what is on the TV: the other pages follow. */
    fun updateDevice(d: TvDevice) {
        device = d
    }

    /** Something is running that must not be left halfway: the menu waits. */
    val isBusy: Boolean
        get() = applying || restoreBusy || connecting != null ||
            infoModel?.restarting == true || installModel?.installing == true

    /**
     * What is changing the TV right now, for the notification that keeps the work going when the
     * phone locks; null when nothing is. Connecting isn't in it: that waits for someone at the TV.
     */
    private fun workTitle(): String? {
        val name = device?.displayName.orEmpty()
        return when {
            applying -> str(R.string.Android_WorkApply, name)
            restoreBusy || infoModel?.restarting == true || installModel?.installing == true -> str(R.string.Android_WorkOther, name)
            else -> null
        }
    }

    // ---------- Tools ----------

    var infoModel by mutableStateOf<TvInfoModel?>(null); private set
    var healthModel by mutableStateOf<HealthModel?>(null); private set
    var privacyModel by mutableStateOf<PrivacyModel?>(null); private set
    var appsModel by mutableStateOf<AppsModel?>(null); private set
    var remoteModel by mutableStateOf<RemoteModel?>(null); private set
    var installModel by mutableStateOf<InstallModel?>(null); private set

    /** Each visit reads the TV afresh, like the desktop apps. */
    fun openTool(s: Screen) {
        if (tv == null || isBusy) return
        when (s) {
            Screen.Info -> infoModel = TvInfoModel(this)
            Screen.Health -> healthModel = HealthModel(this)
            Screen.Privacy -> privacyModel = PrivacyModel(this)
            Screen.Apps -> appsModel = AppsModel(this)
            Screen.Remote -> remoteModel = remoteModel ?: RemoteModel(this)
            Screen.Install -> installModel = installModel ?: InstallModel(this)
            Screen.Restore -> return openRestore()
            else -> return
        }
        go(s)
    }

    /** Back to the list, rebuilt if a tool changed the TV meanwhile (an app turned off, the TV restarted…). */
    fun goChoose(reload: Boolean = false) {
        if (tv == null) return go(Screen.Connect)
        if (reload || groups.isEmpty() || groupsFor !== device) openChoose() else go(Screen.Choose)
    }

    // ---------- Connect ----------

    var scanning by mutableStateOf(false)
        private set
    var scanProgress by mutableStateOf(0f)
        private set
    /** Null before the first search. */
    var found by mutableStateOf<List<String>?>(null)
        private set
    var noWifi by mutableStateOf(false)
        private set
    var connecting by mutableStateOf<String?>(null)
        private set
    /** The TV is probably showing "Allow USB debugging?". */
    var waitingForApproval by mutableStateOf(false)
        private set
    var reading by mutableStateOf(false)
        private set
    var connectError by mutableStateOf<String?>(null)
        private set
    /** The TV wants pairing: the tips about USB debugging don't apply. */
    var needsPairing by mutableStateOf(false)
        private set
    val recent = mutableStateListOf<RecentTv>().apply { addAll(loadRecent()) }

    fun scan() {
        if (scanning) return
        val ctx = getApplication<Application>()
        noWifi = Scanner.localAddresses(ctx).isEmpty()
        if (noWifi) return
        scanning = true
        scanProgress = 0f
        viewModelScope.launch {
            // Network debugging answers on port 5555; Wireless debugging announces its own port meanwhile.
            val wireless = async { runCatching { Mdns.discover(ctx, listOf(Mdns.CONNECT), 8_000) }.getOrDefault(emptyList()) }
            val classic = runCatching { Scanner.scan(ctx) { p -> scanProgress = p } }.getOrDefault(emptyList())
            val extra = wireless.await().filter { it.host !in classic }.map { "${it.host}:${it.port}" }
            found = classic + extra
            scanning = false
        }
    }

    fun connect(input: String) {
        val host = input.trim().substringBefore(':')
        val port = input.trim().substringAfter(':', "").toIntOrNull() ?: Tv.PORT
        if (!Regex("""^\d{1,3}(\.\d{1,3}){3}$""").matches(host)) {
            connectError = str(R.string.Connect_BadAddress)
            return
        }
        if (connecting != null) return
        connecting = host
        connectError = null
        needsPairing = false
        viewModelScope.launch {
            // No answer within a few seconds: the TV is most likely asking for permission
            // (Wireless debugging never asks: the phone is paired or it isn't).
            val hint = launch { if (port != Tv.PORT) awaitCancellation(); delay(2_500); waitingForApproval = true }
            try {
                val t = Tv.connect(host, port)
                hint.cancel()
                waitingForApproval = false
                reading = true
                val dev = TvDevice.load(t)
                tv?.close()
                tv = t
                device = dev
                // Wireless debugging: the port too, as a first guess for next time.
                remember(RecentTv(if (t.isWireless) t.serial else host, dev.displayName))
                Catalog.refreshFromGitHub()
                openChoose()
            } catch (e: Exception) {
                hint.cancel()
                val msg = e.message.orEmpty() + " " + (e.cause?.message.orEmpty())
                connectError = when {
                    e is WirelessNotPaired -> {
                        needsPairing = true
                        str(R.string.Android_NeedPairing)
                    }
                    msg.contains("unauthorized", true) || msg.contains("rejected", true) ||
                        msg.contains("timed out", true) || msg.contains("timeout", true) -> str(R.string.Connect_ApprovalTimeout)
                    else -> str(R.string.Connect_Refused, host)
                }
            } finally {
                waitingForApproval = false
                reading = false
                connecting = null
            }
        }
    }

    fun disconnect() {
        tv?.close()
        tv = null
        device = null
        groups = emptyList()
        infoModel = null; healthModel = null; privacyModel = null; appsModel = null
        remoteModel?.stop(); remoteModel = null; installModel = null
        go(Screen.Connect)
    }

    // ---------- Pairing (Wireless debugging) ----------

    var pairAddress by mutableStateOf("")
    var pairCode by mutableStateOf("")
    var pairing by mutableStateOf(false)
        private set
    var pairSearching by mutableStateOf(false)
        private set
    var pairStatus by mutableStateOf("")
        private set
    var pairFailed by mutableStateOf(false)
        private set

    /**
     * While "Pair device with pairing code" is open, the TV announces where to pair: the address
     * fills itself in. Runs as long as the Connect page shows (the caller's coroutine).
     */
    suspend fun watchPairingScreen() {
        val ctx = getApplication<Application>()
        try {
            while (true) {
                pairSearching = pairAddress.isBlank()
                if (pairAddress.isBlank() && !pairing) {
                    // Not runCatching: leaving the page cancels this loop, and that must get through.
                    val s = try {
                        Mdns.discover(ctx, listOf(Mdns.PAIRING), 10_000, first = true).firstOrNull()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        delay(3_000)
                        null
                    }
                    if (s != null && pairAddress.isBlank()) pairAddress = "${s.host}:${s.port}"
                } else {
                    delay(1_000)
                }
            }
        } finally {
            pairSearching = false
        }
    }

    fun pair() {
        val target = pairAddress.trim()
        val host = target.substringBefore(':')
        val port = target.substringAfter(':', "").toIntOrNull()
        val code = pairCode.filter(Char::isDigit)
        if (!Regex("""^\d{1,3}(\.\d{1,3}){3}$""").matches(host) || port == null || code.length != 6) {
            pairFailed = true
            pairStatus = str(R.string.Connect_PairBadInput)
            return
        }
        if (pairing || connecting != null) return
        pairing = true
        pairFailed = false
        pairStatus = str(R.string.Android_Pairing)
        viewModelScope.launch {
            try {
                Tv.pair(host, port, code)
            } catch (e: Exception) {
                pairFailed = true
                pairStatus = str(R.string.Connect_PairFailed)
                pairing = false
                return@launch
            }
            pairCode = ""
            pairStatus = str(R.string.Android_PairedConnecting)
            // The connect port isn't the pairing port: the TV announces it too.
            val connectPort = Mdns.connectPort(getApplication(), host, 8_000)
            pairing = false
            if (connectPort != null) {
                pairStatus = ""
                pairAddress = ""
                connect("$host:$connectPort")
            } else {
                pairStatus = str(R.string.Connect_PairOk)
            }
        }
    }

    private fun loadRecent(): List<RecentTv> =
        prefs.getString("recent", "").orEmpty().lines().filter { '|' in it }
            .map { RecentTv(it.substringBefore('|'), it.substringAfter('|')) }

    private fun remember(r: RecentTv) {
        recent.removeAll { it.host.substringBefore(':') == r.host.substringBefore(':') }
        recent.add(0, r)
        while (recent.size > 5) recent.removeAt(recent.lastIndex)
        prefs.edit().putString("recent", recent.joinToString("\n") { "${it.host}|${it.name}" }).apply()
    }

    // ---------- Choose ----------

    var groups by mutableStateOf<List<TweakGroup>>(emptyList())
        private set
    /** The reading of the TV the list was built from. */
    private var groupsFor: TvDevice? = null
    var returned by mutableStateOf<ReturnedItems?>(null)
        private set
    var returnedTicked by mutableStateOf<Int?>(null)
        private set
    /** Fire TV only: Amazon's tracking switches that are on. */
    var firePrivacyOn by mutableStateOf<List<Int>?>(null)
        private set
    var rebootAfter by mutableStateOf(true)
    /** Bumped whenever a tick changes, so totals recompose. */
    var selectionVersion by mutableIntStateOf(0)
        private set

    val allTweaks: List<Tweak> get() = groups.flatMap { it.tweaks }
    val selectedCount: Int get() { selectionVersion; return allTweaks.count { it.isSelected } }

    private fun openChoose() {
        val dev = device ?: return
        val t = tv ?: return
        groups = Catalog.build(dev)
        groupsFor = dev
        returned = null
        returnedTicked = null
        firePrivacyOn = null
        selectionVersion++
        go(Screen.Choose)
        viewModelScope.launch { checkDone(t, dev) }
        viewModelScope.launch {
            val r = runCatching { ReturnCheck.find(t, dev) }.getOrNull()
            // Only worth showing if something on this list can be ticked again.
            if (r != null && matchingTweaks(r).isNotEmpty()) returned = r
        }
        if (dev.isFireTv) viewModelScope.launch { firePrivacyOn = runCatching { FirePrivacy.readOn(t) }.getOrNull() }
    }

    /** Settings the TV already has are shown as done and unticked. Reading changes nothing. */
    private suspend fun checkDone(t: Tv, dev: TvDevice) {
        for (tweak in allTweaks.filter { it !is PackageTweak }) {
            val (done, option) = runCatching { tweak.readDone(t, dev) }.getOrDefault(false to null)
            if (!done) continue
            if (option != null) tweak.selectedOption = option
            tweak.isSelected = false
            tweak.isDone = true
            selectionVersion++
        }
    }

    fun matchingTweaks(r: ReturnedItems): List<Tweak> = allTweaks.filter { t ->
        (t is PackageTweak && t.pkg in r.packages) || r.settings.any { it.key in t.settingKeys }
    }

    fun tickReturned() {
        val r = returned ?: return
        val list = matchingTweaks(r)
        for (t in list) {
            t.isSelected = true
            // The value chosen last time, when it is one of the options.
            r.settings.firstOrNull { it.key in t.settingKeys }?.let { s ->
                t.options.firstOrNull { o -> s.newValue == o.value || s.newValue.endsWith("=${o.value}") }?.let { t.selectedOption = it }
            }
        }
        returnedTicked = list.size
        selectionVersion++
    }

    fun setSelected(t: Tweak, on: Boolean) {
        t.isSelected = on
        // A chosen option that needs an app (e.g. Google Ambient) unticks turning that app off.
        for (other in allTweaks.filterIsInstance<PackageTweak>().filter { it.pkg in t.keptPackages }) other.isSelected = false
        selectionVersion++
    }

    fun selectRecommended() {
        for (t in allTweaks) t.isSelected = t.recommended && !t.isDone
        selectionVersion++
    }

    fun selectNone() {
        for (t in allTweaks) t.isSelected = false
        selectionVersion++
    }

    fun openFirePrivacy() {
        val t = tv ?: return
        viewModelScope.launch { FirePrivacy.openOnTv(t) }
    }

    // ---------- Apply ----------

    val steps = mutableStateListOf<StepItem>()
    var applyFinished by mutableStateOf(false)
        private set
    var problemCount by mutableIntStateOf(0)
        private set
    var progress by mutableStateOf(0f)
        private set
    var appsOff by mutableIntStateOf(0)
        private set
    var settingsChanged by mutableIntStateOf(0)
        private set
    var appliedWithReboot by mutableStateOf(true)
        private set
    var debugOff by mutableStateOf(false)
        private set
    var turningOffDebug by mutableStateOf(false)
        private set
    var debugStatus by mutableStateOf("")
        private set
    var applying by mutableStateOf(false)
        private set

    fun apply() {
        val t = tv ?: return
        val dev = device ?: return
        val selected = allTweaks.filter { it.isSelected }
        if (selected.isEmpty() || applying) return
        steps.clear()
        applyFinished = false
        problemCount = 0
        appsOff = 0
        settingsChanged = 0
        debugOff = false
        debugStatus = ""
        progress = 0f
        appliedWithReboot = rebootAfter
        go(Screen.Apply)
        applying = true
        viewModelScope.launch {
            try { runApply(t, dev, selected, rebootAfter) } finally { applying = false }
        }
    }

    private suspend fun runApply(t: Tv, dev: TvDevice, selected: List<Tweak>, reboot: Boolean) {
        fun add(text: String) = StepItem(text).also { steps += it }
        val backupStep = add(str(R.string.Apply_Backup))
        val tweakSteps = selected.map { it to add(it.title.text) }
        val rebootStep = if (reboot) add(str(R.string.Apply_Reboot)) else null
        val checkStep = add(str(R.string.Apply_Check))
        val total = steps.size
        var done = 0
        fun tick() { done++; progress = done.toFloat() / total }

        // 1. Backup. No backup, no changes.
        backupStep.state = StepState.Running
        val backup = Backup.start(dev)
        try {
            backup.save()
            backupStep.state = StepState.Done
        } catch (e: Exception) {
            backupStep.detail = e.message.orEmpty()
            backupStep.state = StepState.Failed
            tweakSteps.forEach { it.second.state = StepState.Skipped }
            problemCount = 1
            applyFinished = true
            return
        }
        tick()

        // 2. Changes, packages first so the screensaver fix below can see what went.
        val ctx = ApplyContext(t, dev, backup)
        val kept = selected.flatMap { it.keptPackages }.toSet()
        for ((tweak, step) in tweakSteps.sortedBy { if (it.first is PackageTweak) 0 else 1 }) {
            if (tweak is PackageTweak && tweak.pkg in kept) {
                // A choice in the same run needs this app (e.g. the screensaver): leave it on.
                step.detail = str(R.string.Apply_Kept)
                step.state = StepState.Skipped
                tick()
                continue
            }
            step.state = StepState.Running
            try {
                val r = tweak.apply(ctx)
                step.detail = r.detail
                step.state = if (r.success) StepState.Done else StepState.Failed
            } catch (e: Exception) {
                step.detail = e.message.orEmpty()
                step.state = StepState.Failed
            }
            when {
                step.state == StepState.Failed -> problemCount++
                tweak is PackageTweak -> appsOff++
                tweak !is TrimCachesTweak && tweak !is OptimizeAppsTweak -> settingsChanged++
            }
            runCatching { backup.save() }
            tick()
        }

        // If the active screensaver's app was just turned off, switch to the simple one.
        val dreamPkg = dev.screensaverComponent.substringBefore('/')
        if (dreamPkg.isNotEmpty() && dreamPkg in backup.disabledPackages && dev.isInstalled("com.android.dreams.basic")) {
            SettingsIO.change(ctx, "secure", "screensaver_components", Catalog.BASIC_SCREENSAVER)
            runCatching { backup.save() }
        }

        // 3. Restart and wait for the TV to come back.
        if (rebootStep != null) {
            rebootStep.state = StepState.Running
            val back = t.rebootAndWait()
            if (back) {
                rebootStep.detail = str(R.string.Apply_RebootSettle)
                delay(20_000) // let apps and the network settle
                rebootStep.detail = ""
            } else {
                rebootStep.detail = str(R.string.Apply_RebootTimeout)
                problemCount++
            }
            rebootStep.state = if (back) StepState.Done else StepState.Failed
            tick()
        }

        // 4. Check the essentials: home screen, internet, name lookups.
        checkStep.state = StepState.Running
        val problems = check(t)
        checkStep.detail = if (problems.isEmpty()) str(R.string.Apply_CheckOk) else problems.joinToString("\n")
        checkStep.state = if (problems.isEmpty()) StepState.Done else StepState.Failed
        if (problems.isNotEmpty()) problemCount++
        tick()

        applyFinished = true
        runCatching { device = TvDevice.load(t) }
    }

    private suspend fun check(t: Tv): List<String> {
        val problems = mutableListOf<String>()
        val home = t.shell("cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME")
        if ('/' !in home.output) problems += str(R.string.Apply_CheckNoHome)
        val ip = t.shell("ping -c 1 -W 3 8.8.8.8")
        if (" 0% packet loss" !in ip.output) problems += str(R.string.Apply_CheckNoInternet)
        else if ("PING " !in t.shell("ping -c 1 -W 3 connectivitycheck.gstatic.com").output) problems += str(R.string.Apply_CheckNoDns)
        return problems
    }

    fun backToChoices() {
        if (applying) return
        openChoose()
    }

    /**
     * Turns USB and network debugging off from here. adbd stops as soon as the setting is
     * written, so success is "the TV no longer answers", not the command's output.
     */
    fun turnOffDebugging() {
        val t = tv ?: return
        turningOffDebug = true
        debugStatus = ""
        viewModelScope.launch {
            runCatching { t.shell("settings put global adb_wifi_enabled 0; settings put global adb_enabled 0") }
            delay(3_000)
            val stillThere = runCatching { Tv.connect(t.host, t.port).close(); true }.getOrDefault(false)
            debugOff = !stillThere
            debugStatus = str(if (debugOff) R.string.Apply_DebugOffDone else R.string.Apply_DebugOffFailed)
            if (debugOff) { t.close(); tv = null }
            turningOffDebug = false
        }
    }

    // ---------- Restore ----------

    val backups = mutableStateListOf<Backup>()
    var restoreBusy by mutableStateOf(false)
        private set
    var restoreStatus by mutableStateOf("")
        private set
    var restoreError by mutableStateOf(false)
        private set

    fun openRestore() {
        reloadBackups()
        restoreStatus = ""
        go(Screen.Restore)
    }

    private fun reloadBackups() {
        backups.clear()
        device?.let { backups.addAll(Backup.listFor(it)) }
    }

    /** Undo one run: re-enable its apps and put every setting back as it was. */
    fun restore(b: Backup) = runRestore {
        val t = tv!!
        for (pkg in b.disabledPackages) t.shell("pm enable $pkg")
        for (s in b.settings.asReversed()) SettingsIO.restore(t, s.namespace, s.key, s.oldValue)
        if (b.wifiWasOn == true) t.shell("svc wifi enable")
        for (pkg in b.enabledPackages) t.shell("pm disable-user --user 0 $pkg")
        for (p in b.revokedPermissions) t.shell("pm grant --user 0 ${p.pkg} ${p.permission}")
        // Keep the file for reference, but stop offering it.
        b.file.renameTo(java.io.File(b.file.parentFile, b.file.name.replace("changes-", "restored-")))
        str(R.string.Restore_Done)
    }

    /** A copy of a backup somewhere the person picked (Drive, Downloads…), safe from uninstalling the app. */
    fun exportBackup(b: Backup, uri: android.net.Uri) {
        restoreError = runCatching {
            getApplication<Application>().contentResolver.openOutputStream(uri, "wt")!!.use { it.write(b.file.readBytes()) }
        }.isFailure
        restoreStatus = str(if (restoreError) R.string.Android_BackupSaveFailed else R.string.Android_BackupSaved)
    }

    fun importBackup(uri: android.net.Uri) {
        val dev = device ?: return
        viewModelScope.launch {
            try {
                val text = withContext(Dispatchers.IO) {
                    getApplication<Application>().contentResolver.openInputStream(uri)!!.use { it.readBytes().toString(Charsets.UTF_8) }
                }
                val b = Backup.adopt(dev, text)
                reloadBackups()
                restoreError = false
                restoreStatus = str(R.string.Android_BackupAdded, b.createdText)
            } catch (e: Exception) {
                restoreError = true
                restoreStatus = if (e is IllegalArgumentException) e.message.orEmpty() else str(R.string.Android_BackupBad)
            }
        }
    }

    /** Safety net: re-enable every app disabled by a user (not the ones the maker turned off). */
    fun enableAll() = runRestore {
        val t = tv!!
        var count = 0
        for (pkg in device?.disabled.orEmpty()) {
            // enabled=3 is COMPONENT_ENABLED_STATE_DISABLED_USER, i.e. disabled by someone, not by the firmware.
            if ("enabled=3" !in t.shell("dumpsys package $pkg | grep -m1 -oE 'enabled=[0-9]'").output) continue
            t.shell("pm enable $pkg")
            count++
        }
        str(R.string.Restore_AllDone, count)
    }

    private fun runRestore(work: suspend () -> String) {
        if (restoreBusy || tv == null) return
        restoreBusy = true
        restoreStatus = ""
        restoreError = false
        viewModelScope.launch {
            try {
                restoreStatus = work()
                device = TvDevice.load(tv!!)
                reloadBackups()
            } catch (e: Exception) {
                restoreStatus = e.message.orEmpty()
                restoreError = true
            } finally {
                restoreBusy = false
            }
        }
    }

    // ---------- Updates ----------

    enum class UpdateDialog { None, Available, UpToDate, Failed }

    var update by mutableStateOf<Updater.Info?>(null)
        private set
    var updateDialog by mutableStateOf(UpdateDialog.None)
    var updateProgress by mutableStateOf<Float?>(null)
        private set
    var updateStatus by mutableStateOf("")
        private set
    var autoCheckUpdates: Boolean
        get() = prefs.getBoolean("autoUpdate", true)
        set(v) { prefs.edit().putBoolean("autoUpdate", v).apply(); autoCheckVersion++ }
    /** Bumped so the switch redraws. */
    var autoCheckVersion by mutableIntStateOf(0)
        private set

    /** Version and notes shown once after the app updated itself. */
    var whatsNew by mutableStateOf<Pair<String, String>?>(null)

    init {
        // The first start of a new version shows what changed. 1.8.0 didn't note its version yet:
        // a phone that had used the app before came from it.
        val now = Updater.current
        val before = prefs.getString("lastVersion", null) ?: if (prefs.getBoolean("welcomed", false)) "1.8.0" else null
        prefs.edit().putString("lastVersion", now).apply()
        if (before != null && Updater.isNewer(now, before)) viewModelScope.launch {
            val notes = runCatching { Updater.notesFor(now) }.getOrNull()
            if (!notes.isNullOrBlank()) whatsNew = now to notes
        }
        // Quiet check at start-up: only speaks up when there is something new and not skipped.
        if (autoCheckUpdates) viewModelScope.launch {
            delay(1_500)
            val u = runCatching { Updater.check() }.getOrNull() ?: return@launch
            update = u
            if (prefs.getString("skipped", "") != u.version && !isBusy) updateDialog = UpdateDialog.Available
        }
    }

    fun checkForUpdates() {
        update?.let { updateDialog = UpdateDialog.Available; return }
        viewModelScope.launch {
            updateDialog = try {
                update = Updater.check()
                if (update != null) UpdateDialog.Available else UpdateDialog.UpToDate
            } catch (e: Exception) {
                UpdateDialog.Failed
            }
        }
    }

    fun skipUpdate() {
        update?.let { prefs.edit().putString("skipped", it.version).apply() }
        updateDialog = UpdateDialog.None
    }

    /** Download, check, and hand to Android, which asks to confirm. Not while the TV is being changed. */
    fun installUpdate(ctx: Context) {
        val u = update ?: return
        if (isBusy) { updateStatus = str(R.string.Update_Busy); return }
        if (!Updater.canInstall(ctx)) {
            updateStatus = str(R.string.Android_UpdateAllow)
            ctx.startActivity(
                android.content.Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, android.net.Uri.parse("package:${ctx.packageName}"))
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return
        }
        if (updateProgress != null) return
        updateStatus = str(R.string.Update_Downloading)
        updateProgress = 0f
        viewModelScope.launch {
            try {
                val file = Updater.download(u) { p -> updateProgress = p }
                updateStatus = str(R.string.Android_UpdateInstalling)
                Updater.install(ctx, file)
            } catch (e: Exception) {
                updateStatus = if (e is SecurityException) e.message.orEmpty() else str(R.string.Update_InstallFailed, e.message ?: e.javaClass.simpleName)
            } finally {
                updateProgress = null
            }
        }
    }

    // Last, once every property above exists: these read them straight away.
    init {
        val ctx = getApplication<Application>()
        viewModelScope.launch {
            snapshotFlow { workTitle() }.collect { t -> if (t != null) WorkService.start(ctx, t) else WorkService.stop(ctx) }
        }
        viewModelScope.launch {
            snapshotFlow { if (applying) steps.firstOrNull { it.state == StepState.Running }?.text.orEmpty() to progress else null }
                .collect { it?.let { (step, p) -> WorkService.update(ctx, step, p) } }
        }
    }

    override fun onCleared() {
        WorkService.stop(getApplication())
        tv?.close()
    }
}
