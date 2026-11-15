package com.askthecar.qa.samples

import java.io.Closeable

/**
 * 테스트 시스템 담당자가 채울 연결 지점. 현재 앱 API라는 뜻이 아니다.
 * 각 함수는 격리된 새 환경을 반환한다. 기대 결과를 반환하는 가짜 제품을 만들지 않는다.
 * 연결 전에는 명시적으로 실패시켜 "테스트 통과" 집계에 들어가지 않게 한다.
 */
object QaAdapters {
    fun sensor(): SensorHarness = missing("TC-01", "ImpactSensor.onSensorChanged")
    fun delivery(): DeliveryHarness = missing("TC-02", "CarSync → Sync.apply → 저장 확인 → 차 표시")
    fun retry(): RetryHarness = missing("TC-03", "CarSync.publish + 영속 재시도 스케줄러")
    fun deletion(): DeletionHarness = missing("TC-04", "CrashFlow.chooseKind + Consent.set/deleteRecords")

    private fun missing(id: String, path: String): Nothing =
        throw AssertionError("BLOCKED_ADAPTER: $id 실제 코드 연결 필요: $path")
}

/** 폴더·SharedPreferences·싱글턴·스레드는 close에서 정리. 삭제 시험 관찰 전에 정리하지 않는다. */
interface IsolatedHarness : Closeable

interface SensorHarness : IsolatedHarness {
    /** 0ms부터 시작. Float 원시값을 실제 센서 계산 경로에 전달하고 이 이벤트 처리를 마친다. */
    fun feed(atMs: Long, x: Float, y: Float, z: Float)
    fun snapshot(): SensorObservation
}

data class SensorObservation(
    val correctedG: Double,
    /** 초기화 이후 실제 onImpact 호출마다 그 인자를 누적. */
    val impactCallbacksG: List<Double>,
)

/** version은 시험용 의미 버전이며 현재 API의 필드가 아니다. server version과도 다르다. */
data class CaseDocument(
    val account: String,
    val incidentEpochSeconds: Double,
    val semanticVersion: Int,
    val statement: String,
    /** Handoff에서 지문을 사용하는 완성 기록 / 아직 후속 기록이 없는 임시 자료 구분. */
    val finalRecord: Boolean,
)

interface VirtualHarness : IsolatedHarness {
    /** 가짜 경과시간. epoch 시작점은 fixtures의 사고 시각이며 실시간 sleep은 금지한다. */
    fun nowMs(): Long
    /** 현재 시각에 실행 가능한 작업만 실행. 미래 예약 작업을 실행하거나 시각을 앞당기지 않는다. */
    fun runReady()
    /** 현재 이후의 목표 시각으로 진행하며 <= 목표 시각의 예약 작업을 순서대로 실행한다. */
    fun advanceTo(atMs: Long)
}

interface DeliveryHarness : VirtualHarness {
    /** 로컬 자료 준비와 실제 CarSync 발행 경로. 전송 요청은 보류해 다음 메서드로 응답한다. */
    fun publishFromCar(document: CaseDocument)
    /** 실제 대기 중 업로드가 정확히 1개인지 확인하고 본문을 서버에 저장, HTTP 200 전달. */
    fun acceptPendingUpload()
    /** 실패 주입은 다음 폰 파일 쓰기 1회에만 적용. 실제 저장 판단·ACK 생성은 앱 코드가 수행. */
    fun failNextPhoneWrite()
    /** 마지막 서버 본문을 실제 폰 수신/저장 경로로 전달. 생성된 ACK는 자동으로 차에 전달하지 않음. */
    fun deliverServerDocumentToPhone()
    /** 보류된 폰 ACK를 서버로 보내고 그 상태를 차의 수신 경로에 전달. 없으면 연결 오류로 실패. */
    fun forwardPendingPhoneAcknowledgements()
    fun snapshot(): DeliveryObservation
}

data class DeliveryObservation(
    /** 메모리 UI가 아니라 실제 폰 파일을 다시 읽은 본문. */
    val phoneStoredStatement: String?,
    val phoneCaseCount: Int,
    /** 폰이 실제 발행한 저장 확인 요청 누적 횟수. */
    val acknowledgementRequests: Int,
    /** 실제 차 상태/표시 계산을 읽는다. 테스트가 Boolean을 조립해 recStage에 넣으면 안 됨. */
    val carRecordSaved: Boolean,
    val carServerAccepted: Boolean,
    val carShowsFinalArrived: Boolean,
    val carStatusText: String,
)

interface RetryHarness : VirtualHarness {
    /** 온라인/인증 유효, 실패 응답은 지연 0, 첫 요청부터 503 반환. */
    fun respondToUploadsWith(status: Int)
    /** 필수 자료를 로컬 저장하고 앱의 실제 초기 업로드를 요청. */
    fun saveAndStartUpload(document: CaseDocument)
    /** 디스크는 유지, 앱 메모리/스케줄러는 폐기 후 재생성. 가짜 시간은 유지. */
    fun restartApp()
    fun snapshot(): RetryObservation
}

data class UploadAttempt(
    val startedAtMs: Long,
    /** 실제 요청 본문을 해석. 기대 fixture를 그대로 붙여 관찰값처럼 반환하지 않는다. */
    val document: CaseDocument,
)

data class RetryObservation(
    /** 재시작해도 유지되는 외부 네트워크 관찰 기록. */
    val attempts: List<UploadAttempt>,
    val localStatement: String?,
    val serverCaseCount: Int,
    val serverStoredStatement: String?,
    val uploadQueueSize: Int,
    val carServerAccepted: Boolean,
    val carShowsFinalArrived: Boolean,
)

interface DeletionHarness : VirtualHarness {
    /** DRIVE 동의 true, 사고 A 및 초기 파일 존재, 오래된 다른 사고 없음. */
    fun seedConsentedCase(document: CaseDocument)
    /** 실제 chooseKind(OBJECT)를 호출. 저장 스레드가 폴더 참조를 잡은 뒤 디스크에 처음 닿기 직전 정지(메서드 이름은 호환을 위해 유지). */
    fun startKindWriteAndPauseBeforeMkdir()
    /** 실제 DRIVE 동의 철회 경로. 로컬 처리를 끝내되 보류한 종류 저장 작업은 풀지 않는다. */
    fun withdrawDriveConsent()
    fun releaseKindWrite()
    /** 종료해 버리지 말고 기존 작업이 정상적으로 끝나게 한다. 실제 스레드면 제한시간 있는 join 사용. */
    fun awaitKindWriteFinished()
    fun restartApp()
    fun snapshot(): DeletionObservation
}

data class DeletionObservation(
    val driveConsent: Boolean,
    val kindWritePausedBeforeMkdir: Boolean,
    val kindWriteFinished: Boolean,
    /** 사고 폴더 자체까지 검사. kind.txt가 없어도 빈 사고 폴더 재생성이면 실패. */
    val incidentDirectoryExists: Boolean,
    val incidentFiles: List<String>,
    val visibleIncidentCount: Int,
    val currentIncidentEpochSeconds: Double?,
)
