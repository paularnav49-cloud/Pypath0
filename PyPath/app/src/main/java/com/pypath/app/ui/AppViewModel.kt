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
import com.pypath.app.data.content.CourseRepository
import com.pypath.app.data.model.Level
import com.pypath.app.data.model.StepType
import com.pypath.app.data.progress.ProgressRepository
import com.pypath.app.data.settings.SettingsRepository
import com.pypath.app.domain.CourseSnapshot
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
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

    init {
        // Upgrade older saved progress once the course is known (e.g. grant badges for finished levels).
        viewModelScope.launch { runCatching { progressRepo.migrate(courses.loadCourse()) } }
    }

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

    fun resetProgress() = viewModelScope.launch { progressRepo.reset() }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as PyPathApp
                val c = app.container
                AppViewModel(c.courseRepository, c.progressRepository, c.settingsRepository, c.sounds)
            }
        }
    }
}
