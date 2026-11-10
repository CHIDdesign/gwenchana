package com.askthecar

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.view.View
import android.view.accessibility.AccessibilityEvent
import androidx.core.graphics.PathParser
import kotlin.math.min

/** 켜 두면 덮개가 상단 바·하단 바까지 가린다(일반 앱 창은 못 가림). launcher 가 에뮬레이터 설정에서 켠다. 게임 시연 전용 */
class SimCoverService : AccessibilityService() {
    override fun onServiceConnected() { instance = this }
    override fun onUnbind(intent: android.content.Intent?): Boolean { instance = null; return super.onUnbind(intent) }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}
    companion object { @Volatile var instance: SimCoverService? = null }
}

/**
 * 게임 시연 전용: 시동 꺼짐 = 검은 화면, 시동 켜짐 = 실제 차의 시동 애니메이션.
 * 순서·크기 기준 = 2027 그랜저 실제 영상(youtube 6ev6thpzSI0, 0:38~0:47을 0.5초 단위로 봄):
 * 하늘색 위에 도드라진 둥근 네모 → 점 4개 → 막대·점이 바뀜 → 점 2개 → 입체 H 로고 → 로고가 연보라로 빛남 → 검정 → 왼쪽 주행 화면 → 내비.
 * 조명·질감은 영상보다 진하게 만든 것(실제 재질은 영상 화질로 확인 못 함). GPU 셰이더라 API 33 이상(이 에뮬레이터 34)에서만 그린다.
 * 로고 모양 = Wikimedia "Hyundai Motor Company logo.svg"의 엠블럼 경로. 현대차 상표이므로 공개 저장소·APK·영상에 쓰기 전 허락 확인. 이 파일은 release 에 안 들어간다.
 */
class ScreenCover(ctx: Context, private val onDone: () -> Unit) : View(ctx) {
    private val power = ctx.getSystemService(PowerManager::class.java)
    private val paint = Paint()
    private var shader: RuntimeShader? = null
    private var booting = false
    private var start = 0L

