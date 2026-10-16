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
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

import com.example.wristpet.pet.DailyRewards
import com.example.wristpet.pet.DailyTask
import com.example.wristpet.pet.PetBehavior
import com.example.wristpet.pet.PetState
import com.example.wristpet.pet.ShopCatalog
import com.example.wristpet.pet.ShopItem
import com.example.wristpet.pet.ShopItemType
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Dependency-free Canvas UI sized in a 360x360 logical square and clipped by the
 * watch's round display.
 *
 * Rendering contract: this view NEVER decides what the pet does. It only reads the
 * [PetBehavior] state machine (owned by the activity's game-logic layer) and turns
 * (state, stateElapsedMs) into pixels. Every motion — blink, sway, hop, bounce,
 * yawn, breathing, floating "Zzz" — is a deterministic function of that snapshot,
 * so no animation timers live here.
 *
 * Frame pacing follows the state: lively states redraw fast, sleepy ones slow down
 * to save battery. The frame loop stops completely when the view is not interactive
 * (app backgrounded or screen off), which also pauses the behavior clock.
 */
class PetView(context: Context) : View(context) {
    interface Listener {
        fun onFeed()
        fun onPet()
        fun onWake()
        /** Backward-compatible callback retained for older callers. */
        fun onGameFinished(score: Int)
        fun onClaimDiscovery()
        fun onShopItemSelected(itemId: String)

        /** Common reward entry point for current and future minigames. */
        fun onMiniGameFinished(gameId: String, score: Int, completedNormally: Boolean) {
            onGameFinished(score)
        }
    }

    var listener: Listener? = null

    /** Injected by the game-logic layer; the renderer only reads it. */
    var behavior: PetBehavior? = null

    private var state = PetState()
    private var notice = "你好呀，摸摸我吧！"
    private var isInteractive = false
    private var mode = Mode.HOME
    private var happyUntil = 0L

    // Touch origin and scroll position for the daily-task list (360x360 logical coords).
    private var touchDownX = 0f
    private var touchDownY = 0f
    private var taskScrollY = 0f

    private var shopCategory = ShopItemType.ACCESSORY
    private var shopScrollY = 0f
    private var shopNotice = "购买商品后会自动装备或切换伙伴。"

    private var gameStartedAt = 0L
    private var gameElapsedBeforePause = 0L
    private var gameScore = 0
    private var lastHitRound = -1
    private var gameHint = "光点到达顶部亮区时点击"
    private var gameResultSent = false

    /** Wall-clock anchor used to compute frame deltas for the behavior machine. */
    private var lastFrameAt = 0L

    private val handler = Handler(Looper.getMainLooper())
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val rect = RectF()
    private val ticker = object : Runnable {
        override fun run() {
            if (!isInteractive) return
            if (mode == Mode.GAME) {
                if (!gameResultSent && gameElapsed() >= GAME_LENGTH_MS) {
                    finishGameNormally()
                }
            } else if (mode == Mode.HOME) {
                advanceBehavior()
            }
            invalidate()
            handler.postDelayed(this, frameIntervalMs())
        }
    }

    fun setPetState(newState: PetState) {
        state = newState
        invalidate()
    }

    fun setNotice(value: String, durationMs: Long = 1_300L) {
        notice = value
        happyUntil = SystemClock.uptimeMillis() + durationMs.coerceAtLeast(0L)
        invalidate()
    }

    fun setShopNotice(value: String) {
        shopNotice = value
        invalidate()
    }

    fun setInteractive(active: Boolean) {
        if (isInteractive == active) return
        val now = SystemClock.elapsedRealtime()
        if (!active && mode == Mode.GAME && gameStartedAt != 0L) {
            // Pause the game clock while the app is backgrounded or the screen is off.
            gameElapsedBeforePause += (now - gameStartedAt).coerceAtLeast(0L)
            gameStartedAt = 0L
        }
        isInteractive = active
        handler.removeCallbacks(ticker)
        if (active) {
            if (mode == Mode.GAME && gameStartedAt == 0L) gameStartedAt = now
            lastFrameAt = now
            handler.post(ticker)
        } else {
            lastFrameAt = 0L
        }
    }

    /** Feed real elapsed time into the state machine (foreground frames only). */
    private fun advanceBehavior() {
        val machine = behavior ?: return
        val now = SystemClock.elapsedRealtime()
        if (lastFrameAt == 0L) lastFrameAt = now
        val delta = (now - lastFrameAt).coerceIn(0L, MAX_FRAME_DELTA_MS)
        lastFrameAt = now
        machine.tick(delta)
    }

    /** Lively states animate fast; sleepy states slow down to save battery. */
    private fun frameIntervalMs(): Long = when (mode) {
        Mode.GAME -> 45L
        Mode.TASKS -> 500L
        Mode.SHOP -> 500L
        Mode.HOME -> when (behavior?.state) {
            PetBehavior.State.EVOLVING -> 40L
            PetBehavior.State.HAPPY -> 50L
            PetBehavior.State.CURIOUS -> 70L
            PetBehavior.State.IDLE -> 80L
            PetBehavior.State.SLEEPY -> 110L
            PetBehavior.State.SLEEPING -> 140L
            null -> 600L
        }
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
        when (mode) {
            Mode.HOME -> drawHome(canvas)
            Mode.GAME -> drawGame(canvas)
            Mode.TASKS -> drawDailyTasks(canvas)
            Mode.SHOP -> drawShop(canvas)
        }
        canvas.restoreToCount(saveCount)
    }

    private fun drawHome(canvas: Canvas) {
        // Quiet decorative stars stay well inside the round screen's safe area.
        drawCircle(canvas, 69f, 94f, 1.5f, MINT_DARK)
        drawCircle(canvas, 286f, 105f, 2f, GOLD)
        drawCircle(canvas, 268f, 171f, 1.5f, MINT_DARK)
        drawCircle(canvas, 91f, 174f, 1.5f, GOLD)

        text(canvas, "腕上伙伴", 180f, 37f, 17f, TEXT, bold = true)
        text(canvas, "${ShopCatalog.petName(state.activePetId)} · Lv.${state.level} · ${state.stage}", 180f, 57f, 9.2f, MUTED)
        drawShopButton(canvas)
        drawDailyTaskButton(canvas)

        drawPet(canvas)
        drawDiscoveryMarker(canvas)

        val mood = when {
            SystemClock.uptimeMillis() < happyUntil -> notice
            behavior?.state == PetBehavior.State.SLEEPING -> "睡着啦，轻一点哦。"
            behavior?.state == PetBehavior.State.SLEEPY -> "有点困了呢。"
            state.hunger <= 30 -> "有一点饿啦。"
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

    // ---------------------------------------------------------------- daily tasks

    private data class TaskRowData(
        val title: String,
        val subtitle: String,
        val rewardOrStatus: String,
        val completed: Boolean
    )

    private fun drawDailyTaskButton(canvas: Canvas) {
        paint.color = color(PANEL)
        rect.set(241f, 38f, 289f, 66f)
        canvas.drawRoundRect(rect, 12f, 12f, paint)
        text(canvas, "任务", 265f, 56f, 10f, TEXT, bold = true)
    }

    private fun drawDiscoveryMarker(canvas: Canvas) {
        if (!DailyRewards.hasDiscovery(state, todayKeyForView())) return
        drawCircle(canvas, 244f, 135f, 13f, PANEL)
        drawCircle(canvas, 244f, 135f, 8f, GOLD)
        drawCircle(canvas, 244f, 135f, 3f, TEXT)
        text(canvas, "+", 255f, 148f, 9f, GOLD, bold = true)
    }

    private fun maxTaskScroll(): Float = (
        TASK_FIRST_ROW_CENTER +
            (TASK_COUNT - 1) * TASK_ROW_HEIGHT +
            TASK_ROW_CARD_HALF_HEIGHT - TASK_VIEWPORT_BOTTOM
        ).coerceAtLeast(0f)

    private fun drawDailyTasks(canvas: Canvas) {
        val today = todayKeyForView()
        text(canvas, "今日任务", 180f, 35f, 17f, TEXT, bold = true)
        text(
            canvas,
            "金币 ${state.coins}  ·  游戏奖励 ${state.dailyGameCoinsEarned}/${PetState.MAX_DAILY_GAME_COINS}",
            180f, 54f, 9.5f, MUTED
        )

        val discoveryStatus = when {
            DailyRewards.hasDiscovery(state, today) -> "主页拾取"
            state.discoveryDay == today && state.discoveryClaimedDay == today -> "已领取"
            state.discoveryDay == today && state.discoveryCoins == 0 -> "今日未出现"
            else -> "查看主页"
        }

        val rows = listOf(
            TaskRowData("每日拜访", "今天首次打开应用", "+3 金币", DailyRewards.isClaimed(state, DailyTask.VISIT, today)),
            TaskRowData("喂食小芽", "首次有效喂食", "+2 金币", DailyRewards.isClaimed(state, DailyTask.FEED, today)),
            TaskRowData("摸摸小芽", "首次有效摸摸", "+2 金币", DailyRewards.isClaimed(state, DailyTask.PET, today)),
            TaskRowData("陪它玩耍", "完整完成一局小游戏", "+3 金币", DailyRewards.isClaimed(state, DailyTask.GAME, today)),
            TaskRowData(
                "随机发现",
                "每天最多出现一次",
                discoveryStatus,
                state.discoveryDay == today && state.discoveryClaimedDay == today
            )
        )

        // Clip only the scrolling list; title and return button stay fixed.
        val saveCount = canvas.save()
        canvas.clipRect(38f, TASK_VIEWPORT_TOP, 322f, TASK_VIEWPORT_BOTTOM)
        rows.forEachIndexed { index, row ->
            val centerY = TASK_FIRST_ROW_CENTER + index * TASK_ROW_HEIGHT - taskScrollY
            drawTaskRow(
                canvas = canvas,
                centerY = centerY,
                title = row.title,
                subtitle = row.subtitle,
                rewardOrStatus = row.rewardOrStatus,
                completed = row.completed
            )
        }
        canvas.restoreToCount(saveCount)

        drawTaskScrollIndicator(canvas)
        text(canvas, "上下滑动查看更多", 180f, 298f, 8.5f, MUTED)

        paint.color = color(PANEL)
        rect.set(126f, 306f, 234f, 336f)
        canvas.drawRoundRect(rect, 15f, 15f, paint)
        text(canvas, "返回主页", 180f, 325f, 11f, TEXT, bold = true)
    }

    private fun drawTaskRow(
        canvas: Canvas,
        centerY: Float,
        title: String,
        subtitle: String,
        rewardOrStatus: String,
        completed: Boolean
    ) {
        paint.color = color(if (completed) 0x2030443C else PANEL)
        rect.set(42f, centerY - TASK_ROW_CARD_HALF_HEIGHT, 316f, centerY + TASK_ROW_CARD_HALF_HEIGHT)
        canvas.drawRoundRect(rect, 13f, 13f, paint)

        drawCircle(canvas, 58f, centerY - 1f, 9f, if (completed) MINT else TRACK)
        if (completed) {
            text(canvas, "✓", 58f, centerY + 3.5f, 11f, BG, bold = true)
        } else {
            drawCircle(canvas, 58f, centerY - 1f, 3f, GOLD)
        }

        text(canvas, title, 75f, centerY - 2f, 12f, TEXT, bold = true, align = Paint.Align.LEFT)
        text(canvas, subtitle, 75f, centerY + 15f, 9.2f, MUTED, align = Paint.Align.LEFT)
        text(
            canvas,
            if (completed) "已完成" else rewardOrStatus,
            305f,
            centerY + 3f,
            9.5f,
            if (completed) MINT else GOLD,
            bold = completed,
            align = Paint.Align.RIGHT
        )
    }

    private fun drawTaskScrollIndicator(canvas: Canvas) {
        val top = TASK_VIEWPORT_TOP + 5f
        val bottom = TASK_VIEWPORT_BOTTOM - 5f
        val thumbHeight = 42f
        val travel = (bottom - top - thumbHeight).coerceAtLeast(0f)
        val maxScroll = maxTaskScroll()
        val fraction = if (maxScroll <= 0f) 0f else (taskScrollY / maxScroll).coerceIn(0f, 1f)
        val thumbTop = top + travel * fraction

        paint.color = color(TRACK)
        rect.set(320f, top, 323f, bottom)
        canvas.drawRoundRect(rect, 1.5f, 1.5f, paint)
        paint.color = color(MINT)
        rect.set(319f, thumbTop, 324f, thumbTop + thumbHeight)
        canvas.drawRoundRect(rect, 2.5f, 2.5f, paint)
    }

    private fun todayKeyForView(): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(System.currentTimeMillis()))

    // ---------------------------------------------------------------- coin shop

    private fun drawShopButton(canvas: Canvas) {
        paint.color = color(PANEL)
        rect.set(65f, 38f, 116f, 66f)
        canvas.drawRoundRect(rect, 12f, 12f, paint)
        drawCircle(canvas, 78f, 52f, 5.5f, GOLD)
        text(canvas, "商店", 96f, 56f, 10f, TEXT, bold = true)
    }

    private fun drawShop(canvas: Canvas) {
        text(canvas, "金币商店", 180f, 29f, 16f, TEXT, bold = true)
        text(canvas, "余额 ${state.coins} 金币", 180f, 47f, 10f, GOLD, bold = true)
        drawShopTab(canvas, 48f, 58f, 169f, 85f, "装饰", shopCategory == ShopItemType.ACCESSORY)
        drawShopTab(canvas, 191f, 58f, 312f, 85f, "新伙伴", shopCategory == ShopItemType.PET)

        val items = ShopCatalog.items(shopCategory)
        val maxScroll = maxShopScroll(items.size)
        shopScrollY = shopScrollY.coerceIn(0f, maxScroll)
        val saveCount = canvas.save()
        canvas.clipRect(38f, SHOP_VIEWPORT_TOP, 322f, SHOP_VIEWPORT_BOTTOM)
        items.forEachIndexed { index, item ->
            val centerY = SHOP_FIRST_ROW_CENTER + index * SHOP_ROW_HEIGHT - shopScrollY
            drawShopItemRow(canvas, centerY, item)
        }
        canvas.restoreToCount(saveCount)
        drawShopScrollIndicator(canvas, items.size)

        text(canvas, shopNotice, 180f, 300f, 8.2f, MINT)
        paint.color = color(PANEL)
        rect.set(126f, 307f, 234f, 337f)
        canvas.drawRoundRect(rect, 15f, 15f, paint)
        text(canvas, "返回主页", 180f, 326f, 10.5f, TEXT, bold = true)
    }

    private fun drawShopTab(
        canvas: Canvas,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        label: String,
        selected: Boolean
    ) {
        paint.color = color(if (selected) 0x254B8068 else PANEL)
        rect.set(left, top, right, bottom)
        canvas.drawRoundRect(rect, 13f, 13f, paint)
        if (selected) {
            paint.color = color(MINT)
            rect.set(left + 8f, bottom - 3f, right - 8f, bottom)
            canvas.drawRoundRect(rect, 1.5f, 1.5f, paint)
        }
        text(canvas, label, (left + right) / 2f, top + 18f, 10.5f, if (selected) MINT else TEXT, bold = selected)
    }

    private fun maxShopScroll(itemCount: Int): Float = (
        SHOP_FIRST_ROW_CENTER + (itemCount - 1).coerceAtLeast(0) * SHOP_ROW_HEIGHT +
            SHOP_ROW_HALF_HEIGHT - SHOP_VIEWPORT_BOTTOM
        ).coerceAtLeast(0f)

    private fun drawShopItemRow(canvas: Canvas, centerY: Float, item: ShopItem) {
        val owned = ShopCatalog.isOwned(state, item)
        val equipped = ShopCatalog.isEquipped(state, item)
        paint.color = color(if (equipped) 0x2030443C else PANEL)
        rect.set(42f, centerY - SHOP_ROW_HALF_HEIGHT, 318f, centerY + SHOP_ROW_HALF_HEIGHT)
        canvas.drawRoundRect(rect, 12f, 12f, paint)
        drawShopItemIcon(canvas, item, 61f, centerY)

        text(canvas, item.name, 82f, centerY - 3f, 10.5f, TEXT, bold = true, align = Paint.Align.LEFT)
        text(canvas, item.description, 82f, centerY + 12f, 8f, MUTED, align = Paint.Align.LEFT)

        val actionLabel = when {
            item.type == ShopItemType.PET && equipped -> "当前"
            item.type == ShopItemType.PET && owned -> "切换"
            item.type == ShopItemType.ACCESSORY && equipped -> "摘下"
            item.type == ShopItemType.ACCESSORY && owned -> "装备"
            item.price == 0 -> "免费"
            else -> "${item.price}币"
        }
        paint.color = color(when {
            equipped -> 0x254B8068
            owned -> TRACK
            state.coins >= item.price -> 0x253D6655
            else -> 0x254D3B32
        })
        rect.set(253f, centerY - 13f, 311f, centerY + 13f)
        canvas.drawRoundRect(rect, 9f, 9f, paint)
        text(
            canvas, actionLabel, 282f, centerY + 3.5f, 8.5f,
            when {
                equipped -> MINT
                owned -> TEXT
                state.coins >= item.price -> GOLD
                else -> PINK
            }, bold = true
        )
    }

    private fun drawShopItemIcon(canvas: Canvas, item: ShopItem, cx: Float, cy: Float) {
        when (item.id) {
            "flower_crown" -> {
                drawCircle(canvas, cx - 5f, cy - 2f, 4f, PINK)
                drawCircle(canvas, cx + 1f, cy - 5f, 4f, MINT_LIGHT)
                drawCircle(canvas, cx + 6f, cy, 4f, PINK)
                drawCircle(canvas, cx + 1f, cy - 1f, 2f, GOLD)
            }
            "scarf" -> {
                paint.color = color(PINK)
                rect.set(cx - 8f, cy - 7f, cx + 8f, cy + 2f)
                canvas.drawRoundRect(rect, 4f, 4f, paint)
                val tail = Path().apply {
                    moveTo(cx + 2f, cy); lineTo(cx + 8f, cy + 10f); lineTo(cx - 1f, cy + 7f); close()
                }
                canvas.drawPath(tail, paint)
            }
            "star_pin" -> drawSparkle(canvas, cx, cy, 8f, GOLD, 255)
            "bunny" -> {
                paint.color = color(0xF1DDE8)
                rect.set(cx - 8f, cy - 12f, cx - 2f, cy + 2f)
                canvas.drawRoundRect(rect, 3f, 3f, paint)
                rect.set(cx + 2f, cy - 12f, cx + 8f, cy + 2f)
                canvas.drawRoundRect(rect, 3f, 3f, paint)
                drawCircle(canvas, cx, cy + 3f, 9f, 0xF1DDE8)
            }
            "fox" -> {
                val p = Path().apply {
                    moveTo(cx - 10f, cy - 4f); lineTo(cx - 6f, cy - 12f); lineTo(cx - 1f, cy - 4f); close()
                }
                paint.color = color(0xF5A35B); canvas.drawPath(p, paint)
                val q = Path().apply {
                    moveTo(cx + 1f, cy - 4f); lineTo(cx + 6f, cy - 12f); lineTo(cx + 10f, cy - 4f); close()
                }
                canvas.drawPath(q, paint)
                drawCircle(canvas, cx, cy + 4f, 9f, 0xF5A35B)
            }
            "cat" -> {
                val p = Path().apply {
                    moveTo(cx - 10f, cy - 1f); lineTo(cx - 8f, cy - 12f); lineTo(cx, cy - 4f); close()
                }
                paint.color = color(0xC8C6FF); canvas.drawPath(p, paint)
                val q = Path().apply {
                    moveTo(cx, cy - 4f); lineTo(cx + 8f, cy - 12f); lineTo(cx + 10f, cy - 1f); close()
                }
                canvas.drawPath(q, paint)
                drawCircle(canvas, cx, cy + 4f, 9f, 0xC8C6FF)
            }
            else -> {
                drawCircle(canvas, cx, cy + 1f, 9f, MINT)
                drawCircle(canvas, cx + 1f, cy - 8f, 4f, LEAF)
            }
        }
    }

    private fun drawShopScrollIndicator(canvas: Canvas, itemCount: Int) {
        val maxScroll = maxShopScroll(itemCount)
        if (maxScroll <= 0f) return
        val top = SHOP_VIEWPORT_TOP + 4f
        val bottom = SHOP_VIEWPORT_BOTTOM - 4f
        val thumbHeight = 34f
        val travel = (bottom - top - thumbHeight).coerceAtLeast(0f)
        val fraction = (shopScrollY / maxScroll).coerceIn(0f, 1f)
        val thumbTop = top + travel * fraction
        paint.color = color(TRACK)
        rect.set(320f, top, 323f, bottom)
        canvas.drawRoundRect(rect, 1.5f, 1.5f, paint)
        paint.color = color(MINT)
        rect.set(319f, thumbTop, 324f, thumbTop + thumbHeight)
        canvas.drawRoundRect(rect, 2f, 2f, paint)
    }

    // ---------------------------------------------------------------- pet body

    /** Per-frame pose of the pet, derived purely from the behavior snapshot. */
    private class PetVisuals(
        val bodyOffsetY: Float = 0f,
        val scaleX: Float = 1f,
        val scaleY: Float = 1f,
        val rotationDeg: Float = 0f,
        val eyeMode: EyeMode = EyeMode.OPEN,
        val mouthOpen: Float = 0f,
        val leafDroopDeg: Float = 0f,
        val showHearts: Boolean = false,
        val showZzz: Boolean = false,
        val showEvolution: Boolean = false,
        val showQuestion: Boolean = false
    )

    private enum class EyeMode { OPEN, BLINK, CLOSED, HAPPY }

    /**
     * Translate (behavior state, elapsed ms in state) into a pose. Deterministic:
     * the same snapshot always yields the same pixels — no timers, no randomness.
     */
    private fun computeVisuals(): PetVisuals {
        val machine = behavior ?: return PetVisuals()
        val t = machine.stateElapsedMs
        return when (machine.state) {
            PetBehavior.State.IDLE -> idleVisuals(t)
            PetBehavior.State.HAPPY -> happyVisuals(t)
            PetBehavior.State.CURIOUS -> curiousVisuals(t)
            PetBehavior.State.EVOLVING -> evolutionVisuals(t)
            PetBehavior.State.SLEEPY -> sleepyVisuals(t)
            PetBehavior.State.SLEEPING -> sleepingVisuals(t)
        }
    }

    /** Idle: gentle sway + bob, a hop every 8–16 s, regular blinking. */
    private fun idleVisuals(t: Long): PetVisuals {
        val sway = sin(t / 900.0 * 2.0 * PI)
        val bob = sin(t / 700.0 * 2.0 * PI)
        val hop = hopArc(t)
        return PetVisuals(
            bodyOffsetY = (bob * 1.2f - hop * 16f).toFloat(),
            scaleX = (1.0 - bob * 0.012 - hop * 0.05).toFloat(),
            scaleY = (1.0 + bob * 0.015 + hop * 0.08).toFloat(),
            rotationDeg = (sway * 2.2).toFloat(),
            eyeMode = if ((t % 4_300L) in 90L..190L) EyeMode.BLINK else EyeMode.OPEN
        )
    }

    /** Happy: three bounces, wiggly tilt, starry eyes, floating hearts. */
    private fun happyVisuals(t: Long): PetVisuals {
        val progress = t / HAPPY_MS.toDouble()
        val bounce = abs(sin(progress * PI * 3.0))
        val wiggle = sin(t / 130.0 * 2.0 * PI)
        return PetVisuals(
            bodyOffsetY = (-bounce * 15.0).toFloat(),
            scaleX = (1.0 - bounce * 0.05).toFloat(),
            scaleY = (1.0 + bounce * 0.07).toFloat(),
            rotationDeg = (wiggle * 1.5).toFloat(),
            eyeMode = EyeMode.HAPPY,
            showHearts = t < HEARTS_MS
        )
    }

    /** Curious: a small head tilt and a floating question mark. */
    private fun curiousVisuals(t: Long): PetVisuals {
        val sway = sin(t / 360.0 * 2.0 * PI)
        val bob = sin(t / 260.0 * 2.0 * PI)
        return PetVisuals(
            bodyOffsetY = (-2.0 + bob * 2.2).toFloat(),
            scaleX = (1.0 + abs(sway) * 0.018).toFloat(),
            scaleY = (1.0 + abs(bob) * 0.018).toFloat(),
            rotationDeg = (-4.0 + sway * 3.0).toFloat(),
            eyeMode = EyeMode.OPEN,
            showQuestion = t < QUESTION_MARK_MS
        )
    }

    /** Short celebratory pose shown only when XP crosses into a new growth stage. */
    private fun evolutionVisuals(t: Long): PetVisuals {
        val progress = (t.toFloat() / PetBehavior.EVOLUTION_MS).coerceIn(0f, 1f)
        val pulse = sin(progress * PI).toFloat()
        val bounce = abs(sin(progress * PI * 4.0)).toFloat()
        return PetVisuals(
            bodyOffsetY = -bounce * 9f - pulse * 3f,
            scaleX = 1f + pulse * 0.12f,
            scaleY = 1f + pulse * 0.16f,
            rotationDeg = (sin(t / 110.0 * 2.0 * PI) * (1f - progress) * 3f).toFloat(),
            eyeMode = EyeMode.HAPPY,
            showEvolution = true
        )
    }

    /** Sleepy: slumped posture, slower blink, an occasional yawn, drooping leaf. */
    private fun sleepyVisuals(t: Long): PetVisuals {
        val sway = sin(t / 1_600.0 * 2.0 * PI)
        val yawn = yawnCurve(t)
        val yawning = yawn > 0f
        return PetVisuals(
            bodyOffsetY = 2f,
            scaleX = 1.02f,
            scaleY = 0.97f,
            rotationDeg = (1.8 + sway * 1.0).toFloat(),
            eyeMode = if (yawning) EyeMode.CLOSED
            else if ((t % 6_000L) in 200L..420L) EyeMode.BLINK else EyeMode.OPEN,
            mouthOpen = yawn,
            leafDroopDeg = 22f
        )
    }

    /** Asleep: eyes closed, slow breathing, floating "Zzz". */
    private fun sleepingVisuals(t: Long): PetVisuals {
        val breath = sin(t / 3_000.0 * 2.0 * PI)
        return PetVisuals(
            bodyOffsetY = (breath * 1.5).toFloat(),
            scaleX = (1.0 - breath * 0.025).toFloat(),
            scaleY = (1.0 + breath * 0.045).toFloat(),
            eyeMode = EyeMode.CLOSED,
            leafDroopDeg = 30f,
            showZzz = true
        )
    }

    /** 0..1 hop arc; one hop every 8–16 s (pseudo-random per time slot, still deterministic). */
    private fun hopArc(t: Long): Float {
        val slot = t / HOP_SLOT_MS
        val hopStart = slot * HOP_SLOT_MS + (slot.toLong() * 997L % 2_000L)
        val hopT = t - hopStart
        return if (hopT in 0L..HOP_DURATION_MS) {
            sin(hopT / HOP_DURATION_MS.toDouble() * PI).toFloat()
        } else {
            0f
        }
    }

    /** 0..1 yawn curve; one yawn every ~12–15 s while sleepy. */
    private fun yawnCurve(t: Long): Float {
        val slot = t / YAWN_SLOT_MS
        val yawnStart = slot * YAWN_SLOT_MS + (slot.toLong() * 1_301L % 3_000L)
        val yawnT = t - yawnStart
        return if (yawnT in 0L..YAWN_DURATION_MS) {
            sin(yawnT / YAWN_DURATION_MS.toDouble() * PI).toFloat()
        } else {
            0f
        }
    }

    private fun drawPet(canvas: Canvas) {
        val visuals = computeVisuals()
        val sleeping = behavior?.state == PetBehavior.State.SLEEPING
        val growthStage = state.evolutionStage
        val growthScale = when (growthStage) {
            0 -> 0.80f  // baby: smaller and rounder
            1 -> 0.93f  // growing: almost full size
            else -> 1.07f // mature: a little larger
        }

        // Glow and ground shadow stay outside the body transform.
        if (!sleeping) drawCircle(canvas, 180f, 139f, 54f, HALO)
        paint.color = color(SHADOW)
        canvas.drawOval(RectF(145f, 179f, 215f, 191f), paint)

        canvas.save()
        // Pivot at the body center; apply growth scale as well as the current pose.
        canvas.translate(180f, 150f + visuals.bodyOffsetY)
        canvas.rotate(visuals.rotationDeg)
        canvas.scale(visuals.scaleX * growthScale, visuals.scaleY * growthScale)
        canvas.translate(-180f, -150f)

        when (state.activePetId) {
            "bunny" -> drawBunnyBase(canvas)
            "fox" -> drawFoxBase(canvas)
            "cat" -> drawCatBase(canvas)
            else -> drawSproutBase(canvas, visuals, growthStage)
        }
        drawFace(canvas, visuals)
        drawAccessory(canvas, state.equippedAccessoryId)
        if (growthStage >= 2 && state.activePetId != ShopCatalog.STARTER_PET_ID) {
            drawMatureBloom(canvas)
        }
        if (visuals.showHearts) drawHappyHearts(canvas)
        canvas.restore()

        if (visuals.showZzz) drawSleepZzz(canvas)
        if (visuals.showQuestion) drawCuriosityMark(canvas)
        if (visuals.showEvolution) drawEvolutionSparkles(canvas, behavior?.stateElapsedMs ?: 0L)
    }

    private fun drawSproutBase(canvas: Canvas, visuals: PetVisuals, growthStage: Int) {
        val leftEar = Path().apply {
            moveTo(148f, 143f); quadTo(138f, 119f, 160f, 126f); lineTo(169f, 141f); close()
        }
        paint.color = color(MINT_DARK)
        canvas.drawPath(leftEar, paint)
        val rightEar = Path().apply {
            moveTo(191f, 141f); lineTo(201f, 126f); quadTo(224f, 119f, 212f, 145f); close()
        }
        canvas.drawPath(rightEar, paint)
        paint.color = color(MINT)
        canvas.drawRoundRect(RectF(143f, 124f, 217f, 185f), 27f, 27f, paint)
        paint.color = color(MINT_LIGHT)
        canvas.drawOval(RectF(157f, 157f, 203f, 181f), paint)
        val stageLeafDroop = visuals.leafDroopDeg + when (growthStage) {
            0 -> 12f
            1 -> 2f
            else -> -6f
        }
        drawSprout(canvas, stageLeafDroop)
        if (growthStage >= 2) drawMatureBloom(canvas)
    }

    private fun drawBunnyBase(canvas: Canvas) {
        val leftEar = Path().apply {
            moveTo(154f, 139f); quadTo(148f, 103f, 163f, 101f); quadTo(176f, 105f, 171f, 140f); close()
        }
        val rightEar = Path().apply {
            moveTo(188f, 139f); quadTo(185f, 103f, 199f, 103f); quadTo(212f, 108f, 207f, 142f); close()
        }
        paint.color = color(0xF1DDE8)
        canvas.drawPath(leftEar, paint); canvas.drawPath(rightEar, paint)
        paint.color = color(0xE6AFC8)
        val innerLeft = Path().apply {
            moveTo(159f, 130f); quadTo(156f, 111f, 163f, 109f); quadTo(169f, 114f, 166f, 132f); close()
        }
        val innerRight = Path().apply {
            moveTo(193f, 132f); quadTo(191f, 114f, 198f, 111f); quadTo(204f, 115f, 202f, 132f); close()
        }
        canvas.drawPath(innerLeft, paint); canvas.drawPath(innerRight, paint)
        paint.color = color(0xF1DDE8)
        canvas.drawOval(RectF(143f, 125f, 217f, 185f), paint)
        paint.color = color(0xFFF6FA)
        canvas.drawOval(RectF(157f, 157f, 203f, 181f), paint)
    }

    private fun drawFoxBase(canvas: Canvas) {
        val tail = Path().apply {
            moveTo(151f, 163f); quadTo(124f, 163f, 132f, 185f); quadTo(150f, 190f, 160f, 176f); close()
        }
        paint.color = color(0xE9823F)
        canvas.drawPath(tail, paint)
        paint.color = color(0xFFF0D7)
        canvas.drawOval(RectF(130f, 179f, 143f, 188f), paint)
        val leftEar = Path().apply {
            moveTo(147f, 143f); lineTo(151f, 113f); lineTo(173f, 137f); close()
        }
        val rightEar = Path().apply {
            moveTo(187f, 137f); lineTo(209f, 113f); lineTo(215f, 145f); close()
        }
        paint.color = color(0xE9823F)
        canvas.drawPath(leftEar, paint); canvas.drawPath(rightEar, paint)
        paint.color = color(0xF5A35B)
        canvas.drawRoundRect(RectF(143f, 124f, 217f, 185f), 24f, 24f, paint)
        paint.color = color(0xFFF0D7)
        canvas.drawOval(RectF(158f, 153f, 202f, 181f), paint)
    }

    private fun drawCatBase(canvas: Canvas) {
        val leftEar = Path().apply {
            moveTo(146f, 143f); lineTo(150f, 112f); lineTo(174f, 135f); close()
        }
        val rightEar = Path().apply {
            moveTo(186f, 135f); lineTo(210f, 112f); lineTo(215f, 144f); close()
        }
        paint.color = color(0xC8C6FF)
        canvas.drawPath(leftEar, paint); canvas.drawPath(rightEar, paint)
        paint.color = color(0xF1AFC7)
        val innerLeft = Path().apply { moveTo(151f, 134f); lineTo(153f, 120f); lineTo(165f, 135f); close() }
        val innerRight = Path().apply { moveTo(195f, 135f); lineTo(207f, 120f); lineTo(209f, 134f); close() }
        canvas.drawPath(innerLeft, paint); canvas.drawPath(innerRight, paint)
        paint.color = color(0xC8C6FF)
        canvas.drawRoundRect(RectF(143f, 124f, 217f, 185f), 25f, 25f, paint)
        paint.color = color(0xF0EFFF)
        canvas.drawOval(RectF(157f, 157f, 203f, 181f), paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 5f
        paint.strokeCap = Paint.Cap.ROUND
        val tail = Path().apply { moveTo(211f, 169f); quadTo(228f, 178f, 216f, 185f); quadTo(208f, 189f, 208f, 181f) }
        paint.color = color(0xA6A3F0)
        canvas.drawPath(tail, paint)
        paint.style = Paint.Style.FILL
        paint.strokeCap = Paint.Cap.BUTT
    }

    private fun drawAccessory(canvas: Canvas, id: String) {
        when (id) {
            "flower_crown" -> {
                drawCircle(canvas, 164f, 119f, 4.4f, PINK)
                drawCircle(canvas, 173f, 115f, 4.4f, MINT_LIGHT)
                drawCircle(canvas, 182f, 117f, 4.4f, PINK)
                drawCircle(canvas, 191f, 120f, 4.4f, PINK)
                drawCircle(canvas, 173f, 115f, 1.8f, GOLD)
                drawCircle(canvas, 164f, 119f, 1.7f, GOLD)
                drawCircle(canvas, 182f, 117f, 1.7f, GOLD)
                drawCircle(canvas, 191f, 120f, 1.7f, GOLD)
            }
            "scarf" -> {
                val band = Path().apply {
                    moveTo(147f, 166f); quadTo(180f, 172f, 213f, 166f)
                    lineTo(210f, 176f); quadTo(180f, 182f, 150f, 176f); close()
                }
                paint.color = color(PINK)
                canvas.drawPath(band, paint)
                val tail = Path().apply {
                    moveTo(195f, 174f); lineTo(207f, 175f); lineTo(204f, 190f); lineTo(196f, 185f); close()
                }
                canvas.drawPath(tail, paint)
                paint.color = color(0xFFFFD9E2.toInt())
                canvas.drawLine(154f, 174f, 198f, 178f, paint)
            }
            "star_pin" -> drawSparkle(canvas, 207f, 127f, 7f, GOLD, 255)
        }
    }

    /** A tiny blossom is the visual marker of the mature stage. */
    private fun drawMatureBloom(canvas: Canvas) {
        val cx = 187f
        val cy = 101f
        drawCircle(canvas, cx - 4f, cy, 2.8f, PINK)
        drawCircle(canvas, cx + 4f, cy, 2.8f, PINK)
        drawCircle(canvas, cx, cy - 4f, 2.8f, MINT_LIGHT)
        drawCircle(canvas, cx, cy + 4f, 2.8f, PINK)
        drawCircle(canvas, cx, cy, 2.1f, GOLD)
    }

    /** Gold sparkles around the pet during the one-shot evolution celebration. */
    private fun drawEvolutionSparkles(canvas: Canvas, elapsedMs: Long) {
        val progress = (elapsedMs.toFloat() / PetBehavior.EVOLUTION_MS).coerceIn(0f, 1f)
        val alpha = ((1f - progress) * 230f).toInt().coerceIn(0, 230)
        val drift = sin(elapsedMs / 160.0 * 2.0 * PI).toFloat() * 4f
        drawSparkle(canvas, 128f, 137f - drift, 7f, GOLD, alpha)
        drawSparkle(canvas, 232f, 130f + drift, 6f, MINT_LIGHT, alpha)
        drawSparkle(canvas, 226f, 174f - drift, 5f, PINK, alpha)
    }

    private fun drawSparkle(canvas: Canvas, cx: Float, cy: Float, radius: Float, colorValue: Int, alpha: Int) {
        val path = Path().apply {
            moveTo(cx, cy - radius)
            lineTo(cx + radius * 0.30f, cy - radius * 0.30f)
            lineTo(cx + radius, cy)
            lineTo(cx + radius * 0.30f, cy + radius * 0.30f)
            lineTo(cx, cy + radius)
            lineTo(cx - radius * 0.30f, cy + radius * 0.30f)
            lineTo(cx - radius, cy)
            lineTo(cx - radius * 0.30f, cy - radius * 0.30f)
            close()
        }
        paint.color = color(colorValue)
        paint.alpha = alpha
        canvas.drawPath(path, paint)
        paint.alpha = 255
    }

    private fun drawSprout(canvas: Canvas, droopDeg: Float) {
        canvas.save()
        canvas.rotate(droopDeg, 178f, 119f) // rotate around the stem base
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
        canvas.restore()
    }

    private fun drawFace(canvas: Canvas, visuals: PetVisuals) {
        paint.color = color(FACE)
        when (visuals.eyeMode) {
            EyeMode.BLINK -> {
                paint.strokeWidth = 2.3f
                paint.style = Paint.Style.STROKE
                paint.strokeCap = Paint.Cap.ROUND
                canvas.drawLine(162f, 148f, 170f, 148f, paint)
                canvas.drawLine(190f, 148f, 198f, 148f, paint)
                paint.strokeCap = Paint.Cap.BUTT
                paint.style = Paint.Style.FILL
            }
            EyeMode.CLOSED -> {
                // Peaceful closed eyes: gentle downward arcs.
                paint.strokeWidth = 2f
                paint.style = Paint.Style.STROKE
                paint.strokeCap = Paint.Cap.ROUND
                val left = Path().apply {
                    moveTo(161f, 145f); quadTo(166f, 152f, 171f, 145f)
                }
                val right = Path().apply {
                    moveTo(189f, 145f); quadTo(194f, 152f, 199f, 145f)
                }
                canvas.drawPath(left, paint)
                canvas.drawPath(right, paint)
                paint.strokeCap = Paint.Cap.BUTT
                paint.style = Paint.Style.FILL
            }
            EyeMode.HAPPY -> {
                // Joyful squinting: upward arcs.
                paint.strokeWidth = 2.2f
                paint.style = Paint.Style.STROKE
                paint.strokeCap = Paint.Cap.ROUND
                val left = Path().apply {
                    moveTo(161f, 152f); quadTo(166f, 144f, 171f, 152f)
                }
                val right = Path().apply {
                    moveTo(189f, 152f); quadTo(194f, 144f, 199f, 152f)
                }
                canvas.drawPath(left, paint)
                canvas.drawPath(right, paint)
                paint.strokeCap = Paint.Cap.BUTT
                paint.style = Paint.Style.FILL
            }
            EyeMode.OPEN -> {
                drawCircle(canvas, 166f, 148f, 3f, FACE)
                drawCircle(canvas, 194f, 148f, 3f, FACE)
                drawCircle(canvas, 167f, 147f, 1f, TEXT)
                drawCircle(canvas, 195f, 147f, 1f, TEXT)
            }
        }
        drawCircle(canvas, 155f, 157f, 4f, CHEEK)
        drawCircle(canvas, 205f, 157f, 4f, CHEEK)

        if (visuals.mouthOpen > 0.05f) {
            // Yawning: an open little mouth instead of the smile.
            val mouthHeight = 11f * visuals.mouthOpen
            paint.color = color(FACE)
            canvas.drawOval(RectF(174f, 158f, 186f, 158f + mouthHeight), paint)
        } else {
            val smile = Path().apply {
                moveTo(174f, 157f)
                quadTo(180f, 165f, 186f, 157f)
            }
            paint.color = color(FACE)
            paint.strokeWidth = 2f
            paint.strokeCap = Paint.Cap.ROUND
            paint.style = Paint.Style.STROKE
            canvas.drawPath(smile, paint)
            paint.strokeCap = Paint.Cap.BUTT
            paint.style = Paint.Style.FILL
        }
    }

    /** Floating hearts while the happy state plays out. */
    private fun drawHappyHearts(canvas: Canvas) {
        val t = behavior?.stateElapsedMs ?: return
        for (i in 0 until 3) {
            val progress = (t - i * HEART_STAGGER_MS) / 1_000f
            if (progress < 0f || progress > 1f) continue
            val hx = 180f + (i - 1) * 26f + sin(progress * 6f) * 8f
            val hy = 132f - progress * 55f
            drawHeart(canvas, hx, hy, 6f + progress * 3f, PINK, ((1f - progress) * 220f).toInt())
        }
    }

    /** Floating "Zzz" letters while asleep, rising on the breathing rhythm. */
    private fun drawSleepZzz(canvas: Canvas) {
        val t = behavior?.stateElapsedMs ?: return
        for (i in 0 until 3) {
            val phase = ((t + i * ZZZ_STAGGER_MS) % ZZZ_PERIOD_MS) / ZZZ_PERIOD_MS.toFloat()
            val zx = 208f + phase * 28f
            val zy = 148f - phase * 62f
            text(canvas, "Z", zx, zy, 10f + phase * 5f, MINT_LIGHT, alpha = ((1f - phase) * 200f).toInt())
        }
    }

    private fun drawCuriosityMark(canvas: Canvas) {
        val t = behavior?.stateElapsedMs ?: return
        val bob = sin(t / 240.0 * 2.0 * PI).toFloat() * 3f
        val alpha = (255f * (1f - (t / QUESTION_MARK_MS.toFloat()).coerceIn(0f, 1f)))
            .toInt()
            .coerceIn(0, 255)
        text(
            canvas = canvas,
            value = "?",
            x = 226f,
            y = 105f - bob,
            size = 20f,
            colorValue = GOLD,
            bold = true,
            alpha = alpha
        )
    }

    // ---------------------------------------------------------------- widgets

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

    private fun drawHeart(canvas: Canvas, cx: Float, cy: Float, size: Float, colorValue: Int, alpha: Int = 255) {
        val path = Path().apply {
            moveTo(cx, cy + size * 0.8f)
            cubicTo(cx - size * 1.5f, cy - size * 0.1f, cx - size, cy - size, cx - size * 0.35f, cy - size)
            cubicTo(cx, cy - size, cx, cy - size * 0.55f, cx, cy - size * 0.42f)
            cubicTo(cx, cy - size * 0.75f, cx + size * 0.55f, cy - size, cx + size, cy - size * 0.55f)
            cubicTo(cx + size * 1.15f, cy + size * 0.1f, cx + size * 0.4f, cy + size * 0.5f, cx, cy + size * 0.8f)
            close()
        }
        paint.color = color(colorValue)
        paint.alpha = alpha
        canvas.drawPath(path, paint)
        paint.alpha = 255
    }

    // ---------------------------------------------------------------- minigame

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
        text(canvas, "命中 $gameScore 次   剩余 ${remainingSeconds} 秒", 180f, 270f, 11f, TEXT)
        drawBar(canvas, 74f, 283f, 286f, 1f - elapsed.toFloat() / GAME_LENGTH_MS, MINT)
        text(canvas, gameHint, 180f, 303f, 9f, if (gameHint.startsWith("命中")) MINT else MUTED)

        paint.color = color(PANEL)
        rect.set(139f, 313f, 221f, 339f)
        canvas.drawRoundRect(rect, 13f, 13f, paint)
        text(canvas, "结束", 180f, 330f, 10f, TEXT)
    }

    private fun startGame() {
        mode = Mode.GAME
        gameElapsedBeforePause = 0L
        gameStartedAt = SystemClock.elapsedRealtime()
        gameScore = 0
        lastHitRound = -1
        gameResultSent = false
        gameHint = "光点到达顶部时点击！"
        invalidate()
    }

    private fun gameElapsed(): Long {
        val runningSegment = if (mode == Mode.GAME && isInteractive && gameStartedAt != 0L) {
            (SystemClock.elapsedRealtime() - gameStartedAt).coerceAtLeast(0L)
        } else {
            0L
        }
        return (gameElapsedBeforePause + runningSegment).coerceAtLeast(0L)
    }

    private fun finishGameNormally() {
        if (mode != Mode.GAME || gameResultSent) return
        gameResultSent = true
        mode = Mode.HOME
        gameStartedAt = 0L
        listener?.onMiniGameFinished(
            gameId = "timing",
            score = gameScore.coerceIn(0, PetState.MAX_GAME_SCORE),
            completedNormally = true
        )
        invalidate()
    }

    private fun finishGameEarly() {
        if (mode != Mode.GAME || gameResultSent) return
        gameResultSent = true
        mode = Mode.HOME
        gameStartedAt = 0L
        listener?.onMiniGameFinished(
            gameId = "timing",
            score = gameScore.coerceIn(0, PetState.MAX_GAME_SCORE),
            completedNormally = false
        )
        invalidate()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val logicalScale = min(width.toFloat(), height.toFloat()) / DESIGN_SIZE
        if (logicalScale <= 0f) return true
        val offsetX = (width - DESIGN_SIZE * logicalScale) / 2f
        val offsetY = (height - DESIGN_SIZE * logicalScale) / 2f
        val x = (event.x - offsetX) / logicalScale
        val y = (event.y - offsetY) / logicalScale

        if (event.action == MotionEvent.ACTION_DOWN) {
            touchDownX = x
            touchDownY = y
            return true
        }
        if (event.action != MotionEvent.ACTION_UP) return true

        if (mode == Mode.TASKS) {
            val deltaY = y - touchDownY
            val deltaX = x - touchDownX
            val beganInList = touchDownY in TASK_VIEWPORT_TOP..TASK_VIEWPORT_BOTTOM
            if (beganInList && abs(deltaY) >= TASK_SWIPE_THRESHOLD && abs(deltaY) > abs(deltaX)) {
                // Swipe up advances to later tasks; swipe down returns to earlier tasks.
                taskScrollY = (taskScrollY - deltaY).coerceIn(0f, maxTaskScroll())
                invalidate()
                return true
            }
            if (x in 120f..240f && y in 300f..340f) {
                mode = Mode.HOME
                invalidate()
            }
            return true
        }

        if (mode == Mode.SHOP) {
            val deltaY = y - touchDownY
            val deltaX = x - touchDownX
            val beganInList = touchDownY in SHOP_VIEWPORT_TOP..SHOP_VIEWPORT_BOTTOM
            if (beganInList && abs(deltaY) >= TASK_SWIPE_THRESHOLD && abs(deltaY) > abs(deltaX)) {
                shopScrollY = (shopScrollY - deltaY).coerceIn(0f, maxShopScroll(ShopCatalog.items(shopCategory).size))
                invalidate()
                return true
            }
            if (y in 55f..89f) {
                when {
                    x in 40f..176f -> { shopCategory = ShopItemType.ACCESSORY; shopScrollY = 0f }
                    x in 184f..320f -> { shopCategory = ShopItemType.PET; shopScrollY = 0f }
                }
                invalidate()
                return true
            }
            if (x in 120f..240f && y in 302f..342f) {
                mode = Mode.HOME
                invalidate()
                return true
            }
            if (beganInList && x in 38f..320f) {
                val items = ShopCatalog.items(shopCategory)
                val index = kotlin.math.round((y + shopScrollY - SHOP_FIRST_ROW_CENTER) / SHOP_ROW_HEIGHT).toInt()
                if (index in items.indices) {
                    val rowCenter = SHOP_FIRST_ROW_CENTER + index * SHOP_ROW_HEIGHT - shopScrollY
                    if (kotlin.math.abs(y - rowCenter) <= SHOP_ROW_HALF_HEIGHT + 4f) {
                        listener?.onShopItemSelected(items[index].id)
                    }
                }
                return true
            }
            return true
        }

        if (mode == Mode.GAME) {
            if (y in 308f..344f && x in 130f..230f) {
                finishGameEarly()
                return true
            }
            val elapsed = gameElapsed()
            if (elapsed >= GAME_LENGTH_MS) {
                finishGameNormally()
                return true
            }
            val round = ((elapsed + ROTATION_MS / 2L) / ROTATION_MS).toInt()
            // The player can tap anywhere, but only one hit is counted per pass.
            val dotAngle = -PI / 2.0 + (elapsed % ROTATION_MS).toDouble() / ROTATION_MS * 2.0 * PI
            val diff = angleDistance(dotAngle, -PI / 2.0)
            if (diff <= HIT_WINDOW_RADIANS && round != lastHitRound) {
                gameScore = (gameScore + 1).coerceAtMost(PetState.MAX_GAME_SCORE)
                lastHitRound = round
                gameHint = "命中！很好！"
            } else if (round == lastHitRound) {
                gameHint = "本圈已经命中过啦"
            } else {
                gameHint = "差一点，再试试"
            }
            invalidate()
            return true
        }

        // Top-left coin shop button (home only).
        if (x in 60f..121f && y in 35f..69f) {
            shopScrollY = 0f
            shopCategory = ShopItemType.ACCESSORY
            shopNotice = "购买装饰，或解锁新的伙伴。"
            mode = Mode.SHOP
            invalidate()
            return true
        }

        // Top-right task button (home only).
        if (x in 238f..292f && y in 35f..69f) {
            taskScrollY = 0f
            mode = Mode.TASKS
            invalidate()
            return true
        }

        // A persisted discovery appears as a small gold coin on the home screen.
        if (x in 226f..263f && y in 116f..155f &&
            DailyRewards.hasDiscovery(state, todayKeyForView())) {
            listener?.onClaimDiscovery()
            invalidate()
            return true
        }

        // Tapping the pet itself: pet while awake, or gently wake it while asleep.
        if (y in 100f..200f && x in 140f..220f) {
            if (behavior?.state == PetBehavior.State.SLEEPING) {
                listener?.onWake()
            } else {
                listener?.onPet()
            }
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

    // ---------------------------------------------------------------- helpers

    private fun text(
        canvas: Canvas,
        value: String,
        x: Float,
        y: Float,
        size: Float,
        colorValue: Int,
        bold: Boolean = false,
        align: Paint.Align = Paint.Align.CENTER,
        alpha: Int = 255
    ) {
        paint.style = Paint.Style.FILL
        paint.color = color(colorValue)
        paint.textSize = size
        paint.textAlign = align
        paint.typeface = Typeface.create("sans-serif", if (bold) Typeface.BOLD else Typeface.NORMAL)
        paint.alpha = alpha
        canvas.drawText(value, x, y, paint)
        paint.alpha = 255
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

    private enum class Mode { HOME, GAME, TASKS, SHOP }
    private enum class Action { FEED, PET, GAME }

    private companion object {
        const val DESIGN_SIZE = 360f
        const val GAME_LENGTH_MS = 10_000L
        const val QUESTION_MARK_MS = 1_700L
        const val TASK_VIEWPORT_TOP = 65f
        const val TASK_VIEWPORT_BOTTOM = 284f
        const val TASK_FIRST_ROW_CENTER = 92f
        const val TASK_ROW_HEIGHT = 58f
        const val TASK_ROW_CARD_HALF_HEIGHT = 25f
        const val TASK_COUNT = 5
        const val TASK_SWIPE_THRESHOLD = 24f
        const val SHOP_VIEWPORT_TOP = 90f
        const val SHOP_VIEWPORT_BOTTOM = 284f
        const val SHOP_FIRST_ROW_CENTER = 112f
        const val SHOP_ROW_HEIGHT = 53f
        const val SHOP_ROW_HALF_HEIGHT = 23f
        const val ROTATION_MS = 1_500L
        const val HIT_WINDOW_RADIANS = 0.27

        /** Behavior animation tuning (all in ms). */
        const val HAPPY_MS = PetBehavior.HAPPY_MS
        const val MAX_FRAME_DELTA_MS = 250L
        const val HEARTS_MS = 1_300L
        const val HEART_STAGGER_MS = 240L
        const val HOP_SLOT_MS = 11_000L
        const val HOP_DURATION_MS = 500L
        const val YAWN_SLOT_MS = 12_000L
        const val YAWN_DURATION_MS = 900L
        const val ZZZ_PERIOD_MS = 2_400L
        const val ZZZ_STAGGER_MS = 800L

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

