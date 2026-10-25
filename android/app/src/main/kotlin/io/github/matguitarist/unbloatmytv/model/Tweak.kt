package io.github.matguitarist.unbloatmytv.model

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.matguitarist.unbloatmytv.LocText
import io.github.matguitarist.unbloatmytv.R
import io.github.matguitarist.unbloatmytv.str
import io.github.matguitarist.unbloatmytv.tv.Tv
import kotlinx.coroutines.delay
import kotlin.math.max
import kotlin.math.roundToInt

/** @param keeps Apps this option needs: they are not turned off while it is chosen. */
data class TweakOption(val label: LocText, val value: String, val keeps: List<String> = emptyList())

data class SettingWrite(val namespace: String, val key: String, val value: String)

class ApplyContext(val tv: Tv, val device: TvDevice, val backup: Backup)

/** Result of applying one choice, shown as one line on the Apply page. */
data class TweakResult(val success: Boolean, val detail: String = "")

/** One thing the user can tick. Same kinds and rules as the desktop apps. */
sealed class Tweak(
    val id: String,
    val groupId: String,
    val recommended: Boolean,
    val title: LocText,
    val description: LocText,
    val warning: LocText?,
    val options: List<TweakOption> = emptyList(),
) {
    var isSelected by mutableStateOf(false)
    var selectedOption by mutableStateOf<TweakOption?>(null)
    /** The TV is already set this way: shown with a check mark and not ticked. */
    var isDone by mutableStateOf(false)

    /** Apps the chosen option needs, which must not be turned off in the same run. */
    val keptPackages: List<String> get() = if (isSelected) selectedOption?.keeps.orEmpty() else emptyList()

    /** Small technical line under the description (package name, setting key). */
    abstract val technical: String

    /** Settings this choice writes, to recognise it when an update changed them back. */
    open val settingKeys: List<String> get() = emptyList()

    abstract suspend fun apply(ctx: ApplyContext): TweakResult

    /**
     * Whether the TV is already set the way this choice would set it, and if so with which option.
     * Reading changes nothing. Choices that run something (clear caches…) are never done.
     */
    open suspend fun readDone(tv: Tv, dev: TvDevice): Pair<Boolean, TweakOption?> = false to null

    protected fun matchOption(value: String?): Pair<Boolean, TweakOption?> =
        options.firstOrNull { it.value == value }.let { (it != null) to it }
}

/** Disable one app for the main user ("pm disable-user"). Nothing is uninstalled. */
class PackageTweak(
    id: String, groupId: String, recommended: Boolean, title: LocText, description: LocText, warning: LocText? = null,
    val pkg: String,
) : Tweak(id, groupId, recommended, title, description, warning) {
    override val technical get() = pkg

    override suspend fun apply(ctx: ApplyContext): TweakResult {
        val r = ctx.tv.shell("pm disable-user --user 0 $pkg")
        if ("disabled-user" in r.all) {
            ctx.backup.disabledPackages += pkg
            return TweakResult(true)
        }
        // Fire OS (and some makers) refuse for their own apps; say so instead of a Java error.
        if (r.all.contains("protected package", ignoreCase = true)) return TweakResult(false, str(R.string.Apply_ProtectedPackage))
        return TweakResult(false, firstLine(r.all))
    }
}

/** Write one or more Android settings. "{option}" in a value is replaced by the chosen option. */
class SettingsTweak(
    id: String, groupId: String, recommended: Boolean, title: LocText, description: LocText, warning: LocText?,
    options: List<TweakOption>, val writes: List<SettingWrite>,
    /** False for a plain choice between options, such as the screensaver. */
    private val canBeDone: Boolean = true,
) : Tweak(id, groupId, recommended, title, description, warning, options) {
    override val technical get() = writes.map { it.key }.distinct().joinToString(" · ")
    override val settingKeys get() = writes.map { it.key }

    override suspend fun apply(ctx: ApplyContext): TweakResult {
        // The chosen option needs an app that was turned off (e.g. Google Ambient): turn it back on.
        for (pkg in keptPackages.filter { it in ctx.device.disabled }) {
            ctx.tv.shell("pm enable $pkg")
            ctx.backup.enabledPackages += pkg
            ctx.backup.save()
        }
        for (w in writes) {
            val value = w.value.replace("{option}", selectedOption?.value.orEmpty())
            if (!SettingsIO.change(ctx, w.namespace, w.key, value)) return TweakResult(false, w.key)
        }
        return TweakResult(true)
    }

    /** Done when every setting already has the value of one same option (any option counts). */
    override suspend fun readDone(tv: Tv, dev: TvDevice): Pair<Boolean, TweakOption?> {
        if (!canBeDone) return false to null
        val current = writes.associateWith { SettingsIO.get(tv, it.namespace, it.key) }
        fun matches(option: String) = writes.all { current[it] == it.value.replace("{option}", option) }
        if (options.isEmpty()) return matches("") to null
        return options.firstOrNull { matches(it.value) }.let { (it != null) to it }
    }
}

