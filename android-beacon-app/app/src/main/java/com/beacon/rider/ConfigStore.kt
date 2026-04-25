package com.beacon.rider

import android.content.Context

data class BeaconConfig(
    val serverUrl: String,
    val riderId: String,
    val apiKey: String,
    val mode: String
)

object ConfigStore {
    const val PREFS = "beacon_config"

    private const val KEY_SERVER_URL = "server_url"
    private const val KEY_RIDER_ID = "rider_id"
    private const val KEY_API_KEY = "api_key"
    private const val KEY_MODE = "mode"

    fun read(context: Context): BeaconConfig {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return BeaconConfig(
            serverUrl = prefs.getString(KEY_SERVER_URL, "") ?: "",
            riderId = prefs.getString(KEY_RIDER_ID, "") ?: "",
            apiKey = prefs.getString(KEY_API_KEY, "") ?: "",
            mode = prefs.getString(KEY_MODE, "eco") ?: "eco"
        )
    }

    fun write(
        context: Context,
        serverUrl: String,
        riderId: String,
        apiKey: String,
        mode: String
    ) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_SERVER_URL, serverUrl)
            .putString(KEY_RIDER_ID, riderId)
            .putString(KEY_API_KEY, apiKey)
            .putString(KEY_MODE, mode)
            .apply()
    }
}
