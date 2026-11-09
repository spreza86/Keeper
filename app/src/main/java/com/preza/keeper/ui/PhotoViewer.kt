package com.preza.keeper.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.preza.keeper.data.Decision
import com.preza.keeper.data.Verdict

/** Zoom and pan of one image. Shared by both images in compare mode so they move together. */
@Stable
class ZoomState {
    var scale by mutableFloatStateOf(1f)
    var offset by mutableStateOf(Offset.Zero)
    var size by mutableStateOf(IntSize.Zero)

    fun apply(zoom: Float, pan: Offset) {
        scale = (scale * zoom).coerceIn(1f, 6f)
        if (scale <= 1.01f) { scale = 1f; offset = Offset.Zero; return }
        val maxX = size.width * (scale - 1f) / 2f
        val maxY = size.height * (scale - 1f) / 2f
        val o = offset + pan
        offset = Offset(o.x.coerceIn(-maxX, maxX), o.y.coerceIn(-maxY, maxY))
    }

    fun toggle(at: Offset) {
        if (scale > 1f) { scale = 1f; offset = Offset.Zero }
        else {
            scale = 2.5f
            // zoom towards the tapped point
            val c = Offset(size.width / 2f, size.height / 2f)
            apply(1f, (c - at) * 1.5f)
        }
    }
}

/**
 * Pinch to zoom, drag to pan when zoomed, double-tap to zoom in or out.
 * A single-finger drag at normal size is left alone so the pager can swipe between photos.
 */
@Composable
fun ZoomablePhoto(d: Decision, state: ZoomState, modifier: Modifier = Modifier) {
    Box(
        modifier
            .clipToBounds()
            .onSizeChanged { state.size = it }
            .pointerInput(d.photo.id) {
                detectTapGestures(onDoubleTap = { state.toggle(it) })
            }
            .pointerInput(d.photo.id) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        val fingers = event.changes.count { it.pressed }
                        if (fingers > 1 || state.scale > 1f) {
                            state.apply(event.calculateZoom(), event.calculatePan())
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            }
    ) {
        LargePhoto(
            d.photo,
            Modifier.fillMaxSize().graphicsLayer {
                scaleX = state.scale; scaleY = state.scale
                translationX = state.offset.x; translationY = state.offset.y
            }
        )
    }
}

@Composable
fun PhotoViewer(v: ViewerState, state: UiState, vm: AppViewModel) {
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (v.compare && v.decisions.size >= 2) {
            val shared = remember { ZoomState() }
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(top = 56.dp)) {
                v.decisions.take(2).forEach { d ->
                    Box(Modifier.fillMaxWidth().weight(1f).padding(vertical = 2.dp)) {
                        ZoomablePhoto(d, shared, Modifier.fillMaxSize())
                        KeepToggle(d, vm.isKept(d, state.overrides), small = true, modifier = Modifier.align(Alignment.BottomCenter).padding(10.dp)) { keep ->
                            vm.setKeep(d, keep)
                        }
                    }
                }
            }
            TopBar("Compare · zoom moves both", null) { vm.closeViewer() }
        } else {
            val pager = rememberPagerState(initialPage = v.index, pageCount = { v.decisions.size })
            val zooms = remember(v) { v.decisions.map { ZoomState() } }
            HorizontalPager(
                state = pager,
                userScrollEnabled = zooms.getOrNull(pager.currentPage)?.scale?.let { it <= 1f } ?: true,
                modifier = Modifier.fillMaxSize()
            ) { page ->
                ZoomablePhoto(v.decisions[page], zooms[page], Modifier.fillMaxSize())
            }
            val cur = v.decisions[pager.currentPage.coerceIn(0, v.decisions.size - 1)]
            val kept = vm.isKept(cur, state.overrides)
            var showWhy by remember { mutableStateOf(false) }
            val count = "${pager.currentPage + 1} of ${v.decisions.size}"
            Column(Modifier.fillMaxWidth()) {
                TopBar(
                    if (v.label != null) "${v.label} · $count" else count,
                    reasonFor(cur, state.overrides[cur.photo.id]),
                    why = if (cur.why.isNotBlank()) ({ showWhy = !showWhy }) else null,
                    whyOpen = showWhy
                ) { vm.closeViewer() }
                if (showWhy && cur.why.isNotBlank()) {
                    Column(
                        Modifier.fillMaxWidth().background(Color(0xE6000000)).padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text("Why \"${cur.reason}\"", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        cur.why.lines().forEach { Text(it, color = Color(0xFFE0E0E0), fontSize = 12.sp, lineHeight = 16.sp) }
                    }
                }
            }
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("Pinch or double-tap to zoom · swipe for the other frames", color = Color(0xFFBDBDBD), fontSize = 12.sp)
                KeepToggle(cur, kept, small = false) { keep -> vm.setKeep(cur, keep) }
            }
        }
    }
}

private fun reasonFor(d: Decision, override: Boolean?): String = when {
    override == true && d.verdict == Verdict.DELETE -> "Kept by you"
    override == false && d.verdict != Verdict.DELETE -> "Removed by you"
    else -> d.reason
}

@Composable
private fun TopBar(title: String, chip: String?, why: (() -> Unit)? = null, whyOpen: Boolean = false, onClose: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(Color(0x99000000)).statusBarsPadding().padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = "Close", tint = Color.White) }
        Text(title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        if (chip != null) Text(
            chip,
            modifier = Modifier.padding(end = 12.dp).clip(RoundedCornerShape(50)).background(Color(0x33FFFFFF)).padding(horizontal = 10.dp, vertical = 4.dp),
            color = Color.White, fontSize = 12.sp
        )
        if (why != null) Text(
            if (whyOpen) "Hide" else "Why?",
            modifier = Modifier.padding(end = 12.dp).clip(RoundedCornerShape(50)).background(Color(0xFFB1D18A))
                .clickable(onClick = why).padding(horizontal = 10.dp, vertical = 4.dp),
            color = Color(0xFF1F3701), fontSize = 12.sp, fontWeight = FontWeight.SemiBold
        )
    }
}

/** Two-state control: the selected side is filled. */
@Composable
private fun KeepToggle(d: Decision, kept: Boolean, small: Boolean, modifier: Modifier = Modifier, onChange: (Boolean) -> Unit) {
    val h = if (small) 40.dp else 52.dp
    Row(modifier.clip(RoundedCornerShape(26.dp)).background(Color(0xCC1A1A1A)).padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Button(
            onClick = { onChange(false) },
            modifier = Modifier.heightIn(min = h),
            shape = RoundedCornerShape(22.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (!kept) Color(0xFFFFB4AB) else Color.Transparent,
                contentColor = if (!kept) Color(0xFF690005) else Color.White
            )
        ) { Icon(Icons.Filled.Close, contentDescription = null); Text(" Delete", fontWeight = FontWeight.SemiBold) }
        Button(
            onClick = { onChange(true) },
            modifier = Modifier.heightIn(min = h),
            shape = RoundedCornerShape(22.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (kept) Color(0xFFB1D18A) else Color.Transparent,
                contentColor = if (kept) Color(0xFF1F3701) else Color.White
            )
        ) { Icon(Icons.Filled.Check, contentDescription = null); Text(" Keep", fontWeight = FontWeight.SemiBold) }
    }
}
