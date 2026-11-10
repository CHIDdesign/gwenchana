"""Ask the Car 한 번에 켜기 (시작.cmd).

순서: BeamNG 를 먼저 켜 두고(자유 주행) → 이 프로그램
  1) BeamNG 찾기·모드 연결 (beamng_bridge.setup_beamng)
  2) OBS: 다각도 영상 녹화(리플레이 버퍼). --no-obs 면 안 켠다(영상 없음, 게임 프레임 여유)
  3) 에뮬레이터: 안 켜져 있으면 켜고 부팅 대기
  4) 앱: 새 APK 면 설치, 권한·시간대 → 시작 신호(기록 서비스)
  5) 브리지 실행, 이 창에는 상태표만 보여 준다(자세한 기록은 runs/bridge.log). 게임 속 시동을 끄고 켜면 에뮬레이터 화면도 꺼지고 켜진다.
끝낼 때는 끝내기.cmd (launcher.py --stop)
에뮬레이터 창은 부팅 뒤 두 번째 모니터로 옮긴다(이미 거기 있거나 모니터가 하나면 그대로). 게임 창은 안 건드린다.
"""
import json
import os
import pathlib
import secrets as pysecrets
import subprocess
import sys
import threading
import ctypes
import ctypes.wintypes
import socket
import time
import urllib.request

import beamng_bridge as bb

HERE = bb.HERE
ROOT = HERE.parents[1]
PKG = "com.askthecar"
USER = "10"  # Pleos(AAOS) 운전자 사용자
APK = ROOT / "app" / "build" / "outputs" / "apk" / "debug" / "app-debug.apk"
STATE = HERE / ".installed_apk"  # 마지막으로 설치한 APK 의 수정 시각 (git 제외)


def say(msg: str):
    print(f"[시작] {msg}", flush=True)


def running(exe: str) -> bool:
    out = subprocess.run(["tasklist", "/fi", f"imagename eq {exe}", "/nh"], capture_output=True, text=True, errors="ignore").stdout
    return exe.lower() in out.lower()


def adb(*args: str, timeout: float = 60) -> str:
    try:
        r = subprocess.run([str(bb.ADB), *args], capture_output=True, text=True, errors="ignore", timeout=timeout)
    except (subprocess.TimeoutExpired, OSError) as e:  # adb 가 잠깐 멈춰도 실행 프로그램 전체가 죽지 않게
        return f"adb 실패: {e}"
    return (r.stdout + r.stderr).strip()


def set_secret(key: str, value: str):
    f = ROOT / "secrets.properties"
    lines = f.read_text(encoding="utf-8").splitlines() if f.exists() else []
    lines = [l for l in lines if l.partition("=")[0].strip() != key] + [f"{key}={value}"]
    tmp = f.with_suffix(".tmp")  # 쓰다가 끊겨도(전원·USB 분리) API 키 파일이 반쯤 지워지지 않게
    tmp.write_text("\n".join(lines) + "\n", encoding="utf-8")
    os.replace(tmp, f)


def powershell(cmd: str) -> str:
    return subprocess.run(["powershell", "-NoProfile", "-Command", cmd], capture_output=True, text=True, errors="ignore",
                          creationflags=subprocess.CREATE_NO_WINDOW).stdout


def stop_previous():
    """이전 실행이 남아 있으면 포트가 겹쳐 값이 안 온다 → 시작할 때 정리"""
    if powershell("Get-CimInstance Win32_Process -Filter \"Name like 'python%'\" | Where-Object { $_.CommandLine -match 'beamng_bridge.py|sync_server.py' } "
                  "| ForEach-Object { Stop-Process -Id $_.ProcessId -Force; 'x' }").strip():
        say("이전 브리지를 정리했어요")
    # 이전 시작.cmd 의 검은 창도 닫는다. 안 닫으면 "계속하려면 아무 키"인 창이 실행할 때마다 쌓였다. 지금 이 창(내 부모들)은 빼고
    powershell("$all = @{}; Get-CimInstance Win32_Process | ForEach-Object { $all[[int]$_.ProcessId] = $_ }; $me = @(); $p = " + str(os.getpid()) + "; "
               "while ($all[[int]$p]) { $me += [int]$p; $p = $all[[int]$p].ParentProcessId; if ($me -contains [int]$p) { break } }; "
               "$all.Values | Where-Object { $_.Name -like 'python*' -and $_.CommandLine -match 'launcher\\.py' -and $me -notcontains [int]$_.ProcessId } | ForEach-Object { "
               "$q = $_; while ($q -and ($q.Name -like 'python*' -or $q.Name -eq 'cmd.exe')) { Stop-Process -Id $q.ProcessId -Force -ErrorAction SilentlyContinue; $q = $all[[int]$q.ParentProcessId] } }")


