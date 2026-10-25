package io.github.matguitarist.unbloatmytv.model

import io.github.matguitarist.unbloatmytv.R
import io.github.matguitarist.unbloatmytv.tv.Tv
import java.time.LocalDateTime

/**
 * Amazon's tracking switches on Fire TV. They are kept in the Amazon account and synced back,
 * so the app only reads them and sends the user to the TV's own menu.
 */
object FirePrivacy {
    /** Secure setting (1 = on) and the name of the switch in the TV's menu. */
    val switches = listOf(
        "advertisingIdApp:interestBasedAds:value" to R.string.Fire_IbAds,
        "settingsApp:deviceUsageData:value" to R.string.Fire_DeviceUsage,
        "settingsApp:appUsageData:value" to R.string.Fire_AppUsage,
    )

    /** The names of the switches that are on. */
    suspend fun readOn(tv: Tv): List<Int> =
        switches.filter { (key, _) -> tv.shell("settings get secure $key").output.trim() == "1" }.map { it.second }

    /** Opens Settings → Preferences on the TV, where Privacy Settings is. */
    suspend fun openOnTv(tv: Tv) {
        tv.shell("am start -a com.amazon.device.settings.action.PREFERENCES")
    }
}

/** What earlier runs turned off or changed that is back the way it was, usually after a system update. */
data class ReturnedItems(val since: LocalDateTime, val packages: List<String>, val settings: List<SettingChange>) {
    val count get() = packages.size + settings.size
}

object ReturnCheck {
    /** Compare the TV with the backups of runs that weren't undone. Read-only. */
    suspend fun find(tv: Tv, dev: TvDevice): ReturnedItems? {
        val runs = Backup.listFor(dev) // newest first, undone runs excluded
        if (runs.isEmpty()) return null

        val packages = runs.flatMap { it.disabledPackages }.distinct()
            .filter { it in dev.enabled }
            .filter { p -> runs.none { p in it.enabledPackages } } // turned back on on purpose
            .sorted()

        // The latest value we set for each setting.
        val wanted = LinkedHashMap<Pair<String, String>, SettingChange>()
        for (run in runs) for (s in run.settings) wanted.putIfAbsent(s.namespace to s.key, s)
        if (runs.any { it.wifiWasOn == true }) wanted.putIfAbsent("global" to "wifi_on", SettingChange("global", "wifi_on", null, "0"))

        // One shell call reads them all: "@@<n>" before each value.
        val list = wanted.values.toList()
        val settings = mutableListOf<SettingChange>()
        if (list.isNotEmpty()) {
            val script = list.mapIndexed { i, s ->
                val (cmd, ns) = if (s.namespace.startsWith("device_config:")) "device_config" to s.namespace.removePrefix("device_config:")
                else "settings" to s.namespace
                "echo @@$i; $cmd get $ns ${s.key}"
            }.joinToString("; ")
            val values = mutableMapOf<Int, String>()
            var current = -1
            for (line in tv.shell(script).output.lines()) {
                val n = if (line.startsWith("@@")) line.drop(2).trim().toIntOrNull() else null
                if (n != null) current = n else if (current >= 0 && current !in values) values[current] = line.trim()
            }
            list.forEachIndexed { i, s ->
                val now = values[i].orEmpty()
                // wifi_on: anything but "0" means the radio is back on.
                val changedBack = if (s.key == "wifi_on") now != "0" else now != s.newValue
                if (changedBack && now.isNotEmpty()) settings += s
            }
        }

        return ReturnedItems(runs[0].createdAt, packages, settings).takeIf { it.count > 0 }
    }
}
