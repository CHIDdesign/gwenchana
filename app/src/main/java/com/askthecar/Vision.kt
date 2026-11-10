package com.askthecar

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/**
 * 차 카메라 사진에서 차·사람을 찾아 상대의 방향·거리를 대략 추정한다 (실시간 아님, 사고 후 사진 1장).
 * 모델: TensorFlow EfficientDet-Lite0 (Apache-2.0, COCO 학습, Kaggle tensorflow/efficientdet tfLite lite0-detection-metadata v1)
 * 실행: Google MediaPipe Tasks Vision (Apache-2.0)
 * 거리 = 종류별 폭(차 1.8m, 사람 0.5m 등)이 화면에서 차지하는 폭으로, 방향 = 상자 중심의 가로 위치로. 카메라 화각·위치는 가정값 → 결과는 "[영상 분석 추정]".
 */
object VehicleFinder {
    private const val MODEL = "efficientdet_lite0.tflite"
    /** 종류별 (대략 폭 m, 조사 붙은 말). 거리는 이 폭이 화면에서 차지하는 폭으로 잰다 */
    private val KINDS = mapOf(
        "person" to (0.5 to "사람이"), "bicycle" to (0.6 to "자전거가"), "motorcycle" to (0.8 to "오토바이가"),
        "car" to (1.8 to "차량이"), "truck" to (2.5 to "트럭이"), "bus" to (2.5 to "버스가"),
    )
    // 가정: 차 카메라 가로 화각 90°, 카메라는 차 중심보다 1m 앞. 실제 장치마다 다름(미확인)
    private const val HFOV_DEG = 90.0
    private const val CAMERA_FWD_M = 1.0

    /** 사진에서 가장 크게 보이는 물체(없으면 null). 인식 자체가 실패하면 Result 실패: "안 보임"과 "못 살펴봄"은 다르다. label = 사진의 시각 표시 */
    fun nearest(ctx: Context, label: String, bmp: Bitmap): Result<Sighting?> = runCatching {
        val opts = ObjectDetector.ObjectDetectorOptions.builder()
            .setBaseOptions(BaseOptions.builder().setModelAssetPath(MODEL).build())
            .setRunningMode(RunningMode.IMAGE)
            .setScoreThreshold(0.4f)
            .setMaxResults(5)
            .setCategoryAllowlist(listOf("car", "truck", "bus", "person", "bicycle", "motorcycle"))
            .build()
        ObjectDetector.createFromOptions(ctx, opts).use { det ->
            val w = bmp.width.toDouble()
            det.detect(BitmapImageBuilder(bmp).build()).detections()
                .also { found -> Log.i("AskTheCar", "사진 인식 ${bmp.width}x${bmp.height}: " + found.joinToString { "${it.categories().first().categoryName()} %.2f 폭 %.0f".format(it.categories().first().score(), it.boundingBox().width()) }.ifEmpty { "없음" }) }
                .maxByOrNull { it.boundingBox().width() }
                ?.let { d ->
                    val box = d.boundingBox()
                    val focal = (w / 2) / tan(Math.toRadians(HFOV_DEG / 2))
                    val cat = d.categories().first()
                    // 낮은 점수의 트럭·버스는 승용차를 잘못 본 일이 많다(시험에서 확인): 재현에 버스가 나오지 않게 차로 본다
                    val kind = if (cat.categoryName() in listOf("truck", "bus") && cat.score() < 0.6f) "car" else cat.categoryName()
                    val dist = (KINDS[kind]?.first ?: 1.8) * focal / box.width().coerceAtLeast(1f)
                    val bearing = atan((box.centerX() - w / 2) / focal) // 오른쪽 +
                    Sighting(label, CAMERA_FWD_M + dist * cos(bearing), dist * sin(bearing), cat.score(), kind)
                }
        }
    }.onFailure { Log.w("AskTheCar", "사진 인식 실패", it) }

