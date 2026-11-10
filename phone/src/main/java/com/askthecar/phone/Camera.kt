package com.askthecar.phone

import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.UseCase
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import java.io.File
import java.util.concurrent.Executors

/** 사진 저장용 뒤쪽 스레드 하나 (앱이 살아 있는 동안) */
private val bg = Executors.newSingleThreadExecutor()

/** 카메라 미리보기 + 사진 찍기 */
@Composable
fun CameraPreview(useCase: UseCase, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val view = remember { PreviewView(context) }
    DisposableEffect(useCase) {
        val future = ProcessCameraProvider.getInstance(context)
        val preview = Preview.Builder().build().also { it.surfaceProvider = view.surfaceProvider }
        future.addListener({
            runCatching {
                future.get().run { unbindAll(); bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, useCase) }
            }
        }, ContextCompat.getMainExecutor(context))
        onDispose { runCatching { future.get().unbindAll() } }
    }
    AndroidView({ view }, modifier)
}

/** 사진 한 장 → file, 옆에 <이름>.sha256 (원본이 바뀌지 않았는지 확인용). 끝나면 메인 스레드에서 onDone(성공 여부) */
fun takePhoto(capture: ImageCapture, file: File, context: android.content.Context, onDone: (Boolean) -> Unit) {
    file.parentFile?.mkdirs()
    val main = ContextCompat.getMainExecutor(context)
    capture.takePicture(ImageCapture.OutputFileOptions.Builder(file).build(), bg,
        object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(r: ImageCapture.OutputFileResults) {
                val ok = runCatching { File(file.path.removeSuffix(".jpg") + ".sha256").writeText(Logic.sha256(file)) }.isSuccess
                main.execute { onDone(ok) }
            }
            override fun onError(e: ImageCaptureException) { main.execute { onDone(false) } }
        })
}
