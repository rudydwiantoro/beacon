package com.beacon.rider

import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.Switch
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class TrackingProfileSettingsActivity : AppCompatActivity() {

    private lateinit var modeSpinner: Spinner
    private lateinit var intervalInput: EditText
    private lateinit var minDistanceInput: EditText
    private lateinit var lowBatteryThresholdInput: EditText
    private lateinit var lowBatteryIntervalInput: EditText
    private lateinit var forceEcoSwitch: Switch

    companion object {
        private const val KM_TO_METERS = 1000f
        private const val DEFAULT_MIN_DISTANCE_KM = 5f
        private const val LEGACY_MAX_DISTANCE_METERS = 999f
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_tracking_profile_settings)

        modeSpinner = findViewById(R.id.modeSpinner)
        intervalInput = findViewById(R.id.intervalInput)
        minDistanceInput = findViewById(R.id.minDistanceInput)
        lowBatteryThresholdInput = findViewById(R.id.lowBatteryThresholdInput)
        lowBatteryIntervalInput = findViewById(R.id.lowBatteryIntervalInput)
        forceEcoSwitch = findViewById(R.id.forceEcoSwitch)

        val modeAdapter = ArrayAdapter.createFromResource(
            this,
            R.array.tracking_modes,
            android.R.layout.simple_spinner_item
        )
        modeAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        modeSpinner.adapter = modeAdapter

        bindConfig()

        findViewById<Button>(R.id.saveTrackingProfileSettingsBtn).setOnClickListener {
            saveSettings()
        }

        findViewById<Button>(R.id.cancelTrackingProfileSettingsBtn).setOnClickListener {
            finish()
        }
    }

    private fun bindConfig() {
        val config = normalizeLegacyMinDistance(ConfigStore.read(this))

        modeSpinner.setSelection(
            when (config.mode) {
                "balanced" -> 1
                "live" -> 2
                else -> 0
            }
        )
        intervalInput.setText(config.customIntervalSec.toString())
        val minDistanceKm = config.minDistanceMeters / KM_TO_METERS
        minDistanceInput.setText(
            if (minDistanceKm % 1f == 0f) {
                minDistanceKm.toInt().toString()
            } else {
                minDistanceKm.toString()
            }
        )
        lowBatteryThresholdInput.setText(config.lowBatteryThreshold.toString())
        lowBatteryIntervalInput.setText(config.lowBatteryIntervalSec.toString())
        forceEcoSwitch.isChecked = config.forceEcoOnRun
    }

    private fun normalizeLegacyMinDistance(config: BeaconConfig): BeaconConfig {
        if (config.minDistanceMeters in 0.001f..LEGACY_MAX_DISTANCE_METERS) {
            val normalizedConfig = config.copy(
                minDistanceMeters = DEFAULT_MIN_DISTANCE_KM * KM_TO_METERS
            )
            ConfigStore.write(this, normalizedConfig)
            return normalizedConfig
        }
        return config
    }

    private fun saveSettings() {
        val mode = when (modeSpinner.selectedItemPosition) {
            1 -> "balanced"
            2 -> "live"
            else -> "eco"
        }

        val customIntervalSec = intervalInput.text.toString().trim().toIntOrNull() ?: 0
        val minDistanceKm = minDistanceInput.text.toString().trim().toFloatOrNull() ?: DEFAULT_MIN_DISTANCE_KM
        val minDistanceMeters = minDistanceKm.coerceAtLeast(0f) * KM_TO_METERS
        val lowBatteryThreshold = lowBatteryThresholdInput.text.toString().trim().toIntOrNull() ?: 20
        val lowBatteryIntervalSec = lowBatteryIntervalInput.text.toString().trim().toIntOrNull() ?: 300

        ConfigStore.updateAdvanced(
            this,
            mode = mode,
            customIntervalSec = customIntervalSec.coerceAtLeast(0),
            minDistanceMeters = minDistanceMeters,
            lowBatteryThreshold = lowBatteryThreshold.coerceIn(1, 100),
            lowBatteryIntervalSec = lowBatteryIntervalSec.coerceAtLeast(30),
            forceEcoOnRun = forceEcoSwitch.isChecked
        )

        Toast.makeText(this, getString(R.string.settings_saved), Toast.LENGTH_SHORT).show()
        finish()
    }
}
