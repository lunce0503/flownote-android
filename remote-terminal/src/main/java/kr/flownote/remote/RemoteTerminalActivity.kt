package kr.flownote.remote

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color
import kr.flownote.remote.ui.RemoteApp

class RemoteTerminalActivity : ComponentActivity() {
    val model: RemoteViewModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.rgb(23, 26, 29)))
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(
                primary = Color(0xFF63D4B1), background = Color(0xFF171A1D), surface = Color(0xFF202427),
                secondary = Color(0xFFF2C879), error = Color(0xFFFFB4AB),
            )) { RemoteApp(model, onClose = ::finish) }
        }
    }
    override fun onStart() { super.onStart(); model.foreground() }
    override fun onStop() { model.background(); super.onStop() }
}
