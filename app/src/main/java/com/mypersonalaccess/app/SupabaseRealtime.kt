package com.mypersonalaccess.app

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Supabase Realtime client over a raw WebSocket (Phoenix protocol).
 *
 * This mirrors exactly what the official supabase-js client does, verified
 * against the live GamesHub project:
 *  - Connect: wss://<ref>.supabase.co/realtime/v1/websocket?apikey=<key>&vsn=2.0.0
 *  - Join: JSON text ["1","1","realtime:remote-CODE","phx_join",
 *      {"config":{"broadcast":{"ack":false,"self":false},
 *                 "presence":{"key":"","enabled":false},
 *                 "postgres_changes":[],"private":false}}]
 *  - Send broadcast: JSON text ["1","2","realtime:remote-CODE","broadcast",
 *      {"type":"broadcast","event":"remote","payload":{...app payload...}}]
 *  - Heartbeat: JSON text ["1","3","phoenix","heartbeat",{}] every 30s
 *  - Receive: server replies are JSON text (phx_reply); broadcasts from
 *    other clients arrive as BINARY frames whose tail is the JSON payload.
 *    A binary frame is decoded by finding the first '{' and parsing the
 *    JSON from there (header bytes before it are the binary envelope).
 *
 * Nothing is stored anywhere. All traffic goes to the user's own Supabase
 * project, protected only by the 6-letter room code.
 */
class SupabaseRealtime(
    private val supabaseUrl: String,
    private val anonKey: String,
    private val listener: Listener
) {
    interface Listener {
        fun onJoined()
        fun onRemote(type: String, data: JSONObject?)
        fun onError(message: String)
        fun onClosed()
    }

    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private var socket: WebSocket? = null
    private var topic: String = ""
    private var refCounter = 0
    private var joined = false
    private var closedByUs = false
    private var reconnectTries = 0
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var heartbeatRunnable: Runnable? = null

    private fun nextRef(): String {
        refCounter++
        return refCounter.toString()
    }

    private fun wsUrl(): String {
        // supabaseUrl = https://<ref>.supabase.co
        val ref = supabaseUrl.removePrefix("https://").removePrefix("http://").substringBefore(".")
        return "wss://$ref.supabase.co/realtime/v1/websocket?apikey=$anonKey&vsn=2.0.0"
    }

    fun connect(roomCode: String) {
        closedByUs = false
        reconnectTries = 0
        topic = "realtime:remote-" + roomCode.uppercase()
        refCounter = 0
        val request = Request.Builder().url(wsUrl()).build()
        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                reconnectTries = 0
                join()
                startHeartbeat()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleText(text)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                handleBinary(bytes.toByteArray())
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                stopHeartbeat()
                if (closedByUs) return
                listener.onError("Connection fail: " + (t.message ?: "unknown"))
                scheduleReconnect()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                stopHeartbeat()
                if (closedByUs) {
                    listener.onClosed()
                } else {
                    listener.onError("Connection closed ($code)")
                    scheduleReconnect()
                }
            }
        })
    }

    private fun join() {
        val config = JSONObject().apply {
            put("broadcast", JSONObject().apply {
                put("ack", false)
                put("self", false)
            })
            put("presence", JSONObject().apply {
                put("key", "")
                put("enabled", false)
            })
            put("postgres_changes", JSONArray())
            put("private", false)
        }
        val arr = JSONArray().apply {
            put("1")
            put(nextRef())
            put(topic)
            put("phx_join")
            put(JSONObject().apply { put("config", config) })
        }
        socket?.send(arr.toString())
    }

    /** Send one app-level broadcast: {type, data}. */
    fun send(type: String, data: JSONObject?) {
        val sock = socket ?: return
        if (!joined) return
        val payload = JSONObject().apply {
            put("type", type)
            if (data != null) put("data", data)
        }
        val inner = JSONObject().apply {
            put("type", "broadcast")
            put("event", "remote")
            put("payload", payload)
        }
        val arr = JSONArray().apply {
            put("1")
            put(nextRef())
            put(topic)
            put("broadcast")
            put(inner)
        }
        sock.send(arr.toString())
    }

    private fun startHeartbeat() {
        stopHeartbeat()
        heartbeatRunnable = object : Runnable {
            override fun run() {
                val arr = JSONArray().apply {
                    put("1")
                    put(nextRef())
                    put("phoenix")
                    put("heartbeat")
                    put(JSONObject())
                }
                socket?.send(arr.toString())
                mainHandler.postDelayed(this, 30_000)
            }
        }
        mainHandler.postDelayed(heartbeatRunnable!!, 30_000)
    }

    private fun stopHeartbeat() {
        heartbeatRunnable?.let { mainHandler.removeCallbacks(it) }
        heartbeatRunnable = null
    }

    private fun scheduleReconnect() {
        if (closedByUs || reconnectTries >= 5) return
        reconnectTries++
        mainHandler.postDelayed({
            if (!closedByUs) {
                val request = Request.Builder().url(wsUrl()).build()
                socket = client.newWebSocket(request, object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        reconnectTries = 0
                        joined = false
                        join()
                        startHeartbeat()
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) = handleText(text)
                    override fun onMessage(webSocket: WebSocket, bytes: ByteString) =
                        handleBinary(bytes.toByteArray())

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        stopHeartbeat()
                        if (!closedByUs) scheduleReconnect()
                    }

                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                        stopHeartbeat()
                        if (!closedByUs) scheduleReconnect()
                    }
                })
            }
        }, 5_000)
    }

    private fun handleText(text: String) {
        try {
            val arr = JSONArray(text)
            if (arr.length() < 5) return
            val event = arr.optString(3)
            val payload = arr.optJSONObject(4) ?: return
            when (event) {
                "phx_reply" -> {
                    if (payload.optString("status") == "ok" && !joined) {
                        // The join reply carries postgres_changes in response;
                        // a plain ok reply is the join ack.
                        if (payload.optJSONObject("response")?.has("postgres_changes") == true) {
                            joined = true
                            listener.onJoined()
                        }
                    }
                }
                "broadcast" -> {
                    val inner = payload.optJSONObject("payload") ?: return
                    if (payload.optString("event") != "remote") return
                    listener.onRemote(inner.optString("type"), inner.optJSONObject("data"))
                }
            }
        } catch (e: Exception) {
            // Ignore malformed frames; the socket stays alive.
        }
    }

    private fun handleBinary(bytes: ByteArray) {
        try {
            // Binary broadcast frame: short binary envelope, then the JSON
            // payload as its tail. Find the first '{' and parse from there.
            val text = String(bytes, Charsets.UTF_8)
            val idx = text.indexOf('{')
            if (idx < 0) return
            val json = JSONObject(text.substring(idx))
            listener.onRemote(json.optString("type"), json.optJSONObject("data"))
        } catch (e: Exception) {
            // Ignore malformed frames.
        }
    }

    fun disconnect() {
        closedByUs = true
        stopHeartbeat()
        try {
            socket?.close(1000, "bye")
        } catch (e: Exception) {
        }
        socket = null
        joined = false
    }
}
