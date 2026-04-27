package com.beacon.rider

import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class RelayTestActivity : AppCompatActivity() {

    private lateinit var statusText: TextView

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    private val ioExecutor = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_relay_test)

        statusText = findViewById(R.id.relayTestStatus)

        findViewById<Button>(R.id.sendTestTelegramBtn).setOnClickListener {
            sendTelegramTest()
        }

        findViewById<Button>(R.id.sendTestCloudBtn).setOnClickListener {
            sendCloudTest()
        }
    }

    private fun sendTelegramTest() {
        updateStatus("Mengirim test Telegram...")
        val config = ConfigStore.read(this)

        ioExecutor.execute {
            try {
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
                    throw IllegalStateException("Token/chat id Telegram belum diisi")
                }

                val now = Instant.now().toString()
                val riderId = config.riderId.ifBlank { "beacon-test" }
                val text = "Beacon Telegram test\\nRider: $riderId\\nTime: $now"

                val payload = JSONObject()
                    .put("chat_id", chatId)
                    .put("text", text)

                val request = Request.Builder()
                    .url("https://api.telegram.org/bot$token/sendMessage")
                    .post(payload.toString().toRequestBody(JSON_MEDIA))
                    .build()

                executeWithoutBody(request)
                runOnUiThread {
                    updateStatus("Telegram test terkirim. Cek chat Telegram.")
                    Toast.makeText(this, "Telegram test success", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    updateStatus("Telegram test gagal: ${e.message}")
                }
            }
        }
    }

    private fun sendCloudTest() {
        updateStatus("Mengirim test Cloud...")
        val config = ConfigStore.read(this)

        ioExecutor.execute {
            try {
                if (config.dbHost.isBlank() || config.dbPassword.isBlank()) {
                    throw IllegalStateException("Host/API key cloud belum diisi")
                }

                val beaconCode = config.riderId.ifBlank { "beacon-test" }
                val beaconId = ensureBeaconId(config, beaconCode)
                val tripId = ensureTripId(config, beaconId)
                val nextSeq = fetchLatestSeq(config, tripId) + 1
                val now = Instant.now().toString()

                val sampleLat = -5.147665
                val sampleLon = 119.432732

                val payload = JSONObject()
                    .put("trip_id", tripId)
                    .put("beacon_id", beaconId)
                    .put("seq", nextSeq)
                    .put("recorded_at", now)
                    .put("latitude", sampleLat)
                    .put("longitude", sampleLon)
                    .put("speed_mps", 0)
                    .put("heading_deg", 0)
                    .put("accuracy_m", 5)
                    .put("battery_pct", 77)
                    .put("event_type", "track")
                    .put("payload", JSONObject()
                        .put("source", "manual_test")
                        .put("db_user", config.dbUser)
                        .put("db_name", config.dbName)
                    )

                val request = supabaseRequestBuilder(config, restUrl(config, "trip_points")!!)
                    .header("Prefer", "return=minimal")
                    .post(payload.toString().toRequestBody(JSON_MEDIA))
                    .build()

                executeWithoutBody(request)
                runOnUiThread {
                    updateStatus("Cloud test sukses. Cek local viewer (mode cloud).")
                    Toast.makeText(this, "Cloud test success", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    updateStatus("Cloud test gagal: ${e.message}")
                }
            }
        }
    }

    private fun ensureBeaconId(config: BeaconConfig, beaconCode: String): String {
        val queryUrl = restUrl(config, "beacons")
            ?.newBuilder()
            ?.addQueryParameter("select", "id")
            ?.addQueryParameter("beacon_code", "eq.$beaconCode")
            ?.addQueryParameter("limit", "1")
            ?.build()
            ?: throw IllegalStateException("Invalid host URL")

        val getReq = supabaseRequestBuilder(config, queryUrl).get().build()
        val found = JSONArray(executeForBody(getReq))
        if (found.length() > 0) {
            return found.getJSONObject(0).optString("id")
        }

        val createPayload = JSONObject()
            .put("beacon_code", beaconCode)
            .put("name", beaconCode)
            .put("metadata", JSONObject().put("source", "manual_test"))

        val createReq = supabaseRequestBuilder(config, restUrl(config, "beacons")!!)
            .header("Prefer", "return=representation")
            .post(createPayload.toString().toRequestBody(JSON_MEDIA))
            .build()

        val created = JSONArray(executeForBody(createReq))
        if (created.length() == 0) throw IllegalStateException("Gagal create beacon")
        return created.getJSONObject(0).optString("id")
    }

    private fun ensureTripId(config: BeaconConfig, beaconId: String): String {
        val queryUrl = restUrl(config, "trips")
            ?.newBuilder()
            ?.addQueryParameter("select", "id")
            ?.addQueryParameter("beacon_id", "eq.$beaconId")
            ?.addQueryParameter("status", "eq.in_progress")
            ?.addQueryParameter("order", "started_at.desc")
            ?.addQueryParameter("limit", "1")
            ?.build()
            ?: throw IllegalStateException("Invalid host URL")

        val getReq = supabaseRequestBuilder(config, queryUrl).get().build()
        val found = JSONArray(executeForBody(getReq))
        if (found.length() > 0) {
            return found.getJSONObject(0).optString("id")
        }

        val now = Instant.now().toString()
        val createPayload = JSONObject()
            .put("beacon_id", beaconId)
            .put("started_at", now)
            .put("status", "in_progress")

        val createReq = supabaseRequestBuilder(config, restUrl(config, "trips")!!)
            .header("Prefer", "return=representation")
            .post(createPayload.toString().toRequestBody(JSON_MEDIA))
            .build()

        val created = JSONArray(executeForBody(createReq))
        if (created.length() == 0) throw IllegalStateException("Gagal create trip")
        return created.getJSONObject(0).optString("id")
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

        val getReq = supabaseRequestBuilder(config, queryUrl).get().build()
        val rows = JSONArray(executeForBody(getReq))
        if (rows.length() == 0) return 0
        return rows.getJSONObject(0).optInt("seq", 0)
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

    private fun updateStatus(text: String) {
        statusText.text = text
    }

    override fun onDestroy() {
        ioExecutor.shutdownNow()
        super.onDestroy()
    }

    companion object {
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }
}
