package com.preza.keeper.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.preza.keeper.data.CamPhoto
import com.preza.keeper.data.Thumbs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun Thumb(id: Long, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    var bmp by remember(id) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(id) {
        bmp = withContext(Dispatchers.IO) { Thumbs.load(ctx, id) }
    }
    Box(modifier.background(K.Surface2)) {
        bmp?.let { Image(it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
    }
}

@Composable
fun LargePhoto(p: CamPhoto, modifier: Modifier = Modifier, contentScale: ContentScale = ContentScale.Fit) {
    val ctx = LocalContext.current
    var small by remember(p.id) { mutableStateOf<ImageBitmap?>(null) }
    var large by remember(p.id) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(p.id) {
        small = withContext(Dispatchers.IO) { Thumbs.load(ctx, p.id) }
        large = withContext(Dispatchers.IO) { Thumbs.loadLarge(ctx, p) }
    }
    Box(modifier.background(K.Surface2)) {
        (large ?: small)?.let { Image(it, contentDescription = p.name, contentScale = contentScale, modifier = Modifier.fillMaxSize()) }
    }
}

@Composable
fun Title(text: String, sub: String? = null, onBack: (() -> Unit)? = null, backLabel: String = "Back") {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (onBack != null) {
            TextButton(onClick = onBack, contentPadding = PaddingValues(horizontal = 0.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, tint = K.Accent)
                Text(" $backLabel", color = K.Accent, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        Text(text, color = K.Text, fontSize = 30.sp, fontWeight = FontWeight.SemiBold)
        if (sub != null) Text(sub, color = K.Muted, fontSize = 14.sp, lineHeight = 20.sp)
    }
}

@Composable
fun Label(text: String, modifier: Modifier = Modifier) {
    Text(text.uppercase(), modifier = modifier.padding(top = 8.dp), color = K.Muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.8.sp)
}

@Composable
fun Panel(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    padding: PaddingValues = PaddingValues(16.dp),
    content: @Composable ColumnScope.() -> Unit
) {
    val base = modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(K.Surface)
    Column(
        (if (onClick != null) base.clickable(onClick = onClick) else base).padding(padding),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        content = content
    )
}

@Composable
fun Note(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(K.Surface2).padding(12.dp),
        color = K.Soft, fontSize = 13.sp, lineHeight = 19.sp
    )
}

@Composable
fun Chip(text: String, bg: Color = K.Surface2, fg: Color = K.Soft) {
    Text(
        text,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(bg).padding(horizontal = 9.dp, vertical = 4.dp),
        color = fg, fontSize = 11.sp, fontWeight = FontWeight.SemiBold
    )
}

@Composable
fun RowScope.Primary(text: String, onClick: () -> Unit, danger: Boolean = false, weight: Float = 1f, enabled: Boolean = true) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.weight(weight).heightIn(min = 48.dp),
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (danger) K.Danger else K.Accent,
            contentColor = if (danger) K.OnDanger else K.OnAccent
        )
    ) { Text(text, fontWeight = FontWeight.SemiBold, fontSize = 15.sp) }
}

@Composable
fun RowScope.Secondary(text: String, onClick: () -> Unit, weight: Float = 1f) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.weight(weight).heightIn(min = 48.dp),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, K.Outline),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = K.Text)
    ) { Text(text, fontWeight = FontWeight.SemiBold, fontSize = 15.sp) }
}

@Composable
fun ListRow(
    thumbId: Long?,
    title: String,
    sub: String,
    chip: String? = null,
    onClick: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (thumbId != null) Thumb(thumbId, Modifier.size(44.dp).clip(RoundedCornerShape(8.dp)))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, color = K.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(sub, color = K.Muted, fontSize = 12.sp)
        }
        if (chip != null) Chip(chip, K.Hero, K.OnHero)
    }
}

fun Modifier.rounded(r: Int = 8): Modifier = this.clip(RoundedCornerShape(r.dp))

fun Modifier.ring(on: Boolean, color: Color = K.Accent, r: Int = 8): Modifier =
    if (on) this.border(3.dp, color, RoundedCornerShape(r.dp)) else this
