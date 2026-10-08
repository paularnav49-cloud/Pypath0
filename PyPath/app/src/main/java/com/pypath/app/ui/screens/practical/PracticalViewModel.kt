package com.pypath.app.ui.screens.practical

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pypath.app.PyPathApp
import com.pypath.app.practical.CheckReport
import com.pypath.app.practical.EngineState
import com.pypath.app.practical.PracticalTask
import com.pypath.app.practical.PythonRunner
import com.pypath.app.practical.RunEnd
import com.pypath.app.practical.RunOutcome
import com.pypath.app.practical.RunnerListener
import com.pypath.app.practical.TestRun
import com.pypath.app.practical.evaluateTest
import com.pypath.app.practical.loadPracticalCatalog
import com.pypath.app.practical.runEndFor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class ConsoleKind { STDOUT, STDERR, INPUT, SYSTEM }

data class ConsoleSegment(val kind: ConsoleKind, val text: String)

enum class RunPhase { IDLE, STARTING, RUNNING, WAITING_INPUT }

/** Result of the most recent run, shown in the status pill and error card. */
data class RunResult(
    val kind: Kind,
    val elapsedMs: Long,
    val exitCode: Int = 0,
    /** Last line of the traceback, e.g. "NameError: name 'x' is not defined". */
    val errorSummary: String? = null,
    /** Line in main.py where the error happened, parsed from the real traceback. */
    val errorLine: Int? = null,
) {
    enum class Kind { SUCCESS, ERROR, EXITED, TIMEOUT, STOPPED, CRASHED, INTERNAL }
}

/**
 * State of the latest Check of one task (see [PracticalViewModel.check] and [PracticalViewModel.checkState]).
 * [report] grows as tests finish; [running] is true until the last test is done.
 */
data class CheckState(
    val taskId: String,
    val runId: Int,
    val report: CheckReport,
    val running: Boolean,
    /** Stop was pressed (or the task was changed) before every test had run. */
    val stopped: Boolean = false,
    /** Set when Python could not run at all (the check ends early). */
    val problem: String? = null,
) {
    /** 1-based number of the test that is running now. */
    val currentTest: Int get() = minOf(report.results.size + 1, report.total)
    val allPassed: Boolean get() = !running && !stopped && problem == null && report.allPassed
}

/**
 * State for the Practical tab. Code lives only in memory for this app session: it is never
 * written to disk, uploaded or synced. Activity-scoped, so it survives tab switches and rotation.
 */
class PracticalViewModel(app: Application) : AndroidViewModel(app), RunnerListener {

    var tasks by mutableStateOf<List<PracticalTask>>(emptyList()); private set
    var loadError by mutableStateOf<String?>(null); private set
    var selectedTaskId by mutableStateOf<String?>(null); private set
    private val drafts = mutableStateMapOf<String, TextFieldValue>()

    val console = mutableStateListOf<ConsoleSegment>()
    var engine by mutableStateOf<EngineState>(EngineState.Starting); private set
    var phase by mutableStateOf(RunPhase.IDLE); private set
    var inputPrompt by mutableStateOf(""); private set
    var lastResult by mutableStateOf<RunResult?>(null); private set
    var elapsedMs by mutableStateOf(0L); private set
    var hasRunOnce by mutableStateOf(false); private set
    private var stderrBuffer = StringBuilder()
    private var consoleChars = 0

    /** Latest Check results for the selected task, or null. */
    var checkState by mutableStateOf<CheckState?>(null); private set
    /** Set when a Check passes every test; the screen plays the "correct" sound and clears it. */
    var successSoundPending by mutableStateOf(false); private set
    /** Tasks completed in this app session (the stored set arrives through the course snapshot). */
    var completedThisSession by mutableStateOf<Set<String>>(emptySet()); private set
    private var checkSession: CheckSession? = null
    private var nextCheckRunId = 0

    private val runner = PythonRunner(app, this)