    /** 경위서에 붙이는 문장. 판단 없이 위치만 */
    fun sentence(s: Sighting) =
        "[영상 분석 추정] ${s.frameLabel} 차 카메라 사진에 ${KINDS[s.label]?.second ?: "물체가"} 보임: 앞 약 %.0fm, %s 약 %.0fm (화각 90° 가정)".format(
            s.fwd, if (s.right >= 0) "오른쪽" else "왼쪽", kotlin.math.abs(s.right),
        )
}

/**
 * 사고 사진: 안드로이드 표준 Camera2 (Pleos SDK 엔 카메라 API 가 없음).
 * 차에 따라 앱에 열린 카메라가 없을 수 있음(미확인) → 없거나 권한이 없으면 done(false) 하고 조용히 넘어감.
 * 안드로이드 14 는 백그라운드 서비스의 카메라 사용을 막아서, 화면(사고 화면)이 앞에 떠 있을 때 부른다.
 */
object CarCamera {
    @android.annotation.SuppressLint("MissingPermission")
    fun snapshot(ctx: Context, out: java.io.File, done: (Boolean) -> Unit) {
        if (!Consent.allowed(Consent.Item.CAMERA)) return done(false)
        if (Sim.connected) return Sim.link!!.snapshot(out, done)
        if (ctx.checkSelfPermission(android.Manifest.permission.CAMERA) != android.content.pm.PackageManager.PERMISSION_GRANTED) return done(false)
        val cm = ctx.getSystemService(android.hardware.camera2.CameraManager::class.java)
        val ch = android.hardware.camera2.CameraCharacteristics.LENS_FACING
        val id = runCatching {
            cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(ch) != android.hardware.camera2.CameraCharacteristics.LENS_FACING_FRONT }
                ?: cm.cameraIdList.firstOrNull()
        }.getOrNull() ?: return done(false)
        val size = runCatching {
            cm.getCameraCharacteristics(id).get(android.hardware.camera2.CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
                .getOutputSizes(android.graphics.ImageFormat.JPEG).filter { it.width <= 1920 }.maxByOrNull { it.width * it.height }
        }.getOrNull() ?: return done(false)
        val thread = android.os.HandlerThread("car-camera").apply { start() }
        val h = android.os.Handler(thread.looper)
        val reader = android.media.ImageReader.newInstance(size.width, size.height, android.graphics.ImageFormat.JPEG, 1)
        var device: android.hardware.camera2.CameraDevice? = null
        var finished = false
        fun finish(ok: Boolean) {
            if (finished) return
            finished = true
            runCatching { device?.close() }; runCatching { reader.close() }; thread.quitSafely(); done(ok)
        }
        reader.setOnImageAvailableListener({ r ->
            runCatching { r.acquireLatestImage()?.use { img -> val b = img.planes[0].buffer; out.writeBytes(ByteArray(b.remaining()).also { b.get(it) }) } }
            finish(out.exists() && out.length() > 0)
        }, h)
        h.postDelayed({ finish(false) }, 8000) // 카메라가 응답 없으면 포기
        runCatching {
            cm.openCamera(id, object : android.hardware.camera2.CameraDevice.StateCallback() {
                override fun onOpened(cam: android.hardware.camera2.CameraDevice) {
                    device = cam
                    @Suppress("DEPRECATION")
                    cam.createCaptureSession(listOf(reader.surface), object : android.hardware.camera2.CameraCaptureSession.StateCallback() {
                        override fun onConfigured(s: android.hardware.camera2.CameraCaptureSession) {
                            val req = cam.createCaptureRequest(android.hardware.camera2.CameraDevice.TEMPLATE_STILL_CAPTURE).apply { addTarget(reader.surface) }.build()
                            runCatching { s.capture(req, null, h) }.onFailure { finish(false) }
                        }
                        override fun onConfigureFailed(s: android.hardware.camera2.CameraCaptureSession) = finish(false)
                    }, h)
                }
                override fun onDisconnected(cam: android.hardware.camera2.CameraDevice) = finish(false)
                override fun onError(cam: android.hardware.camera2.CameraDevice, error: Int) = finish(false)
            }, h)
        }.onFailure { Log.w("AskTheCar", "차 카메라 열기 실패", it); finish(false) }
    }
}
