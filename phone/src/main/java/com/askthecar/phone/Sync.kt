package com.askthecar.phone

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat
import com.askthecar.BuildConfig
import com.askthecar.ClaimText
import com.askthecar.CrashKind
import com.askthecar.HandoffCodec
import com.askthecar.HandoffData
import com.askthecar.LinkClient
import com.askthecar.R
import com.askthecar.SyncClient
import com.askthecar.SyncState
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/** 로그인 정보·서버 상태를 앱 전체가 같이 본다 (서비스가 채우고 화면이 읽음) */
object Sync {
    val state = MutableStateFlow<SyncState?>(null)
    /** 받아 둔 사고 전부, 최신이 앞. state 는 서버가 지금 들고 있는 최신 사고 하나 */
    val cases = MutableStateFlow<List<HandoffData>>(emptyList())
    val follow = MutableStateFlow(Follow())
    val connected = MutableStateFlow(false)
    private val sends = Executors.newSingleThreadExecutor()
    private val acking = ConcurrentHashMap.newKeySet<Double>()
    @Volatile private var moves = 0

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("phone", Context.MODE_PRIVATE)
    fun account(ctx: Context) = prefs(ctx).getString("account", null)
    fun server(ctx: Context) = prefs(ctx).getString("server", null) ?: BuildConfig.SYNC_URL
    fun setServer(ctx: Context, url: String) = prefs(ctx).edit().putString("server", url.trim().ifEmpty { BuildConfig.SYNC_URL }).apply()
    fun client(ctx: Context) = account(ctx)?.let { SyncClient(server(ctx), it) }

    private fun oldCache(ctx: Context) = File(ctx.filesDir, "incident.json")
    private fun casesDir(ctx: Context) = File(ctx.filesDir, "cases")

    /** 사고 하나를 cases/<t>.json 에. 임시 파일에 다 쓴 뒤 바꿔치기해서 쓰다 죽어도 옛 파일이 남는다. 저장돼 있으면 true */
    private fun save(ctx: Context, d: HandoffData): Boolean = runCatching {
        val dir = casesDir(ctx).apply { mkdirs() }
        val f = File(dir, "${d.t}.json")
        val text = HandoffCodec.encode(d)
        if (!f.exists() || f.readText() != text) {
            val tmp = File(dir, "${d.t}.tmp")
            tmp.writeText(text)
            check(tmp.renameTo(f))
            cases.value = readCases(ctx)
        }
    }.isSuccess

    private fun readCases(ctx: Context) = casesDir(ctx).listFiles { f -> f.name.endsWith(".json") }.orEmpty()
        .mapNotNull { f -> runCatching { HandoffCodec.decode(f.readText()) }.getOrNull() }.sortedByDescending { it.t }

    /** 이 사고 다음에 난 사고의 시각(ms). 그 뒤에 찍은 사진은 이 사고 것이 아니다 */
    fun photoUntil(d: HandoffData) = cases.value.filter { it.t > d.t }.minOfOrNull { it.t }?.let { (it * 1000).toLong() } ?: Long.MAX_VALUE

    /** 옛 버전의 incident.json 하나를 새 저장소로 옮긴다(한 번만). 못 읽거나 못 옮기면 파일을 남겨 둔다 */
    private fun migrate(ctx: Context) {
        val old = oldCache(ctx).takeIf { it.exists() } ?: return
        val d = runCatching { HandoffCodec.decode(old.readText()) }.getOrNull() ?: return
        if (!save(ctx, d)) return
        val p = prefs(ctx)
        ClaimStore.setFlag(ctx, "closed", d.t, p.getBoolean("closed", false))
        ClaimStore.setFlag(ctx, "notAccident", d.t, p.getBoolean("notAccident", false))
        if (p.getString("alerted", null) == d.t.toString()) ClaimStore.setFlag(ctx, "alerted", d.t)
        p.edit().remove("closed").remove("notAccident").remove("alerted").apply()
        old.delete()
    }