def stop_all():
    stop_previous()
    for serial in ("emulator-5554", "emulator-5556"):
        adb("-s", serial, "emu", "kill", timeout=15)
    kill_car_emulator()  # 멈춘 에뮬레이터는 위 명령을 못 받는다
    subprocess.run(["taskkill", "/im", "obs64.exe", "/f"], capture_output=True)
    rec = bb.rec_on_file()
    if rec:
        rec.unlink(missing_ok=True)  # 시점 창을 끈다(그래픽 부담)
    # adb 서버와 빌드 도우미(Gradle·Kotlin)는 뒤에 남아 SSD 안전 제거를 막는다
    adb("kill-server", timeout=15)
    for _ in range(30):  # 에뮬레이터가 다 꺼진 뒤에 남은 프로그램을 알려 준다. 바로 보면 꺼지는 중인 것까지 나왔다
        if not running("qemu-system-x86_64.exe"):
            time.sleep(2)
            break
        time.sleep(1)
    powershell("Get-CimInstance Win32_Process -Filter \"Name='java.exe'\" | Where-Object { $_.CommandLine -match 'askthecar-tools' } | ForEach-Object { Stop-Process -Id $_.ProcessId -Force }")
    left = powershell("Get-CimInstance Win32_Process | Where-Object { $_.ExecutablePath -like '" + ROOT.drive + "\\*' -and $_.Name -ne 'python.exe' } | ForEach-Object { $_.Name }").split()
    say("브리지·에뮬레이터·OBS·adb·빌드 도우미를 껐어요. BeamNG 는 직접 닫아 주세요.")
    if left:
        say("아직 이 드라이브에서 도는 프로그램: " + ", ".join(sorted(set(left))))


def set_priorities():
    """게임과 같이 돌 때 차 에뮬레이터가 밀리지 않게, 휴대폰 에뮬레이터는 양보"""
    powershell("Get-CimInstance Win32_Process -Filter \"Name like 'qemu-system%'\" | ForEach-Object { $c = if ($_.CommandLine -match 'Pleos_Connect') {'AboveNormal'} else {'BelowNormal'}; "
               "(Get-Process -Id $_.ProcessId).PriorityClass = $c }")


def apk_outdated() -> bool:
    if not APK.exists():
        return False
    built = APK.stat().st_mtime
    for root in ("app/src", "shared"):
        for f in (ROOT / root).rglob("*"):
            if f.is_file() and f.stat().st_mtime > built:
                return True
    return False


# ---- OBS ----
def obs_dir() -> pathlib.Path | None:
    try:
        import winreg
        with winreg.OpenKey(winreg.HKEY_LOCAL_MACHINE, r"SOFTWARE\OBS Studio") as k:
            p = pathlib.Path(winreg.QueryValueEx(k, "")[0])
    except OSError:
        p = pathlib.Path(os.environ.get("ProgramFiles", r"C:\Program Files")) / "obs-studio"
    return p if (p / "bin" / "64bit" / "obs64.exe").exists() else None


def patch_obs():
    """OBS 가 꺼져 있을 때만: 웹소켓 켜기(4455, 비밀번호)"""
    cfg = pathlib.Path(os.environ["APPDATA"]) / "obs-studio"
    ws = cfg / "plugin_config" / "obs-websocket" / "config.json"
    c = json.loads(ws.read_text(encoding="utf-8")) if ws.exists() else {}
    pw = c.get("server_password") or bb.secret("obs.password") or pysecrets.token_urlsafe(12)
    want = {"server_enabled": True, "server_port": 4455, "auth_required": True, "server_password": pw,
            "alerts_enabled": False}  # 브리지가 10초마다 접속 → 알림 끄기
    if any(c.get(k) != v for k, v in want.items()):
        c.update(want)
        ws.parent.mkdir(parents=True, exist_ok=True)
        ws.write_text(json.dumps(c, indent=4), encoding="utf-8")
        say("OBS 웹소켓 켬 (포트 4455)")
    if bb.secret("obs.password") != pw:
        set_secret("obs.password", pw)
        say("OBS 비밀번호를 secrets.properties 에 저장")


