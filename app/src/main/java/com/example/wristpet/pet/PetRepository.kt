package com.example.wristpet.pet

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max

/** Small offline save backed by SharedPreferences. */
class PetRepository(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(SAVE_NAME, Context.MODE_PRIVATE)

    fun load(): PetState {
        val now = System.currentTimeMillis()
        val today = dayKey(now)
        if (!prefs.getBoolean(KEY_CREATED, false)) {
            return save(PetState(lastUpdatedAt = now, expDay = today))
        }

        val stored = PetState(
            affection = prefs.getInt(KEY_AFFECTION, 8).coerceIn(0, 100),
            hunger = prefs.getInt(KEY_HUNGER, 78).coerceIn(20, 100),
            exp = prefs.getInt(KEY_EXP, 0).coerceIn(0, PetState.MAX_TOTAL_EXP),
            coins = prefs.getInt(KEY_COINS, 0).coerceIn(0, PetState.MAX_COINS),
            lastUpdatedAt = prefs.getLong(KEY_UPDATED, now).coerceAtMost(now),
            dailyExpEarned = prefs.getInt(KEY_DAILY_EXP, 0).coerceIn(0, PetState.MAX_DAILY_EXP),
            // Old saves without these keys start today's cap at zero.
            expDay = prefs.getString(KEY_EXP_DAY, today) ?: today,
            lastPettedAt = prefs.getLong(KEY_LAST_PETTED, 0L).coerceAtLeast(0L),
            dailyVisitDay = prefs.getString(KEY_DAILY_VISIT_DAY, "") ?: "",
            dailyFeedDay = prefs.getString(KEY_DAILY_FEED_DAY, "") ?: "",
            dailyPetDay = prefs.getString(KEY_DAILY_PET_DAY, "") ?: "",
            dailyGameDay = prefs.getString(KEY_DAILY_GAME_DAY, "") ?: "",
            discoveryDay = prefs.getString(KEY_DISCOVERY_DAY, "") ?: "",
            discoveryCoins = prefs.getInt(KEY_DISCOVERY_COINS, 0).coerceIn(0, 5),
            discoveryClaimedDay = prefs.getString(KEY_DISCOVERY_CLAIMED_DAY, "") ?: "",
            gameCoinDay = prefs.getString(KEY_GAME_COIN_DAY, "") ?: "",
            dailyGameCoinsEarned = prefs.getInt(KEY_DAILY_GAME_COINS, 0)
                .coerceIn(0, PetState.MAX_DAILY_GAME_COINS),
            ownedPetIds = (prefs.getString(KEY_OWNED_PET_IDS, ShopCatalog.STARTER_PET_ID)
                ?: ShopCatalog.STARTER_PET_ID).ifBlank { ShopCatalog.STARTER_PET_ID },
            activePetId = (prefs.getString(KEY_ACTIVE_PET_ID, ShopCatalog.STARTER_PET_ID)
                ?: ShopCatalog.STARTER_PET_ID).ifBlank { ShopCatalog.STARTER_PET_ID },
            ownedAccessoryIds = prefs.getString(KEY_OWNED_ACCESSORY_IDS, "") ?: "",
            equippedAccessoryId = prefs.getString(KEY_EQUIPPED_ACCESSORY_ID, "") ?: ""
        )
        return save(applyElapsedTime(stored, now).forDay(today))
    }

    /** Hunger drops by one point per 20 elapsed minutes, preserving the remainder. */
    fun applyElapsedTime(state: PetState, now: Long = System.currentTimeMillis()): PetState {
        val safeState = state.copy(hunger = state.hunger.coerceIn(20, 100))
        val elapsed = (now - safeState.lastUpdatedAt).coerceAtLeast(0L)
        val intervals = elapsed / HUNGER_INTERVAL_MS
        if (intervals <= 0L) return safeState

        val loss = intervals.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val nextHunger = max(20, safeState.hunger - loss)
        val nextTimestamp = if (nextHunger <= 20) now else {
            safeState.lastUpdatedAt + intervals * HUNGER_INTERVAL_MS
        }
        return safeState.copy(hunger = nextHunger, lastUpdatedAt = nextTimestamp)
    }

    /** Save while retaining the partial interval before the next hunger decrease. */
    fun save(state: PetState): PetState {
        val now = System.currentTimeMillis()
        val todayState = state.forDay(dayKey(now))
        val normalized = todayState.copy(
            affection = todayState.affection.coerceIn(0, 100),
            hunger = todayState.hunger.coerceIn(20, 100),
            exp = todayState.exp.coerceIn(0, PetState.MAX_TOTAL_EXP),
            coins = todayState.coins.coerceIn(0, PetState.MAX_COINS),
            dailyExpEarned = todayState.dailyExpEarned.coerceIn(0, PetState.MAX_DAILY_EXP),
            dailyGameCoinsEarned = todayState.dailyGameCoinsEarned.coerceIn(0, PetState.MAX_DAILY_GAME_COINS),
            discoveryCoins = todayState.discoveryCoins.coerceIn(0, 5),
            lastUpdatedAt = todayState.lastUpdatedAt.coerceAtMost(now),
            lastPettedAt = todayState.lastPettedAt.coerceAtLeast(0L)
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
            .putString(KEY_DAILY_VISIT_DAY, normalized.dailyVisitDay)
            .putString(KEY_DAILY_FEED_DAY, normalized.dailyFeedDay)
            .putString(KEY_DAILY_PET_DAY, normalized.dailyPetDay)
            .putString(KEY_DAILY_GAME_DAY, normalized.dailyGameDay)
            .putString(KEY_DISCOVERY_DAY, normalized.discoveryDay)
            .putInt(KEY_DISCOVERY_COINS, normalized.discoveryCoins)
            .putString(KEY_DISCOVERY_CLAIMED_DAY, normalized.discoveryClaimedDay)
            .putString(KEY_GAME_COIN_DAY, normalized.gameCoinDay)
            .putInt(KEY_DAILY_GAME_COINS, normalized.dailyGameCoinsEarned)
            .putString(KEY_OWNED_PET_IDS, normalized.ownedPetIds.ifBlank { ShopCatalog.STARTER_PET_ID })
            .putString(KEY_ACTIVE_PET_ID, normalized.activePetId.ifBlank { ShopCatalog.STARTER_PET_ID })
            .putString(KEY_OWNED_ACCESSORY_IDS, normalized.ownedAccessoryIds)
            .putString(KEY_EQUIPPED_ACCESSORY_ID, normalized.equippedAccessoryId)
            .apply()
        return normalized
    }

    private fun dayKey(timeMillis: Long): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(timeMillis))

    private companion object {
        const val SAVE_NAME = "wrist_pet_save"
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
        const val KEY_DAILY_VISIT_DAY = "daily_visit_day"
        const val KEY_DAILY_FEED_DAY = "daily_feed_day"
        const val KEY_DAILY_PET_DAY = "daily_pet_day"
        const val KEY_DAILY_GAME_DAY = "daily_game_day"
        const val KEY_DISCOVERY_DAY = "discovery_day"
        const val KEY_DISCOVERY_COINS = "discovery_coins"
        const val KEY_DISCOVERY_CLAIMED_DAY = "discovery_claimed_day"
        const val KEY_GAME_COIN_DAY = "game_coin_day"
        const val KEY_DAILY_GAME_COINS = "daily_game_coins"
        const val KEY_OWNED_PET_IDS = "owned_pet_ids"
        const val KEY_ACTIVE_PET_ID = "active_pet_id"
        const val KEY_OWNED_ACCESSORY_IDS = "owned_accessory_ids"
        const val KEY_EQUIPPED_ACCESSORY_ID = "equipped_accessory_id"
    }
}
