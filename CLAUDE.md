# 괜차나 (Gwenchana) — 팀 인수인계

이 파일은 이 저장소에서 일하는 Claude와 팀원을 위한 안내다. 처음 왔으면 끝까지 읽고 "지금 상태"와 "다음 할 일"부터 이어 간다.
설치·사용 안내는 [README.md](README.md), 게임 시험 순서는 [README_TEST.md](README_TEST.md), 오픈소스 고지는 [NOTICE.md](NOTICE.md).

## 무엇을 만드는가

현대자동차그룹 차량용 앱 공모전(Pleos App Market) 출품작. 사고 순간 차가 "제3의 탑승자"처럼 곁에 있어 주는 앱이다. **판사가 아니라 증인**: 과실을 판정하지 않고, 차가 기록한 사실만 시각과 함께 보여 준다.

두 앱이 한 짝이다. **차는 입구, 휴대폰은 본거지.**
- 차 앱(`app/`): 평소에는 뒤에서 기록. 충격을 감지하면 "다치셨나요?" → 대상 고르기 → 단계별 안내 → 3D 재현 → 사고 기록.
- 휴대폰 앱(`phone/`): 사고 알림, 안내 이어 보기, 사고 카드 지갑, 차에게 묻기, 보험 접수 전화 원고, 보내기(기록서 PDF), 두 차 기록 연결, 가족에게 맡기기.
- 돋보이게 할 점: 사고 난 사람의 유형별 맞춤(사회초년생은 쉽고 차근차근, 바쁜 직장인은 빠르고 깔끔, 고령자는 음성·가족). 목표는 단계 수·순서·화면·말투가 전부 달라지는 것이지만, 지금 차 안내의 실제 차이는 작다("알려진 문제" 참고).

일정: **1차 접수 2026-11-15**(접수양식 + 서명 동의서 필수, 시연 영상·GitHub·release APK 가산), **2차 PT 2026-12-03~04**(에뮬레이터 시연).

기획의 기준 문서: [docs/기획서.md](docs/기획서.md), [docs/아이디어 정리 (최신).md](docs/아이디어%20정리%20(최신).md), [docs/reports/앱 전체 흐름 기획.md](docs/reports/앱%20전체%20흐름%20기획.md). 코드와 다르면 코드가 현재 상태다.

## 반드시 지킬 규칙

1. **실제 차 기준으로 만든다.** 앱은 실제 차에서 Pleos SDK·안드로이드 표준 API로 얻는 값만으로 동작해야 한다. BeamNG 게임은 그 데이터를 대신 만드는 시험장이다. 게임만 아는 값(손상도, 상대 차 정답 등)을 앱의 판단·화면에 넣지 않는다. 채점은 PC에서만 한다. 새 입력을 쓸 때마다 [docs/실차_대응표.md](docs/실차_대응표.md)에 "실차 출처 / 게임 대체"를 적는다.
2. **게임 코드는 `app/src/debug`에만.** 본체는 `Sim.link`(실차·release에서는 null)만 안다. `app/src/debug`·`app/src/testDebug`·`game/`을 지워도 release가 빌드되어야 한다.
3. **증인 원칙.** 과실·책임 판정 금지. 수치는 타임스탬프와 함께, 영상·사진 추정은 "[영상 분석 추정]", LLM이 낸 숫자는 로그와 대조한다(`Validator`). 불리한 기록도 그대로 보여 주고 보낼지만 사용자가 고른다.
4. **거짓 완료 금지.** 막히면 막혔다고 말한다. "에뮬레이터·시험으로 확인함"과 "만들었지만 미확인"을 나눠 보고하고, 확인 못 한 사실은 "미확인"이라고 적는다. 가짜 대체물을 진짜처럼 만들지 않는다.
5. **화면은 코드 전에 시안.** 핵심 화면을 고치기 전에 구성안 2개를 그림으로 보여 주고 고르게 한다. 고른 뒤에는 시안과 에뮬레이터 캡처를 나란히 놓고 맞춘다. 빌드 통과·겹침 없음·디자인 값 일치는 완료 기준이 아니다. 화면마다 주인공을 정하고 실제 상황(충격 받은 운전자, 전화하는 사람, PDF 받는 담당자)으로 검토한다.
6. **말투.** 팀 대화와 보고는 쉬운 한국어. 코딩 기초 수준의 사람도 읽을 수 있게 쓴다.
7. **공개 저장소다.** 실명, 계정, 개인 경로, 비밀 키, 다른 팀 자료를 커밋하지 않는다(접수양식의 실명 금지 조항). 비밀 값은 루트 `secrets.properties`(git 제외)에만.
8. **시험 안전.** 에뮬레이터에서 119·112·1588-2504·전화 걸기 버튼을 누르지 않는다. 사람이 게임을 운전 중이면 게임에 키를 보내지 않는다.
9. **작업을 끝낼 때 이 파일의 "지금 상태"·"다음 할 일"·"알려진 문제"를 날짜·검증 여부와 함께 고친다.**