    /** 앱을 처음 열 때: 지난번 받은 사고를 보여 줌 (현장에서 연결이 끊겨도 읽을 수 있게) */
    fun load(ctx: Context) {
        if (account(ctx) == null) return
        migrate(ctx)
        cases.value = readCases(ctx)
        // 서비스가 먼저 빈 서버 응답을 받아 둔 경우(상태는 있고 사고는 없음)에도 저장해 둔 사고를 띄운다
        if (state.value?.incident != null) return
        val d = cases.value.firstOrNull() ?: return
        apply(ctx, SyncState(-1, d, null, null, ClaimStore.flag(ctx, "closed", d.t), notAccident = ClaimStore.flag(ctx, "notAccident", d.t)))
        follow.value = Follow() // 단계는 서버의 첫 응답에서 정한다(휴대폰에서 옮겨 둔 단계도 이어짐)
    }

    fun login(ctx: Context, id: String) {
        prefs(ctx).edit().putString("account", id).apply()
        start(ctx)
    }

    fun logout(ctx: Context) {
        ctx.stopService(Intent(ctx, SyncService::class.java))
        // 맡겨 둔 사고가 있으면 맡기기도 푼다. 로그아웃하면 취소 화면을 다시 볼 수 없다
        val handed = cases.value.map { it.t }.filter { ClaimStore.flag(ctx, "handed", it) }
        client(ctx)?.let { c -> handed.forEach { t -> sends.execute { repeat(3) { if (c.handover(t, false)) return@execute; Thread.sleep(2000) } } } }
        // 계정을 지우기 전에 잡아 둔다. 서버에 못 닿아도 로그아웃은 막지 않음
        account(ctx)?.let { LinkClient(server(ctx), it) }?.let { link -> sends.execute { link.revoke() } }
        prefs(ctx).edit().remove("account").apply()
        ClaimStore.clear(ctx)
        forget(ctx)
        connected.value = false
    }

    /** 받아 둔 사고 전부와 그 사고로 생긴 파일(사진·기록서)을 이 휴대폰에서 지운다. 몇 번을 불러도 같다 */
    private fun forget(ctx: Context) {
        prefs(ctx).edit().remove("alerted").remove("closed").remove("notAccident").apply()
        ClaimStore.clearCase(ctx)
        oldCache(ctx).delete()
        casesDir(ctx).deleteRecursively()
        File(ctx.filesDir, "photos").deleteRecursively()
        ClaimStore.clearFiles(ctx)
        state.value = null; follow.value = Follow(); cases.value = emptyList()
        Notify.cancelAlert(ctx)
    }

    fun start(ctx: Context) {
        if (account(ctx) != null) ContextCompat.startForegroundService(ctx, Intent(ctx, SyncService::class.java))
    }

    /** 서버에서 새 상태가 왔을 때 (서비스 스레드). shortcut: 서버는 계정당 최신 사고 1건만 들고 있어 휴대폰이 꺼져 있던 사이의 옛 사고는 못 받음, 12/03 실제 서버 때 목록으로 바꾼다 */
    fun apply(ctx: Context, s: SyncState) {
        if (account(ctx) == null) return // 로그아웃 뒤 늦게 온 응답으로 사고가 다시 저장되지 않게
        if (s.wiped) { forget(ctx); return } // 차에서 철회함. 서버에 wiped 가 남아 있어 매 응답마다 온다
        val shown = Logic.keepIncident(state.value, s)
        state.value = shown
        follow.value = follow.value.onServer(shown)
        val d = s.incident ?: return
        val family = account(ctx) == Logic.FAMILY
        val saved = save(ctx, d)
        ClaimStore.setFlag(ctx, "closed", d.t, s.closed)
        ClaimStore.setFlag(ctx, "notAccident", d.t, s.notAccident)
        d.hash?.let { if (!ClaimStore.flag(ctx, "locked", d.t)) { ClaimStore.setFlag(ctx, "locked", d.t); ClaimStore.addLog(ctx, d.t, Logic.lockLog(it), d.t) } }
        if (saved && s.version >= 0 && !family) ackSeen(ctx, d.t, again = s.seen != d.t) // 서버가 재시작해 도착 표시를 잃었으면 다시 보낸다
        // 닫힌 사고여도 맡은 가족에게는 처음 도착했을 때 알린다. 달리는 중엔 울리지 않고(에어백은 바로), 실제로 알렸을 때만 표시
        if (s.notAccident || (s.closed && !family)) Notify.cancelAlert(ctx)
        else if (s.version >= 0 && !ClaimStore.flag(ctx, "alerted", d.t) && (d.stopped || d.severe) && Notify.alert(ctx, d)) ClaimStore.setFlag(ctx, "alerted", d.t)
    }

