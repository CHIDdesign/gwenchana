package com.askthecar

import android.content.Context
import android.content.Intent
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * 개인정보 동의 (Pleos 앱마켓 심사 User Privacy & Security).
 * 사고 기록·안내(주행·위치·진술·사진·휴대폰 연동) = 필수 묶음, 외부 AI 전송(제3자 제공) = 선택. 동의 화면의 [동의하고 시작] 버튼으로만 켜진다.
 * 끄면(철회) 사고 기록 전체를 바로 지운다 — 항목별로 나눠 지우지 않고 단순하게.
 */
object Consent {
    enum class Item(val title: String, val what: String, val why: String, val keep: String) {
        DRIVE("주행 기록·휴대폰 앱 연동", "속도·조향·차의 회전·브레이크·방향지시등·비상등·에어백·기어·가속도 등 차량 상태, 통화 중인지 여부(번호·기록은 읽지 않음). 사고가 나면 사실 기록·경위서·진술 글자·대처 단계·연락처와 재현용 충돌 전후 15초의 속도·조향·차의 회전·위치·브레이크·방향지시등·비상등·에어백 상태를 같은 계정의 휴대폰 앱으로 보내요(사진은 안 보내요). 두 차의 기록을 이을 수 있도록 사고 시각·위치를 서버에서 다른 사고와 비교해 '비슷한 시각·장소 사고 후보'를 보여 줘요. 후보에는 상대가 누구인지, 어디에서 난 사고인지는 나오지 않아요",
            "충격을 감지하고 사고 직전·직후 사실 기록을 만들기 위해. 차에서 내린 뒤에도 휴대폰으로 안내를 이어 가기 위해",
            "평소엔 최근 60초만 메모리에 두고 버려요. 사고가 감지됐을 때만 기기에 저장, 30일 뒤 자동 삭제('사고 아니에요'로 표시한 기록은 7일). 휴대폰 연동 서버는 메모리에만 두고 저장하지 않아요"),
        LOCATION("위치", "차의 위도·경도", "사고 기록과 기록서(PDF)에 사고 장소를 남기기 위해",
            "사고 기록과 함께 30일 뒤 자동 삭제"),
        VOICE("운전자 진술 음성", "진술 버튼을 눌렀을 때의 음성(글자 변환 또는 녹음)", "운전자가 직접 말한 사고 경위를 기록에 남기기 위해",
            "사고 기록과 함께 30일 뒤 자동 삭제"),
        CAMERA("차 카메라 사진", "사고 화면이 뜰 때 차 카메라 사진 1장", "사고 순간 주변 상황을 기록하고 차·사람을 찾기 위해",
            "사고 기록과 함께 30일 뒤 자동 삭제"),
        CLOUD("외부 AI 전송 (제3자 제공)", "경위서 사실 문장, 차 카메라 사진", "읽기 쉬운 문장으로 다듬고 장면을 설명하기 위해. 앱 중계 서버를 거쳐 Anthropic(Claude, 미국)으로 보내요",
            "앱은 따로 보관하지 않아요. 받는 쪽 보관 정책은 Anthropic 정책을 따름"),
    }

    /** 화면에 보이는 동의 묶음. 자세히 보기에서 항목별 전문을 보여 준다 */
    enum class Bundle(val title: String, val summary: String, val required: Boolean, val items: List<Item>, val refuse: String) {
        RECORD("사고 기록·안내", "주행 기록, 위치, 차 카메라 사진, 진술 음성, 휴대폰 앱 연동", true,
            listOf(Item.DRIVE, Item.LOCATION, Item.CAMERA, Item.VOICE), "동의하지 않으면 사고를 감지하거나 기록할 수 없어 앱을 쓸 수 없어요."),
        AI("외부 AI 전송", "경위서 문장과 사진을 AI로 보내 다듬기 (제3자 제공)", false,
            listOf(Item.CLOUD), "동의하지 않아도 앱을 쓸 수 있어요. 다듬지 않은 사실 목록만 보여 줘요.");
        fun allowed() = items.all { allowed(it) }
    }

    fun set(ctx: Context, b: Bundle, on: Boolean) = b.items.forEach { set(ctx, it, on) }

    private val on = java.util.concurrent.ConcurrentHashMap<Item, Boolean>() // 센서·소켓 스레드에서도 읽음
    private const val DAY = 24 * 60 * 60 * 1000L

    fun init(ctx: Context) {
        val p = prefs(ctx)
        Item.entries.forEach { on[it] = p.getBoolean(it.name, false) }
        CarSync.init(ctx)
        CarSync.flushPending()
    }

    fun allowed(item: Item): Boolean = on[item] ?: false

    fun set(ctx: Context, item: Item, on: Boolean) {
        val was = this.on[item] == true
        this.on[item] = on
        prefs(ctx).edit().putBoolean(item.name, on).apply()
        if (on || !was) return // 처음 동의 때 안 고른 선택 항목은 철회가 아니다(기록·서버 값까지 지우면 안 됨)
        deleteRecords(ctx)
        if (item == Item.DRIVE) ctx.stopService(Intent(ctx, CarService::class.java))
    }

    /** 사고 기록 전부 삭제 (철회·"모두 지우기") */
    fun deleteRecords(ctx: Context) {
        CrashFlow.dir = null // 먼저 비운다: 대기 중인 사고의 저장(finish)·종류 기록이 폴더를 다시 만들지 않게
        File(ctx.filesDir, "crashes").deleteRecursively()
        ctx.cacheDir.listFiles()?.filter { it.name.startsWith("camera_") }?.forEach { it.delete() }
        CrashFlow.record = null
        CrashFlow.setIncident { null }
        CrashFlow.closed++ // 열린 사고 화면을 닫음. 화면이 없으면 아무 일도 없다
        ctx.getSystemService(android.app.NotificationManager::class.java).cancel(2) // "사고 기록은 저장되어 있어요" 알림
        CarSync.wipe()
    }

    /** 보관 기간 지난 사고 폴더 삭제. CarService 시작 때 */
    fun cleanup(ctx: Context, now: Long = System.currentTimeMillis()) {
        File(ctx.filesDir, "crashes").listFiles()?.forEach { d ->
            val short = File(d, "false_alarm").exists() || File(d, "practice").exists()
            if (expired(d.name, d.lastModified(), short, now)) d.deleteRecursively()
        }
        ctx.cacheDir.listFiles()?.filter { it.name.startsWith("camera_") }?.forEach { it.delete() } // 저장 전에 앱이 꺼져 남은 사진
    }

    /** 폴더 이름(yyyyMMdd_HHmmss) 시각 기준, 이름이 다르면 수정 시각. 30일, 오탐·연습(short)은 7일 */
    fun expired(name: String, lastModified: Long, short: Boolean, now: Long): Boolean {
        val t = runCatching { SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).apply { isLenient = false }.parse(name)!!.time }.getOrDefault(lastModified)
        return now - t > (if (short) 7 else 30) * DAY
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("consent", Context.MODE_PRIVATE)
}
