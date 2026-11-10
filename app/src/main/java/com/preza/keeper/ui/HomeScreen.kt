package com.preza.keeper.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.preza.keeper.data.Library
import com.preza.keeper.data.Stats
import com.preza.keeper.data.Verdict
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** Weak shots in groups of similar photos that Tinder mode can still show. */
fun weakShotsWaiting(state: UiState): Int = state.library?.sessions?.sumOf { s ->
    s.scenes.filter { it.size >= 2 }.sumOf { sc ->
        sc.decisions.count { it.verdict == Verdict.DELETE && it.photo.id !in state.trashed && state.overrides[it.photo.id] == null }
    }
} ?: 0

/** Colors for the four stat tiles, light and dark. */
private class Tone(val bg: Color, val fg: Color, val sub: Color)

private fun tones(): List<Tone> = if (K.p.dark) listOf(
    Tone(K.Hero, K.OnHero, K.OnHero.copy(alpha = 0.8f)),
    Tone(Color(0xFF5A3A22), Color(0xFFFFDCC2), Color(0xFFE8C2A6)),
    Tone(Color(0xFF5C2B3B), Color(0xFFFFD9E3), Color(0xFFE8BCC9)),
    Tone(Color(0xFF1F3A57), Color(0xFFD3E4FF), Color(0xFFB4C8E6))
) else listOf(
    Tone(K.Hero, K.OnHero, Color(0xFF3B4A2A)),
    Tone(Color(0xFFF5DFCB), Color(0xFF2B160A), Color(0xFF5C4130)),
    Tone(Color(0xFFF3D9E2), Color(0xFF3A0F1F), Color(0xFF6E3A4C)),
    Tone(Color(0xFFD6E3F7), Color(0xFF0F2236), Color(0xFF3A4F66))
)

private val burstColor get() = if (K.p.dark) Color(0xFFE0A877) else Color(0xFFC98A55)

