package com.askthecar.phone

import com.askthecar.HandoffData
import com.askthecar.Sample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LinkLogicTest {
    private fun rec(t: Double, samples: List<Sample>) = HandoffData(t, "차", false, 0.0, false, null, emptyList(), emptyList(), 0, emptyList(), emptyList(), samples)

    @Test fun rowsUseEachCarClock() {
        // 내 차: 1000초 충돌, 10Hz. 상대 차: 1000.4초에 충격 기록, 1Hz, 위도 0.0001(약 11m) 북쪽
        val mine = rec(1000.0, (0..150).map { Sample(990.0 + it / 10.0, speedKph = 40.0, lat = 37.5, lon = 127.0) })
        val other = rec(1000.4, (-10..5).map { Sample(1000.4 + it, speedKph = 30.0, lat = 37.5001, lon = 127.0, yawRate = 0.2) })
        val rows = LinkLogic.rows(mine, other)
        assertEquals(16, rows.size)
        assertEquals(-10, rows.first().sec)
        val r0 = rows.single { it.sec == 0 }
        assertEquals(1000.4, r0.other!!.t, 1e-9) // 같은 시계로 0.4초 뒤 값이 가장 가까움
        assertEquals(11.1, r0.gapM!!, 0.1)
        assertEquals(0.4, LinkLogic.impactGap(mine, other), 1e-9)
        assertEquals("30km/h · 왼쪽으로 도는 중", LinkLogic.cell(r0.other))
        assertEquals("40km/h · 곧게", LinkLogic.cell(r0.mine))
    }

    @Test fun missingStaysMissing() {
        val mine = rec(1000.0, listOf(Sample(1000.0, 20.0)))
        val other = rec(1000.0, listOf(Sample(1003.0, 10.0, steerDeg = -5.0)))
        val rows = LinkLogic.rows(mine, other, -1, 3)
        assertNull(rows.single { it.sec == 1 }.mine) // 0.5초 넘게 떨어진 값은 끌어오지 않음
        assertNull(rows.single { it.sec == 0 }.gapM) // 위치 없음
        assertEquals("기록 없음", LinkLogic.cell(null))
        assertEquals("10km/h", LinkLogic.cell(com.askthecar.Sample(0.0, speedKph = 10.0), steerRecorded = false)) // 조향·자이로 둘 다 없으면 "곧게"라고 쓰지 않음
        assertEquals("10km/h · 핸들 왼쪽", LinkLogic.cell(rows.single { it.sec == 3 }.other))
    }

    @Test fun code() {
        assertEquals("123456", LinkLogic.cleanCode(" 123 456 "))
        assertNull(LinkLogic.cleanCode("12345"))
        assertNull(LinkLogic.cleanCode("1234567"))
        assertEquals("앞 (0°)", LinkLogic.impactWord(0.0))
    }
}
