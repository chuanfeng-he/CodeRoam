package com.local.codexremote

import android.graphics.Color as AndroidColor
import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.local.codexremote.ui.CodexRemoteApp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureSystemBars()
        setContent {
            runCatching {
                CodexRemoteApp()
            }.onFailure {
                StartupErrorScreen(it)
            }
        }
    }

    private fun configureSystemBars() {
        val shellColor = AndroidColor.rgb(0xFB, 0xFB, 0xF8)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(shellColor, shellColor),
            navigationBarStyle = SystemBarStyle.light(shellColor, shellColor)
        )
    }
}

@Composable
private fun StartupErrorScreen(error: Throwable) {
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFFF7F7F4)) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text("CodeRoam 启动失败", style = MaterialTheme.typography.titleLarge)
                Text(
                    error.stackTraceToString().take(4_000),
                    modifier = Modifier.padding(top = 12.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF7A1F1F)
                )
            }
        }
    }
}
