package com.askthecar.qa.samples

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 독립 실행 계약 4개. qa/samples는 현재 Gradle source set에 포함되지 않는다.
 * 동료는 QaAdapters를 실제 코드에 연결하고 선택한 테스트 모듈의 source set에 등록한다.
 * 연결 전에는 BLOCKED_ADAPTER 실패이며, 앱 기능 실패나 성공으로 집계하지 않는다.
 */
class QaSampleTest {
    @Test fun tc01_sensorThresholdAndSign() {
        QaFixtures.sensorRows.forEach { row ->
            // 중력 추정 상태가 행 사이에 섞이지 않도록 반드시 새 환경.
            QaAdapters.sensor().use { app ->
                app.feed(0, 0f, QaFixtures.GRAVITY, 0f)
                val baseline = app.snapshot()
                assertEquals("${row.name}: 초기 충격값", 0.0, baseline.correctedG, QaFixtures.G_TOLERANCE)
                assertTrue("${row.name}: 초기 콜백은 없어야 함", baseline.impactCallbacksG.isEmpty())

                app.feed(20, row.x, row.y, row.z)
                val result = app.snapshot()
                assertEquals(row.name, row.expectedG, result.correctedG, QaFixtures.G_TOLERANCE)
                assertEquals("${row.name}: 실제 콜백 횟수", row.expectedCallbacks, result.impactCallbacksG.size)
                result.impactCallbacksG.forEach { g ->
                    assertEquals("${row.name}: 콜백 값", row.expectedG, g, QaFixtures.G_TOLERANCE)
                }
            }
        }
    }

    @Test fun tc02_previewAckDoesNotConfirmFinalDelivery() {
        QaAdapters.delivery().use { app ->
            app.advanceTo(1_000)
            app.publishFromCar(QaFixtures.preview)
            app.runReady()
            app.acceptPendingUpload()
            app.runReady()
            app.deliverServerDocumentToPhone()
            app.runReady()
            val preview = app.snapshot()
            assertEquals(QaFixtures.preview.statement, preview.phoneStoredStatement)
            assertEquals(1, preview.phoneCaseCount)
            assertEquals("임시 자료 저장 확인도 실제로 발생해야 함", 1, preview.acknowledgementRequests)
            app.forwardPendingPhoneAcknowledgements()
            app.runReady()

            app.advanceTo(11_000)
            app.publishFromCar(QaFixtures.final)
            app.runReady()
            app.acceptPendingUpload()
            app.runReady()
            val beforePhone = app.snapshot()
            assertTrue(beforePhone.carRecordSaved)
            assertTrue(beforePhone.carServerAccepted)
            assertFalse("임시 자료 ACK를 완성본 ACK로 쓰면 안 됨: $beforePhone", beforePhone.carShowsFinalArrived)

            app.failNextPhoneWrite()
            app.deliverServerDocumentToPhone()
            app.runReady()
            val failed = app.snapshot()
            assertEquals("쓰기 실패 후 기존 파일 보존", QaFixtures.preview.statement, failed.phoneStoredStatement)
            assertEquals("실패한 쓰기의 ACK 금지", 1, failed.acknowledgementRequests)
            assertFalse("저장 실패: $failed", failed.carShowsFinalArrived)

            // 같은 서버 자료를 다시 전달해 저장만 성공. ACK는 계속 보류한다.
            app.deliverServerDocumentToPhone()
            app.runReady()
            val saved = app.snapshot()
            assertEquals(QaFixtures.final.statement, saved.phoneStoredStatement)
            assertEquals(1, saved.phoneCaseCount)
            assertEquals("완성본의 새 저장 확인 필요", 2, saved.acknowledgementRequests)
            assertFalse("ACK 전달 전: $saved", saved.carShowsFinalArrived)

            app.forwardPendingPhoneAcknowledgements()
            app.runReady()
            val delivered = app.snapshot()
            assertTrue("완성본 ACK 이후: $delivered", delivered.carShowsFinalArrived)
            assertEquals(1, delivered.phoneCaseCount)
        }
    }

