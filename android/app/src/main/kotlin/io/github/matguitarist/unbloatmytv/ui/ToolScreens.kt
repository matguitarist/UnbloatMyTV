package io.github.matguitarist.unbloatmytv.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.Contacts
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.MyLocation
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.SettingsBackupRestore
import androidx.compose.material.icons.outlined.Tv
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.matguitarist.unbloatmytv.R

/** Title, lead text and a refresh button: the top of every tool page. */
@Composable
fun ToolHeader(title: String, lead: String, loading: Boolean = false, onRefresh: (() -> Unit)? = null) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
        if (onRefresh != null)
            IconButton(onClick = onRefresh, enabled = !loading) { Icon(Icons.Outlined.Refresh, stringResource(R.string.Health_Refresh)) }
    }
    Muted(lead)
    if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
}

@Composable
fun Tips(tips: List<Tip>) = tips.forEach { Banner(it.text, it.kind) }

/** A card with an icon before its title. */
@Composable
fun IconCard(title: String, icon: ImageVector, tint: PillKind = PillKind.Info, content: @Composable ColumnScope.() -> Unit) {
    val c = AppTheme.colors
    AppCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = if (tint == PillKind.Warn) c.warnFg else c.accentFg, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(10.dp))
            SectionTitle(title)
        }
        content()
    }
}

/** Label above value: reads well on a narrow screen. */
@Composable
private fun Facts(rows: List<InfoRow>) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        rows.forEach { r ->
            Column {
                Text(r.label, style = MaterialTheme.typography.labelSmall, color = AppTheme.colors.muted)
                Text(r.value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/** A question asked on the page itself, with Cancel and a confirm button. */
@Composable
fun ConfirmBox(text: String, yes: String, onCancel: () -> Unit, onYes: () -> Unit) {
    Banner("⚠ $text", PillKind.Warn) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onCancel) { Text(stringResource(R.string.Common_Cancel)) }
            Spacer(Modifier.width(8.dp))
            Button(onClick = onYes) { Text(yes) }
        }
    }
}

@Composable
fun StatusBanner(text: String, isError: Boolean) {
    if (text.isEmpty()) return
    if (isError) ErrorBanner(text) else Banner(text, PillKind.Ok)
}

/** The phone must not sleep while the TV is being changed or watched. */
@Composable
fun KeepScreenOn(on: Boolean) {
    val view = LocalView.current
    DisposableEffect(on) {
        view.keepScreenOn = on
        onDispose { view.keepScreenOn = false }
    }
}

