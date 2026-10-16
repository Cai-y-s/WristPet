package com.example.wristpet.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.media.AudioManager
import android.media.ToneGenerator
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
import kotlin.random.Random

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

    // Shared touch/scroll state for the daily tasks and shop lists.
    private var touchDownX = 0f
    private var touchDownY = 0f
    private var lastTouchY = 0f
    private var touchDownTime = 0L
    private var lastMoveTime = 0L
    private var touchStartMode = Mode.HOME
    private var touchStartedInScrollableViewport = false
    private var draggingList = false
    private var scrollVelocityPerFrame = 0f
    private var flingMode: Mode? = null
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

    // Cat Paw Quick Tap state. Scores are normalized at settlement to the shared 0..MAX_GAME_SCORE scale.
    private var catPawRawScore = 0
    private var catPawTargetX = 180f
    private var catPawTargetY = 160f
    private var catPawTarget = CatPawTarget.COIN
    private var catPawTargetExpiresAt = 0L
    private var catPawHint = "快速点击金币和鱼干，避开炸弹！"

    // Fuxing Catch Coins: move Fuxing horizontally to catch falling rewards.
    private data class FallingCatchItem(
        val id: Long,
        val x: Float,
        var y: Float,
        val kind: FallingKind,
        val speed: Float
    )
    private enum class FallingKind { COIN, FISH, ROCK }
    private val fuxingCatchItems = mutableListOf<FallingCatchItem>()
    private var fuxingCatchX = 180f
    private var fuxingCatchRawScore = 0
    private var fuxingCatchHint = "左右滑动福星，接住金币和鱼干！"
    private var fuxingNextItemId = 1L
    private var fuxingNextSpawnAt = 0L
    private var fuxingLastUpdateAt = 0L

    // Fuxing Jump: side-scrolling obstacle course. The mini-game uses the shared
    // game clock, so it pauses naturally when the watch app goes to the background.
    private data class JumpingObject(
        val id: Long,
        var x: Float,
        val kind: JumpingKind,
        val speed: Float
    )
    private enum class JumpingKind { BOX, YARN, COIN, FISH }
    private val fuxingJumpObjects = mutableListOf<JumpingObject>()
    private var fuxingJumpRawScore = 0
    private var fuxingJumpHint = "轻点跳跃，越过障碍并收集奖励！"
    private var fuxingJumpStartedAt = -FUXING_JUMP_DURATION_MS
    private var fuxingJumpNextSpawnAt = 0L
    private var fuxingJumpLastElapsed = 0L
    private var fuxingJumpNextId = 1L

    /** Wall-clock anchor used to compute frame deltas for the behavior machine. */
    private var lastFrameAt = 0L

    private val handler = Handler(Looper.getMainLooper())
    private var toneGenerator: ToneGenerator? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val rect = RectF()
    private val ticker = object : Runnable {
        override fun run() {
            if (!isInteractive) return
            if (isTimedGameMode()) {
                if (!gameResultSent && gameElapsed() >= activeGameLengthMs()) {
                    finishGameNormally()
                } else {
                    when (mode) {
                        Mode.CAT_PAW -> updateCatPawTarget()
                        Mode.FUXING_CATCH -> updateFuxingCatch()
                        Mode.FUXING_JUMP -> updateFuxingJump()
                        else -> Unit
                    }
                }
            } else if (mode == Mode.HOME) {
                advanceBehavior()
            }
            if (mode == Mode.HOME || isTimedGameMode() || mode == Mode.GAME_MENU) invalidate()
            handler.postDelayed(this, frameIntervalMs())
        }
    }

    private val scrollFlingRunnable = object : Runnable {
        override fun run() {
            val listMode = flingMode ?: return
            if (!isInteractive || mode != listMode) {
                flingMode = null
                scrollVelocityPerFrame = 0f
                return
            }

            val oldScroll = scrollPosition(listMode)
            val wanted = oldScroll + scrollVelocityPerFrame
            val maxScroll = maxScrollFor(listMode)
            val next = wanted.coerceIn(0f, maxScroll)
            setScrollPosition(listMode, next)
            if (next != wanted) scrollVelocityPerFrame = 0f
            scrollVelocityPerFrame *= FLING_FRICTION
            invalidate()

            if (kotlin.math.abs(scrollVelocityPerFrame) >= FLING_STOP_THRESHOLD) {
                handler.postDelayed(this, FLING_FRAME_MS)
            } else {
                flingMode = null
                scrollVelocityPerFrame = 0f
            }
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
        if (!active) cancelScrollFling()
        if (!active && isTimedGameMode() && gameStartedAt != 0L) {
            // Pause the game clock while the app is backgrounded or the screen is off.
            gameElapsedBeforePause += (now - gameStartedAt).coerceAtLeast(0L)
            gameStartedAt = 0L
        }
        isInteractive = active
        handler.removeCallbacks(ticker)
        if (active) {
            if (isTimedGameMode() && gameStartedAt == 0L) gameStartedAt = now
            if (mode == Mode.FUXING_CATCH) fuxingLastUpdateAt = now
            if (mode == Mode.FUXING_JUMP) fuxingJumpLastElapsed = gameElapsed()
            lastFrameAt = now
            handler.post(ticker)
        } else {
            if (mode == Mode.FUXING_CATCH) fuxingLastUpdateAt = 0L
            if (mode == Mode.FUXING_JUMP) fuxingJumpLastElapsed = gameElapsedBeforePause
            lastFrameAt = 0L
            releaseAudio()
        }
    }

    override fun onDetachedFromWindow() {
        releaseAudio()
        super.onDetachedFromWindow()
    }

    private enum class SoundCue { TAP, GOOD, BAD }

    /** Short built-in tones; no audio assets or network access are required. */
    private fun playFeedback(cue: SoundCue) {
        try {
            val generator = toneGenerator ?: ToneGenerator(AudioManager.STREAM_MUSIC, 68)
                .also { toneGenerator = it }
            val tone = when (cue) {
                SoundCue.TAP -> ToneGenerator.TONE_PROP_BEEP
                SoundCue.GOOD -> ToneGenerator.TONE_PROP_ACK
                SoundCue.BAD -> ToneGenerator.TONE_PROP_NACK
            }
            generator.startTone(tone, if (cue == SoundCue.GOOD) 110 else 75)
        } catch (_: RuntimeException) {
            // Some watch audio stacks reject ToneGenerator; the UI must still work.
        }
    }

    private fun releaseAudio() {
        try {
            toneGenerator?.stopTone()
            toneGenerator?.release()
        } catch (_: RuntimeException) {
            // Ignore vendor audio-stack errors during pause/detach.
        } finally {
            toneGenerator = null
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
        Mode.GAME, Mode.CAT_PAW, Mode.FUXING_CATCH, Mode.FUXING_JUMP -> 45L
        Mode.GAME_MENU -> 500L
        // Detail pages are static; active drag/fling events redraw them independently.
        Mode.TASKS -> 1_000L
        Mode.SHOP -> 1_000L
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
        canvas.drawColor(color(backgroundForMode()))
        val logicalScale = min(width.toFloat(), height.toFloat()) / DESIGN_SIZE
        val offsetX = (width - DESIGN_SIZE * logicalScale) / 2f
        val offsetY = (height - DESIGN_SIZE * logicalScale) / 2f
        val saveCount = canvas.save()
        canvas.translate(offsetX, offsetY)
        canvas.scale(logicalScale, logicalScale)
        drawModeBackdrop(canvas)
        when (mode) {
            Mode.HOME -> drawHome(canvas)
            Mode.GAME_MENU -> drawGameMenu(canvas)
            Mode.GAME -> drawGame(canvas)
            Mode.CAT_PAW -> drawCatPawGame(canvas)
            Mode.FUXING_CATCH -> drawFuxingCatchGame(canvas)
            Mode.FUXING_JUMP -> drawFuxingJumpGame(canvas)
            Mode.TASKS -> drawDailyTasks(canvas)
            Mode.SHOP -> drawShop(canvas)
        }
        canvas.restoreToCount(saveCount)
    }

    private fun backgroundForMode(): Int = when (mode) {
        Mode.HOME -> 0xFF28545E.toInt()        // deep aqua
        Mode.GAME_MENU -> 0xFF354C73.toInt()   // blue-violet
        Mode.GAME -> 0xFF344F78.toInt()        // timing challenge
        Mode.CAT_PAW -> 0xFF66446E.toInt()     // berry purple
        Mode.FUXING_CATCH -> 0xFF24616B.toInt()// ocean teal
        Mode.FUXING_JUMP -> 0xFF32684F.toInt() // garden green
        Mode.TASKS -> 0xFF55466B.toInt()       // plum
        Mode.SHOP -> 0xFF705039.toInt()        // warm caramel
    }

    /** Large translucent colour washes make every page visibly different without obscuring text. */
    private fun drawModeBackdrop(canvas: Canvas) {
        val (glowA, glowB) = when (mode) {
            Mode.HOME -> 0x287DE3B5 to 0x24FFD166
            Mode.GAME_MENU -> 0x284F91D9 to 0x287DE3B5
            Mode.GAME -> 0x285A7DCC to 0x24FFD166
            Mode.CAT_PAW -> 0x28FFA5B8 to 0x284F91D9
            Mode.FUXING_CATCH -> 0x287DE3B5 to 0x28FFD166
            Mode.FUXING_JUMP -> 0x287DE3B5 to 0x28E7C56B
            Mode.TASKS -> 0x28FFA5B8 to 0x285A7DCC
            Mode.SHOP -> 0x28FFD166 to 0x28FFA5B8
        }
        drawCircle(canvas, 46f, 92f, 66f, glowA)
        drawCircle(canvas, 315f, 246f, 78f, glowB)
        drawCircle(canvas, 180f, 355f, 66f, 0x16FFFFFF)
        // A few tiny stars are kept away from primary text and touch controls.
        drawCircle(canvas, 106f, 77f, 1.7f, 0x88FFFFFF.toInt())
        drawCircle(canvas, 285f, 88f, 1.4f, 0x88FFFFFF.toInt())
        drawCircle(canvas, 58f, 214f, 1.3f, 0x66FFFFFF)
        drawCircle(canvas, 302f, 177f, 1.6f, 0x66FFFFFF)
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

        // Lift controls into the wider part of the round screen; labels no longer
        // sit on the tapered lower edge where the watch bezel clips them.
        drawActionButton(canvas, 86f, 266f, "喂食", Action.FEED)
        drawActionButton(canvas, 180f, 266f, "摸摸", Action.PET)
        drawActionButton(canvas, 274f, 266f, "玩耍", Action.GAME)
        text(canvas, "经验 ${state.exp} · 今日 ${state.dailyExpEarned}/${PetState.MAX_DAILY_EXP} · 金币 ${state.coins}", 180f, 329f, 7.1f, MUTED)
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
        DETAIL_FIRST_ROW_CENTER +
            (TASK_COUNT - 1) * DETAIL_ROW_HEIGHT +
            DETAIL_ROW_HALF_HEIGHT - DETAIL_VIEWPORT_BOTTOM
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
            TaskRowData("喂食${ShopCatalog.petName(state.activePetId)}", "首次有效喂食", "+2 金币", DailyRewards.isClaimed(state, DailyTask.FEED, today)),
            TaskRowData("摸摸${ShopCatalog.petName(state.activePetId)}", "首次有效摸摸", "+2 金币", DailyRewards.isClaimed(state, DailyTask.PET, today)),
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
        canvas.clipRect(38f, DETAIL_VIEWPORT_TOP, 322f, DETAIL_VIEWPORT_BOTTOM)
        rows.forEachIndexed { index, row ->
            val centerY = DETAIL_FIRST_ROW_CENTER + index * DETAIL_ROW_HEIGHT - taskScrollY
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
        text(canvas, if (maxTaskScroll() > 0f) "上下滑动查看更多" else "今日任务已展示完毕", 180f, 296f, 9f, MUTED)

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
        rect.set(42f, centerY - DETAIL_ROW_HALF_HEIGHT, 318f, centerY + DETAIL_ROW_HALF_HEIGHT)
        canvas.drawRoundRect(rect, 13f, 13f, paint)

        drawCircle(canvas, 58f, centerY - 1f, 9f, if (completed) MINT else TRACK)
        if (completed) {
            text(canvas, "✓", 58f, centerY + 3.5f, 11f, 0x14343A, bold = true)
        } else {
            drawCircle(canvas, 58f, centerY - 1f, 3f, GOLD)
        }

        text(canvas, title, 75f, centerY - 4f, 12f, TEXT, bold = true, align = Paint.Align.LEFT)
        text(canvas, subtitle, 75f, centerY + 14f, 9.2f, MUTED, align = Paint.Align.LEFT)
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
        drawSharedScrollIndicator(canvas, taskScrollY, maxTaskScroll())
    }

    private fun drawSharedScrollIndicator(canvas: Canvas, scrollY: Float, maxScroll: Float) {
        if (maxScroll <= 0f) return
        val top = DETAIL_VIEWPORT_TOP + 5f
        val bottom = DETAIL_VIEWPORT_BOTTOM - 5f
        val thumbHeight = 38f
        val travel = (bottom - top - thumbHeight).coerceAtLeast(0f)
        val fraction = (scrollY / maxScroll).coerceIn(0f, 1f)
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
        canvas.clipRect(38f, DETAIL_VIEWPORT_TOP, 322f, DETAIL_VIEWPORT_BOTTOM)
        items.forEachIndexed { index, item ->
            val centerY = DETAIL_FIRST_ROW_CENTER + index * DETAIL_ROW_HEIGHT - shopScrollY
            drawShopItemRow(canvas, centerY, item)
        }
        canvas.restoreToCount(saveCount)
        drawShopScrollIndicator(canvas, items.size)

        text(canvas, shopNotice, 180f, 296f, 9f, MINT)
        paint.color = color(PANEL)
        rect.set(126f, 306f, 234f, 336f)
        canvas.drawRoundRect(rect, 15f, 15f, paint)
        text(canvas, "返回主页", 180f, 325f, 11f, TEXT, bold = true)
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
        DETAIL_FIRST_ROW_CENTER + (itemCount - 1).coerceAtLeast(0) * DETAIL_ROW_HEIGHT +
            DETAIL_ROW_HALF_HEIGHT - DETAIL_VIEWPORT_BOTTOM
        ).coerceAtLeast(0f)

    private fun drawShopItemRow(canvas: Canvas, centerY: Float, item: ShopItem) {
        val owned = ShopCatalog.isOwned(state, item)
        val equipped = ShopCatalog.isEquipped(state, item)
        paint.color = color(if (equipped) 0x2030443C else PANEL)
        rect.set(42f, centerY - DETAIL_ROW_HALF_HEIGHT, 318f, centerY + DETAIL_ROW_HALF_HEIGHT)
        canvas.drawRoundRect(rect, 12f, 12f, paint)
        drawShopItemIcon(canvas, item, 61f, centerY)

        text(canvas, item.name, 82f, centerY - 4f, 11.5f, TEXT, bold = true, align = Paint.Align.LEFT)
        text(canvas, item.description, 82f, centerY + 14f, 9f, MUTED, align = Paint.Align.LEFT)

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
        rect.set(251f, centerY - 14f, 311f, centerY + 14f)
        canvas.drawRoundRect(rect, 9f, 9f, paint)
        text(
            canvas, actionLabel, 281f, centerY + 3.5f, 9f,
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
            "fuxing" -> {
                // Tiny orange tabby portrait with the signature blue ID badge.
                val leftEar = Path().apply {
                    moveTo(cx - 9f, cy - 1f); lineTo(cx - 8f, cy - 12f); lineTo(cx - 1f, cy - 5f); close()
                }
                val rightEar = Path().apply {
                    moveTo(cx + 1f, cy - 5f); lineTo(cx + 8f, cy - 12f); lineTo(cx + 9f, cy - 1f); close()
                }
                paint.color = color(FUXING_ORANGE)
                canvas.drawPath(leftEar, paint)
                canvas.drawPath(rightEar, paint)
                drawCircle(canvas, cx, cy + 1f, 9f, FUXING_ORANGE)
                drawCircle(canvas, cx, cy + 4f, 5f, FUXING_CREAM)
                drawCircle(canvas, cx - 3f, cy, 1.1f, FACE)
                drawCircle(canvas, cx + 3f, cy, 1.1f, FACE)
                paint.color = color(FUXING_COLLAR)
                rect.set(cx - 3f, cy + 7f, cx + 3f, cy + 10f)
                canvas.drawRoundRect(rect, 1f, 1f, paint)
                paint.color = color(FUXING_BADGE_BLUE)
                rect.set(cx - 4f, cy + 9f, cx + 4f, cy + 15f)
                canvas.drawRoundRect(rect, 1f, 1f, paint)
                drawCircle(canvas, cx, cy + 11.5f, 1.8f, FUXING_ORANGE)
            }
            else -> {
                drawCircle(canvas, cx, cy + 1f, 9f, MINT)
                drawCircle(canvas, cx + 1f, cy - 8f, 4f, LEAF)
            }
        }
    }

    private fun drawShopScrollIndicator(canvas: Canvas, itemCount: Int) {
        drawSharedScrollIndicator(canvas, shopScrollY, maxShopScroll(itemCount))
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
            "fuxing" -> drawFuxingBase(canvas)
            else -> drawSproutBase(canvas, visuals, growthStage)
        }
        drawFace(canvas, visuals)
        if (state.activePetId == "fuxing") drawFuxingFaceDetails(canvas)
        drawAccessory(canvas, state.equippedAccessoryId)
        // Keep the signature ID badge visible even when an accessory is equipped.
        if (state.activePetId == "fuxing") drawFuxingIdBadge(canvas)
        if (growthStage >= 2 && state.activePetId != ShopCatalog.STARTER_PET_ID && state.activePetId != "fuxing") {
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

    /**
     * A custom orange tabby inspired by Fuxing's photo. The eyes still use the shared
     * PetVisuals renderer, so happy/curious/sleepy/sleeping expressions keep working.
     */
    private fun drawFuxingBase(canvas: Canvas) {
        // Curled striped tail behind the body.
        val tail = Path().apply {
            moveTo(207f, 164f)
            quadTo(230f, 170f, 226f, 185f)
            quadTo(224f, 194f, 214f, 189f)
        }
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeWidth = 10f
        paint.color = color(FUXING_DARK)
        canvas.drawPath(tail, paint)
        paint.strokeWidth = 4f
        paint.color = color(FUXING_LIGHT)
        canvas.drawPath(tail, paint)
        paint.style = Paint.Style.FILL
        paint.strokeCap = Paint.Cap.BUTT

        // Pointed ears with warm pink inner ears.
        val leftEar = Path().apply {
            moveTo(145f, 139f); lineTo(149f, 109f); lineTo(173f, 133f); close()
        }
        val rightEar = Path().apply {
            moveTo(187f, 133f); lineTo(211f, 109f); lineTo(215f, 140f); close()
        }
        paint.color = color(FUXING_ORANGE)
        canvas.drawPath(leftEar, paint)
        canvas.drawPath(rightEar, paint)
        paint.color = color(FUXING_PINK)
        val leftInner = Path().apply {
            moveTo(151f, 132f); lineTo(153f, 117f); lineTo(166f, 133f); close()
        }
        val rightInner = Path().apply {
            moveTo(194f, 133f); lineTo(207f, 117f); lineTo(209f, 133f); close()
        }
        canvas.drawPath(leftInner, paint)
        canvas.drawPath(rightInner, paint)

        // Rounded orange body, pale muzzle/chest and two tiny paws.
        paint.color = color(FUXING_ORANGE)
        canvas.drawRoundRect(RectF(142f, 121f, 218f, 187f), 25f, 25f, paint)
        paint.color = color(FUXING_CREAM)
        canvas.drawOval(RectF(155f, 151f, 205f, 178f), paint)
        canvas.drawOval(RectF(151f, 174f, 178f, 191f), paint)
        canvas.drawOval(RectF(182f, 174f, 209f, 191f), paint)

        // Signature orange-tabby forehead and cheek stripes.
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 3f
        paint.strokeCap = Paint.Cap.ROUND
        paint.color = color(FUXING_DARK)
        val stripe1 = Path().apply { moveTo(163f, 125f); quadTo(166f, 132f, 166f, 138f) }
        val stripe2 = Path().apply { moveTo(180f, 122f); quadTo(180f, 131f, 180f, 139f) }
        val stripe3 = Path().apply { moveTo(197f, 125f); quadTo(194f, 132f, 194f, 138f) }
        canvas.drawPath(stripe1, paint)
        canvas.drawPath(stripe2, paint)
        canvas.drawPath(stripe3, paint)
        canvas.drawLine(147f, 151f, 155f, 154f, paint)
        canvas.drawLine(147f, 158f, 154f, 159f, paint)
        canvas.drawLine(205f, 154f, 213f, 151f, paint)
        canvas.drawLine(206f, 159f, 213f, 158f, paint)
        paint.style = Paint.Style.FILL
        paint.strokeCap = Paint.Cap.BUTT

        // Silver collar and the strap that leads to Fuxing's ID badge.
        val collar = Path().apply {
            moveTo(149f, 165f); quadTo(180f, 176f, 211f, 165f)
            lineTo(208f, 173f); quadTo(180f, 184f, 152f, 173f); close()
        }
        paint.color = color(FUXING_COLLAR)
        canvas.drawPath(collar, paint)
        paint.color = color(0x697781)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.2f
        canvas.drawLine(155f, 170f, 205f, 170f, paint)
        paint.style = Paint.Style.FILL
        paint.color = color(FUXING_LIGHT)
        canvas.drawLine(180f, 172f, 180f, 180f, paint)
    }

    /** Pink nose and whiskers are drawn after the shared expressive face. */
    private fun drawFuxingFaceDetails(canvas: Canvas) {
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.15f
        paint.strokeCap = Paint.Cap.ROUND
        paint.color = color(FUXING_CREAM)
        canvas.drawLine(153f, 156f, 137f, 151f, paint)
        canvas.drawLine(153f, 160f, 136f, 160f, paint)
        canvas.drawLine(154f, 164f, 140f, 168f, paint)
        canvas.drawLine(207f, 156f, 223f, 151f, paint)
        canvas.drawLine(207f, 160f, 224f, 160f, paint)
        canvas.drawLine(206f, 164f, 220f, 168f, paint)
        paint.style = Paint.Style.FILL
        paint.strokeCap = Paint.Cap.BUTT
        val nose = Path().apply {
            moveTo(176.5f, 153f); quadTo(180f, 151f, 183.5f, 153f)
            lineTo(180f, 157f); close()
        }
        paint.color = color(FUXING_PINK)
        canvas.drawPath(nose, paint)
    }

    /** The little blue-framed portrait badge is Fuxing's signature detail. */
    private fun drawFuxingIdBadge(canvas: Canvas) {
        paint.color = color(FUXING_COLLAR)
        rect.set(169f, 177f, 191f, 200f)
        canvas.drawRoundRect(rect, 3f, 3f, paint)
        paint.color = color(0x697781)
        rect.set(171f, 178.5f, 189f, 198.5f)
        canvas.drawRoundRect(rect, 2f, 2f, paint)
        paint.color = color(FUXING_BADGE_BLUE)
        rect.set(172.5f, 180f, 187.5f, 194.5f)
        canvas.drawRoundRect(rect, 1.5f, 1.5f, paint)
        // Tiny portrait in the badge: ears, orange face and a cream muzzle.
        val leftEar = Path().apply {
            moveTo(176f, 186f); lineTo(176.5f, 182.5f); lineTo(179f, 185f); close()
        }
        val rightEar = Path().apply {
            moveTo(181f, 185f); lineTo(183.5f, 182.5f); lineTo(184f, 186f); close()
        }
        paint.color = color(FUXING_ORANGE)
        canvas.drawPath(leftEar, paint)
        canvas.drawPath(rightEar, paint)
        rect.set(176f, 184.5f, 184f, 191.5f)
        canvas.drawRoundRect(rect, 2.5f, 2.5f, paint)
        paint.color = color(FUXING_CREAM)
        canvas.drawOval(RectF(177.2f, 188f, 182.8f, 191.2f), paint)
        drawCircle(canvas, 178.2f, 187.5f, 0.65f, FACE)
        drawCircle(canvas, 181.8f, 187.5f, 0.65f, FACE)
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

    // ---------------------------------------------------------------- minigame selector + games

    private fun drawGameMenu(canvas: Canvas) {
        text(canvas, "选择小游戏", 180f, 31f, 17f, TEXT, bold = true)
        text(canvas, "玩一局，赚金币！", 180f, 48f, 9.5f, MUTED)

        // Four compact touch-friendly rows fit inside the round display.
        drawMiniGameCard(canvas, 56f, 108f, "卡点挑战", "抓准光点经过亮区的时机", "10 秒", 0)
        drawMiniGameCard(canvas, 112f, 164f, "猫爪快拍", "点击金币和鱼干，避开炸弹", "20 秒", 1)
        drawMiniGameCard(canvas, 168f, 220f, "福星接金币", "左右滑动，接金币鱼干躲石头", "30 秒", 2)
        drawMiniGameCard(canvas, 224f, 276f, "福星跳跳", "轻点跳跃，越过障碍收集奖励", "30 秒", 3)

        paint.color = color(PANEL)
        rect.set(126f, 304f, 234f, 334f)
        canvas.drawRoundRect(rect, 15f, 15f, paint)
        text(canvas, "返回主页", 180f, 323f, 11f, TEXT, bold = true)
    }

    private fun drawMiniGameCard(
        canvas: Canvas,
        top: Float,
        bottom: Float,
        title: String,
        subtitle: String,
        duration: String,
        iconType: Int
    ) {
        paint.color = color(PANEL)
        rect.set(47f, top, 313f, bottom)
        canvas.drawRoundRect(rect, 12f, 12f, paint)
        val cy = (top + bottom) / 2f
        when (iconType) {
            0 -> {
                paint.color = color(TRACK)
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 3.2f
                canvas.drawCircle(74f, cy, 13f, paint)
                paint.style = Paint.Style.FILL
                paint.color = color(MINT)
                canvas.drawArc(RectF(61f, cy - 13f, 87f, cy + 13f), -110f, 38f, false, paint)
                drawCircle(canvas, 74f, cy - 14f, 3.3f, GOLD)
            }
            1 -> {
                drawCircle(canvas, 74f, cy, 15f, 0x254B8068)
                drawCircle(canvas, 69f, cy - 3f, 4f, PINK)
                drawCircle(canvas, 78f, cy - 3f, 4f, MINT_LIGHT)
                drawCircle(canvas, 74f, cy + 4f, 5f, GOLD)
            }
            2 -> drawFuxingMiniIcon(canvas, 74f, cy)
            else -> {
                drawFuxingMiniIcon(canvas, 74f, cy - 1f)
                // A small golden arc hints at the jump action.
                paint.color = color(GOLD)
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 2f
                canvas.drawArc(RectF(56f, cy - 22f, 92f, cy + 6f), 205f, 115f, false, paint)
                paint.style = Paint.Style.FILL
            }
        }
        text(canvas, title, 99f, cy - 4f, 11.5f, TEXT, bold = true, align = Paint.Align.LEFT)
        text(canvas, subtitle, 99f, cy + 12f, 8.1f, MUTED, align = Paint.Align.LEFT)
        text(canvas, duration, 291f, cy + 1f, 8.2f, if (iconType == 0) MINT else GOLD, bold = true, align = Paint.Align.RIGHT)
    }

    private fun drawFuxingMiniIcon(canvas: Canvas, cx: Float, cy: Float) {
        drawCircle(canvas, cx, cy, 20f, 0x254B8068)
        paint.color = color(FUXING_ORANGE)
        canvas.drawRoundRect(RectF(cx - 12f, cy - 12f, cx + 12f, cy + 12f), 8f, 8f, paint)
        val leftEar = Path().apply { moveTo(cx - 12f, cy - 5f); lineTo(cx - 9f, cy - 18f); lineTo(cx - 2f, cy - 9f); close() }
        val rightEar = Path().apply { moveTo(cx + 2f, cy - 9f); lineTo(cx + 9f, cy - 18f); lineTo(cx + 12f, cy - 5f); close() }
        paint.color = color(FUXING_ORANGE)
        canvas.drawPath(leftEar, paint)
        canvas.drawPath(rightEar, paint)
        drawCircle(canvas, cx - 5f, cy - 2f, 1.7f, FACE)
        drawCircle(canvas, cx + 5f, cy - 2f, 1.7f, FACE)
        paint.color = color(FUXING_COLLAR)
        rect.set(cx - 5f, cy + 5f, cx + 5f, cy + 13f)
        canvas.drawRoundRect(rect, 1.5f, 1.5f, paint)
        paint.color = color(FUXING_BADGE_BLUE)
        rect.set(cx - 3.5f, cy + 6f, cx + 3.5f, cy + 11f)
        canvas.drawRoundRect(rect, 1f, 1f, paint)
    }

    private fun drawCatPawGame(canvas: Canvas) {
        val elapsed = gameElapsed().coerceAtMost(CAT_PAW_GAME_LENGTH_MS)
        val remaining = ((CAT_PAW_GAME_LENGTH_MS - elapsed + 999L) / 1_000L).toInt().coerceAtLeast(0)
        text(canvas, "猫爪快拍", 180f, 34f, 17f, TEXT, bold = true)
        text(canvas, "拍金币和鱼干，避开炸弹！", 180f, 54f, 10f, MUTED)

        paint.color = color(TRACK)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f
        canvas.drawCircle(180f, 166f, 75f, paint)
        paint.style = Paint.Style.FILL

        if (elapsed < catPawTargetExpiresAt) {
            drawCatPawTarget(canvas, catPawTarget, catPawTargetX, catPawTargetY)
        }

        text(canvas, "得分 $catPawRawScore", 180f, 260f, 12f, TEXT, bold = true)
        text(canvas, "剩余 ${remaining} 秒", 180f, 279f, 10f, MUTED)
        drawBar(canvas, 78f, 287f, 282f, 1f - elapsed.toFloat() / CAT_PAW_GAME_LENGTH_MS, GOLD)
        text(canvas, catPawHint, 180f, 304f, 9f, if (catPawHint.contains("命中") || catPawHint.contains("接住")) MINT else MUTED)
        paint.color = color(PANEL)
        rect.set(139f, 313f, 221f, 339f)
        canvas.drawRoundRect(rect, 13f, 13f, paint)
        text(canvas, "结束", 180f, 330f, 10f, TEXT)
    }

    private fun drawCatPawTarget(canvas: Canvas, target: CatPawTarget, cx: Float, cy: Float) {
        when (target) {
            CatPawTarget.COIN -> {
                drawCircle(canvas, cx, cy, 22f, 0x254B8068)
                drawCircle(canvas, cx, cy, 15f, GOLD)
                drawCircle(canvas, cx, cy, 10f, 0xFFB98B37.toInt())
                text(canvas, "$", cx, cy + 5f, 16f, TEXT, bold = true)
            }
            CatPawTarget.FISH -> {
                val body = Path().apply {
                    moveTo(cx - 14f, cy)
                    quadTo(cx - 4f, cy - 13f, cx + 9f, cy - 4f)
                    quadTo(cx + 14f, cy, cx + 9f, cy + 4f)
                    quadTo(cx - 4f, cy + 13f, cx - 14f, cy)
                    close()
                }
                val tail = Path().apply {
                    moveTo(cx + 9f, cy); lineTo(cx + 20f, cy - 9f); lineTo(cx + 20f, cy + 9f); close()
                }
                paint.color = color(MINT)
                canvas.drawPath(body, paint)
                canvas.drawPath(tail, paint)
                drawCircle(canvas, cx - 8f, cy - 2f, 1.8f, FACE)
            }
            CatPawTarget.BOMB -> {
                drawCircle(canvas, cx, cy + 2f, 16f, 0xFF344047.toInt())
                val fuse = Path().apply {
                    moveTo(cx + 1f, cy - 13f); quadTo(cx + 6f, cy - 22f, cx + 12f, cy - 18f)
                }
                paint.color = color(GOLD)
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 3f
                paint.strokeCap = Paint.Cap.ROUND
                canvas.drawPath(fuse, paint)
                paint.style = Paint.Style.FILL
                paint.strokeCap = Paint.Cap.BUTT
                drawSparkle(canvas, cx + 13f, cy - 19f, 3.5f, PINK, 255)
                text(canvas, "!", cx, cy + 7f, 15f, TEXT, bold = true)
            }
        }
    }

    // ---------------------------------------------------------------- Fuxing catches coins

    private fun drawFuxingCatchGame(canvas: Canvas) {
        val elapsed = gameElapsed().coerceAtMost(FUXING_CATCH_GAME_LENGTH_MS)
        val remaining = ((FUXING_CATCH_GAME_LENGTH_MS - elapsed + 999L) / 1_000L).toInt().coerceAtLeast(0)
        text(canvas, "福星接金币", 180f, 32f, 16f, TEXT, bold = true)
        text(canvas, "左右滑动福星，接金币和鱼干！", 180f, 51f, 9.5f, MUTED)

        // Soft lane lines stay inside the circular screen's safe area.
        paint.color = color(TRACK)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.5f
        canvas.drawLine(78f, 73f, 78f, 260f, paint)
        canvas.drawLine(282f, 73f, 282f, 260f, paint)
        paint.style = Paint.Style.FILL

        for (item in fuxingCatchItems) drawFuxingCatchItem(canvas, item)
        drawFuxingCatcher(canvas)

        text(canvas, "得分 ${fuxingCatchRawScore}", 112f, 284f, 11f, TEXT, bold = true)
        text(canvas, "剩余 ${remaining} 秒", 248f, 284f, 10f, MUTED)
        drawBar(canvas, 78f, 292f, 282f, 1f - elapsed.toFloat() / FUXING_CATCH_GAME_LENGTH_MS, GOLD)
        text(canvas, fuxingCatchHint, 180f, 308f, 8.8f, if (fuxingCatchHint.contains("接住") || fuxingCatchHint.contains("金币 +") || fuxingCatchHint.contains("鱼干 +")) MINT else MUTED)
        paint.color = color(PANEL)
        rect.set(139f, 315f, 221f, 339f)
        canvas.drawRoundRect(rect, 12f, 12f, paint)
        text(canvas, "结束", 180f, 331f, 10f, TEXT)
    }

    private fun drawFuxingCatchItem(canvas: Canvas, item: FallingCatchItem) {
        when (item.kind) {
            FallingKind.COIN -> {
                drawCircle(canvas, item.x, item.y, 13f, 0x254B8068)
                drawCircle(canvas, item.x, item.y, 9f, GOLD)
                paint.color = color(0xFFB98B37.toInt())
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 1.5f
                canvas.drawCircle(item.x, item.y, 5.5f, paint)
                paint.style = Paint.Style.FILL
                drawCircle(canvas, item.x, item.y, 2f, TEXT)
            }
            FallingKind.FISH -> {
                val body = Path().apply {
                    moveTo(item.x - 12f, item.y)
                    quadTo(item.x - 2f, item.y - 10f, item.x + 8f, item.y - 3f)
                    quadTo(item.x + 13f, item.y, item.x + 8f, item.y + 3f)
                    quadTo(item.x - 2f, item.y + 10f, item.x - 12f, item.y)
                    close()
                }
                val tail = Path().apply {
                    moveTo(item.x + 7f, item.y)
                    lineTo(item.x + 16f, item.y - 7f)
                    lineTo(item.x + 16f, item.y + 7f)
                    close()
                }
                paint.color = color(MINT_LIGHT)
                canvas.drawPath(body, paint)
                paint.color = color(MINT)
                canvas.drawPath(tail, paint)
                drawCircle(canvas, item.x - 6f, item.y - 1.5f, 1.5f, FACE)
            }
            FallingKind.ROCK -> {
                val rock = Path().apply {
                    moveTo(item.x - 11f, item.y + 6f)
                    lineTo(item.x - 8f, item.y - 7f)
                    lineTo(item.x + 1f, item.y - 12f)
                    lineTo(item.x + 11f, item.y - 3f)
                    lineTo(item.x + 8f, item.y + 8f)
                    close()
                }
                paint.color = color(0xFF697780.toInt())
                canvas.drawPath(rock, paint)
                paint.color = color(0xFF9AA6AC.toInt())
                canvas.drawLine(item.x - 4f, item.y - 5f, item.x + 2f, item.y - 7f, paint)
            }
        }
    }

    private fun drawFuxingCatcher(canvas: Canvas) {
        val catcherY = FUXING_CATCHER_Y
        drawCircle(canvas, fuxingCatchX, catcherY + 2f, 24f, HALO)
        canvas.save()
        canvas.translate(fuxingCatchX, catcherY)
        canvas.scale(FUXING_CATCHER_SCALE, FUXING_CATCHER_SCALE)
        canvas.translate(-180f, -150f)
        val visual = PetVisuals()
        drawFuxingBase(canvas)
        drawFace(canvas, visual)
        drawFuxingFaceDetails(canvas)
        // The blue-framed badge remains visible on the mini-game catcher.
        drawFuxingIdBadge(canvas)
        canvas.restore()
    }

    private fun startFuxingCatchGame() {
        mode = Mode.FUXING_CATCH
        gameElapsedBeforePause = 0L
        gameStartedAt = SystemClock.elapsedRealtime()
        gameResultSent = false
        fuxingCatchX = 180f
        fuxingCatchRawScore = 0
        fuxingCatchHint = "左右滑动福星，接住金币和鱼干！"
        fuxingNextItemId = 1L
        fuxingNextSpawnAt = 0L
        fuxingLastUpdateAt = SystemClock.elapsedRealtime()
        fuxingCatchItems.clear()
        spawnFuxingCatchItem(0L)
        fuxingNextSpawnAt = FUXING_FIRST_SPAWN_MS
        invalidate()
    }

    private fun spawnFuxingCatchItem(elapsed: Long) {
        if (fuxingCatchItems.size >= FUXING_MAX_ITEMS) return
        val roll = Random.nextInt(100)
        val kind = when {
            roll < 58 -> FallingKind.COIN
            roll < 82 -> FallingKind.FISH
            else -> FallingKind.ROCK
        }
        val difficulty = if (elapsed >= 15_000L) 1.22f else 1f
        val speed = Random.nextInt(FUXING_MIN_FALL_SPEED, FUXING_MAX_FALL_SPEED + 1) * difficulty
        val x = Random.nextInt(FUXING_MIN_X.toInt(), FUXING_MAX_X.toInt() + 1).toFloat()
        fuxingCatchItems += FallingCatchItem(
            id = fuxingNextItemId++,
            x = x,
            y = FUXING_SPAWN_Y,
            kind = kind,
            speed = speed
        )
    }

    private fun updateFuxingCatch() {
        val now = SystemClock.elapsedRealtime()
        if (fuxingLastUpdateAt == 0L) fuxingLastUpdateAt = now
        val deltaMs = (now - fuxingLastUpdateAt).coerceIn(0L, FUXING_MAX_FRAME_DELTA_MS)
        fuxingLastUpdateAt = now
        val elapsed = gameElapsed()

        if (elapsed >= fuxingNextSpawnAt && fuxingCatchItems.size < FUXING_MAX_ITEMS) {
            spawnFuxingCatchItem(elapsed)
            val interval = if (elapsed >= 15_000L) FUXING_SPAWN_INTERVAL_LATE_MS else FUXING_SPAWN_INTERVAL_MS
            fuxingNextSpawnAt = elapsed + Random.nextLong(interval.first, interval.last + 1L)
        }

        val deltaSeconds = deltaMs.toFloat() / 1_000f
        val iterator = fuxingCatchItems.iterator()
        while (iterator.hasNext()) {
            val item = iterator.next()
            item.y += item.speed * deltaSeconds
            val inCatchBand = item.y >= FUXING_CATCHER_Y - FUXING_CATCH_BAND_ABOVE &&
                item.y <= FUXING_CATCHER_Y + FUXING_CATCH_BAND_BELOW
            if (inCatchBand && abs(item.x - fuxingCatchX) <= FUXING_CATCH_HALF_WIDTH) {
                when (item.kind) {
                    FallingKind.COIN -> {
                        playFeedback(SoundCue.GOOD)
                        fuxingCatchRawScore = (fuxingCatchRawScore + 1).coerceAtMost(FUXING_RAW_SCORE_CAP)
                        fuxingCatchHint = "接住金币！+1"
                    }
                    FallingKind.FISH -> {
                        playFeedback(SoundCue.GOOD)
                        fuxingCatchRawScore = (fuxingCatchRawScore + 2).coerceAtMost(FUXING_RAW_SCORE_CAP)
                        fuxingCatchHint = "接住鱼干！+2"
                    }
                    FallingKind.ROCK -> {
                        playFeedback(SoundCue.BAD)
                        fuxingCatchRawScore = (fuxingCatchRawScore - 2).coerceAtLeast(0)
                        fuxingCatchHint = "哎呀，接到石头了！-2"
                    }
                }
                iterator.remove()
            } else if (item.y > FUXING_CATCHER_Y + FUXING_CATCH_BAND_BELOW || item.y > FUXING_DESPAWN_Y) {
                iterator.remove()
            }
        }
        invalidate()
    }

    // ---------------------------------------------------------------- Fuxing Jump

    private fun drawFuxingJumpGame(canvas: Canvas) {
        val elapsed = gameElapsed().coerceAtMost(FUXING_JUMP_GAME_LENGTH_MS)
        val remaining = ((FUXING_JUMP_GAME_LENGTH_MS - elapsed + 999L) / 1_000L)
            .toInt().coerceAtLeast(0)
        text(canvas, "福星跳跳", 180f, 32f, 16f, TEXT, bold = true)
        text(canvas, "轻点跳跃，越过纸箱和毛线球！", 180f, 51f, 9.5f, MUTED)

        // Background dashes create a subtle sense of forward movement.
        val dashPhase = ((elapsed / 85L) % 48L).toFloat()
        for (i in 0..5) {
            val dx = 42f + i * 54f - dashPhase
            if (dx in 26f..334f) drawCircle(canvas, dx, 91f + (i % 2) * 14f, 1.4f, TRACK)
        }

        // Ground plane stays below all collectibles and the runner.
        paint.color = color(TRACK)
        rect.set(36f, FUXING_JUMP_GROUND_Y, 324f, FUXING_JUMP_GROUND_Y + 3f)
        canvas.drawRoundRect(rect, 1.5f, 1.5f, paint)
        for (i in 0..7) {
            val dx = 42f + i * 42f - ((elapsed / 50L) % 42L).toFloat()
            if (dx in 36f..324f) {
                paint.color = color(TRACK)
                rect.set(dx, FUXING_JUMP_GROUND_Y + 8f, dx + 12f, FUXING_JUMP_GROUND_Y + 10f)
                canvas.drawRoundRect(rect, 1f, 1f, paint)
            }
        }

        fuxingJumpObjects.forEach { drawFuxingJumpObject(canvas, it) }
        drawFuxingJumpRunner(canvas, jumpHeightAt(elapsed))

        text(canvas, "得分 $fuxingJumpRawScore", 112f, 280f, 11f, TEXT, bold = true)
        text(canvas, "剩余 ${remaining} 秒", 248f, 280f, 10f, MUTED)
        drawBar(canvas, 78f, 290f, 282f, 1f - elapsed.toFloat() / FUXING_JUMP_GAME_LENGTH_MS, GOLD)
        text(
            canvas,
            fuxingJumpHint,
            180f,
            307f,
            8.8f,
            if (fuxingJumpHint.contains("接住") || fuxingJumpHint.contains("躲开") || fuxingJumpHint.contains("跳起来")) MINT else MUTED
        )
        paint.color = color(PANEL)
        rect.set(139f, 315f, 221f, 339f)
        canvas.drawRoundRect(rect, 12f, 12f, paint)
        text(canvas, "结束", 180f, 331f, 10f, TEXT)
    }

    private fun drawFuxingJumpObject(canvas: Canvas, item: JumpingObject) {
        when (item.kind) {
            JumpingKind.BOX -> {
                val top = FUXING_JUMP_GROUND_Y - 25f
                paint.color = color(0xFFB9855B.toInt())
                rect.set(item.x - 14f, top, item.x + 14f, FUXING_JUMP_GROUND_Y)
                canvas.drawRoundRect(rect, 3f, 3f, paint)
                paint.color = color(0xFFE7C19A.toInt())
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 2f
                canvas.drawLine(item.x - 12f, top + 5f, item.x + 12f, FUXING_JUMP_GROUND_Y - 5f, paint)
                canvas.drawLine(item.x + 12f, top + 5f, item.x - 12f, FUXING_JUMP_GROUND_Y - 5f, paint)
                paint.style = Paint.Style.FILL
            }
            JumpingKind.YARN -> {
                drawCircle(canvas, item.x, FUXING_JUMP_GROUND_Y - 10f, 11f, PINK)
                paint.color = color(0xFFFFDCE4.toInt())
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 1.5f
                canvas.drawArc(RectF(item.x - 7f, FUXING_JUMP_GROUND_Y - 17f, item.x + 7f, FUXING_JUMP_GROUND_Y - 3f), 10f, 145f, false, paint)
                canvas.drawArc(RectF(item.x - 5f, FUXING_JUMP_GROUND_Y - 16f, item.x + 6f, FUXING_JUMP_GROUND_Y - 4f), 190f, 140f, false, paint)
                paint.style = Paint.Style.FILL
            }
            JumpingKind.COIN -> {
                drawCircle(canvas, item.x, FUXING_JUMP_GROUND_Y - 43f, 13f, 0x254B8068)
                drawCircle(canvas, item.x, FUXING_JUMP_GROUND_Y - 43f, 9f, GOLD)
                paint.color = color(0xFFB98B37.toInt())
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 1.3f
                canvas.drawCircle(item.x, FUXING_JUMP_GROUND_Y - 43f, 5.5f, paint)
                paint.style = Paint.Style.FILL
                drawCircle(canvas, item.x, FUXING_JUMP_GROUND_Y - 43f, 1.7f, TEXT)
            }
            JumpingKind.FISH -> {
                val cy = FUXING_JUMP_GROUND_Y - 43f
                val body = Path().apply {
                    moveTo(item.x - 12f, cy)
                    quadTo(item.x - 2f, cy - 10f, item.x + 8f, cy - 3f)
                    quadTo(item.x + 13f, cy, item.x + 8f, cy + 3f)
                    quadTo(item.x - 2f, cy + 10f, item.x - 12f, cy)
                    close()
                }
                val tail = Path().apply {
                    moveTo(item.x + 7f, cy); lineTo(item.x + 16f, cy - 7f); lineTo(item.x + 16f, cy + 7f); close()
                }
                paint.color = color(MINT_LIGHT)
                canvas.drawPath(body, paint)
                paint.color = color(MINT)
                canvas.drawPath(tail, paint)
                drawCircle(canvas, item.x - 6f, cy - 1.5f, 1.4f, FACE)
            }
        }
    }

    private fun drawFuxingJumpRunner(canvas: Canvas, jumpHeight: Float) {
        val centerY = FUXING_JUMP_BASE_Y - jumpHeight
        drawCircle(canvas, FUXING_JUMP_RUNNER_X, FUXING_JUMP_GROUND_Y + 2f, 19f, SHADOW)
        canvas.save()
        canvas.translate(FUXING_JUMP_RUNNER_X, centerY)
        // Compress slightly for the runner view while retaining Fuxing's chest badge.
        canvas.scale(FUXING_JUMP_SCALE, FUXING_JUMP_SCALE)
        canvas.translate(-180f, -150f)
        val neutral = PetVisuals()
        drawFuxingBase(canvas)
        drawFace(canvas, neutral)
        drawFuxingFaceDetails(canvas)
        drawFuxingIdBadge(canvas)
        canvas.restore()

        if (jumpHeight > 8f) {
            paint.color = color(MINT_LIGHT)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1.4f
            canvas.drawArc(
                RectF(FUXING_JUMP_RUNNER_X - 26f, centerY - 30f, FUXING_JUMP_RUNNER_X + 26f, centerY + 24f),
                205f, 75f, false, paint
            )
            paint.style = Paint.Style.FILL
        }
    }

    private fun startFuxingJumpGame() {
        mode = Mode.FUXING_JUMP
        gameElapsedBeforePause = 0L
        gameStartedAt = SystemClock.elapsedRealtime()
        gameResultSent = false
        fuxingJumpObjects.clear()
        fuxingJumpRawScore = 0
        fuxingJumpHint = "轻点跳跃，越过障碍并收集奖励！"
        fuxingJumpStartedAt = -FUXING_JUMP_DURATION_MS
        fuxingJumpNextSpawnAt = FUXING_JUMP_FIRST_SPAWN_MS
        fuxingJumpLastElapsed = 0L
        fuxingJumpNextId = 1L
        spawnFuxingJumpObject(0L)
        fuxingJumpNextSpawnAt = FUXING_JUMP_FIRST_SPAWN_MS
        invalidate()
    }

    private fun spawnFuxingJumpObject(elapsed: Long) {
        if (fuxingJumpObjects.size >= FUXING_JUMP_MAX_OBJECTS) return
        val roll = Random.nextInt(100)
        val kind = when {
            roll < 25 -> JumpingKind.BOX
            roll < 45 -> JumpingKind.YARN
            roll < 74 -> JumpingKind.COIN
            else -> JumpingKind.FISH
        }
        val speedMultiplier = if (elapsed >= 15_000L) 1.18f else 1f
        val speed = Random.nextInt(FUXING_JUMP_MIN_SPEED, FUXING_JUMP_MAX_SPEED + 1) * speedMultiplier
        fuxingJumpObjects += JumpingObject(
            id = fuxingJumpNextId++,
            x = FUXING_JUMP_SPAWN_X,
            kind = kind,
            speed = speed
        )
    }

    private fun jumpHeightAt(elapsed: Long): Float {
        val jumpElapsed = elapsed - fuxingJumpStartedAt
        if (jumpElapsed < 0L || jumpElapsed > FUXING_JUMP_DURATION_MS) return 0f
        val t = jumpElapsed.toFloat() / FUXING_JUMP_DURATION_MS.toFloat()
        return (4f * FUXING_JUMP_MAX_HEIGHT * t * (1f - t)).coerceAtLeast(0f)
    }

    private fun tryStartFuxingJump(elapsed: Long) {
        if (jumpHeightAt(elapsed) > 1f) return // no double-jump while airborne
        playFeedback(SoundCue.TAP)
        fuxingJumpStartedAt = elapsed
        fuxingJumpHint = "跳起来啦！"
        invalidate()
    }

    private fun updateFuxingJump() {
        val elapsed = gameElapsed()
        val deltaMs = (elapsed - fuxingJumpLastElapsed).coerceIn(0L, FUXING_JUMP_MAX_FRAME_DELTA_MS)
        fuxingJumpLastElapsed = elapsed
        if (elapsed >= fuxingJumpNextSpawnAt && fuxingJumpObjects.size < FUXING_JUMP_MAX_OBJECTS) {
            spawnFuxingJumpObject(elapsed)
            val interval = if (elapsed >= 15_000L) {
                Random.nextLong(FUXING_JUMP_SPAWN_LATE_MIN_MS, FUXING_JUMP_SPAWN_LATE_MAX_MS + 1L)
            } else {
                Random.nextLong(FUXING_JUMP_SPAWN_MIN_MS, FUXING_JUMP_SPAWN_MAX_MS + 1L)
            }
            fuxingJumpNextSpawnAt = elapsed + interval
        }

        val deltaSeconds = deltaMs.toFloat() / 1_000f
        val jumpHeight = jumpHeightAt(elapsed)
        val iterator = fuxingJumpObjects.iterator()
        while (iterator.hasNext()) {
            val item = iterator.next()
            val previousX = item.x
            item.x -= item.speed * deltaSeconds
            val crossedRunner = previousX > FUXING_JUMP_RUNNER_X + FUXING_JUMP_COLLISION_X &&
                item.x <= FUXING_JUMP_RUNNER_X + FUXING_JUMP_COLLISION_X
            if (crossedRunner) {
                when (item.kind) {
                    JumpingKind.COIN -> {
                        playFeedback(SoundCue.GOOD)
                        fuxingJumpRawScore = (fuxingJumpRawScore + 1).coerceAtMost(FUXING_JUMP_RAW_SCORE_CAP)
                        fuxingJumpHint = "接住金币！+1"
                    }
                    JumpingKind.FISH -> {
                        playFeedback(SoundCue.GOOD)
                        fuxingJumpRawScore = (fuxingJumpRawScore + 2).coerceAtMost(FUXING_JUMP_RAW_SCORE_CAP)
                        fuxingJumpHint = "接住鱼干！+2"
                    }
                    JumpingKind.BOX, JumpingKind.YARN -> {
                        val safeJumpHeight = if (item.kind == JumpingKind.BOX) 29f else 20f
                        if (jumpHeight >= safeJumpHeight) {
                            playFeedback(SoundCue.GOOD)
                            fuxingJumpHint = "漂亮！躲开障碍！"
                        } else {
                            playFeedback(SoundCue.BAD)
                            fuxingJumpRawScore = (fuxingJumpRawScore - FUXING_JUMP_COLLISION_PENALTY).coerceAtLeast(0)
                            fuxingJumpHint = "哎呀，撞到障碍了！-${FUXING_JUMP_COLLISION_PENALTY}"
                        }
                    }
                }
                iterator.remove()
            } else if (item.x < FUXING_JUMP_REMOVE_X) {
                iterator.remove()
            }
        }
        invalidate()
    }

    private fun startGameMenu() {
        cancelScrollFling()
        mode = Mode.GAME_MENU
        invalidate()
    }

    private fun startCatPawGame() {
        mode = Mode.CAT_PAW
        gameElapsedBeforePause = 0L
        gameStartedAt = SystemClock.elapsedRealtime()
        gameResultSent = false
        catPawRawScore = 0
        catPawHint = "快速点击金币和鱼干，避开炸弹！"
        spawnCatPawTarget(0L)
        invalidate()
    }

    private fun spawnCatPawTarget(elapsed: Long) {
        catPawTarget = when (Random.nextInt(100)) {
            in 0..44 -> CatPawTarget.COIN
            in 45..79 -> CatPawTarget.FISH
            else -> CatPawTarget.BOMB
        }
        // Keep targets comfortably inside the circular screen's safe area.
        catPawTargetX = Random.nextInt(126, 235).toFloat()
        catPawTargetY = Random.nextInt(112, 220).toFloat()
        catPawTargetExpiresAt = elapsed + CAT_PAW_TARGET_LIFETIME_MS
    }

    private fun updateCatPawTarget() {
        val elapsed = gameElapsed()
        if (elapsed >= catPawTargetExpiresAt) spawnCatPawTarget(elapsed)
    }

    private fun catPawHitAt(x: Float, y: Float) {
        val elapsed = gameElapsed()
        if (elapsed >= catPawTargetExpiresAt) {
            spawnCatPawTarget(elapsed)
            catPawHint = "新目标出现啦，快拍！"
            invalidate()
            return
        }
        val dx = x - catPawTargetX
        val dy = y - catPawTargetY
        if (dx * dx + dy * dy > CAT_PAW_HIT_RADIUS * CAT_PAW_HIT_RADIUS) {
            playFeedback(SoundCue.BAD)
            catPawHint = "没拍中，再试试！"
            invalidate()
            return
        }

        when (catPawTarget) {
            CatPawTarget.COIN -> {
                playFeedback(SoundCue.GOOD)
                catPawRawScore = (catPawRawScore + 1).coerceAtMost(CAT_PAW_RAW_SCORE_CAP)
                catPawHint = "命中金币！+1"
            }
            CatPawTarget.FISH -> {
                playFeedback(SoundCue.GOOD)
                catPawRawScore = (catPawRawScore + 2).coerceAtMost(CAT_PAW_RAW_SCORE_CAP)
                catPawHint = "接住鱼干！+2"
            }
            CatPawTarget.BOMB -> {
                playFeedback(SoundCue.BAD)
                catPawRawScore = (catPawRawScore - 2).coerceAtLeast(0)
                catPawHint = "哎呀，拍到炸弹了！-2"
            }
        }
        spawnCatPawTarget(elapsed)
        invalidate()
    }

    private enum class CatPawTarget { COIN, FISH, BOMB }

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

    private fun isTimedGameMode(): Boolean =
        mode == Mode.GAME || mode == Mode.CAT_PAW || mode == Mode.FUXING_CATCH || mode == Mode.FUXING_JUMP

    private fun activeGameLengthMs(): Long = when (mode) {
        Mode.CAT_PAW -> CAT_PAW_GAME_LENGTH_MS
        Mode.FUXING_CATCH -> FUXING_CATCH_GAME_LENGTH_MS
        Mode.FUXING_JUMP -> FUXING_JUMP_GAME_LENGTH_MS
        else -> GAME_LENGTH_MS
    }

    private fun gameElapsed(): Long {
        val runningSegment = if (isTimedGameMode() && isInteractive && gameStartedAt != 0L) {
            (SystemClock.elapsedRealtime() - gameStartedAt).coerceAtLeast(0L)
        } else {
            0L
        }
        return (gameElapsedBeforePause + runningSegment).coerceAtLeast(0L)
    }

    private fun rewardScoreForCurrentGame(): Int = when (mode) {
        Mode.CAT_PAW -> (catPawRawScore / CAT_PAW_POINTS_PER_REWARD).coerceIn(0, PetState.MAX_GAME_SCORE)
        Mode.FUXING_CATCH -> (fuxingCatchRawScore / FUXING_POINTS_PER_REWARD).coerceIn(0, PetState.MAX_GAME_SCORE)
        Mode.FUXING_JUMP -> (fuxingJumpRawScore / FUXING_JUMP_POINTS_PER_REWARD).coerceIn(0, PetState.MAX_GAME_SCORE)
        else -> gameScore.coerceIn(0, PetState.MAX_GAME_SCORE)
    }

    private fun finishGameNormally() {
        if (!isTimedGameMode() || gameResultSent) return
        playFeedback(SoundCue.GOOD)
        val finishedMode = mode
        val gameId = when (finishedMode) {
            Mode.CAT_PAW -> "cat_paw"
            Mode.FUXING_CATCH -> "fuxing_catch"
            Mode.FUXING_JUMP -> "fuxing_jump"
            else -> "timing"
        }
        val rewardScore = rewardScoreForMode(finishedMode)
        gameResultSent = true
        mode = Mode.HOME
        gameStartedAt = 0L
        listener?.onMiniGameFinished(
            gameId = gameId,
            score = rewardScore,
            completedNormally = true
        )
        invalidate()
    }

    private fun finishGameEarly() {
        if (!isTimedGameMode() || gameResultSent) return
        playFeedback(SoundCue.TAP)
        val finishedMode = mode
        val gameId = when (finishedMode) {
            Mode.CAT_PAW -> "cat_paw"
            Mode.FUXING_CATCH -> "fuxing_catch"
            Mode.FUXING_JUMP -> "fuxing_jump"
            else -> "timing"
        }
        val rewardScore = rewardScoreForMode(finishedMode)
        gameResultSent = true
        mode = Mode.HOME
        gameStartedAt = 0L
        listener?.onMiniGameFinished(
            gameId = gameId,
            score = rewardScore,
            completedNormally = false
        )
        invalidate()
    }

    private fun rewardScoreForMode(finishedMode: Mode): Int = when (finishedMode) {
        Mode.CAT_PAW -> (catPawRawScore / CAT_PAW_POINTS_PER_REWARD).coerceIn(0, PetState.MAX_GAME_SCORE)
        Mode.FUXING_CATCH -> (fuxingCatchRawScore / FUXING_POINTS_PER_REWARD).coerceIn(0, PetState.MAX_GAME_SCORE)
        Mode.FUXING_JUMP -> (fuxingJumpRawScore / FUXING_JUMP_POINTS_PER_REWARD).coerceIn(0, PetState.MAX_GAME_SCORE)
        else -> gameScore.coerceIn(0, PetState.MAX_GAME_SCORE)
    }

    private fun cancelScrollFling() {
        handler.removeCallbacks(scrollFlingRunnable)
        flingMode = null
        scrollVelocityPerFrame = 0f
    }

    private fun startScrollFling(listMode: Mode, velocityPerFrame: Float) {
        cancelScrollFling()
        if (!isInteractive || (listMode != Mode.TASKS && listMode != Mode.SHOP)) return
        val boundedVelocity = velocityPerFrame.coerceIn(-MAX_FLING_VELOCITY, MAX_FLING_VELOCITY)
        if (kotlin.math.abs(boundedVelocity) < FLING_START_THRESHOLD) return
        flingMode = listMode
        scrollVelocityPerFrame = boundedVelocity
        handler.postDelayed(scrollFlingRunnable, FLING_FRAME_MS)
    }

    private fun scrollPosition(listMode: Mode): Float = when (listMode) {
        Mode.TASKS -> taskScrollY
        Mode.SHOP -> shopScrollY
        else -> 0f
    }

    private fun maxScrollFor(listMode: Mode): Float = when (listMode) {
        Mode.TASKS -> maxTaskScroll()
        Mode.SHOP -> maxShopScroll(ShopCatalog.items(shopCategory).size)
        else -> 0f
    }

    private fun setScrollPosition(listMode: Mode, value: Float) {
        val safeValue = value.coerceIn(0f, maxScrollFor(listMode))
        when (listMode) {
            Mode.TASKS -> taskScrollY = safeValue
            Mode.SHOP -> shopScrollY = safeValue
            else -> return
        }
    }

    private fun applyScrollDelta(listMode: Mode, delta: Float) {
        setScrollPosition(listMode, scrollPosition(listMode) + delta)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val logicalScale = min(width.toFloat(), height.toFloat()) / DESIGN_SIZE
        if (logicalScale <= 0f) return true
        val offsetX = (width - DESIGN_SIZE * logicalScale) / 2f
        val offsetY = (height - DESIGN_SIZE * logicalScale) / 2f
        val x = (event.x - offsetX) / logicalScale
        val y = (event.y - offsetY) / logicalScale

        if (event.action == MotionEvent.ACTION_DOWN) {
            cancelScrollFling()
            if (mode == Mode.FUXING_CATCH && y < 307f) {
                fuxingCatchX = x.coerceIn(FUXING_MIN_X, FUXING_MAX_X)
                invalidate()
            }
            if (mode == Mode.FUXING_JUMP && y < FUXING_JUMP_END_BUTTON_TOP) {
                tryStartFuxingJump(gameElapsed())
            }
            touchDownX = x
            touchDownY = y
            lastTouchY = y
            touchDownTime = event.eventTime
            lastMoveTime = event.eventTime
            touchStartMode = mode
            touchStartedInScrollableViewport = when (mode) {
                Mode.TASKS -> y in DETAIL_VIEWPORT_TOP..DETAIL_VIEWPORT_BOTTOM
                Mode.SHOP -> y in DETAIL_VIEWPORT_TOP..DETAIL_VIEWPORT_BOTTOM
                else -> false
            }
            draggingList = false
            return true
        }

        if (event.action == MotionEvent.ACTION_MOVE) {
            if (mode == Mode.FUXING_CATCH) {
                if (y < 307f) fuxingCatchX = x.coerceIn(FUXING_MIN_X, FUXING_MAX_X)
                lastTouchY = y
                lastMoveTime = event.eventTime
                invalidate()
                return true
            }
            if (touchStartedInScrollableViewport && touchStartMode == mode) {
                val totalX = x - touchDownX
                val totalY = y - touchDownY
                val wasDragging = draggingList
                if (!draggingList && kotlin.math.abs(totalY) >= DRAG_START_THRESHOLD &&
                    kotlin.math.abs(totalY) > kotlin.math.abs(totalX)) {
                    draggingList = true
                }

                if (draggingList) {
                    val dtSinceDown = (event.eventTime - touchDownTime).coerceAtLeast(1L).toFloat()
                    val dtSinceMove = (event.eventTime - lastMoveTime).coerceAtLeast(1L).toFloat()
                    val contentDelta = if (!wasDragging) touchDownY - y else lastTouchY - y
                    applyScrollDelta(touchStartMode, contentDelta)
                    val measuredVelocity = if (!wasDragging) {
                        contentDelta * FLING_FRAME_MS / dtSinceDown
                    } else {
                        (lastTouchY - y) * FLING_FRAME_MS / dtSinceMove
                    }
                    scrollVelocityPerFrame = (
                        scrollVelocityPerFrame * VELOCITY_SMOOTHING +
                            measuredVelocity * (1f - VELOCITY_SMOOTHING)
                        ).coerceIn(-MAX_FLING_VELOCITY, MAX_FLING_VELOCITY)
                    invalidate()
                }
            }
            lastTouchY = y
            lastMoveTime = event.eventTime
            return true
        }

        if (event.action == MotionEvent.ACTION_CANCEL) {
            draggingList = false
            touchStartedInScrollableViewport = false
            scrollVelocityPerFrame = 0f
            return true
        }

        if (event.action != MotionEvent.ACTION_UP) return true

        if (draggingList) {
            startScrollFling(touchStartMode, scrollVelocityPerFrame)
            draggingList = false
            touchStartedInScrollableViewport = false
            invalidate()
            return true
        }
        touchStartedInScrollableViewport = false

        if (mode == Mode.TASKS) {
            if (x in 120f..240f && y in 300f..340f) {
                playFeedback(SoundCue.TAP)
                mode = Mode.HOME
                invalidate()
            }
            return true
        }

        if (mode == Mode.SHOP) {
            if (y in 55f..89f) {
                when {
                    x in 40f..176f -> { playFeedback(SoundCue.TAP); shopCategory = ShopItemType.ACCESSORY; shopScrollY = 0f }
                    x in 184f..320f -> { playFeedback(SoundCue.TAP); shopCategory = ShopItemType.PET; shopScrollY = 0f }
                }
                invalidate()
                return true
            }
            if (x in 120f..240f && y in 300f..340f) {
                playFeedback(SoundCue.TAP)
                mode = Mode.HOME
                invalidate()
                return true
            }
            if (y in DETAIL_VIEWPORT_TOP..DETAIL_VIEWPORT_BOTTOM && x in 38f..320f) {
                val items = ShopCatalog.items(shopCategory)
                val index = kotlin.math.round((y + shopScrollY - DETAIL_FIRST_ROW_CENTER) / DETAIL_ROW_HEIGHT).toInt()
                if (index in items.indices) {
                    val rowCenter = DETAIL_FIRST_ROW_CENTER + index * DETAIL_ROW_HEIGHT - shopScrollY
                    if (kotlin.math.abs(y - rowCenter) <= DETAIL_ROW_HALF_HEIGHT + 4f) {
                        playFeedback(SoundCue.TAP)
                        listener?.onShopItemSelected(items[index].id)
                    }
                }
                return true
            }
            return true
        }

        if (mode == Mode.GAME_MENU) {
            if (x in 120f..240f && y in 300f..340f) {
                playFeedback(SoundCue.TAP)
                mode = Mode.HOME
                invalidate()
                return true
            }
            if (y in 56f..108f && x in 40f..320f) {
                playFeedback(SoundCue.TAP)
                startGame()
                return true
            }
            if (y in 112f..164f && x in 40f..320f) {
                playFeedback(SoundCue.TAP)
                startCatPawGame()
                return true
            }
            if (y in 168f..220f && x in 40f..320f) {
                playFeedback(SoundCue.TAP)
                startFuxingCatchGame()
                return true
            }
            if (y in 224f..276f && x in 40f..320f) {
                playFeedback(SoundCue.TAP)
                startFuxingJumpGame()
                return true
            }
            return true
        }

        if (mode == Mode.FUXING_JUMP) {
            if (y in FUXING_JUMP_END_BUTTON_TOP..345f && x in 130f..230f) {
                finishGameEarly()
                return true
            }
            if (gameElapsed() >= FUXING_JUMP_GAME_LENGTH_MS) {
                finishGameNormally()
                return true
            }
            // Jump starts on ACTION_DOWN for responsive controls; ACTION_UP only consumes the tap.
            invalidate()
            return true
        }

        if (mode == Mode.FUXING_CATCH) {
            if (y in 310f..345f && x in 130f..230f) {
                finishGameEarly()
                return true
            }
            if (gameElapsed() >= FUXING_CATCH_GAME_LENGTH_MS) {
                finishGameNormally()
                return true
            }
            if (y < 307f) fuxingCatchX = x.coerceIn(FUXING_MIN_X, FUXING_MAX_X)
            invalidate()
            return true
        }

        if (mode == Mode.CAT_PAW) {
            if (y in 308f..344f && x in 130f..230f) {
                finishGameEarly()
                return true
            }
            if (gameElapsed() >= CAT_PAW_GAME_LENGTH_MS) {
                finishGameNormally()
                return true
            }
            catPawHitAt(x, y)
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
            val dotAngle = -PI / 2.0 + (elapsed % ROTATION_MS).toDouble() / ROTATION_MS * 2.0 * PI
            val diff = angleDistance(dotAngle, -PI / 2.0)
            if (diff <= HIT_WINDOW_RADIANS && round != lastHitRound) {
                playFeedback(SoundCue.GOOD)
                gameScore = (gameScore + 1).coerceAtMost(PetState.MAX_GAME_SCORE)
                lastHitRound = round
                gameHint = "命中！很好！"
            } else if (round == lastHitRound) {
                gameHint = "本圈已经命中过啦"
            } else {
                playFeedback(SoundCue.BAD)
                gameHint = "差一点，再试试"
            }
            invalidate()
            return true
        }

        // Top-left coin shop button (home only).
        if (x in 60f..121f && y in 35f..69f) {
            playFeedback(SoundCue.TAP)
            shopScrollY = 0f
            shopCategory = ShopItemType.ACCESSORY
            shopNotice = "购买装饰，或解锁新的伙伴。"
            mode = Mode.SHOP
            invalidate()
            return true
        }

        // Top-right task button (home only).
        if (x in 238f..292f && y in 35f..69f) {
            playFeedback(SoundCue.TAP)
            taskScrollY = 0f
            mode = Mode.TASKS
            invalidate()
            return true
        }

        // A persisted discovery appears as a small gold coin on the home screen.
        if (x in 226f..263f && y in 116f..155f &&
            DailyRewards.hasDiscovery(state, todayKeyForView())) {
            playFeedback(SoundCue.GOOD)
            listener?.onClaimDiscovery()
            invalidate()
            return true
        }

        // Tapping the pet itself: pet while awake, or gently wake it while asleep.
        if (y in 100f..200f && x in 140f..220f) {
            if (behavior?.state == PetBehavior.State.SLEEPING) {
                playFeedback(SoundCue.TAP)
                listener?.onWake()
            } else {
                playFeedback(SoundCue.GOOD)
                listener?.onPet()
            }
            return true
        }

        if (y in 241f..321f) {
            when {
                x in 44f..126f -> { playFeedback(SoundCue.TAP); listener?.onFeed() }
                x in 140f..220f -> { playFeedback(SoundCue.GOOD); listener?.onPet() }
                x in 234f..316f -> { playFeedback(SoundCue.TAP); startGameMenu() }
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

    private enum class Mode { HOME, GAME_MENU, GAME, CAT_PAW, FUXING_CATCH, FUXING_JUMP, TASKS, SHOP }
    private enum class Action { FEED, PET, GAME }

    private companion object {
        const val DESIGN_SIZE = 360f
        const val GAME_LENGTH_MS = 10_000L
        const val CAT_PAW_GAME_LENGTH_MS = 20_000L
        const val CAT_PAW_TARGET_LIFETIME_MS = 820L
        const val CAT_PAW_HIT_RADIUS = 26f
        const val CAT_PAW_RAW_SCORE_CAP = 14
        const val CAT_PAW_POINTS_PER_REWARD = 2
        const val FUXING_CATCH_GAME_LENGTH_MS = 30_000L
        const val FUXING_JUMP_GAME_LENGTH_MS = 30_000L
        const val FUXING_JUMP_GROUND_Y = 247f
        const val FUXING_JUMP_BASE_Y = 221f
        const val FUXING_JUMP_RUNNER_X = 101f
        const val FUXING_JUMP_SCALE = 0.52f
        const val FUXING_JUMP_MAX_HEIGHT = 62f
        const val FUXING_JUMP_DURATION_MS = 720L
        const val FUXING_JUMP_SPAWN_X = 344f
        const val FUXING_JUMP_REMOVE_X = 38f
        const val FUXING_JUMP_MAX_OBJECTS = 4
        const val FUXING_JUMP_MIN_SPEED = 112
        const val FUXING_JUMP_MAX_SPEED = 151
        const val FUXING_JUMP_FIRST_SPAWN_MS = 750L
        const val FUXING_JUMP_SPAWN_MIN_MS = 900L
        const val FUXING_JUMP_SPAWN_MAX_MS = 1_280L
        const val FUXING_JUMP_SPAWN_LATE_MIN_MS = 720L
        const val FUXING_JUMP_SPAWN_LATE_MAX_MS = 1_020L
        const val FUXING_JUMP_MAX_FRAME_DELTA_MS = 90L
        const val FUXING_JUMP_COLLISION_X = 21f
        const val FUXING_JUMP_COLLISION_PENALTY = 1
        const val FUXING_JUMP_RAW_SCORE_CAP = 42
        const val FUXING_JUMP_POINTS_PER_REWARD = 3
        const val FUXING_JUMP_END_BUTTON_TOP = 310f
        const val FUXING_CATCHER_Y = 246f
        const val FUXING_CATCHER_SCALE = 0.43f
        const val FUXING_CATCH_HALF_WIDTH = 26f
        const val FUXING_CATCH_BAND_ABOVE = 19f
        const val FUXING_CATCH_BAND_BELOW = 7f
        const val FUXING_SPAWN_Y = 78f
        const val FUXING_DESPAWN_Y = 282f
        const val FUXING_MIN_X = 82f
        const val FUXING_MAX_X = 278f
        const val FUXING_MAX_ITEMS = 7
        const val FUXING_MIN_FALL_SPEED = 68
        const val FUXING_MAX_FALL_SPEED = 102
        const val FUXING_FIRST_SPAWN_MS = 420L
        const val FUXING_SPAWN_INTERVAL_MS_MIN = 460L
        const val FUXING_SPAWN_INTERVAL_MS_MAX = 700L
        val FUXING_SPAWN_INTERVAL_MS = FUXING_SPAWN_INTERVAL_MS_MIN..FUXING_SPAWN_INTERVAL_MS_MAX
        const val FUXING_SPAWN_INTERVAL_LATE_MS_MIN = 350L
        const val FUXING_SPAWN_INTERVAL_LATE_MS_MAX = 560L
        val FUXING_SPAWN_INTERVAL_LATE_MS = FUXING_SPAWN_INTERVAL_LATE_MS_MIN..FUXING_SPAWN_INTERVAL_LATE_MS_MAX
        const val FUXING_MAX_FRAME_DELTA_MS = 90L
        const val FUXING_RAW_SCORE_CAP = 36
        const val FUXING_POINTS_PER_REWARD = 3
        const val QUESTION_MARK_MS = 1_700L
        // Task and shop details share identical card sizes and a common scroll viewport.
        const val DETAIL_VIEWPORT_TOP = 90f
        const val DETAIL_VIEWPORT_BOTTOM = 281f
        const val DETAIL_FIRST_ROW_CENTER = 120f
        const val DETAIL_ROW_HEIGHT = 66f
        const val DETAIL_ROW_HALF_HEIGHT = 29f
        const val TASK_COUNT = 5
        const val DRAG_START_THRESHOLD = 5f
        const val FLING_FRAME_MS = 16L
        const val FLING_FRICTION = 0.90f
        const val FLING_STOP_THRESHOLD = 0.25f
        const val FLING_START_THRESHOLD = 0.7f
        const val MAX_FLING_VELOCITY = 18f
        const val VELOCITY_SMOOTHING = 0.35f
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
        const val FUXING_ORANGE = 0xE99A45
        const val FUXING_LIGHT = 0xF6B75F
        const val FUXING_DARK = 0xB7682D
        const val FUXING_CREAM = 0xFFF0D8
        const val FUXING_PINK = 0xEFA2B8
        const val FUXING_COLLAR = 0xB9C0C8
        const val FUXING_BADGE_BLUE = 0x477EC6
    }
}