def start_obs():
    """다각도 영상용 OBS: 트레이로 숨겨 켜고 리플레이 버퍼를 돌린다"""
    d = obs_dir()
    if not d:
        say("OBS 를 못 찾음 — 다각도 영상 없이 진행 (https://obsproject.com 에서 설치)")
        return
    patch_obs()
    exe = d / "bin" / "64bit" / "obs64.exe"
    pw = bb.secret("obs.password")
    for _ in range(2):
        if not running("obs64.exe"):
            # 강제 종료 흔적이 있으면 OBS 가 안내창에서 멈춰 웹소켓이 안 열린다(--disable-shutdown-check 로도 뜸, 이 PC OBS 32 확인)
            for f in (pathlib.Path(os.environ["APPDATA"]) / "obs-studio" / ".sentinel").glob("run_*"):
                f.unlink(missing_ok=True)
            subprocess.Popen([str(exe), "--minimize-to-tray", "--disable-shutdown-check", "--profile", bb.MC_PROFILE, "--startreplaybuffer"], cwd=exe.parent,
                             creationflags=subprocess.DETACHED_PROCESS | subprocess.CREATE_NEW_PROCESS_GROUP)
        try:
            for _ in range(30):
                try:
                    bb.obs("GetVersion", pw)
                    break
                except (OSError, RuntimeError):  # 켜지는 중엔 연결 거부 또는 "OBS is not ready"
                    time.sleep(1)
            for attempt in range(3):  # 막 켜진 OBS 는 설정 요청이 시간 초과로 끊기기도 한다
                try:
                    restart = bb.setup_multicam(pw)
                    break
                except (OSError, RuntimeError) as e:
                    if attempt == 2:
                        raise
                    say(f"OBS 설정 다시 시도 ({e})")
                    time.sleep(2)
            if not restart:
                if not bb.obs("GetReplayBufferStatus", pw)["outputActive"]:
                    bb.obs("StartReplayBuffer", pw)
                rec = bb.rec_on_file()
                if rec:
                    rec.parent.mkdir(parents=True, exist_ok=True)
                    rec.write_text("1")
                say("OBS 켬 (트레이로 숨김, 다각도 영상 녹화)")
                return
        except Exception as e:
            say(f"OBS 설정 실패: {e}")
            return
        subprocess.run(["taskkill", "/im", "obs64.exe", "/f"], capture_output=True)  # 새 프로필로 다시 켠다
        time.sleep(2)
    say("OBS 를 다각도 녹화로 못 맞춤 — 영상 없이 진행")


# ---- 에뮬레이터·앱 ----
def kill_car_emulator():
    powershell("Get-CimInstance Win32_Process -Filter \"Name like 'qemu-system%'\" | Where-Object { $_.CommandLine -match 'Pleos_Connect' } | ForEach-Object { Stop-Process -Id $_.ProcessId -Force }")
    time.sleep(3)


def start_emulator() -> bool:
    listed = "emulator-5554" in adb("devices")
    if listed and adb("-s", "emulator-5554", "shell", "getprop", "sys.boot_completed", timeout=15) != "1":
        say("에뮬레이터가 응답이 없어 다시 켜요 (오래 켜 두면 멈출 때가 있어요)")
        kill_car_emulator()
        listed = False
    if listed:
        say("에뮬레이터 이미 켜져 있음")
    else:
        cmd = ROOT / "scripts/start-emulator.cmd"
        if not cmd.exists():
            say("scripts/start-emulator.cmd 가 없음 — 에뮬레이터를 직접 켜세요")
            return False
        subprocess.Popen(["cmd", "/c", str(cmd)], cwd=ROOT, creationflags=subprocess.CREATE_NO_WINDOW)
        say("에뮬레이터 켜는 중… (처음엔 1분쯤)")
    adb("wait-for-device", timeout=300)
    for _ in range(150):
        if adb("shell", "getprop", "sys.boot_completed") == "1":
            return True
        time.sleep(2)
    say("에뮬레이터 부팅이 5분 넘게 안 끝남")
    return False


