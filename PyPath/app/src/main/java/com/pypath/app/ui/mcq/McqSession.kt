package com.pypath.app.ui.mcq

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import com.pypath.app.data.model.Question

enum class McqPhase { ANSWERING, FEEDBACK, FINISHED }

/**
 * Reusable MCQ engine. Knows nothing about sub-levels, so the same session can drive
 * sub-level quizzes today and level-final assessments / recall reviews later.
 */
class McqSession(
    val questions: List<Question>,
    val passPercent: Int,
) {
    var index by mutableIntStateOf(0); private set
    var selected by mutableStateOf<Int?>(null); private set
    var phase by mutableStateOf(McqPhase.ANSWERING); private set
    /** Number of completed attempts — used to fire result callbacks exactly once per attempt. */
    var attempt by mutableIntStateOf(0); private set
    /** Last attempt whose result has been persisted. */
    var reportedAttempt: Int = 0
    private val _answers = mutableStateListOf<Int>()
    val answers: List<Int> get() = _answers

    val current: Question get() = questions[index]
    val total: Int get() = questions.size
    val isLastQuestion: Boolean get() = index == questions.lastIndex
    val score: Int get() = _answers.withIndex().count { (i, a) -> questions[i].correctIndex == a }
    val percent: Int get() = if (total == 0) 0 else score * 100 / total
    val passed: Boolean get() = percent >= passPercent
    val isCorrect: Boolean get() = selected == current.correctIndex
    /** Progress through the quiz, counting the current question once it is answered. */
    val progress: Float get() = if (total == 0) 0f else (_answers.size.toFloat()) / total

    // ── Batch 1: optional hints. Counted for information only; they never change scoring or block progress. ──
    /** True once the learner opened the hint of the current question. */
    var hintShown by mutableStateOf(false); private set
    /** Number of questions in this attempt where the hint was opened. */
    var hintsUsed by mutableIntStateOf(0); private set
    val hasHint: Boolean get() = !current.hint.isNullOrBlank()

    fun showHint() {
        if (phase != McqPhase.ANSWERING || hintShown || !hasHint) return
        hintShown = true
        hintsUsed++
    }

    fun select(option: Int) {
        if (phase == McqPhase.ANSWERING) selected = option
    }

    fun check() {
        val s = selected ?: return
        if (phase != McqPhase.ANSWERING) return
        _answers += s
        phase = McqPhase.FEEDBACK
    }

    fun next() {
        if (phase != McqPhase.FEEDBACK) return
        if (isLastQuestion) {
            phase = McqPhase.FINISHED
            attempt++
        } else {
            index++
            selected = null
            hintShown = false
            phase = McqPhase.ANSWERING
        }
    }

    fun restart() {
        index = 0; selected = null; _answers.clear(); phase = McqPhase.ANSWERING
        hintShown = false; hintsUsed = 0
    }

    val inProgress: Boolean get() = phase != McqPhase.FINISHED && (_answers.isNotEmpty() || selected != null)
}

/** Keeps the session alive across configuration changes; scoped to the quiz nav entry. */
class McqViewModel : ViewModel() {
    var session: McqSession? = null
        private set

    fun ensure(questions: List<Question>, passPercent: Int): McqSession =
        session ?: McqSession(questions, passPercent).also { session = it }
}
