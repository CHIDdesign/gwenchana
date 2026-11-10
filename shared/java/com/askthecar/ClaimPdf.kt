package com.askthecar

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.media.ExifInterface
import androidx.core.content.res.ResourcesCompat
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** 기록서에 넣는 운전자 본인 정보. 모두 선택: null·빈칸이면 PDF 에 손으로 적을 빈 줄("직접 적어 주세요") */
data class ClaimOwner(val name: String?, val phone: String?, val plate: String?, val insurer: String?, val policyNo: String?)

/** 보내기 전에 운전자가 채운 값. 없으면 기록서에 손으로 적을 칸이 그대로 남는다 */
data class ClaimExtra(val otherPlate: String?, val injured: String, val noPlace: Boolean = false, val noStatement: Boolean = false, val noContact: Boolean = false, val noPolicy: Boolean = false, val recipient: String? = null)

/**
 * 기록서·문자·이메일·전화 요약이 같이 쓰는 글자 (화면·PDF 와 떨어진 계산 → 단위 테스트 대상).
 * 차가 기록한 값과 운전자 입력만 쓴다. 판단 단어(과실·잘못·책임 등) 금지.
 */
object ClaimText {
    val KST: ZoneId = ZoneId.of("Asia/Seoul")

    fun time(t: Double, pattern: String, zone: ZoneId = KST): String =
        DateTimeFormatter.ofPattern(pattern, Locale.KOREAN).withZone(zone).format(Instant.ofEpochMilli((t * 1000).toLong()))

    /** 충격 방향(앞 0°, 오른쪽 90°) → 말. 각도 숫자는 센서 추정이라 글에 넣지 않는다 */
    fun side(deg: Double) = listOf("앞", "오른쪽 앞", "오른쪽", "오른쪽 뒤", "뒤", "왼쪽 뒤", "왼쪽", "왼쪽 앞")[(((deg + 22.5) % 360 + 360) % 360 / 45).toInt()]

    fun recordNo(d: HandoffData) = "ATC-" + time(d.t, "yyyyMMdd-HHmmss")

    /** t 에 가장 가까운 기록(tol 초 안), 없으면 null */
    fun near(samples: List<Sample>, t: Double, tol: Double = 0.6): Sample? =
        samples.minByOrNull { abs(it.t - t) }?.takeIf { abs(it.t - t) <= tol }

    fun speedAt(d: HandoffData, dt: Double = 0.0) = near(d.samples, d.t + dt)?.speedKph
    fun kph(v: Double) = "%.0f km/h".format(v)

    /** 충돌 순간에 가장 가까운 GPS 기록 */
    fun place(d: HandoffData) = d.samples.filter { it.lat != null && it.lon != null }.minByOrNull { abs(it.t - d.t) }
        ?.let { "위도 %.5f, 경도 %.5f".format(Locale.US, it.lat, it.lon) }

    fun impact(d: HandoffData) = d.impactDeg?.let { "${side(it)} [센서 추정]" }

    /** 운전자가 휴대폰에서 직접 시작한 사고: 차가 보낸 안내 단계가 없다 */
    fun manual(d: HandoffData) = d.steps.isEmpty() && d.samples.isEmpty() // 연결된 상대 차 기록도 단계가 없어서 샘플까지 본다

    fun kind(d: HandoffData) = d.kind + when {
        d.kind == CrashKind.UNKNOWN.label -> ""
        d.kindChosen -> " (운전자 선택)"
        else -> " (기록으로 추정)"
    }

    fun airbag(d: HandoffData): String {
        if (manual(d)) return "차량 기록 없음"
        if (d.samples.isEmpty() || d.hash == null) return "기록 저장 전" // 저장 전 샘플은 충돌 순간까지만이라 그 뒤 신호를 모른다
        if (!d.controlsRecorded) return "기록 없음"
        val t =d.samples.firstOrNull { it.airbag }?.t ?: return "전개 신호 없음"
        return "전개 신호 있음 (${time(t, "HH:mm:ss.S")})"
    }

    fun speedLine(d: HandoffData) = speedAt(d)?.let { v -> kph(v) + (speedAt(d, -1.0)?.let { " (1초 전 ${kph(it)})" } ?: "") } ?: "기록 없음"

    fun steer(deg: Double) = when { abs(deg) < 2.0 -> "직진"; deg < 0 -> "왼쪽 %.0f°".format(-deg); else -> "오른쪽 %.0f°".format(deg) }
    fun signal(s: String) = when (s) { "L" -> "왼쪽"; "R" -> "오른쪽"; else -> "꺼짐" }

    private fun String?.or(alt: String) = this?.trim()?.takeIf { it.isNotEmpty() } ?: alt

    /** 전화로 접수할 때 그대로 읽는 다섯 줄 */
    fun callLines(d: HandoffData, owner: ClaimOwner?): List<String> = listOf(
        "언제: ${time(d.t, "M월 d일 HH:mm")}",
        "어디: ${place(d) ?: "위치 기록 없음"}",
        "무엇과: ${kind(d)}${impact(d)?.let { ", 충격 $it" } ?: ""}",
        "속도: 충돌 순간 ${speedAt(d)?.let(::kph) ?: "기록 없음"}, 에어백 ${airbag(d)}",
        "내 차: ${owner?.plate.or("차량번호 미입력")}, 증권번호 ${owner?.policyNo.or("미입력")}",
    )

