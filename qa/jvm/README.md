# qa/jvm — JVM bench for the car app's crash/consent logic

Compiles the **real, unmodified** product sources of a checkout against small platform stubs and runs
JUnit 4 tests on a plain JVM (no Android SDK, no Gradle, no network).

```sh
./run.sh <repo root>              # or: SRC=<repo root> ./run.sh
QA_SLOW=0 ./run.sh <repo root>    # skip @Slow tests (the 67 s control case)
QA_ONLY=tc04 ./run.sh <repo root> # only tests whose Class#method contains the text
QA_VERBOSE=1 ...                  # product Log output, observations, stack traces
KOTLIN_LIB=<dir> ...              # folder with kotlin-compiler-embeddable-2.0.21, kotlin-stdlib-2.0.21,
                                  # kotlin-reflect, kotlin-script-runtime, kotlin-daemon-embeddable,
                                  # annotations, trove4j, kotlinx-coroutines-core-jvm, junit-4.13.2,
                                  # hamcrest-core-1.3 (default: $GRADLE_HOME/lib or /opt/gradle-*/lib)
```

Each test runs in its own JVM (the product uses singletons). Output: one `PASS` / `FAIL` / `BLOCKED` /
`SKIP` line per test with the assertion message, then a summary. Exit code is non-zero if any test FAILs.
`BLOCKED_ADAPTER` (TC-02, TC-03: not wired yet) is neither a pass nor a product failure.

## What is real

- Product files compiled whole: `app/.../{Crash,Consent,Sources,Handoff,Guide,Report,Sim}.kt`,
  `shared/.../{Recorder,CrashTypes,HandoffData}.kt` (list in `run.sh`).
- `java.io.File` on a temp sandbox, real threads (`kotlin.concurrent.thread`, executors), real wall-clock time,
  kotlinx-coroutines.
- SharedPreferences are persisted to disk, so `restartApp()` starts a **new JVM** on the same sandbox and runs
  the real start-up calls (`Consent.init`, and `Consent.cleanup` + `CrashWatcher.loadLatest` as `CarService` does).

## What is stubbed (`stubs/`)

- `android.*`, `android.car.*`: signatures only; `SensorManager` keeps registered listeners so tests deliver
  `SensorEvent`s through the real `ImpactSensor`. No Looper: events arrive on a test thread named `impact`.
- `org.json`: small stand-in (Android ships it in the platform).
- `androidx.compose.runtime`: `mutableStateOf` is a volatile cell. Snapshot isolation/recomposition are not modelled.
- `ai.pleos.*`: SDK signatures; vehicle/navi/TTS/STT initialisation fails like a device without the service.
- Product symbols from files that are not compiled (`stubs/kotlin/com/askthecar`): `BuildConfig` with empty
  URLs/keys (the `-Ppublic` build: `CarSync`/`Relay` never touch the network), `CarService` (class literal only),
  `CameraVideo` file names, `VehicleFinder`, `ClaimText`, `ClaimPdf`.

## Tests

- `samples/QaSampleTest.kt`, `QaFixtures.kt`: the team's contract files, verbatim. `QaAdapters.kt`: same
  interfaces; `sensor()` and `deletion()` are wired to `RealHarnesses.kt`.
- TC-04: `CrashFlow.dir` is seeded with `bench/PausingFile`, a `File` for the same path whose first disk call from a
  `CrashFlow` lambda thread (the kind-write job) blocks until the test releases it. UI actions run on a thread
  named `main`; a call that blocks there for 5 s is reported as `BLOCKED_HARNESS`.
- `drive/Drv12AirbagUpgradeTest.kt`: the real `CrashWatcher.start()` loop at 40 km/h; impact via the real
  accelerometer listener; airbag via `CarState.fromCar("airbag")`.

## Running it from the repository

```sh
bash qa/jvm/run.sh .                    # this checkout
bash qa/jvm/run.sh /path/to/other/checkout
```

## Results (2026-11-15)

Same bench, same tests, two checkouts. Each line is one JVM.

| Test | What it checks | Public main `5ffe61e` | Fix branch `qa/tester-and-fixes` |
|---|---|---|---|
| TC-01 | sensor correction, 2.5 g threshold, negative and vertical pulses | PASS | PASS |
| TC-02 | preview ACK must not confirm the final record | BLOCKED_ADAPTER (not wired) | BLOCKED_ADAPTER (not wired) |
| TC-03 | upload retry 5 s after failure, survives restart | BLOCKED_ADAPTER (not wired) | BLOCKED_ADAPTER (not wired) |
| TC-04 | late kind write after consent withdrawal must not recreate the folder | **FAIL** — folder came back with `kind.txt` | PASS |
| DRV-12 | airbag 0.5 s after an impact, app up 5.5 s → severe | **FAIL** — `severe=false` | PASS |
| DRV-12b | airbag right after "사고가 아니에요", app up < 60 s → new severe incident | **FAIL** — 1 incident instead of 2 | PASS |
| DRV-12c | airbag 11.5 s after an impact (after the save window), app up < 60 s → severe | **FAIL** — `severe=false` | PASS |
| DRV-12 control | same as DRV-12 after 65 s uptime (old behaviour already worked) | PASS | PASS |
| DRV-12 diag | the upgrade must not depend on the pre-save folder | **FAIL** | PASS |

Summary: public main PASS 2 · FAIL 5 · BLOCKED 2; fix branch PASS 7 · FAIL 0 · BLOCKED 2.
TC-02 and TC-03 need the proposed features (per-version ACK, persistent retry) before an adapter can be wired.
