package com.beacon.rider

import android.content.Context

data class BeaconConfig(
    val serverUrl: String,
    val riderId: String,
    val apiKey: String,
    val relayMode: String,
    val telegramBotToken: String,
    val telegramChatId: String,
    val dbHost: String,
    val dbUser: String,
    val dbPassword: String,
    val dbName: String,
    val mode: String,
    val customIntervalSec: Int,
    val minDistanceMeters: Float,
    val lowBatteryThreshold: Int,
    val lowBatteryIntervalSec: Int,
    val forceEcoOnRun: Boolean
)

object ConfigStore {
    const val PREFS = "beacon_config"

    private const val KEY_SERVER_URL = "server_url"
    private const val KEY_RIDER_ID = "rider_id"
    private const val KEY_API_KEY = "api_key"
    private const val KEY_RELAY_MODE = "relay_mode"
    private const val KEY_TELEGRAM_BOT_TOKEN = "telegram_bot_token"
    private const val KEY_TELEGRAM_CHAT_ID = "telegram_chat_id"
    private const val KEY_DB_HOST = "db_host"
    private const val KEY_DB_USER = "db_user"
    private const val KEY_DB_PASSWORD = "db_password"
    private const val KEY_DB_NAME = "db_name"
    private const val KEY_MODE = "mode"
    private const val KEY_CUSTOM_INTERVAL_SEC = "custom_interval_sec"
    private const val KEY_MIN_DISTANCE_METERS = "min_distance_meters"
    private const val KEY_LOW_BATTERY_THRESHOLD = "low_battery_threshold"
    private const val KEY_LOW_BATTERY_INTERVAL_SEC = "low_battery_interval_sec"
    private const val KEY_FORCE_ECO_ON_RUN = "force_eco_on_run"

    fun read(context: Context): BeaconConfig {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return BeaconConfig(
            serverUrl = prefs.getString(KEY_SERVER_URL, "") ?: "",
            riderId = prefs.getString(KEY_RIDER_ID, "") ?: "",
            apiKey = prefs.getString(KEY_API_KEY, "") ?: "",
            relayMode = prefs.getString(KEY_RELAY_MODE, "telegram") ?: "telegram",
            telegramBotToken = prefs.getString(KEY_TELEGRAM_BOT_TOKEN, "") ?: "",
            telegramChatId = prefs.getString(KEY_TELEGRAM_CHAT_ID, "") ?: "",
            dbHost = prefs.getString(KEY_DB_HOST, "") ?: "",
            dbUser = prefs.getString(KEY_DB_USER, "") ?: "",
            dbPassword = prefs.getString(KEY_DB_PASSWORD, "") ?: "",
            dbName = prefs.getString(KEY_DB_NAME, "") ?: "",
            mode = prefs.getString(KEY_MODE, "eco") ?: "eco",
            customIntervalSec = prefs.getInt(KEY_CUSTOM_INTERVAL_SEC, 0),
            minDistanceMeters = prefs.getFloat(KEY_MIN_DISTANCE_METERS, 5000f),
            lowBatteryThreshold = prefs.getInt(KEY_LOW_BATTERY_THRESHOLD, 20),
            lowBatteryIntervalSec = prefs.getInt(KEY_LOW_BATTERY_INTERVAL_SEC, 300),
            forceEcoOnRun = prefs.getBoolean(KEY_FORCE_ECO_ON_RUN, false)
        )
    }

    fun write(
        context: Context,
        config: BeaconConfig
    ) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_SERVER_URL, config.serverUrl)
            .putString(KEY_RIDER_ID, config.riderId)
            .putString(KEY_API_KEY, config.apiKey)
            .putString(KEY_RELAY_MODE, config.relayMode)
            .putString(KEY_TELEGRAM_BOT_TOKEN, config.telegramBotToken)
            .putString(KEY_TELEGRAM_CHAT_ID, config.telegramChatId)
            .putString(KEY_DB_HOST, config.dbHost)
            .putString(KEY_DB_USER, config.dbUser)
            .putString(KEY_DB_PASSWORD, config.dbPassword)
            .putString(KEY_DB_NAME, config.dbName)
            .putString(KEY_MODE, config.mode)
            .putInt(KEY_CUSTOM_INTERVAL_SEC, config.customIntervalSec)
            .putFloat(KEY_MIN_DISTANCE_METERS, config.minDistanceMeters)
            .putInt(KEY_LOW_BATTERY_THRESHOLD, config.lowBatteryThreshold)
            .putInt(KEY_LOW_BATTERY_INTERVAL_SEC, config.lowBatteryIntervalSec)
            .putBoolean(KEY_FORCE_ECO_ON_RUN, config.forceEcoOnRun)
            .apply()
    }

    fun updateBasic(
        context: Context,
        serverUrl: String,
        riderId: String,
        apiKey: String
    ) {
        val current = read(context)
        write(
            context,
            current.copy(
                serverUrl = serverUrl,
                riderId = riderId,
                apiKey = apiKey
            )
        )
    }

    fun updateRelayMode(
        context: Context,
        relayMode: String
    ) {
        val current = read(context)
        write(
            context,
            current.copy(
                relayMode = relayMode
            )
        )
    }

    fun updateTelegram(
        context: Context,
        botToken: String,
        chatId: String
    ) {
        val current = read(context)
        write(
            context,
            current.copy(
                telegramBotToken = botToken,
                telegramChatId = chatId
            )
        )
    }

    fun updateCloudDb(
        context: Context,
        host: String,
        user: String,
        password: String,
        dbName: String
    ) {
        val current = read(context)
        write(
            context,
            current.copy(
                dbHost = host,
                dbUser = user,
                dbPassword = password,
                dbName = dbName
            )
        )
    }

    fun updateAdvanced(
        context: Context,
        mode: String,
        customIntervalSec: Int,
        minDistanceMeters: Float,
        lowBatteryThreshold: Int,
        lowBatteryIntervalSec: Int,
        forceEcoOnRun: Boolean
    ) {
        val current = read(context)
        write(
            context,
            current.copy(
                mode = mode,
                customIntervalSec = customIntervalSec,
                minDistanceMeters = minDistanceMeters,
                lowBatteryThreshold = lowBatteryThreshold,
                lowBatteryIntervalSec = lowBatteryIntervalSec,
                forceEcoOnRun = forceEcoOnRun
            )
        )
    }
}
