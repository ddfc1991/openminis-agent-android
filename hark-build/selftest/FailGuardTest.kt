import com.openminis.app.agent.FailurePatternGuard
fun main() {
    var fail = 0
    val g = FailurePatternGuard()
    val r = (1..3).map { g.onResult("shell_execute", false, "starting browser...\nError: browserType.connectOverCDP: WebSocket error: 400 Bad Request (session ${it}f1a${it}b2c3d4e5)") }
    println(r); if (!(r[0] == null && r[1] == null && r[2] != null)) { fail++; println("FAIL cdp loop") }
    println(g.nextStep()); if (!g.nextStep().contains("内置浏览器")) { fail++; println("FAIL next step") }
    val g2 = FailurePatternGuard()
    val ok = (1..5).map { g2.onResult("shell_execute", false, "total 12\n-rw-r--r-- file$it.txt\ndone") }
    if (ok.any { it != null }) { fail++; println("FAIL false positive on normal output") }
    val g3 = FailurePatternGuard()
    val diff = listOf("Error: ENOENT no such file a", "Error: permission denied", "Error: connection refused").map { g3.onResult("shell_execute", true, it) }
    if (diff.any { it != null }) { fail++; println("FAIL different errors stopped") }
    println("RESULT fail=$fail"); if (fail > 0) kotlin.system.exitProcess(1)
}
