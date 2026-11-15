package com.askthecar.qa.drive

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.askthecar.CarState
import com.askthecar.CarSync
import com.askthecar.Consent
import com.askthecar.CrashFlow
import com.askthecar.CrashWatcher
import com.askthecar.Incident
import com.askthecar.qa.bench.QaContext
import com.askthecar.qa.bench.Slow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList

/**
 * DRV-12 / F-01 on the real CrashWatcher.start() 10 Hz loop, real time, real files.
 * Spec: DET-04 ("저장 대기 중 반복 신호를 억제하고 에어백 신호가 오면 심각도를 올린다"), §4.5 ("새 에어백 전개는 억제하지 않고
 * 즉시 반영"), §4.4 table row 2 / item 5 (유효한 새 에어백 전개 → 우선 등급, 같은 기록을 중복 생성하지 않음), DET-02 (airbag edge path).
 */
class Drv12AirbagUpgradeTest {

    @Test fun drv12_airbagEdgeWhilePending_upgradesWithinFirst60s() =
        scenario(impactAt = 5.0, airbagAt = 5.5)

    @Slow("drives ~67 s of real time") @Test fun drv12control_airbagEdgeWhilePending_upgradesAfter60s() =
        scenario(impactAt = 65.0, airbagAt = 65.5)

    /** Same as DRV-12 but the pre-save folder write cannot happen: shows whether the suppression depends on the folder. */
    @Test fun drv12diag_upgradeDoesNotDependOnPreSaveFolder() =
        scenario(impactAt = 5.0, airbagAt = 5.5, diskBlocked = true)

    /** Variant: driver says "사고 아니에요" (cancel) right after the impact, then the airbag deploys. §4.5: after 사고 아님 a new signal is a new card. */
    @Test fun drv12b_airbagAfterFalseAlarmCancel_isNotDroppedWithinFirst60s() {
        Bench(diskBlocked = false).use { b ->
            b.drive(impactAt = 5.0, cancelAt = 5.2, airbagAt = 5.5)
            val inc = CrashFlow.incident
            assertTrue("DRV-12b: the airbag was sampled by the real loop", b.airbagSampled())
            assertEquals("DRV-12b: incidents raised (impact, then airbag after cancel) ${b.incidents.map { it.reason }}", 2, b.incidents.size)
            assertNotNull("DRV-12b: an incident must be in progress after the airbag edge", inc)
            assertTrue("DRV-12b: new incident must be severe, got ${inc!!.reason}", inc.severe)
        }
    }

    /** Variant: airbag deploys 11.5 s after the impact, i.e. after the 10 s save window (no longer "pending"), app up 16.5 s. §4.5: never suppressed. */
    @Test fun drv12c_airbagEdgeAfterSaveWindow_isNotDroppedWithinFirst60s() {
        Bench(diskBlocked = false).use { b ->
            b.drive(impactAt = 5.0, cancelAt = null, airbagAt = 16.5)
            val inc = CrashFlow.incident
            assertTrue("DRV-12c: the airbag was sampled by the real loop", b.airbagSampled())
            assertNotNull("DRV-12c: an incident is shown", inc)
            assertTrue("DRV-12c: airbag edge 11.5 s after the impact (after the save window) must leave a severe incident shown; " +
                "got severe=${inc!!.severe}, reason='${inc.reason}', incidents=${b.incidents.map { it.reason }}", inc.severe)
        }
    }

