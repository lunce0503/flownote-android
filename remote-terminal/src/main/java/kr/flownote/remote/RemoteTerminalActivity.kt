package kr.flownote.remote

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import kr.flownote.remote.ui.RemoteApp

class RemoteTerminalActivity : ComponentActivity() {
    val model: RemoteViewModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val ivory = android.graphics.Color.rgb(247, 243, 232)
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, ivory),
            navigationBarStyle = SystemBarStyle.light(ivory, ivory))
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(
                primary = Color(0xFF211F1B), onPrimary = Color(0xFFFFFFFF),
                primaryContainer = Color(0xFFE9E0CE), onPrimaryContainer = Color(0xFF211F1B),
                secondary = Color(0xFF5F5545), secondaryContainer = Color(0xFFE9E0CE),
                onSecondaryContainer = Color(0xFF211F1B),
                background = Color(0xFFF7F3E8), onBackground = Color(0xFF171714),
                surface = Color(0xFFFFFFFF), onSurface = Color(0xFF171714),
                surfaceTint = Color(0xFF211F1B), surfaceVariant = Color(0xFFF0EBDF),
                onSurfaceVariant = Color(0xFF5F5545), outline = Color(0xFF8C8271),
                outlineVariant = Color(0xFFDED9CC), surfaceContainer = Color(0xFFF5F1E8),
                surfaceContainerHigh = Color(0xFFF0EBDF),
            )) { RemoteApp(model, onClose = ::finish) }
        }
    }
    override fun onStart() { super.onStart(); model.foreground() }
    override fun onStop() { model.background(); super.onStop() }
}
