package com.askthecar.phone

import com.askthecar.Contact
import com.askthecar.CrashKind
import com.askthecar.HandoffCodec
import com.askthecar.HandoffData
import com.askthecar.Sample
import com.askthecar.ShotArt
import com.askthecar.Sighting
import com.askthecar.StepArt
import com.askthecar.SyncState
import com.askthecar.skipped
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.ZoneId

class LogicTest {
    @Test fun time() {
        // 2026-10-30 05:05 UTC = 14:05 서울
        assertEquals("10월 30일 14:05", Logic.time(1793336700.0, ZoneId.of("Asia/Seoul")))
    }

    @Test fun latestPhoto() {
        val names = listOf("0_OVERVIEW_100.jpg", "0_OVERVIEW_300.jpg", "0_OVERVIEW_300.sha256", "1_DAMAGE_400.jpg", "0_OVERVIEW_50.jpg", "10_OVERVIEW_900.jpg")
        assertEquals("0_OVERVIEW_300.jpg", Logic.latestPhoto(names, 0, "OVERVIEW", 60))
        assertNull(Logic.latestPhoto(names, 0, "OVERVIEW", 301)) // 예전 사고 사진은 안 고름
        assertEquals(Logic.photoName(1, "DAMAGE", 400), "1_DAMAGE_400.jpg")
    }

    @Test fun sha256() {
        val f = File.createTempFile("atc", ".txt").apply { writeText("abc"); deleteOnExit() }
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", Logic.sha256(f))
    }

    @Test fun carAnswer() {
        fun s(dt: Double, v: Double = 60.0, brake: Boolean = false, lamps: String = "", airbag: Boolean = false, signal: String = "none") =
            com.askthecar.Sample(inc.t + dt, v, brake = brake, lamps = lamps, airbag = airbag, signal = signal)
        val log = listOf(s(-8.0), s(-5.6, lamps = "비상등"), s(-1.8, 72.0, brake = true, lamps = "비상등"), s(-1.0, 45.0, brake = true, lamps = "비상등"), s(0.0, 9.0, airbag = true, lamps = "비상등"), s(2.0, 0.0))
        fun a(q: com.askthecar.Ask, l: List<com.askthecar.Sample> = log, c: Boolean = true) = com.askthecar.CarAnswer.of(q, l, inc.t, c, true)
        assertEquals("충돌 *5.6초 전* 비상등이 켜졌어요.", a(com.askthecar.Ask.HAZARD).text) // 그때 속도는 "속도" 질문이 답한다(차·휴대폰 답을 같게, 2026-11-09)
        assertEquals("충돌 *1.8초 전* 브레이크를 밟았어요.", a(com.askthecar.Ask.BRAKE).text)
        assertEquals("충돌 순간 시속 *9km*였어요. 1초 전에는 시속 *45km*였어요.", a(com.askthecar.Ask.SPEED).text)
        assertEquals("충돌 순간 에어백 전개 신호가 기록됐어요.", a(com.askthecar.Ask.AIRBAG).text)
        assertEquals("기록된 구간에는 방향지시등이 켜진 표시가 없어요.", a(com.askthecar.Ask.SIGNAL).text)
        assertEquals(null, a(com.askthecar.Ask.SIGNAL).at) // 없는 일에 점을 찍지 않는다
        assertEquals("그건 기록에 없어요.", a(com.askthecar.Ask.BRAKE, c = false).text)
        assertEquals(null, a(com.askthecar.Ask.BRAKE, emptyList()).source) // 띠를 비움
        assertEquals(true, a(com.askthecar.Ask.JUDGE).text.startsWith("판단은 드리지"))
        // 경고등 값을 못 받는 차, 저장 전 기록: "없었다"고 말하지 않는다
        assertEquals("그건 기록에 없어요.", com.askthecar.CarAnswer.of(com.askthecar.Ask.HAZARD, log, inc.t, true, true, lamps = false).text)
        val pre = log.filter { it.t <= inc.t - 1 } // 저장 전: 에어백은 뒤에 올 수 있다
        assertEquals("충돌 뒤 기록은 아직 받지 못했어요.", com.askthecar.CarAnswer.of(com.askthecar.Ask.AIRBAG, pre, inc.t, true, true, complete = false).text)
        assertEquals(true, a(com.askthecar.Ask.HAZARD, listOf(s(-8.0, lamps = "비상등"), s(0.0, lamps = "비상등"))).text.startsWith("기록이 시작될 때부터"))
        assertEquals(true, a(com.askthecar.Ask.HAZARD, listOf(s(-1.0), s(0.0), s(2.1, 0.0, lamps = "비상등"))).text == "충돌 *2.1초 뒤* 비상등이 켜졌어요.")
    }

