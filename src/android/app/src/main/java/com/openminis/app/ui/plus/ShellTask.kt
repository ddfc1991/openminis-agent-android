package com.openminis.app.ui.plus

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

/**
 * [Mines-dsh] Runs a sandbox command in the background.
 * [Mines-jobs-25] No dialog any more: the work goes to [com.openminis.app.plus.MinesJobs],
 * which shows a progress notification and a slim in-app pill while it runs and
 * a short popup when it ends ([MinesJobsHost]). [ShellTaskState] still mirrors
 * the job so screens can disable buttons while it runs.
 */
class ShellTaskState {
    var title by mutableStateOf("")
    var lines by mutableStateOf(listOf<String>())
    var running by mutableStateOf(false)
    var exit by mutableStateOf<Int?>(null)
    var visible by mutableStateOf(false)
}

class ShellTaskRunner(val state: ShellTaskState, val run: (title: String, command: String) -> Unit)

@Composable
fun rememberShellTask(onFinished: (Int) -> Unit = {}): ShellTaskRunner {
    val context = LocalContext.current
    val state = remember { ShellTaskState() }
    val latest = androidx.compose.runtime.rememberUpdatedState(onFinished)
    val jobs by com.openminis.app.plus.MinesJobs.jobs.collectAsState()
    var mine by remember { mutableStateOf(setOf<Long>()) }
    // Mirror "any of my jobs is running" for screens that disable buttons.
    LaunchedEffect(jobs, mine) {
        val my = jobs.filter { it.id in mine }
        state.running = my.any { it.running }
        my.lastOrNull()?.let { j -> state.title = j.title; state.lines = j.lines; state.exit = j.exit }
    }
    val runner = remember {
        ShellTaskRunner(state) { title, command ->
            val id = com.openminis.app.plus.MinesJobs.run(context, title, command) { code -> latest.value(code) }
            mine = mine + id
            state.running = true
        }
    }
    return runner
}

/** Kept for source compatibility; progress now shows in [MinesJobsHost]. */
@Composable
fun ShellTaskDialog(@Suppress("UNUSED_PARAMETER") state: ShellTaskState) {}
