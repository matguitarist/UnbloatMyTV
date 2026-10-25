package io.github.matguitarist.unbloatmytv.model

import io.github.matguitarist.unbloatmytv.LocText
import io.github.matguitarist.unbloatmytv.UnbloatApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/** A group of choices on the Choose page. */
class TweakGroup(val id: String, val title: LocText, val description: LocText) {
    val tweaks = mutableListOf<Tweak>()
}

/**
 * Builds the list of choices for one TV from shared/catalog.json: the copy built into the app,
 * or a newer one downloaded from GitHub (the same file the desktop apps use).
 */
object Catalog {
    const val BASIC_SCREENSAVER = "com.android.dreams.basic/com.android.dreams.basic.Colors"
    /** Catalog format this version understands. */
    private const val SCHEMA = 1
    private const val REMOTE_URL = "https://raw.githubusercontent.com/matguitarist/UnbloatMyTV/main/shared/catalog.json"
    private val cacheFile get() = File(UnbloatApp.dataDir, "catalog.json")

    @Serializable data class Text(val en: String = "", val fr: String = "")
    @Serializable data class GroupDef(val id: String = "", val title: Text = Text(), val description: Text = Text())
    @Serializable data class PackageDef(
        val id: String? = null,
        val prefix: String? = null,
        val group: String = "",
        val recommended: Boolean = false,
        val requiresSetupComplete: Boolean = false,
        val name: Text = Text(),
        val description: Text = Text(),
        val warning: Text? = null,
    )
    /** A known system part that must stay on: never offered. */
    @Serializable data class KeepDef(val id: String = "")
    /** A readable name for a system part. Never offered. */
    @Serializable data class NameDef(val id: String = "", val name: Text = Text())
    @Serializable data class OptionDef(
        val label: Text = Text(),
        val value: String = "",
        /** Offered only when this app is installed (it is turned back on if needed). */
        val installed: String? = null,
        val keeps: List<String>? = null,
    )
    @Serializable data class WriteDef(val namespace: String = "", val key: String = "", val value: String = "")
    @Serializable data class WhenDef(
        val minSdk: Int? = null,
        val maxSdk: Int? = null,
        val network: String? = null,
        val installed: String? = null,
        val screensaverIsNot: String? = null,
        val storageFreeBelowPercent: Int? = null,
    )
    @Serializable data class SettingDef(
        val id: String = "",
        val kind: String = "settings",
        val group: String = "",
        val recommended: Boolean = false,
        val `when`: WhenDef? = null,
        val recommendedWhen: WhenDef? = null,
        val name: Text = Text(),
        val description: Text = Text(),
        val warning: Text? = null,
        val options: List<OptionDef>? = null,
        val writes: List<WriteDef>? = null,
    )
    @Serializable data class CatalogFile(
        val schema: Int = 0,
        val version: Int = 0,
        val groups: List<GroupDef> = emptyList(),
        val packages: List<PackageDef> = emptyList(),
        val settings: List<SettingDef> = emptyList(),
        val keep: List<KeepDef> = emptyList(),
        val names: List<NameDef> = emptyList(),
    )

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Null if the text isn't a catalog this version can use. */
    private fun parse(text: String): CatalogFile? = try {
        val c = json.decodeFromString<CatalogFile>(text)
        val groups = c.groups.map { it.id }.toSet()
        when {
            c.schema != SCHEMA || c.groups.isEmpty() || "other" !in groups -> null
            c.packages.any { it.group !in groups || (it.id == null && it.prefix.isNullOrEmpty()) } -> null
            c.settings.any { it.group !in groups } -> null
            else -> c
        }
    } catch (_: Exception) {
        null
    }

    private val embedded: CatalogFile by lazy {
        UnbloatApp.instance.assets.open("catalog.json").bufferedReader().use { parse(it.readText()) }
            ?: error("built-in catalog.json is invalid")
    }

