package com.tuytam.automacro.data

/**
 * 1 kich ban da duoc "giai ma" tu JSON - dung de hien thi trong danh sach
 * va de dua thang vao ScriptEngine khi bam "Chay".
 */
data class ScriptWithSteps(
    val id: Long,
    val name: String,
    val enabled: Boolean,
    val steps: List<ScriptStep>,
    val stepsJson: String
)

fun ScriptEntity.toScriptWithSteps(): ScriptWithSteps = ScriptWithSteps(
    id = id,
    name = name,
    enabled = enabled,
    steps = ScriptJson.jsonToSteps(actionsJson),
    stepsJson = actionsJson
)
