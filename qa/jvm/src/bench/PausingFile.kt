package com.askthecar.qa.bench

import java.io.File
import java.io.FileFilter
import java.io.FilenameFilter
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Same path as a plain File, so equals()/hashCode()/path compare equal to the product's own File objects.
 * The FIRST disk-touching call made by a thread that (a) is not [isCaller] and (b) is running a CrashFlow
 * lambda (the kind-write job started by CrashFlow.chooseKind) blocks until [release]; every call then
 * delegates to java.io.File unchanged. The pause therefore sits after the job captured this reference and
 * before its first disk access through it. Product code is not modified.
 */
class PausingFile(path: String, private val isCaller: (Thread) -> Boolean) : File(path) {
    private val armed = AtomicBoolean(true)
    private val reached = CountDownLatch(1)
    private val go = CountDownLatch(1)
    @Volatile var pausedThread: Thread? = null; private set
    @Volatile var pausedIn: String? = null; private set
    @Volatile var pausedFrame: String? = null; private set

    private fun gate(op: String) {
        if (!armed.get()) return
        val me = Thread.currentThread()
        if (isCaller(me)) return
        val frame = me.stackTrace.firstOrNull(::isKindJobFrame) ?: return
        if (!armed.compareAndSet(true, false)) return
        pausedThread = me; pausedIn = op; pausedFrame = "${frame.className}.${frame.methodName}"
        reached.countDown()
        go.await(60, TimeUnit.SECONDS) // safety net only; the test releases explicitly
    }

    fun awaitPaused(ms: Long) = reached.await(ms, TimeUnit.MILLISECONDS)
    val paused get() = reached.count == 0L
    fun release() { armed.set(false); go.countDown() }

    override fun mkdirs(): Boolean { gate("mkdirs"); return super.mkdirs() }
    override fun mkdir(): Boolean { gate("mkdir"); return super.mkdir() }
    override fun exists(): Boolean { gate("exists"); return super.exists() }
    override fun isDirectory(): Boolean { gate("isDirectory"); return super.isDirectory() }
    override fun isFile(): Boolean { gate("isFile"); return super.isFile() }
    override fun canRead(): Boolean { gate("canRead"); return super.canRead() }
    override fun canWrite(): Boolean { gate("canWrite"); return super.canWrite() }
    override fun length(): Long { gate("length"); return super.length() }
    override fun lastModified(): Long { gate("lastModified"); return super.lastModified() }
    override fun setLastModified(time: Long): Boolean { gate("setLastModified"); return super.setLastModified(time) }
    override fun delete(): Boolean { gate("delete"); return super.delete() }
    override fun createNewFile(): Boolean { gate("createNewFile"); return super.createNewFile() }
    override fun renameTo(dest: File): Boolean { gate("renameTo"); return super.renameTo(dest) }
    override fun list(): Array<String>? { gate("list"); return super.list() }
    override fun list(filter: FilenameFilter?): Array<String>? { gate("list"); return super.list(filter) }
    override fun listFiles(): Array<File>? { gate("listFiles"); return super.listFiles() }
    override fun listFiles(filter: FileFilter?): Array<File>? { gate("listFiles"); return super.listFiles(filter) }
    override fun listFiles(filter: FilenameFilter?): Array<File>? { gate("listFiles"); return super.listFiles(filter) }
    override fun getCanonicalPath(): String { gate("getCanonicalPath"); return super.getCanonicalPath() }
    override fun getCanonicalFile(): File { gate("getCanonicalFile"); return super.getCanonicalFile() }

    companion object {
        /** A frame of a lambda/inner class of com.askthecar.CrashFlow (e.g. chooseKind$lambda$2$lambda$1 in 5ffe61e). */
        fun isKindJobFrame(e: StackTraceElement) =
            (e.className == "com.askthecar.CrashFlow" && e.methodName.contains("lambda")) || e.className.startsWith("com.askthecar.CrashFlow$")
    }
}