## 그림·아이콘 규칙

- 사람·장면 그림은 코드(SVG)로 그리지 않는다. AI 잉크 일러스트와 영상을 쓴다. 최종본은 `app/src/main/assets/guide/`(야간판은 `night/`). 도형을 짜 맞춘 사람, 윤곽만 있는 사람은 거절됐다.
- 아이콘·작은 도식: 48 격자, 선 1.2, 둥근 끝, 빨강은 뜻이 있을 때만. 면으로 채운 덩어리 아이콘은 거절됐다. 차는 위에서 본 모양 말고 옆모습.
- 다크 모드 그림은 색 반전·음영 처리 금지. 사람·차를 잘라 어두운 바닥에 붙인 별도 파일을 쓴다.
- 앱 그림은 정보가 있을 때만(비상등 위치, 트렁크, 촬영 위치). 장식 그림은 신뢰를 깎는다.
- 안내 화면: 그림이 바닥 전체, 글은 그림의 빈 곳. 3D 차·재현은 `crash3d/`.
- 현대·Pleos 로고는 쓰지 않는다(규정에 허락이 없고 제3자 권리 침해 시 수상 취소 조항이 있음). 이름 근처에 Hyundai·Pleos·Gleo 단어 금지, 방패·체크·저울·법봉 형태 금지.
- 색: 사고 신호는 빨강 `#D32016` 하나. 브랜드 포인트는 라일락 `#DCC8FF`, 깊은 청록 `#0B5F58`.

## 폴더

| 위치 | 내용 |
|---|---|
| `시작.cmd` / `끝내기.cmd` / `시작-실제폰.cmd` | 시연·시험 한 번에 켜기 / 끄기 / 휴대폰 에뮬레이터 없이 |
| `app/` | 차 앱. 게임 연결은 `src/debug` |
| `phone/` | 휴대폰 앱 |
| `shared/` | 두 앱 공용(디자인 토큰, 3D, PDF, 동기화 데이터) |
| `relay/` | Claude 중계 Cloudflare Worker(앱 → Claude, 경위서 다듬기·사진 설명). **아직 배포 안 됨** |
| `game/bridge/` | PC 도구: BeamNG 브리지, 동기화 서버, 사고 시험 묶음 |
| `game/beamng/mod/` | BeamNG 모드(주행 값 UDP, 사진·리셋) |
| `scripts/` | 따로 켤 때 쓰는 실행 파일 |
| `apk/` | 공개 release APK(`-Ppublic`로 다시 빌드해 교체) |
| `tools/` | `env.cmd`·`run.cmd`만 저장소에 있음. JDK·SDK·에뮬레이터는 직접 넣는다(아래) |
| `docs/` | 기획, SDK 메모, 디자인 메모, 실차 대응표, `reports/` 조사 보고서 |
| `design/` | 차 모델 생성 스크립트, 확정 아이콘, 재설계 시안 |

주요 코드:

