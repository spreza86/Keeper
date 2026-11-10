package com.preza.keeper.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun SettingsScreen(state: UiState, vm: AppViewModel) {
    var picking by remember { mutableStateOf<String?>(null) }   // "cam" or "edits"

    LazyColumn(
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { Title("Settings", onBack = if (state.stack.size > 1) ({ vm.back() }) else null, backLabel = "Home") }

        item { Label("Appearance") }
        item {
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(K.Surface).padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf("system" to "System", "light" to "Light", "dark" to "Dark").forEach { (key, label) ->
                    val on = state.appearance == key
                    Text(
                        label,
                        modifier = Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(if (on) K.Accent else K.Surface)
                            .clickable { vm.setAppearance(key) }.padding(vertical = 11.dp),
                        color = if (on) K.OnAccent else K.Soft, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            }
        }
        item { Label("Colour") }
        item {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(K.Surface).padding(4.dp)) {
                COLOR_THEMES.forEach { (key, label) ->
                    val on = state.colorTheme == key
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(if (on) K.Hero else K.Surface)
                            .clickable { vm.setColorTheme(key) }.padding(horizontal = 12.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Box(
                            Modifier.size(22.dp).clip(RoundedCornerShape(50))
                                .background(if (key == "wallpaper") K.Surface2 else themeSwatch(key))
                                .border(1.dp, K.Outline, RoundedCornerShape(50))
                        )
                        Text(label, color = if (on) K.OnHero else K.Text, fontSize = 15.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal, modifier = Modifier.weight(1f))
                        if (on) Text("On", color = K.OnHero, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
        item { Note("Keep stays green and delete stays red in every theme." + if (android.os.Build.VERSION.SDK_INT < 31) " Match my wallpaper needs Android 12." else "") }

        item { Label("Folders") }
        item {
            Panel(padding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)) {
                SettingRow("RAW photos", state.camFolder.trimEnd('/'), Modifier.clickable { picking = "cam"; vm.loadFolders() })
                HorizontalDivider(color = K.Line)
                SettingRow("Edits", state.editsFolder.trimEnd('/'), Modifier.clickable { picking = "edits"; vm.loadFolders() })
            }
        }

        item { Label("Rules") }
        item {
            Panel(padding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)) {
                Stepper("Abandoned after", "with zero edits", "${state.weeks} ${if (state.weeks == 1) "week" else "weeks"}", { vm.setWeeks(state.weeks - 1) }, { vm.setWeeks(state.weeks + 1) })
            }
        }

        item { Note("One outing = one day out. Photos from the same day are grouped together, and a lone photo is never listed as a set.") }

        item { Label("Learning from your edits") }
        item { Note(state.learned.ifBlank { "Runs after the first scan." }) }

        item { Label("Learning from your choices") }
        item {
            Note(
                (state.loosened.ifBlank { "No check adjusted yet." }) + "\n\n" +
                    "When you keep photos a check flagged (Blurry, Too bright, Flash not ready, eyes), after 10 answers where you kept most of them, " +
                    "that check gets one step less strict. Tinder swipes count too. " +
                    "Your choices in Sets and Bursts are saved: ${Fmt.plural(state.decided.size, "photo")} reviewed so far."
            )
        }
        item {
            Row { Secondary("Forget my choices", { vm.forgetChoices() }) }
        }
        item { Note("Clears your saved keep and delete choices and what Keeper learned from them. Reviewed scenes will be suggested again. Kept sets stay kept.") }

        item { Label("Where photos were taken, tried in order") }
        item {
            Note(
                "1. GPS saved in the photo, when the camera was linked to a phone by Bluetooth.\n" +
                    "2. Photos your phone took at the same time: within 15 min gives the area, the same outing gives the city, the same day gives a likely city.\n" +
                    "3. Neighbouring days of the same trip, marked as a guess.\n" +
                    "The camera clock is checked against your phone photos and corrected if it was wrong."
            )
        }

        item { Label("Privacy") }
        item {
            Note(
                "Keeper has no internet permission, so Android blocks it from connecting to anything. " +
                    "Place names come from a map database built into the app. Deleted photos go to Android's trash and can be restored for 30 days."
            )
        }

        item {
            Row(Modifier.padding(top = 8.dp)) {
                Secondary(if (state.scanning) "Scanning..." else "Rescan everything", { if (!state.scanning) vm.rescan() })
            }
        }
    }

    picking?.let { which ->
        AlertDialog(
            onDismissRequest = { picking = null },
            containerColor = K.Surface,
            title = { Text(if (which == "cam") "RAW photos folder" else "Edits folder", color = K.Text) },
            text = {
                if (state.folders.isEmpty()) Text("Loading folders...", color = K.Muted)
                else LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(state.folders) { (path, n) ->
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                if (which == "cam") vm.setCameraFolder(path) else vm.setEditsFolder(path)
                                picking = null
                            }.padding(vertical = 12.dp)
                        ) {
                            Text(path.trimEnd('/'), color = K.Text, fontSize = 15.sp, modifier = Modifier.weight(1f))
                            Text(Fmt.n(n), color = K.Muted, fontSize = 13.sp)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { picking = null }) { Text("Cancel", color = K.Accent) } }
        )
    }
}

@Composable
private fun SettingRow(title: String, value: String, modifier: Modifier) {
    Column(modifier.fillMaxWidth().padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, color = K.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        Text(value, color = K.Muted, fontSize = 13.sp)
    }
}

@Composable
private fun Stepper(title: String, sub: String, value: String, onMinus: () -> Unit, onPlus: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = K.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(sub, color = K.Muted, fontSize = 13.sp)
        }
        StepButton("−", onMinus)
        Text(value, color = K.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 8.dp))
        StepButton("+", onPlus)
    }
}

@Composable
private fun StepButton(label: String, onClick: () -> Unit) {
    FilledTonalButton(
        onClick = onClick,
        modifier = Modifier.size(44.dp),
        shape = RoundedCornerShape(12.dp),
        contentPadding = PaddingValues(0.dp),
        colors = ButtonDefaults.filledTonalButtonColors(containerColor = K.Surface2, contentColor = K.Text)
    ) { Text(label, fontSize = 20.sp) }
}
