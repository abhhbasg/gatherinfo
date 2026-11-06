package com.imunav.app.ui

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material.icons.rounded.Route
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.imunav.app.NavViewModel
import com.imunav.app.ui.screens.DashboardScreen
import com.imunav.app.ui.screens.SensorsScreen
import com.imunav.app.ui.screens.TrackScreen

private data class Tab(val title: String, val icon: androidx.compose.ui.graphics.vector.ImageVector)

private val tabs = listOf(
    Tab("仪表", Icons.Rounded.Dashboard),
    Tab("轨迹", Icons.Rounded.Route),
    Tab("传感器", Icons.Rounded.Tune),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImuNavApp(viewModel: NavViewModel, onRequestLocation: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val gnss by viewModel.gnss.collectAsStateWithLifecycle()
    val tracks by viewModel.tracks.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("惯导姿态", fontWeight = FontWeight.SemiBold) },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                tabs.forEachIndexed { i, t ->
                    NavigationBarItem(
                        selected = tab == i,
                        onClick = { tab = i },
                        icon = { Icon(t.icon, null) },
                        label = { Text(t.title) },
                    )
                }
            }
        },
    ) { inner ->
        val dir = LocalLayoutDirection.current
        val padding = PaddingValues(
            start = inner.calculateStartPadding(dir),
            end = inner.calculateEndPadding(dir),
            top = inner.calculateTopPadding() + 4.dp,
            bottom = inner.calculateBottomPadding() + 12.dp,
        )
        Crossfade(targetState = tab, label = "tab") { current ->
            when (current) {
                0 -> DashboardScreen(state, gnss, padding, onRequestLocation)
                1 -> TrackScreen(state, tracks, padding, viewModel::clearTrack)
                else -> SensorsScreen(
                    state, gnss, viewModel.availability, settings, padding,
                    onSettings = viewModel::updateSettings,
                    onRealign = viewModel::realign,
                )
            }
        }
    }
}
