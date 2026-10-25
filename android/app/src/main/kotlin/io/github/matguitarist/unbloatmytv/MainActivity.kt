package io.github.matguitarist.unbloatmytv

import android.os.Bundle
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.InstallMobile
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.MonitorHeart
import androidx.compose.material.icons.outlined.PanTool
import androidx.compose.material.icons.outlined.SettingsRemote
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Switch
import androidx.compose.runtime.key
import io.github.matguitarist.unbloatmytv.update.Updater
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import kotlinx.coroutines.launch
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.matguitarist.unbloatmytv.ui.AppTheme
import io.github.matguitarist.unbloatmytv.ui.AppViewModel
import io.github.matguitarist.unbloatmytv.ui.ApplyScreen
import io.github.matguitarist.unbloatmytv.ui.AppsScreen
import io.github.matguitarist.unbloatmytv.ui.HealthScreen
import io.github.matguitarist.unbloatmytv.ui.InfoScreen
import io.github.matguitarist.unbloatmytv.ui.InstallScreen
import io.github.matguitarist.unbloatmytv.ui.Muted
import io.github.matguitarist.unbloatmytv.ui.PrivacyScreen
import io.github.matguitarist.unbloatmytv.ui.RemoteScreen
import io.github.matguitarist.unbloatmytv.ui.ChooseScreen
import io.github.matguitarist.unbloatmytv.ui.ConnectScreen
import io.github.matguitarist.unbloatmytv.ui.Logo
import io.github.matguitarist.unbloatmytv.ui.RestoreScreen
import io.github.matguitarist.unbloatmytv.ui.Screen
import io.github.matguitarist.unbloatmytv.ui.StepsBar
import io.github.matguitarist.unbloatmytv.ui.UnbloatTheme
import io.github.matguitarist.unbloatmytv.ui.WelcomeScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { UnbloatTheme { App() } }
    }
}

@Composable
private fun App(vm: AppViewModel = viewModel()) {
    val screen = vm.screen
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    // The clean-up carries on with the phone locked, with a notification showing how far it is:
    // Android 13 and newer ask before an app may show one.
    val ctx = LocalContext.current
    val askNotify = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(screen) {
        if (screen == Screen.Apply && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) askNotify.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
    // Back closes the menu, then goes one page back, never past a running job.
    BackHandler(enabled = drawer.isOpen) { scope.launch { drawer.close() } }
    BackHandler(enabled = !drawer.isOpen && screen != Screen.Welcome) {
        when (screen) {
            Screen.Connect -> vm.go(Screen.Welcome)
            Screen.Choose -> vm.disconnect()
            Screen.Apply -> if (vm.applyFinished) vm.backToChoices()
            Screen.Restore -> if (!vm.restoreBusy) vm.goChoose()
            Screen.Welcome -> Unit
            else -> if (!vm.isBusy) vm.goChoose()
        }
    }
    ModalNavigationDrawer(
        drawerState = drawer,
        gesturesEnabled = drawer.isOpen || !vm.isBusy,
        drawerContent = { Drawer(vm) { scope.launch { drawer.close() } } },
    ) {
        UpdateDialogs(vm)
        // A surface, so text outside the cards also gets the theme's colour.
        Surface(Modifier.fillMaxSize(), color = AppTheme.colors.bg, contentColor = AppTheme.colors.text) {
            Column(Modifier.fillMaxSize().statusBarsPadding()) {
                Header(vm) { scope.launch { drawer.open() } }
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                    // Tablets and TVs: keep lines readable.
                    Box(Modifier.widthIn(max = 720.dp).fillMaxSize()) {
                        when (screen) {
                            Screen.Welcome -> WelcomeScreen(vm)
                            Screen.Connect -> ConnectScreen(vm)
                            Screen.Choose -> ChooseScreen(vm)
                            Screen.Apply -> ApplyScreen(vm)
                            Screen.Restore -> RestoreScreen(vm)
                            Screen.Info -> vm.infoModel?.let { InfoScreen(it) }
                            Screen.Health -> vm.healthModel?.let { HealthScreen(it, vm) }
                            Screen.Privacy -> vm.privacyModel?.let { PrivacyScreen(it) }
                            Screen.Apps -> vm.appsModel?.let { AppsScreen(it) }
                            Screen.Remote -> vm.remoteModel?.let { RemoteScreen(it) }
                            Screen.Install -> vm.installModel?.let { InstallScreen(it) }
                        }
                    }
                }
            }
        }
    }
}

/** Where the clean-up is: Prepare 0 … Apply 3, 4 once it is done. */
private fun stepIndex(vm: AppViewModel): Int = when (vm.screen) {
    Screen.Welcome -> 0
    Screen.Connect -> 1
    Screen.Apply -> if (vm.applyFinished) 4 else 3
    else -> if (vm.applyFinished && vm.steps.isNotEmpty()) 4 else 2
}

