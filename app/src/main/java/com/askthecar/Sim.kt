package com.askthecar

import android.content.Context
import java.io.File

/**
 * 시연 도구가 끼어드는 유일한 자리. 실차·release 에선 항상 null 이라 앱은 SDK 값만으로 동작한다.
 * 도구 본체는 src/debug 에만 있다. src/debug 폴더를 지워도 이 파일 외 수정 없이 빌드된다.
 */
interface SimLink {
    val connected: Boolean
    fun start(ctx: Context, onIgnitionOn: () -> Unit)
    fun stop()
    /** 시동이 켜졌을 때 (게임 시연: 차의 Welcome·프로필 화면을 흉내 냄) */
    fun onBoot(ctx: Context)
    /** 앱 아이콘에서 열 화면 (게임 지도 내비) */
    fun openHome(ctx: Context)
    fun snapshot(out: File, done: (Boolean) -> Unit)
    /** 시연: 차 카메라 여러 대의 사고 전후 영상을 out 에 받는다(카메라 접근이 열렸을 때의 확장 기능을 보여 줌). 끝 시각(epoch 초)을 돌려준다 */
    fun cameraVideo(out: File): Double?
    /** 게임 시연: 안내 음성을 PC 스피커로(게임·에뮬레이터를 같이 돌리면 에뮬레이터 소리가 끊김). 못 하면 fallback */
    fun say(text: String, fallback: () -> Unit)
}

object Sim {
    @Volatile var link: SimLink? = null
    val connected get() = link?.connected == true
}