    @Test fun callScript() {
        val o = com.askthecar.ClaimOwner("박준호", null, "12가 3456", null, null)
        val chosen = inc.copy(kind = "차", kindChosen = true, impactDeg = 180.0, samples = emptyList())
        val all = Logic.script(chosen, o, chosen.t + 60)
        assertEquals("사고 접수하려고 전화드렸어요.", all.first())
        assertEquals("접수번호를 알려 주세요.", all.last())
        assertEquals(true, "다른 차와 부딪혔어요" in all[1])
        assertEquals("차 센서 추정으로는 *뒤* 부분에 충격이 있었어요.", all[2])
        assertEquals(true, all.none { "고속도로" in it || "시내" in it }) // 도로 종류는 추정값
        assertEquals("운전자는 *박준호*예요.", all[3])
        assertEquals("차 번호는 *12가 3456*이에요.", all[4])
        assertEquals(true, all.none { "위치" in it }) // GPS 기록이 없으면 약속하지 않는다
        assertEquals(true, Logic.script(chosen.copy(rental = true), o, chosen.t).none { "차 번호" in it }) // 렌터카에 내 차 번호를 말하지 않게
        // 기록으로 추정한 대상은 말하게 하지 않는다
        assertEquals(true, "사고가 났어요" in Logic.script(chosen.copy(kindChosen = false), o, chosen.t)[1])
        assertEquals(true, Logic.script(chosen, o, chosen.t + 86400)[1].startsWith("어제 "))
        assertEquals(true, Logic.script(chosen, o, chosen.t + 86400 * 3)[1].contains("월 "))
        // 입력값이 없으면 그 문장이 빠지고, * 를 넣어도 굵게 표시가 깨지지 않는다
        assertEquals(4, Logic.script(chosen, com.askthecar.ClaimOwner(null, null, " ", null, null), chosen.t).size)
        assertEquals("차 번호는 *12가 3452*예요.", Logic.script(chosen, com.askthecar.ClaimOwner(null, null, "12가 *3452", null, null), chosen.t)[3])
    }

