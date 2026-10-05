package com.memur.app

import android.content.Intent
import android.content.pm.PackageManager
import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.content.ContextCompat
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.memur.app.data.RecordingService
import com.memur.app.data.TtsController
import com.memur.app.ui.AppRoot
import com.memur.app.ui.NavBus
import com.memur.app.ui.MemurTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        TtsController.init(this)
        handleIntent(intent)
        setContent {
            val container = (application as MemurApp).container
            val settings by container.settings.flow.collectAsStateWithLifecycle(initialValue = com.memur.app.data.SettingsStore.DEFAULT)
            val dark = when (settings.darkMode) {
                "dark" -> true
                "light" -> false
                else -> androidx.compose.foundation.isSystemInDarkTheme()
            }
            MemurTheme(darkTheme = dark) {
                AppRoot(container)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        intent ?: return
        val data = intent.data
        if (data != null && data.scheme == "memur") {
            val host = data.host ?: return
            val id = data.pathSegments.firstOrNull()
            NavBus.deepLink.value = if (id != null) "$host/$id" else host
            return
        }
        if (intent.getBooleanExtra("from_widget", false)) {
            if (hasAudioPermission()) RecordingService.start(this)
        }
    }

    private fun hasAudioPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
}