    /** 파일에 저장한 사고를 서버에 알린다(차의 "휴대폰 도착"). 사고당 한 번 */
    private fun ackSeen(ctx: Context, t: Double, again: Boolean = false) {
        if ((!again && ClaimStore.flag(ctx, "seen", t)) || !acking.add(t)) return
        val c = client(ctx) ?: run { acking.remove(t); return }
        sends.execute {
            try { repeat(3) { if (c.putSeen(t)) { ClaimStore.setFlag(ctx, "seen", t); return@execute }; Thread.sleep(2000) } }
            finally { acking.remove(t) }
        }
    }

    /** 차가 못 본 사고를 운전자가 직접 시작. 차 기록 없이 같은 카드를 쓴다. 안내 단계가 없어 바로 닫힌 상태로 */
    fun startManual(ctx: Context): Double {
        val t = System.currentTimeMillis() / 1000.0
        val d = HandoffData(t, CrashKind.UNKNOWN.label, false, null, false, null, listOf("운전자가 앱에서 직접 시작한 사고 (차량 기록 없음)"), emptyList(), 0, emptyList(), emptyList())
        ClaimStore.setFlag(ctx, "alerted", t) // 내가 시작한 사고를 알림으로 다시 알리지 않게
        val live = state.value?.let { it.incident != null && !it.closed } == true // 서버는 사고 1건만 들어서, 진행 중인 차 사고를 덮어쓰지 않게 휴대폰에만 둔다
        apply(ctx, SyncState(-1, d, null, null, closed = true))
        if (!live) client(ctx)?.let { c -> sends.execute { repeat(3) { if (c.putIncident(d) && c.close(t)) return@execute; Thread.sleep(2000) } } }
        return t
    }

    /** 휴대폰에서 단계를 옮김 → 서버에 "phone" 으로 알림. 실패하면 2초 뒤 다시(새로 옮겼으면 옛 것은 버림) */
    fun move(ctx: Context, to: Int) {
        val f = follow.value.move(to)
        follow.value = f
        val t = f.t ?: return
        val c = client(ctx) ?: return
        val gen = ++moves
        sends.execute {
            repeat(3) {
                if (gen != moves || c.putStep(t, to, "phone")) return@execute
                Thread.sleep(2000)
            }
        }
    }
}

object Notify {
    private const val ALERT = "crash_full" // 만든 채널의 중요도는 코드로 못 바꿔서 새 이름
    private const val OLD_ALERT = "crash_alert"
    private const val LINK = "car_link"
    private const val GROUP_ALERT = "crash"
    private const val GROUP_LINK = "link"
    const val ALERT_ID = 2
    const val LINK_ID = 1
    const val EXTRA_GUIDE = "open_guide"

    private fun channels(ctx: Context) = ctx.getSystemService(NotificationManager::class.java).run {
        deleteNotificationChannel(OLD_ALERT)
        createNotificationChannel(NotificationChannel(ALERT, "사고 알림", NotificationManager.IMPORTANCE_HIGH).apply {
            enableVibration(true); vibrationPattern = longArrayOf(0, 500, 200, 500, 200, 900)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        })
        createNotificationChannel(NotificationChannel(LINK, "차와 연결", NotificationManager.IMPORTANCE_MIN))
        this
    }

