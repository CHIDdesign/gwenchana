# 괜차나 시험 도구 (qa/)

앱 빌드와 분리된 시험 도구 모음이에요. Gradle(`settings.gradle.kts`)은 `:app`, `:phone`만 빌드하므로, 이 폴더는 APK에 들어가지 않고 앱 빌드에도 영향을 주지 않아요.

| 폴더 | 내용 | 실행 |
|---|---|---|
| [`web/`](web/README.md) | **웹 시험장.** 운전 시뮬레이터 + 차 화면 + 시험 케이스 16개. 앱 로직을 JS로 옮긴 사본이에요. 공개본(main `5ffe61e`)과 수정본을 골라 같은 케이스를 돌릴 수 있어요 | 바로 써 보기: https://claude.ai/artifact/3yNqsuMJnJ6jLmUEZAwmEB · 직접: `node qa/web/build.mjs` 후 `qa/web/dist/index-local.html` |
| [`jvm/`](jvm/README.md) | **실제 코드 JVM 시험대.** 저장소의 Kotlin 소스를 그대로 컴파일하고, 안드로이드 부분만 최소 대역으로 바꿔 TC-04·DRV-12 등을 돌려요 | `sh qa/jvm/run.sh <소스 루트>` |
| [`samples/`](samples/README.md) | 팀 QA 샘플 4개(JUnit 계약: `QaSampleTest`, `QaAdapters`, `QaFixtures`) | samples/README.md |
| [`../docs/qa/EXPECTED_BEHAVIOR.md`](../docs/qa/EXPECTED_BEHAVIOR.md) | 기대 동작 명세 (요구사항 ID: DET, REC, GUIDE, SYNC, PRIV …) | — |

## 이번에 고친 것

1. **에어백 승격 (DRV-12, 명세 DET-02·DET-04).** 충격으로 먼저 잡힌 사고가 저장을 기다리는 동안 에어백 신호가 오면, 앱을 켠 뒤 지난 시간과 관계없이 그 사고를 큰 사고(에어백)로 올려요. 기다리는 사고가 없을 때만, 켠 지 60초 안에 방금 기록한 사고가 있으면 같은 사고로 봐요(앱 재시작 뒤 늦게 온 에어백 값). 코드: `Crash.kt` CrashWatcher의 에어백 조건.
2. **삭제 뒤 늦은 쓰기 (TC-04, 명세 PRIV-01).** 사고 폴더에 쓰는 모든 작업(사고 종류, 충돌 전 저장, 최종 저장, 경위서, 사진 복사, 영상 끝 시각)이 `Records.write`를 거쳐요. 삭제(`Consent.deleteRecords`)와 같은 잠금 안에서 DRIVE 동의와 지금 사고 폴더를 다시 확인하고, 지운 폴더는 다시 만들지 않아요. 코드: `Consent.kt`의 `Records`, `Crash.kt`의 각 저장 지점.

## 결과

| 케이스 | 공개본 `5ffe61e` | 수정본 |
|---|---|---|
| TC-01 센서 보정·임계값 | PASS | PASS |
| TC-02 임시 자료 ACK를 완성본 ACK로 쓰지 않기 | FAIL_REQUIREMENT | FAIL_REQUIREMENT |
| TC-03 실패 5초 후 재시도, 재시작에도 유지 | FAIL_REQUIREMENT | FAIL_REQUIREMENT |
| TC-04 동의 철회 뒤 늦은 종류 저장 | FAIL_REQUIREMENT | **PASS** |
| DRV-01~11 자동 운전 11개 | PASS | PASS |
| DRV-12 켠 직후 충격 → 에어백 | FAIL_REQUIREMENT | **PASS** |

웹 시험장(Node와 브라우저 같은 결과). 실제 코드 결과는 [`jvm/README.md`](jvm/README.md)에 있어요.

TC-02·TC-03은 명세의 **보강 제안**(자료 버전별 저장 확인 SYNC-01, 영속 재시도 §4.6)이 아직 앱에 없어서 실패해요. 고장이 아니라 아직 만들지 않은 기능이에요.

## 어디까지 믿을 수 있나

- 웹 시험장은 사본이라 실제 스레드 경합, 센서 축 방향, 안드로이드 동작은 보여 주지 못해요.
- JVM 시험대는 실제 코드를 돌리지만 안드로이드 API는 대역이에요. 실기기·에뮬레이터에서 APK로 다시 확인해야 해요.
- `apk/`의 APK는 공개본으로 빌드한 것이에요. 이 수정이 APK에 들어가려면 다시 빌드해야 해요.
