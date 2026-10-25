package io.github.matguitarist.unbloatmytv.ui

import androidx.compose.foundation.background
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
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.matguitarist.unbloatmytv.R

@Composable
fun ApplyScreen(vm: AppViewModel) {
    // The phone must not sleep in the middle: the TV is being changed over the network.
    val view = LocalView.current
    DisposableEffect(vm.applyFinished) {
        view.keepScreenOn = !vm.applyFinished
        onDispose { view.keepScreenOn = false }
    }
    val finished = vm.applyFinished
    val problems = vm.problemCount

    PageColumn {
        Text(
            stringResource(when { !finished -> R.string.Apply_Working; problems == 0 -> R.string.Apply_DoneTitle; else -> R.string.Apply_DoneWithProblems }),
            style = MaterialTheme.typography.headlineMedium,
        )
        Muted(
            when { !finished -> stringResource(R.string.Apply_WorkingSub) + " " + stringResource(R.string.Android_KeepOpen)
                problems == 0 -> stringResource(R.string.Apply_DoneSub)
                else -> stringResource(R.string.Apply_ProblemsSub, problems) },
        )
        if (!finished) LinearProgressIndicator(progress = { vm.progress }, modifier = Modifier.fillMaxWidth())

        if (finished) Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Stat(vm.appsOff.toString(), stringResource(R.string.Apply_StatApps), Modifier.weight(1f))
            Stat(vm.settingsChanged.toString(), stringResource(R.string.Apply_StatSettings), Modifier.weight(1f))
            Stat(
                if (problems == 0) "OK" else problems.toString(),
                stringResource(when { problems > 0 -> R.string.Apply_StatProblems; vm.appliedWithReboot -> R.string.Apply_StatChecks; else -> R.string.Apply_StatChecksNoReboot }),
                Modifier.weight(1f), warn = problems > 0,
            )
        }

        AppCard {
            SectionTitle(stringResource(R.string.Apply_Details))
            vm.steps.forEachIndexed { i, s ->
                if (i > 0) HorizontalDivider(color = AppTheme.colors.divider)
                StepRow(s)
            }
        }

        if (finished) {
            AppCard {
                SectionTitle(stringResource(R.string.Apply_LastStepTitle))
                Muted(stringResource(R.string.Apply_LastStep))
                if (!vm.debugOff) Button(onClick = vm::turnOffDebugging, enabled = !vm.turningOffDebug) {
                    if (vm.turningOffDebug) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                    else Text(stringResource(R.string.Apply_DebugOffButton))
                }
                if (vm.debugStatus.isNotEmpty())
                    Text(vm.debugStatus, color = if (vm.debugOff) AppTheme.colors.okFg else AppTheme.colors.warnFg, style = MaterialTheme.typography.bodyMedium)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!vm.debugOff) OutlinedButton(onClick = vm::backToChoices) { Text(stringResource(R.string.Apply_BackToChoices)) }
                TextButton(onClick = vm::disconnect) { Text(stringResource(R.string.Apply_StartOver)) }
            }
        }
    }
}

@Composable
private fun Stat(value: String, label: String, modifier: Modifier, warn: Boolean = false) {
    AppCard(modifier, padding = androidx.compose.foundation.layout.PaddingValues(14.dp)) {
        Text(value, fontSize = 26.sp, fontWeight = FontWeight.Bold, color = if (warn) AppTheme.colors.warnFg else AppTheme.colors.text)
        Muted(label)
    }
}

@Composable
private fun StepRow(s: StepItem) {
    val c = AppTheme.colors
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.Top) {
        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
            when (s.state) {
                StepState.Running -> CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                StepState.Pending -> Box(Modifier.size(10.dp).clip(CircleShape).background(c.idle))
                else -> {
                    val (bg, fg, mark) = when (s.state) {
                        StepState.Done -> Triple(c.okBg, c.okFg, "✓")
                        StepState.Failed -> Triple(c.errBg, c.errFg, "✕")
                        else -> Triple(c.idle, c.muted, "–")
                    }
                    Box(Modifier.size(22.dp).clip(CircleShape).background(bg), contentAlignment = Alignment.Center) {
                        Text(mark, color = fg, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(s.text, style = MaterialTheme.typography.bodyMedium,
                color = if (s.state == StepState.Pending) c.muted else c.text)
            if (s.detail.isNotEmpty())
                Text(s.detail, style = MaterialTheme.typography.bodySmall,
                    color = if (s.state == StepState.Failed) c.errFg else c.muted)
        }
    }
}

@Composable
fun RestoreScreen(vm: AppViewModel) {
    PageColumn {
        Text(stringResource(R.string.Restore_Title), style = MaterialTheme.typography.headlineMedium)
        Muted(stringResource(R.string.Restore_Lead))
        if (vm.restoreStatus.isNotEmpty()) {
            if (vm.restoreError) ErrorBanner(vm.restoreStatus) else Banner(vm.restoreStatus, PillKind.Ok)
        }
        AppCard {
            SectionTitle(stringResource(R.string.Restore_RunsTitle))
            if (vm.backups.isEmpty()) Muted(stringResource(R.string.Restore_None))
            vm.backups.forEachIndexed { i, b ->
                if (i > 0) HorizontalDivider(color = AppTheme.colors.divider)
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(b.createdText, style = MaterialTheme.typography.titleMedium)
                        val changed = b.settings.size + if (b.wifiWasOn == true) 1 else 0
                        Muted(
                            if (b.revokedPermissions.isEmpty()) stringResource(R.string.Restore_Summary, b.disabledPackages.size, changed)
                            else stringResource(R.string.Restore_SummaryAccess, b.disabledPackages.size, changed, b.revokedPermissions.size)
                        )
                    }
                    OutlinedButton(onClick = { vm.restore(b) }, enabled = !vm.restoreBusy) { Text(stringResource(R.string.Android_Restore)) }
                }
            }
        }
        AppCard {
            SectionTitle(stringResource(R.string.Restore_AllTitle))
            Muted(stringResource(R.string.Restore_AllText))
            OutlinedButton(onClick = vm::enableAll, enabled = !vm.restoreBusy) { Text(stringResource(R.string.Restore_AllButton)) }
        }
        if (vm.restoreBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
        Spacer(Modifier.height(4.dp))
        TextButton(onClick = vm::backToChoices, enabled = !vm.restoreBusy) { Text(stringResource(R.string.Common_Back)) }
    }
}