    /** 문자로 접수할 때 붙여 넣는 한 문단 */
    fun smsText(d: HandoffData, owner: ClaimOwner?, noPlace: Boolean = false): String = buildString {
        append("[사고 접수] ${time(d.t, "M월 d일 HH:mm")}, ${if (noPlace) "" else (place(d) ?: "위치 기록 없음") + "에서 "}사고가 있었습니다. ")
        append("부딪힌 대상 ${kind(d)}, 충격 방향 ${impact(d) ?: "기록 없음"}, 충돌 순간 속도 ${speedAt(d)?.let(::kph) ?: "기록 없음"}(차량 기록). ")
        owner?.name?.takeIf { it.isNotBlank() }?.let { append("운전자 ${it.trim()}. ") }
        owner?.plate?.takeIf { it.isNotBlank() }?.let { append("차량번호 ${it.trim()}. ") }
        owner?.policyNo?.takeIf { it.isNotBlank() }?.let { append("증권번호 ${it.trim()}. ") }
        append("차량 기록서(PDF)는 따로 보내 드리겠습니다. 기록 번호 ${recordNo(d)}")
    }

    fun emailSubject(d: HandoffData, owner: ClaimOwner?) =
        "[사고 기록서] ${time(d.t, "yyyy-MM-dd HH:mm")}" + (owner?.plate?.takeIf { it.isNotBlank() }?.let { " ${it.trim()}" } ?: "") + " ${recordNo(d)}"

    fun emailBody(d: HandoffData, owner: ClaimOwner?, photoCount: Int) = buildString {
        append("안녕하세요. 사고 기록서를 보내 드립니다.\n\n")
        callLines(d, owner).forEach { append(it).append('\n') }
        append("\n첨부: 사고 사실 기록서(PDF)")
        if (photoCount > 0) append(", 현장 사진 ${photoCount}장")
        append("\n이 기록서는 차량이 기록한 값을 정리한 첨부 자료이며, 사고의 원인이나 비율을 판단하지 않습니다.")
    }

    /** 두 차 기록 한 줄 = 같은 시각. sec 는 내 차 충돌 시각 기준 초, 두 기록 모두 각 기기의 epoch 초를 그대로 맞춘다 */
    data class Row(val sec: Int, val mine: Sample?, val other: Sample?)

    fun sideBySide(mine: HandoffData, other: HandoffData, from: Int = -5, to: Int = 3): List<Row> =
        (from..to).map { s -> Row(s, near(mine.samples, mine.t + s, 0.5), near(other.samples, mine.t + s, 0.5)) }

    /** 사진 시각을 충돌 기준으로: "+12초", "+3분 5초", "-4초" */
    fun rel(dt: Double): String {
        val s = Math.round(dt).toInt()
        val sign = if (s < 0) "-" else "+"
        val a = abs(s)
        return sign + if (a < 60) "${a}초" else "${a / 60}분 ${a % 60}초"
    }
}

/**
 * 보험 담당자에게 붙여 주는 "사고 사실 기록서" PDF (A4). 차 앱(report.pdf)·휴대폰 앱이 같은 양식을 쓴다.
 * 보험사 공통 양식·공개 API 는 없어서(docs/reports/보험사 제출 서류 조사 (2026-11-07).md) 양식을 대신하지 않는 첨부 자료로 만든다.
 * 기록된 값과 운전자 본인 말만 담고 판단하지 않는다.
 */
object ClaimPdf {
    /**
     * 회사 표시(현대·Pleos 로고) 자리. 사용 허락 전에는 비워 둔다(조사 보고서 6절: 데이터 보증으로 오인될 수 있음).
     * 허락을 받으면 여기서 표지 오른쪽 위 칸(RectF) 안에 그린다.
     */
    val BRAND_MARK: ((Canvas, RectF) -> Unit)? = null

