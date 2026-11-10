package com.askthecar

import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import com.askthecar.StepArt.*

class ReportTest {
    /** 시안 모양 바뀜(비상등 삼각형 → 체크): 두 경로의 숫자만 보간, 명령 글자는 그대로 */
    @Test fun morphInterpolatesPathNumbers() {
        assertEquals("M1.5 2.0L3.0 4.0", morph("M1 2L3 4", "M2 2L3 4", 0.5f))
        assertEquals("M17.4 31.2L22.2 36.0L30.0 25.8L30.0 25.8", morph("M14.4 35.4L33.6 35.4L24 18L14.4 35.4", "M17.4 31.2L22.2 36L30 25.8L30 25.8", 1f))
        assertEquals(840, LineArts.HAZARD_OK.end)
        assertEquals(1980, LineArts.EVIDENCE.end)
    }

    // fake_drive.py 시나리오를 줄인 것: 직진 → 좌지시등 → 좌조향 → 브레이크 → 충돌
    private val log = (0 until 100).map { i ->
        val t = i / 10.0
        Sample(
            t = t,
            speedKph = if (t < 8) 50.0 else 0.0,
            steerDeg = if (t >= 5) -8.0 else 0.0,
            brake = t >= 7.5,
            signal = if (t >= 3) "L" else "none",
            airbag = t >= 8.1,
        )
    }
    private val facts = FactTimeline.build(log, ZoneOffset.UTC)

    @Test fun timelineHasEveryEventInOrder() {
        val keys = listOf("기록 시작", "좌측 방향지시등", "좌측 조향", "브레이크 페달 밟음", "에어백", "기록 종료")
        assertEquals(keys.size, facts.size)
        keys.zip(facts).forEach { (k, f) -> assertTrue("$k ∉ $f", k in f) }
        assertTrue(facts[4].startsWith("00:00:08.1"))
        assertEquals("09:00:00.0", FactTimeline.ts(0.0)) // 기본은 기기 시간대가 아니라 한국 시각
    }

    /** 차(전체 기록)와 휴대폰(충돌 전후 15초 샘플만)이 같은 질문에 같은 답을 내야 한다: 비상등을 창 밖(충돌 17.2초 전)에서 켠 경우 */
    @Test fun carAnswerSameOnCarAndPhone() {
        val crash = 1_762_700_000.0 + 40.0 // 2025-11-09 KST 임의 시각
        val full = (0 until 500).map { i -> val t = crash - 30 + i / 10.0; Sample(t, speedKph = 60.0, lamps = if (t >= crash - 17.2) "비상등" else "") }
        val facts = FactTimeline.build(full)
        val phone = full.filter { it.t in (crash - 10)..(crash + 5) } // Handoff.data 의 범위
        val car = CarAnswer.of(Ask.HAZARD, full, crash, controls = true, steer = true, facts = facts)
        val ph = CarAnswer.of(Ask.HAZARD, phone, crash, controls = true, steer = true, facts = facts)
        assertEquals(car.text, ph.text)
        assertTrue(car.text, car.text.startsWith("충돌 *17.2초 전* 비상등이 켜졌어요."))
        assertEquals(car.at!!, ph.at!!, 0.001)
        // 사실 줄이 없으면 옛 동작: 창 처음부터 켜져 있던 것으로
        assertTrue(CarAnswer.of(Ask.HAZARD, phone, crash, controls = true, steer = true).text.startsWith("기록이 시작될 때부터"))
    }

    @Test fun accelerationAndWarningLamps() {
        // 정지 → 1초에 10km/h 씩 가속 → ABS 경고등 켜졌다 꺼짐
        val log = (0 until 40).map { i -> val t = i / 10.0
            Sample(t = t, speedKph = if (t < 1) 0.0 else (t - 1) * 10, lamps = if (t in 2.0..2.9) "ABS 작동" else "") }
        val f = FactTimeline.build(log, ZoneOffset.UTC)
        assertEquals(1, f.count { "가속 시작" in it })
        assertTrue(f.any { "경고등 켜짐: ABS 작동" in it } && f.any { "경고등 꺼짐: ABS 작동" in it })
    }

    @Test fun validatorRejectsInventedNumberAndJudgement() {
        val ok = "00:00:03.0에 좌측 방향지시등을 켠 뒤 00:00:08.1에 에어백이 전개되었다."
        assertTrue(Validator.problems(ok, facts).isEmpty())
        assertTrue(Validator.problems("속도 70 km/h로 주행", facts).isNotEmpty())
        assertTrue(Validator.problems("운전자 과실로 보인다", facts).isNotEmpty())
        assertEquals(facts.joinToString("\n"), Validator.choose("시속 99 km/h", facts))
    }

