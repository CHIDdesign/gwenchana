package com.askthecar

import java.text.SimpleDateFormat
import java.util.Locale
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConsentTest {
    @Test
    fun retention() {
        val day = 24 * 60 * 60 * 1000L
        val now = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).parse("20261130_120000")!!.time
        // 이름 기준: 29일 전은 남기고 31일 전은 지움
        assertFalse(Consent.expired("20261101_120000", 0, short = false, now = now))
        assertTrue(Consent.expired("20261030_120000", 0, short = false, now = now))
        // 오탐·연습은 7일
        assertFalse(Consent.expired("20261124_120000", 0, short = true, now = now))
        assertTrue(Consent.expired("20261122_120000", 0, short = true, now = now))
        // 이름이 날짜가 아니면 수정 시각 기준
        assertTrue(Consent.expired("etc", now - 31 * day, short = false, now = now))
        assertFalse(Consent.expired("etc", now - day, short = false, now = now))
    }
}
