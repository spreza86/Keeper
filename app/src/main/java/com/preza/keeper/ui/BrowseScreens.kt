package com.preza.keeper.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import com.preza.keeper.data.Library
import com.preza.keeper.data.LocSource
import com.preza.keeper.data.Session
import com.preza.keeper.data.Trip
import kotlin.math.abs

private val TABS = listOf("Trips", "Places", "Colors", "Light")

@Composable
fun BrowseScreen(state: UiState, vm: AppViewModel) {
    val lib = state.library
    LazyColumn(
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { Title("Browse") }
        item {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(K.Surface).padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                TABS.forEachIndexed { i, t ->
                    val on = state.browseTab == i
                    Box(
                        Modifier.weight(1f).heightIn(min = 40.dp).clip(RoundedCornerShape(10.dp))
                            .background(if (on) K.Accent else Color.Transparent).clickable { vm.browseTab(i) },
                        contentAlignment = Alignment.Center
                    ) { Text(t, color = if (on) K.OnAccent else K.Soft, fontSize = 14.sp, fontWeight = FontWeight.SemiBold) }
                }
            }
        }
        if (lib == null) {
            item { Note("Still scanning...") }
            return@LazyColumn
        }
        when (state.browseTab) {
            0 -> {
                item { Hint("Trips are split by gaps between shooting days. Places come from GPS saved in the photo, or from photos your phone took during the same outing.") }
                items(lib.trips, key = { it.key }) { TripCard(it, vm) }
            }
            1 -> {
                item { CoverageCard(lib) }
                items(lib.places.entries.toList(), key = { it.key }) { (country, cities) ->
                    Panel(padding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)) {
                        Label("$country · ${Fmt.plural(cities.values.sumOf { it.size }, "photo")}")
                        cities.entries.sortedByDescending { it.value.size }.forEach { (city, ids) ->
                            val src = lib.placeSource["$country|$city"]
                            ListRow(ids.first(), "$city · ${Fmt.n(ids.size)}", src?.short ?: "") {
                                vm.open(Screen.Group(city, "${Fmt.plural(ids.size, "photo")} · $country", ids, listOfNotNull(src?.let { "Place from: ${it.label.lowercase()}" })))
                            }
                        }
                    }
                }
                val unknown = lib.coverage["NONE"] ?: 0
                if (unknown > 0) item { Hint("${Fmt.plural(unknown, "photo")} have no GPS and no phone photo nearby, so their place is unknown.") }
            }
            2 -> {
                item { Hint("Dominant color of each photo, read from the small preview inside the RAW file.") }
                item {
                    val entries = lib.colors.entries.toList()
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        entries.chunked(2).forEach { pair ->
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                pair.forEach { (name, ids) ->
                                    Column(
                                        Modifier.weight(1f).clip(RoundedCornerShape(16.dp)).background(K.Surface)
                                            .clickable { vm.open(Screen.Group(name, Fmt.plural(ids.size, "photo"), ids, emptyList())) }
                                    ) {
                                        Row(Modifier.fillMaxWidth().height(72.dp)) {
                                            ids.take(3).forEach { Thumb(it, Modifier.weight(1f).fillMaxHeight()) }
                                        }
                                        Column(Modifier.padding(12.dp)) {
                                            Text(name, color = K.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                                            Text(Fmt.plural(ids.size, "photo"), color = K.Soft, fontSize = 13.sp)
                                        }
                                    }
                                }
                                if (pair.size == 1) Box(Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
            else -> {
                item { Hint("Uses the sun's position when the place is known. Otherwise it only tells day from night, using brightness, flash and the hour.") }
                item {
                    Panel(padding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)) {
                        lib.lights.forEach { (name, ids) ->
                            val note = when (name) {
                                "Golden hour" -> "Sun low, just above or below the horizon"
                                "Blue hour" -> "Just after sunset or before sunrise"
                                "Night" -> "Sun well below the horizon, or a dark scene"
                                else -> "Sun up"
                            }
                            ListRow(ids.first(), "$name · ${Fmt.n(ids.size)}", note) {
                                vm.open(Screen.Group(name, Fmt.plural(ids.size, "photo"), ids, emptyList()))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, color = K.Muted, fontSize = 14.sp, lineHeight = 20.sp)
}

@Composable
private fun TripCard(t: Trip, vm: AppViewModel) {
    var draft by remember(t.key) { mutableStateOf("") }
    var showAll by remember(t.key) { mutableStateOf(false) }
    Panel {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(t.name, color = K.Text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Text("${Fmt.dates(t.start, t.end, t.offset)} · ${Fmt.plural(t.count, "photo")}", color = K.Muted, fontSize = 13.sp)
                if (t.clockShiftMin != 0) {
                    val h = abs(t.clockShiftMin) / 60.0
                    Text(
                        "Camera clock was ${if (h % 1.0 == 0.0) h.toInt().toString() else "%.1f".format(h)} h ${if (t.clockShiftMin > 0) "behind" else "ahead"}, corrected",
                        color = K.Accent, fontSize = 12.sp
                    )
                }
            }
            when {
                !t.located -> Chip("No location", K.Surface2, K.Soft)
                t.isHome -> Chip("Home", K.Surface2, K.Muted)
                else -> Chip("Trip", K.Hero, K.OnHero)
            }
        }
        if (!t.located) {
            Text("No GPS in these photos and no phone photos at the same time, so Keeper can't tell where this was.", color = K.Soft, fontSize = 13.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = draft, onValueChange = { draft = it },
                    label = { Text("Name this trip") }, singleLine = true,
                    modifier = Modifier.weight(1f),
                    colors = OutlinedTextFieldDefaults.colors(focusedTextColor = K.Text, unfocusedTextColor = K.Text, focusedBorderColor = K.Accent)
                )
                Row(Modifier.size(width = 90.dp, height = 56.dp)) { Primary("Save", { vm.nameTrip(t.key, draft) }) }
            }
        }
        val list = if (showAll) t.sessions.reversed() else t.sessions.reversed().take(5)
        list.forEach { s -> SessionRow(s, vm) }
        if (t.sessions.size > 5 && !showAll) {
            HorizontalDivider(color = K.Line)
            Text(
                "Show ${t.sessions.size - 5} more outings",
                color = K.Accent, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clickable { showAll = true }.padding(vertical = 6.dp)
            )
        }
    }
}

@Composable
private fun SessionRow(s: Session, vm: AppViewModel) {
    HorizontalDivider(color = K.Line)
    val title = (s.place?.let { p -> p.area?.let { "$it, ${p.city}" } ?: p.city } ?: "Outing")
    ListRow(
        thumbId = s.photos.first().id,
        title = title,
        sub = "${Fmt.day(s.start, s.offset)}, ${Fmt.range(s.start, s.end, s.offset)} · ${Fmt.plural(s.photos.size, "photo")}",
        chip = s.holiday
    ) {
        vm.open(
            Screen.Group(
                title,
                "${Fmt.day(s.start, s.offset)}, ${Fmt.range(s.start, s.end, s.offset)} · ${Fmt.plural(s.photos.size, "photo")}",
                s.photos.map { it.id },
                listOfNotNull(s.holiday, s.place?.source?.let { "Place from: ${it.label.lowercase()}" }, if (s.edited) "Has edits" else null)
            )
        )
    }
}

@Composable
private fun CoverageCard(lib: Library) {
    val total = lib.total.coerceAtLeast(1)
    val rows = listOf(
        Triple(LocSource.PHOTO_GPS.label, lib.coverage[LocSource.PHOTO_GPS.name] ?: 0, K.Blue),
        Triple(LocSource.PHONE_15.label, lib.coverage[LocSource.PHONE_15.name] ?: 0, K.Green),
        Triple(LocSource.PHONE_OUTING.label, lib.coverage[LocSource.PHONE_OUTING.name] ?: 0, K.Green.copy(alpha = 0.6f)),
        Triple(LocSource.PHONE_DAY.label, lib.coverage[LocSource.PHONE_DAY.name] ?: 0, K.Accent),
        Triple(LocSource.NEIGHBOUR.label, lib.coverage[LocSource.NEIGHBOUR.name] ?: 0, K.Purple),
        Triple("Not found", lib.coverage["NONE"] ?: 0, K.Grey)
    )
    val found = total - (lib.coverage["NONE"] ?: 0)
    Panel {
        Row(Modifier.fillMaxWidth()) {
            Text("Place found", color = K.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text("${100 * found / total}% of photos", color = K.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        }
        Row(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(50)).background(K.Line)) {
            rows.filter { it.second > 0 }.forEach { (_, n, c) -> Box(Modifier.weight(n.toFloat()).fillMaxHeight().background(c)) }
        }
        rows.forEach { (label, n, c) ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.size(10.dp).clip(RoundedCornerShape(3.dp)).background(c))
                Text(label, color = K.Soft, fontSize = 13.sp, modifier = Modifier.weight(1f))
                Text("${100 * n / total}%", color = K.Text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GroupScreen(g: Screen.Group, vm: AppViewModel) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 96.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(bottom = 8.dp)) {
                Title(g.title, g.subtitle, onBack = { vm.back() }, backLabel = "Browse")
                if (g.tags.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        g.tags.forEach { Chip(it) }
                    }
                }
            }
        }
        items(g.ids, key = { it }) { id ->
            Thumb(id, Modifier.aspectRatio(1f).rounded())
        }
    }
}
