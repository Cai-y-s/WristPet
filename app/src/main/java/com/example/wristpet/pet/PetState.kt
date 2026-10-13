package com.example.wristpet.pet

import kotlin.math.min

/**
 * Game balance rules for the offline pet MVP.
 * All experience sources share one daily cap so repeated taps or repeated minigames
 * cannot instantly grow the pet to the mature stage.
 */
data class PetState(
    val affection: Int = 8,
    val hunger: Int = 78,
    val exp: Int = 0,
    val coins: Int = 0,
    /** Time anchor used for offline hunger decay; preserves unused partial hours. */
    val lastUpdatedAt: Long = System.currentTimeMillis(),
    /** Experience earned during expDay, across feeding, petting, and minigames. */
    val dailyExpEarned: Int = 0,
    /** Local calendar day key in yyyy-MM-dd format. */
    val expDay: String = "",
    /** Last accepted petting action, used for a persistent cooldown. */
    val lastPettedAt: Long = 0L
) {
    val level: Int
        get() = (exp / 20 + 1).coerceIn(1, 99)

    val stage: String
        get() = when {
            level <= 2 -> "幼年"
            level <= 4 -> "成长中"
            else -> "成熟"
        }

    fun forDay(dayKey: String): PetState = if (expDay == dayKey) {
        copy(dailyExpEarned = dailyExpEarned.coerceIn(0, MAX_DAILY_EXP))
    } else {
        copy(dailyExpEarned = 0, expDay = dayKey)
    }

    /** A full pet receives no additional feeding reward until hunger falls below the threshold. */
    fun canFeed(): Boolean = hunger < FEED_HUNGER_LIMIT

    fun canPet(now: Long): Boolean =
        affection < 100 && (lastPettedAt <= 0L || now - lastPettedAt >= PET_COOLDOWN_MS)

    fun fed(dayKey: String): PetState {
        val base = forDay(dayKey)
        if (!base.canFeed()) return base
        return base.copy(hunger = (base.hunger + 28).coerceAtMost(100))
            .withExperience(1, dayKey)
    }

    fun petted(now: Long, dayKey: String): PetState {
        val base = forDay(dayKey)
        if (!base.canPet(now)) return base
        val newAffection = (base.affection + 4).coerceAtMost(100)
        val actualAffectionGain = newAffection > base.affection
        return base.copy(
            affection = newAffection,
            lastPettedAt = now
        ).withExperience(if (actualAffectionGain) 1 else 0, dayKey)
    }

    fun rewarded(score: Int, dayKey: String): PetState {
        val base = forDay(dayKey)
        val safeScore = score.coerceAtLeast(0)
        return base.copy(
            coins = (base.coins + safeScore).coerceAtMost(99999),
            affection = (base.affection + min(5, safeScore)).coerceAtMost(100)
        ).withExperience(safeScore * 2, dayKey)
    }

    private fun withExperience(requested: Int, dayKey: String): PetState {
        val base = forDay(dayKey)
        val roomToday = (MAX_DAILY_EXP - base.dailyExpEarned).coerceAtLeast(0)
        val granted = requested.coerceAtLeast(0).coerceAtMost(roomToday)
        return base.copy(
            exp = (base.exp + granted).coerceAtMost(MAX_TOTAL_EXP),
            dailyExpEarned = (base.dailyExpEarned + granted).coerceAtMost(MAX_DAILY_EXP),
            expDay = dayKey
        )
    }

    companion object {
        /** Eight experience points/day means reaching the first mature stage takes at least 10 days. */
        const val MAX_DAILY_EXP = 8
        const val MAX_TOTAL_EXP = 1980
        const val FEED_HUNGER_LIMIT = 85
        const val PET_COOLDOWN_MS = 60_000L
    }
}
