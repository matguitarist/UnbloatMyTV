package io.github.matguitarist.unbloatmytv.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.VolumeDown
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Sort
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.matguitarist.unbloatmytv.R
import io.github.matguitarist.unbloatmytv.model.UserApps

@Composable
fun AppsScreen(m: AppsModel) {
    val c = AppTheme.colors
    PageColumn {
        ToolHeader(stringResource(R.string.Apps_Title), stringResource(R.string.Apps_Lead), m.loading, m::refresh)
        Muted(stringResource(R.string.Apps_Note))

        val outside = m.outside
        if (outside.isNotEmpty()) {
            // Apps that didn't come from a store, by name
            Banner("⚠ " + stringResource(R.string.Apps_Outside, outside.size), PillKind.Warn) {
                Text(outside.joinToString("\n") { "• ${it.name} · ${AppRow.origin(it.app)}" },
                    color = c.warnFg, style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { m.onlyOutside = !m.onlyOutside }) {
                    Text(stringResource(if (m.onlyOutside) R.string.Apps_ShowAll else R.string.Apps_ShowOutside))
                }
            }
        }
        StatusBanner(m.status, m.statusIsError)

        if (!m.loading && m.rows.isEmpty()) {
            Banner(stringResource(R.string.Apps_None), PillKind.Ok)
            return@PageColumn
        }
        if (m.rows.isEmpty()) return@PageColumn

        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = m.search, onValueChange = { m.search = it }, singleLine = true,
                placeholder = { Text(stringResource(R.string.Apps_Search)) },
                leadingIcon = { Icon(Icons.Outlined.Search, null) },
                modifier = Modifier.weight(1f),
            )
            SortMenu(m)
        }
        Muted(m.summary)

        val visible = m.visible
        if (visible.isEmpty()) {
            Muted(stringResource(R.string.Apps_NoMatch))
        } else {
            AppCard(padding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 6.dp)) {
                visible.forEachIndexed { i, row ->
                    if (i > 0) HorizontalDivider(color = c.divider)
                    AppRowView(row, m)
                }
            }
        }
    }
}

@Composable
private fun SortMenu(m: AppsModel) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Outlined.Sort, stringResource(R.string.Apps_SortName)) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            listOf(AppsModel.Sort.Name to R.string.Apps_SortName, AppsModel.Sort.Size to R.string.Apps_SortSize,
                AppsModel.Sort.Newest to R.string.Apps_SortNewest).forEach { (s, label) ->
                DropdownMenuItem(
                    text = { Text(stringResource(label), fontWeight = if (m.sort == s) FontWeight.SemiBold else FontWeight.Normal) },
                    onClick = { m.sort = s; open = false },
                )
            }
        }
    }
}

@Composable
private fun AppRowView(row: AppRow, m: AppsModel) {
    val c = AppTheme.colors
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // 16:9, like the tiles on the TV's home screen
            Box(Modifier.width(64.dp).height(36.dp).clip(RoundedCornerShape(6.dp)).background(c.idle)) {
                row.icon?.let { Image(it, null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxWidth().height(36.dp)) }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(row.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (row.hasFriendlyName) Mono(row.pkg)
            }
            if (row.gone) Pill("✓ " + stringResource(R.string.Apps_Uninstalled))
            else ActionsMenu(row, m)
        }
        Text(row.details, style = MaterialTheme.typography.bodySmall, color = c.muted)
        if (row.isProtected || row.isOutside || row.isOff) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (row.isProtected) Pill(stringResource(R.string.Apps_Needed), PillKind.Info)
                if (row.isOutside) Pill(stringResource(R.string.Apps_NotFromStore), PillKind.Warn)
                if (row.isOff) Pill(stringResource(R.string.Apps_Off), PillKind.Count)
            }
        }
        if (row.confirming)
            ConfirmBox(stringResource(R.string.Apps_ConfirmUninstall, row.name), stringResource(R.string.Apps_Uninstall),
                onCancel = { row.confirming = false }, onYes = { m.uninstall(row) })
    }
}