def setup_app():
    if apk_outdated():
        say("주의: 코드가 APK 보다 새로워요. 최신으로 하려면 먼저 빌드: tools\\run.cmd .\\gradlew.bat :app:assembleDebug")
    if not APK.exists():
        say(f"APK 없음({APK}) — 먼저 빌드: tools\\run.cmd .\\gradlew.bat :app:assembleDebug")
    elif PKG not in adb("shell", "pm", "list", "packages", "--user", USER, PKG) or \
            not STATE.exists() or STATE.read_text() != str(APK.stat().st_mtime):
        say("앱 설치/갱신 중…")
        out = adb("install", "-r", "-g", str(APK), timeout=300)
        if "Success" not in out:
            say(f"앱 설치 실패: {out[-200:]}")
            return
        STATE.write_text(str(APK.stat().st_mtime))
    adb("shell", "appops", "set", "--user", USER, PKG, "SYSTEM_ALERT_WINDOW", "allow")
    # 시동 덮개가 상단 바까지 가리게(에뮬레이터 설정만 바꿈, 디버그 앱 전용 서비스)
    adb("shell", "settings", "put", "--user", USER, "secure", "enabled_accessibility_services", f"{PKG}/{PKG}.SimCoverService")
    adb("shell", "settings", "put", "--user", USER, "secure", "accessibility_enabled", "1")
    adb("shell", "cmd", "alarm", "set-timezone", "Asia/Seoul")
    adb("shell", "input", "keyevent", "KEYCODE_WAKEUP")
    adb("shell", "am", "broadcast", "--user", USER, "-a", "com.askthecar.START", "-n", f"{PKG}/.SimReceiver")
    say("앱 시작: 기록 서비스 (처음이면 동의 화면을 눌러 주세요)")


def start_phone():
    """휴대폰 에뮬레이터·앱. 차 시험을 붙잡지 않게 따로 돌고, 실패해도 차 쪽은 그대로"""
    serial = "emulator-5556"
    pkg, apk = "com.askthecar.phone", ROOT / "phone/build/outputs/apk/debug/phone-debug.apk"
    phone = lambda *a, timeout=60: adb("-s", serial, *a, timeout=timeout)
    if serial not in adb("devices"):
        subprocess.Popen(["cmd", "/c", str(ROOT / "scripts/start-phone-emulator.cmd")], cwd=ROOT, creationflags=subprocess.CREATE_NO_WINDOW)
        say("휴대폰 에뮬레이터 켜는 중…")
    phone("wait-for-device", timeout=300)
    for _ in range(150):
        if phone("shell", "getprop", "sys.boot_completed") == "1":
            break
        time.sleep(2)
    else:
        return say("휴대폰 에뮬레이터 부팅이 5분 넘게 안 끝남")
    to_second_monitor("Phone", right=True)
    if not apk.exists():
        return say(f"휴대폰 APK 없음({apk}) — 먼저 빌드: tools\\run.cmd .\\gradlew.bat :phone:assembleDebug")
    out = phone("install", "-r", "-g", str(apk), timeout=300)
    if "Success" not in out:
        return say(f"휴대폰 앱 설치 실패: {out[-200:]}")
    phone("shell", "cmd", "alarm", "set-timezone", "Asia/Seoul")
    # 게임과 같이 돌면 폰이 멈췄다(CPU 99%, 시스템 응답 없음). 애니메이션·블루투스를 끄고, 바탕화면 앱이 죽던 밀어 올리기 대신 버튼 3개로
    for k in ("window_animation_scale", "transition_animation_scale", "animator_duration_scale"):
        phone("shell", "settings", "put", "global", k, "0")
    phone("shell", "cmd", "bluetooth_manager", "disable")
    phone("shell", "cmd", "overlay", "enable-exclusive", "--category", "com.android.internal.systemui.navbar.threebutton")
    phone("shell", "am", "start", "-n", f"{pkg}/{pkg}.PhoneActivity")
    set_priorities()
    say("휴대폰 앱 켬: 처음이면 아무 운전자로나 로그인하세요 (PC 시연은 차와 자동으로 묶여요)")
    for _ in range(300):  # 로그인돼 있으면(연결 서비스가 돎) 바탕화면으로. 사고가 나면 알림이 오고, 눌러야 앱이 열린다
        if "SyncService" in phone("shell", "dumpsys", "activity", "services", pkg):
            time.sleep(4)  # 앱 화면이 다 뜬 뒤에 눌러야 바탕화면으로 나간다
            phone("shell", "input", "keyevent", "HOME")
            return say("휴대폰: 바탕화면에서 사고 알림을 기다려요")
        time.sleep(2)