    private var current: CatalogFile? = null
    private val file: CatalogFile
        get() = current ?: run {
            val cached = runCatching { parse(cacheFile.readText()) }.getOrNull()
            (if (cached != null && cached.version > embedded.version) cached else embedded).also { current = it }
        }

    val version: Int get() = file.version

    private var lastRefresh = 0L

    /** Fetch the latest list from GitHub; it is used from the next TV list on. At most once a minute. */
    suspend fun refreshFromGitHub() = withContext(Dispatchers.IO) {
        if (System.currentTimeMillis() - lastRefresh < 60_000) return@withContext
        lastRefresh = System.currentTimeMillis()
        try {
            val conn = URL(REMOTE_URL).openConnection() as HttpURLConnection
            conn.connectTimeout = 10_000
            conn.readTimeout = 20_000
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            val remote = parse(text) ?: return@withContext
            if (remote.version <= file.version) return@withContext
            cacheFile.writeText(text)
            current = remote
        } catch (_: Exception) {
            // Offline: keep what we have.
        }
    }

    private fun l(t: Text) = LocText(t.en, t.fr)

    fun isKeep(pkg: String) = file.keep.any { it.id == pkg }

    /** Whether the catalog lists this exact app as one that can be turned off. */
    fun isListed(pkg: String) = file.packages.any { it.id == pkg }

    /** Whether the Choose page offers this app on this TV: listed by id or prefix, on, and not needed. */
    fun isOffered(dev: TvDevice, pkg: String): Boolean =
        pkg in dev.enabled && !dev.isProtected(pkg) && !isKeep(pkg) &&
            file.packages.any { it.id == pkg || (!it.prefix.isNullOrEmpty() && pkg.startsWith(it.prefix)) }

    /** The catalog's name for an app, if it has one. */
    fun nameOf(pkg: String): String? {
        val app = pkg.substringBefore(':')
        for (id in listOf(pkg, app)) {
            file.packages.firstOrNull { it.id == id }?.let { return l(it.name).text }
            file.names.firstOrNull { it.id == id }?.let { return l(it.name).text }
        }
        return null
    }

    fun build(dev: TvDevice): List<TweakGroup> {
        val c = file
        val groups = c.groups.associate { it.id to TweakGroup(it.id, l(it.title), l(it.description)) }
        val used = mutableSetOf<String>()

        // Apps from the catalog, only those installed, enabled and not protected on this TV.
        for (def in c.packages) {
            if (def.requiresSetupComplete && !dev.setupComplete) continue
            val matches = if (def.id != null) listOfNotNull(def.id.takeIf { it in dev.enabled })
            else dev.enabled.filter { it.startsWith(def.prefix!!) }.sorted()
            for (pkg in matches) {
                if (dev.isProtected(pkg) || !used.add(pkg)) continue
                groups.getValue(def.group).tweaks += PackageTweak(
                    id = "pkg:$pkg", groupId = def.group, recommended = def.recommended,
                    title = l(def.name), description = l(def.description), warning = def.warning?.let(::l), pkg = pkg,
                )
            }
        }

        // Apps installed on top of the system that the catalog doesn't know.
        for (pkg in dev.thirdParty.filter { it in dev.enabled }.sorted()) {
            if (pkg in used || dev.isProtected(pkg) || c.keep.any { it.id == pkg }) continue
            groups.getValue("other").tweaks += PackageTweak(
                id = "pkg:$pkg", groupId = "other", recommended = false,
                title = LocText(friendlyName(pkg), friendlyName(pkg)),
                description = LocText("Installed app.", "Application installée."), pkg = pkg,
            )
        }

        for (def in c.settings)
            if (matches(def.`when`, dev)) createSetting(def, dev)?.let { groups.getValue(def.group).tweaks += it }

        val list = groups.values.filter { it.tweaks.isNotEmpty() }
        for (t in list.flatMap { it.tweaks }) {
            if (t.selectedOption == null) t.selectedOption = t.options.firstOrNull()
            t.isSelected = t.recommended
        }
        return list
    }

