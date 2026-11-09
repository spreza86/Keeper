package com.preza.keeper.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Material palettes, light and dark. Screens read colors through K, which follows the active one. */
data class Palette(
    val bg: Color, val surface: Color, val surface2: Color, val line: Color,
    val text: Color, val soft: Color, val muted: Color,
    val accent: Color, val onAccent: Color, val hero: Color, val onHero: Color,
    val danger: Color, val onDanger: Color, val blue: Color, val green: Color, val purple: Color, val grey: Color,
    val fade: Color, val inverseAccent: Color, val outline: Color, val dark: Boolean
)

val LightPalette = Palette(
    bg = Color(0xFFFBF8F3), surface = Color(0xFFEFEADF), surface2 = Color(0xFFE6DFCF), line = Color(0xFFD6CFBE),
    text = Color(0xFF1D1B16), soft = Color(0xFF4D4635), muted = Color(0xFF5F5848),
    accent = Color(0xFF4C662B), onAccent = Color(0xFFFFFFFF), hero = Color(0xFFDCE7C8), onHero = Color(0xFF1A2410),
    danger = Color(0xFFBA1A1A), onDanger = Color(0xFFFFFFFF), blue = Color(0xFF4F739E), green = Color(0xFF4C662B),
    purple = Color(0xFF7A5BA6), grey = Color(0xFFA39E92),
    fade = Color(0x99FBF8F3), inverseAccent = Color(0xFFB1D18A), outline = Color(0xFF7E7667), dark = false
)

val DarkPalette = Palette(
    bg = Color(0xFF15130F), surface = Color(0xFF25221C), surface2 = Color(0xFF2F2B24), line = Color(0xFF3D382F),
    text = Color(0xFFE8E2D4), soft = Color(0xFFCFC7B6), muted = Color(0xFFB3AC9C),
    accent = Color(0xFFB1D18A), onAccent = Color(0xFF1F3701), hero = Color(0xFF354E16), onHero = Color(0xFFCDEDA3),
    danger = Color(0xFFFFB4AB), onDanger = Color(0xFF690005), blue = Color(0xFFA9C7EE), green = Color(0xFFB1D18A),
    purple = Color(0xFFCDB4F0), grey = Color(0xFF6B665C),
    fade = Color(0x9915130F), inverseAccent = Color(0xFF4C662B), outline = Color(0xFF958F80), dark = true
)

object K {
    var p: Palette by mutableStateOf(LightPalette)
    val Bg get() = p.bg
    val Surface get() = p.surface
    val Surface2 get() = p.surface2
    val Line get() = p.line
    val Text get() = p.text
    val Soft get() = p.soft
    val Muted get() = p.muted
    val Accent get() = p.accent
    val OnAccent get() = p.onAccent
    val Hero get() = p.hero
    val OnHero get() = p.onHero
    val Danger get() = p.danger
    val OnDanger get() = p.onDanger
    val Blue get() = p.blue
    val Green get() = p.green
    val Purple get() = p.purple
    val Grey get() = p.grey
    val Fade get() = p.fade
    val InverseAccent get() = p.inverseAccent
    val Outline get() = p.outline
}

/** mode: "system", "light" or "dark" */
@Composable
fun KeeperTheme(mode: String, content: @Composable () -> Unit) {
    val dark = when (mode) { "dark" -> true; "light" -> false; else -> isSystemInDarkTheme() }
    val pal = if (dark) DarkPalette else LightPalette
    K.p = pal
    MaterialTheme(
        colorScheme = (if (dark) darkColorScheme() else lightColorScheme()).copy(
            primary = pal.accent, onPrimary = pal.onAccent,
            primaryContainer = pal.hero, onPrimaryContainer = pal.onHero,
            secondary = pal.blue,
            background = pal.bg, onBackground = pal.text,
            surface = pal.surface, onSurface = pal.text,
            surfaceVariant = pal.surface2, onSurfaceVariant = pal.soft,
            outline = pal.outline,
            error = pal.danger, onError = pal.onDanger
        ),
        content = content
    )
}

object Fmt {
    private val loc = Locale.getDefault()
    private val dayF = DateTimeFormatter.ofPattern("EEE MMM d", loc)
    private val dayYearF = DateTimeFormatter.ofPattern("EEE MMM d, yyyy", loc)
    private val timeF = DateTimeFormatter.ofPattern("h:mm a", loc)
    private val shortF = DateTimeFormatter.ofPattern("MMM d", loc)
    private val shortYearF = DateTimeFormatter.ofPattern("MMM d, yyyy", loc)

    fun day(t: Instant, off: ZoneOffset): String {
        val d = t.atOffset(off)
        val thisYear = Instant.now().atOffset(off).year == d.year
        return d.format(if (thisYear) dayF else dayYearF)
    }

    fun time(t: Instant, off: ZoneOffset): String = t.atOffset(off).format(timeF)

    fun range(a: Instant, b: Instant, off: ZoneOffset): String {
        val x = time(a, off)
        val y = time(b, off)
        return if (x == y) x else "$x - $y"
    }

    fun dates(a: Instant, b: Instant, off: ZoneOffset): String {
        val x = a.atOffset(off)
        val y = b.atOffset(off)
        return when {
            x.toLocalDate() == y.toLocalDate() -> x.format(shortYearF)
            x.year == y.year -> "${x.format(shortF)} - ${y.format(shortYearF)}"
            else -> "${x.format(shortYearF)} - ${y.format(shortYearF)}"
        }
    }

    fun gb(bytes: Long): String = if (bytes >= 1_000_000_000L) "%.1f GB".format(bytes / 1e9) else "%.0f MB".format(bytes / 1e6)

    fun daysAgo(t: Instant): Long = Duration.between(t, Instant.now()).toDays()

    fun n(v: Int): String = "%,d".format(v)

    fun plural(v: Int, one: String, many: String = one + "s") = "${n(v)} ${if (v == 1) one else many}"
}