def to_second_monitor(title: str, right: bool = False):
    """에뮬레이터 창을 게임과 안 겹치게 두 번째 모니터로. 모니터가 하나거나 이미 옮겨 놓은 창은 건드리지 않는다"""
    u = ctypes.windll.user32
    wintypes = ctypes.wintypes
    monitors, windows = [], []

    def on_monitor(_h, _dc, rect, _p):
        monitors.append((rect.contents.left, rect.contents.top, rect.contents.right))
        return True

    def on_window(h, _):
        n = u.GetWindowTextLengthW(h)
        if n and u.IsWindowVisible(h):
            buf = ctypes.create_unicode_buffer(n + 1)
            u.GetWindowTextW(h, buf, n + 1)
            if buf.value.startswith("Android Emulator") and title in buf.value:
                windows.append(h)
        return True
    u.SetThreadDpiAwarenessContext(ctypes.c_void_p(-4))  # 실제 픽셀 좌표로 다룬다
    u.EnumDisplayMonitors(0, 0, ctypes.WINFUNCTYPE(ctypes.c_bool, wintypes.HMONITOR, wintypes.HDC, ctypes.POINTER(wintypes.RECT), wintypes.LPARAM)(on_monitor), 0)
    u.EnumWindows(ctypes.WINFUNCTYPE(ctypes.c_bool, wintypes.HWND, wintypes.LPARAM)(on_window), 0)
    other = [m for m in monitors if (m[0], m[1]) != (0, 0)]
    if not other or not windows:
        return
    left, top, end = other[0]
    r = wintypes.RECT()
    u.GetWindowRect(windows[0], ctypes.byref(r))
    if left <= r.left < end:
        return
    x = end - 430 if right else left + 10  # 휴대폰은 오른쪽 끝, 차는 왼쪽
    u.SetWindowPos(windows[0], 0, x, top + 8, 0, 0, 0x1 | 0x4 | 0x10)  # 크기·순서 유지, 포커스 안 뺏음


def game_minimized() -> bool:
    """최소화된 게임은 화면을 안 그려서 사고 사진을 못 찍는다"""
    u = ctypes.windll.user32
    found = []

    def each(h, _):
        n = u.GetWindowTextLengthW(h)
        if n:
            buf = ctypes.create_unicode_buffer(n + 1)
            u.GetWindowTextW(h, buf, n + 1)
            if buf.value.startswith("BeamNG.drive -"):
                found.append(h)
        return True
    u.EnumWindows(ctypes.WINFUNCTYPE(ctypes.c_bool, ctypes.c_void_p, ctypes.c_void_p)(each), 0)
    return any(u.IsIconic(h) for h in found)


def status_loop(bridge: subprocess.Popen):
    """상태가 바뀔 때만 한 번에 보여 준다. 잘못된 줄에는 할 일을 적는다. 앱이 사라지면(에뮬레이터 재시작 등) 다시 켠다"""
    last, gone = None, 0
    while bridge.poll() is None:
        time.sleep(5)
        try:
            st = json.load(urllib.request.urlopen("http://127.0.0.1:8766/status", timeout=3))
        except OSError:
            continue
        booted = adb("-s", "emulator-5554", "shell", "getprop", "sys.boot_completed", timeout=10) == "1"
        app = booted and bool(adb("-s", "emulator-5554", "shell", "pidof", PKG, timeout=10))
        phone = bool(adb("-s", "emulator-5556", "shell", "pidof", "com.askthecar.phone", timeout=10)) and "error" not in adb("-s", "emulator-5556", "get-state", timeout=10)
        game = "BeamNG 를 켜 주세요" if not running("BeamNG.drive.x64.exe") else "자유 주행에서 차에 타고, 옵션 > 프로토콜 > 기타 프로토콜을 켜 주세요"
        rows = [("게임", st["game"], game), ("게임 창", not game_minimized(), "최소화돼 있으면 사고 사진을 못 찍어요. 창을 열어 두세요"), ("차 에뮬레이터", booted and st["vc"], "켜지는 중이거나 멈췄어요. 2분 넘게 이러면 시작.cmd 를 다시 실행하세요"),
                ("차 앱", app and st["app"], "앱을 다시 켜는 중이에요"),
                ("휴대폰 앱", phone or "--no-phone" in sys.argv, "휴대폰 에뮬레이터를 켜는 중이거나 꺼져 있어요(없어도 차 시연은 돼요)")]
        if "--no-obs" not in sys.argv:  # 실패하면 사고 영상이 전부 검게 저장된다. 시연 전에 보이게
            rows.append(("다각도 영상", st["obs"], "OBS 영상 실패. OBS 가 안내창에 걸렸는지 보고 시작.cmd 를 다시 실행하세요(영상 없이 하려면 --no-obs)"))
        text = "\n".join(f"  {n:<10} {'OK' if ok else '확인 필요 -> ' + hint}" for n, ok, hint in rows)
        text += "\n  안내 음성    " + ("PC 스피커" if st["voice"] else "Pleos 음성 (에뮬레이터, 끊길 수 있음)")
        if text != last:
            last = text
            print(f"\n[{time.strftime('%H:%M:%S')}] 상태 (자세한 기록: game/bridge/runs/bridge.log)\n{text}", flush=True)
        gone = gone + 1 if booted and not app else 0
        if gone >= 3:
            gone = 0
            setup_app()