| 파일 | 하는 일 |
|---|---|
| `app/.../CarService.kt` | 포그라운드 서비스. 사고 때 화면 띄움 |
| `app/.../Crash.kt` | `CrashFlow`(사고 상태), `CrashWatcher`(10Hz 기록, 감지: 에어백 / 2.5g 이상 / 0.3초 내 15km/h 급감 + 1.5g 이상 → 10초 뒤 전 30초 + 후 10초 저장, SHA-256), `Voice`, `Statement`, `Relay` |
| `app/.../CrashActivity.kt` | 첫 화면 → 대상 → 안내 단계 → 재현 → 기록 |
| `app/.../CarHome.kt` | 차 홈(지난 사고 목록), 기록 띠, 대상 고르기 |
| `app/.../Guide.kt` | 안내 단계 정의(고속도로/시내, 유형별). 공식 수칙 근거: `docs/reports/사고 수칙 공식 원문 재확인.md` |
| `app/.../Sources.kt` | `CarState`, `PleosSource`(Vehicle SDK·AAOS 속도/기어·위치·NaviHelper) |
| `app/.../Report.kt` | `FactTimeline`, `Validator`(숫자·금지어 대조), `FactSheet` |
| `app/.../Vision.kt` | MediaPipe 차량 인식(사진 1장) |
| `app/.../Sim.kt` | 게임 연결 지점. release에서는 null |
| `shared/.../Recorder.kt` | 샘플, 링버퍼, 궤적 계산(`fitAfterCrash` 등). 고치면 `game/bridge/crash_suite.py`의 파이썬 사본도 같이 |
| `shared/.../ClaimPdf.kt` | 보험사 첨부용 사실 기록서 PDF |
| `shared/.../Theme.kt` | Pleos 디자인 토큰, 버튼 |
| `shared/.../Ask.kt` | 차에게 묻기(AI 없이 기록에서 계산) |
| `phone/.../Wallet.kt` | B안 홈, 사고 카드. 사고 여러 건 저장(`cases/<시각>.json`)은 `Sync.kt` |
| `phone/.../CaseHome.kt`, `CasePages.kt`, `ClaimShare.kt`, `LinkScreen.kt` | 사건 홈·전화 원고, 보내기·맡기기, PDF 공유, 두 차 연결 |
| `game/bridge/launcher.py` | `시작.cmd`가 부르는 실행기 |
| `game/bridge/beamng_bridge.py` | 게임 UDP → Virtual Console·센서·보조 통로 |
| `game/bridge/sync_server.py` | 시제품 동기화 서버(HTTP 8767) |
| `game/bridge/crash_suite.py`, `crash_test.py` | 자동 사고 시험 |

## 새 PC에서 처음 설치 (Claude가 이 순서대로 진행)

**저장 위치는 아무 드라이브나 된다.** 개발자는 외장 SSD에서 작업했지만 코드와 스크립트에 드라이브 고정값은 없다. `git clone` 뒤 어느 폴더든 그대로 쓴다. 공간은 설치에 약 30GB(Pleos 이미지 19GB, 휴대폰 이미지 3.5GB, Android Studio·JDK·에뮬레이터·Gradle 캐시), 에뮬레이터를 쓰면 데이터가 약 18GB 더 늘어난다(개발 PC 기준). 인텔/AMD Windows만 시험했다.

저장소에 없는 것: JDK·Android SDK·에뮬레이터 이미지·`secrets.properties`·BeamNG. 전부 `tools/` 아래에 넣는다(git 제외).

**사람만 할 수 있는 일은 먼저 부탁한다**: Windows 기능 켜기와 재부팅, Android Studio의 라이선스 동의(Pleos 이미지는 42dot SDK 라이선스), Steam 로그인, 비밀 키 입력.

1. Windows 기능 "Windows 하이퍼바이저 플랫폼"(WHPX) 켜고 재부팅. Python 3 설치(`winget install Python.Python.3.12`, 표준 라이브러리만 쓴다). `relay`를 배포할 사람은 Node.js도.
2. JDK 17(Temurin) zip을 풀어 `tools/jdk17/bin/java.exe`가 되게 둔다.
3. `tools\env.cmd`를 한 번 실행한다. 드라이브 루트에 `askthecar-tools` 정션과 `local.properties`, 에뮬레이터 2대의 설정(`scripts/avd/*.config.ini`를 `tools/android-user/avd/`로 복사)이 생긴다. 에뮬레이터를 Android Studio로 새로 만들지 않는다.
4. Android Studio를 설치하고(`tools/android-studio`에 두면 `scripts\start-studio.cmd`로 열림) Settings > Android SDK에서 **SDK 위치를 `<드라이브>:\askthecar-tools\sdk`** 로 정한다. 쉼표 없는 정션 경로여야 한다.
   - SDK Update Sites에 추가: `https://nexus-playground.pleos.ai/repository/raw-releases/connect-avd/2.x/crp-sys-img.xml` (이 개발 PC가 실제로 쓴 주소)
   - SDK Platforms(Show Package Details 켜기): Android 15 **Android SDK Platform 35**, **Google APIs Intel x86_64 Atom System Image**(API 35) / Android 14 **Pleos Connect v2.1.0 APIs Intel x86_64 Atom System Image**
   - SDK Tools: **Build-Tools 35.0.0, Android Emulator, Platform-Tools, Command-line Tools (latest)**
   - `sdkmanager --list`에는 Pleos 이미지가 안 나왔다(개발 PC에서 확인). 화면에서 설치한다.
   - 설치 확인: `tools\sdk\system-images\android-34\pleos_car_emulator\x86_64\system.img`, `tools\sdk\system-images\android-35\google_apis\x86_64\system.img`. 경로가 다르면 `scripts/avd/*.config.ini`의 `image.sysdir.1`을 실제 경로로 고친다(`tools/android-user/avd/<이름>.avd/config.ini`도 같이).
