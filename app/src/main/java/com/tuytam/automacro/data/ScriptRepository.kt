package com.tuytam.automacro.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Lop trung gian giua UI va Room: doc/ghi kich ban duoi dang ScriptWithSteps
 * (da giai ma JSON) thay vi lam viec truc tiep voi ScriptEntity/chuoi JSON.
 */
class ScriptRepository(private val dao: ScriptDao) {

    fun observeScripts(): Flow<List<ScriptWithSteps>> =
        dao.getAll().map { entities -> entities.map { it.toScriptWithSteps() } }

    suspend fun saveNewScript(name: String, steps: List<ScriptStep>) {
        dao.insert(
            ScriptEntity(
                name = name,
                actionsJson = ScriptJson.stepsToJson(steps),
                enabled = true
            )
        )
    }

    suspend fun deleteScript(item: ScriptWithSteps) {
        dao.delete(
            ScriptEntity(
                id = item.id,
                name = item.name,
                actionsJson = item.stepsJson,
                enabled = item.enabled
            )
        )
    }
}
