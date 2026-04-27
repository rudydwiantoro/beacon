package com.beacon.rider

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class TelegramSettingsActivity : AppCompatActivity() {

    private lateinit var tokenInput: EditText
    private lateinit var chatIdInput: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_telegram_settings)

        tokenInput = findViewById(R.id.telegramTokenInput)
        chatIdInput = findViewById(R.id.telegramChatIdInput)

        bindConfig()

        findViewById<Button>(R.id.saveTelegramSettingsBtn).setOnClickListener {
            saveSettings()
        }

        findViewById<Button>(R.id.cancelTelegramSettingsBtn).setOnClickListener {
            finish()
        }
    }

    private fun bindConfig() {
        val config = ConfigStore.read(this)
        tokenInput.setText(config.telegramBotToken)
        chatIdInput.setText(config.telegramChatId)
    }

    private fun saveSettings() {
        ConfigStore.updateTelegram(
            this,
            botToken = tokenInput.text.toString().trim(),
            chatId = chatIdInput.text.toString().trim()
        )
        Toast.makeText(this, getString(R.string.settings_saved), Toast.LENGTH_SHORT).show()
        finish()
    }
}