5. 하드웨어 가속 확인: `tools\run.cmd emulator -accel-check`. "hypervisor driver is not installed"면 1번이 안 된 것이다.
6. `secrets.properties`를 루트에 만든다(항목: `pleos.clientId`, `pleos.clientSecret`, `anthropic.apiKey`, `relay.url`, `relay.token`, `obs.password`, `sync.url`). 없어도 빌드와 화면 시험은 되고 음성·문장 다듬기만 꺼진다. 키는 대표에게 안전한 경로로 받거나 자기 것을 쓴다.
7. 빌드: 아래 "빌드와 실행"의 debug 명령.
8. 첫 부팅: `explorer.exe "<프로젝트>\scripts\start-emulator.cmd"`(Claude 도구로 바로 띄우면 명령이 끝날 때 같이 꺼진다). 첫 부팅은 몇 분 걸린다. `tools\run.cmd adb -s emulator-5554 shell getprop sys.boot_completed`가 `1`이면 끝. "USB 디버깅 허용" 창이 뜨면 화면을 눌러 허용한다(`adb` 연결 전에는 `adb shell input tap`도 안 되니 사람에게 부탁).
9. 시간대(기본이 미국): `tools\run.cmd adb -s emulator-5554 shell cmd alarm set-timezone Asia/Seoul`. 휴대폰(`emulator-5556`)도 같게. 휴대폰은 UTC로 돌아간 적이 있어 시각이 이상하면 다시 한다.
10. 게임 없이 시험: README_TEST.md의 연습 사고 신호. 게임까지 쓸 때: BeamNG를 Steam으로 설치 → 1회 실행·종료 → 옵션 > 프로토콜 > 기타 프로토콜(`protocols_others_enabled`) 켜기(simple menu에서는 안 보임) → 브리지가 시작할 때 모드를 연결한다. OBS(다각도 영상, 선택)는 웹소켓 포트 4455를 켜고 비밀번호를 `secrets.properties`에.

가장 빠른 길은 대표의 `tools/` 폴더를 통째로 복사하는 것이다(이때도 `tools\env.cmd`가 드라이브 문자를 다시 맞춘다).

**막힐 때**

| 증상 | 원인·해결 |
|---|---|
| 차 앱이 차량 값을 못 받음 | Quick Boot로 켜면 VHAL이 꺼진다. 항상 cold boot(`scripts/start-emulator.cmd`, config `fastboot.forceColdBoot=yes`) |
| 빌드·에뮬레이터가 경로 오류 | 실제 폴더 경로 대신 `<드라이브>:\askthecar-tools` 정션을 쓴다. 쉼표가 있는 폴더는 QEMU와 Kotlin Compose 플러그인을 깨뜨린다 |
| 도구가 다른 프로젝트 것으로 바뀜 | `tools\env.cmd`가 실행마다 정션을 다시 만든다. 같은 드라이브에 사본이 둘이면 서로 덮어쓴다. 드라이브당 하나만 |
| AVD를 못 찾음 | `tools\env.cmd`를 다시 실행(포인터 `.ini`를 현재 드라이브로 다시 씀) |
| 앱 파일·설정이 안 보임 | Pleos 운전자는 사용자 10이다. `--user 10` |
| adb가 기기를 못 고름 | 2대면 `-s emulator-5554` 또는 `-s emulator-5556` 필수 |
| 브리지가 콘솔 인증 실패 | `%USERPROFILE%\.emulator_console_auth_token`은 첫 부팅 때 생긴다 |
| cmd 파일이 깨짐 | cmd는 ASCII + CRLF. 한글을 넣지 않는다 |