/** Cap the number of apps Android keeps cached in memory. */
class BackgroundLimitTweak(
    id: String, groupId: String, recommended: Boolean, title: LocText, description: LocText, warning: LocText?, options: List<TweakOption>,
) : Tweak(id, groupId, recommended, title, description, warning, options) {
    override val technical get() = "max_cached_processes"
    override val settingKeys get() = listOf("max_cached_processes", "activity_manager_constants")

    override suspend fun apply(ctx: ApplyContext): TweakResult {
        val value = selectedOption?.value ?: "16"
        // Android 10+ reads it from device_config; older versions from a global setting.
        val ok = if (ctx.device.sdk >= 29) SettingsIO.change(ctx, "device_config:activity_manager", "max_cached_processes", value)
        else SettingsIO.change(ctx, "global", "activity_manager_constants", "max_cached_processes=$value")
        return TweakResult(ok)
    }

    override suspend fun readDone(tv: Tv, dev: TvDevice): Pair<Boolean, TweakOption?> {
        val v = if (dev.sdk >= 29) SettingsIO.get(tv, "device_config:activity_manager", "max_cached_processes")
        else SettingsIO.get(tv, "global", "activity_manager_constants")?.split(',')
            ?.firstOrNull { it.startsWith("max_cached_processes=") }?.removePrefix("max_cached_processes=")
        return matchOption(v)
    }
}

/** Turn the Wi-Fi radio off. Only offered when the TV is on Ethernet. */
class WifiOffTweak(
    id: String, groupId: String, recommended: Boolean, title: LocText, description: LocText, warning: LocText?,
) : Tweak(id, groupId, recommended, title, description, warning) {
    override val technical get() = "svc wifi disable"
    override val settingKeys get() = listOf("wifi_on")

    override suspend fun apply(ctx: ApplyContext): TweakResult {
        ctx.backup.wifiWasOn = SettingsIO.get(ctx.tv, "global", "wifi_on") != "0"
        ctx.backup.save()
        ctx.tv.shell("svc wifi disable")
        return TweakResult(true)
    }

    override suspend fun readDone(tv: Tv, dev: TvDevice) = (SettingsIO.get(tv, "global", "wifi_on") == "0") to null
}

/**
 * Private DNS with a filtering provider. Checked straight away: if names stop resolving,
 * the previous DNS setting is put back on its own.
 */
class PrivateDnsTweak(
    id: String, groupId: String, recommended: Boolean, title: LocText, description: LocText, warning: LocText?, options: List<TweakOption>,
) : Tweak(id, groupId, recommended, title, description, warning, options) {
    override val technical get() = "private_dns_mode · private_dns_specifier"
    override val settingKeys get() = listOf("private_dns_mode", "private_dns_specifier")

    override suspend fun apply(ctx: ApplyContext): TweakResult {
        val host = selectedOption?.value ?: "dns.adguard-dns.com"
        val oldMode = SettingsIO.get(ctx.tv, "global", "private_dns_mode")
        val oldHost = SettingsIO.get(ctx.tv, "global", "private_dns_specifier")
        SettingsIO.change(ctx, "global", "private_dns_specifier", host)
        SettingsIO.change(ctx, "global", "private_dns_mode", "hostname")

        // Give the network stack a moment to validate the new resolver, then test a lookup.
        repeat(4) {
            delay(2_500)
            if ("PING " in ctx.tv.shell("ping -c 1 -W 3 connectivitycheck.gstatic.com").output) return TweakResult(true, host)
        }

        // Lookups fail: undo now rather than leave the TV offline.
        SettingsIO.restore(ctx.tv, "global", "private_dns_mode", oldMode)
        SettingsIO.restore(ctx.tv, "global", "private_dns_specifier", oldHost)
        ctx.backup.settings.removeAll { it.key == "private_dns_mode" || it.key == "private_dns_specifier" }
        ctx.backup.save()
        return TweakResult(false, str(R.string.Apply_DnsReverted))
    }

    /** Done when Private DNS already points at one of the offered filtering services. */
    override suspend fun readDone(tv: Tv, dev: TvDevice): Pair<Boolean, TweakOption?> {
        if (SettingsIO.get(tv, "global", "private_dns_mode") != "hostname") return false to null
        return matchOption(SettingsIO.get(tv, "global", "private_dns_specifier"))
    }
}

