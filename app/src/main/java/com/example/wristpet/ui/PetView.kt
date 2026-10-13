package com.example.wristpet.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

import com.example.wristpet.pet.PetState

/** A dependency-free Canvas UI sized in a 360x360 logical square and clipped by the watch's round display. */
class PetView(context: Context) : View(context) {
    interface Listener {
        fun onFeed()
        fun onPet()
        fun onGameFinished(score: Int)
    }

    var listener: Listener? = null
    private var state = PetState()
    private var notice = "你好呀！摸摸我吧"
    private var isInteractive = false
    private var mode = Mode.HOME
    private var happyUntil = 0L

    private var gameStartedAt = 0L
    private var gameScore = 0
    private var lastHitRound = -1
    private var gameHint = "光点到达顶部亮区时点击"
    private var gameResultSent = false

    private val handler = Handler(Looper.getMainLooper())
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val rect = RectF()
    private val ticker = object : Runnable {
        override fun run() {
            if (!isInteractive) return
            if (mode == Mode.GAME && !gameResultSent && gameElapsed() >= GAME_LENGTH_MS) {
                gameResultSent = true
                mode = Mode.HOME
                listener?.onGameFinished(gameScore)
            }
            invalidate()
            handler.postDelayed(this, if (mode == Mode.GAME) 45L else 600L)
        }
    }

    fun setPetState(newState: PetState) {
        state = newState
        invalidate()
    }

    fun setNotice(value: String) {
        notice = value
        happyUntil = SystemClock.uptimeMillis() + 1_300L
        invalidate()
    }

    fun setInteractive(active: Boolean) {
        if (isInteractive == active) return
        isInteractive = active
        handler.removeCallbacks(ticker)
        if (active) handler.post(ticker)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(color(BG))
        val logicalScale = min(width.toFloat(), height.toFloat()) / DESIGN_SIZE
        val offsetX = (width - DESIGN_SIZE * logicalScale) / 2f
        val offsetY = (height - DESIGN_SIZE * logicalScale) / 2f
        val saveCount = canvas.save()
        canvas.translate(offsetX, offsetY)
        canvas.scale(logicalScale, logicalScale)
        if (mode == Mode.HOME) drawHome(canvas) else drawGame(canvas)
        canvas.restoreToCount(saveCount)
    }

    private fun drawHome(canvas: Canvas) {
        // Quiet decorative stars stay well inside the round screen's safe area.
        drawCircle(canvas, 69f, 94f, 1.5f, MINT_DARK)
        drawCircle(canvas, 286f, 105f, 2f, GOLD)
        drawCircle(canvas, 268f, 171f, 1.5f, MINT_DARK)
        drawCircle(canvas, 91f, 174f, 1.5f, GOLD)

        text(canvas, "腕上伙伴", 180f, 37f, 17f, TEXT, bold = true)
        text(canvas, "Lv.${state.level}  ·  ${state.stage}", 180f, 57f, 10f, MUTED)

        drawPet(canvas)

        val mood = when {
            SystemClock.uptimeMillis() < happyUntil -> notice
            state.hunger <= 30 -> "有一点饿啦…"
            state.affection >= 80 -> "今天也最喜欢你！"
            else -> notice
        }
        text(canvas, mood, 180f, 205f, 11f, if (SystemClock.uptimeMillis() < happyUntil) MINT else TEXT)

        text(canvas, "饱腹", 59f, 229f, 10f, MUTED, align = Paint.Align.LEFT)
        drawBar(canvas, 105f, 224f, 301f, state.hunger / 100f, MINT)
        text(canvas, "亲密", 59f, 250f, 10f, MUTED, align = Paint.Align.LEFT)
        drawBar(canvas, 105f, 245f, 301f, state.affection / 100f, PINK)

        drawActionButton(canvas, 78f, 290f, "喂食", Action.FEED)
        drawActionButton(canvas, 180f, 290f, "摸摸", Action.PET)
        drawActionButton(canvas, 282f, 290f, "玩耍", Action.GAME)
        text(canvas, "经验 ${state.exp}  今日成长 ${state.dailyExpEarned}/${PetState.MAX_DAILY_EXP}  金币 ${state.coins}", 180f, 337f, 8.5f, MUTED)
    }

