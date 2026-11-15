# 괜차나 Web 시험장 (web test bench)

> **요약** — 괜차나 차 앱의 로직을 JS로 옮겨, 브라우저에서 직접 운전하고 차 화면을 보며 시험 케이스를 돌리는 도구예요. 실제 APK를 돌리는 게 아니라 사본이에요. 여기서 찾은 문제는 Kotlin에서 다시 확인했어요([`../jvm`](../jvm/)). 화면 위쪽 **앱 코드** 상자로 공개본(main `5ffe61e`, 심사용 APK와 같은 코드)과 수정본(이 브랜치)을 골라 같은 케이스를 돌릴 수 있어요.

- **바로 써 보기:** https://claude.ai/artifact/3yNqsuMJnJ6jLmUEZAwmEB
- **워크스루 1 · 감지 흐름:** https://claude.ai/artifact/Jg7a68Gf7arVtbecxBZPUD (원본은 [`docs/walkthrough-1-detection.html`](docs/walkthrough-1-detection.html))

## 할 수 있는 것

- **운전:** 고속도로와 차들이 있는 3D 도시. 방향키 또는 WASD, Space 브레이크, Q/E 방향지시등, H 비상등, P 주차, R 원위치, C 카메라, Esc 차 화면 뒤로.
- **차 화면**(앱 창 1700×1184dp): 내비 화면과 괜차나 화면(주행 중 띠, 다치셨나요?, 무엇과 부딪혔나요, 안내 단계, 사고 기록, 홈). 전화 버튼은 가짜 통화 창만 열고 **절대 전화를 걸지 않아요**.
- **시험 케이스:** TC-01~04는 가상 시계로 "Simulating…" 화면 뒤에서 돌고, DRV-01~12는 차를 자동으로 운전해요. 결과는 JSON으로 복사할 수 있어요.
- **개발 창:** 앱이 받는 차 신호, g 센서 그래프, 사고 상태, 동기화·서버 상태, 가상 파일, 휴대폰 화면, 기록. 동의 끄기, 네트워크 끊기, 앱 재시작, 연습 사고. 브라우저 콘솔에서 `GW.ACTIVE.app`, `GW.HU`로 살아 있는 상태를 볼 수 있어요.

## 직접 실행

```bash
node qa/web/build.mjs                 # dist/index.html(three.js CDN), dist/index-local.html(저장소의 three.js). 그림·글꼴·3D 모델은 앱 자산에서 복사
python3 -m http.server 8765 --directory qa/web/dist
# http://localhost:8765/index-local.html

node qa/web/test/core.test.mjs        # Kotlin 단위 테스트에서 옮긴 14개 확인
node qa/web/test/run-cases.mjs        # qa-cases.json 전체를 공개본·수정본 두 번 (페이지와 같은 코드)
node qa/web/test/run-cases.mjs DRV-12 --variant original --verbose
```

Node 18 이상. `dist/`는 커밋하지 않아요(빌드할 때 앱 자산에서 만들어짐). 브라우저 시험(`test/browser.mjs`, `test/variant.mjs`)은 Playwright가 필요해요(`PLAYWRIGHT=<playwright 경로>`).

## 구성

| 경로 | 내용 |
|---|---|
| `src/00-util.js` | 한국 시각 형식, 폴더 이름, SHA-256, float32, 앱 코드 선택(`VARIANT`) |
| `src/10-app-core.js` | Kotlin에서 옮긴 순수 로직: `Recorder`, `FactTimeline`, `Validator`, `Guides`, `inferKind`, `PhotoGuide`, 운전자 유형 |
| `src/20-app-runtime.js` | 앱 실행부: 가상 시계, 정지 지점이 있는 작업, 가상 파일, 동기화 서버, 네트워크, `ImpactSensor`, `CrashWatcher`, `CrashFlow`, `Records`, `Consent`, `CarSync`, 휴대폰 `Sync` |
| `src/30-sim.js` | 운전 시뮬레이터(자전거 모델, 충돌, 충격 펄스, 합성 에어백)와 시험 장면 |
| `src/40-render.js` | three.js 화면 |
| `src/45-lineart.js`, `src/50-headunit.js` | 차 화면: 선 그림 애니메이션, 차 그림, 내비, 홈, 사고 화면 |
| `src/60-qa.js` | 시험 장치(`QaAdapters.kt`와 같은 모양), 케이스 해석기, 자동 운전 실행기 |
| `src/70-main.js`, `template.html` | 페이지 틀, 입력, 패널 |
| `qa-cases.json` | 케이스 파일(`gwenchana-qa-cases/1`). TC 단계는 `QaSampleTest.kt`와 같은 순서·판정 |

`src/10`, `src/20`의 각 블록은 옮겨 온 Kotlin 파일 이름을 적어 두었어요. 앱이 바뀌면 사본도 같이 고쳐요.

