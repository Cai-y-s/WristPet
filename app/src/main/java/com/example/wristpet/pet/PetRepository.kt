package com.example.wristpet.pet

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max

/** Small, offline-only save file backed by SharedPreferences. */
class PetRepository(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("wrist_pet_save", Context.MODE_PRIVATE)

    fun load(): PetState {
        val now = System.currentTimeMillis()
        val today = dayKey(now)
        if (!prefs.getBoolean(KEY_CREATED, false)) {
            return save(PetState(lastUpdatedAt = now, expDay = today))
        }

        val stored = PetState(
            affection = prefs.getInt(KEY_AFFECTION, 8).coerceIn(0, 100),
            hunger = prefs.getInt(KEY_HUNGER, 78).coerceIn(0, 100),
            exp = prefs.getInt(KEY_EXP, 0).coerceIn(0, PetState.MAX_TOTAL_EXP),
            coins = prefs.getInt(KEY_COINS, 0).coerceAtLeast(0),
            lastUpdatedAt = prefs.getLong(KEY_UPDATED, now),
            dailyExpEarned = prefs.getInt(KEY_DAILY_EXP, 0).coerceIn(0, PetState.MAX_DAILY_EXP),
            // Existing v0.1 saves have no daily cap fields; default them to today with zero earned.
            expDay = prefs.getString(KEY_EXP_DAY, today) ?: today,
            lastPettedAt = prefs.getLong(KEY_LAST_PETTED, 0L)
        )
        return save(applyElapsedTime(stored, now).forDay(today))
    }

    /**
     * Hunger decays by one point every 20 minutes. Keep the time remainder instead of
     * resetting the clock on every save, so tapping buttons cannot pause hunger decay.
     */
    fun applyElapsedTime(state: PetState, now: Long = System.currentTimeMillis()): PetState {
        val elapsed = (now - state.lastUpdatedAt).coerceAtLeast(0L)
        val intervals = elapsed / HUNGER_INTERVAL_MS
        if (intervals <= 0L) return state

        val loss = intervals.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val nextHunger = max(20, state.hunger - loss)
        val nextTimestamp = if (nextHunger <= 20) {
            // Once at the floor, discard accumulated excess time.
            now
        } else {
            state.lastUpdatedAt + intervals * HUNGER_INTERVAL_MS
        }
        return state.copy(hunger = nextHunger, lastUpdatedAt = nextTimestamp)
    }

    /** Persist the supplied hunger time anchor as-is, preserving partial decay intervals. */
    fun save(state: PetState): PetState {
        val now = System.currentTimeMillis()
        val normalized = state.forDay(dayKey(now)).copy(
            affection = state.affection.coerceIn(0, 100),
            hunger = state.hunger.coerceIn(20, 100),
            exp = state.exp.coerceIn(0, PetState.MAX_TOTAL_EXP),
            coins = state.coins.coerceAtLeast(0),
            dailyExpEarned = state.forDay(dayKey(now)).dailyExpEarned.coerceIn(0, PetState.MAX_DAILY_EXP)
        )
        prefs.edit()
            .putBoolean(KEY_CREATED, true)
            .putInt(KEY_AFFECTION, normalized.affection)
            .putInt(KEY_HUNGER, normalized.hunger)
            .putInt(KEY_EXP, normalized.exp)
            .putInt(KEY_COINS, normalized.coins)
            .putLong(KEY_UPDATED, normalized.lastUpdatedAt)
            .putInt(KEY_DAILY_EXP, normalized.dailyExpEarned)
            .putString(KEY_EXP_DAY, normalized.expDay)
            .putLong(KEY_LAST_PETTED, normalized.lastPettedAt)
            .apply()
        return normalized
    }

    private fun dayKey(timeMillis: Long): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(timeMillis))

    private companion object {
        const val HUNGER_INTERVAL_MS = 20L * 60L * 1000L
        const val KEY_CREATED = "created"
        const val KEY_AFFECTION = "affection"
        const val KEY_HUNGER = "hunger"
        const val KEY_EXP = "exp"
        const val KEY_COINS = "coins"
        const val KEY_UPDATED = "updated_at"
        const val KEY_DAILY_EXP = "daily_exp"
        const val KEY_EXP_DAY = "exp_day"
        const val KEY_LAST_PETTED = "last_petted_at"
    }
}