    private fun drawPet(canvas: Canvas) {
        // Glow and ground shadow.
        drawCircle(canvas, 180f, 139f, 54f, HALO)
        paint.color = color(SHADOW)
        canvas.drawOval(RectF(145f, 179f, 215f, 191f), paint)

        // Tiny ears behind the body.
        val leftEar = Path().apply {
            moveTo(148f, 143f); quadTo(138f, 119f, 160f, 126f); lineTo(169f, 141f); close()
        }
        paint.color = color(MINT_DARK)
        canvas.drawPath(leftEar, paint)
        val rightEar = Path().apply {
            moveTo(191f, 141f); lineTo(201f, 126f); quadTo(224f, 119f, 212f, 145f); close()
        }
        canvas.drawPath(rightEar, paint)

        // Body.
        paint.color = color(MINT)
        canvas.drawRoundRect(RectF(143f, 124f, 217f, 185f), 27f, 27f, paint)
        paint.color = color(MINT_LIGHT)
        canvas.drawOval(RectF(157f, 157f, 203f, 181f), paint)

        // Little sprout.
        val leaf = Path().apply {
            moveTo(179f, 128f)
            quadTo(157f, 108f, 164f, 98f)
            quadTo(185f, 101f, 184f, 121f)
            close()
        }
        paint.color = color(LEAF)
        canvas.drawPath(leaf, paint)
        paint.color = color(MINT_LIGHT)
        paint.strokeWidth = 1.5f
        paint.style = Paint.Style.STROKE
        canvas.drawLine(178f, 119f, 169f, 105f, paint)
        paint.style = Paint.Style.FILL

        val blink = (SystemClock.uptimeMillis() % 4_300L) in 90L..190L
        paint.color = color(FACE)
        if (blink) {
            paint.strokeWidth = 2.3f
            paint.style = Paint.Style.STROKE
            canvas.drawLine(162f, 148f, 170f, 148f, paint)
            canvas.drawLine(190f, 148f, 198f, 148f, paint)
            paint.style = Paint.Style.FILL
        } else {
            drawCircle(canvas, 166f, 148f, 3f, FACE)
            drawCircle(canvas, 194f, 148f, 3f, FACE)
            drawCircle(canvas, 167f, 147f, 1f, TEXT)
            drawCircle(canvas, 195f, 147f, 1f, TEXT)
        }
        drawCircle(canvas, 155f, 157f, 4f, CHEEK)
        drawCircle(canvas, 205f, 157f, 4f, CHEEK)

        val smile = Path().apply {
            moveTo(174f, 157f)
            quadTo(180f, 165f, 186f, 157f)
        }
        paint.color = color(FACE)
        paint.strokeWidth = 2f
        paint.strokeCap = Paint.Cap.ROUND
        paint.style = Paint.Style.STROKE
        canvas.drawPath(smile, paint)
        paint.style = Paint.Style.FILL
    }

    private fun drawBar(canvas: Canvas, left: Float, y: Float, right: Float, fraction: Float, fillColor: Int) {
        paint.color = color(TRACK)
        rect.set(left, y, right, y + 7f)
        canvas.drawRoundRect(rect, 4f, 4f, paint)
        paint.color = color(fillColor)
        rect.set(left, y, left + (right - left) * fraction.coerceIn(0f, 1f), y + 7f)
        canvas.drawRoundRect(rect, 4f, 4f, paint)
    }

    private fun drawActionButton(canvas: Canvas, cx: Float, cy: Float, label: String, action: Action) {
        paint.color = color(PANEL)
        rect.set(cx - 34f, cy - 24f, cx + 34f, cy + 24f)
        canvas.drawRoundRect(rect, 17f, 17f, paint)
        when (action) {
            Action.FEED -> {
                paint.color = color(GOLD)
                canvas.drawOval(RectF(cx - 11f, cy - 8f, cx + 11f, cy + 2f), paint)
                paint.color = color(PINK)
                drawCircle(canvas, cx + 3f, cy - 8f, 4f, PINK)
                paint.color = color(MINT_LIGHT)
                canvas.drawLine(cx - 14f, cy + 6f, cx + 14f, cy + 6f, paint)
            }
            Action.PET -> {
                drawHeart(canvas, cx, cy - 1f, 12f, PINK)
            }
            Action.GAME -> {
                val triangle = Path().apply {
                    moveTo(cx - 5f, cy - 10f); lineTo(cx + 10f, cy); lineTo(cx - 5f, cy + 10f); close()
                }
                paint.color = color(MINT)
                canvas.drawPath(triangle, paint)
            }
        }
        text(canvas, label, cx, cy + 39f, 10f, TEXT)
    }