def main():
    sys.stdout.reconfigure(errors="replace")  # 한국어 콘솔(cp949)이 못 찍는 글자(— 등) 때문에 멈추지 않게
    if "--stop" in sys.argv:
        return stop_all()
    stop_previous()
    print(bb.setup_beamng(), flush=True)
    if not running("BeamNG.drive.x64.exe"):
        say("BeamNG 가 아직 안 켜져 있음 — 게임을 켜고 자유 주행으로 들어가면 바로 연동돼요")
    if "--no-obs" not in sys.argv:  # 끄면 다각도 영상은 없고 게임 프레임 약 20% 여유(2026-11-05 측정: 42 → 33~36)
        start_obs()
    if start_emulator():
        to_second_monitor("Pleos_Connect")
        setup_app()
        time.sleep(2)
        if "ConsentActivity" not in adb("shell", "dumpsys", "activity", "activities"):  # 동의 화면은 눌러야 하니 켜 둔다
            adb("shell", "input", "keyevent", "KEYCODE_SLEEP")
            say("차 화면은 시동 전이라 검은색이에요. 게임에서 시동을 걸면 켜져요")
    set_priorities()
    if "--no-phone" not in sys.argv:  # 휴대폰을 실제 폰이나 다른 PC 로 돌릴 때
        threading.Thread(target=start_phone, daemon=True).start()
    else:
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
            s.connect(("8.8.8.8", 80))  # 패킷은 안 나간다. 와이파이 쪽 내 주소만 알아냄
            say(f"실제 휴대폰: 같은 와이파이에서 앱 서버 주소에 http://{s.getsockname()[0]}:8767 입력 (Windows 방화벽이 물으면 허용)")
    # 차 ↔ 휴대폰 앱 동기화 서버 (시제품)
    subprocess.Popen([sys.executable, str(HERE / "sync_server.py"), "--one-account"], cwd=HERE, creationflags=subprocess.CREATE_NO_WINDOW)
    say("브리지 실행 (이 창을 닫으면 연동이 끊겨요. 끝낼 때는 끝내기.cmd)")
    bb.RUNS.mkdir(exist_ok=True)
    with (bb.RUNS / "bridge.log").open("w", encoding="utf-8", errors="replace") as log:
        env = dict(os.environ, PYTHONIOENCODING="utf-8", PYTHONUNBUFFERED="1")
        # --openings 를 주지 않으면 게임 트렁크·문 열림을 에뮬레이터로 안 보낸다(충돌마다 Pleos "트렁크 열림" 창이 사고 팝업을 가렸음)
        extra = (["--no-obs"] if "--no-obs" in sys.argv else []) + ([] if "--openings" in sys.argv else ["--no-openings"])
        # 시작.cmd --damage-jump 5000 처럼 주면 브리지의 합성 에어백 기준(0.5초 손상 증가)을 바꿈. 측정: 25km/h 정면 4847, 48km/h 10426, 스침 3072~4542
        if "--damage-jump" in sys.argv:
            extra += sys.argv[sys.argv.index("--damage-jump"):][:2]
        bridge = subprocess.Popen([sys.executable, str(HERE / "beamng_bridge.py"), "--skip-setup"] + extra, cwd=HERE, stdout=log, stderr=subprocess.STDOUT, env=env)
        threading.Thread(target=status_loop, args=(bridge,), daemon=True).start()
        code = bridge.wait()
    say(f"브리지가 멈췄어요 (코드 {code}). game/bridge/runs/bridge.log 를 확인하세요")
    sys.exit(code)


if __name__ == "__main__":
    main()