## 빌드와 실행

```
tools\run.cmd .\gradlew.bat :app:assembleDebug :phone:assembleDebug
tools\run.cmd .\gradlew.bat :app:testDebugUnitTest :phone:testDebugUnitTest
tools\run.cmd .\gradlew.bat :app:assembleRelease :phone:assembleRelease -Ppublic
```

- 스택: AGP 8.8.2, Kotlin 2.1.21, Gradle 8.10.2, JDK 17, compile/target 35, min 28. Pleos Maven `https://nexus-playground.pleos.ai/repository/maven-releases/`.
- **공개 APK는 반드시 `-Ppublic`**(relay·Pleos 키가 빠짐). 결과물은 `apk/gwenchana_car_release.apk`, `apk/gwenchana_phone_release.apk`로 복사한다(GitHub Releases v1.0.0은 아직 없음, 차 APK 90MB. 공식 규정 파일명은 `apk/팀명_앱이름_release.apk`, 팀 이름 확정 뒤 변경). release끼리는 평문 통신이 막혀 휴대폰 연동이 안 된다(https 서버 전까지 debug만). 빌드 뒤 dex에서 비밀값과 게임 클래스(`SimProfile`·`ScreenCover`·`NaviActivity`·`TestModeSource`·`BeamNgLink`·`SimReceiver`), 수신 신호(`RESET`·`TEST_CRASH`)가 없는지 검사한다.
- 시연: 앱 빌드 → BeamNG 켜고 자유 주행 → `시작.cmd`(에뮬레이터 2대, 앱 설치, 브리지, 동기화 서버). 게임 없이: 차 앱에 `com.askthecar.TEST_CRASH` 신호(README_TEST.md).
- 자동 시험: 게임을 `-enablemcp`로 켜고 `cd game\bridge` → `python crash_suite.py` 또는 `python crash_test.py`.
- 차 에뮬레이터 `emulator-5554`(앱 파일은 사용자 10), 휴대폰 `emulator-5556`. adb는 PATH에 없다: `tools\run.cmd adb -s emulator-5554 ...`(둘 다 켜져 있으면 `-s` 필수). 앱 파일 읽기: `adb exec-out run-as com.askthecar --user 10 cat files/...`. `adb screencap`은 검게 나온다 → `adb emu screenrecord screenshot <폴더>`. Git Bash에서는 `export MSYS_NO_PATHCONV=1`.
- 에뮬레이터는 Claude 도구로 직접 띄우면 명령이 끝날 때 같이 꺼진다 → `explorer.exe "<cmd 경로>"`.
- 차 에뮬레이터는 몇 시간 뒤 멈춘 적이 있다. 시연 녹화 전 재시작.

## 지금 상태 (2026-11-10)

- 흐름 재설계 **B안(사고 카드 지갑) 구현 끝.** 두 앱 debug 빌드·단위 테스트 통과(차 35·휴대폰 32개). 연습 사고로 차 첫 화면·휴대폰 알림·안내 화면 확인.
- 이름 **괜차나 / Gwenchana**, 아이콘 ㄱㅊ 모노그램 R4 적용. 패키지 `com.askthecar`는 그대로 둔다(일부러). 상표: 완전일치 0건이지만 법률 판단 아님, 출원·출시 전 변리사 검토 필요.
- 안내 순서·문구는 공식 원문을 재확인해 고침(`docs/reports/사고 수칙 공식 원문 재확인.md`). 고속도로 수정안 일부는 공식 문구가 아닌 추론이므로 발표에서 "공식 안내"라고 하지 않는다. 삼각대·못 나오는 운전자 문구는 변호사·119 확인 전 초안.
- 실제 BeamNG 시연 검증: 시동·내비·사고·안내·재현·기록·휴대폰·리셋 동작 확인(11-10, 재설계 반영 전 빌드 기준).
- 동기화 서버는 **PC 시제품**(`sync_server.py`, 인증 없음, 평문 HTTP). 1차 제출까지만 허용된 예외이고 README에 밝혀야 한다.
- 운영국 답(11-04): ADAS SDK 사용 불가. Connect SDK는 매니페스트 권한 선언이면 에뮬레이터 개발 가능. 추가 안내 대기: 대학생 신청, TTS 401, 나머지 SDK, 센서·카메라·초음파·CODA API.

