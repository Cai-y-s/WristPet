package com.example.wristpet

import android.app.Activity
import android.os.Bundle
import android.view.WindowManager
import com.example.wristpet.pet.PetRepository
import com.example.wristpet.pet.PetState
import com.example.wristpet.ui.PetView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity(), PetView.Listener {
    private lateinit var repository: PetRepository
    private lateinit var petView: PetView
    private var petState = PetState()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        repository = PetRepository(this)
        petState = repository.load()

        petView = PetView(this).apply {
            listener = this@MainActivity
            setPetState(petState)
            setNotice("你好呀！摸摸我吧")
        }
        setContentView(petView)
    }

    override fun onResume() {
        super.onResume()
        if (::petView.isInitialized) {
            petState = repository.load()
            petView.setPetState(petState)
            petView.setInteractive(true)
        }
    }

    override fun onPause() {
        if (::petView.isInitialized && ::repository.isInitialized) {
            petState = repository.save(repository.applyElapsedTime(petState))
            petView.setPetState(petState)
            petView.setInteractive(false)
        }
        super.onPause()
    }

    override fun onFeed() {
        val fresh = currentState()
        if (!fresh.canFeed()) {
            publish(fresh, "我已经饱啦，饿一点再喂吧")
            return
        }

        val updated = fresh.fed(todayKey())
        val message = if (updated.dailyExpEarned >= PetState.MAX_DAILY_EXP) {
            "吃饱啦！今天的成长经验已满"
        } else {
            "吃饱啦，好开心！"
        }
        publish(updated, message)
    }

    override fun onPet() {
        val now = System.currentTimeMillis()
        val fresh = currentState(now)
        if (fresh.affection >= 100) {
            publish(fresh, "小芽已经被你摸得超开心啦！")
            return
        }
        if (!fresh.canPet(now)) {
            publish(fresh, "刚刚摸过啦，过一会儿再来吧")
            return
        }

        val updated = fresh.petted(now, todayKey(now))
        val message = if (updated.dailyExpEarned >= PetState.MAX_DAILY_EXP) {
            "小芽超喜欢你！今日成长经验已满"
        } else {
            "小芽超喜欢你！"
        }
        publish(updated, message)
    }

    override fun onGameFinished(score: Int) {
        val safeScore = score.coerceAtLeast(0)
        val now = System.currentTimeMillis()
        val updated = currentState(now).rewarded(safeScore, todayKey(now))
        val message = when {
            safeScore <= 0 -> "再试一次，你一定可以！"
            updated.dailyExpEarned >= PetState.MAX_DAILY_EXP -> "挑战结束！金币 +$safeScore，今日成长已满"
            else -> "挑战结束！金币 +$safeScore"
        }
        publish(updated, message)
    }

    private fun currentState(now: Long = System.currentTimeMillis()): PetState =
        repository.applyElapsedTime(petState, now).forDay(todayKey(now))

    private fun publish(state: PetState, message: String) {
        petState = repository.save(state)
        petView.setPetState(petState)
        petView.setNotice(message)
    }

    private fun todayKey(now: Long = System.currentTimeMillis()): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(now))
}
