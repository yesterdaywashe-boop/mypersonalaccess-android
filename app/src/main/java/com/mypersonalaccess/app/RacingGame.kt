package com.mypersonalaccess.app

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.isActive
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * Simple racing game — Turbo Race.
 *
 * Top-down 3-lane road: drag your finger (or tap ◀ ▶) to steer, dodge the
 * traffic, grab coins. Speed ramps up the longer you survive. Best score is
 * saved on the phone. Pure Compose Canvas — no extra libraries, no internet.
 */

private enum class RaceState { MENU, PLAYING, PAUSED, OVER }

private data class Enemy(val x: Float, var y: Float, val drift: Float, val color: Color)
private data class Coin(val x: Float, var y: Float)

private val ENEMY_COLORS = listOf(
    Color(0xFFEF4444), Color(0xFFFACC15), Color(0xFFF97316),
    Color(0xFFA855F7), Color(0xFF22C55E)
)

private class RaceGame(private val context: Context) {
    var state by mutableStateOf(RaceState.MENU)
    var score by mutableIntStateOf(0)
    var coinCount by mutableIntStateOf(0)
    var best by mutableIntStateOf(0)
    var speedKmh by mutableIntStateOf(0)
    var newBest by mutableStateOf(false)
    var frame by mutableIntStateOf(0)

    // Plain fields — the Canvas redraws every frame via `frame`.
    var width = 0f
    var height = 0f
    var playerX = 0.5f
    var targetX = 0.5f
    var distance = 0f
    var dashOffset = 0f
    val enemies = mutableListOf<Enemy>()
    val coins = mutableListOf<Coin>()
    private var spawnTimer = 0.9f
    private var coinTimer = 1.4f
    private var tone: ToneGenerator? = null

    private val prefs = context.getSharedPreferences("mpa", Context.MODE_PRIVATE)

    init {
        best = prefs.getInt("race_best", 0)
    }

    fun setSize(w: Float, h: Float) {
        width = w
        height = h
    }

    fun roadLeft(): Float = width * 0.08f
    fun roadWidth(): Float = width * 0.84f
    fun laneWidth(): Float = roadWidth() / 3f
    fun carWidth(): Float = laneWidth() * 0.66f
    fun carHeight(): Float = carWidth() * 1.9f
    fun playerTop(): Float = height * 0.80f
    fun playerPx(): Float = roadLeft() + playerX * roadWidth()

    fun start() {
        enemies.clear()
        coins.clear()
        distance = 0f
        dashOffset = 0f
        score = 0
        coinCount = 0
        newBest = false
        playerX = 0.5f
        targetX = 0.5f
        spawnTimer = 0.9f
        coinTimer = 1.2f
        state = RaceState.PLAYING
    }

    fun pause() {
        if (state == RaceState.PLAYING) state = RaceState.PAUSED
    }

    fun resume() {
        if (state == RaceState.PAUSED) state = RaceState.PLAYING
    }

    fun nudge(dir: Int) {
        targetX = (targetX + dir * (1f / 3f)).coerceIn(0.12f, 0.88f)
    }

    fun drag(dxPx: Float) {
        if (roadWidth() <= 0f) return
        targetX = (targetX + dxPx / roadWidth()).coerceIn(0.12f, 0.88f)
    }

    fun update(dt: Float) {
        if (width <= 0f || height <= 0f) return
        val speedPx = height * (0.42f + min(distance * 0.00004f, 0.55f))
        speedKmh = (speedPx / height * 260).toInt()
        distance += speedPx * dt
        dashOffset = (dashOffset + speedPx * dt) % 90f

        // Smooth steering.
        playerX += (targetX - playerX) * min(1f, dt * 10f)

        // Score: distance + coins.
        val newScore = (distance / 45f).toInt() + coinCount * 50
        if (newScore != score) score = newScore

        // Spawn traffic.
        spawnTimer -= dt
        if (spawnTimer <= 0f) {
            spawnTimer = max(0.45f, 1.0f - distance * 0.00004f) * (0.8f + Random.nextFloat() * 0.5f)
            val lane = Random.nextInt(3)
            val laneX = laneX(lane)
            val blocked = enemies.any { it.x == laneX && it.y < height * 0.35f }
            if (!blocked) {
                enemies.add(
                    Enemy(
                        x = laneX,
                        y = -carHeight() - 20f,
                        drift = 0.35f + Random.nextFloat() * 0.25f,
                        color = ENEMY_COLORS.random()
                    )
                )
            }
        }

        // Spawn coins.
        coinTimer -= dt
        if (coinTimer <= 0f) {
            coinTimer = 1.6f + Random.nextFloat() * 1.6f
            coins.add(Coin(x = laneX(Random.nextInt(3)), y = -40f))
        }

        // Move traffic + coins.
        val it = enemies.iterator()
        while (it.hasNext()) {
            val e = it.next()
            e.y += speedPx * e.drift * dt
            if (e.y > height + 120f) it.remove()
        }
        val cit = coins.iterator()
        while (cit.hasNext()) {
            val c = cit.next()
            c.y += speedPx * dt
            if (c.y > height + 60f) cit.remove()
        }

        // Collisions.
        val px = playerPx()
        val py = playerTop()
        val cw = carWidth()
        val ch = carHeight()
        for (e in enemies) {
            val ex = roadLeft() + e.x * roadWidth()
            if (kotlin.math.abs(px - ex) < cw * 0.78f && kotlin.math.abs(py - e.y) < ch * 0.82f) {
                gameOver()
                return
            }
        }
        val coinIt = coins.iterator()
        while (coinIt.hasNext()) {
            val c = coinIt.next()
            val cx = roadLeft() + c.x * roadWidth()
            if (kotlin.math.abs(px - cx) < cw * 0.62f && kotlin.math.abs(py + ch / 2f - c.y) < ch * 0.72f) {
                coinIt.remove()
                coinCount++
                beep()
            }
        }

        frame++
    }

