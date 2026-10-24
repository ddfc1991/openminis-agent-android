package com.openminis.app.crash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CrashRedactCoreTest {
    @Test fun keysAndTokensGone() {
        val raw = """java.io.IOException: 401 for Authorization: Bearer sk-abcdefghijklmnopqrstuvwx
            at x (ghp_AAAAAAAAAAAAAAAAAAAAAAAA) url=https://a.com/?api_key=SECRET1&q=1
            OPENAI_API_KEY=sk-proj-zzzzzzzzzzzzzzzzzzzz
            {"password":"hunter2"} jwt eyJhbGciOiJI.eyJzdWIiOiIxMjM0.abcdefghijk AIzaSyA1234567890abcdefghijklmnopqrstu
            phone 13690688658 mail deningyang740@gmail.com
            -----BEGIN OPENSSH PRIVATE KEY-----
            b3BlbnNzaC1rZXktdjEAAAAA
            -----END OPENSSH PRIVATE KEY-----"""
        val r = CrashRedactCore.redact(raw)
        listOf("sk-abcdefghij", "ghp_AAAA", "SECRET1", "sk-proj-zzz", "hunter2", "eyJhbGciOiJI", "AIzaSyA123", "13690688658", "deningyang740", "b3BlbnNz")
            .forEach { assertFalse("leaked $it in $r", r.contains(it)) }
        assertTrue(r.contains("136****8658")); assertTrue(r.contains("d***@gmail.com")); assertTrue(r.contains("at x ("))
    }

    @Test fun stackTraceKept() {
        val st = "java.lang.IllegalStateException: boom\n\tat com.openminis.app.plus.Ledger.choose(Ledger.kt:63)\n#00 pc 000000000004f1a8  /apex/libc.so (abort+164)"
        assertEquals(st, CrashRedactCore.redact(st))
    }

    @Test fun clipboardAndTitle() {
        assertTrue(CrashRedactCore.forClipboard("x".repeat(100), 10).contains("已截断"))
        assertTrue(CrashRedactCore.title("Reason: ANR\n").contains("ANR"))
        assertEquals("上次 Mines 闪退（底层）", CrashRedactCore.title("Reason: CRASH_NATIVE"))
        assertEquals("上次 Mines 闪退", CrashRedactCore.title("=== Minis Java/Kotlin Crash ==="))
    }
}