공개 주소: https://github.com/CHIDdesign/gwenchana (2026-11-10 공개). 작성자는 임시로 `Gwenchana Team`.

## 다음 할 일 (우선순위)

1. 휴대폰 홈의 사고 카드 화면 확인. (release 빌드·dex 검사는 게임 폴더를 지운 상태로 2026-11-10 통과)
2. 접수양식 초안 마무리(11/15 전): 팀 이름, 앱 이름 반영, 수치 원문 확인.
3. 시연 영상 촬영(`끝내기.cmd` 뒤 새로 켜고, 지시등 끄고).
4. release 전용 서명 키 만들기(지금은 디버그 키), `apk/팀명_앱이름_release.apk` 규칙, 실명 아닌 팀명 확정.
5. `relay` 배포: `cd relay` → `npx wrangler login` → `npm run deploy` → 나온 주소를 `relay.url`에.
6. AI 생성 그림의 공모전 규칙 미확인. 그림의 AI 표시를 지운 적이 있어 운영국 확인 권장.
7. **12/03 전**: 실제 https 동기화 서버(인증·푸시), 기본 주소 교체, release APK로 끝까지 확인. 알림은 FCM 필요.
8. **12/03 전**: 말로 답하기, 통화 도우미, 의심 등급, 고속도로 재설계, 합의까지 진행 표시 완성.
9. 수상·사업화 전 BeamNG 라이선스 문의(EULA 4.2 상업 이용 금지, media@beamng.com).

## 알려진 문제 / 미확인

- **실차 미시험.** 일반 앱에 가속도·자이로·Camera2가 열려 있는지, 센서 축 방향, 포그라운드 서비스·다른 앱 위 표시의 앱마켓 심사 통과 여부 미확인. 공식 샘플이 Telltales를 차단 목록에 둬서 비상등 감지가 실차에서 막힐 수 있다.
- 미확인 동작: 실제 통화 뒤 복귀(블루투스 없는 에뮬레이터), 소리(TTS는 호출 로그만), 실제 휴대폰, 다크 모드와 글자 1.3배(새 화면들), 실제 게임 R 키, 모션 움직임.
- 합성 에어백·저속 충격은 게임 손상값 기준이라 들쭉날쭉하다(`--damage-jump`는 `시작.cmd`로 넘길 수 있고, `--impact-jump`는 브리지를 직접 켤 때만). 게임 전용 값이라 앱에는 영향 없음.
- 지시등 켠 채 사고 나면 사각지대 카메라 뒤에서 팝업이 눌리지 않는다. 시연 때 지시등 끄기.
- 재현 궤적: 충돌 전 미끄러짐은 보정 안 함. 충돌 뒤는 GPS 거리로 길이만 맞춤.
- 사진 인식은 차를 자주 놓친다. 대상은 운전자가 고르는 흐름으로 보완.
- 왼쪽 패널의 차 뒤 깨짐, 아래 흰 네모는 Pleos `drivingview` 그림이다(앱이 그리지 않음).
- 그림의 차가 아이오닉 5와 매우 비슷하다(엠블럼 없음). 공모전 권리 조항 확인 필요.
- 디버그 APK는 약 98MB. release에서 x86_64를 빼면 2차 에뮬레이터 시연이 안 된다.
- 새 PC 설치 안내(2026-11-10): 빈 폴더·다른 드라이브에서 `tools\env.cmd`의 정션·`local.properties`·에뮬레이터 설정 생성과 `-list-avds`까지만 시험했다. Android Studio 설치부터 첫 부팅까지 처음부터 해 본 적은 없다. 막히면 여기를 고쳐 다음 사람에게 남긴다.
- 유형별 맞춤이 약하다(2026-11-10 코드 대조): 차 안내는 세 방식의 순서·문구가 같고, 간결은 가족 연락·안심 단계만 빠지며(`Guide.kt` `brief`), 음성은 본문을 숨긴다. 내세우는 차별점이므로 코드를 키우거나 발표 문구를 낮춰야 한다.
- 휴대폰에 받은 사고 사본·사진은 날짜로 지우는 코드가 없다(로그아웃·철회 때만). 동의문 `Consent.kt`의 "30일 뒤 자동 삭제"와 범위가 다르므로 고쳐야 한다.
- 앱 안 일부 문자열·알림 제목에 옛 이름이 남았는지 공개 전 `Ask the Car`로 검색.