    @Test fun nextTodoOrder() {
        val car = inc.copy(samples = listOf(Sample(inc.t, 40.0)))
        val t0 = inc.t + 100
        fun task(d: HandoffData, m: Marks, now: Double = t0) = Logic.nextTodo(d, m, false, now).task
        assertEquals(Task.CHECK_119, task(car.copy(hurt = true), Marks(sceneOpen = true)))
        assertEquals(Task.SCENE, task(car.copy(hurt = true, near = true), Marks(sceneOpen = true))) // 차에서 119를 눌렀으면 넘어간다
        assertEquals(Task.REST, task(car.copy(hurt = true), Marks(called119 = true)))
        assertEquals(Task.REST, task(car.copy(severe = true), Marks(), inc.t + 6 * 86400))
        assertEquals(Task.CARD, task(car.copy(severe = true), Marks(), inc.t + 8 * 86400)) // 7일 지나면 다시
        assertEquals(Task.CARD, task(car.copy(severe = true), Marks(myself = true))) // "제가 할게요"
        assertEquals(Task.CARD, task(car, Marks()))
        assertEquals(Task.CLAIM, task(car, Marks(card = true)))
        assertEquals(Task.SEND, task(car, Marks(card = true, claimed = true)))
        assertEquals(Task.WAIT, task(car, Marks(card = true, claimed = true, sent = true)))
        assertEquals("차에게 묻기", Logic.nextTodo(car, Marks(card = true, claimed = true, sent = true), false, t0).button)
        assertEquals("기록 다시 보내기", Logic.nextTodo(inc.copy(samples = emptyList()), Marks(card = true, claimed = true, sent = true), false, t0).button) // 차 기록이 없으면 물을 수 없다
        assertEquals(Task.NOT_ACCIDENT, Logic.nextTodo(car.copy(hurt = true), Marks(), true, t0).task)
        assertEquals("가족에게 맡기기", Logic.nextTodo(car.copy(hurt = true, near = true), Marks(canHandOver = true), false, t0).button)
        assertEquals("제가 할게요", Logic.nextTodo(car.copy(hurt = true, near = true), Marks(), false, t0).button)
        assertEquals("렌터카 회사에\n사고를 알리세요", Logic.nextTodo(car.copy(rental = true), Marks(card = true), false, t0).title)
    }

    @Test fun sendRules() {
        assertEquals(Include(true, false, false, false, false), Logic.includeFor(ContactType.OTHER))
        assertEquals(Include(true, false, false, false, false), Logic.includeFor(ContactType.POLICE))
        assertEquals(Include(true, true, true, true, true), Logic.includeFor(ContactType.MINE))
        assertEquals(Include(true, true, true, true, true), Logic.includeFor(ContactType.RENT))
        assertEquals("상대 보험사 담당자에게 기록판을 보냈다고 표시함", Logic.sentLog(ContactType.OTHER))
        assertEquals("내 보험사 담당자에게 전체판을 보냈다고 표시함", Logic.sentLog(ContactType.MINE))
        assertEquals("경찰 조사관에게 기록판을 보냈다고 표시함", Logic.sentLog(ContactType.POLICE))
        val all = listOf(CaseContact(ContactType.POLICE, "a", "1"), CaseContact(ContactType.FAMILY, "b", "2"), CaseContact(ContactType.OTHER, "c", "3"))
        assertEquals(listOf(ContactType.OTHER, ContactType.POLICE), Logic.recipients(all, false)) // 가족은 PDF 로 보내지 않는다
        assertEquals(listOf(ContactType.MINE), Logic.recipients(emptyList(), false))
        assertEquals(listOf(ContactType.RENT), Logic.recipients(emptyList(), true))
    }

    @Test fun contactsAndLogRoundTrip() {
        val l = listOf(CaseContact(ContactType.OTHER, "김 담당자", "010", "A-1"), CaseContact(ContactType.MINE, "", "02"))
        assertEquals(l, Logic.decodeContacts(Logic.encodeContacts(l)))
        assertEquals(emptyList<CaseContact>(), Logic.decodeContacts("깨진 값"))
        val log = listOf(LogEntry(1.5, "접수"), LogEntry(2.0, "기록 잠김"))
        assertEquals(log, Logic.decodeLog(Logic.encodeLog(log)))
        val ins = Contact("한결손해보험 (가상)", "02-1")
        assertEquals(listOf(CaseContact(ContactType.MINE, "한결손해보험", "02-1")), Logic.seedContacts(inc.copy(contacts = listOf(ins)), null))
        assertEquals(ContactType.RENT, Logic.seedContacts(inc.copy(contacts = listOf(ins), rental = true), null).single().type)
        assertEquals(emptyList<CaseContact>(), Logic.seedContacts(inc.copy(contacts = emptyList()), null))
        assertEquals("02-9", Logic.seedContacts(inc.copy(contacts = emptyList()), Contact("내 보험사", "02-9")).single().phone)
    }