    @Test fun impactIsAFactEvenWithoutAirbag() {
        val f = FactTimeline.build(log, ZoneOffset.UTC, impactT = 8.05, peakG = 3.42, impactDeg = 350.0)
        val i = f.indexOfFirst { "충격 감지" in it }
        assertEquals("00:00:08.0 충격 감지 (최대 3.4g, 앞 방향 [추정])", f[i])
        assertTrue("에어백보다 먼저", i < f.indexOfFirst { "에어백" in it })
        assertTrue(Validator.problems("00:00:08.0에 최대 3.4g 충격이 감지되었다.", f).isEmpty())
        assertEquals("오른쪽", FactTimeline.side(90.0))
        assertEquals("왼쪽 뒤", FactTimeline.side(-135.0))
        // 센서 충격이 없으면(연습 사고 등) 숫자 없이 사고 감지만
        assertTrue(FactTimeline.build(log, ZoneOffset.UTC, impactT = 20.0).dropLast(1).last().endsWith("사고 감지 (가속도 센서 충격 기록 없음)"))
    }

    @Test fun validatorChecksSignAndWholeTimestamps() {
        // 사실: 앞바퀴 각도 -8.0° (좌조향). 부호를 뒤집은 8.0 은 로그에 없는 숫자
        assertTrue(Validator.problems("앞바퀴 각도 -8.0°로 좌측 조향", facts).isEmpty())
        assertTrue(Validator.problems("앞바퀴 각도 8.0°로 좌측 조향", facts).isNotEmpty())
        // 시각은 통째로 비교: 각 부분 숫자(00, 08)가 있어도 없는 시각이면 거부
        assertTrue(Validator.problems("00:00:08.1에 에어백 전개", facts).isEmpty())
        assertTrue(Validator.problems("00:00:03.1에 에어백 전개", facts).isNotEmpty())
    }

    private fun arts(l: List<GuideStep>) = l.map { it.art }

    /** 화면에서 실제로 보이는 순서: 아무도 안 다쳤고 차를 안 옮겼을 때 */
    private fun seen(l: List<GuideStep>, near: Boolean = false, moved: Boolean = false, person: Boolean = false) =
        l.filterNot { it.art.skipped(near, moved, person) }.map { it.art }

    @Test fun highwayOrder() {
        val (_, junho, sunja) = Personas.all
        assertEquals(listOf(HAZARD, TRUNK, WALK, PERSON, PHONE, WAIT, INSURER, CAMERA_FAR, CARD_LATER, EXCHANGE, REPLAY, RECORD), seen(Guides.steps(true, junho)))
        // 사회초년생이어도 고속도로엔 안심 단계가 없고, 보호자는 기다리기 다음
        assertEquals(listOf(HAZARD, TRUNK, WALK, PERSON, PHONE, WAIT, GUARDIAN, INSURER, CAMERA_FAR, CARD_LATER, EXCHANGE, REPLAY, RECORD), seen(Guides.steps(true, sunja)))
        assertTrue(arts(Guides.steps(true, Personas.guest)).none { it == CALM || it == HANDOFF })
        // 시설물은 신고 다음
        assertEquals(PHONE, arts(Guides.steps(true, junho, kind = CrashKind.OBJECT)).let { it[it.indexOf(FACILITY) - 1] })
        // 대피 단계가 곧 휴대폰 이어 보기
        val walk = Guides.steps(true, junho).first { it.art == WALK }
        assertEquals("휴대폰을 들고 가드레일 밖으로 대피해 주세요", walk.title)
        assertEquals("휴대폰으로 이어 볼게요", walk.next)
        // 신고: 112 + 1588-2504 + 신고했어요, 에어백 경로만 한 줄 추가
        val report = Guides.steps(true, junho).first { it.art == PHONE }
        assertEquals(listOf("112", "1588-2504", "신고했어요"), listOf(report.call, report.call2, report.decline))
        assertTrue("돌아가지" !in report.body)
        assertTrue("차로 오는 전화를 받으러 돌아가지 마세요." in Guides.steps(true, junho, severe = true).first { it.art == PHONE }.body)
        // 트렁크 설명은 표지처럼 말하지 않는다
        assertTrue(Guides.steps(true, junho).first { it.art == TRUNK }.body.startsWith("뒤차가 사고 차임을"))
    }

