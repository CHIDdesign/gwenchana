package com.askthecar.qa.bench

import com.askthecar.Consent
import com.askthecar.CrashFlow
import com.askthecar.CrashWatcher
import java.io.File
import kotlin.system.exitProcess

/**
 * Cold start in a NEW JVM on the same sandbox (disk + SharedPreferences kept, every singleton fresh).
 * Runs the real start-up calls: Consent.init, then — only if DRIVE is allowed — the record reload that
 * CarService.onCreate does (Consent.cleanup + CrashWatcher.loadLatest, CarService.kt:44-50; CarService itself
 * is an Android Service and is not compiled on the bench). Prints the AppView.
 */
fun main(args: Array<String>) {
    val (root, dirName) = args
    val ctx = QaContext(File(root))
    Consent.init(ctx)
    if (Consent.allowed(Consent.Item.DRIVE)) {
        Consent.cleanup(ctx)
        if (CrashFlow.record == null) CrashWatcher.loadLatest(ctx)?.let { r ->
            CrashFlow.record = r
            r.kind?.let { CrashFlow.kind = it; CrashFlow.kindChosen = r.kindChosen }
        }
    }
    println("---VIEW---")
    println(observe(ctx, dirName).encode())
    System.out.flush()
    exitProcess(0)
}

/** Runs [main] above in a child JVM with the current classpath. */
fun restartInFreshJvm(root: File, dirName: String, timeoutSec: Long = 30): AppView {
    val javaBin = File(System.getProperty("java.home"), "bin/java").path
    val p = ProcessBuilder(javaBin, "-Dstdout.encoding=UTF-8", "-cp", System.getProperty("java.class.path"), "com.askthecar.qa.bench.RestartProbeKt", root.path, dirName)
        .redirectErrorStream(true).start()
    val out = p.inputStream.bufferedReader().readText()
    if (!p.waitFor(timeoutSec, java.util.concurrent.TimeUnit.SECONDS)) { p.destroyForcibly(); throw AssertionError("BLOCKED_ENVIRONMENT: restart probe timed out") }
    if (p.exitValue() != 0 || "---VIEW---" !in out) throw AssertionError("BLOCKED_ENVIRONMENT: restart probe failed (exit ${p.exitValue()}):\n$out")
    return AppView.decode(out.substringAfter("---VIEW---"))
}
