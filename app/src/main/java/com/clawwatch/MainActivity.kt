package com.clawwatch

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.wear.ambient.AmbientLifecycleObserver
import com.clawwatch.ui.navigation.NavGraph
import com.clawwatch.ui.theme.ClawWatchTheme

import android.view.WindowManager

class MainActivity : ComponentActivity() {

    /** Exposed so the NavGraph/MainScreen can read ambient state */
    var isAmbient: Boolean = false
        private set

    private val ambientCallback = object : AmbientLifecycleObserver.AmbientLifecycleCallback {
        override fun onEnterAmbient(ambientDetails: AmbientLifecycleObserver.AmbientDetails) {
            isAmbient = true
        }
        override fun onExitAmbient() {
            isAmbient = false
        }
    }

    private lateinit var ambientObserver: AmbientLifecycleObserver

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)

        ambientObserver = AmbientLifecycleObserver(this, ambientCallback)
        lifecycle.addObserver(ambientObserver)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val launchedAsAssistant = intent?.action == Intent.ACTION_ASSIST ||
                intent?.action == "android.intent.action.VOICE_ASSIST"

        setContent {
            ClawWatchTheme {
                NavGraph(launchedAsAssistant = launchedAsAssistant)
            }
        }
    }
}
