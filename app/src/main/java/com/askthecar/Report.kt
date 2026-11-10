package com.askthecar

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs

/**
 * 경위서 만들기 1단계: 로그에서 "타임스탬프 + 수치" 사실 목록을 코드로 뽑는다.
 * LLM이 실패하거나 검증에 걸리면 이 목록이 그대로 경위서가 된다.
 */
object FactTimeline {
    private const val STRAIGHT_DEG = 2.0

    /** epoch 초 → "HH:mm:ss.S" (경위서·영상 분석 추정·기록서가 같은 시각 표기를 쓴다. 기록서가 KST 라고 적으므로 기기 시간대를 따르지 않는다) */
    fun ts(t: Double, zone: ZoneId = ClaimText.KST): String =
        DateTimeFormatter.ofPattern("HH:mm:ss.S").withZone(zone).format(Instant.ofEpochMilli((t * 1000).toLong()))

    /** 충격 방향(앞 0°, 오른쪽 90°) → 말. 각도 숫자는 센서 추정이라 경위서에 넣지 않는다 */
    fun side(deg: Double) = ClaimText.side(deg)

    /** impactT·peakG·impactDeg = 감지기가 가진 사고 순간 값(impact.json). 에어백이 없어도 사고 자체가 경위서에 남게 */
    fun build(
        log: List<Sample>, zone: ZoneId = ClaimText.KST,
        impactT: Double? = null, peakG: Double = 0.0, impactDeg: Double? = null, manual: Boolean = false,
    ): List<String> {
        if (log.isEmpty()) return emptyList()
        fun ts(t: Double) = ts(t, zone)
        fun n(v: Double) = "%.1f".format(if (kotlin.math.abs(v) < 0.05) 0.0 else v) // "-0.0°" 방지
        fun dir(d: Double) = when { abs(d) < STRAIGHT_DEG -> 0; d < 0 -> -1; else -> 1 }

        val first = log.first()
        val facts = mutableListOf("${ts(first.t)} 기록 시작: 속도 ${n(first.speedKph)} km/h, 앞바퀴 각도 ${n(first.steerDeg)}°")
        var impactLine = impactT?.let { t ->
            if (manual) "${ts(t)} 운전자가 사고 기록을 시작함"
            else if (peakG >= 1.0) "${ts(t)} 충격 감지 (최대 ${n(peakG)}g${impactDeg?.let { ", ${side(it)} 방향 [추정]" } ?: ""})"
            else "${ts(t)} 사고 감지 (가속도 센서 충격 기록 없음)"
        }
        // 가속: SDK 에 가속 페달 값이 없어 차 속도로 판단 (1초 사이 4km/h 넘게 오르면 가속 중)
        fun accelerating(c: Sample) = (log.lastOrNull { it.t <= c.t - 1.0 } ?: first).let { c.speedKph - it.speedKph >= 4.0 }
        for ((p, c) in log.zipWithNext()) {
            if (impactLine != null && c.t >= impactT!!) { facts += impactLine; impactLine = null }
            if (accelerating(c) && !accelerating(p)) facts += "${ts(c.t)} 가속 시작 (속도 ${n(c.speedKph)} km/h)"
            val (pl, cl) = p.lamps.split(",").filter { it.isNotBlank() }.toSet() to c.lamps.split(",").filter { it.isNotBlank() }.toSet()
            (cl - pl).forEach { facts += "${ts(c.t)} 계기판 경고등 켜짐: $it" }
            (pl - cl).forEach { facts += "${ts(c.t)} 계기판 경고등 꺼짐: $it" }
            if (c.signal != p.signal) facts += when (c.signal) {
                "L" -> "${ts(c.t)} 좌측 방향지시등 켜짐"
                "R" -> "${ts(c.t)} 우측 방향지시등 켜짐"
                else -> "${ts(c.t)} 방향지시등 꺼짐"
            }
            if (c.brake != p.brake) facts +=
                "${ts(c.t)} 브레이크 페달 ${if (c.brake) "밟음" else "뗌"} (속도 ${n(c.speedKph)} km/h)"
            if (dir(c.steerDeg) != dir(p.steerDeg)) facts += when (dir(c.steerDeg)) {
                0 -> "${ts(c.t)} 조향 직진으로 복귀"
                -1 -> "${ts(c.t)} 좌측 조향 시작 (앞바퀴 각도 ${n(c.steerDeg)}°)"
                else -> "${ts(c.t)} 우측 조향 시작 (앞바퀴 각도 ${n(c.steerDeg)}°)"
            }
            if (c.cruise != p.cruise) facts += "${ts(c.t)} 크루즈 컨트롤 ${if (c.cruise) "켜짐" else "꺼짐"}"
            if (c.gear != p.gear) facts += "${ts(c.t)} 기어 ${c.gear}단으로 변경"
            if (c.airbag && !p.airbag) {
                val before = log.lastOrNull { it.t <= c.t - 1.0 } ?: first
                facts += "${ts(c.t)} 에어백 전개 신호 (1초 전 속도 ${n(before.speedKph)} km/h → 현재 ${n(c.speedKph)} km/h, 앞바퀴 각도 ${n(c.steerDeg)}°)"
            }
        }
        impactLine?.let { facts += it }
        val last = log.last()
        facts += "${ts(last.t)} 기록 종료: 속도 ${n(last.speedKph)} km/h"
        return facts
    }
}