    @Test fun photoBelongsToOneCase() {
        val names = listOf("0_OVERVIEW_100.jpg", "0_OVERVIEW_500.jpg")
        assertEquals("0_OVERVIEW_100.jpg", Logic.latestPhoto(names, 0, "OVERVIEW", 50, 400)) // 다음 사고 뒤에 찍은 사진은 빠진다
    }

    @Test fun words() {
        assertEquals("뒤쪽 충격", Logic.impactWord(inc.copy(impactDeg = 180.0)))
        assertEquals("왼쪽 충격", Logic.impactWord(inc.copy(impactDeg = 270.0)))
        assertEquals("차 · 앞쪽 충격", Logic.caseWord(inc.copy(kindChosen = true, impactDeg = 0.0)))
        assertEquals("충격", Logic.caseWord(inc.copy(kindChosen = false, impactDeg = null)))
        val z = ZoneId.of("Asia/Seoul")
        assertEquals("오늘 14:05", Logic.dayTime(1793336700.0, 1793336700.0 + 60, z))
        assertEquals("어제 14:05", Logic.dayTime(1793336700.0, 1793336700.0 + 86400, z))
        assertEquals("10월 30일 14:05", Logic.dayTime(1793336700.0, 1793336700.0 + 86400 * 3, z))
        assertEquals("방금", Logic.logTime(100.0, 130.0, z))
    }

    @Test fun caseHome() {
        assertEquals(false, Logic.recordLost(inc.copy(hash = null), inc.t + 30))
        assertEquals(true, Logic.recordLost(inc.copy(hash = null), inc.t + 61))
        assertEquals(false, Logic.recordLost(inc, inc.t + 61)) // 기록 있음
        assertEquals(true, Logic.fresh(inc, inc.t + 3599))
        assertEquals(false, Logic.fresh(inc, inc.t + 3601))
        assertEquals("따님" to "지은 님", Logic.family("딸 박지은"))
        assertEquals("어머니" to "어머니", Logic.family("어머니"))
        assertEquals("이", Logic.subject("지은 님"))
        assertEquals("가", Logic.subject("어머니"))
        val guardian = Contact("딸 박지은", "010")
        val insurer = Contact("보험", "02-1"); val rental = Contact("렌터카", "02-2")
        val x = inc.copy(contacts = listOf(insurer, rental, guardian), guardian = guardian)
        assertEquals(insurer, Logic.callee(x))
        assertEquals(rental, Logic.callee(x.copy(rental = true)))
        assertNull(Logic.callee(x.copy(contacts = listOf(guardian))))
        assertEquals(x.copy(mode = "VOICE", rental = true), HandoffCodec.decode(HandoffCodec.encode(x.copy(mode = "VOICE", rental = true))))
    }

    private val inc = HandoffData(1793336700.0, "차", false, 270.0, true, "abcd", listOf("14:05:01 충격 감지"),
        listOf(HandoffData.Step("HAZARD", "비상등", "켜요", null, null, null),
            HandoffData.Step("INSURER", "보험사", "접수해요", "1588-0000", "보험사", "나중에 할게요"),
            HandoffData.Step("CAMERA", "사진", "찍어요", null, null, null)),
        1, listOf(HandoffData.ShotInfo("SAFE", "안전", "차도 금지", -12.0, 6.0, false)), listOf(Contact("딸", "010-0000-0000")))

    @Test fun decodeFromCar() {
        assertEquals(inc, HandoffCodec.decode(HandoffCodec.encode(inc)))
        assertEquals(inc.copy(impactDeg = null, hash = null), HandoffCodec.decode(HandoffCodec.encode(inc.copy(impactDeg = null, hash = null))))
        assertNull(HandoffCodec.decode("tel:0101234"))
    }