@Composable
private fun Header(vm: AppViewModel, openMenu: () -> Unit) {
    val dev = vm.device
    Column(Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp, top = 6.dp, bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = openMenu, enabled = !vm.isBusy) { Icon(Icons.Outlined.Menu, stringResource(R.string.Android_Menu)) }
            Spacer(Modifier.width(4.dp))
            Logo(36.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.App_Name), style = MaterialTheme.typography.titleMedium)
                // On the tools, which TV they are looking at.
                Text(
                    if (vm.screen.isTool && dev != null) dev.displayName else "Android TV · Google TV · Fire TV",
                    color = AppTheme.colors.muted, fontSize = 12.sp, maxLines = 1,
                )
            }
        }
        if (!vm.screen.isTool) Box(Modifier.padding(start = 12.dp)) { StepsBar(stepIndex(vm)) }
    }
}

@Composable
private fun Drawer(vm: AppViewModel, close: () -> Unit) {
    val c = AppTheme.colors
    val dev = vm.device
    val connected = vm.tv != null && dev != null
    val current = stepIndex(vm)
    fun go(action: () -> Unit) { close(); action() }

    ModalDrawerSheet(drawerContainerColor = c.bg, drawerContentColor = c.text) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 16.dp)) {
            Row(Modifier.padding(start = 8.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Logo(32.dp)
                Spacer(Modifier.width(10.dp))
                Text(stringResource(R.string.App_Name), style = MaterialTheme.typography.titleMedium)
            }

            DrawerLabel(stringResource(R.string.Android_Steps))
            val steps = listOf(R.string.Step_Prepare, R.string.Step_Connect, R.string.Step_Choose, R.string.Step_Apply)
            steps.forEachIndexed { i, label ->
                val enabled = when (i) {
                    0, 1 -> true
                    2 -> connected
                    else -> vm.steps.isNotEmpty()
                }
                val selected = !vm.screen.isTool && i == minOf(current, 3)
                DrawerItem(stringResource(label), selected, enabled, icon = {
                    val done = i < current
                    Box(Modifier.size(24.dp).clip(CircleShape).background(if (done) c.okBg else c.idle), contentAlignment = Alignment.Center) {
                        Text(if (done) "✓" else "${i + 1}", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = if (done) c.okFg else c.muted)
                    }
                }) {
                    go {
                        when (i) {
                            0 -> vm.go(Screen.Welcome)
                            1 -> vm.go(Screen.Connect)
                            2 -> vm.goChoose()
                            else -> vm.go(Screen.Apply)
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            DrawerLabel(stringResource(R.string.Android_Tools))
            if (!connected) Muted(stringResource(R.string.Android_ConnectFirst), Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
            TOOLS.forEach { (screen, label, icon) ->
                DrawerItem(stringResource(label), vm.screen == screen, connected, icon = { Icon(icon, null) }) { go { vm.openTool(screen) } }
            }

            if (connected) {
                Spacer(Modifier.height(16.dp))
                Surface(shape = MaterialTheme.shapes.medium, color = c.accentSoft, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp)) {
                        Text(stringResource(R.string.Side_Connected), style = MaterialTheme.typography.labelSmall, color = c.muted)
                        Text(dev!!.displayName, style = MaterialTheme.typography.titleSmall)
                        Text(dev.host, style = MaterialTheme.typography.bodySmall, color = c.muted)
                        TextButton(onClick = { go(vm::disconnect) }, contentPadding = PaddingValues(0.dp)) {
                            Text(stringResource(R.string.Android_Disconnect))
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            val u = vm.update
            if (u != null) {
                DrawerItem(stringResource(R.string.Side_UpdateAvailable, u.version), selected = true, enabled = true,
                    icon = { Icon(Icons.Outlined.SystemUpdate, null) }) { go { vm.updateDialog = AppViewModel.UpdateDialog.Available } }
            }
            Row(Modifier.fillMaxWidth().padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("v" + appVersion(), style = MaterialTheme.typography.bodySmall, color = c.faint, modifier = Modifier.weight(1f))
                if (u == null) TextButton(onClick = { go(vm::checkForUpdates) }) { Text(stringResource(R.string.Side_CheckUpdates)) }
            }
        }
    }
}

private val TOOLS = listOf(
    Triple(Screen.Info, R.string.Side_Info, Icons.Outlined.Info),
    Triple(Screen.Health, R.string.Side_Health, Icons.Outlined.MonitorHeart),
    Triple(Screen.Privacy, R.string.Side_Privacy, Icons.Outlined.PanTool),
    Triple(Screen.Apps, R.string.Side_Apps, Icons.Outlined.Apps),
    Triple(Screen.Remote, R.string.Side_Remote, Icons.Outlined.SettingsRemote),
    Triple(Screen.Install, R.string.Side_Install, Icons.Outlined.InstallMobile),
    Triple(Screen.Restore, R.string.Side_Restore, Icons.AutoMirrored.Outlined.Undo),
)

@Composable
private fun DrawerLabel(text: String) =
    Text(text.uppercase(), style = MaterialTheme.typography.labelSmall, color = AppTheme.colors.muted,
        modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 6.dp))

@Composable
private fun DrawerItem(label: String, selected: Boolean, enabled: Boolean, icon: @Composable () -> Unit, onClick: () -> Unit) {
    val c = AppTheme.colors
    NavigationDrawerItem(
        label = { Text(label, color = if (enabled) c.text else c.faint) },
        icon = { Box(Modifier.alpha(if (enabled) 1f else 0.4f)) { icon() } },
        selected = selected,
        onClick = { if (enabled) onClick() },
        colors = NavigationDrawerItemDefaults.colors(
            selectedContainerColor = c.accentSoft, unselectedContainerColor = Color.Transparent,
            selectedIconColor = c.accentFg, unselectedIconColor = c.muted,
        ),
        modifier = Modifier.height(48.dp),
    )
}

@Composable
private fun appVersion(): String {
    val ctx = LocalContext.current
    return remember { runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull().orEmpty() }
}

@Composable
private fun UpdateDialogs(vm: AppViewModel) {
    val ctx = LocalContext.current
    val close = { vm.updateDialog = AppViewModel.UpdateDialog.None }
    vm.whatsNew?.let { (version, notes) ->
        val close = { vm.whatsNew = null }
        AlertDialog(
            onDismissRequest = close,
            title = { Text(stringResource(R.string.Android_WhatsNew, version)) },
            text = { Text(notes, Modifier.verticalScroll(rememberScrollState()), style = MaterialTheme.typography.bodySmall) },
            confirmButton = { TextButton(onClick = close) { Text(stringResource(R.string.Update_Ok)) } },
        )
    }
    when (vm.updateDialog) {
        AppViewModel.UpdateDialog.None -> Unit
        AppViewModel.UpdateDialog.UpToDate -> AlertDialog(
            onDismissRequest = close,
            title = { Text(stringResource(R.string.Update_UpToDateTitle)) },
            text = { Text(stringResource(R.string.Update_UpToDate, appVersion())) },
            confirmButton = { TextButton(onClick = close) { Text(stringResource(R.string.Update_Ok)) } },
        )
        AppViewModel.UpdateDialog.Failed -> AlertDialog(
            onDismissRequest = close,
            title = { Text(stringResource(R.string.Update_FailedTitle)) },
            text = { Text(stringResource(R.string.Update_FailedText)) },
            confirmButton = { TextButton(onClick = close) { Text(stringResource(R.string.Update_Ok)) } },
        )
        AppViewModel.UpdateDialog.Available -> {
            val u = vm.update ?: return
            val c = AppTheme.colors
            val working = vm.updateProgress != null
            AlertDialog(
                onDismissRequest = { if (!working) close() },
                title = { Text(stringResource(R.string.Update_Title)) },
                text = {
                    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(stringResource(R.string.Android_UpdateText, u.version, appVersion()))
                        Text(stringResource(R.string.Update_Notes), style = MaterialTheme.typography.labelSmall, color = c.muted)
                        Text(u.notes.ifEmpty { stringResource(R.string.Update_NoNotes) }, style = MaterialTheme.typography.bodySmall)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            key(vm.autoCheckVersion) {
                                Switch(checked = vm.autoCheckUpdates, onCheckedChange = { vm.autoCheckUpdates = it })
                            }
                            Spacer(Modifier.width(10.dp))
                            Text(stringResource(R.string.Update_Auto), style = MaterialTheme.typography.bodySmall)
                        }
                        vm.updateProgress?.let { LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth()) }
                        val status = Updater.installError ?: vm.updateStatus
                        if (status.isNotEmpty()) Text(status, style = MaterialTheme.typography.bodySmall,
                            color = if (Updater.installError != null) c.errFg else c.muted)
                    }
                },
                confirmButton = {
                    Button(onClick = { vm.installUpdate(ctx) }, enabled = !working) { Text(stringResource(R.string.Android_UpdateInstall)) }
                },
                dismissButton = {
                    Row {
                        TextButton(onClick = vm::skipUpdate, enabled = !working) { Text(stringResource(R.string.Update_Skip)) }
                        TextButton(onClick = close, enabled = !working) { Text(stringResource(R.string.Update_Later)) }
                    }
                },
            )
        }
    }
}
