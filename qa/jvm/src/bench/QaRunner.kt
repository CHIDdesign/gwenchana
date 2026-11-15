package com.askthecar.qa.bench

import org.junit.runner.Description
import org.junit.runner.JUnitCore
import org.junit.runner.Request
import org.junit.runner.notification.Failure
import org.junit.runner.notification.RunListener
import kotlin.system.exitProcess

/**
 * Usage: QaRunner --list <Class>...      prints Class#method for every @Test
 *        QaRunner <Class#method>         runs one test, prints one result line:
 *          PASS | FAIL | BLOCKED (message starts with BLOCKED_) | SKIP (slow test with QA_SLOW=0)
 * Exit code: 0 = PASS/SKIP, 1 = FAIL, 2 = BLOCKED.
 */
fun main(args: Array<String>) {
    if (args.firstOrNull() == "--list") {
        args.drop(1).forEach { c ->
            Class.forName(c).methods.filter { it.isAnnotationPresent(org.junit.Test::class.java) }.sortedBy { it.name }
                .forEach { println("$c#${it.name}") }
        }
        return
    }
    val (cls, method) = args.single().split('#', limit = 2)
    val klass = Class.forName(cls)
    val m = klass.getMethod(method)
    val label = "${klass.simpleName}.$method"
    val slow = m.getAnnotation(Slow::class.java)
    if (slow != null && System.getenv("QA_SLOW") == "0") { println("SKIP     $label (slow: ${slow.why}; QA_SLOW=0)"); exitProcess(0) }
    var failure: Failure? = null
    val core = JUnitCore()
    core.addListener(object : RunListener() { override fun testFailure(f: Failure) { failure = f } })
    val t0 = System.nanoTime()
    core.run(Request.method(klass, method))
    val sec = "%.1fs".format((System.nanoTime() - t0) / 1e9)
    val f = failure
    when {
        f == null -> { println("PASS     $label ($sec)"); exitProcess(0) }
        (f.message ?: "").startsWith("BLOCKED_") -> { println("BLOCKED  $label ($sec): ${f.message}"); exitProcess(2) }
        else -> {
            val where = f.exception.stackTrace.firstOrNull { it.className.startsWith("com.askthecar.qa") }?.let { " @${it.fileName}:${it.lineNumber}" } ?: ""
            println("FAIL     $label ($sec)$where: ${f.exception.javaClass.simpleName}: ${f.message}")
            if (System.getenv("QA_VERBOSE") == "1") f.exception.printStackTrace(System.out)
            exitProcess(1)
        }
    }
}
