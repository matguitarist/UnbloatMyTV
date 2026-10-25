package io.github.matguitarist.unbloatmytv.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.matguitarist.unbloatmytv.R

/** The app's logo: a TV with a tick, as on the desktop apps. */
@Composable
fun Logo(size: Dp = 44.dp) {
    Box(
        Modifier.size(size).clip(RoundedCornerShape(size * 0.22f)).background(Color(0xFF2459D6)),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(size * 0.6f)) {
            // Drawn on the desktop icon's 24-unit grid, content from x 3 to 21 and y 5 to 20.
            val s = this.size.width / 18f
            fun o(x: Float, y: Float) = Offset((x - 3f) * s, (y - 3.5f) * s)
            val stroke = Stroke(width = 1.7f * s, cap = StrokeCap.Round, join = StrokeJoin.Round)
            val p = Path().apply {
                moveTo(o(3f, 5f).x, o(3f, 5f).y); lineTo(o(21f, 5f).x, o(21f, 5f).y)
                lineTo(o(21f, 16f).x, o(21f, 16f).y); lineTo(o(3f, 16f).x, o(3f, 16f).y); close()
                moveTo(o(8f, 20f).x, o(8f, 20f).y); lineTo(o(16f, 20f).x, o(16f, 20f).y)
                moveTo(o(12f, 16f).x, o(12f, 16f).y); lineTo(o(12f, 20f).x, o(12f, 20f).y)
                moveTo(o(9f, 9.5f).x, o(9f, 9.5f).y); lineTo(o(11f, 11.5f).x, o(11f, 11.5f).y); lineTo(o(15f, 7.5f).x, o(15f, 7.5f).y)
            }
            drawPath(p, Color.White, style = stroke)
        }
    }
}

@Composable
fun AppCard(
    modifier: Modifier = Modifier,
    padding: PaddingValues = PaddingValues(18.dp),
    color: Color = AppTheme.colors.card,
    border: Color = AppTheme.colors.cardBorder,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium, color = color, border = BorderStroke(1.dp, border)) {
        Column(Modifier.padding(padding), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}

enum class PillKind { Ok, Info, Warn, Count }

@Composable
fun Pill(text: String, kind: PillKind = PillKind.Ok) {
    val c = AppTheme.colors
    val (bg, fg) = when (kind) {
        PillKind.Ok -> c.okBg to c.okFg
        PillKind.Info -> c.accentSoft to c.accentFg
        PillKind.Warn -> c.warnBg to c.warnFg
        PillKind.Count -> c.idle to c.muted
    }
    Text(
        text, color = fg, style = MaterialTheme.typography.labelSmall,
        modifier = Modifier.clip(CircleShape).background(bg).padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

/** A coloured note: warnings, errors and good news. */
@Composable
fun Banner(text: String, kind: PillKind = PillKind.Info, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit = {}) {
    val c = AppTheme.colors
    val (bg, fg) = when (kind) {
        PillKind.Ok -> c.okBg to c.okFg
        PillKind.Warn -> c.warnBg to c.warnFg
        PillKind.Info, PillKind.Count -> c.accentSoft to c.accentFg
    }
    Surface(modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium, color = bg) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(text, color = fg, style = MaterialTheme.typography.bodyMedium)
            content()
        }
    }
}

@Composable
fun ErrorBanner(text: String) {
    val c = AppTheme.colors
    Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium, color = c.errBg) {
        Text(text, color = c.errFg, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(14.dp))
    }
}

@Composable
fun Muted(text: String, modifier: Modifier = Modifier) =
    Text(text, color = AppTheme.colors.muted, style = MaterialTheme.typography.bodyMedium, modifier = modifier)

@Composable
fun Mono(text: String) =
    Text(text, color = AppTheme.colors.faint, fontFamily = FontFamily.Monospace, fontSize = 11.sp)

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) =
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = modifier)

/** Prepare · Connect · Choose · Apply, like the desktop sidebar, as a row. */
@Composable
fun StepsBar(current: Int) {
    val c = AppTheme.colors
    val labels = listOf(R.string.Step_Prepare, R.string.Step_Connect, R.string.Step_Choose, R.string.Step_Apply)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        labels.forEachIndexed { i, id ->
            val done = i < current
            val active = i == current
            Box(
                Modifier.size(22.dp).clip(CircleShape).background(
                    when { done -> c.okBg; active -> c.accent; else -> c.idle }
                ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (done) "✓" else "${i + 1}", fontSize = 11.sp, fontWeight = FontWeight.Bold,
                    color = when { done -> c.okFg; active -> Color.White; else -> c.muted },
                )
            }
            Spacer(Modifier.width(6.dp))
            Text(
                stringResource(id), fontSize = 12.sp,
                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                color = if (active) c.text else c.muted, maxLines = 1,
            )
            if (i < labels.lastIndex) {
                Box(Modifier.weight(1f).padding(horizontal = 6.dp).height(1.dp).background(c.divider))
            }
        }
    }
}

/** A scrolling page with the usual gutter and spacing. */
@Composable
fun PageColumn(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        content = content,
    )
}