    @Test fun cityOrder() {
        val (haneul, junho, _) = Personas.all
        assertEquals(listOf(HAZARD, PERSON, MOVE_RISK, WALK, HANDOFF, POLICE, EXCHANGE, INSURER, CARD, CAMERA, MOVE_LATER, REPLAY, RECORD), seen(Guides.steps(false, junho)))
        assertEquals(listOf(CALM, HAZARD, PERSON, MOVE_RISK, WALK, HANDOFF, POLICE, EXCHANGE, GUARDIAN, INSURER, CARD, CAMERA, MOVE_LATER, REPLAY, RECORD), seen(Guides.steps(false, haneul)))
        // 대상별: 시설물이면 경찰 판단 대신 시설물 + 전선 문구, 사람이면 곁에 있기가 보이고 차 옮기기·경찰 판단 없음
        val wall = Guides.steps(false, junho, kind = CrashKind.OBJECT)
        assertTrue(FACILITY in arts(wall) && POLICE !in arts(wall))
        assertTrue("끊어진 전선" in wall.first { it.art == WALK }.body)
        assertTrue("끊어진 전선" !in Guides.steps(false, junho).first { it.art == WALK }.body)
        assertEquals(listOf(HAZARD, PERSON, DONT_MOVE, WALK, HANDOFF, EXCHANGE, INSURER, CARD, CAMERA, REPLAY, RECORD), seen(Guides.steps(false, junho, kind = CrashKind.PERSON), person = true))
        // 119를 눌렀으면: 곁에 있기가 나오고, 차 옮기기·경찰 판단은 빠지고, 블랙박스는 나중에
        assertEquals(listOf(HAZARD, PERSON, DONT_MOVE, WALK, HANDOFF, EXCHANGE, INSURER, CARD_LATER, CAMERA, REPLAY, RECORD), seen(Guides.steps(false, junho, near = true), near = true))
        // 이미 옮겼으면 뒤의 옮기기는 없다
        assertTrue(MOVE_LATER !in seen(Guides.steps(false, junho), moved = true) && MOVE_RISK in seen(Guides.steps(false, junho), moved = true))
    }

    @Test fun briefKeepsTrunkAndDropsOnlyGuardianAndCalm() {
        val junho = Personas.all[1].copy(guardian = Contact("가족", "010"))
        val hw = Guides.steps(true, junho)
        assertTrue(StepArt.TRUNK in arts(hw) && StepArt.GUARDIAN !in arts(hw))
        assertEquals(arts(Guides.steps(true, junho.copy(mode = GuideMode.DETAIL))) - StepArt.GUARDIAN, arts(hw))
        assertEquals(arts(Guides.steps(false, junho.copy(mode = GuideMode.DETAIL))) - StepArt.GUARDIAN, arts(Guides.steps(false, junho)))
    }

    @Test fun hurtPath() {
        val (haneul, junho, _) = Personas.all
        // 다쳤어요: 119가 맨 앞, 안심 단계·부상 재질문·차 옮기기·경찰 판단 없음, 블랙박스는 나중에
        val city = Guides.steps(false, haneul, hurt = true)
        assertEquals(listOf(HURT_CALL, HAZARD, WALK, HANDOFF, EXCHANGE, GUARDIAN, INSURER, CARD_LATER, CAMERA, REPLAY, RECORD), arts(city))
        assertEquals(1, city.count { it.call == "119" })
        assertTrue("끊지 말아" in city.first().body && "문자" in city.first().body)
        assertTrue("움직이지 말고 119" in city.first { it.art == WALK }.body && "움직이지 말고 119" in city.first { it.art == WALK }.voice)
        assertEquals(listOf(HURT_CALL, HAZARD, TRUNK, WALK, PHONE, WAIT, INSURER, CAMERA_FAR, CARD_LATER, EXCHANGE, REPLAY, RECORD), arts(Guides.steps(true, junho, hurt = true)))
        val hw = Guides.steps(true, junho, hurt = true)
        assertTrue("못 나간다" in hw.first().body)
        assertTrue("못 나오면 119" in hw.first { it.art == WALK }.voice)
        assertEquals("휴대폰으로 이어 볼게요", hw.first { it.art == WALK }.next)
        // 안 다쳤으면 그 문구가 없다
        assertTrue("못 나오면" !in Guides.steps(true, junho).first { it.art == WALK }.body)
        assertTrue("차 안에 그대로" !in (city + hw).joinToString { it.body })
    }

