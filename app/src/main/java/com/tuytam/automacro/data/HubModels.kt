package com.tuytam.automacro.data

import com.google.gson.annotations.SerializedName

/*
 * Cac lop du lieu khop 1-1 voi JSON ma owo-tracker tra ve o GET /hub.
 * MOI truong deu de null/co gia tri mac dinh: neu 1 muc bot chua tung thay
 * (hoac sau nay them truong moi) thi app van doc duoc, khong bi crash.
 */

data class HubResponse(
    @SerializedName("hub_version") val hubVersion: Int? = null,
    @SerializedName("server_time") val serverTime: String? = null,
    val daily: HubDaily? = null,
    val cowoncy: HubCowoncy? = null,
    val gems: HubGems? = null,
    val huntbot: HubHuntbot? = null,
    val quest: HubQuest? = null,
    val team: HubTeam? = null,
    val battles: HubBattles? = null,
    val zoo: HubZoo? = null,
    val inventory: HubInventory? = null,
    val weapons: HubWeapons? = null,
    /** Muc nao khong doc duoc -> ten muc + ly do (vd "zoo" -> "Expected BEGIN_ARRAY..."). Cac muc con lai van hien binh thuong. */
    val sectionErrors: Map<String, String>? = null
)

// ---- Daily + Cowoncy ----
data class HubDaily(
    val state: String? = null,
    val streak: Int? = null,
    @SerializedName("last_reward") val lastReward: Long? = null,
    @SerializedName("ready_at") val readyAt: String? = null,
    @SerializedName("seconds_left") val secondsLeft: Long? = null,
    @SerializedName("age_seconds") val ageSeconds: Long? = null
)

data class HubCowoncy(
    val amount: Long? = null,
    @SerializedName("age_seconds") val ageSeconds: Long? = null
)

// ---- Gem ----
data class HubGems(
    val equipped: List<HubGemEquipped>? = null,
    val spare: List<HubGemSpare>? = null
)

data class HubGemEquipped(
    val slot: String? = null,
    val tier: String? = null,
    val current: Long? = null,
    val max: Long? = null,
    val percent: Int? = null,
    @SerializedName("age_seconds") val ageSeconds: Long? = null
)

data class HubGemSpare(
    val code: String? = null,
    val slot: String? = null,
    val tier: String? = null,
    val count: Long? = null
)

// ---- HuntBot ----
data class HubHuntbot(
    val hunting: Boolean? = null,
    @SerializedName("progress_pct") val progressPct: Double? = null,
    @SerializedName("animals_captured") val animalsCaptured: Long? = null,
    val essence: Long? = null,
    @SerializedName("time_remaining_text") val timeRemainingText: String? = null,
    @SerializedName("ready_at") val readyAt: String? = null,
    @SerializedName("seconds_left") val secondsLeft: Long? = null,
    @SerializedName("age_seconds") val ageSeconds: Long? = null
)

// ---- Quest ----
data class HubQuest(
    val seals: Long? = null,
    @SerializedName("all_done") val allDone: Boolean? = null,
    @SerializedName("next_quest") val nextQuest: String? = null,
    @SerializedName("next_quest_seconds") val nextQuestSeconds: Long? = null,
    val quests: List<HubQuestItem>? = null,
    @SerializedName("age_seconds") val ageSeconds: Long? = null
)

data class HubQuestItem(
    val index: Int? = null,
    val title: String? = null,
    val rarity: String? = null,
    val description: String? = null,
    val rewards: List<HubReward>? = null,
    val current: Long? = null,
    val max: Long? = null,
    val done: Boolean? = null
)

data class HubReward(
    val item: String? = null,
    val amount: Long? = null
)

// ---- Doi hinh ----
data class HubTeam(
    val members: List<HubTeamMember>? = null,
    @SerializedName("age_seconds") val ageSeconds: Long? = null
)

data class HubTeamMember(
    val pos: Int? = null,
    val name: String? = null,
    val level: Int? = null,
    val hp: Long? = null,
    val wp: Long? = null,
    val att: Long? = null,
    val mag: Long? = null,
    val pr: Long? = null,
    val mr: Long? = null,
    val weaponId: String? = null,
    val quality: Double? = null
)

// ---- Tran dau ----
data class HubBattles(
    @SerializedName("sample_size") val sampleSize: Int? = null,
    val wins: Int? = null,
    val losses: Int? = null,
    @SerializedName("current_streak") val currentStreak: Int? = null,
    val recent: List<HubBattle>? = null,
    @SerializedName("age_seconds") val ageSeconds: Long? = null
)

data class HubBattle(
    val result: String? = null,
    val turns: Int? = null,
    val xp: Long? = null,
    val streak: Int? = null
)

// ---- Zoo (pet dang co) ----
data class HubZoo(
    @SerializedName("zoo_points") val zooPoints: Long? = null,
    @SerializedName("breakdown_raw") val breakdownRaw: String? = null,
    @SerializedName("total_pets") val totalPets: Long? = null,
    @SerializedName("by_tier") val byTier: Map<String, HubTier>? = null,
    val pets: List<HubPet>? = null,
    @SerializedName("age_seconds") val ageSeconds: Long? = null
)

data class HubTier(
    val species: Int? = null,
    val total: Long? = null
)

data class HubPet(
    val tier: String? = null,
    val name: String? = null,
    val count: Long? = null
)

// ---- Kho do ----
data class HubInventory(
    val kinds: Int? = null,
    @SerializedName("total_items") val totalItems: Long? = null,
    val items: List<HubItem>? = null,
    @SerializedName("age_seconds") val ageSeconds: Long? = null
)

data class HubItem(
    val code: String? = null,
    val name: String? = null,
    val count: Long? = null,
    @SerializedName("is_gem") val isGem: Boolean? = null
)

// ---- Vu khi ----
data class HubWeapons(
    val count: Int? = null,
    val weapons: List<HubWeapon>? = null,
    @SerializedName("age_seconds") val ageSeconds: Long? = null
)

data class HubWeapon(
    val id: String? = null,
    @SerializedName("type_name") val typeName: String? = null,
    val quality: Double? = null,
    @SerializedName("passive_name") val passiveName: String? = null,
    @SerializedName("passive_desc") val passiveDesc: String? = null
)