    @Test fun tc03_retryAtFiveSecondsSurvivesRestart() {
        QaAdapters.retry().use { app ->
            assertEquals(0L, app.nowMs())
            app.respondToUploadsWith(503)
            app.saveAndStartUpload(QaFixtures.final)
            app.runReady()
            val failed = app.snapshot()
            assertEquals("초기 요청을 실제로 보냈는지", listOf(0L), failed.attempts.map { it.startedAtMs })
            assertEquals(QaFixtures.final.statement, failed.localStatement)
            assertEquals(1, failed.uploadQueueSize)
            assertFalse(failed.carServerAccepted)

            app.advanceTo(2_000)
            app.restartApp()
            app.runReady()
            assertEquals("재시작은 시계를 초기화하지 않음", 2_000L, app.nowMs())
            assertEquals("아직 5초가 되지 않음", 1, app.snapshot().attempts.size)

            // 연결은 계속 온라인. 응답만 바꾼다. 네트워크 복구 이벤트를 발생시키지 않는다.
            app.respondToUploadsWith(200)
            app.advanceTo(4_999)
            assertEquals("너무 이른 재시도 금지", 1, app.snapshot().attempts.size)
            app.advanceTo(5_000)
            val success = app.snapshot()
            assertEquals(listOf(0L, 5_000L), success.attempts.map { it.startedAtMs })
            success.attempts.forEach { assertEquals("계정/사고/버전/본문 보존", QaFixtures.final, it.document) }
            assertEquals(QaFixtures.final.statement, success.localStatement)
            assertEquals(QaFixtures.final.statement, success.serverStoredStatement)
            assertEquals(1, success.serverCaseCount)
            assertEquals(0, success.uploadQueueSize)
            assertTrue(success.carServerAccepted)
            assertFalse("서버 200만으로 폰 도착 완료 금지", success.carShowsFinalArrived)

            app.advanceTo(20_000)
            assertEquals("성공한 버전은 다시 전송하지 않음", 2, app.snapshot().attempts.size)
        }
    }

    @Test fun tc04_withdrawalPreventsLateKindWriteResurrection() {
        QaAdapters.deletion().use { app ->
            app.seedConsentedCase(QaFixtures.final)
            val initial = app.snapshot()
            assertTrue(initial.driveConsent)
            assertTrue(initial.incidentDirectoryExists)
            assertTrue("삭제할 실제 파일이 있어야 함", initial.incidentFiles.isNotEmpty())
            assertEquals(1, initial.visibleIncidentCount)

            app.startKindWriteAndPauseBeforeMkdir()
            app.runReady()
            val paused = app.snapshot()
            assertTrue("실제 저장 작업이 barrier에 도달해야 함", paused.kindWritePausedBeforeMkdir)
            assertFalse(paused.kindWriteFinished)

            app.withdrawDriveConsent()
            app.runReady()
            assertDeleted("철회 직후", app.snapshot())

            app.releaseKindWrite()
            app.awaitKindWriteFinished()
            app.runReady()
            val afterLateWrite = app.snapshot()
            assertTrue("작업을 영원히 막아둔 채 통과시키지 않음", afterLateWrite.kindWriteFinished)
            assertDeleted("늦은 종류 저장 완료 뒤", afterLateWrite)

            app.restartApp()
            app.runReady()
            assertDeleted("재시작 뒤", app.snapshot())
        }
    }

    private fun assertDeleted(phase: String, result: DeletionObservation) {
        assertFalse("$phase: 동의 상태", result.driveConsent)
        assertFalse("$phase: 사고 폴더 재생성 금지", result.incidentDirectoryExists)
        assertTrue("$phase: 파일이 남음 ${result.incidentFiles}", result.incidentFiles.isEmpty())
        assertEquals("$phase: 화면 카드", 0, result.visibleIncidentCount)
        assertNull("$phase: 현재 사고", result.currentIncidentEpochSeconds)
    }
}