    private fun drawHeart(canvas: Canvas, cx: Float, cy: Float, size: Float, colorValue: Int) {
        val path = Path().apply {
            moveTo(cx, cy + size * 0.8f)
            cubicTo(cx - size * 1.5f, cy - size * 0.1f, cx - size, cy - size, cx - size * 0.35f, cy - size)
            cubicTo(cx, cy - size, cx, cy - size * 0.55f, cx, cy - size * 0.42f)
            cubicTo(cx, cy - size * 0.75f, cx + size * 0.55f, cy - size, cx + size, cy - size * 0.55f)
            cubicTo(cx + size * 1.15f, cy + size * 0.1f, cx + size * 0.4f, cy + size * 0.5f, cx, cy + size * 0.8f)
            close()
        }
        paint.color = color(colorValue)
        canvas.drawPath(path, paint)
    }

    private fun drawGame(canvas: Canvas) {
        text(canvas, "卡点挑战", 180f, 39f, 17f, TEXT, bold = true)
        text(canvas, "光点经过顶部亮区时，点击屏幕", 180f, 60f, 10f, MUTED)

        val cx = 180f
        val cy = 160f
        val radius = 85f
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 7f
        paint.strokeCap = Paint.Cap.ROUND
        paint.color = color(TRACK)
        canvas.drawCircle(cx, cy, radius, paint)
        rect.set(cx - radius, cy - radius, cx + radius, cy + radius)
        paint.color = color(MINT)
        canvas.drawArc(rect, -108f, 36f, false, paint)
        paint.style = Paint.Style.FILL
        paint.strokeCap = Paint.Cap.BUTT

        val elapsed = gameElapsed().coerceAtMost(GAME_LENGTH_MS)
        val angle = -PI / 2.0 + (elapsed % ROTATION_MS).toDouble() / ROTATION_MS * 2.0 * PI
        val dotX = cx + cos(angle).toFloat() * radius
        val dotY = cy + sin(angle).toFloat() * radius
        drawCircle(canvas, dotX, dotY, 12f, HALO)
        drawCircle(canvas, dotX, dotY, 6.5f, GOLD)
        drawCircle(canvas, dotX - 1.5f, dotY - 1.5f, 2f, TEXT)
        drawCircle(canvas, cx, cy, 6f, MINT_DARK)

        val remainingSeconds = ((GAME_LENGTH_MS - elapsed + 999L) / 1_000L).toInt().coerceAtLeast(0)
        text(canvas, "命中 $gameScore 次    剩余 ${remainingSeconds} 秒", 180f, 270f, 11f, TEXT)
        drawBar(canvas, 74f, 283f, 286f, 1f - elapsed.toFloat() / GAME_LENGTH_MS, MINT)
        text(canvas, gameHint, 180f, 303f, 9f, if (gameHint.startsWith("命中")) MINT else MUTED)

        paint.color = color(PANEL)
        rect.set(139f, 313f, 221f, 339f)
        canvas.drawRoundRect(rect, 13f, 13f, paint)
        text(canvas, "结束", 180f, 330f, 10f, TEXT)
    }

    private fun startGame() {
        mode = Mode.GAME
        gameStartedAt = SystemClock.elapsedRealtime()
        gameScore = 0
        lastHitRound = -1
        gameResultSent = false
        gameHint = "光点到顶部时点击！"
        invalidate()
    }

    private fun gameElapsed(): Long = if (gameStartedAt == 0L) 0L else SystemClock.elapsedRealtime() - gameStartedAt

