package com.beacon.rider

import android.os.Bundle
import android.widget.Button
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class RelayModeSettingsActivity : AppCompatActivity() {

    private lateinit var relayModeGroup: RadioGroup

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_relay_mode_settings)

        relayModeGroup = findViewById(R.id.relayModeGroup)

        bindConfig()

        findViewById<Button>(R.id.saveRelayModeBtn).setOnClickListener {
            saveRelayMode()
        }

        findViewById<Button>(R.id.cancelRelayModeBtn).setOnClickListener {
            finish()
        }
    }

    private fun bindConfig() {
        val config = ConfigStore.read(this)
        val targetId = if (config.relayMode == "cloud") {
            R.id.relayModeCloud
        } else {
            R.id.relayModeTelegram
        }
        relayModeGroup.check(targetId)
    }

    private fun saveRelayMode() {
        val checkedId = relayModeGroup.checkedRadioButtonId
        val relayMode = findViewById<RadioButton>(checkedId).tag?.toString() ?: "telegram"

        ConfigStore.updateRelayMode(this, relayMode)
        Toast.makeText(this, getString(R.string.settings_saved), Toast.LENGTH_SHORT).show()
        finish()
    }
}
