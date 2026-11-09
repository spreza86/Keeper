package com.preza.keeper.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle

private val PERMS: Array<String> =
    if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.ACCESS_MEDIA_LOCATION)
    else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.ACCESS_MEDIA_LOCATION)

@Composable
fun KeeperApp(vm: AppViewModel) {
    val state by vm.ui.collectAsStateWithLifecycle()
    val ctx = LocalContext.current

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        vm.onPermission(ContextCompat.checkSelfPermission(ctx, PERMS[0]) == PackageManager.PERMISSION_GRANTED)
    }
    LaunchedEffect(Unit) {
        if (ContextCompat.checkSelfPermission(ctx, PERMS[0]) == PackageManager.PERMISSION_GRANTED) vm.onPermission(true)
    }

    BackHandler(enabled = state.stack.size > 1) { vm.back() }
    BackHandler(enabled = state.viewer != null) { vm.closeViewer() }

    Box(Modifier.fillMaxSize()) {
    Scaffold(
        containerColor = K.Bg,
        bottomBar = { if (state.permission) BottomNav(state.screen, vm) }
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) {
            if (!state.permission) {
                PermissionScreen { launcher.launch(PERMS) }
            } else {
                when (val s = state.screen) {
                    Screen.Home -> HomeScreen(state, vm)
                    Screen.Sets -> SetsScreen(state, vm)
                    is Screen.SetDetail -> SetDetailScreen(state, vm, s.key)
                    Screen.Bursts -> BurstsScreen(state, vm)
                    Screen.Browse -> BrowseScreen(state, vm)
                    is Screen.Group -> GroupScreen(s, vm)
                    Screen.Settings -> SettingsScreen(state, vm)
                    Screen.Quick -> QuickScreen(state, vm)
                }
            }
            state.toast?.let { msg ->
                Row(
                    Modifier.align(Alignment.BottomCenter).padding(16.dp).fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp)).background(K.Text).padding(start = 16.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(msg, color = K.Bg, fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f).padding(vertical = 10.dp))
                    if (state.canUndo) TextButton(onClick = vm::undo) { Text("Undo", color = K.InverseAccent, fontWeight = FontWeight.Bold) }
                }
            }
        }
    }
    state.viewer?.let { PhotoViewer(it, state, vm) }
    }
}

@Composable
private fun BottomNav(screen: Screen, vm: AppViewModel) {
    data class Tab(val label: String, val icon: ImageVector, val target: Screen, val active: Boolean)
    val tabs = listOf(
        Tab("Home", Icons.Filled.Home, Screen.Home, screen == Screen.Home),
        Tab("Sets", Icons.Filled.DateRange, Screen.Sets, screen == Screen.Sets || screen is Screen.SetDetail),
        Tab("Bursts", Icons.Filled.Star, Screen.Bursts, screen == Screen.Bursts),
        Tab("Tinder", Icons.Filled.Favorite, Screen.Quick, screen == Screen.Quick),
        Tab("Browse", Icons.Filled.Place, Screen.Browse, screen == Screen.Browse || screen is Screen.Group)
    )
    NavigationBar(containerColor = K.Bg, tonalElevation = 0.dp) {
        tabs.forEach { t ->
            NavigationBarItem(
                selected = t.active,
                onClick = { vm.tab(t.target) },
                icon = { Icon(t.icon, contentDescription = null) },
                label = { Text(t.label, fontSize = 11.sp) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = K.Accent, selectedTextColor = K.Accent,
                    unselectedIconColor = K.Muted, unselectedTextColor = K.Muted,
                    indicatorColor = K.Surface2
                )
            )
        }
    }
}

@Composable
private fun PermissionScreen(onAsk: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
        Text("Keeper", color = K.Text, fontSize = 34.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(12.dp))
        Text(
            "Keeper needs to see your photos to find bursts, unedited sets and where you took them. " +
                "Choose \"Allow all\" when Android asks, otherwise it only sees the photos you pick.\n\n" +
                "Everything stays on this phone. Keeper has no internet permission, so it can't send anything anywhere.",
            color = K.Soft, fontSize = 15.sp, lineHeight = 22.sp
        )
        Spacer(Modifier.height(24.dp))
        Row { Primary("Allow access to photos", onAsk) }
    }
}