    private fun open(ctx: Context, guide: Boolean) = PendingIntent.getActivity(ctx, if (guide) 1 else 0,
        Intent(ctx, PhoneActivity::class.java).putExtra(EXTRA_GUIDE, guide).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    fun link(ctx: Context): Notification {
        channels(ctx)
        return Notification.Builder(ctx, LINK).setSmallIcon(R.drawable.ic_notify).setGroup(GROUP_LINK)
            .setContentTitle("사고 알림 대기 중").setContentText("사고가 나면 바로 알려 드릴게요")
            .setOngoing(true).setContentIntent(open(ctx, false)).build()
    }

    /** 실제로 알렸으면 true. 권한이 꺼져 있거나 채널을 껐으면 false(다음 응답에서 다시 시도) */
    fun alert(ctx: Context, d: HandoffData): Boolean {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false
        val nm = channels(ctx)
        if (!nm.areNotificationsEnabled() || nm.getNotificationChannel(ALERT)?.importance == NotificationManager.IMPORTANCE_NONE) return false
        val b = Notification.Builder(ctx, ALERT).setSmallIcon(R.drawable.ic_notify).setColor(0xFFD32016.toInt()).setGroup(GROUP_ALERT)
            .setContentTitle("사고가 감지됐어요").setContentText(alertLine(d))
            .setCategory(Notification.CATEGORY_ALARM).setVisibility(Notification.VISIBILITY_PUBLIC)
            .setAutoCancel(true).setContentIntent(open(ctx, true))
        // 늦게 받은 옛 사고(1시간 넘음)로 화면을 깨우지 않는다
        if (Logic.fresh(d)) b.setFullScreenIntent(PendingIntent.getActivity(ctx, 2, Intent(ctx, AlertActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT), true)
        nm.notify(ALERT_ID, b.build())
        return true
    }

    fun alertLine(d: HandoffData) = ClaimText.time(d.t, "HH:mm:ss") + " · " + (Logic.impactWord(d)?.let { "$it [센서 추정]" } ?: "충격")

    fun cancelAlert(ctx: Context) = ctx.getSystemService(NotificationManager::class.java).cancel(ALERT_ID)
}

/**
 * 로그인해 있는 동안 서버를 long-poll 해서 차의 사고 소식을 받는다.
 * shortcut: 시제품은 푸시 서비스(FCM) 없이 계속 도는 포그라운드 서비스로 받음 — 실제 서비스는 FCM 푸시로 바꿔야 함
 */
class SyncService : Service() {
    @Volatile private var running = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val n = Notify.link(this)
        if (Build.VERSION.SDK_INT >= 29) startForeground(Notify.LINK_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(Notify.LINK_ID, n)
        if (!running) { running = true; Thread(::loop, "sync-poll").apply { isDaemon = true }.start() }
        return START_STICKY
    }

    private fun loop() {
        var since = -1L
        var base: String? = null
        while (running) {
            val c = Sync.client(this) ?: break
            val url = Sync.server(this) + "|" + Sync.account(this)
            if (url != base) { base = url; since = -1 } // 서버 주소를 바꿨으면 처음부터
            val s = c.poll(since)
            if (!running) break // 기다리는 사이 로그아웃했으면 옛 계정 상태를 되살리지 않는다
            Sync.connected.value = s != null
            if (s == null) { Thread.sleep(2000); continue }
            since = s.version // 서버가 다시 켜져 version 이 작아져도 그대로 따라감
            Sync.apply(this, s)
        }
        stopSelf()
    }

    override fun onDestroy() { running = false; Sync.connected.value = false }

    // 안드로이드 15: dataSync 서비스는 하루 6시간 제한 → 시간이 다 되면 스스로 멈춤(앱을 다시 열면 재시작)
    override fun onTimeout(startId: Int, fgsType: Int) { running = false; stopSelf() }
}
