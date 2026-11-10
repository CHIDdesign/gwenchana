package com.askthecar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class CarHomeTest {
    @Test fun recordStripShowsOnlyWhatReallyHappened() {
        assertEquals(RecStage.RECORDING, recStage(7.0, saved = false, acked = false, arrived = false, failed = false))
        assertEquals(RecStage.SAVING, recStage(10.5, saved = false, acked = false, arrived = false, failed = false))
        assertEquals(RecStage.SAVED, recStage(12.0, saved = true, acked = false, arrived = false, failed = false))
        assertEquals(RecStage.UPLOADED, recStage(12.0, saved = true, acked = true, arrived = false, failed = false))
        assertEquals(RecStage.ARRIVED, recStage(12.0, saved = true, acked = true, arrived = true, failed = false))
        // 저장 전에 휴대폰이 충돌 전 기록을 받아도 "도착"이라 하지 않는다
        assertEquals(RecStage.RECORDING, recStage(3.0, saved = false, acked = false, arrived = true, failed = false))
        assertEquals(RecStage.FAILED, recStage(12.0, saved = false, acked = false, arrived = false, failed = true))
    }

    @Test fun carViewShowsTheHitSide() {
        assertEquals(CarView.REAR, carViewFor(null))
        assertEquals(CarView.REAR, carViewFor(180.0))
        assertEquals(CarView.REAR, carViewFor(90.0))
        assertEquals(CarView.FRONT, carViewFor(0.0))
        assertEquals(CarView.FRONT, carViewFor(270.0))
        assertEquals(CarView.FRONT, carViewFor(-45.0))
    }

    @Test fun pastCrashesNewestFirstAndOnlyChosenKind() {
        val root = Files.createTempDirectory("crashes").toFile()
        fun dir(name: String, vararg files: Pair<String, String>) = File(root, name).apply { mkdirs(); File(this, "log.json").writeText("[]"); files.forEach { (n, t) -> File(this, n).writeText(t) } }
        dir("20261103_181200", "kind.txt" to "OBJECT", "impact.json" to """{"t":1,"peakG":3,"deg":null}""")
        dir("20261110_140200", "kind.txt" to "VEHICLE guess", "impact.json" to """{"t":2,"peakG":3,"deg":180}""", "false_alarm" to "")
        File(root, "20261001_000000").mkdirs() // 기록 파일이 없는 폴더는 카드가 되지 않음
        val list = pastCrashes(root)
        assertEquals(listOf("20261110_140200", "20261103_181200"), list.map { it.name })
        assertTrue(list[0].falseAlarm)
        assertNull(list[0].kind) // 기록으로 추정한 대상은 적지 않음
        assertEquals("뒤쪽 충격", list[0].title)
        assertEquals("[센서 추정]", list[0].note)
        assertFalse(list[1].falseAlarm)
        assertEquals("벽·시설물", list[1].title)
        assertEquals("기록 보관", list[1].note)
        root.deleteRecursively()
    }
}
