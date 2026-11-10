package com.askthecar

import android.content.BroadcastReceiver
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.WindowManager
import java.io.File
import java.net.URL
import kotlin.concurrent.thread

/** PC 브리지 연결. 에뮬레이터일 때만 켠다(디버그 앱을 실차에 깔아도 10.0.2.2 로 접속을 시도하지 않게) */
class BeamNgLink : SimLink {
    private val source = TestModeSource()
    private val emulator = Build.HARDWARE == "ranchu" || "generic" in Build.FINGERPRINT || "emulator" in Build.FINGERPRINT
    override val connected get() = source.connected

    override fun start(ctx: Context, onIgnitionOn: () -> Unit) {
        if (!emulator) return
        appCtx = ctx
        source.onIgnition = { on ->
            if (on) onIgnitionOn() // 내비·프로필은 시동 그림 뒤에서 미리 뜬다
            cover(boot = on)
        }
        if (!source.isRunning) source.start() // 브리지가 없으면 2초마다 조용히 재시도
    }

    override fun stop() { source.onIgnition = null; removeCover(); source.stop() }

    override fun openHome(ctx: Context) = NaviActivity.open(ctx)

    /** PC 시작 프로그램을 다시 켰다: 시동이 이미 켜져 있어도 첫 값에 내비·프로필을 다시 띄운다 */
    fun newSession() { source.ignition = null; removeCover() }

    private var appCtx: Context? = null
    private var screen: ScreenCover? = null
    private val main = Handler(Looper.getMainLooper())

    /** 시동 꺼짐 = 검은 덮개를 바로 씌움(Android 재우기는 몇 초 걸림), 켜짐 = 그 덮개가 시동 그림을 보여 주고 사라짐 */
    private fun cover(boot: Boolean) = main.post {
        val ctx = appCtx ?: return@post
        val v = screen ?: run {
            val a11y = SimCoverService.instance // 켜져 있으면 상단 바까지 가린다
            if (a11y == null && !Settings.canDrawOverlays(ctx)) return@post
            val host: Context = a11y ?: ctx
            val type = if (a11y != null) WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY else WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            ScreenCover(host) { removeCover() }.also {
                val wm = host.getSystemService(WindowManager::class.java)
                // 접근성 서비스가 끊긴 직후(앱이 죽었다 살아난 뒤)엔 창 토큰이 없어 BadTokenException 으로 앱이 반복해서 죽었음 → 덮개 없이 진행
                runCatching {
                    wm.addView(it, WindowManager.LayoutParams(-1, -1, type,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED, PixelFormat.TRANSLUCENT)
                        .apply { layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS })
                }.onFailure { e -> android.util.Log.w("AskTheCar", "시동 덮개 창 실패", e); return@post }
                screen = it; coverWm = wm
            }
        }
        if (boot) v.boot() else v.black()
    }

    private var coverWm: WindowManager? = null
    private var badge: ProfileBadge? = null

    private fun removeCover() = main.post {
        screen?.let { v -> runCatching { coverWm?.removeView(v) } }
        screen = null
        if (badge == null) addBadge() // 덮개를 씌울 때 그 아래로 간다(나중에 올린 창이 위)
    }

    /** 상단 바 위에 그릴 수 있을 때만(접근성 덮개 서비스) */
    private fun addBadge() {
        val a11y = SimCoverService.instance ?: return
        val wm = a11y.getSystemService(WindowManager::class.java)
        val d = a11y.resources.displayMetrics
        val size = (d.widthPixels * 0.0235f).toInt()
        val slot = (d.widthPixels * 0.03f).toInt() // 밀려난 실제 동그라미 자리까지(캡처에서 잼)
        val b = ProfileBadge(a11y)
        runCatching {
            wm.addView(b, WindowManager.LayoutParams(size + slot, size, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT).apply {
                gravity = android.view.Gravity.TOP or android.view.Gravity.START
                // 실제 동그라미 가운데(캡처: 가로 84.15%, 세로 3.1%)
                x = (d.widthPixels * 0.8415f).toInt() - size / 2 - slot; y = (d.heightPixels * 0.031f).toInt() - size / 2
            })
        }.onFailure { e -> android.util.Log.w("AskTheCar", "프로필 동그라미 창 실패", e); return } // 위 cover 와 같은 이유
        badge = b
    }