    /**
     * mine = 이 기기 쪽 사고 기록, other = 두 차가 서로 동의해 연결된 경우 상대 차 기록.
     * photos = 넣을 파일. 그림(jpg·png)은 사진 칸에, 모든 파일은 기록 지문 칸에 들어간다(영상·녹음도 지문만).
     * 무거운 일(사진 읽기·지문 계산) → IO 스레드에서 부를 것.
     */
    fun write(ctx: Context, out: File, mine: HandoffData, other: HandoffData? = null, photos: List<File> = emptyList(), owner: ClaimOwner? = null, extra: ClaimExtra? = null): File {
        out.parentFile?.mkdirs()
        val s = Sheet(ctx, ClaimText.recordNo(mine))
        try {
            if (ClaimText.manual(mine)) s.footer = "운전자가 직접 시작한 사고 · 차량 기록 없음 · 판단이 아닌 기록입니다"
            s.cover(System.currentTimeMillis() / 1000.0, mine, owner, extra?.recipient)
            s.summary(mine, extra)
            // 시안 6A: 첫 쪽이 곧 내용 — 속도 그래프가 본인 정보 앞에
            s.section("속도 기록", "충돌 전후 차량 속도 신호", s.chartNeed(mine, other))
            s.chart(mine, other)
            s.section("운전자·차량", "본인 정보")
            s.fields(listOf("성명" to owner?.name, "연락처" to (if (extra?.noContact == true) "보내는 사람이 제외함" else owner?.phone), "차량번호" to owner?.plate, "차종" to null,
                "보험사" to owner?.insurer, "증권번호" to (if (extra?.noPolicy == true) "보내는 사람이 제외함" else owner?.policyNo)))
            s.section("시간순 기록", "차량 기록 그대로")
            s.timeline(mine.facts)
            val report = mine.report?.trim()
            if (!report.isNullOrEmpty() && report != mine.facts.joinToString("\n").trim()) {
                s.section("경위 설명문", "기록을 문장으로 정리, 숫자는 기록과 대조함")
                s.text(report)
            }
            if (mine.vision.isNotEmpty()) {
                s.section("[영상 분석 추정]", "사람이 사진에서 직접 확인해야 함")
                mine.vision.forEach { s.text(it, s.paint(9.5f, C.ORANGE)) }
            }
            other?.let { s.twoCars(mine, it) }
            s.section("현장 사진", "번호·찍은 시각", s.photosNeed(photos))
            s.photos(mine, photos)
            s.statement(mine, photos, extra?.noStatement == true)
            s.section("상대방 정보", "현장에서 받아 적어 두세요", next = 36f)
            other?.let { s.text("상대 차에도 이 앱 기록이 있어요 (기록 번호 ${ClaimText.recordNo(it)}, 두 운전자 동의로 연결).", s.small) }
            s.fields(listOf("성명" to null, "연락처" to null, "차량번호" to extra?.otherPlate, "보험사" to null))
            s.section("목격자·신고·부상", next = 36f)
            s.fields(listOf("목격자 성명" to null, "목격자 연락처" to null))
            s.checks("경찰 신고 (112)", listOf("했음", "안 함"), "접수 번호")
            s.checks("119 출동", listOf("왔음", "안 옴"))
            s.checks("다친 사람", listOf("없음", "있음") + listOfNotNull("확인 못 함".takeIf { extra != null }), "누가·어디", extra?.injured)
            s.fingerprint(mine, other, photos)
            s.section("참고")
            s.text("이 기록은 차량 사고기록장치(EDR)를 대신하지 않는 보조 사실 기록입니다.", s.small)
            s.text("EDR이 있는 차는 제조사에 기록 공개를 요청할 수 있습니다(자동차관리법 제29조의3).", s.small)
            s.text("시각은 기록한 기기의 시계 기준 한국 시간입니다. [센서 추정]·[영상 분석 추정]은 앱이 계산한 추정이라 사람이 확인해야 합니다.", s.small)
            s.close()
            out.outputStream().use(s.doc::writeTo)
        } finally {
            s.doc.close()
        }
        return out
    }
}

/** 색: shared/Theme.kt 라이트 값 (종이는 늘 흰 바탕) */
private object C {
    private fun ink(a: Float) = ((a * 255).toInt() shl 24) or 0x131417
    val INK = ink(1f)
    val SEC = ink(0.84f)
    val TER = ink(0.64f)
    val QUAT = ink(0.32f)
    val DIV = ink(0.10f)
    val LINE = ink(0.22f)
    val BG50 = 0xFFF7F8FA.toInt()
    val BG100 = 0xFFEDEEF2.toInt()
    val WHITE = 0xFFFFFFFF.toInt()
    fun white(a: Float) = ((a * 255).toInt() shl 24) or 0xFFFFFF
    val BLUE = 0xFF0064FF.toInt() // positive
    val RED = 0xFFFE3D16.toInt() // negative
    val RED_TINT = 0x1FFE3D16
    val ORANGE = 0xFFA85200.toInt() // textOrange
    val ORANGE_FILL = 0xFFFF8A00.toInt()
}

private const val PW = 595
private const val PH = 842
private const val M = 40f
private const val CW = PW - 2 * M
private const val BOTTOM = PH - 52f
private const val FOOTER = "Pleos Connect 차량 데이터로 자동 작성 · 판단이 아닌 기록입니다"

/** 폭에 맞게 줄바꿈. 한글은 글자 단위로 잘려도 되지만 띄어쓰기가 가까우면 거기서 자른다 */
private fun wrap(text: String, p: Paint, width: Float): List<String> = text.split('\n').flatMap { para ->
    if (para.isBlank()) listOf("") else buildList {
        var rest = para.trimEnd()
        while (rest.isNotEmpty()) {
            var n = p.breakText(rest, true, width, null).coerceAtLeast(1)
            if (n < rest.length) rest.lastIndexOf(' ', n).let { sp -> if (sp > n / 2) n = sp + 1 }
            add(rest.take(n).trimEnd())
            rest = rest.drop(n).trimStart()
        }
    }
}

private fun ellipsize(s: String, p: Paint, w: Float) =
    if (p.measureText(s) <= w) s else s.take(p.breakText(s, true, w - p.measureText("…"), null)) + "…"

/** 쪽 넘김을 아는 그리기 도구. y = 지금 쓰는 위치(위에서부터 pt) */
private class Sheet(ctx: Context, private val recordNo: String) {
    var footer = FOOTER
    val doc = PdfDocument()
    private var page: PdfDocument.Page? = null
    private lateinit var c: Canvas
    private var y = 0f
    private var n = 0
    private val font: Typeface? = runCatching { ResourcesCompat.getFont(ctx, R.font.asta_sans) }.getOrNull()