    private fun laneX(lane: Int): Float = (lane + 0.5f) / 3f

    private fun gameOver() {
        state = RaceState.OVER
        crash()
        if (score > best) {
            best = score
            newBest = true
            prefs.edit().putInt("race_best", score).apply()
        }
    }

    private fun beep() {
        try {
            if (tone == null) tone = ToneGenerator(AudioManager.STREAM_MUSIC, 50)
            tone?.startTone(ToneGenerator.TONE_PROP_BEEP, 90)
        } catch (e: Exception) {
        }
    }

    private fun crash() {
        try {
            if (tone == null) tone = ToneGenerator(AudioManager.STREAM_MUSIC, 70)
            tone?.startTone(ToneGenerator.TONE_CDMA_ABBR_ALERT, 350)
        } catch (e: Exception) {
        }
    }

    fun release() {
        try {
            tone?.release()
        } catch (e: Exception) {
        }
        tone = null
    }
}

private fun DrawScope.drawCar(cx: Float, topY: Float, w: Float, h: Float, body: Color) {
    val left = cx - w / 2f
    // Wheels.
    val wheelW = w * 0.16f
    val wheelH = h * 0.18f
    drawRoundRect(Color.Black, Offset(left - wheelW * 0.4f, topY + h * 0.12f), Size(wheelW, wheelH), CornerRadius(4f, 4f))
    drawRoundRect(Color.Black, Offset(left + w - wheelW * 0.6f, topY + h * 0.12f), Size(wheelW, wheelH), CornerRadius(4f, 4f))
    drawRoundRect(Color.Black, Offset(left - wheelW * 0.4f, topY + h * 0.70f), Size(wheelW, wheelH), CornerRadius(4f, 4f))
    drawRoundRect(Color.Black, Offset(left + w - wheelW * 0.6f, topY + h * 0.70f), Size(wheelW, wheelH), CornerRadius(4f, 4f))
    // Body.
    drawRoundRect(body, Offset(left, topY), Size(w, h), CornerRadius(w * 0.22f, w * 0.22f))
    // Windshield + roof.
    drawRoundRect(Color(0xFF1E293B), Offset(left + w * 0.14f, topY + h * 0.18f), Size(w * 0.72f, h * 0.20f), CornerRadius(6f, 6f))
    drawRoundRect(Color.White.copy(alpha = 0.25f), Offset(left + w * 0.14f, topY + h * 0.48f), Size(w * 0.72f, h * 0.30f), CornerRadius(6f, 6f))
    // Headlights.
    drawRoundRect(Color(0xFFFEF08A), Offset(left + w * 0.10f, topY + h * 0.02f), Size(w * 0.22f, h * 0.05f), CornerRadius(4f, 4f))
    drawRoundRect(Color(0xFFFEF08A), Offset(left + w * 0.68f, topY + h * 0.02f), Size(w * 0.22f, h * 0.05f), CornerRadius(4f, 4f))
}

