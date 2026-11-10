# Pleos SDK 사실 메모 (2026-10-29, document.pleos.ai에서 확인)

- Maven: `https://nexus-playground.pleos.ai/repository/maven-releases/` (로그인 불필요)
- `ai.pleos.playground:Vehicle:2.3.2`, `TextToSpeech:3.3.1`, `FusedLocation:1.1.3`, `NaviHelper:3.0.5`
- 샘플 앱 빌드: JDK 17, Gradle 8.10.2, AGP 8.8.2, Kotlin 2.0.0, compile/target 35

## Vehicle (`ai.pleos.playground.vehicle.Vehicle(context)`)
- `initialize()` / `release()`, 도메인 `getSteeringWheel()` `getBrake()` `getTurnSignal()` `getSafety()` `getDrivingMode()`
- 리스너 패키지 `ai.pleos.playground.vehicle.listener`, 모든 리스너에 `onFailed(e: Exception)`
- 조향: `registerSteeringWheelAngle(SteeringWheelAngleListener)` → `onSteeringWheelAngleUpdated(angle: Float?)`. **앞바퀴** 각도(°), 좌 음수. 권한 `CAR_DRIVE`
- 브레이크: `registerBrakePedalStatus(BrakePedalStatusListener)` → `onBrakePedalStatusUpdated(status: Boolean?)`. `CAR_DRIVE`
- 지시등: `getTurnSignal().registerTurnSignalState(TurnSignalStateListener)` → `onTurnSignalStateUpdated(state: TurnSignalValue?)`, enum `OFF/RIGHT/LEFT` (`...vehicle.constant.area`). 권한 `CAR_LIGHTS`
- 에어백: `registerSeatAirbagDeployed(SeatArea(SeatValue.ROW_1_LEFT), SeatAirbagDeployedListener)` → `onSeatAirbagDeployUpdated(deployed: Boolean?)` (철자 Deploy). `CAR_SAFETY`
- 권한 문자열 형식: `pleos.car.permission.<이름>` (normal)
- **속도·기어 API 없음** → AAOS `CarPropertyManager` `PERF_VEHICLE_SPEED`(m/s, `android.car.permission.CAR_SPEED` dangerous), `GEAR_SELECTION`(`CAR_POWERTRAIN`)

## TTS (`ai.pleos.playground.tts.TextToSpeech(ctx, Mode.ON_DEVICE)`)
- `initialize(OnInitializeListener{onSuccess; onFailure(ErrorType)})`, `speak(text)`, `stop()`, `release()`. 권한 `pleos.car.permission.TTS_SERVICE`
- SERVER/HYBRID 모드는 `registerApp(clientID, clientSecret, ...)` 필요 → ON_DEVICE 사용

## FusedLocation (`ai.pleos.playground.location.fused.FusedLocation(ctx)`)
- `isSupported()`, `initialize()`, `registerFusedLocationCallback(FusedLocationListener{onChanged(LocationData?); onError(Exception)})`
- `LocationData`: latitude, longitude, altitude, heading, confidence, timestampMillis (speed 없음)
- 권한 `pleos.car.permission.FUSED_LOCATION` — **dangerous, 런타임 요청 필요**

## NaviHelper (`ai.pleos.playground.navi.helper.NaviHelper(ctx)`, 2026-10-29 AAR javap로 확인)
- 도로 종류 API 없음. `initialize()`, `addListener(NaviHelperEventListener)`(메서드 전부 default), `onCurrentRoadSpeedLimit(Int)` 1초마다 → 앱은 제한속도 ≥ 80이면 고속도로로 추정(문서에 없는 추론)
- 권한(문서): `pleos.car.permission.NAVI_ROUTE` / `NAVI_ROUTE_SEARCH` / `NAVI_CUSTOM_MAP`. 이벤트별 필요 권한은 미확인 → NAVI_ROUTE만 선언

## SpeechToText 3.3.1 (`ai.pleos.playground.stt.SpeechToText(ctx, Mode)`, javap로 확인)
- Mode `HYBRID`(기본, 서버 우선·실패 시 온디바이스)/`SERVER`/`ON_DEVICE`. `initialize(OnInitializeListener{onSuccess; onFailure(ErrorType)})`, `addListener(ResultListener)`, `setLocale`, `registerApp(id, secret, OnServerConnectionListener)`, `request()`, `stop()`, `removeListener`, `release()`
- `ResultListener`: `onReady()`, `onStartedRecognition()`, `onUpdated(stt: String, completed: Boolean)`, `onUpdatedEpdData(on, off)`, `onUpdatedRms(rms)`, `onEndedRecognition()`, `onError(ErrorType)`
- 패키지: `...stt.constant.{Mode, ErrorType}`, `...stt.listener.*`. 권한 `pleos.car.permission.STT_SERVICE`

## Vehicle 크루즈 (javap)
- `getDrivingMode().registerCruiseControlState(CruiseControlStateListener)` → `onCruiseControlStateUpdated(state: CruiseControl?)`, enum `OTHER/ENABLED/ACTIVATED/USER_OVERRIDE/SUSPENDED/FORCED_DEACTIVATION_WARNING`

## 에뮬레이터
- cold boot 필수, Virtual Console `adb forward tcp:8088 tcp:8088` → http://127.0.0.1:8088 (Motion: 기어·브레이크·조향)
- 에어백 시뮬레이션 미확인 → 테스트 모드로 대체