@Composable
fun InfoScreen(m: TvInfoModel) {
    KeepScreenOn(m.restarting)
    PageColumn {
        ToolHeader(stringResource(R.string.Info_Title), stringResource(R.string.Info_Lead), m.loading, m::refresh)
        StatusBanner(m.status, m.statusIsError)
        if (m.info == null) return@PageColumn
        Tips(m.tips)

        IconCard(stringResource(R.string.Info_SoftwareTitle), Icons.Outlined.Tv) {
            Facts(m.software)
            OutlinedButton(onClick = { m.openUpdate() }) { Text(stringResource(R.string.Info_OpenUpdate)) }
        }
        IconCard(stringResource(R.string.Info_BootTitle), Icons.Outlined.Security) {
            Facts(m.boot)
            Muted(stringResource(R.string.Info_BootText))
        }
        IconCard(stringResource(R.string.Info_RestartTitle), Icons.Outlined.RestartAlt) {
            Muted(stringResource(R.string.Info_RestartText))
            if (m.confirmingRestart)
                ConfirmBox(stringResource(R.string.Info_ConfirmRestart), stringResource(R.string.Info_ConfirmYes),
                    onCancel = { m.confirmingRestart = false }, onYes = m::restart)
            else
                OutlinedButton(onClick = { m.confirmingRestart = true }, enabled = !m.restarting) { Text(stringResource(R.string.Info_Restart)) }
            if (m.restarting) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        IconCard(stringResource(R.string.Info_ResetTitle), Icons.Outlined.SettingsBackupRestore, PillKind.Warn) {
            Muted(stringResource(R.string.Info_ResetText))
            OutlinedButton(onClick = { m.openAbout() }) { Text(stringResource(R.string.Info_ResetOpen)) }
        }
    }
}

@Composable
fun HealthScreen(m: HealthModel, vm: AppViewModel) {
    PageColumn {
        ToolHeader(stringResource(R.string.Health_Title), stringResource(R.string.Health_Lead), m.loading, m::refresh)
        val r = m.report ?: return@PageColumn
        Tips(m.tips)

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Gauge(stringResource(R.string.Health_Memory), m.memoryUsed,
                stringResource(R.string.Health_MemoryText, HealthModel.gb(r.memAvailableMb), HealthModel.gb(r.memTotalMb)), Modifier.weight(1f))
            Gauge(stringResource(R.string.Health_Storage), m.storageUsed,
                stringResource(R.string.Health_StorageText, HealthModel.gb(r.storageFreeMb), HealthModel.gb(r.storageTotalMb)), Modifier.weight(1f))
        }
        AppCard {
            Text(stringResource(R.string.Health_Cpu), style = MaterialTheme.typography.labelSmall, color = AppTheme.colors.muted)
            Text(m.cpuText, style = MaterialTheme.typography.bodyMedium)
        }

        IconCard(stringResource(R.string.Health_TopTitle), Icons.Outlined.Memory) {
            Muted(stringResource(R.string.Health_TopText))
            r.topMemory.forEachIndexed { i, p ->
                if (i > 0) HorizontalDivider(color = AppTheme.colors.divider)
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        val friendly = io.github.matguitarist.unbloatmytv.model.Catalog.nameOf(p.name)
                        Text(friendly ?: p.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                        if (friendly != null) Mono(p.name)
                    }
                    Text(stringResource(R.string.Health_Mb, p.mb), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        OutlinedButton(onClick = { vm.goChoose(reload = true) }) { Text(stringResource(R.string.Health_GoChoose)) }
    }
}

@Composable
private fun Gauge(title: String, used: Float, text: String, modifier: Modifier) {
    val c = AppTheme.colors
    AppCard(modifier) {
        Text(title, style = MaterialTheme.typography.labelSmall, color = c.muted)
        LinearProgressIndicator(
            progress = { used }, modifier = Modifier.fillMaxWidth().height(6.dp),
            color = if (used > 0.85f) c.warnFg else c.accent, trackColor = c.idle, strokeCap = StrokeCap.Round,
            drawStopIndicator = {},
        )
        Text(text, style = MaterialTheme.typography.bodySmall, color = c.muted)
    }
}

@Composable
fun PrivacyScreen(m: PrivacyModel) {
    PageColumn {
        ToolHeader(stringResource(R.string.Privacy_Title), stringResource(R.string.Privacy_Lead), m.loading, m::refresh)
        Banner(stringResource(R.string.Privacy_Note))
        if (m.status.isNotEmpty()) ErrorBanner(m.status)
        if (!m.loading && m.groups.isEmpty()) Banner(stringResource(R.string.Privacy_None), PillKind.Ok)

        m.groups.forEach { g ->
            val key = g.kind.key
            val (title, text) = kindTexts(key)
            IconCard(stringResource(title), kindIcon(key)) {
                Muted(stringResource(text))
                Pill(stringResource(R.string.Privacy_Count, g.rows.size), PillKind.Count)
                g.rows.forEach { row ->
                    HorizontalDivider(color = AppTheme.colors.divider)
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(row.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                            if (row.name != row.pkg) Mono(row.pkg)
                            Text(
                                stringResource(if (row.access.isThirdParty) R.string.Privacy_Yours else R.string.Privacy_Preinstalled),
                                style = MaterialTheme.typography.bodySmall, color = AppTheme.colors.faint,
                            )
                        }
                        if (row.removed) Pill("✓ " + stringResource(R.string.Privacy_Removed))
                        else OutlinedButton(onClick = { m.remove(row) }, enabled = !row.working) { Text(stringResource(R.string.Privacy_Remove)) }
                    }
                }
            }
        }
    }
}

private fun kindTexts(key: String): Pair<Int, Int> = when (key) {
    "Microphone" -> R.string.Privacy_Kind_Microphone to R.string.Privacy_KindText_Microphone
    "Camera" -> R.string.Privacy_Kind_Camera to R.string.Privacy_KindText_Camera
    "BgLocation" -> R.string.Privacy_Kind_BgLocation to R.string.Privacy_KindText_BgLocation
    "Location" -> R.string.Privacy_Kind_Location to R.string.Privacy_KindText_Location
    "Contacts" -> R.string.Privacy_Kind_Contacts to R.string.Privacy_KindText_Contacts
    else -> R.string.Privacy_Kind_Calendar to R.string.Privacy_KindText_Calendar
}

private fun kindIcon(key: String): ImageVector = when (key) {
    "BgLocation" -> Icons.Outlined.MyLocation
    "Location" -> Icons.Outlined.LocationOn
    "Microphone" -> Icons.Outlined.Mic
    "Camera" -> Icons.Outlined.CameraAlt
    "Contacts" -> Icons.Outlined.Contacts
    else -> Icons.Outlined.CalendarMonth
}

@Composable
fun InstallScreen(m: InstallModel) {
    KeepScreenOn(m.installing)
    // Any file: some file managers don't tag .apk files with their type. The name is checked after.
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(m::install) }
    PageColumn {
        ToolHeader(stringResource(R.string.Install_Title), stringResource(R.string.Install_Lead))
        AppCard {
            Column(Modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Icon(Icons.Outlined.FileDownload, null, tint = AppTheme.colors.accentFg, modifier = Modifier.size(40.dp))
                Button(onClick = { pick.launch(arrayOf("application/vnd.android.package-archive", "application/octet-stream", "*/*")) },
                    enabled = !m.installing) { Text(stringResource(R.string.Install_Pick)) }
            }
        }
        if (m.installing) LinearProgressIndicator(Modifier.fillMaxWidth())
        StatusBanner(m.status, m.statusIsError)
        Banner(stringResource(R.string.Install_Warning), PillKind.Warn)
    }
}
