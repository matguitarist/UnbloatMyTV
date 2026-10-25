package io.github.matguitarist.unbloatmytv.model

import io.github.matguitarist.unbloatmytv.tv.Tv

enum class NetworkKind { Unknown, Ethernet, WiFi }

/** What we know about the connected TV. Read-only queries only. Same readings as the desktop apps. */
data class TvDevice(
    val serial: String,
    val manufacturer: String = "",
    val brand: String = "",
    val model: String = "",
    val serialNo: String = "",
    val androidVersion: String = "",
    /** "Fire OS 7.6.8.8 (PS7688/4591)" on Fire TV, empty elsewhere. */
    val fireOsVersion: String = "",
    val sdk: Int = 0,
    val ramMb: Long = 0,
    val network: NetworkKind = NetworkKind.Unknown,
    val homePackage: String = "",
    /** Every app that can act as the home screen, not just the current one. */
    val homeCapable: Set<String> = emptySet(),
    val inputMethodPackage: String = "",
    val screensaverComponent: String = "",
    val setupComplete: Boolean = false,
    val storageTotalMb: Long = 0,
    val storageFreeMb: Long = 0,
    val enabled: Set<String> = emptySet(),
    val disabled: Set<String> = emptySet(),
    val thirdParty: Set<String> = emptySet(),
) {
    /** Amazon Fire TV: Fire OS refuses to switch off many of Amazon's own apps. */
    val isFireTv: Boolean get() = manufacturer.equals("Amazon", ignoreCase = true)

    fun isInstalled(pkg: String) = pkg in enabled || pkg in disabled

    val displayName: String
        get() {
            if (isFireTv) return FIRE_MODELS[model] ?: "Fire TV $model"
            val maker = brand.ifBlank { manufacturer }
            return if (maker.isEmpty() || model.startsWith(maker, ignoreCase = true)) model else "$maker $model"
        }

    val host: String get() = serial.substringBeforeLast(':')

    /** Packages that must never be disabled, whatever the catalog says. */
    fun isProtected(pkg: String): Boolean =
        pkg == homePackage || pkg in homeCapable || pkg == inputMethodPackage || pkg in PROTECTED ||
            (isFireTv && FIRE_PROTECTED_PREFIXES.any { pkg.startsWith(it) } && !Catalog.isListed(pkg))

    companion object {
        /** Fire TV model codes are not shown anywhere on the device. */
        private val FIRE_MODELS = mapOf(
            "AFTKA" to "Fire TV Stick 4K Max", "AFTKRT" to "Fire TV Stick 4K Max (2nd gen)",
            "AFTMM" to "Fire TV Stick 4K", "AFTKM" to "Fire TV Stick 4K (2nd gen)",
            "AFTSSS" to "Fire TV Stick (3rd gen)", "AFTSS" to "Fire TV Stick Lite",
            "AFTR" to "Fire TV Cube (2nd gen)", "AFTGAZL" to "Fire TV Cube (3rd gen)",
        )

        /**
         * Fire OS answers "Cannot disable a protected package" for many of Amazon's apps, and others hold the
         * TV together: only the Amazon apps the catalog lists, tested on Fire TV, are offered.
         */
        private val FIRE_PROTECTED_PREFIXES = listOf("com.amazon.", "amazon.", "android.amazon.", "com.fireos.", "com.ivona.")

        private val PROTECTED = setOf(
            "android", "com.android.systemui", "com.android.settings", "com.android.tv.settings",
            "com.android.providers.settings", "com.android.providers.tv", "com.android.bluetooth",
            "com.android.shell", "com.android.packageinstaller", "com.google.android.packageinstaller",
            "com.google.android.gms", "com.google.android.gsf", "com.android.vending",
            "com.google.android.webview", "com.android.webview", "com.google.android.tvlauncher",
            "com.google.android.apps.tv.launcherx", "com.google.android.leanbacklauncher",
            "com.android.tv", "com.google.android.permissioncontroller", "com.android.permissioncontroller",
            "com.google.android.inputmethod.latin", "com.android.inputdevices",
        )

        suspend fun load(tv: Tv): TvDevice {
            val p = tv.shell(
                "getprop ro.product.manufacturer; getprop ro.product.brand; getprop ro.product.model; " +
                    "getprop ro.serialno; getprop ro.build.version.release; getprop ro.build.version.sdk; getprop ro.build.version.name"
            ).output.split('\n')
            fun prop(i: Int) = p.getOrNull(i)?.trim().orEmpty()

            val ramKb = tv.shell("grep MemTotal /proc/meminfo").output.filter(Char::isDigit).toLongOrNull() ?: 0

            val homeLine = tv.shell("cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME")
                .output.lines().lastOrNull { it.isNotBlank() }.orEmpty()
            val homeCapable = tv.shell("cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.HOME")
                .output.lines().map { it.trim() }
                .filter { '/' in it && '=' !in it && ' ' !in it }
                .map { it.substringBefore('/') }.toSet()

            val ime = tv.shell("settings get secure default_input_method").output.trim()
            val dream = tv.shell("settings get secure screensaver_components").output.trim()
            val setup = tv.shell("settings get secure user_setup_complete").output.trim()
            val storage = readStorage(tv)

            val net = tv.shell("dumpsys connectivity | grep -E 'NetworkAgentInfo.*CONNECTED/CONNECTED'").output
            val network = when {
                net.contains("type: Ethernet", ignoreCase = true) -> NetworkKind.Ethernet
                net.contains("type: WIFI", ignoreCase = true) -> NetworkKind.WiFi
                else -> NetworkKind.Unknown
            }

            return TvDevice(
                serial = tv.serial,
                manufacturer = prop(0), brand = prop(1), model = prop(2), serialNo = prop(3),
                androidVersion = prop(4), sdk = prop(5).toIntOrNull() ?: 0,
                fireOsVersion = prop(6).takeIf { it.startsWith("Fire OS") }.orEmpty(),
                ramMb = ramKb / 1024,
                network = network,
                homePackage = if ('/' in homeLine) homeLine.substringBefore('/').trim() else "",
                homeCapable = homeCapable,
                inputMethodPackage = if ('/' in ime) ime.substringBefore('/') else "",
                screensaverComponent = dream.takeIf { it != "null" }.orEmpty(),
                setupComplete = setup == "1",
                storageTotalMb = storage.first, storageFreeMb = storage.second,
                enabled = listPackages(tv, "-e"),
                disabled = listPackages(tv, "-d"),
                thirdParty = listPackages(tv, "-3"),
            )
        }

        /** Size and free space of the app storage (/data), from "df -k". Zeros if unreadable. */
        suspend fun readStorage(tv: Tv): Pair<Long, Long> {
            val last = tv.shell("df -k /data").output.lines().lastOrNull { it.isNotBlank() }.orEmpty()
            val cols = last.split(' ').filter { it.isNotEmpty() }
            // Filesystem 1K-blocks Used Available Use% Mounted-on
            val total = cols.getOrNull(1)?.toLongOrNull()
            val free = cols.getOrNull(3)?.toLongOrNull()
            return if (total != null && free != null) total / 1024 to free / 1024 else 0L to 0L
        }

        private suspend fun listPackages(tv: Tv, flag: String): Set<String> =
            tv.shell("pm list packages $flag").output.lines().map { it.trim() }
                .filter { it.startsWith("package:") }.map { it.removePrefix("package:") }.toSet()
    }
}
