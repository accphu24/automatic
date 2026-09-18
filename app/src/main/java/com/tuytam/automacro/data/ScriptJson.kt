package com.tuytam.automacro.data

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * Chuyen doi List<ScriptStep> <-> chuoi JSON de luu vao cot actionsJson
 * trong ScriptEntity (Room khong luu duoc List truc tiep).
 */
object ScriptJson {
    private val gson = Gson()
    private val stepListType = object : TypeToken<List<ScriptStep>>() {}.type

    fun stepsToJson(steps: List<ScriptStep>): String = gson.toJson(steps)

    fun jsonToSteps(json: String): List<ScriptStep> {
        if (json.isBlank()) return emptyList()
        return try {
            val result: List<ScriptStep>? = gson.fromJson(json, stepListType)
            result ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }
}
