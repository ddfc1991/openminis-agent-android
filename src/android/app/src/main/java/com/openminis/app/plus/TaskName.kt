package com.openminis.app.plus

/**
 * [Mines-38.9.13 task_projects] The display name of a conversation for other features (handoff popups, feat/handoff-v2):
 * 「Railway 注册 · 第2次」 when the conversation is an attempt of a project, else null (caller keeps its own title).
 * Thread-safe, no I/O; returns null while the 任务项目 flag is off or the store is not loaded yet.
 */
object TaskName {
    fun of(sid: String?): String? {
        if (sid.isNullOrBlank() || !TaskProjects.enabled()) return null
        return TaskProjectsCore.displayName(TaskProjects.projects.value, sid)
    }
}