/** Clear every app's cache ("pm trim-caches"). Nothing to back up: apps rebuild caches on their own. */
class TrimCachesTweak(
    id: String, groupId: String, recommended: Boolean, title: LocText, description: LocText, warning: LocText?,
) : Tweak(id, groupId, recommended, title, description, warning) {
    override val technical get() = "pm trim-caches"

    override suspend fun apply(ctx: ApplyContext): TweakResult {
        val before = TvDevice.readStorage(ctx.tv)
        val r = ctx.tv.shellLong("pm trim-caches 999G")
        if (!r.ok) return TweakResult(false, firstLine(r.all))
        delay(1_500)
        val after = TvDevice.readStorage(ctx.tv)
        return TweakResult(true, str(R.string.Apply_Freed, max(0, after.second - before.second)))
    }
}

/** Run Android's background app optimisation now ("bg-dexopt-job"). Nothing to back up or undo. */
class OptimizeAppsTweak(
    id: String, groupId: String, recommended: Boolean, title: LocText, description: LocText, warning: LocText?,
) : Tweak(id, groupId, recommended, title, description, warning) {
    override val technical get() = "cmd package bg-dexopt-job"

    override suspend fun apply(ctx: ApplyContext): TweakResult {
        val started = System.currentTimeMillis()
        val r = ctx.tv.shellLong("cmd package bg-dexopt-job")
        if (!r.ok) return TweakResult(false, firstLine(r.all))
        val minutes = max(1, ((System.currentTimeMillis() - started) / 60_000.0).roundToInt())
        return TweakResult(true, str(R.string.Apply_OptimizeDone, minutes))
    }
}

internal fun firstLine(s: String) = s.lines().firstOrNull { it.isNotBlank() }.orEmpty()

/** Reading and writing settings, recording old values in the backup first. */
object SettingsIO {
    private fun split(ns: String) =
        if (ns.startsWith("device_config:")) "device_config" to ns.removePrefix("device_config:") else "settings" to ns

    suspend fun get(tv: Tv, ns: String, key: String): String? {
        val (cmd, n) = split(ns)
        val v = tv.shell("$cmd get $n $key").output.trim()
        return v.takeUnless { it.isEmpty() || it == "null" }
    }

    suspend fun change(ctx: ApplyContext, ns: String, key: String, value: String): Boolean {
        val old = get(ctx.tv, ns, key)
        // Keep the first original value if the same key is changed twice in one run.
        if (ctx.backup.settings.none { it.namespace == ns && it.key == key }) {
            ctx.backup.settings += SettingChange(ns, key, old, value)
            ctx.backup.save()
        }
        val (cmd, n) = split(ns)
        ctx.tv.shell("$cmd put $n $key ${quote(value)}")
        return get(ctx.tv, ns, key) == value
    }

    suspend fun restore(tv: Tv, ns: String, key: String, oldValue: String?) {
        val (cmd, n) = split(ns)
        if (oldValue == null) tv.shell("$cmd delete $n $key") else tv.shell("$cmd put $n $key ${quote(oldValue)}")
    }

    private fun quote(v: String) =
        if (v.all { it.isLetterOrDigit() || it in "._-/=:," }) v else "'" + v.replace("'", "'\\''") + "'"
}
