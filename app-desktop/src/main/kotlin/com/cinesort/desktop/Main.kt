package com.cinesort.desktop

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.cinesort.desktop.state.AppState
import com.cinesort.desktop.state.Phase
import com.cinesort.desktop.theme.CineSortTheme
import com.cinesort.desktop.ui.HistoryScreen
import com.cinesort.desktop.ui.PreviewScreen
import com.cinesort.desktop.ui.ReportScreen
import com.cinesort.desktop.ui.RunningScreen
import com.cinesort.desktop.ui.ScanningScreen
import com.cinesort.desktop.ui.SettingsScreen
import com.cinesort.desktop.ui.SetupScreen

/**
 * CineSort Desktop — Direction 2 (Modern Premium): Atelier tokens,
 * card-based preview with a fixed inspector, journaled runs with undo.
 */
fun main() = application {
    val windowState = rememberWindowState(width = 1180.dp, height = 760.dp)
    Window(
        onCloseRequest = ::exitApplication,
        title = "CineSort",
        state = windowState,
    ) {
        val scope = rememberCoroutineScope()
        val state = remember { AppState(scope) }
        CineSortTheme(darkTheme = true) {
            Surface(modifier = Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxSize()) {
                    when (state.phase) {
                        Phase.SETUP -> SetupScreen(state)
                        Phase.SCANNING -> ScanningScreen(state)
                        Phase.PREVIEW -> PreviewScreen(state)
                        Phase.RUNNING -> RunningScreen(state)
                        Phase.REPORT -> ReportScreen(state)
                        Phase.HISTORY -> HistoryScreen(state)
                        Phase.SETTINGS -> SettingsScreen(state)
                    }
                }
            }
        }
    }
}