    /** "com.example.app" → "App": readable enough for an app the catalog doesn't know. */
    private fun friendlyName(pkg: String): String {
        val parts = pkg.split('.')
        val last = if (parts.size > 1 && parts.last() in setOf("android", "tv", "app", "androidtv")) parts[parts.size - 2] else parts.last()
        return if (last.isEmpty()) pkg else last.replaceFirstChar { it.uppercaseChar() }
    }

    private fun matches(w: WhenDef?, dev: TvDevice): Boolean {
        if (w == null) return true
        if (w.minSdk != null && dev.sdk < w.minSdk) return false
        if (w.maxSdk != null && dev.sdk > w.maxSdk) return false
        if (w.network == "ethernet" && dev.network != NetworkKind.Ethernet) return false
        if (w.network == "wifi" && dev.network != NetworkKind.WiFi) return false
        if (w.installed != null && !dev.isInstalled(w.installed)) return false
        if (w.screensaverIsNot != null && dev.screensaverComponent == w.screensaverIsNot) return false
        if (w.storageFreeBelowPercent != null &&
            (dev.storageTotalMb == 0L || dev.storageFreeMb * 100 >= dev.storageTotalMb * w.storageFreeBelowPercent)) return false
        return true
    }

    /** A setting from the catalog, or null for a kind this version doesn't know. */
    private fun createSetting(def: SettingDef, dev: TvDevice): Tweak? {
        var description = l(def.description)
        if ("{storage}" in description.en)
            description = LocText(description.en.replace("{storage}", storageText(dev, false)),
                description.fr.replace("{storage}", storageText(dev, true)))
        val options = def.options.orEmpty()
            .filter { it.installed == null || dev.isInstalled(it.installed) }
            .map { TweakOption(l(it.label), it.value, it.keeps.orEmpty()) }
        val recommended = def.recommended || (def.recommendedWhen != null && matches(def.recommendedWhen, dev))
        val title = l(def.name)
        val warning = def.warning?.let(::l)
        val writes = def.writes.orEmpty().map { SettingWrite(it.namespace, it.key, it.value) }

        return when (def.kind) {
            "settings" -> if (writes.isEmpty()) null
            else SettingsTweak(def.id, def.group, recommended, title, description, warning, options, writes)
            // A choice of screensaver, starting on the current one. Nothing to choose: not offered.
            "screensaver" -> if (writes.isEmpty() || options.isEmpty() ||
                (options.size == 1 && options[0].value == dev.screensaverComponent)) null
            else SettingsTweak(def.id, def.group, recommended, title, description, warning, options, writes, canBeDone = false).also {
                it.selectedOption = if (recommended) options[0] else options.firstOrNull { o -> o.value == dev.screensaverComponent } ?: options[0]
            }
            "optimizeApps" -> OptimizeAppsTweak(def.id, def.group, recommended, title, description, warning)
            "backgroundLimit" -> BackgroundLimitTweak(def.id, def.group, recommended, title, description, warning, options)
            "privateDns" -> PrivateDnsTweak(def.id, def.group, recommended, title, description, warning, options)
            "wifiOff" -> WifiOffTweak(def.id, def.group, recommended, title, description, warning)
            "trimCaches" -> if (dev.storageTotalMb > 0) TrimCachesTweak(def.id, def.group, recommended, title, description, warning) else null
            else -> null
        }
    }

    private fun storageText(dev: TvDevice, fr: Boolean): String {
        val locale = if (fr) Locale.FRENCH else Locale.ENGLISH
        fun gb(mb: Long) = String.format(locale, "%.1f", mb / 1024.0)
        return if (fr) "${gb(dev.storageFreeMb)} Go libres sur ${gb(dev.storageTotalMb)} Go"
        else "${gb(dev.storageFreeMb)} GB free of ${gb(dev.storageTotalMb)} GB"
    }
}
