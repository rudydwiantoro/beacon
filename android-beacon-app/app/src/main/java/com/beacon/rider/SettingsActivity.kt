package com.beacon.rider

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        findViewById<Button>(R.id.openRelayModeSettingsBtn).setOnClickListener {
            startActivity(Intent(this, RelayModeSettingsActivity::class.java))
        }

        findViewById<Button>(R.id.openTelegramSettingsBtn).setOnClickListener {
            startActivity(Intent(this, TelegramSettingsActivity::class.java))
        }

        findViewById<Button>(R.id.openCloudSettingsBtn).setOnClickListener {
            startActivity(Intent(this, CloudDbSettingsActivity::class.java))
        }

        findViewById<Button>(R.id.openTrackingProfileSettingsBtn).setOnClickListener {
            startActivity(Intent(this, TrackingProfileSettingsActivity::class.java))
        }

        findViewById<Button>(R.id.openRelayTestSettingsBtn).setOnClickListener {
            startActivity(Intent(this, RelayTestActivity::class.java))
        }

        findViewById<Button>(R.id.closeSettingsHubBtn).setOnClickListener {
            finish()
        }
    }
}
