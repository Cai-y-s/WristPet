package com.example.wristpet.pet

/** Daily actions grant coins once per local calendar day. */
enum class DailyTask(val coinReward: Int) {
    VISIT(3), FEED(2), PET(2), GAME(3)
}

data class CoinGrant(
    val state: PetState,
    /** Actual coins added to the wallet; can be 0 when the wallet is full. */
    val coinsAwarded: Int,
    /** True only when this call newly marked a task/discovery as claimed. */
    val newlyClaimed: Boolean
)

/** Common place for daily task and random-discovery rules. */
object DailyRewards {
    const val DISCOVERY_CHANCE_PERCENT = 60
    const val MIN_DISCOVERY_COINS = 2
    const val MAX_DISCOVERY_COINS = 5

    /**
     * Pays the first daily visit (+3) and rolls a discovery no more than once/day.
     * Pass rollPercent 0..99 and discoveryReward 2..5. The result is persisted by caller.
     */
    fun prepareVisit(
        state: PetState,
        dayKey: String,
        rollPercent: Int,
        discoveryReward: Int
    ): CoinGrant {
        val visit = claimTask(state, DailyTask.VISIT, dayKey)
        var next = visit.state
        if (next.discoveryDay != dayKey) {
            val reward = if (rollPercent.coerceIn(0, 99) < DISCOVERY_CHANCE_PERCENT) {
                discoveryReward.coerceIn(MIN_DISCOVERY_COINS, MAX_DISCOVERY_COINS)
            } else 0
            next = next.copy(discoveryDay = dayKey, discoveryCoins = reward)
        }
        return visit.copy(state = next)
    }

    fun claimTask(state: PetState, task: DailyTask, dayKey: String): CoinGrant {
        val base = state.forDay(dayKey)
        if (isClaimed(base, task, dayKey)) return CoinGrant(base, 0, false)

        val marked = when (task) {
            DailyTask.VISIT -> base.copy(dailyVisitDay = dayKey)
            DailyTask.FEED -> base.copy(dailyFeedDay = dayKey)
            DailyTask.PET -> base.copy(dailyPetDay = dayKey)
            DailyTask.GAME -> base.copy(dailyGameDay = dayKey)
        }
        val updated = marked.addCoins(task.coinReward)
        return CoinGrant(updated, updated.coins - base.coins, true)
    }

    fun isClaimed(state: PetState, task: DailyTask, dayKey: String): Boolean = when (task) {
        DailyTask.VISIT -> state.dailyVisitDay == dayKey
        DailyTask.FEED -> state.dailyFeedDay == dayKey
        DailyTask.PET -> state.dailyPetDay == dayKey
        DailyTask.GAME -> state.dailyGameDay == dayKey
    }

    fun hasDiscovery(state: PetState, dayKey: String): Boolean =
        state.discoveryDay == dayKey &&
            state.discoveryCoins in MIN_DISCOVERY_COINS..MAX_DISCOVERY_COINS &&
            state.discoveryClaimedDay != dayKey

    fun claimDiscovery(state: PetState, dayKey: String): CoinGrant {
        val base = state.forDay(dayKey)
        if (!hasDiscovery(base, dayKey)) return CoinGrant(base, 0, false)
        val updated = base.addCoins(base.discoveryCoins).copy(discoveryClaimedDay = dayKey)
        return CoinGrant(updated, updated.coins - base.coins, true)
    }

    private fun PetState.addCoins(amount: Int): PetState = copy(
        coins = (coins.toLong() + amount.coerceAtLeast(0))
            .coerceAtMost(PetState.MAX_COINS.toLong())
            .toInt()
    )
}
