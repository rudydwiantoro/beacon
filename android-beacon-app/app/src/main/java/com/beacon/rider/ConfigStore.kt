package com.beacon.rider

import android.content.Context

data class BeaconConfig(
    val serverUrl: String,
    val riderId: String,
    val apiKey: String,
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
