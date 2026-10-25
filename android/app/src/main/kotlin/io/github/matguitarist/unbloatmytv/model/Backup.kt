package io.github.matguitarist.unbloatmytv.model

import io.github.matguitarist.unbloatmytv.UnbloatApp
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.Json
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Field names match the desktop apps' backup files, so a file can be read by either. */
@Serializable
data class SettingChange(
    /** "global", "secure", "system", or "device_config:<namespace>". */
    @SerialName("Namespace") val namespace: String = "",
    @SerialName("Key") val key: String = "",
    /** Value before we touched it; null means it was not set. */
    @SerialName("OldValue") val oldValue: String? = null,
    @SerialName("NewValue") val newValue: String = "",
)

/** An access removed in the privacy check-up; Restore gives it back. */
@Serializable
data class PermissionChange(
    @SerialName("Package") val pkg: String = "",
    @SerialName("Permission") val permission: String = "",
)

/** Everything one run changed, with the original values, so it can be undone exactly. */
@Serializable
class Backup(
    @SerialName("Created") val created: String = LocalDateTime.now().toString(),
    @SerialName("DeviceName") val deviceName: String = "",
    @SerialName("SerialNo") val serialNo: String = "",
    @SerialName("AndroidVersion") val androidVersion: String = "",
    @SerialName("DisabledPackages") val disabledPackages: MutableList<String> = mutableListOf(),
    /** Apps a choice turned back on (e.g. for the screensaver); Restore turns them off again. */
    @SerialName("EnabledPackages") val enabledPackages: MutableList<String> = mutableListOf(),
    @SerialName("Settings") val settings: MutableList<SettingChange> = mutableListOf(),
    @SerialName("WifiWasOn") var wifiWasOn: Boolean? = null,
    @SerialName("RevokedPermissions") val revokedPermissions: MutableList<PermissionChange> = mutableListOf(),
) {
    @Transient var file: File = File("")

    val changeCount: Int get() = disabledPackages.size + enabledPackages.size + settings.size + (if (wifiWasOn == true) 1 else 0) + revokedPermissions.size

    val createdAt: LocalDateTime get() = runCatching { LocalDateTime.parse(created.take(19)) }.getOrDefault(LocalDateTime.MIN)
    val createdText: String get() = runCatching { createdAt.format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT)) }.getOrDefault(created)

    fun save() {
        file.parentFile?.mkdirs()
        file.writeText(json.encodeToString(serializer(), this))
    }

    companion object {
        private val json = Json { prettyPrint = true; ignoreUnknownKeys = true; explicitNulls = false }
        private val stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

        fun folderFor(dev: TvDevice): File {
            val id = dev.serialNo.ifBlank { dev.serial }
            val safe = "${dev.displayName}-$id".map { if (it.isLetterOrDigit() || it == '-') it else '_' }.joinToString("")
            return File(File(UnbloatApp.dataDir, "backups"), safe)
        }

        fun start(dev: TvDevice): Backup {
            val b = Backup(deviceName = dev.displayName, serialNo = dev.serialNo, androidVersion = dev.androidVersion)
            b.file = File(folderFor(dev), "changes-${b.createdAt.format(stamp)}.json")
            return b
        }

        /** Runs that weren't undone, newest first. */
        fun listFor(dev: TvDevice): List<Backup> =
            folderFor(dev).listFiles { f -> f.name.startsWith("changes-") && f.name.endsWith(".json") }.orEmpty()
                .mapNotNull { f -> runCatching { json.decodeFromString(serializer(), f.readText()).also { it.file = f } }.getOrNull() }
                .filter { it.changeCount > 0 }
                .sortedByDescending { it.createdAt }
    }
}
