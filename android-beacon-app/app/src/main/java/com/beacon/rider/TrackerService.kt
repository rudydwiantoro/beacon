package com.beacon.rider

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
import java.time.Instant
import java.util.concurrent.TimeUnit

class TrackerService : Service() {

    private lateinit var fusedClient: FusedLocationProviderClient
    private lateinit var http: OkHttpClient

    private var locationCallback: LocationCallback? = null

    override fun onCreate() {
        super.onCreate()
        fusedClient = LocationServices.getFusedLocationProviderClient(this)
        http = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopTracking()
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_START -> {
                val config = ConfigStore.read(this)
                if (config.serverUrl.isBlank() || config.riderId.isBlank() || config.apiKey.isBlank()) {
                    stopSelf()
                    return START_NOT_STICKY
                }

                startForeground(NOTIFICATION_ID, buildNotification(config.mode))
                startTracking(config)
                return START_STICKY
            }

            else -> return START_STICKY
        }
    }

    private fun startTracking(config: BeaconConfig) {
        val request = buildLocationRequest(config.mode)

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val location = result.lastLocation ?: return
                pushLocation(config, location)
            }
        }

        try {
            fusedClient.requestLocationUpdates(
                request,
                locationCallback as LocationCallback,
                Looper.getMainLooper()
            )
            markRunning(true)
        } catch (_: SecurityException) {
            stopTracking()
            stopSelf()
        }
    }

    private fun stopTracking() {
        locationCallback?.let {
            fusedClient.removeLocationUpdates(it)
        }
        locationCallback = null
        markRunning(false)
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun pushLocation(config: BeaconConfig, location: Location) {
        val batteryPct = currentBatteryPercent()
        val payload = JSONObject()
            .put("riderId", config.riderId)
            .put("lat", location.latitude)
            .put("lon", location.longitude)
            .put("accuracy", location.accuracy)
            .put("speed", location.speed)
            .put("bearing", location.bearing)
            .put("battery", batteryPct)
            .put("mode", config.mode)
            .put("timestamp", Instant.ofEpochMilli(location.time).toString())

        val body = payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder()
            .url(config.serverUrl + "/api/v1/beacon")
            .header("x-beacon-key", config.apiKey)
            .post(body)
            .build()

        http.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: java.io.IOException) = Unit
            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                response.close()
            }
        })
    }

    private fun buildLocationRequest(mode: String): LocationRequest {
        val intervalMs = when (mode) {
            "live" -> 20_000L
            "balanced" -> 60_000L
            else -> 180_000L
        }

        val priority = when (mode) {
            "live" -> Priority.PRIORITY_HIGH_ACCURACY
            "balanced" -> Priority.PRIORITY_BALANCED_POWER_ACCURACY
            else -> Priority.PRIORITY_LOW_POWER
        }

        return LocationRequest.Builder(priority, intervalMs)
            .setMinUpdateIntervalMillis(intervalMs / 2)
            .setMaxUpdateDelayMillis(intervalMs * 2)
            .setWaitForAccurateLocation(false)
            .build()
    }

    private fun buildNotification(mode: String): Notification {
        createChannel()

        val openIntent = PendingIntent.getActivity(
            this,
            10,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Beacon Rider")
            .setContentText("Tracking mode: $mode")
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
        stopTracking()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "com.beacon.rider.action.START"
        const val ACTION_STOP = "com.beacon.rider.action.STOP"
        const val PREF_RUNNING = "tracking_running"

        private const val CHANNEL_ID = "beacon_tracking"
        private const val NOTIFICATION_ID = 1001
    }
}