    fun boot() { booting = true; start = 0; invalidate() }
    fun black() { booting = false; invalidate() }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        if (Build.VERSION.SDK_INT < 33 || w == 0) return
        // 로고 높이 지도: 번진 가장자리가 그대로 경사면이 된다. 반 해상도로 충분하다
        val bmp = Bitmap.createBitmap(w / 2, h / 2, Bitmap.Config.ARGB_8888)
        val s = bmp.width * LOGO_WIDTH / LOGO_W
        val path = Path(LOGO).apply { transform(Matrix().apply { setTranslate(-LOGO_W / 2, -LOGO_H / 2); postScale(s, s); postTranslate(bmp.width / 2f, bmp.height / 2f) }) }
        Canvas(bmp).apply {
            drawColor(Color.BLACK)
            drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; maskFilter = BlurMaskFilter(bmp.width * 0.0035f, BlurMaskFilter.Blur.NORMAL) })
        }
        val logo = BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
            filterMode = BitmapShader.FILTER_MODE_LINEAR
            setLocalMatrix(Matrix().apply { setScale(w.toFloat() / bmp.width, h.toFloat() / bmp.height) })
        }
        shader = RuntimeShader(AGSL).apply { setInputShader("logo", logo); setFloatUniform("res", w.toFloat(), h.toFloat()) }
        paint.shader = shader
    }

    override fun onDraw(c: Canvas) {
        if (!booting) return c.drawColor(Color.BLACK)
        if (start == 0L) { // 화면이 실제로 켜진 뒤부터 잰다
            c.drawColor(Color.BLACK)
            if (power.isInteractive) start = SystemClock.uptimeMillis()
            return postInvalidateOnAnimation()
        }
        val t = (SystemClock.uptimeMillis() - start) / 1000f * SPEED
        if (t >= END) return onDone()
        val s = shader
        if (t >= LEFT_AT) { // 실제 영상처럼 왼쪽 주행 화면이 먼저 켜지고, 오른쪽·아래는 잠시 뒤에 밝아진다
            val left = width * LEFT_W; val bottom = height * LEFT_H
            paint.shader = null; paint.color = Color.BLACK; paint.alpha = ((1 - ease((t - RIGHT_AT) / (END - RIGHT_AT))) * 255).toInt()
            c.drawRect(left, 0f, width.toFloat(), height.toFloat(), paint)
            c.drawRect(0f, bottom, left, height.toFloat(), paint)
            paint.alpha = 255; paint.shader = shader
            return postInvalidateOnAnimation()
        }
        if (t >= BLACK_AT || Build.VERSION.SDK_INT < 33 || s == null) { c.drawColor(Color.BLACK); return postInvalidateOnAnimation() }
        s.setFloatUniform("time", t)
        s.setFloatUniform("fade", ease(t / 0.3f))
        s.setFloatUniform("logoA", ease((t - DOTS_END) / 0.4f))
        s.setFloatUniform("glow", ease((t - GLOW_AT) / 0.5f))
        s.setFloatUniform("sweep", (t - GLOW_AT) / (BLACK_AT - GLOW_AT))
        val sq = width * 0.037f
        s.setFloatUniform("b0", width / 2f, height / 2f, if (t < SQUARE_END) sq else 0f, if (t < SQUARE_END) sq else 0f)
        s.setFloatUniform("sqA", ease((t - 0.35f) / 0.35f) * (1 - ease((t - SQUARE_END + 0.2f) / 0.2f)))
        s.setFloatUniform("dotA", ease((t - SQUARE_END) / 0.2f) * (1 - ease((t - DOTS_END + 0.25f) / 0.25f)))
        setDots(s, t)
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        postInvalidateOnAnimation()
    }

    /** 칸 4개의 폭을 시간표대로 바꾸며 가운데 정렬한다. 폭 0 인 칸은 사라진다 */
    private fun setDots(s: RuntimeShader, t: Float) {
        val i = KEYS.indexOfLast { it.t <= t }.coerceAtLeast(0)
        val a = KEYS[i]; val b = KEYS[min(i + 1, KEYS.lastIndex)]
        val f = if (b === a) 1f else ease((t - a.t) / (b.t - a.t))
        val d = width * 0.026f; val h = d * 0.82f; val gap = d * 0.42f
        val w = FloatArray(4) { (a.w[it] + (b.w[it] - a.w[it]) * f) * d }
        var x = width / 2f - (w.sum() + gap * (w.count { it > 1f } - 1).coerceAtLeast(0)) / 2
        for (n in 0..3) {
            val on = w[n] > 1f
            s.setFloatUniform("d$n", x + w[n] / 2, height / 2f, if (on) w[n] / 2 else 0f, if (on) h / 2 else 0f)
            if (on) x += w[n] + gap
        }
    }

    private fun ease(v: Float): Float { val x = v.coerceIn(0f, 1f); return x * x * (3 - 2 * x) }

    private class Key(val t: Float, val w: FloatArray)

    private companion object {
        const val SPEED = 1f // 1 = 실제 영상 속도(전체 약 9초). 크면 빨라진다
        const val SQUARE_END = 1.15f
        const val DOTS_END = 4.1f
        const val GLOW_AT = 4.6f
        const val BLACK_AT = 5.9f
        const val LEFT_AT = 7.1f
        const val RIGHT_AT = 8.3f
        const val END = 8.8f
        const val LEFT_W = 0.3335f; const val LEFT_H = 0.902f // 왼쪽 주행 화면 범위(에뮬레이터 캡처)
        const val LOGO_WIDTH = 0.24f // 화면 폭 대비(영상에서 잼)
        const val LOGO_W = 94.5f; const val LOGO_H = 48.4f
        val KEYS = listOf(
            Key(1.15f, floatArrayOf(1f, 1f, 1f, 1f)), Key(1.55f, floatArrayOf(1f, 1f, 1f, 1f)),
            Key(2.05f, floatArrayOf(2.6f, 1f, 2.6f, 2.6f)), Key(2.55f, floatArrayOf(0f, 3f, 1f, 1f)),
            Key(3.05f, floatArrayOf(0f, 1f, 3f, 0f)), Key(3.55f, floatArrayOf(0f, 1f, 1f, 0f)), Key(4.1f, floatArrayOf(0f, 1f, 1f, 0f)),
        )

        /** 도형을 높이 지도로 보고 픽셀마다 빛을 계산한다: 경사면 음영, 반사광, 긴 그림자, 번지는 배경 빛, 지나가는 빛 띠 */
        const val AGSL = """
uniform shader logo;
uniform float2 res;
uniform float time, fade, logoA, glow, sweep, sqA, dotA;
uniform float4 b0, d0, d1, d2, d3;

float box(float2 p, float4 b, float k, float bevel) {
    if (b.z <= 0.0) return 0.0;
    float r = min(b.z, b.w) * k;
    float2 q = abs(p - b.xy) - b.zw + r;
    float d = length(max(q, float2(0.0))) + min(max(q.x, q.y), 0.0) - r;
    return smoothstep(0.0, bevel, -d);
}

float hmap(float2 p) {
    float bevel = res.x * 0.0075;
    float h = sqA * box(p, b0, 0.55, bevel);
    h = max(h, dotA * box(p, d0, 1.0, bevel * 0.7));
    h = max(h, dotA * box(p, d1, 1.0, bevel * 0.7));
    h = max(h, dotA * box(p, d2, 1.0, bevel * 0.7));
    h = max(h, dotA * box(p, d3, 1.0, bevel * 0.7));
    return max(h, logoA * logo.eval(p).r);
}

half4 main(float2 p) {
    float2 uv = p / res;
    float2 c = (p - res * 0.5) / res.y;

    // 배경: 진한 코발트에서 연보라로, 천천히 움직이는 빛 두 덩이, 가장자리는 어둡게
    float diag = (uv.x + 1.0 - uv.y) * 0.5;
    float3 col = mix(float3(0.16, 0.34, 0.86), float3(0.62, 0.66, 1.0), smoothstep(0.0, 1.0, diag));
    float2 a = c - float2(0.55 + 0.08 * sin(time * 0.7), -0.32 + 0.05 * cos(time * 0.9));
    float2 b = c - float2(-0.60 + 0.06 * cos(time * 0.6), 0.36);
    col += float3(0.30, 0.22, 0.34) * exp(-dot(a, a) * 3.2);
    col += float3(0.02, 0.14, 0.30) * exp(-dot(b, b) * 3.0);
    col += float3(0.16, 0.18, 0.22) * exp(-dot(c, c) * 6.0);
    col *= 1.0 - 0.34 * smoothstep(0.35, 1.15, length(c));

    float h = hmap(p);
    float e = res.x * 0.0012;
    float dx = hmap(p + float2(e, 0.0)) - hmap(p - float2(e, 0.0));
    float dy = hmap(p + float2(0.0, e)) - hmap(p - float2(0.0, e));
    float3 n = normalize(float3(-dx * 1.7, -dy * 1.7, 1.0));
    float3 L = normalize(float3(-0.52, -0.66, 0.62));
    float3 V = float3(0.0, 0.0, 1.0);
    float3 H = normalize(L + V);

    // 그림자: 빛 쪽 위치의 높이를 봐서 가까운 진한 그림자 + 먼 흐린 그림자
    float2 toLight = L.xy / L.z;
    float s1 = hmap(p + toLight * res.x * 0.0035);
    float s2 = hmap(p + toLight * res.x * 0.0090);
    float s3 = hmap(p + toLight * res.x * 0.0170);
    float shadow = (0.42 * s1 + 0.26 * s2 + 0.14 * s3) * (1.0 - smoothstep(0.0, 0.5, h));
    col *= 1.0 - shadow;
    col = mix(col, col * float3(0.70, 0.74, 1.0), shadow);

    // 면: 빛을 받는 경사는 밝게, 등진 경사는 진하게, 가장자리에 선명한 반사광
    float mask = smoothstep(0.02, 0.22, h);
    float diff = max(dot(n, L), 0.0);
    float spec = pow(max(dot(n, H), 0.0), 70.0);
    float rim = pow(1.0 - n.z, 2.0);
    float3 face = mix(col * 1.05, float3(0.56, 0.68, 1.0), 0.42);
    face *= 0.50 + 0.95 * diff;
    face += float3(0.95, 0.97, 1.0) * spec * (1.0 - step(0.999, n.z)) * 0.85;
    face += float3(0.75, 0.80, 1.0) * rim * 0.65;

    // 로고 빛남: 진주빛 연보라 + 지나가는 빛 띠 + 주변 번짐
    float lh = logoA * logo.eval(p).r;
    float3 pearl = mix(float3(0.78, 0.72, 0.98), float3(1.0, 0.95, 1.0), smoothstep(-0.12, 0.12, -c.y + c.x * 0.25));
    pearl *= 0.62 + 0.62 * diff;
    pearl += float3(1.0) * spec * 1.2;
    float band = exp(-pow((uv.x + uv.y * 0.45 - (sweep * 1.9 - 0.2)) * 13.0, 2.0));
    pearl += float3(0.9, 0.9, 1.0) * band * 0.55;
    face = mix(face, pearl, glow * smoothstep(0.1, 0.6, lh));
    col = mix(col, face, mask);
    float2 oval = c * float2(1.0, 1.9);
    col += float3(0.42, 0.36, 0.56) * glow * exp(-dot(oval, oval) * 13.0) * (1.0 - mask);

    // 줄무늬 없애기
    col += (fract(sin(dot(p, float2(12.9898, 78.233))) * 43758.5453) - 0.5) / 128.0;
    return half4(col * fade, 1.0);
}
"""

        // Wikimedia 로고 SVG 의 엠블럼(타원 + H). 짝홀 채우기
        val LOGO: Path = PathParser.createPathFromPathData(
            "m 56.707,29.416 c 0.714,3.69 -1.22,6.755 -2.827,9.73 -1.785,2.768 -4.731,5.147 -8.272,4.791 -7.946,-0.119 -15.712,-1.339 -22.914,-3.512 -0.327,-0.089 -0.654,-0.298 -0.833,-0.595 -0.149,-0.417 0.06,-0.804 0.357,-1.071 6.577,-5.327 14.403,-7.796 22.14,-10.386 2.886,-0.833 5.952,-1.726 9.195,-1.309 1.338,0.179 2.677,1.102 3.154,2.352 z M 83.816,13.645 c 3.69,3.035 7.023,7.172 5.892,12.171 -1.845,7.38 -9.879,11.308 -16.277,13.807 -3.482,1.161 -6.993,2.352 -10.802,2.709 -0.238,-0.03 -0.625,0.029 -0.685,-0.328 l 0.089,-0.416 c 5.416,-6.101 9.522,-12.944 13.124,-19.878 1.666,-3.125 3.214,-6.338 4.642,-9.493 0.208,-0.238 0.417,-0.357 0.655,-0.446 1.338,0.118 2.291,1.16 3.362,1.874 z M 31.888,5.789 31.769,6.235 C 23.943,15.132 18.795,25.458 13.915,35.724 12.963,36.617 12.04,35.426 11.177,35.039 6.832,32.183 2.904,27.57 4.035,22.095 5.85,14.954 13.2,11.145 19.331,8.557 c 3.63,-1.369 7.41,-2.47 11.397,-3.095 0.417,-10e-4 0.952,-0.06 1.16,0.327 z m 38.835,1.428 c 0.446,0.238 0.982,0.327 1.25,0.773 0.238,0.506 -0.238,0.833 -0.506,1.161 -6.16,4.82 -13.212,7.499 -20.414,9.85 -3.661,0.893 -7.351,2.5 -11.427,1.607 -0.953,-0.238 -1.815,-0.863 -2.351,-1.786 -1.012,-2.797 0.357,-5.713 1.547,-8.153 1.577,-3.065 4.375,-6.666 8.243,-6.725 8.332,-0.06 16.188,1.19 23.658,3.273 z M 81.019,7.455 C 87.328,10.907 94.47,16.977 93.726,25.071 92.863,32.57 85.87,37.807 79.621,40.962 61.379,49.681 35.758,49.948 16.921,42.331 10.552,39.683 3.41,35.13 0.731,28.285 -1.322,22.661 1.177,16.799 5.374,12.901 14.152,4.926 25.341,2.189 36.858,0.611 c 12.499,-1.518 25.651,-0.179 36.84,3.66 2.5,0.923 4.97,1.935 7.321,3.184 z"
        ).apply { fillType = Path.FillType.EVEN_ODD }
    }
}
