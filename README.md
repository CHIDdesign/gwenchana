# 괜차나 (Gwenchana)

사고 순간 옆에 있어 주는 "제3의 조수석 탑승자" 앱입니다. Pleos Connect(현대자동차그룹 차량용 앱 플랫폼)에서 돌아갑니다.
앱은 **판사가 아니라 증인**입니다. 과실을 판단하지 않고, 차가 기록한 사실만 시각과 함께 남깁니다.

## 무엇을 하나

- **사고 감지**: 차량 데이터와 가속도 센서로 충격을 알아챕니다.
- **대처 안내**: 정차를 확인한 뒤 단계별로 안내합니다. 사용자에 맞게 상세·간결·음성으로 바뀝니다.
- **사실 기록**: 전 30초, 후 10초 기록을 위조 확인값(SHA-256)과 함께 저장하고, 3D 사고 재현과 사실 기록서(PDF)를 만듭니다.
- **휴대폰 이어받기**: 차에서 하던 안내를 휴대폰 알림으로 이어 가고, 지난 사고를 카드로 모아 접수·보내기까지 챙깁니다.

## 화면

| 차: 첫 화면 | 차: 부딪힌 대상 | 차: 사고 기록 |
|---|---|---|
| ![첫 화면](docs/screens/car_first.jpg) | ![대상 고르기](docs/screens/car_kind.jpg) | ![차 홈](docs/screens/car_home.jpg) |

| 휴대폰: 홈 | 휴대폰: 일지 | 휴대폰: 기록 보내기 |
|---|---|---|
| ![홈](docs/screens/phone_home.jpg) | ![일지](docs/screens/phone_log.jpg) | ![보내기](docs/screens/phone_send.jpg) |

화면의 시각·사고 내용은 시험용 값입니다.

## APK 위치

| 파일 | 내용 |
|---|---|
| [`apk/gwenchana_car_release.apk`](apk/gwenchana_car_release.apk) | 차 앱 (Pleos Connect 에뮬레이터용, release) |
| [`apk/gwenchana_phone_release.apk`](apk/gwenchana_phone_release.apk) | 휴대폰 앱 (release) |

같은 파일을 [Releases](../../releases)에도 올립니다. 패키지 이름은 개발 때 정한 `com.askthecar`입니다.

## 실행 방법 (심사용)

### 1. Pleos Connect 에뮬레이터

Pleos 개발자 문서대로 Android 14(API 34) Pleos Connect 시스템 이미지로 기기를 만듭니다. 개발에 쓴 설정은 2560×1440, 160dpi, RAM 4GB, x86_64, Cold boot, AVD 이름 `Pleos_Connect`입니다. 켜고 홈 화면이 뜰 때까지 기다립니다.

### 2. 차 앱 설치

에뮬레이터 이름이 다르면 `-s` 값을 바꾸세요.

```bash
adb -s emulator-5554 install -r -g apk/gwenchana_car_release.apk
```

사고 화면이 다른 앱 위에 뜨도록 허용합니다. 설치할 때마다 한 번씩 합니다.

```bash
adb -s emulator-5554 shell appops set --user 10 com.askthecar SYSTEM_ALERT_WINDOW allow
```

앱 목록에서 **괜차나**를 한 번 열어 동의합니다. 그 뒤로는 뒤에서 기록하며 대기합니다.

### 3. 사고 일으켜 보기

약 3.5g 충격을 넣고, 바로 값을 되돌립니다. "다치셨나요?" 화면이 뜨고, 고르는 대로 안내와 3D 재현, 사고 기록으로 이어집니다. 안내 단계의 전화 버튼을 누르면 실제 전화 앱이 열리니 주의하세요.

```bash
adb -s emulator-5554 emu sensor set acceleration 0:9.81:34
```

```bash
adb -s emulator-5554 emu sensor set acceleration 0:9.81:0
```

### 4. 휴대폰 앱까지 이어 보기

**현재 시제품의 제한**: 차와 휴대폰을 잇는 서버는 아직 PC에서 켜는 시제품(HTTP, 인증 없음)입니다. release APK는 평문 통신을 막으므로 위 APK끼리는 연결되지 않습니다. 연결을 보려면 debug로 빌드합니다. https 서버는 2차 발표 전에 준비합니다.

```bash
./gradlew :app:assembleDebug :phone:assembleDebug
```

```bash
python game/bridge/sync_server.py --one-account
```

휴대폰 에뮬레이터(`emulator-5556`)에 `phone-debug.apk`를 설치하고 로그인합니다. 서버 주소는 `http://10.0.2.2:8767`(실제 휴대폰은 `http://PC주소:8767`)입니다. 로그인 목록의 운전자 3명과 "가족"은 시제품용 가상 계정입니다.

## 테스트 환경과 필요한 설정

