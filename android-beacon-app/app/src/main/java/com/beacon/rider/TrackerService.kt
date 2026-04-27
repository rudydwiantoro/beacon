package com.beacon.rider

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.IntentFilter
import android.location.Location
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.annotation.RequiresPermission
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.lang.Exception
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.max

class TrackerService : Service() {

    private lateinit var fusedClient: FusedLocationProviderClient
    private lateinit var http: OkHttpClient

    private var locationCallback: LocationCallback? = null

    private val cloudExecutor = Executors.newSingleThreadExecutor()
    private var relayModeOnStart: String = "telegram"
    private var cachedBeaconId: String? = null
    private var cachedTripId: String? = null
    private var cachedNextSeq: Int = 1

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate")
        fusedClient = LocationServices.getFusedLocationProviderClient(this)
        http = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .build()

        createChannel()
        startForeground(NOTIFICATION_ID, buildNotification("initialising"))
        Log.d(TAG, "startForeground called")
    }

    @RequiresPermission(allOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION])
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand action=${intent?.action}")
        when (intent?.action) {
            ACTION_STOP -> {
                Log.d(TAG, "ACTION_STOP received")
                stopTracking()
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_START -> {
                val config = ConfigStore.read(this)
                Log.d(TAG, "ACTION_START — riderId='${config.riderId}' serverUrl='${config.serverUrl}'")
                if (config.riderId.isBlank()) {
                    Log.w(TAG, "riderId is blank — stopping service")
                    stopSelf()
                    return START_NOT_STICKY
                }

                relayModeOnStart = config.relayMode
                cachedBeaconId = null
                cachedTripId = null
                cachedNextSeq = 1

                val forceEco = intent.getBooleanExtra(EXTRA_FORCE_ECO, false)
                val requestProfile = resolveProfile(config, forceEco)
                Log.d(TAG, "Resolved profile: ${requestProfile.profileName}, interval=${requestProfile.intervalMs}ms")

                startForeground(NOTIFICATION_ID, buildNotification("${requestProfile.profileName} / ${relayModeOnStart}"))
                startTracking(config, requestProfile)
                return START_STICKY
            }

            else -> {
                Log.w(TAG, "Unknown or null action — doing nothing")
                return START_STICKY
            }
        }
    }

    @RequiresPermission(allOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION])
    private fun startTracking(config: BeaconConfig, profile: TrackingProfile) {
        locationCallback?.let { fusedClient.removeLocationUpdates(it) }
        locationCallback = null

        val request = buildLocationRequest(profile, config.minDistanceMeters)

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val location = result.lastLocation ?: return
                pushLocation(config, location, profile.profileName)
            }
        }

        try {
            fusedClient.requestLocationUpdates(
                request,
                locationCallback as LocationCallback,
                Looper.getMainLooper()
            )
            markRunning(true)
            Log.d(
                TAG,
                "Started tracking with profile: ${profile.profileName}, interval: ${profile.intervalMs}ms, priority: ${profile.priority}"
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start location updates", e)
            stopTracking()
            stopSelf()
        }
    }

    private fun stopTracking() {
        Log.d(TAG, "stopTracking called")
        locationCallback?.let {
            fusedClient.removeLocationUpdates(it)
        }
        locationCallback = null
        markRunning(false)
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun pushLocation(config: BeaconConfig, location: Location, profileName: String) {
        val batteryPct = currentBatteryPercent()
        val timestamp = Instant.ofEpochMilli(location.time).toString()

        if (relayModeOnStart == "cloud") {
            pushCloudPoint(config, location, profileName, batteryPct, timestamp)
        } else {
            pushTelegram(config, location, profileName, batteryPct, timestamp)
        }
    }

    private fun pushTelegram(
        config: BeaconConfig,
        location: Location,
        profileName: String,
        batteryPct: Int,
        timestamp: String
    ) {
        val token = if (config.telegramBotToken.isNotBlank()) {
            config.telegramBotToken
        } else {
            BuildConfig.TELEGRAM_BOT_TOKEN
        }
        val chatId = if (config.telegramChatId.isNotBlank()) {
            config.telegramChatId
        } else {
            BuildConfig.TELEGRAM_CHANNEL_ID
        }

        if (token.isBlank() || chatId.isBlank()) {
            Log.w(TAG, "Telegram mode active but token/chat_id not configured")
            return
        }

        val batteryStr = if (batteryPct >= 0) "$batteryPct%" else "unknown"
        val mapsUrl = "https://maps.google.com/?q=${location.latitude},${location.longitude}"
        val speedKmh = (location.speed * 3.6f).toInt()

        val text = """
            Beacon Rider Update
            Rider: `${config.riderId}`
            Open in Maps: $mapsUrl
            Accuracy: ${location.accuracy.toInt()} m
            Speed: $speedKmh km/h
            Bearing: ${location.bearing.toInt()}°
            Battery: $batteryStr
            Mode: `$profileName`
            Time: `$timestamp`
        """.trimIndent()

        val payload = JSONObject()
            .put("chat_id", chatId)
            .put("text", text)
            .put("parse_mode", "Markdown")
            .put("disable_web_page_preview", false)

        val body = payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder()
            .url("https://api.telegram.org/bot$token/sendMessage")
            .post(body)
            .build()

        http.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                Log.e(TAG, "Telegram push failed", e)
            }

            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                Log.d(TAG, "Telegram push response: ${response.code} — ${response.message}")
                response.close()
            }
        })
    }

    private fun pushCloudPoint(
        config: BeaconConfig,
        location: Location,
        profileName: String,
        batteryPct: Int,
        timestamp: String
    ) {
        cloudExecutor.execute {
            try {
                if (config.dbHost.isBlank() || config.dbPassword.isBlank()) {
                    Log.w(TAG, "Cloud mode active but DB host/password is empty")
                    return@execute
                }

                val beaconId = ensureBeaconId(config)
                val tripId = ensureInProgressTripId(config, beaconId, location, timestamp)
                if (cachedTripId != tripId) {
                    cachedTripId = tripId
                    cachedNextSeq = fetchLatestSeq(config, tripId) + 1
                }

                insertTripPoint(
                    config = config,
                    beaconId = beaconId,
                    tripId = tripId,
                    seq = cachedNextSeq,
                    location = location,
                    profileName = profileName,
                    batteryPct = batteryPct,
                    timestamp = timestamp
                )
                cachedNextSeq += 1
            } catch (e: Exception) {
                Log.e(TAG, "Cloud insert failed", e)
            }
        }
    }

    private fun ensureBeaconId(config: BeaconConfig): String {
        cachedBeaconId?.let { return it }

        val beaconCode = config.riderId
        val queryUrl = restUrl(config, "beacons")
            ?.newBuilder()
            ?.addQueryParameter("select", "id")
            ?.addQueryParameter("beacon_code", "eq.$beaconCode")
            ?.addQueryParameter("limit", "1")
            ?.build()
            ?: throw IllegalStateException("Invalid DB host URL")

        val getReq = supabaseRequestBuilder(config, queryUrl)
            .get()
            .build()

        val getBody = executeForBody(getReq)
        val found = JSONArray(getBody)
        if (found.length() > 0) {
            val id = found.getJSONObject(0).optString("id")
            if (id.isNotBlank()) {
                cachedBeaconId = id
                return id
            }
        }

        val payload = JSONObject()
            .put("beacon_code", beaconCode)
            .put("name", beaconCode)
            .put("metadata", JSONObject()
                .put("db_user", config.dbUser)
                .put("db_name", config.dbName)
            )
        val createReq = supabaseRequestBuilder(config, restUrl(config, "beacons")!!)
            .header("Prefer", "return=representation")
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        val createBody = executeForBody(createReq)
        val created = JSONArray(createBody)
        if (created.length() == 0) throw IllegalStateException("Failed to create beacon")

        val newId = created.getJSONObject(0).optString("id")
        if (newId.isBlank()) throw IllegalStateException("Created beacon has empty id")

        cachedBeaconId = newId
        return newId
    }

    private fun ensureInProgressTripId(
        config: BeaconConfig,
        beaconId: String,
        location: Location,
        timestamp: String
    ): String {
        cachedTripId?.let { return it }

        val queryUrl = restUrl(config, "trips")
            ?.newBuilder()
            ?.addQueryParameter("select", "id")
            ?.addQueryParameter("beacon_id", "eq.$beaconId")
            ?.addQueryParameter("status", "eq.in_progress")
            ?.addQueryParameter("order", "started_at.desc")
            ?.addQueryParameter("limit", "1")
            ?.build()
            ?: throw IllegalStateException("Invalid DB host URL")

        val getReq = supabaseRequestBuilder(config, queryUrl)
            .get()
            .build()

        val getBody = executeForBody(getReq)
        val found = JSONArray(getBody)
        if (found.length() > 0) {
            val id = found.getJSONObject(0).optString("id")
            if (id.isNotBlank()) {
                cachedTripId = id
                return id
            }
        }

        val payload = JSONObject()
            .put("beacon_id", beaconId)
            .put("started_at", timestamp)
            .put("status", "in_progress")
            .put("start_lat", location.latitude)
            .put("start_lon", location.longitude)
        val createReq = supabaseRequestBuilder(config, restUrl(config, "trips")!!)
            .header("Prefer", "return=representation")
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        val createBody = executeForBody(createReq)
        val created = JSONArray(createBody)
        if (created.length() == 0) throw IllegalStateException("Failed to create trip")

        val newId = created.getJSONObject(0).optString("id")
        if (newId.isBlank()) throw IllegalStateException("Created trip has empty id")

        cachedTripId = newId
        return newId
    }

    private fun fetchLatestSeq(config: BeaconConfig, tripId: String): Int {
        val queryUrl = restUrl(config, "trip_points")
            ?.newBuilder()
            ?.addQueryParameter("select", "seq")
            ?.addQueryParameter("trip_id", "eq.$tripId")
            ?.addQueryParameter("order", "seq.desc")
            ?.addQueryParameter("limit", "1")
            ?.build()
            ?: return 0

        val getReq = supabaseRequestBuilder(config, queryUrl)
            .get()
            .build()

        val body = executeForBody(getReq)
        val arr = JSONArray(body)
        if (arr.length() == 0) return 0
        return arr.getJSONObject(0).optInt("seq", 0)
    }

    private fun insertTripPoint(
        config: BeaconConfig,
        beaconId: String,
        tripId: String,
        seq: Int,
        location: Location,
        profileName: String,
        batteryPct: Int,
        timestamp: String
    ) {
        val payload = JSONObject()
            .put("trip_id", tripId)
            .put("beacon_id", beaconId)
            .put("seq", seq)
            .put("recorded_at", timestamp)
            .put("latitude", location.latitude)
            .put("longitude", location.longitude)
            .put("altitude_m", location.altitude)
            .put("speed_mps", location.speed.toDouble())
            .put("heading_deg", location.bearing.toDouble())
            .put("accuracy_m", location.accuracy.toDouble())
            .put("battery_pct", if (batteryPct >= 0) batteryPct else JSONObject.NULL)
            .put("event_type", "track")
            .put("payload", JSONObject()
                .put("profile", profileName)
                .put("relay_mode", relayModeOnStart)
                .put("db_name", config.dbName)
                .put("db_user", config.dbUser)
            )

        val request = supabaseRequestBuilder(config, restUrl(config, "trip_points")!!)
            .header("Prefer", "return=minimal")
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        try {
            executeWithoutBody(request)
        } catch (e: IllegalStateException) {
            if (e.message?.contains("409") == true || e.message?.contains("duplicate") == true) {
                cachedNextSeq = fetchLatestSeq(config, tripId) + 1
                val retryPayload = JSONObject(payload.toString()).put("seq", cachedNextSeq)
                val retryReq = supabaseRequestBuilder(config, restUrl(config, "trip_points")!!)
                    .header("Prefer", "return=minimal")
                    .post(retryPayload.toString().toRequestBody(JSON_MEDIA_TYPE))
                    .build()
                executeWithoutBody(retryReq)
            } else {
                throw e
            }
        }
    }

    private fun restUrl(config: BeaconConfig, table: String) =
        "${config.dbHost}/rest/v1/$table".toHttpUrlOrNull()

    private fun supabaseRequestBuilder(config: BeaconConfig, url: okhttp3.HttpUrl): Request.Builder {
        val apiKey = config.dbPassword
        return Request.Builder()
            .url(url)
            .header("apikey", apiKey)
            .header("Authorization", "Bearer $apiKey")
            .header("Accept", "application/json")
            .header("Content-Type", "application/json")
    }

    private fun executeForBody(request: Request): String {
        http.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IllegalStateException("HTTP ${response.code}: $body")
            }
            return if (body.isBlank()) "[]" else body
        }
    }

    private fun executeWithoutBody(request: Request) {
        http.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IllegalStateException("HTTP ${response.code}: $body")
            }
        }
    }

    private fun resolveProfile(config: BeaconConfig, forceEco: Boolean): TrackingProfile {
        val batteryPct = currentBatteryPercent()
        val mode = if (forceEco) "eco" else config.mode

        val defaultIntervalMs = when (mode) {
            "live" -> 20_000L
            "balanced" -> 60_000L
            else -> 600_000L
        }

        val customIntervalMs = config.customIntervalSec
            .takeIf { it > 0 }
            ?.times(1000L)
            ?: defaultIntervalMs

        val lowBatteryIntervalMs = max(config.lowBatteryIntervalSec, 30) * 1000L
        val isLowBattery = batteryPct in 1..config.lowBatteryThreshold
        val finalIntervalMs = if (isLowBattery) {
            max(customIntervalMs, lowBatteryIntervalMs)
        } else {
            customIntervalMs
        }

        val priority = if (forceEco) {
            Priority.PRIORITY_LOW_POWER
        } else {
            when (mode) {
                "live" -> Priority.PRIORITY_HIGH_ACCURACY
                "balanced" -> Priority.PRIORITY_BALANCED_POWER_ACCURACY
                else -> Priority.PRIORITY_LOW_POWER
            }
        }

        val profileName = if (forceEco) "run-eco" else mode
        return TrackingProfile(
            profileName = profileName,
            intervalMs = finalIntervalMs,
            priority = priority
        )
    }

    private fun buildLocationRequest(profile: TrackingProfile, minDistanceMeters: Float): LocationRequest {
        return LocationRequest.Builder(profile.priority, profile.intervalMs)
            .setMinUpdateIntervalMillis(max(5_000L, profile.intervalMs / 2))
            .setMaxUpdateDelayMillis(profile.intervalMs)
            .setMinUpdateDistanceMeters(minDistanceMeters.coerceAtLeast(0f))
            .setWaitForAccurateLocation(false)
            .build()
    }

    private fun buildNotification(profileName: String): Notification {

        val openIntent = PendingIntent.getActivity(
            this,
            10,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Beacon Rider")
            .setContentText("Tracking profile: $profileName")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return

        val channel = NotificationChannel(
            CHANNEL_ID,
            "Beacon Tracking",
            NotificationManager.IMPORTANCE_LOW
        )
        manager.createNotificationChannel(channel)
    }

    private fun markRunning(running: Boolean) {
        getSharedPreferences(ConfigStore.PREFS, MODE_PRIVATE)
            .edit()
            .putBoolean(PREF_RUNNING, running)
            .apply()
    }

    private fun currentBatteryPercent(): Int {
        val batteryStatus = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        if (level <= 0 || scale <= 0) return -1
        return ((level / scale.toFloat()) * 100).toInt()
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy")
        stopTracking()
        cloudExecutor.shutdownNow()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "com.beacon.rider.action.START"
        const val ACTION_STOP = "com.beacon.rider.action.STOP"
        const val EXTRA_FORCE_ECO = "com.beacon.rider.extra.FORCE_ECO"
        const val PREF_RUNNING = "tracking_running"

        private const val CHANNEL_ID = "beacon_tracking"
        private const val NOTIFICATION_ID = 1001
        private const val TAG = "TrackerService"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}

data class TrackingProfile(
    val profileName: String,
    val intervalMs: Long,
    val priority: Int
)
