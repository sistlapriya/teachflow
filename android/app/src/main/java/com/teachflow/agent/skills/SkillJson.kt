package com.teachflow.agent.skills

import com.teachflow.agent.accessibility.SemanticRole
import org.json.JSONArray
import org.json.JSONObject

/** Plain JSON persistence: readable, diffable, and exportable for judges. */
object SkillJson {

    fun toJson(s: Skill): JSONObject = JSONObject().apply {
        put("id", s.id); put("name", s.name); put("intent", s.intent)
        put("verbs", JSONArray(s.verbs.toList()))
        put("exampleCommands", JSONArray(s.exampleCommands))
        put("contentTokens", JSONArray(s.contentTokens.toList()))
        put("targetPackage", s.targetPackage); put("targetApp", s.targetApp)
        put("slots", JSONArray(s.slots.map { JSONObject().put("name", it.name).put("type", it.type.name).putOpt("defaultValue", it.defaultValue) }))
        put("steps", JSONArray(s.steps.map(::stepJson)))
        put("safetyBoundary", s.safetyBoundary)
        put("recoveryStrategies", JSONArray(s.recoveryStrategies))
        put("discarded", JSONArray(s.discarded.map { JSONObject().put("label", it.label).put("packageName", it.packageName).put("reason", it.reason) }))
        put("createdAt", s.createdAt); put("updatedAt", s.updatedAt)
        put("successCount", s.successCount); put("failureCount", s.failureCount); put("handoffCount", s.handoffCount); put("demonstrations", s.demonstrations); put("status", s.status)
    }

    private fun stepJson(st: SkillStep): JSONObject = JSONObject().apply {
        put("kind", st.kind.name)
        st.target?.let { t ->
            put("target", JSONObject().apply {
                put("role", t.role.name)
                putOpt("slotRef", t.slotRef); putOpt("anchorLabel", t.anchorLabel); putOpt("nearSlot", t.nearSlot)
                putOpt("ordinal", t.ordinal); putOpt("ordinalRole", t.ordinalRole?.name)
                putOpt("resourceIdName", t.resourceIdName); putOpt("className", t.className); putOpt("demoBounds", t.demoBounds)
            })
        }
        putOpt("valueTemplate", st.valueTemplate)
        put("optional", st.optional); put("mayNeedScroll", st.mayNeedScroll); put("evidence", st.evidence)
    }

    fun fromJson(o: JSONObject): Skill = Skill(
        id = o.getString("id"),
        name = o.getString("name"),
        intent = o.getString("intent"),
        verbs = o.optJSONArray("verbs").strings().toSet(),
        exampleCommands = o.optJSONArray("exampleCommands").strings(),
        contentTokens = o.optJSONArray("contentTokens").strings().toSet(),
        targetPackage = o.getString("targetPackage"),
        targetApp = o.getString("targetApp"),
        slots = o.optJSONArray("slots").objects().map {
            SlotDef(it.getString("name"), SlotType.valueOf(it.getString("type")), it.optStringOrNull("defaultValue"))
        },
        steps = o.optJSONArray("steps").objects().map(::stepFrom),
        safetyBoundary = o.optString("safetyBoundary", "PAYMENT"),
        recoveryStrategies = o.optJSONArray("recoveryStrategies").strings().ifEmpty { Skill.DEFAULT_RECOVERY },
        discarded = o.optJSONArray("discarded").objects().map { DiscardedAction(it.optString("label"), it.optString("packageName"), it.optString("reason")) },
        createdAt = o.optLong("createdAt"),
        updatedAt = o.optLong("updatedAt"),
        successCount = o.optInt("successCount"),
        failureCount = o.optInt("failureCount"),
        handoffCount = o.optInt("handoffCount"),
        demonstrations = o.optInt("demonstrations", 1),
        status = o.optString("status", "ACTIVE"),
    )

    private fun stepFrom(o: JSONObject): SkillStep {
        val t = o.optJSONObject("target")?.let {
            TargetSpec(
                role = SemanticRole.valueOf(it.getString("role")),
                slotRef = it.optStringOrNull("slotRef"),
                anchorLabel = it.optStringOrNull("anchorLabel"),
                nearSlot = it.optStringOrNull("nearSlot"),
                ordinal = if (it.has("ordinal")) it.getInt("ordinal") else null,
                ordinalRole = it.optStringOrNull("ordinalRole")?.let(SemanticRole::valueOf),
                resourceIdName = it.optStringOrNull("resourceIdName"),
                className = it.optStringOrNull("className"),
                demoBounds = it.optStringOrNull("demoBounds"),
            )
        }
        return SkillStep(
            kind = StepKind.valueOf(o.getString("kind")),
            target = t,
            valueTemplate = o.optStringOrNull("valueTemplate"),
            optional = o.optBoolean("optional"),
            mayNeedScroll = o.optBoolean("mayNeedScroll"),
            evidence = o.optString("evidence"),
        )
    }

    private fun JSONObject.optStringOrNull(k: String): String? = if (has(k) && !isNull(k)) getString(k) else null
    private fun JSONArray?.strings(): List<String> = if (this == null) emptyList() else (0 until length()).map { getString(it) }
    private fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).map { getJSONObject(it) }
}
