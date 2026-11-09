package com.preza.keeper.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.preza.keeper.data.Verdict

/**
 * Quick clean: a short swipe game. Only the big photo on top is decided.
 * The bottom half shows the other similar shots from its group, just to compare.
 */
@Composable
fun QuickScreen(state: UiState, vm: AppViewModel) {
    val cards = state.quick
    val i = state.quickIndex
    val deleted = state.quickDeleted.size
    val freed = state.quickDeleted.sumOf { id -> state.library?.photoById?.get(id)?.size ?: 35_000_000L }

    if (cards.isEmpty()) {
        val lib = state.library
        val waiting = weakShotsWaiting(state)
        Column(Modifier.fillMaxSize().padding(28.dp), verticalArrangement = Arrangement.Center) {
            Text("Tinder mode", color = K.Text, fontSize = 32.sp, fontWeight = FontWeight.SemiBold)
            Text(
                "A quick round of 20 weak shots from your groups of similar photos. " +
                    "Swipe left to delete, right to keep. Tap the photo to see it full screen.",
                color = K.Muted, fontSize = 15.sp, lineHeight = 22.sp, modifier = Modifier.padding(top = 8.dp)
            )
            Text(
                if (lib == null) "Still scanning..." else "${Fmt.n(waiting)} weak shots waiting",
                color = K.Accent, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 16.dp)
            )
            Row(Modifier.padding(top = 20.dp)) { Primary("Start a round", { vm.startQuick() }, enabled = lib != null && waiting > 0) }
        }
        return
    }

    if (i >= cards.size) {
        Column(Modifier.fillMaxSize().padding(28.dp), verticalArrangement = Arrangement.Center) {
            Text("Round finished", color = K.Muted, fontSize = 14.sp)
            Text("$deleted deleted · ${state.quickKept} kept", color = K.Accent, fontSize = 34.sp, fontWeight = FontWeight.SemiBold)
            Text("${Fmt.gb(freed)} to free", color = K.Text, fontSize = 16.sp, modifier = Modifier.padding(top = 6.dp))
            if (deleted > 0) Text(
                "Android will ask once to move them to the trash. They can be restored for 30 days.",
                color = K.Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp)
            )
            Row(Modifier.padding(top = 20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Secondary("Another round", { vm.startQuick() })
                if (deleted > 0) Primary("Move $deleted to trash", { vm.quickTrash() }, danger = true, weight = 1.4f)
                else Primary("Done", { vm.quickStop() })
            }
        }
        return
    }

    val card = cards[i]
    val photo = card.decision.photo
    var offset by remember(photo.id) { mutableFloatStateOf(0f) }
    val others = card.scene.decisions.filter { it.photo.id != photo.id }.take(6)

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            IconButton(onClick = { vm.quickStop() }) { Icon(Icons.Filled.Close, contentDescription = "End round", tint = K.Text) }
            Column(Modifier.weight(1f)) {
                Text("Quick clean", color = K.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text("Card ${i + 1} of ${cards.size}", color = K.Muted, fontSize = 12.sp)
            }
            Chip("$deleted deleted · ${Fmt.gb(freed)}", K.Hero, K.OnHero)
        }
        LinearProgressIndicator(
            progress = { i.toFloat() / cards.size },
            modifier = Modifier.fillMaxWidth(), color = K.Accent, trackColor = K.Surface
        )

        // The photo being decided: about half the screen, drag left to delete, right to keep.
        Box(
            Modifier.fillMaxWidth().weight(1.15f)
                .graphicsLayer { translationX = offset; rotationZ = offset / 45f }
                .clip(RoundedCornerShape(28.dp))
                .pointerInput(photo.id) {
                    detectTapGestures(onTap = { vm.openViewer(card.scene.decisions, card.scene.decisions.indexOf(card.decision)) })
                }
                .pointerInput(photo.id) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            when {
                                offset < -size.width * 0.28f -> vm.quickDecide(false)
                                offset > size.width * 0.28f -> vm.quickDecide(true)
                                else -> offset = 0f
                            }
                        },
                        onDragCancel = { offset = 0f }
                    ) { change, drag ->
                        change.consume()
                        offset += drag
                    }
                }
        ) {
            LargePhoto(photo, Modifier.fillMaxSize())
            if (offset < -60f) Stamp("DELETE", Color(0xFFFF5A4E), Modifier.align(Alignment.TopEnd))
            if (offset > 60f) Stamp("KEEP", Color(0xFF7BD88F), Modifier.align(Alignment.TopStart))
        }

        Text("Similar shots (${card.scene.size - 1})", color = K.Text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Column(Modifier.fillMaxWidth().weight(0.85f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            others.chunked(3).forEach { row ->
                Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    row.forEach { d ->
                        val best = d.verdict != Verdict.DELETE
                        Box(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(10.dp)).ring(best, K.Accent, 10)) {
                            Thumb(d.photo.id, Modifier.fillMaxSize())
                            if (best) Text(
                                "Best",
                                modifier = Modifier.align(Alignment.TopStart).padding(5.dp).clip(RoundedCornerShape(8.dp))
                                    .background(K.Accent).padding(horizontal = 7.dp, vertical = 1.dp),
                                color = K.OnAccent, fontSize = 10.sp, fontWeight = FontWeight.Bold
                            )
                        }
                    }
                    repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
            if (others.size <= 3) Spacer(Modifier.weight(1f))
        }

        Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(28.dp, Alignment.CenterHorizontally)) {
            FilledIconButton(
                onClick = { vm.quickDecide(false) }, modifier = Modifier.size(62.dp), shape = CircleShape,
                colors = IconButtonDefaults.filledIconButtonColors(containerColor = K.Danger, contentColor = K.OnDanger)
            ) { Icon(Icons.Filled.Close, contentDescription = "Delete this photo") }
            FilledIconButton(
                onClick = { vm.quickDecide(true) }, modifier = Modifier.size(62.dp), shape = CircleShape,
                colors = IconButtonDefaults.filledIconButtonColors(containerColor = K.Accent, contentColor = K.OnAccent)
            ) { Icon(Icons.Filled.Check, contentDescription = "Keep this photo") }
        }
    }
}

@Composable
private fun Stamp(text: String, color: Color, modifier: Modifier) {
    Text(
        text,
        modifier = modifier.padding(18.dp).clip(RoundedCornerShape(8.dp)).background(Color(0x66000000)).padding(horizontal = 12.dp, vertical = 4.dp),
        color = color, fontSize = 24.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp
    )
}