| 항목 | 값 |
|---|---|
| 검증한 기기 | Pleos Connect 에뮬레이터(Android 14 / API 34, x86_64, 2560×1440 160dpi, RAM 4GB), 운전자 사용자 10. 휴대폰은 Android 에뮬레이터 |
| 앱 | `com.askthecar` 0.1.0, minSdk 28, target/compile 35, ABI arm64-v8a·x86_64 |
| 빌드 도구 | JDK 17, Gradle 8.10.2(wrapper), AGP 8.8.2, Kotlin 2.1.21 |
| 차 앱 권한 | Pleos `CAR_DRIVE`·`CAR_LIGHTS`·`CAR_SAFETY`·`PRIVATE_CAR_SAFETY`·`CAR_DOORS`·`CONTROL_CAR_DOORS`·`TTS_SERVICE`·`STT_SERVICE`·`FUSED_LOCATION`·`NAVI_ROUTE`, AAOS `CAR_SPEED`·`CAR_POWERTRAIN`, 마이크, 카메라, 통화 상태, 포그라운드 서비스, 알림, 다른 앱 위 표시, 부팅 후 시작 |
| 네트워크 | 감지·안내·기록은 네트워크 없이 동작합니다. 휴대폰 연동은 동기화 서버가 필요합니다 |

**실제 차량에서는 아직 시험하지 않았습니다.** 일반 앱에 가속도·자이로 센서와 카메라가 열려 있는지는 미확인입니다. release는 공개 시험용으로 디버그 키로 서명했습니다.

### 공개 APK에서 꺼져 있는 것

APK를 풀면 안에 든 키가 보이기 때문에, 공개 APK에는 키를 넣지 않습니다.

| 기능 | 공개 APK | 켜는 방법 |
|---|---|---|
| 안내 음성 (Pleos TTS) | 소리 없음, 화면 안내는 그대로 | 자기 키로 빌드 |
| 경위서 문장 다듬기 (Claude) | 사실 목록을 그대로 표시 | 중계 서버를 배포하고 빌드 |

### 직접 빌드

프로젝트 맨 위에 `secrets.properties`를 만듭니다. 이 파일은 git에 올라가지 않습니다. 없어도 `-Ppublic` 빌드는 됩니다.

```properties
pleos.clientId=발급받은 값
pleos.clientSecret=발급받은 값
relay.url=배포한 중계 서버 주소(없으면 비움)
relay.token=중계 서버 토큰(없으면 비움)
```

JDK 17과 Android SDK 35가 필요합니다. `local.properties`에 `sdk.dir`을 적거나 `ANDROID_HOME`을 설정합니다.

```bash
./gradlew :app:assembleRelease :phone:assembleRelease -Ppublic
```

```bash
./gradlew :app:testDebugUnitTest :phone:testDebugUnitTest
```

## 사용한 Pleos SDK · AAOS API

| 쓰는 곳 | API |
|---|---|
| 조향·브레이크·지시등·에어백·크루즈·경고등·트렁크 | Pleos `Vehicle` 2.3.2 |
| 속도·기어 | AAOS `CarPropertyManager` |
| 위치 | Pleos `FusedLocation` 1.1.3 |
| 도로 제한속도 | Pleos `NaviHelper` 3.0.5 |
| 음성 안내 / 운전자 진술 | Pleos `TextToSpeech` 3.3.1 / `SpeechToText` 3.3.1 |
| 충격 감지 보조 | Android 가속도·자이로 센서 |
| 사고 순간 사진 | Android Camera2 |

## 구조

| 폴더 | 내용 |
|---|---|
| `apk/` | release APK |
| `app/` | 차 앱. `app/src/debug`는 게임 시험 전용이라 release APK에 들어가지 않습니다 |
| `phone/` | 휴대폰 앱 |
| `shared/` | 두 앱이 함께 쓰는 코드 |
| `game/` | PC 시험 도구: BeamNG 모드, 브리지, 동기화 서버, 자동 사고 시험 |
| `relay/` | 경위서 문장을 다듬는 중계 서버 (Cloudflare Worker) |
| `docs/` | 기획서, 실차 대응표, 조사 보고서 |

## 시험 방법

앱은 실제 차에서 Pleos SDK와 안드로이드 표준 API로 얻는 값만 씁니다. 사고를 실제로 낼 수 없어서, 개발 때는 BeamNG.drive의 주행 값을 에뮬레이터 차량 신호로 넣어 시험했습니다. 방법은 [README_TEST.md](README_TEST.md), 값별 실차 출처는 [docs/실차_대응표.md](docs/실차_대응표.md)에 있습니다.

## 라이선스와 고지

사용한 오픈소스와 모델은 [NOTICE.md](NOTICE.md)를 보세요.

BeamNG.drive는 BeamNG GmbH의 게임이며, 이 프로젝트는 BeamNG와 제휴하지 않았습니다. 정식 구매한 게임을 시험 환경으로 썼고, 이 저장소와 APK에는 게임 파일이 들어 있지 않습니다. `game/beamng/mod`는 직접 작성한 모드입니다.
