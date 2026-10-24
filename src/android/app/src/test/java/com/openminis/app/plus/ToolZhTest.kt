package com.openminis.app.plus

import org.junit.Assert.assertEquals
import org.junit.Test

class ToolZhTest {
    @Test fun fromTheUsersScreenshot() {
        assertEquals("任务进度", ToolZh.title("Task Progress", "task_progress"))
        assertEquals("执行命令", ToolZh.title("Execute Shell", "shell_execute"))
        assertEquals("执行命令", ToolZh.title("Execute Shell…", "shell_execute"))
        assertEquals("写文件", ToolZh.title("file_write", "file_write"))
        assertEquals("写文件", ToolZh.title("", "file_write"))
        assertEquals("打补丁并校验 JS 语法", ToolZh.title("打补丁并校验 JS 语法", "shell_execute"))
        assertEquals("执行命令", ToolZh.title("Execute Shell", null))
        assertEquals("Some New Tool", ToolZh.title("", "some_new_tool"))
    }
}
