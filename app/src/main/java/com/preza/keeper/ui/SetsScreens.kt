package com.preza.keeper.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.preza.keeper.data.Decision
import com.preza.keeper.data.Session
import com.preza.keeper.data.Verdict

@Composable
fun SetsScreen(state: UiState, vm: AppViewModel) {
    val lib = state.library
    LazyColumn(
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Title(
                "Abandoned sets",
                "Outings with no edits, older than ${state.weeks} weeks. In each one Keeper picks the photos worth keeping."
            )
        }
        if (lib == null) {
            item { Note(if (state.scanning) "Still scanning..." else "No scan yet.") }
            return@LazyColumn
        }
        if (lib.abandoned.isEmpty()) item { Note("Nothing to clean up. Nice.") }
        items(lib.abandoned, key = { it.key }) { s -> SetCard(s, state, vm) }
        if (lib.waiting.isNotEmpty()) {
            item { Label("Waiting, no edits yet") }
            items(lib.waiting, key = { "w" + it.key }) { s ->
                val due = (state.weeks * 7 - Fmt.daysAgo(s.end)).coerceAtLeast(0)
                ListRow(
                    thumbId = s.photos.first().id,
                    title = "${Fmt.day(s.start, s.offset)} · ${Fmt.plural(s.photos.size, "photo")}",
                    sub = (s.place?.let { "${it.city} · " } ?: "") + "Suggested in ${Fmt.plural(due.toInt(), "day")} if still unedited",
                    onClick = { vm.open(Screen.SetDetail(s.key)) }
                )
            }
        }
    }
}

@Composable
private fun SetCard(s: Session, state: UiState, vm: AppViewModel) {
    val keep = s.decisions.count { vm.isKept(it, state.overrides) }
    Panel {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            s.photos.take(4).forEach { Thumb(it.id, Modifier.weight(1f).aspectRatio(1f).rounded()) }
        }
        Text("${Fmt.day(s.start, s.offset)} · ${Fmt.range(s.start, s.end, s.offset)}", color = K.Text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        Text(
            listOfNotNull(
                s.place?.let { it.city },
                Fmt.plural(s.photos.size, "photo"),
                Fmt.gb(s.photos.sumOf { it.size }),
                "${Fmt.n(keep)} worth keeping",
                "${Fmt.daysAgo(s.end)} days old"
            ).joinToString(" · "),
            color = K.Muted, fontSize = 13.sp
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Secondary("Keep set", { vm.keepSession(s) })
            Primary("Review", { vm.open(Screen.SetDetail(s.key)) })
        }
    }
}

@Composable
fun SetDetailScreen(state: UiState, vm: AppViewModel, key: String) {
    val s = state.library?.sessions?.firstOrNull { it.key == key }
    if (s == null) {
        Column(Modifier.padding(20.dp)) {
            Title("Set", "This set is gone, it was kept or cleaned up.", onBack = { vm.back() })
        }
        return
    }
    val decisions = s.decisions
    val kept = decisions.count { vm.isKept(it, state.overrides) }
    val deleting = decisions.filter { !vm.isKept(it, state.overrides) }
    val groups = s.scenes.filter { it.size > 1 }
    val singles = s.scenes.filter { it.size == 1 }.flatMap { it.decisions }

    Column(Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Title(
                        Fmt.day(s.start, s.offset),
                        listOfNotNull(Fmt.range(s.start, s.end, s.offset), s.place?.let { p -> listOfNotNull(p.area, p.city).joinToString(", ") },
                            Fmt.plural(s.photos.size, "photo")).joinToString(" · "),
                        onBack = { vm.back() }, backLabel = if (state.stack.dropLast(1).lastOrNull() == Screen.Sets) "Sets" else "Back"
                    )
                    Column(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(K.Hero).padding(horizontal = 16.dp, vertical = 12.dp)
                    ) {
                        Text("Keeping ${Fmt.n(kept)} of ${Fmt.n(decisions.size)}", color = K.OnHero, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            "${Fmt.n(deleting.size)} to delete · frees ${Fmt.gb(deleting.sumOf { it.photo.size })} · ${Fmt.plural(groups.size, "scene")}",
                            color = K.OnHero, fontSize = 12.sp
                        )
                    }
                    Text("Tap any photo to switch between keep and delete.", color = K.Muted, fontSize = 12.sp)
                }
            }
            itemsIndexed(groups, key = { _, sc -> sc.key }) { idx, sc ->
                val keepIn = sc.decisions.count { vm.isKept(it, state.overrides) }
                val anyDeleted = keepIn < sc.size
                SceneBlock(
                    title = "Scene ${idx + 1}",
                    sub = "${sc.size} similar · keeping $keepIn",
                    action = if (anyDeleted) "Keep all" else "Keep best",
                    onAction = {
                        if (anyDeleted) vm.setMany(sc.decisions.associate { it.photo.id to true })
                        else {
                            val best = sc.decisions.firstOrNull { it.verdict != Verdict.DELETE } ?: sc.decisions.first()
                            vm.setMany(sc.decisions.associate { it.photo.id to (it === best) })
                        }
                    },
                    onCompare = sc.decisions.filter { it.verdict == Verdict.CHOOSE }.takeIf { it.size >= 2 }?.let { pair -> { vm.openViewer(pair, 0, compare = true) } },
                    tiles = sc.decisions, state = state, vm = vm
                )
            }
            if (singles.isNotEmpty()) item {
                SceneBlock(
                    title = "Single shots",
                    sub = "${singles.size} with no similar frames",
                    action = null, onAction = {},
                    tiles = singles, state = state, vm = vm
                )
            }
        }
        Row(
            Modifier.fillMaxWidth().background(K.Surface).padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Secondary("Keep set", { vm.keepSession(s) })
            Primary(
                if (deleting.isEmpty()) "Nothing to delete" else "Keep ${Fmt.n(kept)}, delete ${Fmt.n(deleting.size)}",
                { vm.deleteFromSession(s) }, danger = true, weight = 1.6f, enabled = deleting.isNotEmpty()
            )
        }
    }
}

