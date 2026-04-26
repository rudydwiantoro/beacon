package com.beacon.rider

import android.util.Log
import com.beacon.rider.ConfigStore
import com.beacon.rider.R
import com.beacon.rider.SettingsActivity
import com.beacon.rider.TrackerService

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var serverUrlInput: EditText
    private lateinit var riderIdInput: EditText
    private lateinit var apiKeyInput: EditText
    private lateinit var statusText: TextView

    private var shouldEnterBackgroundAfterStart = false

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            val granted = result.entries.all { it.value }
            if (granted) {
                startTrackingService()
            } else {
                Toast.makeText(this, getString(R.string.permission_required), Toast.LENGTH_LONG).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        serverUrlInput = findViewById(R.id.serverUrlInput)
        riderIdInput = findViewById(R.id.riderIdInput)
        apiKeyInput = findViewById(R.id.apiKeyInput)
        statusText = findViewById(R.id.statusText)

        val config = ConfigStore.read(this)
        serverUrlInput.setText(config.serverUrl)
        riderIdInput.setText(config.riderId)
        apiKeyInput.setText(config.apiKey)

        findViewById<Button>(R.id.openSettingsBtn).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        findViewById<Button>(R.id.startBtn).setOnClickListener {
            persistBasicInputs()
            shouldEnterBackgroundAfterStart = true
            if (hasLocationPermissions()) {
                startTrackingService()
            } else {
                requestLocationPermissions()
            }
        }

        findViewById<Button>(R.id.stopBtn).setOnClickListener {
            val stopIntent = Intent(this, TrackerService::class.java).apply {
                action = TrackerService.ACTION_STOP
            }
            startService(stopIntent)
            statusText.text = getString(R.string.status_stopped)
            shouldEnterBackgroundAfterStart = false
        }

        refreshStatus()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun persistBasicInputs() {
        ConfigStore.updateBasic(
            this,
            serverUrl = serverUrlInput.text.toString().trim().trimEnd('/'),
            riderId = riderIdInput.text.toString().trim(),
            apiKey = apiKeyInput.text.toString().trim()
        )
    }

    private fun refreshStatus() {
        val isRunning = getSharedPreferences(ConfigStore.PREFS, MODE_PRIVATE)
            .getBoolean(TrackerService.PREF_RUNNING, false)

        statusText.text = if (isRunning) {
            getString(R.string.status_running)
        } else {
            getString(R.string.status_stopped)
        }
    }

    private fun hasLocationPermissions(): Boolean {
        val fine = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        val coarse = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (!fine && !coarse) {
            return false
        }

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_BACKGROUND_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    private fun requestLocationPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            permissions.add(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        }

        permissionLauncher.launch(permissions.toTypedArray())
    }

    private fun startTrackingService() {
        // Guard: if already running, don't send another ACTION_START
        val prefs = getSharedPreferences(ConfigStore.PREFS, MODE_PRIVATE)
        if (prefs.getBoolean(TrackerService.PREF_RUNNING, false)) {
            Log.d("MainActivity", "startTrackingService: already running, skipping")
            return
        }

        val config = ConfigStore.read(this)
        val startIntent = Intent(this, TrackerService::class.java).apply {
            action = TrackerService.ACTION_START
            putExtra(TrackerService.EXTRA_FORCE_ECO, config.forceEcoOnRun)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(startIntent)
        } else {
            startService(startIntent)
        }

        statusText.text = getString(R.string.status_running)

        if (shouldEnterBackgroundAfterStart) {
            moveTaskToBack(true)
            finish()
        }
    }
}