    fun paint(size: Float, color: Int = C.INK, weight: Int = 400, mono: Boolean = false, align: Paint.Align = Paint.Align.LEFT) =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size; this.color = color; textAlign = align
            typeface = if (mono) Typeface.MONOSPACE else font ?: Typeface.DEFAULT
            // Asta Sans 는 가변 글꼴(굵기 300~800). 안 되면 가짜 굵게
            if (mono || font == null || !setFontVariationSettings("'wght' $weight")) isFakeBoldText = weight >= 600
        }

    private fun stroke(color: Int, w: Float = 0.6f, dash: Boolean = false) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = w; this.color = color
        if (dash) pathEffect = DashPathEffect(floatArrayOf(3f, 2.5f), 0f)
    }

    private fun fill(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }

    val body = paint(9.5f)
    val small = paint(8.5f, C.SEC)
    private val lab = paint(7.5f, C.TER)
    private val tiny = paint(7f, C.TER)
    private val tinyR = paint(7f, C.TER, align = Paint.Align.RIGHT)
    private val tinyC = paint(7f, C.TER, align = Paint.Align.CENTER)
    private val h2 = paint(12.5f, weight = 700)
    private val mono = paint(7.2f, C.SEC, mono = true)
    private val blank = paint(9f, C.QUAT)

    // ── 쪽 ──
    private fun newPage() {
        close()
        n++
        page = doc.startPage(PdfDocument.PageInfo.Builder(PW, PH, n).create())
        c = page!!.canvas
        y = M
        if (n > 1) {
            c.drawText("괜차나  ·  사고 사실 기록서", M, M, tiny)
            c.drawText(recordNo, M + CW, M, tinyR)
            c.drawLine(M, M + 6f, M + CW, M + 6f, stroke(C.DIV))
            y = M + 20f
        }
    }

    fun close() {
        page?.let {
            c.drawLine(M, PH - 40f, M + CW, PH - 40f, stroke(C.DIV))
            c.drawText(footer, M, PH - 28f, tiny)
            c.drawText("${n}쪽", M + CW, PH - 28f, tinyR)
            doc.finishPage(it)
        }
        page = null
    }

    private fun ensure(h: Float) { if (page == null || y + h > BOTTOM) newPage() }

    // ── 글 ──
    fun text(s: String, p: Paint = body, x: Float = M, w: Float = CW) {
        val lead = p.textSize * 1.5f
        for (l in wrap(s, p, w)) { ensure(lead); c.drawText(l, x, y + p.textSize, p); y += lead }
        y += 2f
    }

    /** next = 제목 바로 뒤 덩어리의 높이. 제목(38pt)만 쪽 끝에 홀로 남지 않게 함께 들어갈 자리를 본다 */
    fun section(title: String, sub: String? = null, next: Float = 32f) {
        ensure(38f + next)
        y += 14f
        c.drawRect(M, y + 1f, M + 3f, y + 14f, fill(C.INK))
        c.drawText(title, M + 10f, y + 12.5f, h2)
        sub?.let { c.drawText(it, M + 10f + h2.measureText(title) + 8f, y + 12.5f, lab) }
        y += 24f
    }

    /** 이름표 + 값 한 칸. 값이 없으면 손으로 적을 밑줄 */
    fun fields(items: List<Pair<String, String?>>, cols: Int = 2) {
        val gap = 18f
        val w = (CW - gap * (cols - 1)) / cols
        items.chunked(cols).forEach { row ->
            ensure(36f)
            row.forEachIndexed { i, (label, value) ->
                val x = M + i * (w + gap)
                val v = value?.trim()?.takeIf { it.isNotEmpty() }
                c.drawText(label, x, y + 8f, lab)
                c.drawText(v?.let { ellipsize(it, body, w) } ?: "직접 적어 주세요", x, y + 22f, if (v != null) body else blank)
                c.drawLine(x, y + 27f, x + w, y + 27f, stroke(C.LINE))
            }
            y += 36f
        }
    }

    fun checks(label: String, options: List<String>, tail: String? = null, picked: String? = null) {
        ensure(24f)
        c.drawText(label, M, y + 12f, body)
        var x = M + 110f
        options.forEach {
            c.drawRect(x, y + 3.5f, x + 9f, y + 12.5f, stroke(C.INK, 0.8f))
            if (it == picked) c.drawRect(x + 2f, y + 5.5f, x + 7f, y + 10.5f, paint(9f))
            c.drawText(it, x + 13f, y + 12f, body)
            x += 13f + body.measureText(it) + 18f
        }
        tail?.let {
            c.drawText(it, x, y + 12f, lab)
            c.drawLine(x + lab.measureText(it) + 4f, y + 13f, M + CW, y + 13f, stroke(C.LINE))
        }
        y += 24f
    }

    private fun blankLines(count: Int) = repeat(count) {
        ensure(22f); y += 20f
        c.drawLine(M, y, M + CW, y, stroke(C.LINE))
    }

    // ── 표지 ──
    /** 시안 6A: 표지 띠·영어 문구 없이 흰 바탕에 날짜·시각 굵게, 장소·차 번호 한 줄. 기록 번호·작성 시각은 오른쪽 위 작게 */
    fun cover(generated: Double, d: HandoffData, owner: ClaimOwner?, recipient: String? = null) {
        newPage()
        val k = paint(7.5f, C.TER, align = Paint.Align.RIGHT)
        val v = paint(9f, C.INK, 500, align = Paint.Align.RIGHT)
        c.drawText("기록 번호", M + CW, 40f, k); c.drawText(recordNo, M + CW, 52f, v)
        c.drawText("작성 시각", M + CW, 68f, k); c.drawText(ClaimText.time(generated, "yyyy-MM-dd HH:mm") + " KST", M + CW, 80f, v)
        c.drawText(recipient?.let { "사고 사실 기록서 · 받는 사람 $it" } ?: "사고 사실 기록서 · 보험 접수 첨부 자료", M, 44f, paint(9f, C.TER))
        c.drawText(ClaimText.time(d.t, "yyyy.MM.dd HH:mm:ss"), M, 76f, paint(22f, C.INK, 800))
        val line = listOfNotNull(ClaimText.place(d)?.let { "$it (GPS)" }, owner?.plate?.takeIf { it.isNotBlank() }?.let { "차량번호 $it" }).joinToString("  ·  ")
        if (line.isNotEmpty()) c.drawText(line, M, 96f, paint(10.5f, C.INK))
        y = 118f
        text("이 문서는 차량이 기록한 값을 시간순으로 정리한 첨부 자료입니다. 보험사 양식을 대신하지 않으며, 사고의 원인이나 비율을 판단하지 않습니다. " +
            "시각은 그 값이 기록된 시각(한국 시간)입니다.", small)
    }

    // ── 사고 요약 카드 + 충격 방향 그림 ──
    fun summary(d: HandoffData, extra: ClaimExtra? = null) {
        val rows = listOf(
            (if (ClaimText.manual(d)) "앱 시작 시각" else "일시") to ClaimText.time(d.t, "yyyy년 M월 d일 (E) HH:mm:ss") + " KST",
            "장소" to (ClaimText.place(d)?.let { "$it (GPS)" } ?: if (extra?.noPlace == true) "보내는 사람이 제외함" else "위치 기록 없음"),
            "부딪힌 대상" to ClaimText.kind(d),
            "충격 방향" to (ClaimText.impact(d) ?: "센서 충격 기록 없음"),
            "충돌 순간 속도" to ClaimText.speedLine(d),
            "에어백" to ClaimText.airbag(d),
        )
        val h = 20f + rows.size * 22f + 8f
        ensure(h + 12f)
        y += 8f
        val top = y
        c.drawRoundRect(RectF(M, top, M + CW, top + h), 6f, 6f, fill(C.BG50))
        val valueW = CW - 96f - 170f
        rows.forEachIndexed { i, (k, v) ->
            val ry = top + 20f + i * 22f
            c.drawText(k, M + 14f, ry + 8f, lab)
            c.drawText(ellipsize(v, body, valueW), M + 96f, ry + 8f, body)
        }
        c.drawLine(M + CW - 160f, top + 14f, M + CW - 160f, top + h - 14f, stroke(C.DIV))
        carDiagram(M + CW - 80f, top + h / 2 - 8f, d.impactDeg)
        y = top + h + 4f
    }

    /** 위에서 본 내 차(앞이 위) + 빨간 화살표 = 충격이 온 방향 */
    private fun carDiagram(cx: Float, cy: Float, deg: Double?) {
        val bw = 34f; val bh = 66f
        for (sx in listOf(-1, 1)) for (sy in listOf(-1, 1)) {
            val wx = cx + sx * bw / 2; val wy = cy + sy * bh * 0.3f
            c.drawRoundRect(RectF(wx - 3f, wy - 7f, wx + 3f, wy + 7f), 1.5f, 1.5f, fill(C.INK))
        }
        val bodyR = RectF(cx - bw / 2, cy - bh / 2, cx + bw / 2, cy + bh / 2)
        c.drawRoundRect(bodyR, 9f, 9f, fill(C.WHITE))
        c.drawRoundRect(bodyR, 9f, 9f, stroke(C.INK, 1.2f))
        val glass = Path().apply {
            moveTo(cx - bw / 2 + 5f, cy - bh / 2 + 17f); lineTo(cx + bw / 2 - 5f, cy - bh / 2 + 17f)
            lineTo(cx + bw / 2 - 8f, cy - bh / 2 + 27f); lineTo(cx - bw / 2 + 8f, cy - bh / 2 + 27f); close()
        }
        c.drawPath(glass, fill(C.BG100)); c.drawPath(glass, stroke(C.INK, 0.8f))
        c.drawRect(cx - bw / 2 + 8f, cy + bh / 2 - 13f, cx + bw / 2 - 8f, cy + bh / 2 - 8f, fill(C.BG100))
        c.drawText("앞", cx, cy - bh / 2 + 11f, tinyC)
        if (deg == null) { c.drawText("충격 방향 기록 없음", cx, cy + bh / 2 + 22f, tinyC); return }
        val a = Math.toRadians(deg)
        val dx = sin(a).toFloat(); val dy = -cos(a).toFloat()
        val edge = 1f / sqrt((dx / (bw / 2)) * (dx / (bw / 2)) + (dy / (bh / 2)) * (dy / (bh / 2))) // 차 테두리(타원 근사)까지
        val tip = edge + 2f; val tail = edge + 26f
        val red = stroke(C.RED, 2.4f)
        c.drawLine(cx + dx * tail, cy + dy * tail, cx + dx * (tip + 6f), cy + dy * (tip + 6f), red)
        val head = Path().apply {
            moveTo(cx + dx * tip, cy + dy * tip)
            lineTo(cx + dx * (tip + 8f) - dy * 5f, cy + dy * (tip + 8f) + dx * 5f)
            lineTo(cx + dx * (tip + 8f) + dy * 5f, cy + dy * (tip + 8f) - dx * 5f); close()
        }
        c.drawPath(head, fill(C.RED))
        c.drawText("빨간 화살표 = 충격 방향 [센서 추정]", cx, cy + bh / 2 + 36f, tinyC)
    }

    // ── 속도 그래프 (Canvas 로 직접) ──
    private val chartH = 130f
    private val stripH = 10f

    fun chartNeed(mine: HandoffData, other: HandoffData?): Float = if (mine.samples.size < 2) 32f
        else 16f + chartH + 22f + listOfNotNull(mine, other).count { it.controlsRecorded } * 2 * (stripH + 5f) + 18f

    fun chart(mine: HandoffData, other: HandoffData?) {
        if (mine.samples.size < 2) {
            text(if (ClaimText.manual(mine)) "차가 기록하지 못한 사고라 그래프가 없어요." else "기록 저장 전이라 그래프가 없어요. 사고 후 약 10초 뒤 기록이 저장되면 기록서를 다시 만들어 주세요.", small); return
        }
        val all = mine.samples + other?.samples.orEmpty()
        val rel = all.map { it.t - mine.t }
        val lo = max(-10.0, floor(rel.min()))
        val hi = min(5.0, ceil(rel.max())).let { if (it - lo < 2) lo + 2 else it }
        fun inWin(s: Sample) = s.t - mine.t in lo..hi
        val top0 = all.filter(::inWin).maxOfOrNull { it.speedKph } ?: 0.0
        val step = if (top0 <= 40) 10.0 else 20.0
        val vmax = max(step * 2, ceil(top0 / step) * step)
        val cars = listOfNotNull(mine to "내 차", other?.let { it to "상대 차" })
        ensure(chartNeed(mine, other))
        val left = M + 70f; val right = M + CW - 6f
        val top = y + 12f; val bot = top + chartH
        fun x(r: Double) = left + ((r - lo) / (hi - lo)).toFloat() * (right - left)
        fun yv(v: Double) = bot - (v / vmax).toFloat() * chartH
        c.drawText("km/h", left - 6f, y + 6f, tinyR)
        var v = 0.0
        while (v <= vmax + 1e-6) {
            c.drawLine(left, yv(v), right, yv(v), stroke(if (v == 0.0) C.LINE else C.DIV))
            c.drawText("%.0f".format(v), left - 6f, yv(v) + 2.5f, tinyR)
            v += step
        }
        val tick = if (hi - lo <= 8) 1 else 2
        for (s in ceil(lo).toInt()..floor(hi).toInt()) if (s % tick == 0)
            c.drawText(if (s == 0) "충돌" else if (s > 0) "+${s}초" else "${s}초", x(s.toDouble()), bot + 11f, tinyC)
        if (0.0 in lo..hi) c.drawLine(x(0.0), top - 4f, x(0.0), bot, stroke(C.RED, 1f, dash = true))
        fun line(d: HandoffData) = Path().apply {
            d.samples.filter(::inWin).sortedBy { it.t }.forEachIndexed { i, s ->
                if (i == 0) moveTo(x(s.t - mine.t), yv(s.speedKph)) else lineTo(x(s.t - mine.t), yv(s.speedKph))
            }
        }
        other?.let { c.drawPath(line(it), stroke(C.BLUE, 1.6f, dash = true)) }
        c.drawPath(line(mine), stroke(C.INK, 1.8f))
        y = bot + 20f
        // 브레이크·지시등 띠: 칠해진 구간 = 켜짐(밟음)
        fun strip(name: String, d: HandoffData, color: (Sample) -> Int?) {
            c.drawText(name, M, y + 8f, lab)
            c.drawRect(left, y, right, y + stripH, fill(C.BG50))
            d.samples.filter(::inWin).sortedBy { it.t }.zipWithNext().forEach { (a, b) ->
                color(a)?.let { col -> c.drawRect(x(a.t - mine.t), y, x(b.t - mine.t), y + stripH, fill(col)) }
            }
            y += stripH + 5f
        }
        for ((d, name) in cars) if (d.controlsRecorded) { // 기록이 없는 차는 띠를 안 그린다(빈 띠 = "안 밟음"으로 읽힘)
            strip("$name 브레이크", d) { if (it.brake) C.INK else null }
            strip("$name 지시등", d) { when (it.signal) { "L" -> C.ORANGE_FILL; "R" -> C.BLUE; else -> null } }
        }
        val legend = buildString {
            append("검은 선 = 내 차 속도")
            if (other != null) append(" · 파란 점선 = 상대 차 속도")
            if (cars.any { it.first.controlsRecorded }) append(" · 지시등 주황 = 왼쪽, 파랑 = 오른쪽")
            append(" · 빨간 점선 = 내 차 충격 감지 시각")
        }
        c.drawText(ellipsize(legend, tiny, CW), M, y + 8f, tiny)
        y += 16f
    }

    // ── 시간순 표 ──
    fun timeline(lines: List<String>) {
        val rows = lines.filter { it.isNotBlank() }
        if (rows.isEmpty()) { text("기록 저장 전이에요. 사고 후 약 10초 뒤 시간순 기록이 만들어져요.", small); return }
        val re = Regex("""^(\d{2}:\d{2}:\d{2}(?:\.\d+)?)\s+(.*)$""")
        val timeP = paint(8.5f, C.TER, mono = true)
        rows.forEachIndexed { i, l ->
            val m = re.find(l)
            val (tm, tx) = if (m != null) m.groupValues[1] to m.groupValues[2] else "" to l
            val wrapped = wrap(tx, body, CW - 96f)
            val h = wrapped.size * 14f + 6f
            ensure(h)
            if (i % 2 == 0) c.drawRect(M, y, M + CW, y + h, fill(C.BG50))
            if ("충격" in tx || "에어백" in tx || "사고 감지" in tx) c.drawRect(M, y, M + 2.5f, y + h, fill(C.RED))
            c.drawText(tm, M + 8f, y + 13f, timeP)
            wrapped.forEachIndexed { k, s -> c.drawText(s, M + 96f, y + 13f + k * 14f, body) }
            y += h
        }
        y += 4f
    }

    // ── 두 차 나란히 ──
    fun twoCars(mine: HandoffData, other: HandoffData) {
        section("두 차 기록 나란히", "같은 시각끼리 한 줄")
        text("두 차 모두 이 앱을 쓰고 있었고, 두 운전자가 기록 공유에 동의해 함께 정리한 표입니다. " +
            "0초 = 내 차가 충격을 감지한 시각이고, 상대 차가 감지한 시각은 ${"%+.1f".format(other.t - mine.t)}초입니다.", small)
        text("시각은 각 차 기기의 시계(epoch 초) 그대로 맞췄습니다. 두 시계가 같다고 가정했으며, 시계가 다르면 줄이 조금 어긋날 수 있습니다.", small)
        text("이 표는 두 기록을 나란히 놓았을 뿐이며, 사고의 원인이나 비율을 판단하지 않습니다.", paint(8.5f, weight = 700))
        text("내 차: 부딪힌 대상 ${ClaimText.kind(mine)}, 충격 방향 ${ClaimText.impact(mine) ?: "기록 없음"}", small)
        text("상대 차: 부딪힌 대상 ${ClaimText.kind(other)}, 충격 방향 ${ClaimText.impact(other) ?: "기록 없음"}", small)
        val tW = 46f
        val carW = (CW - tW) / 2
        val colW = listOf(0.25f, 0.23f, 0.29f, 0.23f).map { it * carW }
        val heads = listOf("속도", "브레이크", "조향", "지시등")
        val none = "기록 없음"
        fun cells(s: Sample?, d: HandoffData) = if (s == null) List(4) { "–" } else listOf(
            ClaimText.kph(s.speedKph),
            if (!d.controlsRecorded) none else if (s.brake) "밟음" else "안 밟음",
            if (d.steerRecorded) ClaimText.steer(s.steerDeg) else none,
            if (d.controlsRecorded) ClaimText.signal(s.signal) else none,
        )
        fun row(time: String, a: List<String>, b: List<String>, p: Paint, bg: Int?) {
            ensure(16f)
            bg?.let { c.drawRect(M, y, M + CW, y + 16f, fill(it)) }
            c.drawText(time, M + 4f, y + 11.5f, p)
            for ((k, vals) in listOf(a, b).withIndex()) {
                var x = M + tW + k * carW
                vals.forEachIndexed { j, s -> c.drawText(ellipsize(s, p, colW[j] - 4f), x + 4f, y + 11.5f, p); x += colW[j] }
            }
            y += 16f
        }
        ensure(52f)
        c.drawRect(M, y, M + CW, y + 16f, fill(C.INK))
        val wh = paint(8f, C.WHITE, 700)
        c.drawText("시각", M + 4f, y + 11.5f, wh)
        c.drawText("내 차", M + tW + 4f, y + 11.5f, wh)
        c.drawText("상대 차", M + tW + carW + 4f, y + 11.5f, wh)
        y += 16f
        row("", heads, heads, lab, C.BG100)
        val cell = paint(8f)
        ClaimText.sideBySide(mine, other).forEach { r ->
            row(if (r.sec == 0) "0초 충돌" else "${if (r.sec > 0) "+" else ""}${r.sec}초", cells(r.mine, mine), cells(r.other, other), cell, if (r.sec == 0) C.RED_TINT else null)
        }
        y += 4f
        text("– = 그 시각 1초 안에 기록이 없음. 조향은 앞바퀴 각도입니다.", paint(7.5f, C.TER))
    }

    // ── 사진 ──
    private val photoW = (CW - 2 * 10f) / 3
    private val photoH = photoW * 0.75f
    private fun images(files: List<File>) = files.filter { it.extension.lowercase() in setOf("jpg", "jpeg", "png") }

    fun photosNeed(files: List<File>): Float = if (images(files).isEmpty()) 32f else photoH + 36f

    fun photos(mine: HandoffData, files: List<File>) {
        val imgs = images(files)
        if (imgs.isEmpty()) { text("이 기록서에 넣은 사진이 없어요.", small); return }
        val gap = 10f
        val w = photoW
        val ih = photoH
        imgs.chunked(3).forEachIndexed { r, row ->
            ensure(ih + 36f)
            row.forEachIndexed { i, f ->
                val no = r * 3 + i + 1
                val x = M + i * (w + gap)
                val box = RectF(x, y, x + w, y + ih)
                drawPhoto(f, box)
                c.drawRect(x, y, x + 16f, y + 14f, fill(C.INK))
                c.drawText("$no", x + 8f, y + 10.5f, paint(8f, C.WHITE, 700, align = Paint.Align.CENTER))
                val idx = Regex("""^(\d+)_""").find(f.name)?.groupValues?.get(1)?.toIntOrNull()
                val title = idx?.let { mine.shots.getOrNull(it)?.title } ?: if (f.name.startsWith("camera")) "차 카메라 사진" else f.nameWithoutExtension
                c.drawText(ellipsize("$no. $title", paint(8f), w), x, y + ih + 11f, paint(8f))
                val t = f.lastModified() / 1000.0
                c.drawText(ellipsize("${ClaimText.time(t, "HH:mm:ss")} 촬영 · 충돌 ${ClaimText.rel(t - mine.t)}", tiny, w), x, y + ih + 22f, tiny)
            }
            y += ih + 32f
        }
        text("사진 시각은 파일 저장 시각입니다. 원본 파일은 기록 지문 칸의 값으로 바뀌지 않았는지 확인할 수 있어요.", paint(7.5f, C.TER))
    }

    private fun drawPhoto(f: File, box: RectF) {
        val bmp = runCatching {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.path, o)
            var s = 1
            while (o.outWidth / (s * 2) >= 480) s *= 2 // 기록서 크기를 줄이려고 약 480~960px 로
            val b = BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = s })!!
            val deg = runCatching {
                when (ExifInterface(f.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90f; ExifInterface.ORIENTATION_ROTATE_180 -> 180f; ExifInterface.ORIENTATION_ROTATE_270 -> 270f; else -> 0f
                }
            }.getOrDefault(0f)
            if (deg == 0f) b else Bitmap.createBitmap(b, 0, 0, b.width, b.height, Matrix().apply { postRotate(deg) }, true)
        }.getOrNull()
        if (bmp == null) {
            c.drawRect(box, fill(C.BG100))
            c.drawText("사진을 열 수 없어요", box.centerX(), box.centerY(), tinyC)
        } else {
            // 칸 비율에 맞게 가운데를 잘라 넣는다
            val want = box.width() / box.height()
            val have = bmp.width.toFloat() / bmp.height
            val src = if (have > want) {
                val w = (bmp.height * want).toInt(); Rect((bmp.width - w) / 2, 0, (bmp.width + w) / 2, bmp.height)
            } else {
                val h = (bmp.width / want).toInt(); Rect(0, (bmp.height - h) / 2, bmp.width, (bmp.height + h) / 2)
            }
            c.drawBitmap(bmp, src, box, Paint(Paint.FILTER_BITMAP_FLAG))
        }
        c.drawRect(box, stroke(C.DIV))
    }

    // ── 진술 ──
    fun statement(mine: HandoffData, files: List<File>, hidden: Boolean = false) {
        section("운전자 진술", "본인 말 그대로")
        when {
            !mine.statement.isNullOrBlank() -> {
                text("“${mine.statement.trim()}”", paint(10f))
                text("운전자 본인이 한 말을 그대로 옮긴 것이고, 앱의 의견이 아니에요.", paint(7.5f, C.TER))
            }
            files.any { it.name == "statement.m4a" } ->
                text("글로 남긴 진술은 없고, 음성 녹음 파일(statement.m4a)로 보관돼 있어요. 파일 지문은 기록 지문 칸에 있어요.", small)
            hidden -> text("보내는 사람이 제외했어요.", small)
            else -> { text("앱에 남긴 진술이 없어요. 필요하면 아래에 적어 주세요.", small); blankLines(3) }
        }
    }

    // ── 기록 지문 ──
    fun fingerprint(mine: HandoffData, other: HandoffData?, files: List<File>) {
        section("기록 지문", "SHA-256")
        val items = buildList {
            mine.hash?.let { add("내 차 주행 기록 (log.json) · 저장할 때 계산" to it) }
            other?.hash?.let { add("상대 차 주행 기록 (log.json) · 저장할 때 계산" to it) }
            files.forEach { f -> hashOf(f)?.let { (h, saved) -> add("${f.name} · ${if (saved) "저장할 때 계산" else "기록서 만들 때 계산"}" to h) } }
        }
        if (items.isEmpty()) text(if (ClaimText.manual(mine)) "차량 기록이 없어 지문도 없어요." else "아직 기록 지문이 없어요 (기록 저장 전).", small)
        items.forEach { (label, h) ->
            ensure(26f)
            c.drawText(label, M, y + 8f, lab)
            c.drawText(if (h.length < 64) "$h…  (앞 ${h.length}자리)" else h, M, y + 19f, mono)
            y += 26f
        }
        val short = listOfNotNull(mine.hash, other?.hash).any { it.length < 64 }
        val expl = buildList {
            add("기록 지문은 파일 내용으로 계산한 값입니다. 파일이 한 글자라도 바뀌면 값이 완전히 달라지므로, 원본 파일로 다시 계산해 같은 값이 나오면 저장한 뒤 바뀌지 않았다는 뜻입니다.")
            add("이 값은 앱이 직접 계산한 것이며 공인 기관 같은 제3자의 인증이 아닙니다. 기록이 만들어진 시각을 증명하지도 않습니다.")
            if (short) add("휴대폰으로 받은 주행 기록은 지문 앞부분만 담깁니다. 전체 값과 원본 파일은 차에 저장돼 있어요.")
        }
        val lines = expl.flatMap { wrap(it, small, CW - 24f) }
        val h = lines.size * 12.5f + 16f
        ensure(h + 6f)
        y += 4f
        val r = RectF(M, y, M + CW, y + h)
        c.drawRoundRect(r, 5f, 5f, fill(C.BG50))
        c.drawRoundRect(r, 5f, 5f, stroke(C.INK, 0.8f))
        lines.forEachIndexed { i, l -> c.drawText(l, M + 12f, y + 8f + 8.5f + i * 12.5f, small) }
        y += h + 4f
    }

    /** 옆에 저장해 둔 <이름>.sha256 이 있으면 그 값(저장할 때 계산), 없으면 지금 계산. 못 읽으면 null */
    private fun hashOf(f: File): Pair<String, Boolean>? = runCatching {
        File(f.path.substringBeforeLast('.') + ".sha256").takeIf { it.exists() }?.readText()?.trim()?.split(Regex("\\s+"))?.first()
            ?.takeIf { it.isNotEmpty() }?.let { return@runCatching it to true }
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { s -> val b = ByteArray(65536); while (true) { val k = s.read(b); if (k < 0) break; md.update(b, 0, k) } }
        md.digest().joinToString("") { "%02x".format(it) } to false
    }.getOrNull()
}
