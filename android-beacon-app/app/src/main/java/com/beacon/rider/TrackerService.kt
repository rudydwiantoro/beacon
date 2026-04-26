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
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.lang.Exception
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlin.math.max

class TrackerService : Service() {

    private lateinit var fusedClient: FusedLocationProviderClient
    private lateinit var http: OkHttpClient

    private var locationCallback: LocationCallback? = null

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

                val forceEco = intent.getBooleanExtra(EXTRA_FORCE_ECO, false)
                val requestProfile = resolveProfile(config, forceEco)
                Log.d(TAG, "Resolved profile: ${requestProfile.profileName}, interval=${requestProfile.intervalMs}ms")

                startForeground(NOTIFICATION_ID, buildNotification(requestProfile.profileName))
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
        // Remove any existing callback before registering a new one to avoid duplicate pushes
        locationCallback?.let { fusedClient.removeLocationUpdates(it) }
        locationCallback = null

        val request = buildLocationRequest(profile, 0f)

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
            Log.d(TAG, "Started tracking with profile: ${profile.profileName}, interval: ${profile.intervalMs}ms, priority: ${profile.priority}")
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
        val batteryStr = if (batteryPct >= 0) "$batteryPct%" else "unknown"
        val mapsUrl = "https://maps.google.com/?q=${location.latitude},${location.longitude}"
        val speedKmh = (location.speed * 3.6f).toInt()
        val timestamp = Instant.ofEpochMilli(location.time).toString()

        val text = """
            🚴 *Beacon Rider Update*
            👤 Rider: `${config.riderId}`
            📍 [Open in Maps]($mapsUrl)
            🎯 Accuracy: ${location.accuracy.toInt()} m
            💨 Speed: $speedKmh km/h
            🧭 Bearing: ${location.bearing.toInt()}°
            🔋 Battery: $batteryStr
            ⚙️ Mode: `$profileName`
            🕐 `$timestamp`
        """.trimIndent()

        val payload = JSONObject()
            .put("chat_id", BuildConfig.TELEGRAM_CHANNEL_ID)
            .put("text", text)
            .put("parse_mode", "Markdown")
            .put("disable_web_page_preview", false)

        Log.d("TrackerService", "Pushing location: $text")

        val body = payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder()
            .url("https://api.telegram.org/bot${BuildConfig.TELEGRAM_BOT_TOKEN}/sendMessage")
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
//            Priority.PRIORITY_BALANCED_POWER_ACCURACY
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
            .setMaxUpdateDelayMillis(profile.intervalMs)  // don't batch beyond the interval
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
    }
}

data class TrackingProfile(
    val profileName: String,
    val intervalMs: Long,
    val priority: Int
)