    private fun scenario(impactAt: Double, airbagAt: Double, diskBlocked: Boolean = false) = Bench(diskBlocked).use { b ->
        b.drive(impactAt = impactAt, cancelAt = null, airbagAt = airbagAt)
        val inc = CrashFlow.incident
        val folders = b.crashFolders()
        assertTrue("airbag=true must have been sampled by the real 10 Hz loop", b.airbagSampled())
        assertEquals("exactly one incident raised via onIncident; got ${b.incidents.map { it.reason }}", 1, b.incidents.size)
        assertTrue("first incident comes from the impact path: ${b.incidents[0].reason}", b.incidents[0].reason.startsWith("충격 감지") && !b.incidents[0].severe)
        if (diskBlocked) assertEquals("diag precondition: no crash folder could be written", emptyList<String>(), folders)
        else assertEquals("exactly one incident folder on disk", 1, folders.size)
        assertNotNull("incident still in progress", inc)
        assertTrue("DET-04: airbag edge ${"%.1f".format(airbagAt - impactAt)} s after the impact (app up ${"%.1f".format(airbagAt)} s) must upgrade the pending " +
            "incident to severe; got severe=${inc!!.severe}, reason='${inc.reason}'", inc.severe)
        assertEquals("reason after upgrade", "에어백 전개", inc.reason)
    }

    /** One app sandbox + the real CrashWatcher. Sensor events are delivered on a feeder thread (the device uses the "impact" HandlerThread). */
    private class Bench(diskBlocked: Boolean) : AutoCloseable {
        val root: File = Files.createTempDirectory("qa-drv12").toFile()
        val ctx = QaContext(root, filesDirOverride = if (diskBlocked) File(root, "blocked/files").also { File(root, "blocked").writeText("a file, not a folder") } else null)
        val incidents = CopyOnWriteArrayList<Incident>()
        val watcher = CrashWatcher(ctx, onLeftReverse = {}, onIncident = { incidents += it }) // CarService also calls showCrashScreen() + CarSync.publish()
        private var t0 = 0.0
        @Volatile private var feeding = true
        @Volatile private var pulse: FloatArray? = null
        private var feeder: Thread? = null

        init {
            Consent.init(ctx)
            Consent.set(ctx, Consent.Bundle.RECORD, true) // CarService only runs with DRIVE consent
            CarSync.init(ctx) // BuildConfig.SYNC_URL is empty on the bench -> publish() is a no-op
            CarState.update { copy(speedKph = 40.0, gear = "D", airbag = false) }
        }

        fun now() = System.currentTimeMillis() / 1000.0
        private fun sleepUntil(t: Double) { while (now() < t) Thread.sleep(((t - now()) * 1000).toLong().coerceIn(1, 50)) }

        fun drive(impactAt: Double, cancelAt: Double?, airbagAt: Double) {
            t0 = now()
            watcher.start() // CrashWatcher.start reads its own `started` right after this point
            val reg = ctx.sensors.registrations.single { it.sensor.type == Sensor.TYPE_ACCELEROMETER }
            feeder = startFeeder(reg.listener, reg.sensor)
            sleepUntil(t0 + impactAt)
            pulse = floatArrayOf(0f, SensorManager.GRAVITY_EARTH, 3.0f * SensorManager.GRAVITY_EARTH) // ~2.94 g after gravity removal
            cancelAt?.let { sleepUntil(t0 + it); watcher.cancel() }
            sleepUntil(t0 + airbagAt)
            CarState.fromCar("airbag") { copy(airbag = true) } // PleosSource's SeatAirbagDeployedListener path
            sleepUntil(t0 + airbagAt + 1.5)
        }

        private fun startFeeder(l: SensorEventListener, s: Sensor) = Thread({
            val rest = floatArrayOf(0f, SensorManager.GRAVITY_EARTH, 0f)
            while (feeding) {
                val v = pulse?.also { pulse = null } ?: rest
                l.onSensorChanged(SensorEvent(s, System.nanoTime(), v.copyOf()))
                Thread.sleep(20) // ~50 Hz, SENSOR_DELAY_GAME
            }
        }, "impact").apply { isDaemon = true; start() }

        fun airbagSampled() = watcher.recorder.between(t0, now()).any { it.airbag }
        fun crashFolders(): List<String> = File(ctx.filesDir, "crashes").list()?.sorted().orEmpty()

        override fun close() { feeding = false; watcher.stop(); feeder?.join(1000); root.deleteRecursively() }
    }
}