/** 경위서 만들기 3단계: LLM 문장이 사실 목록을 벗어나지 않았는지 검사. */
object Validator {
    val FORBIDDEN = listOf("과실", "잘못", "책임", "가해", "피해자", "부주의", "과속", "위반", "탓", "급하게", "세게")
    private val NUMBER = Regex("""-?\d+(?:\.\d+)?""")
    private val TIME = Regex("""\d{2}:\d{2}:\d{2}\.\d""")

    /** 시각(HH:mm:ss.S)은 통째로 한 토큰, 나머지 숫자는 부호까지 (-8.0 과 8.0 은 다른 값) */
    private fun numbers(s: String): Set<String> =
        (TIME.findAll(s).map { it.value } + NUMBER.findAll(TIME.replace(s, " ")).map { it.value.toDouble().toString() }).toSet()

    /** 문제 목록을 돌려준다. 비어 있으면 통과. */
    fun problems(llmText: String, facts: List<String>): List<String> {
        val allowed = facts.flatMap { numbers(it) }.toSet()
        val badNumbers = numbers(llmText) - allowed
        val badWords = FORBIDDEN.filter { it in llmText }
        return badNumbers.map { "로그에 없는 숫자: $it" } + badWords.map { "판단 단어: $it" }
    }

    /** LLM 문장이 통과하면 그것을, 아니면 사실 목록 원문을 경위서로 쓴다. */
    fun choose(llmText: String?, facts: List<String>): String =
        if (!llmText.isNullOrBlank() && problems(llmText, facts).isEmpty()) llmText
        else facts.joinToString("\n")
}

/**
 * 사실 기록서 PDF (report.pdf). 양식·그리기는 shared ClaimPdf (휴대폰 앱과 같은 양식).
 * 경위서·영상 분석 추정·진술·위치·기록 지문(SHA-256)·EDR 공개 요청 안내를 담는다. 기록된 값과 운전자 본인 말만, 판정하지 않는다.
 */
object FactSheet {
    fun write(ctx: Context, r: CrashRecord, p: Persona, kind: CrashKind = CrashKind.UNKNOWN, chosen: Boolean = true): File {
        fun read(name: String) = File(r.dir, name).takeIf { it.exists() }?.readText()?.trim()
        // 충돌 시각 = impact.json, 없으면(옛 기록) 기록 끝에서 10초 전 (CrashWatcher 가 충돌 뒤 10초까지 저장)
        val t = runCatching { JSONObject(read("impact.json")!!).getDouble("t") }.getOrNull() ?: ((r.log.lastOrNull()?.t ?: 0.0) - 10)
        val mine = HandoffData(
            t, kind.label, chosen, r.impactDeg, CrashFlow.incident?.takeIf { it.t == t }?.highway ?: false, read("log.sha256"), r.facts,
            emptyList(), 0, emptyList(), emptyList(), r.log, r.seen, r.report, r.vision, read("statement.txt"),
        )
        val desk = (p.rentalCompany ?: p.insurer)?.let { "${it.name} ${it.phone}" }
        val owner = ClaimOwner(
            name = p.name.takeIf { p.id != Personas.guest.id }, phone = null, plate = null,
            insurer = listOfNotNull("렌터카".takeIf { p.rental }, desk, p.insurance.takeIf { it != "미등록" }).joinToString(" · ").ifEmpty { null },
            policyNo = null,
        )
        // 사진은 사진 칸에, 영상·녹음은 기록 지문 칸에만 들어간다
        val files = listOf("camera.jpg", CameraVideo.FILE, "statement.m4a").map { File(r.dir, it) }.filter { it.exists() }
        return ClaimPdf.write(ctx, File(r.dir, "report.pdf"), mine, photos = files, owner = owner)
    }
}
