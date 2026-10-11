package com.pypath.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.pypath.app.PyPathApp
import com.pypath.app.audio.SoundManager
import com.pypath.app.data.activity.ActivityData
import com.pypath.app.data.activity.ActivityRepository
import com.pypath.app.data.activity.ActivityUpdates
import com.pypath.app.data.content.CourseRepository
import com.pypath.app.data.model.Level
import com.pypath.app.data.model.StepType
import com.pypath.app.data.progress.ProgressRepository
import com.pypath.app.data.settings.SettingsRepository
import com.pypath.app.domain.CourseSnapshot
import com.pypath.app.domain.DailyGoal
import com.pypath.app.domain.Streaks
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface AppState {
    data object Loading : AppState
    data class Ready(val snapshot: CourseSnapshot) : AppState
    data class Error(val message: String) : AppState
}

/**
 * Activity-scoped view model shared by every screen. It combines static course content
 * with live learner progress into a single [CourseSnapshot].
 */
class AppViewModel(
    private val courses: CourseRepository,
    private val progressRepo: ProgressRepository,
    private val settings: SettingsRepository,
    private val sounds: SoundManager?,
    private val activityRepo: ActivityRepository,
) : ViewModel() {

    val state: StateFlow<AppState> =
        combine(flow { emit(runCatching { courses.loadCourse() }) }, progressRepo.progress) { course, progress ->
            course.fold(
                onSuccess = { AppState.Ready(CourseSnapshot(it, progress)) },
                onFailure = { AppState.Error(it.message ?: "Could not load course") },
            )
        }.stateIn(viewModelScope, SharingStarted.Eagerly, AppState.Loading)

    val soundEffects: StateFlow<Boolean> = settings.soundEffects
        .onEach { sounds?.enabled = it }
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    /** Name on the course certificate ("" until the learner types one). */
    val certificateName: StateFlow<String> = settings.certificateName
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    fun setCertificateName(name: String) = viewModelScope.launch { settings.setCertificateName(name) }

    /** Batch 3: question history, XP per day and streak days. */
    val activity: StateFlow<ActivityData> = activityRepo.activity
        .stateIn(viewModelScope, SharingStarted.Eagerly, ActivityData())

    val dailyGoal: StateFlow<DailyGoal> = settings.dailyGoal
        .stateIn(viewModelScope, SharingStarted.Eagerly, DailyGoal.DEFAULT)

    init {
        viewModelScope.launch {
            // Upgrade older saved progress once the course is known (badges for finished levels, then
            // retroactive XP). Runs before the XP sync below, so old content never counts as "today".
            val course = runCatching { courses.loadCourse() }.getOrNull() ?: return@launch
            runCatching { progressRepo.migrate(course) }
            // Live one-time XP: whenever progress changes (quiz passed, level or project finished,
            // practical task completed), grant XP for events not yet in xpAwards and count it today.
            progressRepo.progress.distinctUntilChanged().collect {
                val gained = runCatching { progressRepo.syncXp(course) }.getOrDefault(0)
                if (gained > 0) updateActivity { a, today, goal -> ActivityUpdates.addXp(a, today, goal, gained) }
            }
        }
    }

    private fun updateActivity(block: (ActivityData, Long, Int) -> ActivityData) = viewModelScope.launch {
        runCatching {
            val today = Streaks.today()
            val goal = dailyGoal.value.xp
            activityRepo.update { block(it, today, goal) }
        }
    }

    /**
     * Records one quiz answer (question history, review pool, XP, today's activity) and returns the
     * XP it earns. No answer XP once the sub-level quiz has been passed (replays earn nothing).
     */
    fun recordAnswer(subLevelId: String, questionId: String, correct: Boolean, hintUsed: Boolean): Int {
        val snap = (state.value as? AppState.Ready)?.snapshot
        val eligible = snap?.subLevel(subLevelId)?.progress?.quiz?.passed != true
        val xp = ActivityUpdates.answerXp(activity.value, questionId, correct, hintUsed, eligible)
        updateActivity { a, today, goal -> ActivityUpdates.recordAnswer(a, questionId, subLevelId, correct, hintUsed, eligible, today, goal) }
        return xp
    }

    /** A lesson page finished or a project stage answered (counts for the streak when the goal is off). */
    fun recordAction() = updateActivity { a, today, goal -> ActivityUpdates.recordAction(a, today, goal) }

    fun setDailyGoal(goal: DailyGoal) = viewModelScope.launch {
        settings.setDailyGoal(goal)
        // Today may now meet the new goal. Days already counted stay counted.
        runCatching { activityRepo.update { ActivityUpdates.evaluateDay(it, Streaks.today(), goal.xp) } }
    }

    /** The streak milestone celebration was shown. */
    fun consumeMilestone() = viewModelScope.launch { runCatching { activityRepo.update { ActivityUpdates.consumeMilestone(it) } } }

    fun completeOnboarding() = viewModelScope.launch { progressRepo.completeOnboarding() }

    fun openSubLevel(id: String) = viewModelScope.launch { progressRepo.setCurrentSubLevel(id) }

    fun completeLesson(id: String, steps: List<StepType>) =
        viewModelScope.launch { progressRepo.markStepCompleted(id, StepType.LEARN, steps) }

    fun recordQuiz(id: String, score: Int, total: Int, passed: Boolean, steps: List<StepType>) =
        viewModelScope.launch { progressRepo.recordQuizResult(id, score, total, passed, steps) }

    fun markBriefingSeen(id: String) = viewModelScope.launch { progressRepo.markBriefingSeen(id) }

    fun recordProjectMiss(id: String) = viewModelScope.launch { progressRepo.recordProjectMiss(id) }

    fun completeProjectStage(id: String, stageId: String, allStageIds: List<String>, steps: List<StepType>) =
        viewModelScope.launch { progressRepo.completeProjectStage(id, stageId, allStageIds, steps) }

    fun earnBadge(levelId: String) = viewModelScope.launch { progressRepo.earnBadge(levelId) }

    /** Level number whose badge celebration is on screen, or null. */
    var celebratingLevel by mutableStateOf<Int?>(null)
        private set

    /** Shows the celebration once: the badge is saved as soon as it starts, so it never replays. */
    fun startCelebration(level: Level) {
        if (celebratingLevel != null) return
        celebratingLevel = level.number
        earnBadge(level.id)
    }

    fun endCelebration() { celebratingLevel = null }

    fun setSoundEffects(enabled: Boolean) = viewModelScope.launch { settings.setSoundEffects(enabled) }

    /** Clears course progress, badges, XP, question history, streaks and day tallies. Settings stay. */
    fun resetProgress() = viewModelScope.launch {
        progressRepo.reset()
        activityRepo.reset()
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as PyPathApp
                val c = app.container
                AppViewModel(c.courseRepository, c.progressRepository, c.settingsRepository, c.sounds, c.activityRepository)
            }
        }
    }
}