    @Test fun replayDataRoundTrips() {
        val full = inc.copy(
            samples = listOf(
                Sample(inc.t - 10, 36.25, -1.5, yawRate = 0.125, lat = 37.5, lon = 127.25),
                Sample(inc.t, 12.5, 0.0), // 자이로·GPS 없음
                Sample(inc.t + 5, 0.0, 2.5, lat = 37.5, lon = 127.25),
            ),
            seen = Sighting("", 5.5, -1.25, 0f, "person"), report = "14:05:01 충격 감지", vision = listOf("[영상 분석 추정] 차량이 보임"), statement = "신호 대기 중이었어요",
        )
        assertEquals(full, HandoffCodec.decode(HandoffCodec.encode(full)))
        assertTrue(HandoffCodec.decode(HandoffCodec.encode(full))!!.seen!!.isPerson)
        // 샘플 400개(40초)도 서버 제한 512KB 를 한참 밑돈다
        val big = inc.copy(samples = List(400) { Sample(inc.t - 30 + it * 0.1, 88.88, -12.34, yawRate = 0.1234, lat = 37.123456, lon = 127.123456) })
        assertTrue(HandoffCodec.encode(big).length < 40_000)
        // 소수를 줄여도 재현에 쓰는 값 그대로 (0.01초·0.01km/h·GPS 약 0.1m)
        val back = HandoffCodec.decode(HandoffCodec.encode(big))!!.samples
        assertEquals(400, back.size)
        assertEquals(big.samples[123].t, back[123].t, 0.006); assertEquals(37.123456, back[123].lat!!, 1e-9)
    }

    @Test fun skipsStepsByAnswer() {
        val arts = listOf(StepArt.PERSON, StepArt.DONT_MOVE, StepArt.MOVE_RISK, StepArt.WALK, StepArt.CAMERA, StepArt.MOVE_LATER, StepArt.REPLAY)
        fun next(from: Int, near: Boolean = false, moved: Boolean = false, person: Boolean = false) = com.askthecar.guideTarget(arts, from, 1) { it.skipped(near, moved, person) }
        assertEquals(2, next(0)) // "없어요": 곁에 있기를 건너뜀
        assertEquals(1, next(0, near = true)) // 119를 눌렀음
        assertEquals(1, next(0, person = true))
        assertEquals(3, next(1, near = true)) // 다친 사람이 있으면 차 옮기기 없음
        assertEquals(6, next(4, moved = true)) // 이미 옮겼으면 뒤의 옮기기 없음
        assertEquals(5, next(4))
        assertEquals(6, next(4, near = true))
        assertEquals(arts.size, com.askthecar.guideTarget(arts, 6, 1) { false })
    }

    @Test fun buttonsAndAnswersCrossToPhone() {
        val x = inc.copy(steps = listOf(HandoffData.Step("PHONE", "신고", "본문", "112", null, "신고했어요", "다음", "1588-2504")), near = true, moved = true)
        assertEquals(x, HandoffCodec.decode(HandoffCodec.encode(x)))
        // 옛 차 앱 형식: next·call2·near·moved 없음
        val old = org.json.JSONObject(HandoffCodec.encode(inc)).apply { getJSONArray("steps").let { a -> for (i in 0 until a.length()) a.getJSONObject(i).apply { remove("next"); remove("call2") } }; remove("near"); remove("moved") }.toString()
        assertEquals(inc, HandoffCodec.decode(old))
    }

    @Test fun oldFormatStillParses() {
        // 재현 값이 생기기 전 차 앱·휴대폰에 받아 둔 사고 형식
        val old = org.json.JSONObject(HandoffCodec.encode(inc)).apply { listOf("samples", "seen", "report", "vision", "statement").forEach { remove(it) } }.toString()
        assertEquals(inc, HandoffCodec.decode(old))
    }