@Composable
fun HomeScreen(state: UiState, vm: AppViewModel) {
    val lib = state.library
    val folder = state.camFolder.trimEnd('/')
    LazyColumn(
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (lib == null) item {
            Row(verticalAlignment = Alignment.Top) {
                Box(Modifier.weight(1f)) { Title("Keeper", folder) }
                IconButton(onClick = { vm.open(Screen.Settings) }, modifier = Modifier.padding(top = 8.dp)) {
                    Icon(Icons.Filled.Settings, contentDescription = "Settings", tint = K.Soft)
                }
            }
        }

        if (state.scanning) item {
            Panel {
                Text(state.stage, color = K.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                if (state.total > 0 && state.stage == "Analyzing photos") {
                    LinearProgressIndicator(
                        progress = { state.done.toFloat() / state.total.coerceAtLeast(1) },
                        modifier = Modifier.fillMaxWidth(), color = K.Accent, trackColor = K.Line
                    )
                    Text(
                        "${Fmt.n(state.done)} of ${Fmt.n(state.total)}. The first scan takes a few minutes, later ones only look at new photos.",
                        color = K.Muted, fontSize = 13.sp
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = K.Accent, trackColor = K.Line)
                }
            }
        }

        state.error?.let { err -> item { Note("Something went wrong: $err") } }

        if (lib != null) {
            val setDels = lib.abandoned.flatMap { it.decisions }.filter { !vm.isKept(it, state.overrides) }
            val burstDels = lib.bursts.flatMap { it.second.decisions }.filter { !vm.isKept(it, state.overrides) }
            val setBytes = setDels.sumOf { it.photo.size }
            val burstBytes = burstDels.sumOf { it.photo.size }
            val libBytes = lib.photoById.values.sumOf { it.size }
            val weak = weakShotsWaiting(state)

            item { Hero(state, vm, lib, setBytes + burstBytes, setDels.size + burstDels.size) }

            item {
                Column(Modifier.padding(start = 4.dp, top = 8.dp)) {
                    Text("Overview", color = K.Text, fontSize = 22.sp, fontWeight = FontWeight.Medium)
                    Text("$folder · ${Fmt.plural(lib.total, "photo")} · ${Fmt.gb(libBytes)}", color = K.Muted, fontSize = 13.sp)
                }
            }

            item { StorageCard(setBytes, burstBytes, libBytes) }

            item {
                val t = tones()
                val firstDue = lib.waiting.minOfOrNull { (state.weeks * 7 - Fmt.daysAgo(it.end)).coerceAtLeast(0) }
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Tile("Abandoned sets", Fmt.n(lib.abandoned.size), "${Fmt.n(setDels.size)} to delete · ${Fmt.gb(setBytes)}", t[0]) { vm.tab(Screen.Sets) }
                        Tile("Bursts", Fmt.n(lib.bursts.size), "${Fmt.plural(burstDels.size, "extra frame")} · ${Fmt.gb(burstBytes)}", t[1]) { vm.tab(Screen.Bursts) }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Tile("Tinder mode", Fmt.n(minOf(20, weak)), "per round · ${Fmt.n(weak)} weak shots", t[2]) { vm.tab(Screen.Quick) }
                        Tile(
                            "Waiting", Fmt.n(lib.waiting.size),
                            if (firstDue == null) "no recent outings" else "outings · first due in ${Fmt.plural(firstDue.toInt(), "day")}",
                            t[3]
                        ) { vm.tab(Screen.Sets) }
                    }
                }
            }

            item { FreedCard(state.stats) }

            item {
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).border(1.dp, K.Line, RoundedCornerShape(20.dp)).padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Your edits", color = K.Text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        Text(
                            listOf("${Fmt.n(lib.editsMatched)} matched", state.learned, state.loosened.lines().firstOrNull().orEmpty()).filter { it.isNotBlank() }.joinToString(" · "),
                            color = K.Muted, fontSize = 12.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Hero(state: UiState, vm: AppViewModel, lib: Library, bytes: Long, count: Int) {
    // Photo: a random one from the whole library, new on each app open. Tapping it opens its outing.
    val hero = state.hero
    val photo = hero?.decision?.photo
    Box(
        Modifier.fillMaxWidth().height(400.dp).clip(RoundedCornerShape(28.dp)).background(Color(0xFF2A261F))
            .then(if (hero != null) Modifier.clickable { vm.open(Screen.SetDetail(hero.session.key)) } else Modifier)
    ) {
        if (photo != null) LargePhoto(photo, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0f to Color(0x66000000), 0.3f to Color(0x00000000), 0.5f to Color(0x22000000), 1f to Color(0xEB15130F)
                )
            )
        )
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Keeper", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                if (hero != null) Text(
                    listOfNotNull(Fmt.day(hero.session.start, hero.session.offset), hero.session.place?.let { it.area ?: it.city })
                        .joinToString(" · ") + " · tap to open",
                    color = Color.White.copy(alpha = 0.85f), fontSize = 12.sp
                )
            }
            IconButton(onClick = { vm.open(Screen.Settings) }) {
                Icon(Icons.Filled.Settings, contentDescription = "Settings", tint = Color.White)
            }
        }
        Column(Modifier.align(Alignment.BottomStart).padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                "Keep the shots that matter",
                modifier = Modifier.clip(RoundedCornerShape(14.dp)).background(Color(0x2EFFFFFF)).padding(horizontal = 12.dp, vertical = 4.dp),
                color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium
            )
            if (count > 0) {
                Text("Free ${Fmt.gb(bytes)}", color = Color.White, fontSize = 36.sp, fontWeight = FontWeight.Bold, lineHeight = 40.sp)
                Text(
                    "${Fmt.plural(count, "photo")} you won't miss. Keeper already picked the keepers, you just confirm.",
                    color = Color.White.copy(alpha = 0.85f), fontSize = 14.sp, lineHeight = 19.sp
                )
            } else {
                Text("All clean", color = Color.White, fontSize = 36.sp, fontWeight = FontWeight.Bold)
                Text("Nothing to delete right now. New outings show up after ${state.weeks} weeks without edits.", color = Color.White.copy(alpha = 0.85f), fontSize = 14.sp)
            }
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                HeroButton("Show me", filled = true) { vm.tab(if (lib.abandoned.isNotEmpty() || lib.bursts.isEmpty()) Screen.Sets else Screen.Bursts) }
                HeroButton("Play Tinder mode", filled = false) { if (hero != null) vm.startQuick(hero) else vm.tab(Screen.Quick) }
            }
        }
    }
}

@Composable
private fun HeroButton(text: String, filled: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(24.dp)
    val base = Modifier.height(48.dp).clip(shape)
    val m = if (filled) base.background(K.Bright) else base.border(1.dp, Color(0x80FFFFFF), shape)
    Box(m.clickable(onClick = onClick).padding(horizontal = 20.dp), contentAlignment = Alignment.Center) {
        Text(text, color = if (filled) K.OnBright else Color.White, fontSize = 15.sp, fontWeight = if (filled) FontWeight.Bold else FontWeight.Medium)
    }
}

