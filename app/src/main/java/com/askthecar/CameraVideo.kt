package com.askthecar

import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import java.io.File
import kotlin.math.abs

/**
 * 여러 카메라의 사고 전후 영상 + 같은 시각의 조작 기록. 차에서 카메라 접근이 열렸을 때의 확장 기능이다.
 * 지금 실차 앱은 영상을 받을 방법이 없어(공개 SDK 에 없음) 파일이 없고 이 화면도 안 나온다. 시연만 파일이 들어온다.
 */
object CameraVideo {
    const val FILE = "cameras.mp4"
    const val END_FILE = "cameras_end.txt"
    const val COLUMNS = 3
    const val ROWS = 2
    /** 영상 안 칸의 차례(왼쪽 위부터). 시연 모드 askthecarRec.lua 의 CAMS 와 같아야 한다 */
    val NAMES = listOf("블랙박스 앞", "서라운드 앞", "블랙박스 뒤", "서라운드 왼쪽", "서라운드 뒤", "서라운드 오른쪽")

    fun endTime(dir: File): Double? = runCatching { File(dir, END_FILE).readText().toDouble() }.getOrNull()

    /** 재생 위치의 조작 기록 한 줄. 사실만 적는다 */
    fun logLine(t: Double, log: List<Sample>): String =
        log.minByOrNull { abs(it.t - t) }?.takeIf { abs(it.t - t) < 0.5 }?.let { s ->
            "%s · 속도 %.1f km/h · 앞바퀴 %.1f° · 브레이크 %s · 지시등 %s".format(
                FactTimeline.ts(t), s.speedKph, s.steerDeg, if (s.brake) "밟음" else "뗌",
                when (s.signal) { "L" -> "좌"; "R" -> "우"; else -> "꺼짐" },
            )
        } ?: "${FactTimeline.ts(t)} · 이 시각의 조작 기록 없음"
}

@Composable
fun CameraVideoView(video: File, dir: File, log: List<Sample>, crashT: Double, modifier: Modifier = Modifier) {
    var selected by remember { mutableStateOf<Int?>(null) } // null = 전체
    var position by remember { mutableFloatStateOf(0f) }
    var duration by remember { mutableFloatStateOf(1f) }
    var playing by remember { mutableStateOf(true) }
    var dragging by remember { mutableStateOf(false) }
    var texture by remember { mutableStateOf<TextureView?>(null) }
    val player = remember { MediaPlayer() }
    val end = remember(dir) { CameraVideo.endTime(dir) }
    val start = end?.let { it - duration / 1000.0 }

    DisposableEffect(player) { onDispose { player.release() } }
    fun applyCrop() {
        val v = texture ?: return
        val m = Matrix()
        selected?.let { i -> // 영상 전체를 칸 수만큼 키우고 고른 칸을 화면 왼쪽 위로 옮긴다
            val col = i % CameraVideo.COLUMNS
            val row = i / CameraVideo.COLUMNS
            m.setScale(CameraVideo.COLUMNS.toFloat(), CameraVideo.ROWS.toFloat())
            m.postTranslate(-col * v.width.toFloat(), -row * v.height.toFloat())
        }
        v.setTransform(m)
        v.invalidate()
    }
    LaunchedEffect(selected, texture) { applyCrop() }
    LaunchedEffect(player) {
        while (true) {
            if (!dragging) runCatching { position = player.currentPosition.toFloat() }
            delay(100)
        }
    }

    Column(modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("차 카메라 영상 (시연)", style = Pleos.titleS, modifier = Modifier.padding(start = 200.dp, top = 10.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PleosButton("전체", if (selected == null) ButtonKind.PRIMARY else ButtonKind.SECONDARY, large = false) { selected = null }
            CameraVideo.NAMES.forEachIndexed { i, name ->
                PleosButton(name, if (selected == i) ButtonKind.PRIMARY else ButtonKind.SECONDARY, large = false) { selected = i }
            }
        }
        // 전체는 영상 비율 그대로, 칸 하나는 칸 비율로 보여 준다
        val ratio = if (selected == null) 480f * CameraVideo.COLUMNS / (270f * CameraVideo.ROWS) else 480f / 270f
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            AndroidView(
                factory = { ctx ->
                    TextureView(ctx).apply {
                        texture = this
                        surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                                runCatching {
                                    player.setSurface(Surface(st))
                                    player.setDataSource(video.path)
                                    player.isLooping = true
                                    player.setVolume(0f, 0f)
                                    player.setOnPreparedListener { p ->
                                        duration = p.duration.toFloat().coerceAtLeast(1f)
                                        // 사고 5초 전부터
                                        val from = end?.let { e -> ((crashT - 5 - (e - p.duration / 1000.0)) * 1000).toInt() } ?: 0
                                        p.seekTo(from.coerceIn(0, p.duration).toLong(), MediaPlayer.SEEK_CLOSEST) // 기본 이동은 앞쪽 키프레임까지 몇 초 어긋난다
                                        p.start()
                                    }
                                    player.prepareAsync()
                                }
                            }
                            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) = applyCrop()
                            override fun onSurfaceTextureDestroyed(st: SurfaceTexture) = true
                            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
                        }
                    }
                },
                modifier = Modifier.aspectRatio(ratio),
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PleosButton(if (playing) "멈춤" else "재생", ButtonKind.SECONDARY, large = false) {
                if (playing) player.pause() else player.start()
                playing = !playing
            }
            Slider(
                value = position.coerceIn(0f, duration),
                onValueChange = { dragging = true; position = it },
                onValueChangeFinished = { player.seekTo(position.toLong(), MediaPlayer.SEEK_CLOSEST); dragging = false },
                valueRange = 0f..duration,
                modifier = Modifier.weight(1f),
            )
        }
        Text(start?.let { CameraVideo.logLine(it + position / 1000.0, log) } ?: "영상 시각 정보가 없어 조작 기록과 맞추지 못했어요.", style = Pleos.bodyS)
        Text("현대차 내장 카메라 자리를 게임에서 재현한 영상이에요. 실제 차에서는 앱에 카메라 접근이 열리면 이렇게 모아 보험사에 낼 수 있어요.", style = Pleos.labelS, color = Pleos.textTertiary)
    }
}