    @Test fun airbagPath() {
        val (haneul, junho, _) = Personas.all
        // 시내: 긴급 구난 전화 안내가 맨 앞(간결도), 안심은 그 뒤, 블랙박스는 나중에
        val c = Guides.steps(false, haneul, severe = true)
        assertEquals("차로 오는 전화를 받아 주세요", c.first().title)
        assertEquals(listOf(PHONE, CALM, HAZARD), arts(c).take(3))
        assertTrue("흰 가루" in c.first().body)
        assertTrue(CARD_LATER in arts(c) && CARD !in arts(c))
        assertEquals(PHONE, Guides.steps(false, junho, severe = true).first().art)
        // 고속도로: 전화 안내·안심 없이 바로 비상등
        assertEquals(HAZARD, Guides.steps(true, haneul, severe = true).first().art)
    }

    @Test fun roadQuestionOnlyWhenAsked() {
        val junho = Personas.all[1]
        assertTrue(ROAD !in arts(Guides.steps(true, junho)) && ROAD !in arts(Guides.steps(false, junho)))
        assertEquals(ROAD, Guides.steps(false, junho, askRoad = true).first().art)
        assertEquals(listOf(HURT_CALL, ROAD), arts(Guides.steps(true, junho, hurt = true, askRoad = true)).take(2)) // 119 가 먼저
        assertEquals(listOf("네", "아니요"), Guides.steps(true, junho, askRoad = true).first().let { listOf(it.next, it.decline) })
    }

    @Test fun guideWordingAndContactWithoutNumber() {
        val (haneul, junho, _) = Personas.all
        // 고속도로 사진·나중 블랙박스는 사람 그림이 아니라 선 그림
        listOf(StepArt.CAMERA_FAR, StepArt.CARD_LATER, StepArt.FIRE, StepArt.WAIT).forEach { assertTrue(!it.hasPicture) ; assertTrue(lineArtOf(it) != null) }
        assertEquals(LineArts.CARD, lineArtOf(StepArt.CARD_LATER))
        val photo = Guides.steps(true, junho).first { it.art == StepArt.CAMERA_FAR }
        assertEquals("사진은 꼭 찍지 않아도 돼요", photo.title)
        assertTrue("가까이 가서 찍지 마세요" in photo.voice) // 음성도 같은 문구
        assertEquals(listOf("가드레일 밖에서 찍기", "찍지 않을게요"), listOf(photo.next, photo.decline))
        // 번호가 없어도 접수 단계는 남는다
        val none = Guides.steps(false, Personas.guest).first { it.art == StepArt.INSURER }
        assertEquals(null, none.call)
        assertTrue("번호는 보험 증권이나 대여 계약서, 앱에서 찾아 주세요." in none.body)
        val rentalNone = Guides.steps(false, Personas.fromInfo(MyInfo(rental = true))).first { it.art == StepArt.INSURER }
        assertEquals("렌터카 회사에 알려 주세요", rentalNone.title)
        assertTrue("합의나 견인" in rentalNone.body && "번호는" in rentalNone.body)
        assertTrue("대여 계약서에 있는 사고 접수 번호예요." in Guides.steps(false, haneul).first { it.art == StepArt.INSURER }.body)
        // 안내 문구에 판단 단어 없음("잘못"은 "누구 잘못인지는 지금 정하지 않아도 돼요"·거절 문구에서 판단을 미루는 말로만 씀)
        val all = Personas.all.flatMap { p -> listOf(true, false).flatMap { Guides.steps(it, p, severe = true, kind = CrashKind.PERSON) } }
        assertTrue(all.none { s -> listOf("과실", "책임", "가해", "피해자").any { it in s.title + s.body } })
    }

    @Test fun guideMatchesPersonaData() {
        val (haneul, junho, sunja) = Personas.all
        assertEquals(StepArt.CALM, Guides.steps(highway = false, haneul).first().art) // 사회초년생: 안심 단계
        assertTrue(Guides.steps(false, junho).all { it.brief }) // 사회인: 간결
        assertEquals(sunja.guardian!!.phone, Guides.steps(true, sunja).first { it.art == StepArt.GUARDIAN }.call)
        assertEquals(haneul.rentalCompany!!.phone, Guides.steps(false, haneul).first { it.art == StepArt.INSURER }.call)
        assertEquals(junho.insurer!!.phone, Guides.steps(false, junho).first { it.art == StepArt.INSURER }.call)
        // 다친 사람의 119 뒤에는 곁에 있기, "없어요"면 건너뜀
        val arts = arts(Guides.steps(false, junho))
        val at = arts.indexOf(StepArt.PERSON)
        assertEquals(StepArt.MOVE_RISK, arts[guideTarget(arts, at, 1) { it.skipped(false, false, false) }])
        assertEquals(StepArt.DONT_MOVE, arts[guideTarget(arts, at, 1) { it.skipped(true, false, false) }])
        // 참고 화면: 추정값을 "운전자 선택"이라고 쓰지 않음
        assertTrue(typeCandidates(listOf(Sample(0.0, 30.0)), 0.0, 0.0, CrashKind.VEHICLE, chosen = false).flatMap { it.facts }.none { "운전자 선택" in it })
    }

