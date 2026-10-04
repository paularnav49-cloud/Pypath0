package com.pypath.app.data.progress

import com.pypath.app.data.model.StepType
import kotlinx.serialization.Serializable

/**
 * Everything the app remembers about the learner. Stored locally as JSON.
 * Add new fields with default values so older saved data keeps decoding.
 *
 * Schema history:
 *  1 - onboarding, current sub-level, per-sub-level steps and quiz results
 *  2 - adds [earnedBadges] and [SubLevelProgress.project]. Migration: badges for levels that were
 *      already complete are granted silently (see [ProgressUpdates.migrate]).
 */
@Serializable
data class UserProgress(
    val schemaVersion: Int = CURRENT_SCHEMA,
    val onboardingCompleted: Boolean = false,
    /** Sub-level the learner is currently working on (used by "Continue Learning"). */
    val currentSubLevelId: String? = null,
    val subLevels: Map<String, SubLevelProgress> = emptyMap(),
    /** Level ids whose completion badge has been earned (and its celebration shown). */
    val earnedBadges: Set<String> = emptySet(),
) {
    fun of(subLevelId: String): SubLevelProgress = subLevels[subLevelId] ?: SubLevelProgress()

    companion object {
        const val CURRENT_SCHEMA = 2
    }
}

@Serializable
data class SubLevelProgress(
    val completedSteps: Set<StepType> = emptySet(),
    val quiz: QuizResult? = null,
    val completed: Boolean = false,
    val completedAt: Long? = null,
    val lastOpenedAt: Long? = null,
    /** Only for project sub-levels. */
    val project: ProjectProgress? = null,
)

@Serializable
data class QuizResult(
    val lastScore: Int,
    val bestScore: Int,
    val total: Int,
    val attempts: Int,
    val passed: Boolean,
)

/** Resume state for a guided project. */
@Serializable
data class ProjectProgress(
    val briefingSeen: Boolean = false,
    /** Ids of stages answered correctly, in the order they were completed. */
    val completedStages: List<String> = emptyList(),
    /** Wrong answers across all stages ("attempts used"). Never blocks progress. */
    val wrongAttempts: Int = 0,
)
