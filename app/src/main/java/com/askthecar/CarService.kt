package com.askthecar

import java.io.File
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import kotlin.concurrent.thread

/**
 * 주행 중 화면 없이 계속 기록하는 포그라운드 서비스.
 * 사고가 감지되면 CrashActivity 를 앞으로 띄운다.
 * 백그라운드에서 화면을 띄우려면 '다른 앱 위에 표시' 권한이 필요하다 (개발 중: adb shell appops set com.askthecar SYSTEM_ALERT_WINDOW allow).
 * 권한이 없으면 전체 화면 알림 + 음성으로 대신 알린다.
 * 미확인: Pleos 앱마켓 심사에서 포그라운드 서비스·다른 앱 위에 표시가 허용되는지 (운영국 문의 항목)
 */
class CarService : Service() {
    private var pleos: PleosSource? = null
    private lateinit var watcher: CrashWatcher

    override fun onCreate() {
        super.onCreate()
        running = this
        startForeground(1, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE) // startForegroundService 뒤엔 꼭 불러야 함
        Consent.init(this)
        if (!Consent.allowed(Consent.Item.DRIVE)) { Log.w("AskTheCar", "주행 기록 동의 없음 — 기록 안 함"); running = null; return stopSelf() }
        // 감지기 먼저: 아래 SDK 중 하나가 권한 오류로 죽어도 사고 감지는 돌아야 함
        CarSync.init(this)
        watcher = CrashWatcher(this, ::bringBackAfterReverse) { showCrashScreen(); CarSync.publish() }.also { it.start() } // 휴대폰에도 같은 사고 알림
        runCatching { Voice.init(this) }.onFailure { Log.w("AskTheCar", "TTS 시작 실패", it) }
        runCatching { Statement.init(this) }.onFailure { Log.w("AskTheCar", "STT 시작 실패", it) }
        pleos = runCatching {
            PleosSource(this).also { it.start(locationGranted = checkSelfPermission(LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED) }
        }.onFailure { Log.w("AskTheCar", "Pleos SDK 시작 실패", it) }.getOrNull()
        Sim.link?.start(this) { Sim.link?.onBoot(this) }
        // 앱이 다시 켜져도 "최근 사고 기록"이 보이게 가장 최근 사고 폴더에서 다시 읽음
        thread(name = "load-record", isDaemon = true) {
            Consent.cleanup(this) // 보관 기간 지난 사고 기록 삭제 (불러오기 전에)
            if (CrashFlow.record == null) CrashWatcher.loadLatest(this)?.let { r ->
                if (CrashFlow.record != null) return@let
                CrashFlow.record = r
                r.kind?.let { CrashFlow.kind = it; CrashFlow.kindChosen = r.kindChosen }
            }
        }
    }

    override fun onDestroy() {
        running = null
        if (!::watcher.isInitialized) return super.onDestroy() // 동의 없이 시작됐다 바로 멈춘 경우
        watcher.stop(); Sim.link?.stop(); pleos?.stop()
        runCatching { Voice.release() }; runCatching { Statement.release() }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_MANUAL && ::watcher.isInitialized) watcher.trigger("운전자 직접 기록", severe = false, manual = true)
        return super.onStartCommand(intent, flags, startId)
    }

    fun manualTrigger(severe: Boolean = false) = watcher.trigger(if (severe) "에어백 전개" else "충격 신호", severe, practice = true)
    fun cancelIncident() = watcher.cancel()

    /** 후진에서 빠지면 Pleos 가 후방 카메라를 닫으며 기본 내비를 맨 앞으로 올려 사고 안내를 덮는다(에뮬레이터 로그로 확인). 카메라가 닫힌 뒤 다시 올린다 */
    private fun bringBackAfterReverse() {
        android.os.Handler(mainLooper).postDelayed({
            if (CrashFlow.screenOpen && Settings.canDrawOverlays(this))
                startActivity(Intent(this, CrashActivity::class.java).putExtra(CrashActivity.EXTRA_RESUME, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
            else Sim.link?.openHome(this)
        }, REVERSE_CAMERA_CLOSE_MS)
    }

    private fun showCrashScreen() {
        val i = Intent(this, CrashActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (Settings.canDrawOverlays(this)) return startActivity(i)
        // 권한이 없으면 백그라운드 startActivity 는 조용히 무시됨 → 전체 화면 알림 + 음성
        Log.w("AskTheCar", "'다른 앱 위에 표시' 권한 없음 — 알림으로 사고 화면을 띄움")
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("crash_alert", "사고 감지", NotificationManager.IMPORTANCE_HIGH))
        val open = PendingIntent.getActivity(this, 3, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        nm.notify(3, Notification.Builder(this, "crash_alert")
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle("사고가 감지되었어요")
            .setContentText("눌러서 안내를 받으세요")
            .setCategory(Notification.CATEGORY_ALARM)
            .setContentIntent(open)
            .setFullScreenIntent(open, true)
            .setAutoCancel(true)
            .build())
        Voice.say("사고가 감지되었어요. 화면을 눌러 안내를 받으세요.")
    }

    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("drive", "주행 기록", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, "drive")
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle("괜차나")
            .setContentText("사고에 대비해 최근 주행을 기록하고 있어요")
            .setContentIntent(open)
            .build()
    }

    companion object {
        const val LOCATION = "pleos.car.permission.FUSED_LOCATION"
        const val ACTION_MANUAL = "com.askthecar.MANUAL_RECORD"
        const val REVERSE_CAMERA_CLOSE_MS = 3_500L // 로그에서 약 2.7초 뒤 올라옴. 실차 값은 미확인
        @Volatile var running: CarService? = null
            private set

        fun start(ctx: Context) = ctx.startForegroundService(Intent(ctx, CarService::class.java))
    }
}
