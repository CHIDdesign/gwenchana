package com.askthecar

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** 차(헤드유닛)가 켜지면: 기록 서비스 시작. 평소엔 화면 없이 대기 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) begin(ctx)
    }

    companion object {
        fun begin(ctx: Context) {
            Consent.init(ctx)
            if (!Consent.allowed(Consent.Item.DRIVE)) return ConsentActivity.show(ctx, ConsentActivity.NEXT_BOOT)
            CarService.start(ctx) // 화면 없이 뒤에서 대기. 프로필은 차가 시동 때 처리한다(게임은 시동 신호로 Sim.link.onBoot)
        }
    }
}
