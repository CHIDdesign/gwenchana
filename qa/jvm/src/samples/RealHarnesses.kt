package com.askthecar.qa.samples

import android.hardware.Sensor
import android.hardware.SensorEvent
import com.askthecar.Consent
import com.askthecar.CrashFlow
import com.askthecar.CrashKind
import com.askthecar.CrashWatcher
import com.askthecar.FactTimeline
import com.askthecar.ImpactSensor
import com.askthecar.Incident
import com.askthecar.Personas
import com.askthecar.Sample
import com.askthecar.qa.bench.AppView
import com.askthecar.qa.bench.PausingFile
import com.askthecar.qa.bench.QaContext
import com.askthecar.qa.bench.observe
import com.askthecar.qa.bench.restartInFreshJvm
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** TC-01: a fresh real ImpactSensor per harness; events go straight into its onSensorChanged. */
class RealSensorHarness : SensorHarness {
    private val root = Files.createTempDirectory("qa-tc01").toFile()
    private val ctx = QaContext(root)
    private val calls = CopyOnWriteArrayList<Double>()
    private val sensor = ImpactSensor(ctx) { g -> calls += g }
    private val accel = Sensor(Sensor.TYPE_ACCELEROMETER)

    override fun feed(atMs: Long, x: Float, y: Float, z: Float) =
        sensor.onSensorChanged(SensorEvent(accel, atMs * 1_000_000L, floatArrayOf(x, y, z)))

    /** correctedG via the public peak(): the max corrected g kept by the sensor. With the fixture (baseline 0 g, then one pulse) that is the latest value. */
    override fun snapshot() = SensorObservation(sensor.peak(0.0, Double.MAX_VALUE).first, calls.toList())

    override fun close() { sensor.stop(); root.deleteRecursively() }
}

/**
 * TC-04 on real threads and the real file system. CrashFlow.dir is seeded with a [PausingFile] for the incident
 * folder; the real kind-write job started by CrashFlow.chooseKind pauses at its first disk access through it.
 * UI actions (chooseKind, consent switch) run on a dedicated "main" thread, as on the device.
 */
class RealDeletionHarness : DeletionHarness {
    private val root = Files.createTempDirectory("qa-tc04").toFile()
    private val ctx = QaContext(root)
    private val main = Executors.newSingleThreadExecutor { Thread(it, "main").apply { isDaemon = true } }
    @Volatile private var mainThread: Thread? = null
    private val t0 = System.nanoTime()
    private var dirName = ""
    private var gate: PausingFile? = null
    private var restarted: AppView? = null

    init { main.submit { mainThread = Thread.currentThread() }.get() }

    private fun onMain(what: String, block: () -> Unit) {
        try { main.submit(block).get(5, TimeUnit.SECONDS) }
        catch (e: TimeoutException) {
            gate?.release()
            throw AssertionError("BLOCKED_HARNESS: '$what' did not finish within 5 s on the main thread while the kind write was paused " +
                "(paused in ${gate?.pausedIn} at ${gate?.pausedFrame}). The pause point is probably inside a lock that this call also takes.")
        }
    }

    override fun nowMs() = (System.nanoTime() - t0) / 1_000_000
    override fun advanceTo(atMs: Long) = throw UnsupportedOperationException("TC-04 bench uses real threads; there is no virtual clock")

    /** Real threads: give runnable work a moment; never waits on the paused job. */
    override fun runReady() {
        val g = gate
        val deadline = System.nanoTime() + 1_000_000_000L
        while (g != null && !g.paused && System.nanoTime() < deadline) Thread.sleep(10)
        Thread.sleep(100)
    }

    override fun seedConsentedCase(document: CaseDocument) {
        val t = document.incidentEpochSeconds
        dirName = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date((t * 1000).toLong()))
        onMain("seed") {
            Consent.init(ctx)
            Consent.set(ctx, Consent.Bundle.RECORD, true) // the real "agree" path
            val dir = File(ctx.filesDir, "crashes/$dirName").apply { mkdirs() }
            val log = (0..400).map { i -> Sample(t = t - 30 + i * 0.1, speedKph = 40.0) }
            val logFile = File(dir, "log.json").apply { writeText(CrashWatcher.toJson(log)) }
            File(dir, "log.sha256").writeText(CrashWatcher.sha256(logFile))
            File(dir, "impact.json").writeText(JSONObject().put("t", t).put("peakG", 3.0).put("deg", JSONObject.NULL).toString())
            File(dir, "report.txt").writeText(FactTimeline.build(log, impactT = t, peakG = 3.0).joinToString("\n"))
            File(dir, "statement.txt").writeText(document.statement)
            val record = CrashWatcher.load(dir) ?: throw AssertionError("BLOCKED_HARNESS: real CrashWatcher.load could not read the seeded folder")
            gate = PausingFile(dir.path) { it === mainThread }
            CrashFlow.dir = gate
            CrashFlow.kindChosen = false
            CrashFlow.kind = CrashKind.UNKNOWN
            CrashFlow.setIncident { Incident(t, "충격 감지 (3.0g)", false, false, null, log.filter { it.t in (t - 10)..t }) }
            CrashFlow.record = record
        }
    }

    override fun startKindWriteAndPauseBeforeMkdir() {
        onMain("CrashFlow.chooseKind(OBJECT)") { CrashFlow.chooseKind(CrashKind.OBJECT) }
        gate!!.awaitPaused(2_000)
    }

    override fun withdrawDriveConsent() = onMain("Consent.set(RECORD, false)") {
        // MainActivity's withdraw confirm: Consent.set(this, b, false); if (b.required) Personas.clearInfo(this)
        Consent.set(ctx, Consent.Bundle.RECORD, false)
        Personas.clearInfo(ctx)
    }

    override fun releaseKindWrite() { gate!!.release() }

    override fun awaitKindWriteFinished() {
        val th = gate?.pausedThread ?: return
        th.join(5_000)
        if (th.isAlive) throw AssertionError("BLOCKED_ENVIRONMENT: kind-write job still running 5 s after release (${th.name})")
    }

    override fun restartApp() { restarted = restartInFreshJvm(root, dirName) }

    override fun snapshot(): DeletionObservation {
        val v = restarted ?: observe(ctx, dirName)
        val g = gate
        if (System.getenv("QA_VERBOSE") == "1") println("  [tc04] $v paused=${g?.pausedIn}@${g?.pausedFrame}")
        return DeletionObservation(
            driveConsent = v.driveConsent,
            kindWritePausedBeforeMkdir = g?.paused == true,
            kindWriteFinished = g?.pausedThread?.let { !it.isAlive } == true,
            incidentDirectoryExists = v.incidentDirectoryExists,
            incidentFiles = v.incidentFiles,
            visibleIncidentCount = v.visibleIncidentCount,
            currentIncidentEpochSeconds = v.currentIncidentEpochSeconds,
        )
    }

    override fun close() { gate?.release(); main.shutdownNow(); root.deleteRecursively() }
}