    @Test fun gpsShortensTrackAfterCrash() {
        // 충돌(3초, 30m) 뒤 속도 신호는 20m 를 더 달렸다고 하지만 GPS 로는 5m 만 움직임. GPS 는 1초마다 불규칙하게 바뀜
        val lat = 37.5; val mPerLon = 111_320.0 * kotlin.math.cos(Math.toRadians(lat))
        val log = (0..50).map { i ->
            val t = i * 0.1
            val at = (t - t % 1.0)
            val m = if (at <= 3.0) at * 10 else 30.0 + 5.0 * ((at - 3.0) / 2.0).coerceAtMost(1.0)
            Sample(t, speedKph = 36.0, lat = lat, lon = 127.0 + m / mPerLon)
        }
        val raw = trackPoses(log)
        val crash = 30
        val fixed = fitAfterCrash(log, raw, crash)
        for (i in 0..crash) assertEquals(raw[i], fixed[i]) // 충돌 전은 그대로
        assertEquals(35.0, fixed.last().x, 1.5)
        assertEquals(raw.last().yaw, fixed.last().yaw, 1e-9)
        // 멈칫·튐 없음: 모든 구간이 같은 빠르기
        val steps = fixed.zipWithNext { a, b -> b.x - a.x }.drop(crash)
        assertEquals(steps.min(), steps.max(), 1e-6)
        assertTrue(steps.min() > 0)
        assertEquals(0.0, fixed.last().y, 1e-6) // 옆으로 끌려가지 않음
        // 충돌 전 10m 미만이면 회전을 못 맞춰 길이만 맞춘다
        val still = (0..50).map { i ->
            val t = i * 0.1; val done = ((t - t % 1.0) - 0.5).coerceAtLeast(0.0)
            Sample(t, speedKph = if (i < 5) 0.0 else 36.0, lat = lat, lon = 127.0 + done / mPerLon)
        }
        val stillRaw = trackPoses(still); val c = stillRaw[5]; val fit = fitAfterCrash(still, stillRaw, 5).last()
        assertTrue(kotlin.math.hypot(fit.x - c.x, fit.y - c.y) < 8.0)
        // GPS 없음: 그대로
        assertEquals(raw, fitAfterCrash(log.map { it.copy(lat = null, lon = null) }, raw, crash))
    }

    @Test fun myInfoBecomesGuide() {
        val i = MyInfo(GuideMode.BRIEF, insurerName = " 한결 ", insurerPhone = "02-1", guardianPhone = "010-2")
        val steps = Guides.steps(false, Personas.fromInfo(i))
        assertEquals("02-1", steps.first { it.art == StepArt.INSURER }.call)
        assertTrue("한결" in steps.first { it.art == StepArt.INSURER }.callName!!)
        assertTrue(steps.none { it.art == StepArt.GUARDIAN }) // 간결은 보호자 단계를 뺀다
        val detail = Personas.fromInfo(MyInfo(guardianPhone = "010-2"))
        assertEquals("보호자", detail.guardian!!.name)
        assertEquals(StepArt.CALM, Guides.steps(false, detail).first().art)
        assertTrue(!MyInfo().filled && MyInfo(rental = true).filled)
        // 번호 없이 상세(기본값)여도 안심 단계와 용어 풀이가 나온다
        assertEquals(Group.NEWBIE, Personas.guest.group)
        assertEquals(StepArt.CALM, Guides.steps(false, Personas.guest).first().art)
        // 내 차로 바꾸면 남아 있는 렌터카 번호를 쓰지 않는다
        assertEquals(null, Personas.fromInfo(MyInfo(rentalPhone = "02-3", guardianPhone = "010-2")).rentalCompany)
        // 기록서에 없는 나이·직업을 쓰지 않는다
        assertEquals("운전자 · 자차", Personas.fromInfo(MyInfo(guardianPhone = "010-2")).summary)
        assertEquals("김하늘 · 26세 · 신입 디자이너 · 렌터카", Personas.all[0].summary)
    }

