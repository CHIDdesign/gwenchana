package com.askthecar.qa.samples

/** 합성 자료만 사용. 날짜와 경과시간은 다른 단위다. */
object QaFixtures {
    const val ACCOUNT = "qa-driver-a"
    const val INCIDENT_EPOCH_SECONDS = 1_700_000_000.0
    // Android SensorManager.GRAVITY_EARTH의 값. 앱에 연결할 때 동일 상수인지 확인한다.
    const val GRAVITY = 9.80665f
    const val G_TOLERANCE = 1e-5
    const val POLICY = "QA-PROPOSAL-01"

    val preview = CaseDocument(ACCOUNT, INCIDENT_EPOCH_SECONDS, 1, "qa-preview-v1", false)
    val final = CaseDocument(ACCOUNT, INCIDENT_EPOCH_SECONDS, 2, "qa-final-v2", true)

    data class SensorRow(
        val name: String,
        val x: Float,
        val y: Float,
        val z: Float,
        val expectedG: Double,
        val expectedCallbacks: Int,
    )

    val sensorRows = listOf(
        SensorRow("A_below", 0f, GRAVITY, 2.54f * GRAVITY, 2.4892, 0),
        SensorRow("B_positive", 0f, GRAVITY, 2.56f * GRAVITY, 2.5088, 1),
        SensorRow("C_negative", 0f, GRAVITY, -2.56f * GRAVITY, 2.5088, 1),
        SensorRow("D_vertical", 0f, GRAVITY + 2.56f * GRAVITY, 0f, 2.5088, 1),
    )
}
