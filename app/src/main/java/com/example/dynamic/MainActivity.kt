package com.example.dynamic

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

class MainActivity : ComponentActivity() {

    private val prefsName = "dynamic_prefs"
    private val keyPulseEnabled = "notification_pulse_enabled"
    private val keyVisualizerMode = "visualizer_mode"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            var overlayEnabled by remember { mutableStateOf(OverlayService.isRunning) }
            var pulseEnabled by remember {
                mutableStateOf(getSharedPreferences(prefsName, MODE_PRIVATE).getBoolean(keyPulseEnabled, true))
            }
            var isWaveVisualizer by remember {
                mutableStateOf(
                    getSharedPreferences(prefsName, MODE_PRIVATE)
                        .getString(keyVisualizerMode, "BAR") == "WAVE"
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
                    .padding(24.dp),
                verticalArrangement = Arrangement.SpaceBetween,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "Dynamic Island",
                        color = Color.White,
                        fontSize = 24.sp
                    )
                    Spacer(modifier = Modifier.height(32.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Dynamic Island Aç/Kapat",
                            color = Color.White,
                            fontSize = 16.sp
                        )
                        Switch(
                            checked = overlayEnabled,
                            onCheckedChange = { enabled ->
                                overlayEnabled = enabled
                                vibrateOnce()

                                if (enabled) {
                                    if (!Settings.canDrawOverlays(this@MainActivity)) {
                                        overlayEnabled = false
                                        val intent = Intent(
                                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                            Uri.parse("package:$packageName")
                                        )
                                        startActivity(intent)
                                        return@Switch
                                    }
                                    runCatching {
                                        val serviceIntent = Intent(this@MainActivity, OverlayService::class.java)
                                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                            startForegroundService(serviceIntent)
                                        } else {
                                            startService(serviceIntent)
                                        }
                                    }.onFailure {
                                        overlayEnabled = false
                                    }
                                } else {
                                    runCatching {
                                        stopService(Intent(this@MainActivity, OverlayService::class.java))
                                    }
                                }
                            }
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Anlık Bildirim Animasyonu",
                            color = Color.White,
                            fontSize = 16.sp
                        )
                        Switch(
                            checked = pulseEnabled,
                            onCheckedChange = { enabled ->
                                pulseEnabled = enabled
                                vibrateOnce()
                                getSharedPreferences(prefsName, MODE_PRIVATE)
                                    .edit()
                                    .putBoolean(keyPulseEnabled, enabled)
                                    .apply()

                                sendBroadcast(
                                    Intent("PULSE_TOGGLE")
                                        .putExtra("enabled", enabled)
                                )
                            }
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Visualizer Dalga Modu",
                            color = Color.White,
                            fontSize = 16.sp
                        )
                        Switch(
                            checked = isWaveVisualizer,
                            onCheckedChange = { waveEnabled ->
                                isWaveVisualizer = waveEnabled
                                vibrateOnce()

                                val mode = if (waveEnabled) "WAVE" else "BAR"
                                getSharedPreferences(prefsName, MODE_PRIVATE)
                                    .edit()
                                    .putString(keyVisualizerMode, mode)
                                    .apply()

                                sendBroadcast(
                                    Intent("VISUALIZER_MODE_UPDATE")
                                        .putExtra("mode", mode)
                                )
                            }
                        )
                    }
                }

                Text(
                    text = stringResource(R.string.developer_credit),
                    color = Color(0xFF9AA0A6),
                    fontSize = 12.sp
                )
            }
        }
    }

    private fun vibrateOnce() {
        val durationMs = 40L
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            vibratorManager?.defaultVibrator?.vibrate(
                VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE)
            )
        } else {
            @Suppress("DEPRECATION")
            val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            @Suppress("DEPRECATION")
            vibrator?.vibrate(durationMs)
        }
    }
}