    fun refreshBadge() = main.post { badge?.invalidate() }

    /** 시동 켜짐: 게임 지도 내비(시동 그림 뒤에서 미리 뜬다). 실차 APK 에는 없다 */
    override fun onBoot(ctx: Context) {
        if (!emulator || CrashFlow.screenOpen) return
        NaviActivity.open(ctx)
    }

    /** 브리지가 PC 음성으로 읽는다. 브리지가 막혀 있으면(PC 음성 없음 등) 에뮬레이터 TTS 로 */
    override fun say(text: String, fallback: () -> Unit) {
        thread(name = "pc-voice", isDaemon = true) {
            val ok = runCatching {
                val c = URL("http://10.0.2.2:8766/say?text=" + java.net.URLEncoder.encode(text, "UTF-8")).openConnection() as java.net.HttpURLConnection
                c.connectTimeout = 1_000; c.readTimeout = 1_000
                c.responseCode == 200
            }.getOrDefault(false)
            if (!ok) fallback()
        }
    }

    override fun cameraVideo(out: File): Double? = runCatching {
        val c = URL("http://10.0.2.2:8766/clip/cameras.mp4").openConnection() as java.net.HttpURLConnection
        c.connectTimeout = 3000
        c.readTimeout = 30000 // 브리지가 OBS 저장을 기다린 뒤 응답한다
        val part = File(out.path + ".part")
        c.inputStream.use { i -> part.outputStream().use { i.copyTo(it) } }
        check(part.length() == c.contentLengthLong) // 잘린 영상에 지문을 찍지 않게
        part.renameTo(out)
        c.getHeaderField("X-Clip-End")?.toDouble()
    }.getOrNull()

    // shortcut: 에뮬레이터가 OBS 가상 카메라를 웹캠으로 못 봐서 같은 사진 한 장을 브리지에서 받음. 웹캠으로 보이면 Camera2 하나로 통일
    override fun snapshot(out: File, done: (Boolean) -> Unit) {
        thread(name = "game-camera", isDaemon = true) {
            val ok = runCatching {
                // 게임 그림의 알파 값은 투명도가 아니다. 기본(미리 곱함)으로 읽으면 픽셀이 검게 깨져 차량 인식이 안 됨
                val raw = android.graphics.BitmapFactory.Options().apply { inPremultiplied = false }
                val bmp = URL("http://10.0.2.2:8766/cam/latest.jpg").openStream().use { android.graphics.BitmapFactory.decodeStream(it, null, raw) }!!
                out.outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, it) } // 게임 카메라는 PNG
            }.isSuccess
            if (!ok) out.delete()
            done(ok)
        }
    }
}

/** 앱 프로세스 시작 때 Sim.link 를 끼운다. 이 파일이 없으면(release) 끼울 것도 없다 */
class SimInstaller : ContentProvider() {
    override fun onCreate(): Boolean { Sim.link = BeamNgLink(); return true }
    override fun query(u: Uri, p: Array<String>?, s: String?, a: Array<String>?, o: String?) = null
    override fun getType(u: Uri): String? = null
    override fun insert(u: Uri, v: ContentValues?): Uri? = null
    override fun delete(u: Uri, s: String?, a: Array<String>?) = 0
    override fun update(u: Uri, v: ContentValues?, s: String?, a: Array<String>?) = 0
}

/** PC(game/bridge/launcher.py, adb)가 보내는 신호. BOOT_COMPLETED 는 adb 로 못 보냄 */
class SimReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.action) {
            "com.askthecar.START" -> { (Sim.link as? BeamNgLink)?.newSession(); BootReceiver.begin(ctx) }
            "com.askthecar.TEST_CRASH" -> CarService.running?.manualTrigger(intent.getBooleanExtra("severe", false))
            "com.askthecar.RESET" -> CarService.running?.cancelIncident()
        }
    }
}
