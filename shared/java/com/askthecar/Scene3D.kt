package com.askthecar

import android.annotation.SuppressLint
import android.content.Context
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewAssetLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * three.js 3D 화면(assets/crash3d) 하나를 사고 화면 내내 다시 쓴다: 상황 화면 → 촬영 안내 → 재현.
 * 사고 팝업 때 미리 만들어 두면 재현 단계에서 3D 엔진을 다시 읽지 않는다.
 * 모듈 스크립트라 file:// 대신 WebViewAssetLoader 의 https 주소로 띄운다.
 */
@SuppressLint("SetJavaScriptEnabled")
class Scene3D(ctx: Context, private val onFail: () -> Unit) {
    private var ready = false
    private var pending: String? = null

    val view: WebView = WebView(ctx).apply {
        val assets = WebViewAssetLoader.Builder().addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(ctx))
            .addPathHandler("/res/", WebViewAssetLoader.ResourcesPathHandler(ctx)).build() // 3D 페이지도 앱과 같은 글꼴
        settings.javaScriptEnabled = true
        setBackgroundColor(android.graphics.Color.TRANSPARENT) // 기록 화면은 앱 바닥이 그대로 보인다. 다른 화면은 3D 쪽에서 바탕을 칠함
        addJavascriptInterface(object { @JavascriptInterface fun fail() = post { onFail() } }, "Android")
        setOnTouchListener { v, _ -> v.parent?.requestDisallowInterceptTouchEvent(true); false } // 3D 를 돌릴 때 바깥이 스크롤되지 않게
        webChromeClient = object : android.webkit.WebChromeClient() { // 3D 페이지 콘솔을 logcat AskTheCar 태그로
            override fun onConsoleMessage(m: android.webkit.ConsoleMessage): Boolean {
                android.util.Log.i("AskTheCar", "3D: ${m.message()} (${m.sourceId()}:${m.lineNumber()})"); return true
            }
        }
        webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest) = assets.shouldInterceptRequest(request.url)
            override fun onPageFinished(view: WebView, url: String) { ready = true; pending?.let(::show); pending = null }
        }
        loadUrl("https://appassets.androidplatform.net/assets/crash3d/index.html")
    }

    /** 재현을 그 시각(충돌 기준 초)으로 옮기고 멈춘다 */
    fun seek(t: Double) { if (ready) view.evaluateJavascript("window.seekTo && window.seekTo($t)", null) }

    fun show(json: String) { if (ready) view.evaluateJavascript("window.show($json)", null) else pending = json }

    /** 장면 JSON 만들기는 계산이 길 수 있어 메인 스레드 밖에서 하고, 보여 주기는 메인에서 */
    suspend fun buildAndShow(build: () -> String) = show(withContext(Dispatchers.Default) { build() })

    /** 사고 종류만 바꿀 때: 카메라·재생 위치를 그대로 두고 "상대" 물체만 교체 */
    fun setKind(kind: CrashKind) { if (ready) view.evaluateJavascript("window.setKind && window.setKind('${estKindOf(kind)}', ${farOf(kind)})", null) }

    fun onPause() = view.onPause()
    fun onResume() = view.onResume()

    /** 앞 화면에서 떼어 낸 뒤 WebView 를 없앤다(붙어 있는 채로 destroy 하면 안 됨) */
    fun destroy() { (view.parent as? ViewGroup)?.removeView(view); view.destroy() }
}

private fun estKindOf(kind: CrashKind) = when (kind) { CrashKind.OBJECT -> "wall"; CrashKind.PERSON -> "person"; else -> "car" }
/** 내 차 겉면에서 상대 물체 중심까지(m): 틈 0.5 + 상대의 반 길이(차는 마주 봄 2.25, 벽 0.3, 사람 0.25) */
private fun farOf(kind: CrashKind) = when (kind) { CrashKind.OBJECT -> 0.8; CrashKind.PERSON -> 0.75; else -> 2.75 }

/** 같은 WebView 를 지금 화면에 붙인다(앞 화면에서 떼어 냄) */
@Composable
fun Scene3DView(scene: Scene3D, modifier: Modifier) = AndroidView(
    factory = { (scene.view.parent as? ViewGroup)?.removeView(scene.view); scene.view },
    modifier = modifier.clipToBounds(), // 둥근 칸 밖으로 궤적 선이 새어 나오지 않게
)

/**
 * 3D 장면 값(JSON). 좌표는 충돌 순간 내 차 기준이 아니라 기록 시작점 기준(trackPoses 와 같음).
 * mode: scene(사고 직후 상황) / photo(촬영 위치·영상 동선) / replay(움직임 재현).
 * 속도·조향 궤적 + 상대 물체(카메라 인식 > 충격 방향) 추정만 그린다.
 */
fun sceneJson(
    log: List<Sample>, crashT: Double, impactDeg: Double?, kind: CrashKind, highway: Boolean, mode: String, seen: Sighting? = null, floor: Boolean = false,
): String {
    val window = log.filter { it.t in (crashT - 10)..(crashT + if (mode == "replay") 5 else 0) }.ifEmpty { listOf(Sample(crashT)) }
    val crashIdx = window.indexOfLast { it.t <= crashT }.coerceAtLeast(0)
    val eased = easeSteps(window)
    val poses = fitAfterCrash(eased, trackPoses(eased), crashIdx)
    val end = poses[crashIdx]
    fun at(fwd: Double, right: Double) = pose(offsetPose(end, fwd, right))
    val d = JSONObject().put("mode", mode).put("dark", Pleos.dark).put("floor", floor)
        .put("own", JSONArray(poses.map(::pose)))
        .put("times", JSONArray(window.map { it.t - crashT }))
        .put("crashIndex", crashIdx)
        .put("speeds", JSONArray(window.map { it.speedKph.toInt() }))
        // 카메라 인식 종류로 상대 차 모델 고르기(실차와 같은 방법). 모르면 세단
        .put("estModel", when (seen?.label) { "truck" -> "truck"; "bus" -> "bus"; else -> "sedan" })
        .put("estKind", estKindOf(kind)).put("far", farOf(kind)).put("edge", impactDeg?.let(::ownEdge) ?: 0.0)

    val r = Math.toRadians(impactDeg ?: 0.0)
    if (seen != null && !seen.isPerson && kind != CrashKind.OBJECT) {
        val p = offsetPose(end, seen.fwd, seen.right)
        d.put("est", pose(Pose(p.x, p.y, kotlin.math.atan2(end.y - p.y, end.x - p.x)))).put("estFrom", "camera")
    } else if (impactDeg != null) {
        d.put("est", pose(estimateOther(end, impactDeg, ownEdge(impactDeg) + farOf(kind)))).put("estFrom", "impact")
    }
    if (impactDeg != null) d.put("impact", at(kotlin.math.cos(r) * 2.3, kotlin.math.sin(r) * 1.0)) // 차 겉면 근처
    if (mode == "photo") {
        val shots = PhotoGuide.shots(impactDeg, kind, highway)
        d.put("spots", JSONArray(shots.map { at(it.fwd, it.right) }))
    }
    return d.toString()
}

private fun pose(p: Pose) = JSONObject().put("x", p.x).put("y", p.y).put("yaw", p.yaw)
