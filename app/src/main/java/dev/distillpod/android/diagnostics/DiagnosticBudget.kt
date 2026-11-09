package dev.distillpod.android.diagnostics

import android.content.SharedPreferences

/** Admission, not delivery, budget. Calls are made under Diagnostics' consent lock. */
internal class DiagnosticBudget(private val prefs: SharedPreferences, private val now: () -> Long = System::currentTimeMillis) {
    enum class Lane(val key: String, val limit: Int) {
        ROUTINE("routine", 800), CRITICAL("critical", 160), HEALTH("health", 10), ERROR("errors", 30)
    }
    private fun roll() {
        val day=now()/86_400_000L
        if(prefs.getLong("budget_day",-1)!=day) {
            val edit=prefs.edit().putLong("budget_day",day).putInt("sent",0).putInt("errors",0)
            Lane.entries.forEach {edit.putInt("budget_"+it.key,0).putLong(it.key+"_dropped",0)}
            edit.remove("suppressed_summaries").putString("health_exhaustion","").putLong("last_health",0).commit()
        } else if(!prefs.contains("budget_routine")) {
            // An upgrade must not reset today's already-used allowance.
            prefs.edit().putInt("budget_routine",(prefs.getInt("sent",0)-prefs.getInt("errors",0)).coerceIn(0,800))
                .putInt("budget_errors",prefs.getInt("errors",0)).commit()
        }
    }
    fun available(lane: Lane): Boolean {
        roll()
        return prefs.getInt("budget_"+lane.key,0)<lane.limit && prefs.getInt("sent",0)<1000
    }
    /** At most one write per flush, with IDs bounded by the journal's 500-row window. */
    fun suppressedSummaries(denied: Map<Lane,Set<String>>, pending: Set<String>) {
        roll()
        val previous=prefs.getStringSet("suppressed_summaries",emptySet()).orEmpty()
        val retained=previous.intersect(pending)
        val added=denied.mapValues {(_,ids)->ids.intersect(pending)-previous}
        val next=retained+added.values.flatten()
        if(next==previous)return
        val edit=prefs.edit().putStringSet("suppressed_summaries",next)
        var count=0L
        added.forEach {(lane,ids)->
            if(ids.isNotEmpty())edit.putLong(lane.key+"_dropped",prefs.getLong(lane.key+"_dropped",0)+ids.size)
            count+=ids.size
        }
        edit.putLong("dropped",prefs.getLong("dropped",0)+count).commit()
    }
    fun admit(lane: Lane): Boolean {
        roll()
        val used=prefs.getInt("budget_"+lane.key,0)
        if(used>=lane.limit || prefs.getInt("sent",0)>=1000) {
            prefs.edit().putLong(lane.key+"_dropped",prefs.getLong(lane.key+"_dropped",0)+1)
                .putLong("dropped",prefs.getLong("dropped",0)+1).commit()
            return false
        }
        prefs.edit().putInt("budget_"+lane.key,used+1).putInt("sent",prefs.getInt("sent",0)+1)
            .putInt("errors",prefs.getInt("budget_errors",0)+if(lane==Lane.ERROR)1 else 0).commit()
        return true
    }
    fun fields(): Map<String,Any> {
        roll()
        return buildMap {
            put("budget_policy_version",2);put("budget_day",prefs.getLong("budget_day",-1));put("daily_limit",1000)
            Lane.entries.forEach {put(it.key+"_limit",it.limit);put(it.key+"_used",prefs.getInt("budget_"+it.key,0));put(it.key+"_dropped",prefs.getLong(it.key+"_dropped",0))}
        }
    }
    fun exhaustion(): String {
        roll()
        return Lane.entries.filter {it!=Lane.HEALTH && prefs.getLong(it.key+"_dropped",0)>0}.joinToString(","){it.key}
    }
    companion object {
        fun lane(name: String, outcome: String? = null): Lane = when {
            name=="diagnostics.health" -> Lane.HEALTH
            name=="operation.summary" && outcome in OperationJournal.TERMINAL+setOf("paused") -> Lane.CRITICAL
            name in setOf("capture.context_summary","capture.completed","capture.failed","transcription.completed","transcription.failed",
                "transcription.partial","transcription.paused","transcription.cancelled","distillation.finished",
                "distillation.failed","distillation.cancelled","distillation.attempt_summary") -> Lane.CRITICAL
            else -> Lane.ROUTINE
        }
    }
}
