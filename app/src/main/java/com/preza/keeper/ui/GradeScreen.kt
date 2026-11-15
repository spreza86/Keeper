package com.preza.keeper.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Grade Keeper: you answer Good or Bad on a random sample without seeing Keeper's opinion,
 * then see how often Keeper agreed with you, overall and per check. Deletes nothing, teaches nothing.
 */
@Composable
fun GradeScreen(state: UiState, vm: AppViewModel) {
    val sample = state.grade
    if (sample.isEmpty()) {
        Column(Modifier.fillMaxSize().padding(28.dp), verticalArrangement = Arrangement.Center) {
            Title("Grade Keeper", onBack = { vm.gradeClose() }, backLabel = "Settings")
            Row(Modifier.padding(top = 16.dp)) { Primary("Start grading", { vm.startGrade() }) }
        }
        return
    }
    val i = state.gradeIndex
    if (i >= sample.size) { GradeResults(state, vm); return }

    val d = sample[i]
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            IconButton(onClick = { vm.gradeClose() }) { Icon(Icons.Filled.Close, contentDescription = "Stop grading", tint = K.Text) }
            Column(Modifier.weight(1f)) {
                Text("Grade Keeper", color = K.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text("Photo ${i + 1} of ${sample.size}", color = K.Muted, fontSize = 12.sp)
            }
            if (i >= 5) TextButton(onClick = { vm.gradeFinishEarly() }) { Text("See score now", color = K.Accent) }
        }
        LinearProgressIndicator(progress = { i.toFloat() / sample.size }, modifier = Modifier.fillMaxWidth(), color = K.Accent, trackColor = K.Surface)
        Text(
            "Would you keep this photo? Keeper's opinion is hidden until the end.",
            color = K.Soft, fontSize = 14.sp
        )
        Box(Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(24.dp))) {
            LargePhoto(d.photo, Modifier.fillMaxSize())
        }
        Row(
            Modifier.fillMaxWidth().padding(bottom = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(22.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically
        ) {
            FilledIconButton(
                onClick = { vm.gradeAnswer(false) }, modifier = Modifier.size(62.dp), shape = CircleShape,
                colors = IconButtonDefaults.filledIconButtonColors(containerColor = K.Danger, contentColor = K.OnDanger)
            ) { Icon(Icons.Filled.Close, contentDescription = "Bad") }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Bad  ·  Good", color = K.Muted, fontSize = 13.sp)
                if (i > 0) TextButton(onClick = { vm.gradeBack() }) { Text("Previous", color = K.Accent, fontSize = 13.sp) }
            }
            FilledIconButton(
                onClick = { vm.gradeAnswer(true) }, modifier = Modifier.size(62.dp), shape = CircleShape,
                colors = IconButtonDefaults.filledIconButtonColors(containerColor = K.Keep, contentColor = K.OnKeep)
            ) { Icon(Icons.Filled.Check, contentDescription = "Good") }
        }
    }
}

@Composable
private fun GradeResults(state: UiState, vm: AppViewModel) {
    val r = GradeResult.of(state.grade, state.gradeAnswers)
    val pct = if (r.total == 0) 0 else 100 * r.agreed / r.total
    LazyColumn(
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { Title("Keeper's grade", onBack = { vm.gradeClose() }, backLabel = "Settings") }
        item {
            Column {
                Text("$pct%", color = K.Accent, fontSize = 44.sp, fontWeight = FontWeight.SemiBold)
                Text("Keeper agreed with you on ${r.agreed} of ${r.total} photos.", color = K.Text, fontSize = 16.sp)
            }
        }
        item { Label("By check") }
        item {
            Panel(padding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)) {
                val rows = r.perCheck.map { (c, ok, n) ->
                    val name = if (c == com.preza.keeper.logic.Checks.EYES) "Eyes closed (you called it Bad)" else "$c (suggested for deletion)"
                    name to "$ok of $n right"
                } + listOf("Photos Keeper would keep" to "${r.keptRight.first} of ${r.keptRight.second} right")
                rows.forEachIndexed { idx, (name, v) ->
                    if (idx > 0) HorizontalDivider(color = K.Line)
                    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
                        Text(name, color = K.Text, fontSize = 15.sp, modifier = Modifier.weight(1f))
                        Text(v, color = K.Soft, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
        item {
            Note(
                "Right means Keeper would delete a photo you called Bad, or keep one you called Good. " +
                    "Eyes closed only lowers the ranking, so there right means you also called the photo Bad."
            )
        }
        if (r.disagreements.isNotEmpty()) {
            item { Label("Where Keeper disagreed (${r.disagreements.size})") }
            item { Note("Tap a photo to see why Keeper decided what it did.") }
            r.disagreements.chunked(3).forEach { row ->
                item {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        row.forEach { d ->
                            val you = if (state.gradeAnswers[d.photo.id] == true) "You: good" else "You: bad"
                            Column(Modifier.weight(1f)) {
                                Thumb(
                                    d.photo.id,
                                    Modifier.fillMaxWidth().aspectRatio(1.5f).clip(RoundedCornerShape(10.dp))
                                        .clickable { vm.openViewer(r.disagreements, r.disagreements.indexOf(d), label = "Keeper disagreed") }
                                )
                                Text(you, color = K.Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp))
                            }
                        }
                        repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
        item { GradeHistory(state.grades) }
        item {
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Secondary("Grade again", { vm.gradeClose(); vm.startGrade() })
                Primary("Done", { vm.gradeClose() })
            }
        }
    }
}

/** Past scores, newest first, so versions can be compared. */
@Composable
fun GradeHistory(grades: List<String>) {
    if (grades.isEmpty()) return
    val fmt = SimpleDateFormat("MMM d", Locale.getDefault())
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Label("Past grades")
        grades.reversed().take(8).forEach { g ->
            val p = g.split('|')
            val at = p.getOrNull(0)?.toLongOrNull() ?: return@forEach
            val ok = p.getOrNull(1)?.toIntOrNull() ?: return@forEach
            val n = p.getOrNull(2)?.toIntOrNull()?.takeIf { it > 0 } ?: return@forEach
            val v = p.getOrNull(3).orEmpty()
            Text(
                "${fmt.format(Date(at))}${if (v.isNotBlank()) ", version $v" else ""}: ${100 * ok / n}% of $n photos",
                color = K.Soft, fontSize = 14.sp
            )
        }
    }
}
