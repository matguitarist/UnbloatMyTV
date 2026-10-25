package io.github.matguitarist.unbloatmytv.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material.icons.outlined.Tv
import androidx.compose.material.icons.outlined.Undo
import androidx.compose.material.icons.outlined.WifiFind
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.matguitarist.unbloatmytv.R

@Composable
fun WelcomeScreen(vm: AppViewModel) {
    PageColumn {
        Text(stringResource(R.string.Welcome_Title), style = MaterialTheme.typography.headlineMedium)
        Muted(stringResource(R.string.Welcome_Lead))
        Feature(Icons.Outlined.Lock, R.string.Welcome_F1Title, R.string.Welcome_F1Text)
        Feature(Icons.Outlined.Undo, R.string.Welcome_F2Title, R.string.Welcome_F2Text)
        Feature(Icons.Outlined.TouchApp, R.string.Welcome_F3Title, R.string.Welcome_F3Text)

        AppCard {
            SectionTitle(stringResource(R.string.Welcome_PrepTitle))
            listOf(R.string.Welcome_Step1, R.string.Welcome_Step2, R.string.Welcome_Step3, R.string.Welcome_Step4)
                .forEachIndexed { i, id -> NumberedStep(i + 1, stringResource(id)) }
            HorizontalDivider(color = AppTheme.colors.divider)
            Muted(stringResource(R.string.Welcome_StepFire))
        }
        Muted(stringResource(R.string.Welcome_Supported))
        Button(onClick = { vm.go(Screen.Connect) }, modifier = Modifier.fillMaxWidth().height(52.dp)) {
            Text(stringResource(R.string.Welcome_Continue), fontSize = 16.sp)
        }
    }
}

@Composable
private fun Feature(icon: ImageVector, title: Int, text: Int) {
    AppCard(padding = androidx.compose.foundation.layout.PaddingValues(16.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            Box(
                Modifier.size(38.dp).clip(MaterialTheme.shapes.small).background(AppTheme.colors.accentSoft),
                contentAlignment = Alignment.Center,
            ) { Icon(icon, null, tint = AppTheme.colors.accentFg) }
            Spacer(Modifier.width(14.dp))
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(stringResource(title), style = MaterialTheme.typography.titleMedium)
                Muted(stringResource(text))
            }
        }
    }
}