@Composable
fun RacingGameScreen(onExit: () -> Unit) {
    val context = LocalContext.current
    val game = remember { RaceGame(context) }
    val state = game.state

    DisposableEffect(Unit) {
        onDispose { game.release() }
    }

    // Game loop — only while playing.
    LaunchedEffect(state) {
        if (state != RaceState.PLAYING) return@LaunchedEffect
        var last = withFrameNanos { it }
        while (isActive) {
            val now = withFrameNanos { it }
            val dt = ((now - last) / 1_000_000_000f).coerceIn(0f, 0.05f)
            last = now
            game.update(dt)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF05070F))
            .padding(12.dp)
    ) {
        // Top bar.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedButton(onClick = onExit) { Text("← Wapas") }
            Text("🏎️ TURBO RACE", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color(0xFF38BDF8))
            Text("🏆 ${game.best}", fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(8.dp))

        // HUD.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            Text("Score: ${game.score}", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Text("🪙 ${game.coinCount}", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color(0xFFFACC15))
            Text("💨 ${game.speedKmh} km/h", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            if (state == RaceState.PLAYING) {
                OutlinedButton(onClick = { game.pause() }, modifier = Modifier.height(36.dp)) { Text("⏸") }
            } else if (state == RaceState.PAUSED) {
                OutlinedButton(onClick = { game.resume() }, modifier = Modifier.height(36.dp)) { Text("▶️") }
            }
        }
        Spacer(Modifier.height(8.dp))

        // Road.
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            // Read `frame` so the Canvas redraws every game tick.
            val tick = game.frame
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .onSizeChanged { game.setSize(it.width.toFloat(), it.height.toFloat()) }
                    .pointerInput(Unit) {
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            game.drag(dragAmount.x)
                        }
                    }
            ) {
                @Suppress("UNUSED_VARIABLE")
                val tickRead = tick
                val w = size.width
                val h = size.height
                if (w <= 0f || h <= 0f) return@Canvas

                // Sky / grass.
                drawRect(
                    brush = Brush.verticalGradient(
                        listOf(Color(0xFF0B3D2E), Color(0xFF052E1B))
                    ),
                    size = size
                )
                // Road.
                val roadLeft = game.roadLeft()
                val roadW = game.roadWidth()
                val laneW = game.laneWidth()
                drawRoundRect(
                    Color(0xFF23232B),
                    Offset(roadLeft, 0f),
                    Size(roadW, h),
                    CornerRadius(12f, 12f)
                )
                // Edge lines.
                drawRect(Color.White, Offset(roadLeft + 4f, 0f), Size(5f, h))
                drawRect(Color.White, Offset(roadLeft + roadW - 9f, 0f), Size(5f, h))
                // Lane dashes (scrolling).
                val dashH = 44f
                val gap = 46f
                val period = dashH + gap
                var y = -period + (game.dashOffset % period)
                while (y < h) {
                    for (i in 1..2) {
                        val x = roadLeft + laneW * i
                        drawRoundRect(
                            Color.White.copy(alpha = 0.75f),
                            Offset(x - 4f, y),
                            Size(8f, dashH),
                            CornerRadius(4f, 4f)
                        )
                    }
                    y += period
                }
                // Speed lines when fast.
                if (game.speedKmh > 130) {
                    for (i in 0..5) {
                        val lx = ((tick * 7 + i * 97) % w.toInt()).toFloat()
                        val ly = ((tick * 13 + i * 211) % h.toInt()).toFloat()
                        drawRect(
                            Color.White.copy(alpha = 0.14f),
                            Offset(lx, ly),
                            Size(3f, 70f)
                        )
                    }
                }
                // Coins.
                for (c in game.coins) {
                    val cx = roadLeft + c.x * roadW
                    drawCircle(Color(0xFFFACC15), radius = laneW * 0.16f, center = Offset(cx, c.y))
                    drawCircle(Color(0xFFB45309), radius = laneW * 0.09f, center = Offset(cx, c.y))
                }
                // Traffic.
                for (e in game.enemies) {
                    val ex = roadLeft + e.x * roadW
                    drawCar(ex, e.y, game.carWidth(), game.carHeight(), e.color)
                }
                // Player.
                drawCar(game.playerPx(), game.playerTop(), game.carWidth(), game.carHeight(), Color(0xFF38BDF8))
            }

            // Overlays.
            if (state == RaceState.MENU) {
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("🏎️", fontSize = 56.sp)
                    Text("TURBO RACE", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    Text("Best: ${game.best}", fontSize = 15.sp, color = Color(0xFFFACC15))
                    Spacer(Modifier.height(14.dp))
                    Button(
                        onClick = { game.start() },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF22C55E))
                    ) { Text("▶️ Start Race", fontSize = 18.sp) }
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "Ungli se drag karo ya ◀ ▶ dabao\nCoins looto, gaadiyon se bacho!",
                        fontSize = 13.sp,
                        color = Color(0xFFA8AECB),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            }
            if (state == RaceState.PAUSED) {
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("⏸ Paused", fontSize = 26.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { game.resume() }) { Text("▶️ Dobara Khelo") }
                }
            }
            if (state == RaceState.OVER) {
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("💥 GAME OVER", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Color(0xFFF87171))
                    if (game.newBest) {
                        Text("🏆 NEW BEST!", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color(0xFFFACC15))
                    }
                    Text("Score: ${game.score}", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Text("Best: ${game.best}  •  Coins: ${game.coinCount}", fontSize = 14.sp, color = Color(0xFFA8AECB))
                    Spacer(Modifier.height(14.dp))
                    Button(
                        onClick = { game.start() },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF22C55E))
                    ) { Text("🔄 Dobara Khelo", fontSize = 17.sp) }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = onExit) { Text("🚪 Wapas") }
                }
            }
        }

        // Steering controls.
        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                onClick = { game.nudge(-1) },
                modifier = Modifier.size(84.dp, 64.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF38BDF8), contentColor = Color(0xFF06202F))
            ) { Text("◀", fontSize = 28.sp) }
            Text("Drag bhi kar sakte ho 👆", fontSize = 13.sp, color = Color(0xFFA8AECB))
            Button(
                onClick = { game.nudge(1) },
                modifier = Modifier.size(84.dp, 64.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF38BDF8), contentColor = Color(0xFF06202F))
            ) { Text("▶", fontSize = 28.sp) }
        }
    }
}
