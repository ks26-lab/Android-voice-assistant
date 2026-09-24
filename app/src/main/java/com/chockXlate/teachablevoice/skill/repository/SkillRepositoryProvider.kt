package com.chockXlate.teachablevoice.skill.repository

/**
 * Thread-safe, application-scoped provider for the single shared [LocalSkillRepository].
 * Ensures that Person 1 teaching, skill matching, execution request building,
 * and Person 2 ExecutionEngine workflow resolution all share the exact same repository instance.
 */
object SkillRepositoryProvider {

    @Volatile
    private var repositoryInstance: LocalSkillRepository = LocalSkillRepository()

    fun getRepository(): LocalSkillRepository = repositoryInstance

    fun setRepository(repository: LocalSkillRepository) {
        repositoryInstance = repository
    }

    fun reset() {
        repositoryInstance = LocalSkillRepository()
    }
}
