package com.example.wristpet.pet

/** Persisted pet attributes and game-balance rules. */
data class PetState(
    val affection: Int = 8,
    val hunger: Int = 78,
    val exp: Int = 0,
    val coins: Int = 0,
    /** Anchor for offline hunger decay; keeps partial 20-minute intervals. */
    val lastUpdatedAt: Long = System.currentTimeMillis(),
    /** Anchor for gradual affection decay; old saves initialize this to load time. */
    val lastAffectionDecayAt: Long = System.currentTimeMillis(),
    /** XP earned from all sources during expDay. */
    val dailyExpEarned: Int = 0,
    /** Local calendar day in yyyy-MM-dd format. */
    val expDay: String = "",
    /** Persisted petting cooldown. */
    val lastPettedAt: Long = 0L,
    // Daily task completion keys. Empty means the task has not been completed yet.
    val dailyVisitDay: String = "",
    val dailyFeedDay: String = "",
    val dailyPetDay: String = "",
    val dailyGameDay: String = "",
    // A random discovery is rolled once per day and persisted.
    val discoveryDay: String = "",
    val discoveryCoins: Int = 0,
    val discoveryClaimedDay: String = "",
    // Coins earned from minigame scores are capped independently from task bonuses.
    val gameCoinDay: String = "",
    val dailyGameCoinsEarned: Int = 0,
    /** Comma-separated IDs owned by this save; starter sprout is always owned. */
    val ownedPetIds: String = "sprout",
    /** Currently selected companion. */
    val activePetId: String = "sprout",
    /** Comma-separated purchased cosmetic IDs. */
    val ownedAccessoryIds: String = "",
    /** Equipped accessory ID, or empty when none is equipped. */
    val equippedAccessoryId: String = ""
) {
    val level: Int
        get() = (exp / 20 + 1).coerceIn(1, 99)

    /** Stable numeric stage ID: 0 = baby, 1 = growing, 2 = mature. */
    val evolutionStage: Int
        get() = when {
            level <= 2 -> 0
            level <= 4 -> 1
            else -> 2
        }

    val stage: String
        get() = when (evolutionStage) {
            0 -> "幼年"
            1 -> "成长期"
            else -> "成熟"
        }

    fun forDay(dayKey: String): PetState {
        val xpToday = if (expDay == dayKey) dailyExpEarned.coerceIn(0, MAX_DAILY_EXP) else 0
        val gameCoinsToday = if (gameCoinDay == dayKey) {
            dailyGameCoinsEarned.coerceIn(0, MAX_DAILY_GAME_COINS)
        } else 0
        return copy(
            dailyExpEarned = xpToday,
            expDay = dayKey,
            dailyGameCoinsEarned = gameCoinsToday,
            gameCoinDay = dayKey
        )
    }

    fun canFeed(): Boolean = hunger < FEED_HUNGER_LIMIT

    /** Petting stays available at full affection; it just cannot raise affection past 100. */
    fun canPet(now: Long): Boolean =
        lastPettedAt <= 0L || (now >= lastPettedAt && now - lastPettedAt >= PET_COOLDOWN_MS)

    fun fed(dayKey: String): PetState {
        val base = forDay(dayKey)
        if (!base.canFeed()) return base
        return base.copy(hunger = (base.hunger + FEED_AMOUNT).coerceAtMost(100))
            .withExperience(1, dayKey)
    }

    fun petted(now: Long, dayKey: String): PetState {
        val base = forDay(dayKey)
        if (!base.canPet(now)) return base
        val newAffection = (base.affection + PET_AFFECTION_AMOUNT).coerceAtMost(100)
        val affectionIncreased = newAffection > base.affection
        return base.copy(
            affection = newAffection,
            lastPettedAt = now
        ).withExperience(if (affectionIncreased) 1 else 0, dayKey)
    }

    /**
     * Standard minigame reward contract: score is normalized to 0..MAX_GAME_SCORE.
     * Score-derived coins are capped at MAX_DAILY_GAME_COINS/day; daily task bonuses
     * are paid separately by DailyRewards.
     */
    fun rewarded(score: Int, dayKey: String): PetState {
        val base = forDay(dayKey)
        val safeScore = score.coerceIn(0, MAX_GAME_SCORE)
        val remainingDailyCoins = (MAX_DAILY_GAME_COINS - base.dailyGameCoinsEarned).coerceAtLeast(0)
        val remainingWallet = (MAX_COINS - base.coins).coerceAtLeast(0)
        val coinGrant = minOf(safeScore, remainingDailyCoins, remainingWallet)
        val expReward = when {
            safeScore >= 4 -> 2
            safeScore > 0 -> 1
            else -> 0
        }
        return base.copy(
            coins = (base.coins + coinGrant).coerceAtMost(MAX_COINS),
            gameCoinDay = dayKey,
            dailyGameCoinsEarned = (base.dailyGameCoinsEarned + coinGrant)
                .coerceIn(0, MAX_DAILY_GAME_COINS)
        ).withExperience(expReward, dayKey)
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
        const val MAX_DAILY_EXP = 8
        const val MAX_TOTAL_EXP = 1980
        const val MAX_GAME_SCORE = 7
        const val MAX_COINS = 99999
        const val MAX_DAILY_GAME_COINS = 12
        const val FEED_HUNGER_LIMIT = 85
        const val FEED_AMOUNT = 28
        const val PET_AFFECTION_AMOUNT = 4
        const val PET_COOLDOWN_MS = 60_000L
        /** Affection decreases by one point per four elapsed hours, down to zero. */
        const val AFFECTION_DECAY_INTERVAL_MS = 4L * 60L * 60L * 1000L
    }
}
