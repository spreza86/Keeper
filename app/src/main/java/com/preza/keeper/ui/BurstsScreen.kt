package com.preza.keeper.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.preza.keeper.data.Verdict

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun BurstsScreen(state: UiState, vm: AppViewModel) {
    val lib = state.library
    // Order follows your sort choice; your place is kept during this visit (Next and Skip move along it).
    val bursts = lib?.bursts.orEmpty().let { vm.sortedBursts(it, state) }
    val index = if (bursts.isEmpty()) 0 else bursts.indexOfFirst { it.second.key == state.burstKey }.takeIf { it >= 0 }
        ?: state.burstIdx.coerceIn(0, bursts.size - 1)
    fun go(i: Int) = vm.burstGo(bursts, i)

    LazyColumn(
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Title("Bursts", if (bursts.isEmpty()) null else "${index + 1} of ${Fmt.n(bursts.size)}")
        }
        if (bursts.isNotEmpty()) item { SortChips(state.burstsSort) { vm.setBurstsSort(it) } }
        if (bursts.isEmpty()) {
            item { Note(if (lib == null) "Still scanning..." else "All bursts are culled.") }
            return@LazyColumn
        }
        val (session, scene) = bursts[index]
        val ds = scene.decisions
        item {
            var selected by remember(scene.key) {
                mutableStateOf(ds.indexOfFirst { it.verdict != Verdict.DELETE }.coerceAtLeast(0))
            }
            val sel = ds[selected.coerceIn(0, ds.size - 1)]
            val maxSharp = ds.maxOf { it.photo.f.subjectSharp }.coerceAtLeast(0.01f)
            val keptCount = ds.count { vm.isKept(it, state.overrides) }
            val delCount = ds.size - keptCount

            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(
                    "${Fmt.day(session.start, session.offset)}, ${Fmt.range(session.start, session.end, session.offset)} · ${ds.size} frames" +
                        (session.place?.let { " · ${it.city}" } ?: ""),
                    color = K.Muted, fontSize = 14.sp
                )
                Box(Modifier.fillMaxWidth().height(300.dp).clip(RoundedCornerShape(16.dp))) {
                    LargePhoto(sel.photo, Modifier.fillMaxSize().clickable { vm.openViewer(ds, selected) })
                    Text(
                        sel.photo.name,
                        modifier = Modifier.align(Alignment.TopStart).padding(10.dp).clip(RoundedCornerShape(50)).background(Color(0xBF000000)).padding(horizontal = 10.dp, vertical = 4.dp),
                        color = Color.White, fontSize = 12.sp
                    )
                    if (sel.verdict != Verdict.DELETE) {
                        Text(
                            sel.reason,
                            modifier = Modifier.align(Alignment.TopEnd).padding(10.dp).clip(RoundedCornerShape(50))
                                .background(if (sel.verdict == Verdict.CHOOSE) K.Blue else K.Keep).padding(horizontal = 10.dp, vertical = 4.dp),
                            color = K.OnKeep, fontSize = 12.sp, fontWeight = FontWeight.Bold
                        )
                    }
                }
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    itemsIndexed(ds, key = { _, d -> d.photo.id }) { i, d ->
                        val kept = vm.isKept(d, state.overrides)
                        val color = if (d.verdict == Verdict.CHOOSE && state.overrides[d.photo.id] == null) K.Blue else K.Keep
                        Box(
                            Modifier.size(68.dp).clip(RoundedCornerShape(8.dp))
                                .combinedClickable(
                                    onClick = { if (selected == i) vm.toggle(d) else selected = i },
                                    onLongClick = { vm.openViewer(ds, i) }
                                )
                                .ring(kept, color)
                        ) {
                            Thumb(d.photo.id, Modifier.fillMaxSize())
                            if (!kept) Box(Modifier.fillMaxSize().background(K.Fade))
                            if (i == selected) Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(4.dp).background(K.Text))
                        }
                    }
                }
                Text("Tap a frame to see it, tap again to keep or drop it. Hold or tap the big photo to zoom.", color = K.Muted, fontSize = 12.sp)

                Panel {
                    Stat("Sharpness", "${(100 * sel.photo.f.subjectSharp / maxSharp).toInt()}", null)
                    val f = sel.photo.f
                    if (f.faces > 0) {
                        Stat("Faces", "${f.faces}", null)
                        if (f.eyesOpen >= 0f) Stat("Eyes open", if (f.eyesOpen > 0.25f) "Yes" else "Maybe not (only lowers the ranking)", if (f.eyesOpen > 0.25f) true else null)
                    }
                    Stat(
                        "Exposure",
                        when {
                            f.blown > 0.25f -> "Too bright"
                            f.crush > 0.55f -> "Dark (${(f.crush * 100).toInt()}% near black)"
                            else -> "Good"
                        },
                        f.blown <= 0.25f
                    )
                    sel.photo.flashFired?.let { Stat("Flash", if (it) "Fired" else "Didn't fire", it) }
                    Text(sel.reason, color = K.Muted, fontSize = 13.sp)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Secondary("Keep all", {
                        vm.keepAllBurst(scene)
                    })
                    Primary(
                        if (delCount == 0) "Next" else "Keep $keptCount, delete $delCount",
                        {
                            if (delCount == 0) { vm.keepAllBurst(scene); go(index) } else vm.cullBurst(scene)
                        },
                        danger = delCount > 0, weight = 1.6f
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Secondary("Previous", { go(index - 1) })
                    Secondary("Skip", { go(index + 1) })
                }
            }
        }
    }
}

@Composable
private fun Stat(label: String, value: String, good: Boolean?) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, color = K.Muted, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Text(
            value, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
            color = when (good) { true -> K.Green; false -> K.Accent; null -> K.Text }
        )
    }
}
