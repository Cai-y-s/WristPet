package com.example.wristpet.pet

/** The two types of items sold in the offline coin shop. */
enum class ShopItemType { ACCESSORY, PET }

data class ShopItem(
    val id: String,
    val name: String,
    val description: String,
    val price: Int,
    val type: ShopItemType
)

data class ShopActionResult(
    val state: PetState,
    val message: String,
    val changed: Boolean,
    /** True when the selected companion changed (including immediately after purchase). */
    val petChanged: Boolean = false
)

/**
 * Single source of truth for shop inventory and purchases.
 * IDs are persisted as comma-separated strings to keep the existing SharedPreferences save simple.
 */
object ShopCatalog {
    const val STARTER_PET_ID = "sprout"

    val accessories: List<ShopItem> = listOf(
        ShopItem("flower_crown", "小花冠", "让小伙伴更可爱", 25, ShopItemType.ACCESSORY),
        ShopItem("scarf", "暖暖围巾", "软软的彩色围巾", 50, ShopItemType.ACCESSORY),
        ShopItem("star_pin", "星星发饰", "闪闪发光的小星星", 80, ShopItemType.ACCESSORY)
    )

    val pets: List<ShopItem> = listOf(
        ShopItem("sprout", "小芽", "最初的绿色伙伴", 0, ShopItemType.PET),
        ShopItem("bunny", "棉棉兔", "长耳朵的温柔伙伴", 120, ShopItemType.PET),
        ShopItem("fox", "橘尾狐", "活泼的橘色小狐狸", 180, ShopItemType.PET),
        ShopItem("cat", "月光猫", "安静的紫色小猫", 220, ShopItemType.PET),
        ShopItem("fuxing", "福星", "戴着工牌的橘猫伙伴", 260, ShopItemType.PET)
    )

    fun items(type: ShopItemType): List<ShopItem> = when (type) {
        ShopItemType.ACCESSORY -> accessories
        ShopItemType.PET -> pets
    }

    fun item(id: String): ShopItem? = (accessories + pets).firstOrNull { it.id == id }

    fun petName(id: String): String = pets.firstOrNull { it.id == id }?.name ?: "小芽"

    fun isOwned(state: PetState, item: ShopItem): Boolean = when (item.type) {
        ShopItemType.PET -> item.id in parseIds(state.ownedPetIds) || item.id == STARTER_PET_ID
        ShopItemType.ACCESSORY -> item.id in parseIds(state.ownedAccessoryIds)
    }

    fun isEquipped(state: PetState, item: ShopItem): Boolean = when (item.type) {
        ShopItemType.PET -> state.activePetId == item.id
        ShopItemType.ACCESSORY -> state.equippedAccessoryId == item.id
    }

    /** Tap behavior: buy-and-equip unowned items; select owned pets; toggle owned accessories. */
    fun activate(state: PetState, itemId: String): ShopActionResult {
        val item = item(itemId)
            ?: return ShopActionResult(state, "没有找到这件商品。", changed = false)
        val owned = isOwned(state, item)

        return when (item.type) {
            ShopItemType.PET -> activatePet(state, item, owned)
            ShopItemType.ACCESSORY -> activateAccessory(state, item, owned)
        }
    }

    private fun activatePet(state: PetState, item: ShopItem, owned: Boolean): ShopActionResult {
        if (owned) {
            if (state.activePetId == item.id) {
                return ShopActionResult(state, "${item.name}正在陪伴你。", changed = false)
            }
            return ShopActionResult(
                state.copy(activePetId = item.id),
                "已切换伙伴：${item.name}！",
                changed = true,
                petChanged = true
            )
        }
        if (state.coins < item.price) {
            return ShopActionResult(state, "金币不够哦，还需要 ${item.price - state.coins} 枚。", changed = false)
        }
        val newOwned = addId(state.ownedPetIds, item.id, STARTER_PET_ID)
        return ShopActionResult(
            state.copy(
                coins = (state.coins - item.price).coerceAtLeast(0),
                ownedPetIds = newOwned,
                activePetId = item.id
            ),
            "购买成功！${item.name}加入你的伙伴队伍。",
            changed = true,
            petChanged = true
        )
    }

    private fun activateAccessory(state: PetState, item: ShopItem, owned: Boolean): ShopActionResult {
        if (owned) {
            return if (state.equippedAccessoryId == item.id) {
                ShopActionResult(
                    state.copy(equippedAccessoryId = ""),
                    "已摘下${item.name}。",
                    changed = true
                )
            } else {
                ShopActionResult(
                    state.copy(equippedAccessoryId = item.id),
                    "已装备${item.name}！",
                    changed = true
                )
            }
        }
        if (state.coins < item.price) {
            return ShopActionResult(state, "金币不够哦，还需要 ${item.price - state.coins} 枚。", changed = false)
        }
        return ShopActionResult(
            state.copy(
                coins = (state.coins - item.price).coerceAtLeast(0),
                ownedAccessoryIds = addId(state.ownedAccessoryIds, item.id),
                equippedAccessoryId = item.id
            ),
            "购买成功！已装备${item.name}。",
            changed = true
        )
    }

    private fun parseIds(raw: String): Set<String> = raw.split(',')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .toSet()

    private fun addId(raw: String, id: String, alwaysKeep: String? = null): String {
        val ids = parseIds(raw).toMutableSet()
        if (alwaysKeep != null) ids += alwaysKeep
        ids += id
        return ids.sorted().joinToString(",")
    }
}
