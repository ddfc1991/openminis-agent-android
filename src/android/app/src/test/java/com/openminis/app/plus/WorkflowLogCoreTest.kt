package com.openminis.app.plus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [Mines-38.9.8] 工作流记录: record, keep, mask secrets, save as skill. */
class WorkflowLogCoreTest {
    private fun run(id: String, task: String = "部署 nginx", fin: String = "已部署好") = WorkflowLogCore.build(id, "sid", task,
        listOf(WorkflowLogCore.Step("shell_execute", "ssh root@x apt install nginx"), WorkflowLogCore.Step("shell_execute", "curl token=abcdefgh123", ok = false, error = "401 api_key: sk-abcdefghijklmnop")),
        fin, false, 1000, 61_000)

    @Test fun buildAndRedact() {
        val r = run("r1")
        assertEquals("done", r.status); assertEquals(60_000, r.durationMs)
        assertFalse(r.steps[1].brief.contains("abcdefgh123")); assertFalse(r.steps[1].error.contains("sk-abcdefghijklmnop"))
        assertEquals("failed", WorkflowLogCore.build("x", "s", "t", emptyList(), "没成功：连不上", false, 0, 1).status)
        assertEquals("stopped", WorkflowLogCore.build("x", "s", "t", emptyList(), "", true, 0, 1).status)
    }

    @Test fun keepNewestFirstAndRoundTrip() {
        var l = emptyList<WorkflowLogCore.Run>()
        for (i in 0 until 205) l = WorkflowLogCore.add(l, run("r$i"))
        assertEquals(WorkflowLogCore.MAX, l.size); assertEquals("r204", l.first().id)
        val back = WorkflowLogCore.fromJson(WorkflowLogCore.toJson(l.take(3)))
        assertEquals(l.take(3), back)
    }

    @Test fun skillMd() {
        val r = run("r1", task = "Deploy nginx on server")
        assertEquals("wf-deploy-nginx-on-server", WorkflowLogCore.skillId(r))
        val md = WorkflowLogCore.toSkillMd(r)
        assertTrue(md.startsWith("---\nname: \""))
        assertTrue(md.contains("1. `shell_execute` ssh root@x apt install nginx"))
        assertTrue(md.contains("踩过的坑")); assertFalse(md.contains("sk-abcdefghijklmnop"))
        assertTrue(WorkflowLogCore.skillId(run("r2", task = "部署")).startsWith("workflow-"))
    }
}