## 어디까지 믿을 수 있나

로직은 그대로 옮겼지만 앱 자체는 아니에요.

- 스레드가 하나이고 작업이 번갈아 돌아요. 경합은 정지 지점이 있는 곳(지금은 `kind-write`)에서만 보여요. 실제 스레드 경합은 [`../jvm`](../jvm/)에서 실제 코드로 확인해요.
- 시계는 정확히 10Hz·50Hz로 흘러요.
- 충격 펄스, 센서 축 방향, 합성 에어백 규칙(정면 Δv ≥ 20km/h, 옆 ≥ 18km/h, 뒤는 없음)은 시험용 가정이에요. DRV 결과는 그럴듯한 입력에서 앱 로직이 맞게 도는지를 보는 것이고, 실제 감지 정확도가 아니에요.
- 중계 서버(Claude), 카메라, 영상, PDF는 없어요. 경위서는 공개 빌드처럼 사실 목록이에요.
- 운전 케이스는 앱을 켠 뒤 65초 지나서 출발해요(DRV-12만 예외: 켜자마자).

## 결과 (2026-11-15, Node와 브라우저에서 같음)

| 케이스 | 공개본 `5ffe61e` | 수정본 |
|---|---|---|
| TC-01 센서 보정·임계값 | PASS | PASS |
| TC-02 임시 자료 ACK를 완성본 ACK로 쓰지 않기 | FAIL_REQUIREMENT | FAIL_REQUIREMENT (제안 기능 미구현) |
| TC-03 실패 5초 후 재시도, 재시작에도 유지 | FAIL_REQUIREMENT | FAIL_REQUIREMENT (제안 기능 미구현) |
| TC-04 동의 철회 뒤 늦은 종류 저장 | FAIL_REQUIREMENT | **PASS** |
| DRV-01~11 운전 케이스 11개 | PASS | PASS |
| DRV-12 켠 직후 충격 → 에어백 | FAIL_REQUIREMENT | **PASS** |

상태 뜻은 [`../samples/README.md`](../samples/README.md)의 "결과 기록"과 같아요(`PASS`, `FAIL_REQUIREMENT`, `BLOCKED_ADAPTER`, `BLOCKED_ENVIRONMENT`). TC는 JUnit처럼 처음 실패한 확인에서 판정하고, 그 뒤 단계는 참고로 흐리게 보여 줘요.

## 문제 목록

| ID | 영역 | 상태 | 내용 | 근거 |
|---|---|---|---|---|
| F-01 | 감지 | **고침** | 이번 실행에서 사고를 감지한 뒤의 에어백 신호는 언제든 받는다. 저장 대기 중이면 큰 사고로 올리고, 대기가 끝났거나 사고 아님으로 닫은 뒤면 새 큰 사고로 기록한다(`Crash.kt` CrashWatcher의 에어백 조건). | DRV-12, `qa/jvm` DRV-12b·12c |
| F-02 | 동기화 | 열림 | 휴대폰 저장 확인(ACK)이 사고당 한 번이라, 임시 자료의 ACK가 완성본 도착처럼 보인다. 제안 SYNC-01 미구현. | TC-02 |
| F-03 | 동기화 | 열림 | 일반 업로드에 영속 대기열·재시도가 없다. 제안 §4.6 미구현. | TC-03 |
| F-04 | 개인정보 | **고침** | 종류 저장은 `Records.write`를 거쳐, 동의 철회·모두 지우기 뒤에는 지운 폴더를 다시 만들지 않는다. | TC-04, `qa/jvm` |
| S-01 | 개인정보 | **고침** | 사진 복사(`copyTo`)도 `Records.write` 안에서만 한다. 지운 사고면 임시 사진만 지운다. | 코드 |
| S-02 | 개인정보 | **고침** | 충돌 전 저장도 `Records.write`를 거친다. 지금 사고 폴더일 때만 폴더를 만든다. | 코드 |
| S-03 | 화면 | 의심 | 어두운 화면에서 차 그림의 트렁크 핀이 안 보일 수 있다(흰 원 + `textPrimary` 아이콘). | `CarFigure.kt` |
| D-01 | 시험 자료 | — | TC-02의 임시 자료에 진술이 있지만, 앱은 기록이 생긴 뒤에만 `statement.txt`를 보낸다. 임시 자료 진술은 `null`을 제안. | TC-02 어댑터 |
| G-01 | 감지 | 빈틈 | 속도 급감 경로가 처음 감지하는 케이스가 없다. DRV-13(부드러운 방호벽) 제안. | — |
| G-03 | 센서 | 빈틈 | 가속도 축 부호가 미확인. 시뮬레이터가 앱의 가정대로 넣으므로 부호가 틀려도 모든 케이스가 통과한다. | — |
