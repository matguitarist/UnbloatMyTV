package io.github.matguitarist.unbloatmytv.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.matguitarist.unbloatmytv.R
import io.github.matguitarist.unbloatmytv.model.NetworkKind
import io.github.matguitarist.unbloatmytv.model.Tweak
import io.github.matguitarist.unbloatmytv.model.TweakGroup
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

@Composable
fun ChooseScreen(vm: AppViewModel) {
    val dev = vm.device ?: return
    val count = vm.selectedCount

    Column(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.weight(1f),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { DeviceCard(vm) }
            if (dev.isFireTv) item { FireCard(vm) }
            vm.returned?.let { r ->
                item {
                    val matches = vm.matchingTweaks(r)
                    AppCard(color = AppTheme.colors.warnBg, border = AppTheme.colors.warnBg) {
                        SectionTitle(stringResource(R.string.Choose_ReturnedTitle))
                        val since = r.since.format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT))
                        Text(
                            stringResource(R.string.Choose_ReturnedText, since,
                                matches.count { it is io.github.matguitarist.unbloatmytv.model.PackageTweak },
                                matches.count { it !is io.github.matguitarist.unbloatmytv.model.PackageTweak }),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        matches.forEach { Text("• " + it.title.text, style = MaterialTheme.typography.bodyMedium) }
                        val ticked = vm.returnedTicked
                        if (ticked == null) Button(onClick = vm::tickReturned) { Text(stringResource(R.string.Choose_ReturnedTick)) }
                        else Text(stringResource(R.string.Choose_ReturnedTicked, ticked), color = AppTheme.colors.okFg)
                    }
                }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(R.string.Choose_Title), style = MaterialTheme.typography.headlineMedium)
                    Muted(stringResource(R.string.Choose_Lead))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = vm::selectRecommended) { Text(stringResource(R.string.Choose_Recommended)) }
                        OutlinedButton(onClick = vm::selectNone) { Text(stringResource(R.string.Choose_None)) }
                    }
                }
            }
            items(vm.groups, key = { it.id }) { g -> GroupCard(vm, g) }
        }

        // Always in sight: how many changes, the restart choice, and Apply.
        Surface(color = AppTheme.colors.card, shadowElevation = 8.dp) {
            Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { vm.rebootAfter = !vm.rebootAfter }) {
                    Checkbox(checked = vm.rebootAfter, onCheckedChange = { vm.rebootAfter = it })
                    Text(stringResource(R.string.Choose_Reboot), style = MaterialTheme.typography.bodyMedium)
                }
                Button(onClick = vm::apply, enabled = count > 0, modifier = Modifier.fillMaxWidth().height(50.dp)) {
                    Text(stringResource(R.string.Choose_Apply, count))
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DeviceCard(vm: AppViewModel) {
    val dev = vm.device ?: return
    AppCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(dev.displayName, style = MaterialTheme.typography.titleLarge)
                Mono(dev.host)
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Pill(if (dev.fireOsVersion.isNotEmpty()) dev.fireOsVersion.substringBefore(" (")
                 else stringResource(R.string.Choose_Android, dev.androidVersion), PillKind.Count)
            Pill(stringResource(R.string.Choose_Ram, String.format(Locale.getDefault(), "%.1f", dev.ramMb / 1024.0)), PillKind.Count)
            if (dev.storageTotalMb > 0)
                Pill(stringResource(R.string.Choose_Storage, String.format(Locale.getDefault(), "%.1f", dev.storageFreeMb / 1024.0)), PillKind.Count)
            Pill(stringResource(when (dev.network) {
                NetworkKind.Ethernet -> R.string.Choose_NetEthernet
                NetworkKind.WiFi -> R.string.Choose_NetWifi
                NetworkKind.Unknown -> R.string.Choose_NetUnknown
            }), PillKind.Count)
            if (dev.disabled.isNotEmpty()) Pill(
                if (dev.disabled.size == 1) stringResource(R.string.Choose_AlreadyOffOne)
                else stringResource(R.string.Choose_AlreadyOff, dev.disabled.size), PillKind.Ok,
            )
        }
    }
}

@Composable
private fun FireCard(vm: AppViewModel) {
    AppCard(border = AppTheme.colors.accent) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Shield, null, tint = AppTheme.colors.accentFg)
            Spacer(Modifier.width(10.dp))
            SectionTitle(stringResource(R.string.Fire_Title))
        }
        Muted(stringResource(R.string.Fire_Text))
        vm.firePrivacyOn?.let { on ->
            if (on.isEmpty()) Text(stringResource(R.string.Fire_PrivacyAllOff), color = AppTheme.colors.okFg)
            else {
                val names = on.map { stringResource(it) }.joinToString(", ")
                Text(stringResource(R.string.Fire_PrivacyOn, names), color = AppTheme.colors.warnFg, fontWeight = FontWeight.SemiBold)
                OutlinedButton(onClick = vm::openFirePrivacy) { Text(stringResource(R.string.Fire_Open)) }
            }
        }
    }
}

@Composable
private fun GroupCard(vm: AppViewModel, g: TweakGroup) {
    // The first groups (tracking, ads) start open; the rest are one tap away.
    var open by rememberSaveable(g.id) { mutableStateOf(g.id in setOf("telemetry", "ads", "privacy", "performance")) }
    vm.selectionVersion // recount when ticks change
    val ticked = g.tweaks.count { it.isSelected }
    AppCard(padding = PaddingValues(0.dp)) {
        Row(
            Modifier.fillMaxWidth().clickable { open = !open }.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(g.title.text, style = MaterialTheme.typography.titleMedium)
                Muted(g.description.text)
            }
            Spacer(Modifier.width(8.dp))
            Pill(stringResource(R.string.Choose_GroupCount, ticked, g.tweaks.size), if (ticked > 0) PillKind.Info else PillKind.Count)
            Icon(if (open) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null, tint = AppTheme.colors.muted)
        }
        AnimatedVisibility(open) {
            Column {
                g.tweaks.forEach { t ->
                    HorizontalDivider(color = AppTheme.colors.divider)
                    TweakRow(vm, t)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TweakRow(vm: AppViewModel, t: Tweak) {
    Column(Modifier.fillMaxWidth().clickable { vm.setSelected(t, !t.isSelected) }.padding(start = 4.dp, end = 16.dp, top = 8.dp, bottom = 12.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            Checkbox(checked = t.isSelected, onCheckedChange = { vm.setSelected(t, it) })
            Column(Modifier.weight(1f).padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(t.title.text, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold))
                    if (t.recommended) Pill(stringResource(R.string.Choose_RecommendedPill))
                    if (t.isDone) Pill(stringResource(R.string.Choose_DonePill))
                }
                Muted(t.description.text)
                t.warning?.let { Text("⚠ " + it.text, color = AppTheme.colors.warnFg, style = MaterialTheme.typography.bodyMedium) }
                Mono(t.technical)
                if (t.options.isNotEmpty()) OptionPicker(t)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OptionPicker(t: Tweak) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { if (t.isSelected) expanded = it }, modifier = Modifier.padding(top = 4.dp)) {
        OutlinedTextField(
            value = t.selectedOption?.label?.text.orEmpty(), onValueChange = {}, readOnly = true, enabled = t.isSelected,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
            textStyle = MaterialTheme.typography.bodyMedium,
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            t.options.forEach { o ->
                DropdownMenuItem(text = { Text(o.label.text) }, onClick = { t.selectedOption = o; expanded = false })
            }
        }
    }
}