@Composable
private fun SceneBlock(
    title: String,
    sub: String,
    action: String?,
    onAction: () -> Unit,
    onCompare: (() -> Unit)? = null,
    tiles: List<Decision>,
    state: UiState,
    vm: AppViewModel
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, color = K.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text(sub, color = K.Muted, fontSize = 12.sp)
            }
            if (onCompare != null) {
                Text(
                    "Compare",
                    modifier = Modifier.padding(end = 6.dp).clip(RoundedCornerShape(16.dp)).background(K.Hero)
                        .clickable(onClick = onCompare).padding(horizontal = 12.dp, vertical = 7.dp),
                    color = K.OnHero, fontSize = 13.sp, fontWeight = FontWeight.Medium
                )
            }
            if (action != null) {
                Text(
                    action,
                    modifier = Modifier.clip(RoundedCornerShape(16.dp)).border(1.dp, K.Outline, RoundedCornerShape(16.dp))
                        .clickable(onClick = onAction).padding(horizontal = 12.dp, vertical = 7.dp),
                    color = K.Soft, fontSize = 13.sp, fontWeight = FontWeight.Medium
                )
            }
        }
        tiles.chunked(4).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                row.forEach { d ->
                    Box(Modifier.weight(1f)) {
                        DecisionTile(d, vm.isKept(d, state.overrides), state.overrides[d.photo.id], onLongClick = { vm.openViewer(tiles, tiles.indexOf(d), label = title) }) { vm.toggle(d) }
                    }
                }
                repeat(4 - row.size) { Box(Modifier.weight(1f)) }
            }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun DecisionTile(d: Decision, kept: Boolean, override: Boolean?, onLongClick: (() -> Unit)? = null, onClick: () -> Unit) {
    val reason = when {
        override == true && d.verdict == Verdict.DELETE -> "Kept by you"
        override == false && d.verdict != Verdict.DELETE -> "Removed by you"
        else -> d.reason
    }
    val color = if (d.verdict == Verdict.CHOOSE && override == null) K.Blue else K.Accent
    Box(
        Modifier.aspectRatio(1f).clip(RoundedCornerShape(8.dp)).combinedClickable(onClick = onClick, onLongClick = onLongClick).ring(kept, color)
    ) {
        Thumb(d.photo.id, Modifier.fillMaxSize())
        if (!kept) Box(Modifier.fillMaxSize().background(K.Fade))
        if (kept) {
            Text(
                if (d.verdict == Verdict.CHOOSE && override == null) "Choose" else "Keep",
                modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).clip(RoundedCornerShape(50)).background(color).padding(horizontal = 7.dp, vertical = 2.dp),
                color = K.OnAccent, fontSize = 11.sp, fontWeight = FontWeight.Bold
            )
        }
        Text(
            reason,
            modifier = Modifier.align(Alignment.BottomStart).padding(5.dp).clip(RoundedCornerShape(6.dp)).background(Color(0xB3000000)).padding(horizontal = 6.dp, vertical = 2.dp),
            color = if (kept) (if (K.p.dark) color else Color.White) else Color.White, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, maxLines = 1
        )
    }
}
