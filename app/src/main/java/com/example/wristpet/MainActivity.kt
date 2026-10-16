package com.example.wristpet

import android.app.Activity
import android.os.Bundle
import android.view.WindowManager
import com.example.wristpet.pet.CoinGrant
import com.example.wristpet.pet.DailyRewards
import com.example.wristpet.pet.DailyTask
import com.example.wristpet.pet.PetBehavior
import com.example.wristpet.pet.PetRepository
import com.example.wristpet.pet.PetState
import com.example.wristpet.pet.ShopCatalog
import com.example.wristpet.ui.PetView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.random.Random

class MainActivity : Activity(), PetView.Listener {
    private lateinit var repository: PetRepository
    private lateinit var petView: PetView
    private var petState = PetState()
    private val behavior = PetBehavior()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        repository = PetRepository(this)
        petState = repository.load()
        petView = PetView(this).apply {
            listener = this@MainActivity
            behavior = this@MainActivity.behavior
            setPetState(petState)
            setNotice("你好呀，摸摸我吧！")
        }
        setContentView(petView)
    }

    override fun onResume() {
        super.onResume()
        if (::petView.isInitialized) {
            val now = System.currentTimeMillis()
            val prepared = DailyRewards.prepareVisit(
                state = repository.load(),
                dayKey = todayKey(now),
                rollPercent = Random.nextInt(100),
                discoveryReward = Random.nextInt(
                    DailyRewards.MIN_DISCOVERY_COINS,
                    DailyRewards.MAX_DISCOVERY_COINS + 1
                )
            )
            petState = repository.save(prepared.state)
            petView.setPetState(petState)
            behavior.onAppResumed()
            petView.setNotice(
                if (prepared.coinsAwarded > 0) "你回来啦！每日拜访 +${prepared.coinsAwarded} 金币"
                else "你回来啦！"
            )
            petView.setInteractive(true)
        }
    }

    override fun onPause() {
        if (::petView.isInitialized && ::repository.isInitialized) {
            petState = repository.save(repository.applyElapsedTime(petState))
            petView.setPetState(petState)
            petView.setInteractive(false)
        }
        behavior.onAppPaused()
        super.onPause()
    }

    override fun onFeed() {
        val now = System.currentTimeMillis()
        val fresh = currentState(now)
        if (!fresh.canFeed()) {
            publish(fresh, "我已经吃饱啦，饿一点再喂吧！")
            return
        }
        val fed = fresh.fed(todayKey(now))
        val task = DailyRewards.claimTask(fed, DailyTask.FEED, todayKey(now))
        behavior.onInteraction()
        publish(
            task.state,
            if (task.coinsAwarded > 0) "喂食成功！每日照顾 +${task.coinsAwarded} 金币"
            else "喂食成功，开心！"
        )
    }

    override fun onPet() {
        val now = System.currentTimeMillis()
        val today = todayKey(now)
        val fresh = currentState(now)
        val petName = ShopCatalog.petName(fresh.activePetId)
        if (fresh.affection >= 100) {
            publish(fresh, "${petName}已经被你摸得超开心啦！")
            return
        }
        if (!fresh.canPet(now)) {
            publish(fresh, "刚刚摸过啦，过一会儿再来哦。")
            return
        }
        val petted = fresh.petted(now, today)
        val task = DailyRewards.claimTask(petted, DailyTask.PET, today)
        behavior.onInteraction()
        publish(
            task.state,
            if (task.coinsAwarded > 0) "${petName}超喜欢你！每日照顾 +${task.coinsAwarded} 金币"
            else "${petName}超喜欢你！"
        )
    }

    /** Backward-compatible callback for the original timing game. */
    override fun onGameFinished(score: Int) {
        handleMiniGameFinished("timing", score, completedNormally = true)
    }

    /** Shared reward entry point for current and future minigames. */
    override fun onMiniGameFinished(gameId: String, score: Int, completedNormally: Boolean) {
        handleMiniGameFinished(gameId, score, completedNormally)
    }

    private fun handleMiniGameFinished(gameId: String, score: Int, completedNormally: Boolean) {
        val now = System.currentTimeMillis()
        val today = todayKey(now)
        val fresh = currentState(now)
        val safeScore = score.coerceIn(0, PetState.MAX_GAME_SCORE)
        val afterScoreReward = fresh.rewarded(safeScore, today)
        val scoreCoins = (afterScoreReward.coins - fresh.coins).coerceAtLeast(0)
        val taskGrant = if (completedNormally) {
            DailyRewards.claimTask(afterScoreReward, DailyTask.GAME, today)
        } else {
            CoinGrant(afterScoreReward, 0, false)
        }
        val updated = taskGrant.state
        val gameName = when (gameId) {
            "timing" -> "卡点挑战"
            else -> "小游戏"
        }
        val parts = mutableListOf("${gameName}结束！游戏金币 +${scoreCoins}")
        if (taskGrant.coinsAwarded > 0) parts += "每日任务 +${taskGrant.coinsAwarded}"
        if (afterScoreReward.dailyGameCoinsEarned >= PetState.MAX_DAILY_GAME_COINS) {
            parts += "今日游戏金币已达上限"
        }
        if (updated.dailyExpEarned >= PetState.MAX_DAILY_EXP) parts += "今日成长经验已满"

        if (safeScore > 0 || taskGrant.coinsAwarded > 0) behavior.onInteraction()
        publish(updated, parts.joinToString("，"))
    }

    override fun onShopItemSelected(itemId: String) {
        val result = ShopCatalog.activate(currentState(), itemId)
        petState = repository.save(result.state)
        petView.setPetState(petState)
        petView.setShopNotice(result.message)
        if (result.changed) petView.setNotice(result.message)
    }

    override fun onClaimDiscovery() {
        val now = System.currentTimeMillis()
        val grant = DailyRewards.claimDiscovery(currentState(now), todayKey(now))
        if (!grant.newlyClaimed) {
            petView.setNotice("这里暂时没有新的发现哦。")
            return
        }
        if (grant.coinsAwarded > 0) {
            behavior.onInteraction()
            publish(grant.state, "发现小惊喜！金币 +${grant.coinsAwarded}")
        } else {
            // Mark it claimed even if the wallet is already full.
            publish(grant.state, "金币已经装满啦，这次发现已记录。")
        }
    }

    /** Waking up does not count as a successful petting task. */
    override fun onWake() {
        behavior.onInteraction()
        petView.setNotice("醒啦，今天精神满满！")
    }

    private fun currentState(now: Long = System.currentTimeMillis()): PetState =
        repository.applyElapsedTime(petState, now).forDay(todayKey(now))

    private fun publish(state: PetState, message: String) {
        val previousStage = petState.evolutionStage
        petState = repository.save(state)
        val evolved = petState.evolutionStage > previousStage
        petView.setPetState(petState)
        if (evolved) {
            behavior.onEvolution()
            val petName = ShopCatalog.petName(petState.activePetId)
            val evolutionMessage = when (petState.evolutionStage) {
                1 -> "${petName}长大了一点！进入成长期啦！"
                2 -> "进化成功！${petName}成熟啦！"
                else -> message
            }
            petView.setNotice(evolutionMessage, PetBehavior.EVOLUTION_MS)
        } else {
            petView.setNotice(message)
        }
    }

    private fun todayKey(now: Long = System.currentTimeMillis()): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(now))
}
