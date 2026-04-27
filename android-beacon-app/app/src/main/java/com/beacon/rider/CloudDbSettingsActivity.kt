package com.beacon.rider

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class CloudDbSettingsActivity : AppCompatActivity() {

    private lateinit var hostInput: EditText
    private lateinit var userInput: EditText
    private lateinit var passwordInput: EditText
    private lateinit var dbNameInput: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_cloud_db_settings)

        hostInput = findViewById(R.id.dbHostInput)
        userInput = findViewById(R.id.dbUserInput)
        passwordInput = findViewById(R.id.dbPasswordInput)
        dbNameInput = findViewById(R.id.dbNameInput)

        bindConfig()

        findViewById<Button>(R.id.saveCloudSettingsBtn).setOnClickListener {
            saveSettings()
        }

        findViewById<Button>(R.id.cancelCloudSettingsBtn).setOnClickListener {
            finish()
        }
    }

    private fun bindConfig() {
        val config = ConfigStore.read(this)
        hostInput.setText(config.dbHost)
        userInput.setText(config.dbUser)
        passwordInput.setText(config.dbPassword)
        dbNameInput.setText(config.dbName)
    }

    private fun saveSettings() {
        ConfigStore.updateCloudDb(
            this,
            host = hostInput.text.toString().trim().trimEnd('/'),
            user = userInput.text.toString().trim(),
            password = passwordInput.text.toString().trim(),
            dbName = dbNameInput.text.toString().trim()
        )
        Toast.makeText(this, getString(R.string.settings_saved), Toast.LENGTH_SHORT).show()
        finish()
    }
}