    @Test fun photoShotsByKind() {
        val car = PhotoGuide.shots(0.0, CrashKind.VEHICLE, highway = false).map { it.art }
        assertEquals(ShotArt.OVERVIEW, car.first()) // 전체 장면이 먼저
        assertTrue(ShotArt.PLATE in car && ShotArt.WHEEL in car)
        val wall = PhotoGuide.shots(0.0, CrashKind.OBJECT, highway = false).map { it.art }
        assertTrue(ShotArt.FACILITY in wall && ShotArt.PLATE !in wall)
        val person = PhotoGuide.shots(0.0, CrashKind.PERSON, highway = false).map { it.art }
        assertTrue(ShotArt.PLATE !in person)
        assertTrue(PhotoGuide.shots(0.0, CrashKind.VEHICLE, false).first().line.startsWith("차도로 나가지 않고"))
        assertEquals(ShotArt.SAFE, PhotoGuide.shots(0.0, CrashKind.VEHICLE, highway = true).first().art) // 고속도로: 안전 먼저
        assertTrue(PhotoGuide.shots(0.0, CrashKind.VEHICLE, true).none { it.art == ShotArt.MARKS || it.art == ShotArt.WHEEL || it.art == ShotArt.DAMAGE }) // 고속도로: 가까이 찍는 장면 없음
    }

    @Test fun impactDirectionAndOtherCarEstimate() {
        // 정면 충돌: 차가 뒤로(-forward) 밀림 → 충격은 앞(0°)
        assertEquals(0.0, impactFromDeg(right = 0.0, forward = -30.0), 1e-9)
        // 오른쪽에서 받힘: 차가 왼쪽(-right)으로 밀림 → 90°
        assertEquals(90.0, impactFromDeg(right = -30.0, forward = 0.0), 1e-9)
        assertEquals(180.0, impactFromDeg(right = 0.0, forward = 30.0), 1e-9)
        val own = Pose(0.0, 0.0, 0.0)
        // 정면: 앞 4.5m, 마주 보는 방향
        estimateOther(own, 0.0).let { assertEquals(4.5, it.x, 1e-9); assertEquals(0.0, it.y, 1e-9); assertEquals(Math.PI, it.yaw, 1e-9) }
        // 오른쪽: 오른쪽(y 음수) 4.5m, 왼쪽을 향함(yaw +90°)
        assertEquals(2.25, ownEdge(180.0), 1e-9); assertEquals(0.925, ownEdge(90.0), 1e-9)
        estimateOther(own, 90.0).let { assertEquals(-4.5, it.y, 1e-9); assertEquals(Math.PI / 2, it.yaw, 1e-9) }
        // 내 차 기준 앞 4m·오른쪽 1m
        offsetPose(own, 4.0, 1.0).let { assertEquals(4.0, it.x, 1e-9); assertEquals(-1.0, it.y, 1e-9) }
    }

    @Test fun accidentTypeCandidatesComeFromRecordedFacts() {
        val crashT = 108.0
        fun drive(speed: Double = 50.0, gear: String = "D", signal: (Double) -> String = { "none" }, steer: (Double) -> Double = { 0.0 }) =
            (1000..1100).map { i -> val t = i / 10.0; Sample(t, speedKph = speed, steerDeg = steer(t), signal = signal(t), gear = gear) }
        fun names(l: List<Sample>, deg: Double?, kind: CrashKind = CrashKind.VEHICLE) = typeCandidates(l, crashT, deg, kind, ZoneOffset.UTC)
        // 앞쪽 충격 + 전진
        names(drive(), 0.0).let { c ->
            assertEquals("앞차와의 추돌 유형", c.first().name)
            assertTrue(c.first().facts.any { Regex("""\d\d:\d\d:\d\d\.\d .*""").matches(it) }) // 시각으로 시작
            assertTrue(c.first().facts.any { "50 km/h" in it })
        }
        assertEquals("뒤차에 추돌된 유형", names(drive(), 180.0).first().name)
        assertEquals("진로 변경 중 접촉 유형", names(drive(signal = { if (it > 105) "L" else "none" }, steer = { -3.0 }), 90.0).first().name)
        assertEquals("교차로 회전 중 사고 유형", names(drive(steer = { 20.0 }), 90.0).first().name)
        assertEquals("후진 중 사고 유형", names(drive(speed = 5.0, gear = "R"), 180.0).first().name)
        assertEquals("차와 보행자 사고 유형", names(drive(), 0.0, CrashKind.PERSON).first().name)
        assertEquals("벽·시설물과 부딪힌 유형", names(drive(), 0.0, CrashKind.OBJECT).first().name)
        assertTrue(names(drive(), null).isEmpty()) // 충격 방향도 종류도 없으면 억지로 고르지 않음
        // 후보는 최대 3개, 판단 단어 없음
        val all = listOf(names(drive(steer = { 20.0 }), 0.0), names(drive(), 180.0), names(drive(gear = "R"), 180.0), names(drive(), 0.0, CrashKind.PERSON)).flatten()
        assertTrue(all.isNotEmpty() && all.all { it.facts.isNotEmpty() })
        assertTrue(names(drive(steer = { 20.0 }, signal = { "R" }), 0.0, CrashKind.PERSON).size <= 3)
        val banned = listOf("과실", "잘못", "책임", "가해", "피해", "부주의", "과속", "위반")
        assertTrue(all.none { c -> (listOf(c.name) + c.facts).any { s -> banned.any { it in s } } })
    }

