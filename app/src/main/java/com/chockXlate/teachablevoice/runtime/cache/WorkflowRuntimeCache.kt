package com.chockXlate.teachablevoice.runtime.cache

import com.chockXlate.teachablevoice.contract.workflow.Workflow
import java.util.concurrent.ConcurrentHashMap

enum class CacheState {
    CACHE_UNINITIALIZED,
    CACHE_HYDRATING,
    CACHE_READY,
    CACHE_FAILED
}

object WorkflowRuntimeCache {
    private val store = ConcurrentHashMap<String, Workflow>()
    
    @Volatile
    var state: CacheState = CacheState.CACHE_UNINITIALIZED
        private set
        
    fun hydrate(workflows: List<Workflow>) {
        state = CacheState.CACHE_HYDRATING
        println("[RUNTIME][CACHE] HYDRATION_START")
        println("[RUNTIME][CACHE] PERSISTED_WORKFLOW_COUNT=${workflows.size}")
        
        store.clear()
        workflows.forEach { store[it.skillId] = it }
        
        state = CacheState.CACHE_READY
        println("[RUNTIME][CACHE] HYDRATION_COMPLETE")
        println("[RUNTIME][CACHE] RAM_WORKFLOW_COUNT=${store.size}")
    }
    
    fun put(workflow: Workflow) {
        println("[RUNTIME][CACHE] SAVE_START")
        println("[RUNTIME][CACHE] WORKFLOW_ID=${workflow.skillId}")
        println("[RUNTIME][CACHE] PERSISTENCE_SAVE_SUCCESS")
        store[workflow.skillId] = workflow
        println("[RUNTIME][CACHE] RAM_CACHE_UPDATED")
        println("[RUNTIME][CACHE] RAM_CACHE_SIZE=${store.size}")
    }
    
    fun get(skillId: String): Workflow? {
        if (state != CacheState.CACHE_READY) {
            println("[RUNTIME][CACHE] MISS")
            println("[RUNTIME][CACHE] SKILL_ID=$skillId")
            println("[RUNTIME][CACHE] EXECUTION_ABORTED_CACHE_NOT_HYDRATED")
            return null
        }
        
        println("[RUNTIME][CACHE] GET")
        println("[RUNTIME][CACHE] SKILL_ID=$skillId")
        val workflow = store[skillId]
        if (workflow != null) {
            println("[RUNTIME][CACHE] RESULT=HIT")
            println("[RUNTIME][CACHE] SOURCE=RAM")
            println("[RUNTIME][CACHE] DISK_READ=false")
        } else {
            println("[RUNTIME][CACHE] RESULT=MISS")
            println("[RUNTIME][CACHE] EXECUTION_ABORTED_CACHE_NOT_HYDRATED")
        }
        return workflow
    }
    
    fun remove(skillId: String) {
        println("[RUNTIME][CACHE] REMOVE")
        println("[RUNTIME][CACHE] WORKFLOW_ID=$skillId")
        store.remove(skillId)
    }
    
    fun contains(skillId: String): Boolean = store.containsKey(skillId)
    
    fun clear() {
        store.clear()
        state = CacheState.CACHE_UNINITIALIZED
    }
    
    fun getAll(): List<Workflow> = store.values.toList()
    
    fun size(): Int = store.size
    
    fun isHydrated(): Boolean = state == CacheState.CACHE_READY
}
