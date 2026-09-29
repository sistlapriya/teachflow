package com.teachflow.agent.skills

import android.content.Context
import com.teachflow.agent.core.TfLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import java.io.File

/**
 * Local skill memory. Stored as JSON in app-private storage and written atomically,
 * so skills survive app restarts, rotation and process death.
 */
class SkillRepository private constructor(context: Context) {
    private val file = File(context.filesDir, "skills.json")
    private val _skills = MutableStateFlow(load())
    val skills: StateFlow<List<Skill>> = _skills.asStateFlow()

    fun get(id: String): Skill? = _skills.value.firstOrNull { it.id == id }

    @Synchronized
    fun save(skill: Skill) {
        _skills.value = _skills.value.filterNot { it.id == skill.id } + skill
        persist()
    }

    @Synchronized
    fun delete(id: String) {
        _skills.value = _skills.value.filterNot { it.id == id }
        persist()
    }

    @Synchronized
    fun clear() {
        _skills.value = emptyList()
        persist()
    }

    @Synchronized
    fun recordOutcome(id: String, success: Boolean, handoff: Boolean) {
        val s = get(id) ?: return
        save(
            s.copy(
                successCount = s.successCount + if (success) 1 else 0,
                failureCount = s.failureCount + if (!success && !handoff) 1 else 0,
                handoffCount = s.handoffCount + if (handoff) 1 else 0,
                updatedAt = System.currentTimeMillis(),
            )
        )
    }

    fun exportJson(): String = JSONArray(_skills.value.map(SkillJson::toJson)).toString(2)

    private fun load(): List<Skill> = try {
        if (!file.exists()) emptyList()
        else JSONArray(file.readText()).let { arr -> (0 until arr.length()).map { SkillJson.fromJson(arr.getJSONObject(it)) } }
    } catch (e: Exception) {
        TfLog.w("SKILL", "Could not read skills.json; starting empty", e)
        emptyList()
    }

    private fun persist() {
        val tmp = File(file.parentFile, "skills.json.tmp")
        tmp.writeText(exportJson())
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
    }

    companion object {
        @Volatile private var instance: SkillRepository? = null
        fun get(context: Context): SkillRepository =
            instance ?: synchronized(this) { instance ?: SkillRepository(context.applicationContext).also { instance = it } }
    }
}