    @Test fun inferKindUsesOnlyCarData() {
        fun at(speed: Double, gear: String = "D") = listOf(Sample(99.0, speed, gear = gear), Sample(100.0, 0.0, gear = gear))
        assertEquals(CrashKind.VEHICLE, inferKind(at(0.0), 100.0, 0.0)?.kind) // 멈춰 있을 때 충격
        assertEquals(CrashKind.VEHICLE, inferKind(at(40.0), 100.0, 180.0)?.kind) // 앞으로 가다 뒤 충격
        assertEquals(null, inferKind(at(5.0, "R"), 100.0, 180.0)) // 후진 중 뒤 충격: 차·기둥 모두 흔해 묻는다
        assertEquals(null, inferKind(at(40.0), 100.0, 0.0)) // 달리다 정면: 기록만으론 모름
        assertEquals(null, inferKind(at(40.0), 100.0, 0.0, Sighting("직전", 5.0, 0.0, 0.45f, "motorcycle"))) // 낮은 점수 오토바이는 믿지 않음
        assertEquals(CrashKind.VEHICLE, inferKind(at(40.0), 100.0, 0.0, Sighting("직전", 5.0, 0.0, 0.8f))?.kind)
        assertEquals(CrashKind.VEHICLE, inferKind(at(40.0), 100.0, 180.0)?.kind) // 뒤 충격은 사진과 무관
        assertEquals(CrashKind.PERSON, inferKind(at(40.0), 100.0, 0.0, Sighting("직전", 5.0, 0.0, 0.8f, "person"))?.kind)
    }

    @Test fun handoffDataMatchesGuideAndRoundTrips() {
        val facts = List(80) { "12:00:%02d.0 브레이크 페달 밟음 (속도 %d km/h)".format(it % 60, it) }
        for (p in Personas.all + Personas.guest) for (hw in listOf(false, true)) {
            val steps = Guides.steps(hw, p, severe = true, kind = CrashKind.PERSON)
            val d = Handoff.data(1.7e9 + 0.123, facts, "a".repeat(64), CrashKind.PERSON, chosen = false, impactDeg = 90.0, highway = hw, severe = true, p = p)
            assertEquals(steps.map { it.title }, d.steps.map { it.title }) // 차 화면과 같은 목록·순서
            assertEquals(steps.map { it.art.name }, d.steps.map { it.art })
            assertEquals(if (hw) StepArt.WALK.name else StepArt.HANDOFF.name, d.steps[d.phoneFrom].art) // 여기부터 휴대폰
            assertEquals(steps.map { it.next }, d.steps.map { it.next }) // 버튼 글자도 같다
            assertEquals(steps.map { it.call2 }, d.steps.map { it.call2 })
            assertEquals("a".repeat(16), d.hash)
            assertEquals(PhotoGuide.shots(90.0, CrashKind.PERSON, hw).map { it.title }, d.shots.map { it.title })
            val text = HandoffCodec.encode(d)
            assertTrue(listOf("과실", "책임").none { it in text })
            assertEquals(d, HandoffCodec.decode(text))
        }
        // 기록 저장 전: 사실·지문 없음
        val early = Handoff.data(1.0, emptyList(), null, CrashKind.UNKNOWN, false, null, false, false, Personas.guest)
        assertEquals(early, HandoffCodec.decode(HandoffCodec.encode(early)))
        assertEquals(null, early.hash)
        assertEquals(null, HandoffCodec.decode("tel:01012345678"))
    }