    private fun finishGameEarly() {
        if (mode != Mode.GAME || gameResultSent) return
        gameResultSent = true
        mode = Mode.HOME
        listener?.onGameFinished(gameScore)
        invalidate()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action != MotionEvent.ACTION_UP) return true
        val logicalScale = min(width.toFloat(), height.toFloat()) / DESIGN_SIZE
        val offsetX = (width - DESIGN_SIZE * logicalScale) / 2f
        val offsetY = (height - DESIGN_SIZE * logicalScale) / 2f
        val x = (event.x - offsetX) / logicalScale
        val y = (event.y - offsetY) / logicalScale

        if (mode == Mode.GAME) {
            if (y in 308f..344f && x in 130f..230f) {
                finishGameEarly()
                return true
            }
            val elapsed = gameElapsed()
            if (elapsed >= GAME_LENGTH_MS) {
                finishGameEarly()
                return true
            }
            val round = (elapsed / ROTATION_MS).toInt()
            // This is a timing game: the player may tap anywhere on the screen.
            // A hit is awarded only while the moving dot is inside the top target arc.
            val dotAngle = -PI / 2.0 + (elapsed % ROTATION_MS).toDouble() / ROTATION_MS * 2.0 * PI
            val diff = angleDistance(dotAngle, -PI / 2.0)
            if (diff <= HIT_WINDOW_RADIANS && round != lastHitRound) {
                gameScore += 1
                lastHitRound = round
                gameHint = "命中！很好！"
            } else if (round == lastHitRound) {
                gameHint = "本圈已经命中啦"
            } else {
                gameHint = "差一点，再试试"
            }
            invalidate()
            return true
        }

        if (y in 263f..333f) {
            when {
                x in 40f..116f -> listener?.onFeed()
                x in 142f..218f -> listener?.onPet()
                x in 244f..320f -> startGame()
            }
        }
        return true
    }

    private fun angleDistance(a: Double, b: Double): Double {
        var delta = (a - b) % (2.0 * PI)
        if (delta > PI) delta -= 2.0 * PI
        if (delta < -PI) delta += 2.0 * PI
        return abs(delta)
    }

    private fun text(
        canvas: Canvas,
        value: String,
        x: Float,
        y: Float,
        size: Float,
        colorValue: Int,
        bold: Boolean = false,
        align: Paint.Align = Paint.Align.CENTER
    ) {
        paint.style = Paint.Style.FILL
        paint.color = color(colorValue)
        paint.textSize = size
        paint.textAlign = align
        paint.typeface = Typeface.create("sans-serif", if (bold) Typeface.BOLD else Typeface.NORMAL)
        canvas.drawText(value, x, y, paint)
    }

    private fun drawCircle(canvas: Canvas, x: Float, y: Float, radius: Float, colorValue: Int) {
        paint.style = Paint.Style.FILL
        paint.color = color(colorValue)
        canvas.drawCircle(x, y, radius, paint)
    }

    private fun color(hex: Int): Int = if ((hex ushr 24) != 0) {
        // Constants with eight hex digits already carry ARGB alpha (for glow/shadow).
        hex
    } else {
        android.graphics.Color.rgb(
            (hex shr 16) and 0xFF,
            (hex shr 8) and 0xFF,
            hex and 0xFF
        )
    }

    private enum class Mode { HOME, GAME }
    private enum class Action { FEED, PET, GAME }

    private companion object {
        const val DESIGN_SIZE = 360f
        const val GAME_LENGTH_MS = 10_000L
        const val ROTATION_MS = 1_500L
        const val HIT_WINDOW_RADIANS = 0.27

        const val BG = 0x0C1114
        const val PANEL = 0x172326
        const val TRACK = 0x2A3A3D
        const val TEXT = 0xF0F7F4
        const val MUTED = 0x9BAEAA
        const val MINT = 0x7DE3B5
        const val MINT_LIGHT = 0xB9FFD8
        const val MINT_DARK = 0x3BA981
        const val LEAF = 0x5DBB83
        const val PINK = 0xFFA5B8
        const val CHEEK = 0xF18DA2
        const val GOLD = 0xFFD166
        const val HALO = 0x203C8F6A
        const val SHADOW = 0x263C8F6A
        const val FACE = 0x193B32
    }
}