    init {
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { loadPracticalCatalog(getApplication()) } }
                .onSuccess { tasks = it.tasks }
                .onFailure { loadError = it.message ?: "Could not load practical tasks" }
        }
    }

    /** Called when the Practical tab is shown: starts the interpreter in the background. */
    fun warmUp() = runner.start()

    val selectedTask: PracticalTask? get() = tasks.firstOrNull { it.id == selectedTaskId }

    fun select(taskId: String) {
        if (taskId == selectedTaskId) return
        val s = checkSession
        if (s != null) {
            // A Check in progress belongs to the old task: cancel it (its run ends via onFinished).
            s.cancelled = true
            if (runner.isRunning) runner.stop() else endCheckSession()
        } else if (phase == RunPhase.RUNNING || phase == RunPhase.WAITING_INPUT) {
            runner.stop()
        }
        selectedTaskId = taskId
        console.clear(); consoleChars = 0
        lastResult = null
        hasRunOnce = false
        checkState = null
    }

    /** Picks a default task the first time the tab opens. */
    fun ensureSelection(defaultId: String?) {
        if (selectedTaskId == null && defaultId != null) selectedTaskId = defaultId
    }

    fun code(task: PracticalTask): TextFieldValue =
        drafts[task.id] ?: TextFieldValue(task.starterCode, TextRange(task.starterCode.length))

    fun updateCode(task: PracticalTask, value: TextFieldValue) { drafts[task.id] = value }

    fun resetCode(task: PracticalTask) {
        drafts.remove(task.id)
        if (lastResult?.errorLine != null) lastResult = lastResult?.copy(errorLine = null)
    }

    fun run(task: PracticalTask) {
        if (phase != RunPhase.IDLE) return
        val code = code(task).text
        console.clear(); consoleChars = 0
        stderrBuffer = StringBuilder()
        lastResult = null
        hasRunOnce = true
        elapsedMs = 0
        phase = if (engine is EngineState.Ready) RunPhase.RUNNING else RunPhase.STARTING
        append(ConsoleKind.SYSTEM, if (phase == RunPhase.STARTING) "Starting Python…\n" else "")
        runner.run(code)
        viewModelScope.launch {
            while (isActive && phase != RunPhase.IDLE) {
                elapsedMs = runner.elapsedMs()
                delay(100)
            }
        }
    }

    fun stop() {
        val s = checkSession
        if (s != null) {
            s.cancelled = true
            // Between two tests nothing is running, so finish the check right away.
            if (!runner.isRunning) { endCheckSession(); markCheckStopped(s) } else runner.stop()
            return
        }
        runner.stop()
    }

    // ───────────── Check (automatic tests) ─────────────

    /** One press of Check: runs [task]'s tests one after another with the existing runner and limits. */
    private class CheckSession(val task: PracticalTask, val code: String, val runId: Int) {
        var index = 0
        val transcript = StringBuilder()
        val stderr = StringBuilder()
        var pendingInput = ArrayDeque<String>()
        var ranOutOfInput = false
        /** Stop pressed or task changed: the next finished run ends the check. */
        var cancelled = false
    }

    /**
     * Runs every test of [task] with its preset input lines and records pass/fail. Uses the normal
     * runner: same 10 s limit per run, same sandbox, same :python process, Stop works as usual.
     * Completion is stored only here, and only when every test passes.
     */
    fun check(task: PracticalTask) {
        if (phase != RunPhase.IDLE || !task.checkable || checkSession != null) return
        val s = CheckSession(task, code(task).text, ++nextCheckRunId)
        checkSession = s
        lastResult = null
        elapsedMs = 0
        checkState = CheckState(task.id, s.runId, CheckReport(task.tests.size, emptyList()), running = true)
        phase = if (engine is EngineState.Ready) RunPhase.RUNNING else RunPhase.STARTING
        startTest(s)
        viewModelScope.launch {
            while (isActive && checkSession === s) {
                elapsedMs = runner.elapsedMs()
                delay(100)
            }
        }
    }

    fun consumeSuccessSound() { successSoundPending = false }

    private fun startTest(s: CheckSession) {
        val test = s.task.tests[s.index]
        s.transcript.clear()
        s.stderr.clear()
        s.pendingInput = ArrayDeque(test.stdin)
        s.ranOutOfInput = false
        runner.run(s.code)
    }

    private fun onCheckInput(s: CheckSession) {
        val line = s.pendingInput.removeFirstOrNull()
        if (line == null) {
            // The test has no more answers. Without touching the runner the only way to end the
            // program is the normal Stop (the run is reported as "needs more input").
            s.ranOutOfInput = true
            runner.stop()
            return
        }
        s.transcript.append(line).append('\n') // the console echoes answers the same way
        runner.sendInput(line)
    }

    private fun onCheckFinished(s: CheckSession, outcome: RunOutcome) {
        if (s.cancelled || (outcome == RunOutcome.StoppedByUser && !s.ranOutOfInput)) {
            endCheckSession()
            markCheckStopped(s)
            return
        }
        val (end, exitCode) = runEndFor(outcome, s.ranOutOfInput)
        val (summary, line) = if (end == RunEnd.ERROR) parseTraceback(s.stderr.toString()) else null to null
        val internal = (outcome as? RunOutcome.Completed)?.status?.takeIf { end == RunEnd.INTERNAL }?.removePrefix("internal:")
        val run = TestRun(s.transcript.toString(), end, errorSummary = summary ?: internal, errorLine = line, exitCode = exitCode)
        val result = evaluateTest(s.task, s.index, s.task.tests[s.index], run)
        val prev = checkState?.takeIf { it.runId == s.runId } ?: return endCheckSession()
        val report = prev.report.copy(results = prev.report.results + result)
        if (end == RunEnd.INTERNAL) { // Python itself is unavailable: no point running the rest
            endCheckSession()
            checkState = prev.copy(report = report, running = false, problem = internal ?: "Python could not start")
            return
        }
        s.index++
        if (s.index < s.task.tests.size) {
            checkState = prev.copy(report = report)
            // Start the next test on a later main-loop turn, after the runner has finished its own
            // clean-up (e.g. restarting the interpreter after a time-out or a stopped run).
            viewModelScope.launch {
                delay(NEXT_TEST_DELAY_MS)
                if (checkSession === s && !s.cancelled) startTest(s)
                else if (checkSession === s) { endCheckSession(); markCheckStopped(s) }
            }
            return
        }
        endCheckSession()
        val done = prev.copy(report = report, running = false)
        checkState = done
        if (done.allPassed) markCompleted(s.task.id)
    }

    private fun endCheckSession() {
        checkSession = null
        phase = RunPhase.IDLE
        inputPrompt = ""
    }

    private fun markCheckStopped(s: CheckSession) {
        checkState = checkState?.takeIf { it.runId == s.runId }?.copy(running = false, stopped = true)
    }

    private fun markCompleted(taskId: String) {
        successSoundPending = true
        completedThisSession = completedThisSession + taskId
        val repo = (getApplication<Application>() as? PyPathApp)?.container?.progressRepository ?: return
        viewModelScope.launch { runCatching { repo.markTaskCompleted(taskId) } }
    }

    fun submitInput(text: String) {
        if (phase != RunPhase.WAITING_INPUT) return
        append(ConsoleKind.INPUT, text + "\n")
        phase = RunPhase.RUNNING
        inputPrompt = ""
        runner.sendInput(text)
    }

    fun clearConsole() {
        console.clear(); consoleChars = 0
    }

    // ───────────── RunnerListener (main thread) ─────────────

    override fun onEngineState(state: EngineState) { engine = state }

    override fun onStarted() {
        if (checkSession != null) { if (phase == RunPhase.STARTING) phase = RunPhase.RUNNING; return }
        if (phase == RunPhase.STARTING) {
            phase = RunPhase.RUNNING
            // drop the "Starting Python…" line now that code is running
            if (console.firstOrNull()?.kind == ConsoleKind.SYSTEM) { consoleChars -= console.first().text.length; console.removeAt(0) }
        }
    }

    override fun onOutput(stderr: Boolean, text: String) {
        checkSession?.let { s ->
            if (stderr) s.stderr.append(text).also { if (it.length > 20_000) it.delete(0, it.length - 20_000) }
            else if (s.transcript.length < MAX_CHECK_OUTPUT_CHARS) s.transcript.append(text)
            return
        }
        if (stderr) stderrBuffer.append(text).also { if (it.length > 20_000) it.delete(0, it.length - 20_000) }
        append(if (stderr) ConsoleKind.STDERR else ConsoleKind.STDOUT, text)
    }

    override fun onInputRequested(prompt: String) {
        checkSession?.let { onCheckInput(it); return }
        inputPrompt = prompt
        phase = RunPhase.WAITING_INPUT
    }

    override fun onFinished(outcome: RunOutcome, elapsedMs: Long) {
        checkSession?.let { onCheckFinished(it, outcome); return }
        this.elapsedMs = elapsedMs
        phase = RunPhase.IDLE
        inputPrompt = ""
        val secs = formatSeconds(elapsedMs)
        val result = when (outcome) {
            is RunOutcome.Completed -> when {
                outcome.status == "ok" -> RunResult(RunResult.Kind.SUCCESS, elapsedMs)
                outcome.status == "error" -> {
                    val (summary, line) = parseTraceback(stderrBuffer.toString())
                    RunResult(RunResult.Kind.ERROR, elapsedMs, errorSummary = summary, errorLine = line)
                }
                outcome.status.startsWith("exit:") -> {
                    val code = outcome.status.removePrefix("exit:").toIntOrNull() ?: 1
                    RunResult(if (code == 0) RunResult.Kind.SUCCESS else RunResult.Kind.EXITED, elapsedMs, exitCode = code)
                }
                else -> RunResult(RunResult.Kind.INTERNAL, elapsedMs, errorSummary = outcome.status.removePrefix("internal:"))
            }
            RunOutcome.StoppedByUser -> RunResult(RunResult.Kind.STOPPED, elapsedMs)
            is RunOutcome.TimedOut -> RunResult(RunResult.Kind.TIMEOUT, elapsedMs)
            RunOutcome.Crashed -> RunResult(RunResult.Kind.CRASHED, elapsedMs)
        }
        lastResult = result
        val line = when (result.kind) {
            RunResult.Kind.SUCCESS -> if (result.exitCode == 0 && outcome is RunOutcome.Completed && outcome.status == "exit:0") "Program exited (code 0) · $secs" else "Finished in $secs"
            RunResult.Kind.ERROR -> "Finished with an error · $secs"
            RunResult.Kind.EXITED -> "Program exited with code ${result.exitCode} · $secs"
            RunResult.Kind.TIMEOUT -> "Execution timed out after ${runner.timeLimitMs / 1000} seconds and was stopped. Check for a loop that never ends."
            RunResult.Kind.STOPPED -> "Execution stopped by you after $secs"
            RunResult.Kind.CRASHED -> "The Python process ended unexpectedly (it may have run out of memory). A fresh one has been started."
            RunResult.Kind.INTERNAL -> "Python could not run the code: ${result.errorSummary}"
        }
        val needsBreak = console.lastOrNull()?.text?.endsWith("\n") == false
        append(ConsoleKind.SYSTEM, (if (needsBreak) "\n" else "") + "— $line\n")
    }

    private fun append(kind: ConsoleKind, text: String) {
        if (text.isEmpty()) return
        consoleChars += text.length
        val last = console.lastOrNull()
        if (last != null && last.kind == kind) console[console.size - 1] = last.copy(text = last.text + text)
        else console.add(ConsoleSegment(kind, text))
        // Keep the UI responsive: retain at most ~120k chars (Python caps output at 200k).
        while (consoleChars > MAX_CONSOLE_CHARS && console.size > 1) {
            consoleChars -= console.first().text.length
            console.removeAt(0)
        }
        if (consoleChars > MAX_CONSOLE_CHARS && console.size == 1) {
            val seg = console[0]
            val trimmed = seg.text.takeLast(MAX_CONSOLE_CHARS)
            console[0] = seg.copy(text = trimmed)
            consoleChars = trimmed.length
        }
    }

    override fun onCleared() {
        runner.release()
        super.onCleared()
    }

    companion object {
        private const val MAX_CONSOLE_CHARS = 120_000
        /** A correct answer is far shorter; this only stops a print loop from filling memory. */
        private const val MAX_CHECK_OUTPUT_CHARS = 200_000
        private const val NEXT_TEST_DELAY_MS = 30L

        fun formatSeconds(ms: Long): String = if (ms < 10_000) "%.2fs".format(ms / 1000.0) else "%.1fs".format(ms / 1000.0)

        /** Extracts "ErrorType: message" and the last main.py line number from a real traceback. */
        fun parseTraceback(tb: String): Pair<String?, Int?> {
            val lines = tb.trimEnd().lines().filter { it.isNotBlank() }
            val summary = lines.lastOrNull()?.trim()
            val line = Regex("""File "main\.py", line (\d+)""").findAll(tb).lastOrNull()?.groupValues?.get(1)?.toIntOrNull()
            return summary to line
        }
    }
}
