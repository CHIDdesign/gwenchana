package com.askthecar.phone

import com.askthecar.ClaimOwner
import com.askthecar.ClaimText
import com.askthecar.HandoffCodec
import com.askthecar.HandoffData
import com.askthecar.Sample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClaimTextTest {
    // 2026-10-30 14:05:00 서울
    private val t = 1793336700.0
    private val d = HandoffData(t, "차", true, 0.0, false, "abcd", listOf("14:05:00.0 충격 감지"), emptyList(), 0, emptyList(), emptyList(),
        samples = listOf(
            Sample(t - 1.0, 42.0, -5.0, brake = true, signal = "L", lat = 37.5, lon = 127.25),
            Sample(t, 30.4, 0.0, brake = true, lat = 37.50001, lon = 127.25001),
            Sample(t + 1.0, 0.0, 0.0, airbag = true),
        ))
    private val owner = ClaimOwner("홍길동", null, "12가3456", null, " ")
    /** Report.kt Validator.FORBIDDEN 과 같은 목록(:app 에 의존할 수 없어 복사) */
    private val forbidden = listOf("과실", "잘못", "책임", "가해", "피해자", "부주의", "과속", "위반", "탓", "급하게", "세게")

    @Test fun recordNoAndSummary() {
        assertEquals("ATC-20261030-140500", ClaimText.recordNo(d))
        val lines = ClaimText.callLines(d, owner)
        assertEquals(5, lines.size)
        assertEquals("언제: 10월 30일 14:05", lines[0])
        assertEquals("어디: 위도 37.50001, 경도 127.25001", lines[1]) // 충돌 순간에 가장 가까운 GPS
        assertEquals("무엇과: 차 (운전자 선택), 충격 앞 [센서 추정]", lines[2])
        assertTrue(lines[3], lines[3].startsWith("속도: 충돌 순간 30 km/h, 에어백 전개 신호 있음"))
        assertEquals("내 차: 12가3456, 증권번호 미입력", lines[4]) // 빈칸은 미입력
        assertEquals("30 km/h (1초 전 42 km/h)", ClaimText.speedLine(d))

        val empty = d.copy(samples = emptyList(), impactDeg = null, kind = "확인 안 됨", steps = listOf(HandoffData.Step("CALM", "", "", null, null, null))) // 차가 보낸 사고(저장 전)
        assertEquals(listOf("어디: 위치 기록 없음", "무엇과: 확인 안 됨", "속도: 충돌 순간 기록 없음, 에어백 기록 저장 전", "내 차: 차량번호 미입력, 증권번호 미입력"),
            ClaimText.callLines(empty, null).drop(1))

        val texts = lines + ClaimText.smsText(d, owner) + ClaimText.emailBody(d, owner, 3) + ClaimText.emailSubject(d, owner)
        texts.forEach { s -> forbidden.forEach { w -> assertTrue("$w in $s", w !in s) } }
        assertTrue(ClaimText.smsText(d, owner).endsWith("기록 번호 ATC-20261030-140500"))
        assertTrue("차량번호 12가3456" in ClaimText.smsText(d, owner))
    }

    @Test fun twoCarsShareOneTimeAxis() {
        // 상대 차는 0.3초 늦게 충격을 감지했고 기록이 띄엄띄엄
        val other = d.copy(t = t + 0.3, samples = listOf(Sample(t - 2.1, 20.0, 3.0, signal = "R"), Sample(t + 0.2, 5.0, 0.0, brake = true)))
        val rows = ClaimText.sideBySide(d, other, -2, 1)
        assertEquals(listOf(-2, -1, 0, 1), rows.map { it.sec })
        assertEquals(20.0, rows[0].other!!.speedKph, 0.0) // -2.1초 기록 → -2초 줄 (내 차 충돌 시각 기준, 0.5초 안)
        assertNull(rows[0].mine) // 내 차는 -2초 근처 기록 없음
        assertNull(rows[1].other)
        assertEquals(5.0, rows[2].other!!.speedKph, 0.0) // +0.2초 → 0초 줄
        assertEquals(30.4, rows[2].mine!!.speedKph, 0.0)
        assertEquals("오른쪽 3°", ClaimText.steer(rows[0].other!!.steerDeg))
        assertEquals("직진", ClaimText.steer(1.0)); assertEquals("왼쪽 5°", ClaimText.steer(-5.0))
        assertEquals("오른쪽", ClaimText.signal("R")); assertEquals("꺼짐", ClaimText.signal("none"))
    }

    @Test fun relativeTime() {
        assertEquals("+12초", ClaimText.rel(12.4))
        assertEquals("+3분 5초", ClaimText.rel(185.0))
        assertEquals("-4초", ClaimText.rel(-4.0))
    }

    @Test fun samplesKeepBrakeSignalAirbag() {
        val back = HandoffCodec.decode(HandoffCodec.encode(d))!!
        assertEquals(d.samples, back.samples)
        // 옛 차 앱이 보낸 6칸 장면도 읽힘(브레이크·지시등·에어백은 기본값)
        val old = org.json.JSONObject(HandoffCodec.encode(d)).apply {
            val a = getJSONArray("samples")
            for (i in 0 until a.length()) a.getJSONArray(i).run { remove(8); remove(7); remove(6) }
        }.toString()
        val s = HandoffCodec.decode(old)!!.samples.first()
        assertEquals(false, s.brake); assertEquals("none", s.signal); assertEquals(42.0, s.speedKph, 0.0)
    }

    private fun editRows(f: (org.json.JSONArray) -> Unit) = org.json.JSONObject(HandoffCodec.encode(d)).apply {
        val a = getJSONArray("samples")
        for (i in 0 until a.length()) f(a.getJSONArray(i))
    }.toString()

    @Test fun nineColumnsAreRecorded() {
        val back = HandoffCodec.decode(HandoffCodec.encode(d))!!
        assertTrue(back.controlsRecorded); assertTrue(back.steerRecorded)
    }

    @Test fun sixColumnsStayNotRecorded() {
        val six = HandoffCodec.decode(editRows { it.remove(8); it.remove(7); it.remove(6) })!!
        assertFalse(six.controlsRecorded); assertTrue(six.steerRecorded)
        assertEquals("기록 없음", ClaimText.airbag(six)) // "전개 신호 없음"이 사실처럼 나가면 안 됨
        val encoded = HandoffCodec.encode(six)
        assertEquals(6, org.json.JSONObject(encoded).getJSONArray("samples").getJSONArray(0).length())
        assertFalse(HandoffCodec.decode(encoded)!!.controlsRecorded)
    }

    @Test fun nullSteeringStaysNotRecorded() {
        val noSteer = HandoffCodec.decode(editRows { it.put(2, org.json.JSONObject.NULL) })!!
        assertFalse(noSteer.steerRecorded); assertTrue(noSteer.controlsRecorded)
        assertEquals(0.0, noSteer.samples[0].steerDeg, 0.0)
        assertFalse(HandoffCodec.decode(HandoffCodec.encode(noSteer))!!.steerRecorded)
    }

    @Test fun noSamplesKeepDefaults() {
        val empty = HandoffCodec.decode(HandoffCodec.encode(d.copy(samples = emptyList())))!!
        assertTrue(empty.controlsRecorded); assertTrue(empty.steerRecorded)
    }
}
