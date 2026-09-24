package com.tuytam.automacro.data

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.JsonParser

/**
 * Doc JSON cua GET /hub. Doc TUNG MUC rieng: 1 muc co du lieu la (vd 1 con so
 * qua lon) thi chi muc do bao loi, cac muc con lai van hien binh thuong —
 * thay vi ca man hinh Hub trang tron.
 */
object HubParser {
    private val gson = Gson()

    /** Nem JsonParseException neu ca cuc JSON khong phai 1 object hop le. */
    fun parse(body: String): HubResponse {
        val root = JsonParser.parseString(body)
        if (!root.isJsonObject) throw JsonParseException("Dữ liệu bot trả về không đúng dạng")
        val obj: JsonObject = root.asJsonObject
        val errors = linkedMapOf<String, String>()

        fun <T> section(key: String, type: Class<T>): T? {
            val element = obj.get(key) ?: return null
            if (element.isJsonNull) return null
            return try {
                gson.fromJson(element, type)
            } catch (e: JsonParseException) {
                errors[key] = (e.message ?: "lỗi không rõ").take(220)
                null
            }
        }

        return HubResponse(
            daily = section("daily", HubDaily::class.java),
            cowoncy = section("cowoncy", HubCowoncy::class.java),
            gems = section("gems", HubGems::class.java),
            huntbot = section("huntbot", HubHuntbot::class.java),
            quest = section("quest", HubQuest::class.java),
            team = section("team", HubTeam::class.java),
            zoo = section("zoo", HubZoo::class.java),
            inventory = section("inventory", HubInventory::class.java),
            weapons = section("weapons", HubWeapons::class.java),
            sectionErrors = if (errors.isEmpty()) null else errors
        )
    }
}
