package com.beacon.rider

import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.Switch
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class SettingsActivity : AppCompatActivity() {

    private lateinit var modeSpinner: Spinner
    private lateinit var intervalInput: EditText
    private lateinit var minDistanceInput: EditText
    private lateinit var lowBatteryThresholdInput: EditText
    private lateinit var lowBatteryIntervalInput: EditText
    private lateinit var forceEcoSwitch: Switch

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

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

        findViewById<Button>(R.id.saveSettingsBtn).setOnClickListener {
            saveSettings()
        }

        findViewById<Button>(R.id.cancelSettingsBtn).setOnClickListener {
            finish()
        }
    }

    private fun bindConfig() {
        val config = ConfigStore.read(this)

        modeSpinner.setSelection(
            when (config.mode) {
                "balanced" -> 1
                "live" -> 2
                else -> 0
            }
        )
        intervalInput.setText(config.customIntervalSec.toString())
        minDistanceInput.setText(config.minDistanceMeters.toString())
        lowBatteryThresholdInput.setText(config.lowBatteryThreshold.toString())
        lowBatteryIntervalInput.setText(config.lowBatteryIntervalSec.toString())
        forceEcoSwitch.isChecked = config.forceEcoOnRun
    }

    private fun saveSettings() {
        val mode = when (modeSpinner.selectedItemPosition) {
            1 -> "balanced"
            2 -> "live"
            else -> "eco"
        }

        val customIntervalSec = intervalInput.text.toString().trim().toIntOrNull() ?: 0
        val minDistanceMeters = minDistanceInput.text.toString().trim().toFloatOrNull() ?: 25f
        val lowBatteryThreshold = lowBatteryThresholdInput.text.toString().trim().toIntOrNull() ?: 20
        val lowBatteryIntervalSec = lowBatteryIntervalInput.text.toString().trim().toIntOrNull() ?: 300

        ConfigStore.updateAdvanced(
            this,
            mode = mode,
            customIntervalSec = customIntervalSec.coerceAtLeast(0),
            minDistanceMeters = minDistanceMeters.coerceAtLeast(0f),
            lowBatteryThreshold = lowBatteryThreshold.coerceIn(1, 100),
            lowBatteryIntervalSec = lowBatteryIntervalSec.coerceAtLeast(30),
            forceEcoOnRun = forceEcoSwitch.isChecked
        )

        Toast.makeText(this, getString(R.string.settings_saved), Toast.LENGTH_SHORT).show()
        finish()
    }
}