@Composable
private fun NumberedStep(n: Int, text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Box(
            Modifier.size(24.dp).clip(CircleShape).background(AppTheme.colors.accentSoft),
            contentAlignment = Alignment.Center,
        ) { Text("$n", color = AppTheme.colors.accentFg, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
        Spacer(Modifier.width(12.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 2.dp))
    }
}

@Composable
fun ConnectScreen(vm: AppViewModel) {
    var address by rememberSaveable { mutableStateOf("") }
    val busy = vm.connecting != null
    // The TV's question can take a while to answer: don't let the phone lock meanwhile.
    val view = LocalView.current
    DisposableEffect(busy || vm.scanning) {
        view.keepScreenOn = busy || vm.scanning
        onDispose { view.keepScreenOn = false }
    }

    PageColumn {
        Text(stringResource(R.string.Connect_Title), style = MaterialTheme.typography.headlineMedium)
        Muted(stringResource(R.string.Connect_Lead))

        // What is happening right now: waiting for the TV's question, reading, or what went wrong.
        when {
            vm.waitingForApproval -> AppCard(color = AppTheme.colors.accentSoft, border = AppTheme.colors.accentSoft) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Tv, null, tint = AppTheme.colors.accentFg)
                    Spacer(Modifier.width(10.dp))
                    Text(stringResource(R.string.Connect_ApproveTitle), style = MaterialTheme.typography.titleMedium, color = AppTheme.colors.accentFg)
                }
                Text(stringResource(R.string.Connect_ApproveText), style = MaterialTheme.typography.bodyMedium)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Muted(stringResource(R.string.Android_ApproveWait))
                }
            }
            vm.reading -> AppCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(stringResource(R.string.Connect_Reading))
                }
            }
            vm.connectError != null -> {
                ErrorBanner(vm.connectError!!)
                if (!vm.needsPairing) Muted(stringResource(R.string.Connect_Tips))
            }
        }

        AppCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.WifiFind, null, tint = AppTheme.colors.accentFg)
                Spacer(Modifier.width(10.dp))
                SectionTitle(stringResource(R.string.Connect_FindTitle))
            }
            Muted(stringResource(R.string.Connect_FindText))
            if (vm.scanning) {
                LinearProgressIndicator(progress = { vm.scanProgress }, modifier = Modifier.fillMaxWidth())
                Muted(stringResource(R.string.Android_Scanning, (vm.scanProgress * 100).toInt()))
            } else {
                OutlinedButton(onClick = vm::scan, enabled = !busy) { Text(stringResource(R.string.Connect_Scan)) }
            }
            if (vm.noWifi) Banner(stringResource(R.string.Android_NoWifi), PillKind.Warn)
            vm.found?.let { list ->
                if (list.isEmpty() && !vm.scanning) Banner(stringResource(R.string.Connect_NothingFound), PillKind.Warn)
                list.forEach { addr ->
                    val name = vm.recent.firstOrNull { it.host.substringBefore(':') == addr.substringBefore(':') }?.name
                    TvRow(addr, name, busy, vm.connecting == addr.substringBefore(':')) { vm.connect(addr) }
                }
            }
        }

        if (vm.recent.isNotEmpty()) AppCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.History, null, tint = AppTheme.colors.accentFg)
                Spacer(Modifier.width(10.dp))
                SectionTitle(stringResource(R.string.Connect_RecentTitle))
            }
            Muted(stringResource(R.string.Connect_RecentText))
            vm.recent.forEach { r -> TvRow(r.host, r.name, busy, vm.connecting == r.host.substringBefore(':')) { vm.connect(r.host) } }
        }

        AppCard {
            SectionTitle(stringResource(R.string.Connect_ManualTitle))
            Muted(stringResource(R.string.Connect_ManualText))
            OutlinedTextField(
                value = address, onValueChange = { address = it }, singleLine = true,
                placeholder = { Text("192.168.1.20") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { vm.connect(address) }),
                modifier = Modifier.fillMaxWidth(),
            )
            Button(onClick = { vm.connect(address) }, enabled = !busy && address.isNotBlank()) {
                Text(stringResource(if (busy && vm.connecting == address.trim().substringBefore(':')) R.string.Connect_Connecting else R.string.Connect_Connect))
            }
        }
        PairCard(vm, busy)
        TextButton(onClick = { vm.go(Screen.Welcome) }) { Text(stringResource(R.string.Common_Back)) }
    }
}

/** Wireless debugging with a pairing code: pair once, then the phone connects by itself. */
@Composable
private fun PairCard(vm: AppViewModel, busy: Boolean) {
    // Listen for the TV's pairing screen while this page shows.
    LaunchedEffect(Unit) { vm.watchPairingScreen() }
    AppCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Lock, null, tint = AppTheme.colors.accentFg)
            Spacer(Modifier.width(10.dp))
            SectionTitle(stringResource(R.string.Connect_PairTitle))
        }
        Muted(stringResource(R.string.Android_PairText))
        OutlinedTextField(
            value = vm.pairAddress, onValueChange = { vm.pairAddress = it }, singleLine = true,
            placeholder = { Text("192.168.1.20:37099") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth(),
        )
        if (vm.pairSearching && vm.pairAddress.isBlank()) Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
            Muted(stringResource(R.string.Android_PairSearching))
        }
        OutlinedTextField(
            value = vm.pairCode, onValueChange = { vm.pairCode = it.filter(Char::isDigit).take(6) }, singleLine = true,
            label = { Text(stringResource(R.string.Android_PairCode)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { vm.pair() }),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = vm::pair, enabled = !busy && !vm.pairing && vm.pairCode.length == 6 && vm.pairAddress.isNotBlank()) {
                Text(stringResource(R.string.Connect_Pair))
            }
            if (vm.pairing) {
                Spacer(Modifier.width(12.dp))
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            }
        }
        if (vm.pairStatus.isNotEmpty()) {
            if (vm.pairFailed) ErrorBanner(vm.pairStatus) else Banner(vm.pairStatus, PillKind.Ok)
        }
    }
}

@Composable
private fun TvRow(host: String, name: String?, busy: Boolean, isThis: Boolean, onConnect: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Outlined.Tv, null, tint = AppTheme.colors.muted)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(name ?: host, style = MaterialTheme.typography.titleMedium)
            if (name != null) Mono(host)
            if (':' in host) Text(stringResource(R.string.Android_Wireless), style = MaterialTheme.typography.bodySmall, color = AppTheme.colors.muted)
        }
        if (isThis) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
        else Button(onClick = onConnect, enabled = !busy) { Text(stringResource(R.string.Connect_ConnectThis)) }
    }
}
