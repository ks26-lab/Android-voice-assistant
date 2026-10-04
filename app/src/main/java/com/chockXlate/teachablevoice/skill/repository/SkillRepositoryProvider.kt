package com.chockXlate.teachablevoice.skill.repository

import java.io.File

/**
 * Thread-safe, application-scoped provider for the single shared [LocalSkillRepository].
 * Ensures that Person 1 teaching, skill matching, execution request building,
 * Workflow Inspector, and Person 2 ExecutionEngine workflow resolution all share
 * the exact same persistent repository instance across Activity recreation and restarts.
 */
object SkillRepositoryProvider {

    @Volatile
    private var repositoryInstance: LocalSkillRepository? = null

    @Volatile
    private var storageDir: File? = null

    /**
     * Initializes the provider with a persistent disk storage directory.
     * Can be called during Application / Service startup or test setup.
     */
    fun initialize(dir: File?) {
        storageDir = dir
        repositoryInstance = LocalSkillRepository(dir)
    }

    fun getRepository(): LocalSkillRepository {
        var repo = repositoryInstance
        if (repo == null) {
            synchronized(this) {
                repo = repositoryInstance
                if (repo == null) {
                    repo = LocalSkillRepository(storageDir)
                    repositoryInstance = repo
                }
            }
        }
        return repo!!
    }

    fun setRepository(repository: LocalSkillRepository) {
        repositoryInstance = repository
    }

    fun reset() {
        repositoryInstance = LocalSkillRepository(storageDir)
    }
}

