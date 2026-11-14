package app.ylite

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import app.ylite.ui.YLiteApp
import app.ylite.ui.YLiteTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private var ready = false

    override fun onCreate(savedInstanceState: Bundle?) {
        // Must be called before super.onCreate: it swaps the splash theme for the app theme.
        val splash = installSplashScreen()
        // Hold the mark for a beat rather than flashing it for one frame. There is no
        // async work to wait for yet; when there is, this is where it would gate.
        splash.setKeepOnScreenCondition { !ready }

        super.onCreate(savedInstanceState)

        lifecycleScope.launch {
            delay(450)
            ready = true
        }

        enableEdgeToEdge()
        setContent {
            YLiteTheme {
                YLiteApp()
            }
        }
    }
}