    @Test fun namesFromCar() {
        assertEquals(CrashKind.VEHICLE, Logic.kindOf(inc))
        assertEquals(CrashKind.UNKNOWN, Logic.kindOf(inc.copy(kind = "새 종류")))
        assertEquals(StepArt.HAZARD, Logic.artOf("HAZARD")); assertNull(Logic.artOf("NEW"))
        assertEquals(ShotArt.SAFE, Logic.shotOf(inc.shots[0]).art)
        assertEquals(ShotArt.OVERVIEW, Logic.shotOf(inc.shots[0].copy(art = "NEW")).art)
    }

    @Test fun followCarNotEcho() {
        fun st(v: Long, step: Int?, by: String?, d: HandoffData? = inc) = SyncState(v, d, step, by, false)
        var f = Follow().onServer(st(1, null, null))
        assertEquals(0, f.step)
        f = f.onServer(st(2, 1, "car")); assertEquals(1, f.step) // 차가 옮기면 따라감
        f = f.move(2); assertEquals(2, f.step)
        f = f.onServer(st(3, 1, "car")); assertEquals(2, f.step) // 휴대폰이 옮긴 뒤 늦게 온 같은 차 단계: 안 바뀐 것 → 그대로
        f = f.onServer(st(4, 2, "phone")); assertEquals(2, f.step) // 메아리
        f = f.onServer(st(5, 2, "phone")); assertEquals(2, f.step) // 다른 이유(기록 갱신)로 온 같은 상태
        f = f.onServer(st(6, 0, "car")); assertEquals(0, f.step) // 차가 다시 옮김
        f = f.onServer(st(7, 0, "car")); f = f.move(1).onServer(st(8, 0, "car")); assertEquals(1, f.step) // 차 단계 그대로면 휴대폰 단계 유지
        assertEquals(f, f.onServer(st(9, 5, "car", d = null))) // 서버가 비면(다시 켜짐) 받아 둔 사고 유지
        assertEquals(2, f.onServer(st(10, 9, "car")).step) // 범위 밖은 끝 단계로
        // 새 사고 / 앱을 새로 연 경우: 서버의 지금 단계(휴대폰이 옮긴 것이어도)부터
        assertEquals(2, Follow().onServer(st(1, 2, "phone")).step)
        val next = inc.copy(t = inc.t + 60)
        val g = f.onServer(st(11, null, null, d = next)); assertEquals(next.t, g.t); assertEquals(0, g.step)
    }

    @Test fun serverRestartKeepsButWipeRemoves() {
        val shown = SyncState(5, inc, 2, "car", true)
        val empty = SyncState(1, null, null, null, false)
        assertEquals(shown.copy(version = 1), Logic.keepIncident(shown, empty)) // 서버가 다시 켜져 빔
        val wiped = empty.copy(wiped = true)
        assertEquals(wiped, Logic.keepIncident(shown, wiped)) // 철회로 지움: 안 남김
        val next = SyncState(2, inc.copy(t = inc.t + 60), null, null, false)
        assertEquals(next, Logic.keepIncident(shown, next))
        assertEquals(empty, Logic.keepIncident(null, empty))
    }

    @Test fun drivers() {
        assertEquals(listOf("haneul", "junho", "sunja", "guest"), Logic.drivers.map { it.first }) // 차 앱 Personas id 와 같아야 함
        assertEquals("박준호", Logic.driverName("junho")); assertNull(Logic.driverName("x"))
    }
    @Test fun phoneWordsForCarSteps() {
        val handoff = HandoffData.Step("HANDOFF", "이제 휴대폰으로 이어서 볼게요", "차에서 내리면", null, null, null, "차에서 계속 볼게요")
        assertNull(Logic.forPhone(handoff).next)
        assertTrue("차에서 계속" !in Logic.forPhone(handoff).title + Logic.forPhone(handoff).body)
        val walk = HandoffData.Step("WALK", "대피", "위험해요. 다음 안내는 휴대폰에서 이어져요.", null, null, null, "휴대폰으로 이어 볼게요")
        assertEquals("대피했어요", Logic.forPhone(walk).next)
        assertEquals("위험해요.", Logic.forPhone(walk).body)
    }
}