@Composable
private fun StorageCard(setBytes: Long, burstBytes: Long, libBytes: Long) {
    Panel {
        Row(verticalAlignment = Alignment.Bottom) {
            Text("Ready to free", color = K.Muted, fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            Text(Fmt.gb(setBytes + burstBytes), color = K.Text, fontSize = 28.sp, fontWeight = FontWeight.Medium)
            Text(" of ${Fmt.gb(libBytes)}", color = K.Muted, fontSize = 14.sp, modifier = Modifier.padding(bottom = 4.dp))
        }
        val total = libBytes.coerceAtLeast(1L).toFloat()
        val fs = if (setBytes > 0) (setBytes / total).coerceIn(0.02f, 0.96f) else 0f
        val fb = if (burstBytes > 0) (burstBytes / total).coerceIn(0.02f, 0.96f) else 0f
        Row(Modifier.fillMaxWidth().height(14.dp).clip(RoundedCornerShape(7.dp)), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            if (fs > 0f) Box(Modifier.weight(fs).fillMaxHeight().background(K.Accent))
            if (fb > 0f) Box(Modifier.weight(fb).fillMaxHeight().background(burstColor))
            Box(Modifier.weight((1f - fs - fb).coerceAtLeast(0.02f)).fillMaxHeight().background(K.Line))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Legend(K.Accent, "Sets", Fmt.gb(setBytes))
            Legend(burstColor, "Bursts", Fmt.gb(burstBytes))
            Legend(K.Line, "Keeping", Fmt.gb((libBytes - setBytes - burstBytes).coerceAtLeast(0)))
        }
    }
}

@Composable
private fun Legend(color: Color, label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(10.dp).clip(RoundedCornerShape(3.dp)).background(color))
        Text("$label ", color = K.Muted, fontSize = 12.sp)
        Text(value, color = K.Text, fontSize = 12.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(start = 0.dp))
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.Tile(label: String, value: String, sub: String, t: Tone, onClick: () -> Unit) {
    Column(
        Modifier.weight(1f).clip(RoundedCornerShape(20.dp)).background(t.bg).clickable(onClick = onClick).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(label, color = t.sub, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        Text(value, color = t.fg, fontSize = 28.sp, fontWeight = FontWeight.Medium)
        Text(sub, color = t.sub, fontSize = 12.sp, lineHeight = 16.sp, minLines = 2)
    }
}

@Composable
private fun FreedCard(st: Stats.Snapshot?) {
    Panel {
        Row(verticalAlignment = Alignment.Bottom) {
            Text("Freed with Keeper", color = K.Muted, fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            Text(
                if (st == null || st.total.count == 0) "Nothing yet" else "${Fmt.gb(st.total.bytes)} · ${Fmt.n(st.total.count)}",
                color = K.Text, fontSize = 20.sp, fontWeight = FontWeight.Medium
            )
        }
        // Last 8 weeks, oldest first.
        val today = LocalDate.now()
        val keys = (7 downTo 0).map { Stats.weekKey(today.minusWeeks(it.toLong())) }
        val vals = keys.map { st?.weeks?.get(it)?.bytes ?: 0L }
        val max = vals.maxOrNull()?.coerceAtLeast(1L) ?: 1L
        val lf = DateTimeFormatter.ofPattern("MMM d")
        Row(Modifier.fillMaxWidth().height(84.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Bottom) {
            keys.forEachIndexed { i, k ->
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.Bottom, horizontalAlignment = Alignment.CenterHorizontally) {
                    val h = if (vals[i] > 0) (4 + 60f * vals[i] / max).dp else 3.dp
                    Box(Modifier.fillMaxWidth().height(h).clip(RoundedCornerShape(5.dp)).background(if (vals[i] > 0) K.Accent else K.Line))
                    Spacer(Modifier.height(4.dp))
                    Text(
                        if (i == 0 || i == keys.lastIndex) LocalDate.parse(k).format(lf) else "",
                        color = K.Muted, fontSize = 10.sp, maxLines = 1, softWrap = false
                    )
                }
            }
        }
        val parts = st?.bySource?.filter { it.value.count > 0 }?.entries?.sortedByDescending { it.value.bytes }.orEmpty()
        Text(
            listOfNotNull(
                "Per week",
                st?.since?.let { "since " + LocalDate.parse(it).format(DateTimeFormatter.ofPattern("MMM d, yyyy")) },
                parts.takeIf { it.isNotEmpty() }?.joinToString(" · ") { "${it.key} ${Fmt.n(it.value.count)}" }
            ).joinToString(" · "),
            color = K.Muted, fontSize = 12.sp
        )
    }
}
