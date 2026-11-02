package com.iceman.ice

import android.app.Application
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.iceman.ice.data.AppState
import com.iceman.ice.player.Playback
import com.iceman.ice.ui.IceRoot
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class IceApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppState.init(this)
        Playback.init(this)
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { IceRoot() }

        // Full-screen player: landscape, hide the system bars, keep the screen on.
        lifecycleScope.launch {
            combine(Playback.showFull, Playback.playing) { full, playing -> full to playing }.collect { (full, playing) ->
                val ctl = WindowCompat.getInsetsController(window, window.decorView)
                if (full) {
                    requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                    ctl.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    ctl.hide(WindowInsetsCompat.Type.systemBars())
                } else {
                    requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                    ctl.show(WindowInsetsCompat.Type.systemBars())
                }
                if (playing) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
    }

    override fun onStop() {
        super.onStop()
        // Leaving the app: pause (no background video).
        // (Keeps playing in the car, where the phone screen is usually off.)
        if (!isChangingConfigurations && !Playback.carConnected.value) Playback.pause()
    }
}