@Composable
private fun ActionsMenu(row: AppRow, m: AppsModel) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }, enabled = !row.working) { Icon(Icons.Outlined.MoreVert, stringResource(R.string.Apps_Actions)) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            @Composable
            fun item(label: Int, enabled: Boolean = true, action: () -> Unit) =
                DropdownMenuItem(text = { Text(stringResource(label)) }, enabled = enabled, onClick = { open = false; action() })
            item(R.string.Apps_Open, row.canOpen) { m.run(row, R.string.Apps_Opened, UserApps::open) }
            item(R.string.Apps_ShowInfo) { m.run(row, R.string.Apps_InfoOpened, UserApps::showInfo) }
            item(R.string.Apps_Stop, row.canStop) { m.run(row, R.string.Apps_Stopped, UserApps::stop) }
            HorizontalDivider()
            if (row.app.isEnabled) item(R.string.Apps_TurnOff, row.canTurnOff) { m.setEnabled(row, on = false) }
            else item(R.string.Apps_TurnOn, row.canTurnOn) { m.setEnabled(row, on = true) }
            item(R.string.Apps_UninstallMenu, row.canRemove) { row.confirming = true }
        }
    }
}

@Composable
fun RemoteScreen(m: RemoteModel) {
    val c = AppTheme.colors
    KeepScreenOn(true)
    DisposableEffect(m) {
        m.start()
        onDispose { m.stop() }
    }
    PageColumn {
        ToolHeader(stringResource(R.string.Remote_Title), stringResource(R.string.Remote_Lead))
        Box(
            Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(12.dp)).background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            val img = m.screen
            if (img != null) Image(img, null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxWidth())
            else Text(stringResource(R.string.Remote_Loading), color = Color.White.copy(alpha = 0.6f))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = m.live, onCheckedChange = { m.live = it })
            Spacer(Modifier.width(10.dp))
            Text(stringResource(R.string.Remote_Live), modifier = Modifier.weight(1f))
            if (!m.live) TextButton(onClick = m::refreshOnce) { Text(stringResource(R.string.Health_Refresh)) }
        }
        Text(stringResource(R.string.Remote_Protected), style = MaterialTheme.typography.bodySmall, color = c.muted)

        // The remote: arrows round OK, then the usual keys.
        Column(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            PadKey(Icons.Outlined.KeyboardArrowUp, "Up") { m.key(19) }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                PadKey(Icons.Outlined.KeyboardArrowLeft, "Left") { m.key(21) }
                Surface(onClick = { m.key(23) }, shape = CircleShape, color = c.accent, modifier = Modifier.size(76.dp)) {
                    Box(contentAlignment = Alignment.Center) { Text("OK", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp) }
                }
                PadKey(Icons.Outlined.KeyboardArrowRight, "Right") { m.key(22) }
            }
            PadKey(Icons.Outlined.KeyboardArrowDown, "Down") { m.key(20) }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            RoundKey(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.Remote_Back)) { m.key(4) }
            RoundKey(Icons.Outlined.Home, stringResource(R.string.Remote_Home)) { m.key(3) }
            RoundKey(Icons.Outlined.Menu, stringResource(R.string.Remote_Menu)) { m.key(82) }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            RoundKey(Icons.Outlined.PlayArrow, stringResource(R.string.Remote_Play)) { m.key(85) }
            RoundKey(Icons.AutoMirrored.Outlined.VolumeDown, stringResource(R.string.Remote_VolDown)) { m.key(25) }
            RoundKey(Icons.AutoMirrored.Outlined.VolumeUp, stringResource(R.string.Remote_VolUp)) { m.key(24) }
        }

        AppCard {
            Text(stringResource(R.string.Remote_TypeLabel), style = MaterialTheme.typography.labelSmall, color = c.muted)
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = m.textToSend, onValueChange = { m.textToSend = it }, singleLine = true,
                    placeholder = { Text(stringResource(R.string.Remote_TypeHint)) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { m.sendText() }),
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = m::sendText, enabled = m.textToSend.isNotEmpty()) { Text(stringResource(R.string.Remote_Send)) }
            }
        }
    }
}

@Composable
private fun PadKey(icon: ImageVector, label: String, onClick: () -> Unit) {
    FilledTonalIconButton(onClick = onClick, modifier = Modifier.size(64.dp)) {
        Icon(icon, label, modifier = Modifier.size(34.dp))
    }
}

@Composable
private fun RoundKey(icon: ImageVector, label: String, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        FilledTonalIconButton(onClick = onClick, modifier = Modifier.size(52.dp), colors = IconButtonDefaults.filledTonalIconButtonColors()) {
            Icon(icon, label)
        }
        Text(label, style = MaterialTheme.typography.labelSmall, color = AppTheme.colors.muted)
    }
}