    @Test fun handoffSendsOnlyReplayWindowOfRecord() {
        val t = 1000.0
        val all = List(400) { Sample(t - 30 + it * 0.1, 36.0 + it % 7, yawRate = 0.01 * (it % 5), lat = 37.5 + it * 1e-5, lon = 127.0) } // 전 30초 + 후 10초
        val rec = CrashRecord(java.io.File("x"), all, "경위서", listOf("사실"), listOf("[영상 분석 추정] 차"), 90.0, Sighting("직전", 5.0, 1.0, 0.8f, "truck"))
        val d = Handoff.data(t, rec.facts, "a", CrashKind.VEHICLE, true, 90.0, false, false, Personas.guest, rec, "진술")
        assertEquals(t - 10, d.samples.minOf { it.t }, 0.11); assertEquals(t + 5, d.samples.maxOf { it.t }, 0.11)
        assertEquals("경위서", d.report); assertEquals("진술", d.statement); assertEquals(rec.vision, d.vision); assertEquals("truck", d.seen?.label)
        // 차와 폰이 같은 장면 계산: 받은 샘플로 만든 궤적이 차 전체 기록으로 만든 궤적과 거의 같다 (소수를 줄인 차이만)
        val back = HandoffCodec.decode(HandoffCodec.encode(d))!!
        val car = all.filter { it.t in (t - 10)..(t + 5) }
        val a = fitAfterCrash(car, trackPoses(car), car.indexOfLast { it.t <= t }); val b = fitAfterCrash(back.samples, trackPoses(back.samples), back.samples.indexOfLast { it.t <= t })
        assertEquals(a.size, b.size)
        a.indices.forEach { assertEquals(a[it].x, b[it].x, 0.2); assertEquals(a[it].y, b[it].y, 0.2) }
    }

    @Test fun reverseGoesBackward() {
        val s = List(20) { Sample(it * 0.1, 36.0, gear = "R") }
        assertEquals(-19.0, trackPoses(s).last().x, 0.5)
        // AAOS 규칙대로 후진 속도가 음수로 와도 방향은 기어로: 뒤집혀 앞으로 가지 않는다
        assertEquals(-19.0, trackPoses(List(20) { Sample(it * 0.1, -36.0, gear = "R") }).last().x, 0.5)
    }

    @Test fun steppedSignalIsSmoothed() {
        // 1초마다 튀는 속도: 이은 뒤에는 한 칸에 튀는 폭이 작다. 첫 멈춤 구간은 그대로, 끝값은 같다
        val s = List(40) { Sample(it * 0.1, speedKph = (it / 10) * 10.0, yawRate = 0.0) }
        val e = easeSteps(s)
        assertEquals(s.last().speedKph, e.last().speedKph, 1e-9)
        assertTrue(e.zipWithNext { a, b -> kotlin.math.abs(b.speedKph - a.speedKph) }.max() < 2.0)
        assertEquals(listOf(null), easeSteps(listOf(Sample(0.0))).map { it.yawRate })
    }

    @Test fun slowSignalIsNotSuddenDrop() {
        assertTrue(suddenDrop(20.0, 0.1)) // 신호가 빠를 때 0.3초에 20km/h
        assertTrue(!suddenDrop(18.0, 1.0)) // 1초 머물다 18km/h 내려감: 보통 제동
        assertTrue(suddenDrop(80.0, 1.0)) // 1초 머물다 80km/h: 충돌
        assertTrue(!suddenDrop(10.0, 0.1))
    }

    @Test fun trackPrefersGyro() {
        // 조향은 0인데 자이로가 왼쪽으로 돌았다고 하면 왼쪽으로 휜다
        val s = List(20) { Sample(it * 0.1, 36.0, yawRate = 0.5) }
        assertTrue(trackPoses(s).last().y > 0)
    }

    @Test fun trackTurnsLeftForNegativeSteer() {
        val (_, y) = track(log).last()
        assertTrue("좌회전이면 y > 0 이어야 함: $y", y > 0)
    }
    @Test fun noExchangeForSoloWallCrash() {
        assertTrue(Guides.steps(false, kind = CrashKind.OBJECT).none { it.art == StepArt.EXCHANGE })
        assertTrue(Guides.steps(true, kind = CrashKind.OBJECT).none { it.art == StepArt.EXCHANGE })
        assertTrue(Guides.steps(false, kind = CrashKind.VEHICLE).any { it.art == StepArt.EXCHANGE })
    }
}
