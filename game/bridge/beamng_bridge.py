"""BeamNG.drive → Pleos 에뮬레이터 브리지.

BeamNG 모드(game/beamng/mod/askthecar)가 UDP 127.0.0.1:4445 로 20Hz 패킷을 보낸다. 이 브리지는:

1) 실차 경로 (기본): 속도·핸들·브레이크·가속·기어·지시등을 Virtual Console HTTP API
   (adb forward 8088)로 에뮬레이터 차량 데이터에 넣는다 → Pleos 화면과 앱이 실제 SDK로 받는다.
2) 보조 통로 TCP 8765: Virtual Console 에 자리가 없는 SDK 값의 대체본과 내비 배경만 보낸다(SIDE_KEYS). 게임 정답은 앱에 안 보내고 runs/ 정답지에만.
   --no-vc 이면 예전처럼 모든 값을 이 통로로 보낸다 (fake_drive.py 와 같은 형식).
3) HTTP 8766: /cam/latest.jpg(게임이 찍은 앞 사진 = 실차 카메라 사진 대체), /clip/cameras.mp4(다각도 영상, OBS), /map/roads.json(게임 지도 내비 배경)
4) 에뮬레이터 콘솔(5554): 게임 가속도 → 안드로이드 가속도 센서 (앱의 충격 감지가 실차와 같은 경로), GPS(geo fix)

- 에어백 합성: 수평 충격 ≥ --airbag-g 또는 0.5초 손상 증가 ≥ --damage-jump. 차 리셋(손상<1)이면 해제.
- 손상도 등 게임 정보는 앱에 보내지 않고 game/bridge/runs/*.jsonl 에 정답지로만 저장.

실행: scripts/start-beamng-bridge.cmd  또는  python game/bridge/beamng_bridge.py
"""
import argparse
import base64
import hashlib
import http.client
import http.server
import json
import math
import os
import pathlib
import re
import select
import shutil
import socket
import socketserver
import struct
import subprocess
import sys
import threading
import time
import urllib.parse
import urllib.request

from fake_drive import BASE_LAT, BASE_LON, CLIP_PORT as FILE_PORT, STREAM_PORT

HERE = pathlib.Path(__file__).parent
UDP_PORT = 4445
VC_PORT = 8088
VC_URL = f"http://127.0.0.1:{VC_PORT}"
ADB = HERE.parents[1] / "tools" / "sdk" / "platform-tools" / "adb.exe"
os.environ.setdefault("ANDROID_SERIAL", "emulator-5554")  # 휴대폰 에뮬레이터까지 2대면 기기를 안 고른 adb 명령이 실패함
RUNS = HERE / "runs"
# game/beamng/mod/askthecar/lua/vehicle/protocols/askthecar.lua 의 struct 와 같아야 함
# 문·후드·트렁크: 이름, 모드 flags 비트, Virtual Console /vehicle/body 값 이름, VC 상태 body 안 위치
OPENINGS = (("trunk", 64, "trunk", ("closures", "trunk")), ("door_fl", 128, "door_fl", ("doors", "front_left")),
            ("door_fr", 256, "door_fr", ("doors", "front_right")), ("door_rl", 512, "door_rl", ("doors", "rear_left")),
            ("door_rr", 1024, "door_rr", ("doors", "rear_right")), ("hood", 2048, "frunk", ("closures", "frunk")))
ZONES = ("FL", "FR", "ML", "MR", "RL", "RR")  # 차체 구역: 앞·가운데·뒤 × 왼쪽·오른쪽
STRUCT = struct.Struct("<4s6f4sI3ff3ff2fI3fI24fI24f6f")
FIELDS = ("format speed steeringWheel steeringInput brake throttle parkingbrake gear flags "
          "posX posY posZ yaw accX accY accZ damage roadSpeedLimit roadLanes roadFlags "
          "otherFwd otherRight otherYaw otherValid").split() + [f"scan{i}" for i in range(24)] + ["objCount"] + [f"obj{i}" for i in range(24)] + list(ZONES)
VC_GEAR = {"P": "Park", "R": "Reverse", "N": "Neutral", "D": "Drive"}
VC_SIGNAL = {"L": "BlinkLeft", "R": "BlinkRight", "none": "Off"}


def parse(data: bytes) -> dict | None:
    if len(data) < STRUCT.size or not data.startswith(b"ATC1"):
        return None
    # 물리 폭주(사고 순간)에 NaN/inf 가 오면 round()가 죽고 앱 JSON 이 깨짐 → 0
    p = {k: v if not isinstance(v, float) or math.isfinite(v) else 0.0 for k, v in zip(FIELDS, STRUCT.unpack_from(data))}
    p["gear"] = p["gear"].split(b"\0")[0].decode(errors="ignore")
    return p


class Converter:
    """게임 패킷 → 실차가 줄 수 있는 값. 에어백 합성 상태를 가진다."""

    def __init__(self, ratio: float, sign: float, airbag_g: float, damage_jump: float, impact_jump: float = 4000):
        self.ratio, self.sign, self.airbag_g, self.damage_jump, self.impact_jump = ratio, sign, airbag_g, damage_jump, impact_jump
        self.airbag = False
        # 게임 가속도는 20Hz 라 약한 충돌의 짧은 충격(측정: 25km/h 정면 0.2g)을 놓친다.
        # 손상이 급히 늘면 그 방향으로 충격 펄스를 센서에 넣어, 에어백이 아니라 일반 충격으로 앱이 감지하게 한다(앱은 실차와 같은 센서 통로만 씀)
        self.pulse: tuple[float, float] | None = None
        self.pulse_at = -1e9
        self.acc_hist: list[tuple[float, float, float]] = []
        self.damage_hist: list[tuple[float, float]] = []
        self.last_damage = 0.0
        self.reset_at: float | None = None  # 리셋(R) 직후 손상 값이 튀어 정지 상태에서도 가짜 에어백이 되던 문제
        self.events: list[str] = []
        self.ignition: bool | None = None  # 게임 속 시동(ACC 이상). 바뀌면 에뮬레이터 화면을 켜고 끈다
        self.ign_off_at: float | None = None
        self.screen: bool | None = None  # 처리할 화면 변경

    def convert(self, p: dict, now: float) -> dict:
        horiz_g = math.hypot(p["accX"], p["accY"]) / 9.81
        self.damage_hist = [(t, d) for t, d in self.damage_hist if now - t <= 0.5] + [(now, p["damage"])]
        jump = p["damage"] - self.damage_hist[0][1]
        if p["damage"] < 1 <= self.last_damage:  # 차 리셋
            self.reset_at = now
            self.damage_hist = self.damage_hist[-1:]
            if self.airbag:
                self.airbag = False
                self.events.append("리셋: 에어백 해제")
        self.last_damage = p["damage"]
        settled = self.reset_at is None or now - self.reset_at > 3  # 리셋 뒤 3초는 판정 제외
        if not self.airbag and settled and (horiz_g >= self.airbag_g or jump >= self.damage_jump):
            self.airbag = True
            self.events.append(f"에어백 합성: 충격 {horiz_g:.1f}g, 0.5초 손상 증가 {jump:.0f}")
        self.acc_hist = [a for a in self.acc_hist if now - a[0] <= 0.5] + [(now, p["accX"], p["accY"])]
        if settled and jump >= self.impact_jump and now - self.pulse_at > 5:
            # 방향 = 충돌 순간의 변화량: 직전 가속(풀악셀 등)을 빼지 않으면 정면 충돌이 뒤쪽 충격으로 나온다
            old = [a for a in self.acc_hist if now - a[0] > 0.15]
            bx, by = (sum(a[1] for a in old) / len(old), sum(a[2] for a in old) / len(old)) if old else (0.0, 0.0)
            ax, ay = max(((a[1] - bx, a[2] - by) for a in self.acc_hist if now - a[0] <= 0.15), key=lambda v: math.hypot(*v))
            size = math.hypot(ax, ay)
            if size > 0.2:  # 방향을 알 수 없으면 펄스를 만들지 않는다
                self.pulse_at, self.pulse = now, (ax / size * 3.5 * 9.81, ay / size * 3.5 * 9.81)
                self.events.append(f"충격 펄스: 0.5초 손상 증가 {jump:.0f}, 실제 {size / 9.81:.1f}g → 3.5g")

        ign = (p["flags"] >> 4) & 3 >= 1
        # 게임 설정이 '시동 켜진 채 시작'이면 R 때 약 0.1초 꺼졌다 켜진다. 그 깜빡임만 무시하고, 그 밖엔 바로 끈 것으로 본다
        if ign or not self.ignition:
            self.ign_off_at = None
        else:
            self.ign_off_at = self.ign_off_at if self.ign_off_at is not None else now
            ign = now - self.ign_off_at < 0.3
        if ign != self.ignition:
            self.screen = ign  # 첫 값도: 시동이 꺼진 채 시작하면 검은 화면
            self.events.append("시동 켜짐 → 화면 켜기" if ign else "시동 꺼짐 → 화면 끄기")
            self.ignition = ign
        left, right = p["flags"] & 1, p["flags"] & 2
        if p["flags"] & 4:  # 비상등이면 방향지시등 아님 (Pleos 가 지시등에 사각지대 카메라를 띄워 사고 안내를 덮음)
            left = right = 0
        gear = p["gear"].upper()
        return {
            "speed": round(p["speed"] * 3.6, 1),
            "steer": round(self.sign * p["steeringWheel"] / self.ratio, 1),  # 앞바퀴 각도 추정, 좌 음수
            "brake": p["brake"] > 0.05,
            "brakePct": round(p["brake"] * 100),
            "throttlePct": round(p["throttle"] * 100),
            "signal": "L" if left and not right else "R" if right and not left else "none",
            "gear": gear if gear in ("P", "R", "N") else "D",
            "cruise": False,
            "hazard": bool(p["flags"] & 4),
            "openings": {n: bool(p["flags"] & bit) for n, bit, _, _ in OPENINGS},
            "ignition": ign,
            "airbag": self.airbag,
            "lat": round(BASE_LAT + p["posY"] / 111_320, 7),
            "lon": round(BASE_LON + p["posX"] / (111_320 * math.cos(math.radians(BASE_LAT))), 7),
            # 실차에서는 Pleos NaviHelper onCurrentRoadSpeedLimit 이 주는 값. 게임에서는 가장 가까운 도로의 제한속도
            "roadLimit": round(p["roadSpeedLimit"] * 3.6),
            "roadLanes": int(p["roadLanes"]),
            # 시뮬레이터 정답(실차는 알 수 없음): 가장 가까운 다른 차, 내 차 기준 앞(+)/오른쪽(+) 미터, 방향(시계방향 도)
            "simOther": {"fwd": round(p["otherFwd"], 2), "right": round(p["otherRight"], 2), "yaw": round(p["otherYaw"], 1)}
            if p["otherValid"] else None,
            # 시뮬레이터 정답: 15°마다 벽·건물·가드레일까지 거리(m), 30 = 없음. 앞(0°)부터 시계방향
            "simScan": [round(p[f"scan{i}"], 1) for i in range(24)],
            # 앱 내비 화면: 게임 지도 위 내 차 (x 동, y 북, h = 북쪽 기준 시계 방향 도)
            "gamePos": {"x": round(p["posX"], 1), "y": round(p["posY"], 1), "h": round(p["yaw"] % 360, 1)},
            "mapVersion": Shared.map_version(),
            # 시뮬레이터 정답 "충돌 센서": 차체 구역별 손상 0~1 (실차의 에어백 제어기 앞·옆 센서 역할)
            "zones": {z: round(p[z], 3) for z in ZONES},
            # 시뮬레이터 정답: 60m 안 차 최대 8대 (가까운 순)
            "simCars": [{"x": round(p[f"obj{3 * i}"], 2), "y": round(p[f"obj{3 * i + 1}"], 2), "h": round(p[f"obj{3 * i + 2}"], 1)}
                           for i in range(min(int(p["objCount"]), 8))],
        }


class VirtualConsole:
    """Virtual Console HTTP API 로 에뮬레이터 차량 데이터에 값을 넣는다. 바뀐 값만 보낸다."""

    def __init__(self, steer_sign: float, no_openings: bool = False):
        self.steer_sign = steer_sign
        self.no_openings = no_openings
        self.sent: dict = {}
        self.hold: dict = {}
        self.ok = False
        self.last_error = ""
        self.lock = threading.Lock()  # push(loop 스레드)와 sync_openings(heartbeat 스레드)가 sent·hold 를 같이 씀
        self.put_at: dict = {}
        self.forwarded = 0.0
        self.local = threading.local()

    def _req(self, method: str, path: str) -> str:
        """연결을 재사용한다: 값마다 새로 연결하면 에뮬레이터가 바쁠 때 그만큼 늦는다. 스레드마다 하나"""
        for attempt in (0, 1):
            conn = getattr(self.local, "conn", None) or http.client.HTTPConnection("127.0.0.1", VC_PORT, timeout=1)
            self.local.conn = conn
            try:
                conn.request(method, path)
                r = conn.getresponse()
                body = r.read().decode()
                if r.status >= 400:
                    raise OSError(f"HTTP {r.status}: {body[:120]}")
                return body
            except (OSError, http.client.HTTPException) as e:
                conn.close()
                self.local.conn = None
                if attempt or (isinstance(e, OSError) and str(e).startswith("HTTP ")):
                    raise OSError(str(e)) from e
        raise OSError("연결 실패")

    def heartbeat_loop(self):
        # Virtual Console 은 브라우저 세션(3초 타임아웃)이 살아 있어야 쓰기를 받는다
        while True:
            try:
                self._req("POST", "/browser/session?client=askthecar-bridge")
                self.sync_openings()
                if not self.ok:
                    print("[vc] Virtual Console 연결됨")
                self.ok = True
            except OSError as e:
                if self.ok or self.last_error != str(e):
                    print(f"[vc] Virtual Console 연결 안 됨 ({e}). 에뮬레이터가 켜져 있는지 확인")
                self.ok, self.last_error = False, str(e)
                if time.time() - self.forwarded > 10:  # 에뮬레이터가 꺼진 동안 adb 를 초당 한 번씩 띄우지 않게
                    self.forwarded = time.time()
                    forward_vc_port()
            time.sleep(1)

    def loop(self):
        """20Hz UDP 경로를 HTTP 로 막지 않게, 최신 값만 따로 보낸다(밀리면 옛 값은 건너뜀)"""
        last = None
        while True:
            with Shared.lock:
                a = Shared.latest
            if a is not None and a is not last:
                last = a
                try:
                    self.push(a)
                except Exception as e:
                    print(f"[vc] 보내기 오류: {e!r}")
            time.sleep(0.05)

    def push(self, a: dict):
        if not self.ok:
            return
        with self.lock:
            self._push(a)

    def _push(self, a: dict):
        controls = {
            "speed": round(a["speed"]),
            "accelerator": a["throttlePct"],
            "brake": a["brakePct"],
            # 확인한 동작: VC steering -90 → Pleos SDK +90. 그래서 부호를 뒤집어 SDK 값 = 앞바퀴(좌 음수)
            "steering": max(-540, min(540, round(self.steer_sign * a["steer"]))),
        }
        self._put_if_changed("controls", controls, lambda c: "/vehicle/controls?" + "&".join(f"{k}={v}" for k, v in c.items()))
        self._put_if_changed("gear", VC_GEAR[a["gear"]], lambda g: f"/vehicle/gear?gear={g}")
        self._put_if_changed("signal", VC_SIGNAL[a["signal"]], lambda m: f"/vehicle/turn-signal?mode={m}")
        for name, _, param, _ in OPENINGS:  # 문·후드·트렁크: 게임 → 에뮬레이터 (반대는 sync_openings)
            if self.no_openings:  # 충돌로 열린 트렁크가 Pleos "트렁크 열림" 창을 띄워 사고 팝업을 가림(시연은 끔)
                break
            if time.time() > self.hold.get(name, 0):  # 에뮬레이터 쪽 명령 직후엔 게임이 따라올 때까지 기다림(되돌려 보내지 않게)
                self._put_if_changed(name, a["openings"][name], lambda v, param=param: f"/vehicle/body?{param}={str(v).lower()}")
        # 비상등 = 좌·우 지시등 경고등 동시 깜빡임: 앱은 실차처럼 Pleos SDK Telltales 로 읽는다
        for t in ("TurnSignalLeft", "TurnSignalRight"):
            self._put_if_changed(t, a["hazard"], lambda on, t=t: f"/vehicle/telltales/simulator?type={t}&state={'Blink' if on else 'Off'}&color=Green")

    def sync_openings(self):
        """에뮬레이터(앱 SDK·Pleos 화면)에서 문·후드·트렁크가 바뀌면 게임 차에도 (모드가 UDP 4446 명령을 받음)"""
        try:
            body = json.loads(self._req("GET", "/vehicle/state"))["body"]
        except (OSError, ValueError, KeyError):
            return
        with self.lock:
            self._sync(body)

    def _sync(self, body: dict):
        for name, _, _, (group, key) in OPENINGS:
            vc = body.get(group, {}).get(key)
            if time.time() - self.put_at.get(name, 0) < 2:  # 방금 보낸 값이 VC 상태에 아직 안 보일 수 있음 → 게임에 되돌려 보내지 않음
                continue
            if name in self.sent and isinstance(vc, bool) and vc != self.sent[name]:  # 게임 값을 한 번은 보낸 뒤부터
                print(f"[vc] 에뮬레이터 {name} {'열림' if vc else '닫힘'} → 게임에도")
                game_command(f"{name} {int(vc)}")
                self.sent[name] = vc  # 게임이 따라오면 push 가 다시 보내지 않음
                self.hold[name] = time.time() + 4

    def close_trunk(self):
        try:
            for v in ("true", "false"):
                self._req("PUT", f"/vehicle/body?trunk={v}")
                time.sleep(0.5)
        except (OSError, ValueError) as e:
            print(f"[vc] 트렁크 닫기 실패 ({e})")

    def _put_if_changed(self, key, value, path_of):
        if self.sent.get(key) == value:
            return
        try:
            body = self._req("PUT", path_of(value))
            if '"error"' in body:
                print(f"[vc] {key} 거부됨: {body[:120]}")
            self.sent[key] = value
            self.put_at[key] = time.time()
        except OSError as e:
            print(f"[vc] {key} 전송 실패: {e}")


def yaw_rate(prev, now_t: float, heading_deg: float):
    """게임 방향(북쪽 기준 시계 도) 두 개 → 자이로 세로축 값(라디안/초, 왼쪽으로 돌면 +). 처음이거나 간격이 이상하면 0"""
    if prev is None or not 0.01 < now_t - prev[0] < 1.0:
        return 0.0
    d = (heading_deg - prev[1] + 540) % 360 - 180
    return -math.radians(d) / (now_t - prev[0])


class EmulatorConsole:
    """에뮬레이터 콘솔(5554)로 가속도 센서 값을 넣는다 → 앱은 안드로이드 표준 SensorManager 로 충격을 읽는다."""

    def __init__(self, port: int = 5554):
        self.port, self.sock, self.warned = port, None, False
        self.next_try = 0.0
        self.hold = (0.0, 0.0, (0.0, 0.0, 0.0))  # (까지, 수평 크기, 값) 센 충격을 잠깐 붙잡아 둠
        self.prev_heading = None  # (시각, 방향 도) 자이로 계산용

    def _connect(self):
        token = pathlib.Path.home().joinpath(".emulator_console_auth_token").read_text().strip()
        s = socket.create_connection(("127.0.0.1", self.port), timeout=1)
        try:
            s.recv(4096)
            s.sendall(f"auth {token}\n".encode())
            s.recv(4096)
        except OSError:
            s.close()
            raise
        self.sock = s

    def set_acceleration(self, lateral: float, longitudinal: float, vertical: float, heading_deg: float | None = None):
        # 충돌은 0.1~0.2초뿐 → 2g 넘는 값은 0.3초 붙잡아 둬서 앱이 놓치지 않게 (더 센 값이 오면 바꿈)
        now, mag = time.time(), math.hypot(lateral, longitudinal)
        gyro = yaw_rate(self.prev_heading, now, heading_deg) if heading_deg is not None else None
        if heading_deg is not None:
            self.prev_heading = (now, heading_deg)
        if mag >= 2 * 9.81 and (now > self.hold[0] or mag > self.hold[1]):
            self.hold = (now + 0.3, mag, (lateral, longitudinal, vertical))
        elif now <= self.hold[0]:
            lateral, longitudinal, vertical = self.hold[2]
        if self.sock is None and now < self.next_try:  # 실패 뒤 3초는 다시 붙지 않음 (20Hz 재접속 방지)
            return
        try:
            if self.sock is None:
                self._connect()
                print("[sensor] 에뮬레이터 가속도 센서 연결됨")
            # 에뮬레이터 화면이 세워진 방향 기준: y 축이 중력(9.81). 앱은 중력을 빼고 크기만 본다
            self.sock.sendall(f"sensor set acceleration {lateral:.2f}:{9.81 + vertical:.2f}:{longitudinal:.2f}\n".encode())
            self.sock.recv(256)
            if gyro is not None:  # 실차의 자이로 센서 자리 (앱이 궤적 계산에 씀). 축은 가속도와 같이 y = 세로
                self.sock.sendall(f"sensor set gyroscope 0:{gyro:.4f}:0\n".encode())
                self.sock.recv(256)
            self.warned = False
        except OSError as e:
            if not self.warned:
                print(f"[sensor] 에뮬레이터 콘솔 연결 실패 ({e})")
                self.warned = True
            if self.sock:
                self.sock.close()
            self.sock, self.next_try = None, now + 3


    def set_location(self, lat: float, lon: float):
        """에뮬레이터 GPS (앱은 실차처럼 Pleos FusedLocation 으로 읽는다)"""
        if self.sock is None:
            return
        try:
            self.sock.sendall(f"geo fix {lon:.7f} {lat:.7f}\n".encode())
            self.sock.recv(256)
        except OSError:
            self.sock.close()
            self.sock, self.next_try = None, time.time() + 3


def game_command(cmd: str, port: int = 4446):
    """게임 모드(askthecar.lua)에 명령 한 줄 (UDP). 예: "trunk 1" """
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
        s.sendto(cmd.encode(), ("127.0.0.1", port))


def forward_vc_port():
    if ADB.exists():
        subprocess.run([str(ADB), "forward", "tcp:8088", "tcp:8088"], capture_output=True)


def obs(request_type: str, password: str = "", port: int = 4455, data: dict | None = None) -> dict:
    """OBS 웹소켓(v5, OBS 28+ 내장)에 요청 하나를 보내고 responseData 를 돌려준다. 실패하면 예외."""
    with socket.create_connection(("127.0.0.1", port), timeout=3) as s:
        key = base64.b64encode(os.urandom(16)).decode()
        s.sendall((f"GET / HTTP/1.1\r\nHost: 127.0.0.1:{port}\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                   f"Sec-WebSocket-Key: {key}\r\nSec-WebSocket-Version: 13\r\n"
                   f"Sec-WebSocket-Protocol: obswebsocket.json\r\n\r\n").encode())
        f = s.makefile("rb")
        if b" 101 " not in f.readline():
            raise ConnectionError("OBS 웹소켓 연결 거부")
        while f.readline() not in (b"\r\n", b""):  # 나머지 HTTP 머리글
            pass

        def send(obj):  # 클라이언트 프레임은 마스킹 필수
            data, mask = json.dumps(obj).encode(), os.urandom(4)
            n = len(data)
            head = bytes([0x81, 0x80 | n]) if n < 126 else bytes([0x81, 0x80 | 126]) + struct.pack(">H", n)
            s.sendall(head + mask + bytes(b ^ mask[i % 4] for i, b in enumerate(data)))

        def recv():
            hdr = f.read(2)
            if len(hdr) < 2:
                raise ConnectionError("OBS 가 연결을 끊음 (비밀번호 확인)")
            n = hdr[1] & 0x7F
            if n == 126:
                n = struct.unpack(">H", f.read(2))[0]
            elif n == 127:
                n = struct.unpack(">Q", f.read(8))[0]
            return json.loads(f.read(n))

        hello = recv()["d"]
        ident = {"rpcVersion": 1, "eventSubscriptions": 0}
        if "authentication" in hello:
            a = hello["authentication"]
            secret = base64.b64encode(hashlib.sha256((password + a["salt"]).encode()).digest()).decode()
            ident["authentication"] = base64.b64encode(hashlib.sha256((secret + a["challenge"]).encode()).digest()).decode()
        send({"op": 1, "d": ident})
        recv()  # op 2 Identified
        send({"op": 6, "d": {"requestType": request_type, "requestId": "1", **({"requestData": data} if data else {})}})
        while (msg := recv())["op"] != 7:
            pass
        mask = os.urandom(4)  # 정상 종료(1000). 그냥 끊으면 OBS 가 매번 "연결 끊김" 알림을 띄움
        s.sendall(bytes([0x88, 0x82]) + mask + bytes(b ^ mask[i % 4] for i, b in enumerate(struct.pack(">H", 1000))))
        try:  # OBS 의 닫기 응답까지 받고 끊는다
            while (h := f.read(2)) and len(h) == 2 and h[0] & 0x0F != 0x8:
                f.read(h[1] & 0x7F if h[1] & 0x7F < 126 else struct.unpack(">H", f.read(2))[0])
        except OSError:
            pass
        st = msg["d"]["requestStatus"]
        if not st["result"]:
            raise RuntimeError(f"{request_type}: {st.get('comment') or st['code']}")
        return msg["d"].get("responseData") or {}


def secret(key: str) -> str:
    """프로젝트 루트 secrets.properties(git 제외)에서 값 하나. 없으면 빈 문자열"""
    f = HERE.parents[1] / "secrets.properties"
    if not f.exists():
        return ""
    for line in f.read_text(encoding="utf-8").splitlines():
        k, _, v = line.partition("=")
        if k.strip() == key and not line.lstrip().startswith("#"):
            return v.strip()
    return ""


# 시연용 다각도 영상: 모드(askthecarRec)가 띄운 시점 창을 OBS 리플레이 버퍼로 계속 녹화한다
MC_PROFILE, MC_SCENE, MC_SOURCE = "AskTheCar", "AskTheCar 다각도", "다각도 카메라"
MC_WINDOW = "atc_cameras:GameEngineMainWindow:BeamNG.drive.x64.exe"
MC_W, MC_H = 1440, 540  # 타일 480×270 × 3열 2줄. 모드의 W·H·COLUMNS 와 같아야 한다
CLIPS = RUNS / "clips"


def setup_multicam(password: str) -> bool:
    """OBS 를 다각도 녹화용으로 맞춘다. 프로필을 새로 골랐으면 True: 리플레이 버퍼가 그 프로필로 다시 켜져야 해서 OBS 재시작이 필요하다"""
    o = lambda req, data=None: obs(req, password, data=data)
    CLIPS.mkdir(parents=True, exist_ok=True)
    profiles = o("GetProfileList")
    restart = profiles["currentProfileName"] != MC_PROFILE
    if MC_PROFILE not in profiles["profiles"]:
        o("CreateProfile", {"profileName": MC_PROFILE})
    if restart:
        o("SetCurrentProfile", {"profileName": MC_PROFILE})
    # 저장 폴더는 PC·드라이브마다 다르다. 바뀌면 리플레이 버퍼를 다시 켜야 적용된다
    restart = restart or o("GetProfileParameter", {"parameterCategory": "SimpleOutput", "parameterName": "FilePath"}).get("parameterValue") != str(CLIPS)
    for cat, name, value in (("Output", "Mode", "Simple"), ("SimpleOutput", "RecRB", "true"), ("SimpleOutput", "RecRBTime", "45"),
                             ("SimpleOutput", "RecRBSize", "256"), ("SimpleOutput", "RecQuality", "Small"), ("SimpleOutput", "RecEncoder", "nvenc"),
                             ("SimpleOutput", "RecFormat2", "mp4"), ("SimpleOutput", "FilePath", str(CLIPS))):
        o("SetProfileParameter", {"parameterCategory": cat, "parameterName": name, "parameterValue": value})
    try:
        active = o("GetReplayBufferStatus")["outputActive"]
    except RuntimeError:  # 프로필을 막 바꾸면 "not available"
        active = False
    if not active:  # 녹화 중에는 화면 크기를 못 바꾼다
        o("SetVideoSettings", {"baseWidth": MC_W, "baseHeight": MC_H, "outputWidth": MC_W, "outputHeight": MC_H, "fpsNumerator": 30, "fpsDenominator": 1})
    if MC_SCENE not in [x["sceneName"] for x in o("GetSceneList")["scenes"]]:
        o("CreateScene", {"sceneName": MC_SCENE})
    o("SetCurrentProgramScene", {"sceneName": MC_SCENE})
    if MC_SOURCE not in [x["sourceName"] for x in o("GetSceneItemList", {"sceneName": MC_SCENE})["sceneItems"]]:
        o("CreateInput", {"sceneName": MC_SCENE, "inputName": MC_SOURCE, "inputKind": "window_capture", "inputSettings": {}})
    # priority 1 = 창 제목이 같아야 함. 아니면 시점 창이 없을 때 게임 본 화면을 대신 찍는다
    o("SetInputSettings", {"inputName": MC_SOURCE, "inputSettings": {"window": MC_WINDOW, "method": 2, "priority": 1, "cursor": False}})
    item = o("GetSceneItemId", {"sceneName": MC_SCENE, "sourceName": MC_SOURCE})["sceneItemId"]
    o("SetSceneItemTransform", {"sceneName": MC_SCENE, "sceneItemId": item, "sceneItemTransform": {"positionX": 0, "positionY": 0, "scaleX": 1, "scaleY": 1, "boundsType": "OBS_BOUNDS_NONE"}})
    return restart


def rec_on_file() -> pathlib.Path | None:
    game = find_beamng()
    return beamng_user_folder(game) / "askthecar" / "rec_on" if game else None


def keep_multicam(password: str):
    while True:  # launcher 상태표용. OBS 를 나중에 켜도 된다
        try:
            Shared.obs_ok = obs("GetReplayBufferStatus", password)["outputActive"]
        except Exception:
            Shared.obs_ok = False
        time.sleep(5)


def save_clip() -> tuple[pathlib.Path, float] | None:
    """리플레이 버퍼를 파일로 저장. (파일, 저장을 요청한 시각 = 영상 끝 시각). 게임은 멈추지 않는다"""
    pw = Shared.obs_password
    if pw is None:
        return None
    last = lambda: obs("GetLastReplayBufferReplay", pw)["savedReplayPath"]
    try:
        before = last()
    except Exception:
        before = ""
    t = time.time()
    try:
        obs("SaveReplayBuffer", pw)
        while time.time() - t < 15:
            time.sleep(0.5)
            p = last()
            if p and p != before and os.path.exists(p):
                size = os.path.getsize(p)
                time.sleep(0.5)
                if os.path.getsize(p) == size:  # 다 쓴 파일만
                    for old in sorted(CLIPS.glob("*.mp4"), key=lambda f: f.stat().st_mtime)[:-5]:
                        old.unlink(missing_ok=True)
                    return pathlib.Path(p), t
    except Exception as e:
        print(f"다각도 영상 저장 실패: {e}")
    return None


def game_camera(wait: float = 3.0) -> bytes | None:
    """모드에 앞 사진(실차 카메라 자리)을 부탁하고 <사용자 폴더>/askthecar/cam/ 에 저장되길 기다린다"""
    if not Shared.roads:
        return None
    folder = Shared.roads.parent
    front = folder / "cam" / "front.png"
    t = time.time()
    try:
        folder.mkdir(parents=True, exist_ok=True)
        (folder / "cam_request").write_text("1")
        while time.time() - t < wait:
            if front.exists() and front.stat().st_mtime >= t - 0.5:
                data = front.read_bytes()
                if b"IEND" in data[-12:]:  # 다 쓴 PNG 만. 쓰는 중에 읽으면 아래쪽이 검게 잘린다
                    return data
            time.sleep(0.1)
    except OSError as e:
        print(f"게임 카메라 실패: {e}")
    return None


def reset_watcher():
    """게임에서 차를 리셋(R 키)하면 앱도 사고 화면·기록 중인 사고를 닫는다"""
    while True:
        time.sleep(0.3)
        f = Shared.roads and Shared.roads.parent / "reset"
        if f and f.exists():
            f.unlink(missing_ok=True)
            print("[beamng] 게임 차 리셋 → 앱도 리셋")
            if Shared.vc:  # 앱이 연 트렁크(SDK)는 VC 상태에 안 잡혀 왼쪽 차 그림이 열린 채 남는다: 열었다 닫아야 풀린다
                Shared.vc.close_trunk()
            subprocess.run([str(ADB), "shell", "am", "broadcast", "--user", "10", "-a", "com.askthecar.RESET", "-n", "com.askthecar/.SimReceiver"],
                           stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, creationflags=subprocess.CREATE_NO_WINDOW)


class PcVoice:
    """앱 안내 음성을 PC 스피커로. 게임·에뮬레이터를 같이 돌리면 에뮬레이터 소리가 끊겨서 시연 때는 이쪽을 쓴다."""
    SCRIPT = ("[Console]::InputEncoding=[Text.Encoding]::UTF8;Add-Type -AssemblyName System.Speech;"
              "$s=New-Object System.Speech.Synthesis.SpeechSynthesizer;$s.SelectVoice('Microsoft Heami Desktop');"
              "while(($l=[Console]::In.ReadLine()) -ne $null){$s.SpeakAsyncCancelAll();if($l.Trim()){[void]$s.SpeakAsync($l)}}")
    proc = None
    lock = threading.Lock()

    @classmethod
    def available(cls):
        r = subprocess.run(["powershell", "-NoProfile", "-Command", "Add-Type -AssemblyName System.Speech;"
                            "(New-Object System.Speech.Synthesis.SpeechSynthesizer).GetInstalledVoices().VoiceInfo.Name"],
                           capture_output=True, text=True, creationflags=subprocess.CREATE_NO_WINDOW)
        return "Heami" in r.stdout

    @classmethod
    def say(cls, text):
        with cls.lock:
            if cls.proc is None or cls.proc.poll() is not None:
                cls.proc = subprocess.Popen(["powershell", "-NoProfile", "-Command", cls.SCRIPT], stdin=subprocess.PIPE,
                                            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, creationflags=subprocess.CREATE_NO_WINDOW)
            cls.proc.stdin.write((text.replace("\n", " ") + "\n").encode("utf-8"))
            cls.proc.stdin.flush()


class FileHandler(http.server.BaseHTTPRequestHandler):
    def log_message(self, *a):
        pass  # launcher 가 5초마다 /status 를 묻는다

    def do_GET(self):
        if self.path == "/status":  # launcher 상태 표시용
            now = time.time()
            data = json.dumps({"game": Shared.latest_at > 0 and now - Shared.latest_at < 2, "app": now - Shared.app_at < 5, "obs": Shared.obs_ok,
                               "vc": bool(Shared.vc and Shared.vc.ok), "voice": Shared.pc_voice}).encode()
            self.send_response(200)
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            return self.wfile.write(data)
        if self.path.split("?")[0] == "/say":
            if not Shared.pc_voice:
                return self.send_error(404)  # 앱이 에뮬레이터 TTS 로 대신 읽는다
            PcVoice.say(urllib.parse.parse_qs(urllib.parse.urlparse(self.path).query, keep_blank_values=True).get("text", [""])[0])
            self.send_response(200)
            self.send_header("Content-Length", "0")
            return self.end_headers()
        if self.path.split("?")[0] == "/clip/cameras.mp4":
            return self.clip()
        if self.path.split("?")[0] == "/cam/latest.jpg":
            return self.snapshot()
        if self.path.split("?")[0] == "/map/roads.json":
            if not (Shared.roads and Shared.roads.exists()):
                return self.send_error(404)
            data = Shared.roads.read_bytes()
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            return self.wfile.write(data)
        self.send_error(404)

    def clip(self):
        saved = save_clip()
        if not saved:
            return self.send_error(503)
        path, end = saved
        self.send_response(200)
        self.send_header("Content-Type", "video/mp4")
        self.send_header("Content-Length", str(path.stat().st_size))
        self.send_header("X-Clip-End", f"{end:.3f}")
        self.end_headers()
        with path.open("rb") as f:
            shutil.copyfileobj(f, self.wfile)

    def snapshot(self):
        """게임이 찍은 차 앞 카메라 시점(모드 askthecarCam) = 실차 카메라 사진의 대체본"""
        data = game_camera()
        t = time.time()
        if data:
            self.send_response(200)
            self.send_header("Content-Type", "image/png")
            self.send_header("Content-Length", str(len(data)))
            self.send_header("X-Shot-Time", f"{t:.3f}")
            self.end_headers()
            return self.wfile.write(data)
        self.send_error(404)

    def log_message(self, *a):
        pass

class Shared:
    latest: dict | None = None
    latest_at = 0.0  # 마지막 게임 패킷 시각
    full = False  # --no-vc 이면 모든 값을 보조 통로로
    obs_password: str | None = None  # None = OBS 안 씀
    pc_voice = False  # 앱 안내 음성을 PC 스피커로
    obs_ok = False  # 다각도 녹화(리플레이 버퍼) 켜짐
    app_at = 0.0  # 앱 보조 통로가 살아 있던 마지막 시각
    vc = None
    roads: pathlib.Path | None = None  # 모드가 쓴 게임 도로 파일 (<사용자 폴더>/askthecar/roads.json)

    @staticmethod
    def map_version() -> int:
        """도로 파일이 바뀐 시각(앱은 바뀌면 /map/roads.json 을 다시 받음). 없으면 0"""
        try:
            return int(Shared.roads.stat().st_mtime) if Shared.roads else 0
        except OSError:
            return 0
    lock = threading.Lock()


# 실차 SDK 값을 대신하는 것(에어백, 제한속도, 시동)과 게임 지도 내비 배경(gamePos, mapVersion)만. 정답 값은 runs/ 정답지에만
SIDE_KEYS = ("airbag", "ignition", "roadLimit", "gamePos", "mapVersion")


class AppHandler(socketserver.BaseRequestHandler):
    def handle(self):
        print(f"[app] 보조 통로 접속: {self.client_address}")
        self.request.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)  # 작은 값을 모아 보내느라 늦지 않게
        try:
            while True:
                Shared.app_at = time.time()
                with Shared.lock:
                    s, at = Shared.latest, Shared.latest_at
                # 게임이 멈추거나 꺼지면(1초 넘게 패킷 없음) 옛 값을 보내지 않음 → 앱은 3초 뒤 끊고 실차(SDK) 값으로
                if s is not None and time.time() - at < 1:
                    out = dict(s) if Shared.full else {k: s[k] for k in SIDE_KEYS}
                    out["t"] = round(time.time(), 2)
                    self.request.sendall((json.dumps(out) + "\n").encode())
                elif select.select([self.request], [], [], 0)[0] and not self.request.recv(1):
                    return print("[app] 연결 끊김 (앱이 닫음)")  # 보낼 게 없을 땐 끊긴 걸 못 알아채 연결이 쌓였음
                time.sleep(0.05)  # 게임 값은 초당 20번
        except (BrokenPipeError, ConnectionResetError, ConnectionAbortedError):
            print("[app] 연결 끊김")


def udp_loop(conv: Converter, console: "EmulatorConsole | None"):
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.bind(("127.0.0.1", UDP_PORT))
    RUNS.mkdir(exist_ok=True)
    run = RUNS / time.strftime("%Y%m%d_%H%M%S.jsonl")
    print(f"[beamng] UDP {UDP_PORT} 대기 (게임에서 차를 타면 값이 들어옴). 정답지 기록: {run}")
    last_print = last_err = last_geo = 0.0
    started = False  # 게임을 launcher 보다 늦게 켜도 시동 화면이 뜨게
    with run.open("a", encoding="utf-8") as log:
        while True:
            data, _ = sock.recvfrom(1024)
            try:
                p = parse(data)
            except Exception as e:  # 이상한 패킷 하나로 스레드가 조용히 죽으면 브리지는 켜져 보이는데 데이터가 멈춤
                p = None
                if time.time() - last_err >= 1:
                    last_err = time.time()
                    print(f"[beamng] 패킷 해석 오류(건너뜀): {e!r}")
            if p is None:
                continue
            if not started:
                started = True
                subprocess.Popen([str(ADB), "shell", "am", "broadcast", "--user", "10", "-a", "com.askthecar.START", "-n", "com.askthecar/.SimReceiver"],
                                 stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, creationflags=subprocess.CREATE_NO_WINDOW)
            now = time.time()
            try:
                app = conv.convert(p, now)
            except Exception as e:
                if now - last_err >= 1:
                    last_err = now
                    print(f"[beamng] 값 변환 오류(건너뜀): {e!r}")
                continue
            with Shared.lock:
                Shared.latest, Shared.latest_at = app, now  # Virtual Console 은 vc.loop 스레드가 10Hz 로 따로 보냄
            if console:  # 20Hz 그대로: 충격은 짧아서 놓치면 안 됨
                ax, ay = conv.pulse or (p["accX"], p["accY"])
                conv.pulse = None
                console.set_acceleration(ax, ay, p["accZ"], p["yaw"])
                if now - last_geo >= 1:
                    last_geo = now
                    console.set_location(app["lat"], app["lon"])
            log.write(json.dumps({"t": round(now, 3), "game": {k: v for k, v in p.items() if k != "format"}, "app": app}) + "\n")
            if conv.screen is not None:  # 게임 속 시동 = 헤드유닛 전원. 끄고 켜는 건 앱의 덮개(ScreenCover)가 바로 한다. 여기선 launcher 가 재워 둔 화면만 깨운다
                if conv.screen:
                    threading.Thread(target=subprocess.run, args=([str(ADB), "shell", "input", "keyevent", "KEYCODE_WAKEUP"],), kwargs={"capture_output": True, "timeout": 10}, daemon=True).start()
                conv.screen = None
            for e in conv.events:
                print(f"[이벤트] {e}")
            conv.events.clear()
            if now - last_print >= 1:  # 1초마다 상태 한 줄 (조향 부호 확인용)
                last_print = now
                log.flush()  # 브리지가 죽어도 정답지 끝부분이 남게
                print(f"속도 {app['speed']:5.1f}km/h  핸들 {p['steeringWheel']:6.1f}° → 앞바퀴 {app['steer']:5.1f}°  "
                      f"브레이크 {p['brake']:.2f}  지시등 {app['signal']:4}  기어 {p['gear']:2}  "
                      f"위치 ({p['posX']:.0f},{p['posY']:.0f})  손상 {p['damage']:.0f}")


STEAM_APP_ID = "284160"  # BeamNG.drive


def steam_libraries() -> list[pathlib.Path]:
    """Steam 라이브러리 폴더들 (레지스트리의 Steam 위치 → steamapps/libraryfolders.vdf 의 "path")"""
    try:
        import winreg
        with winreg.OpenKey(winreg.HKEY_CURRENT_USER, r"Software\Valve\Steam") as k:
            steam = pathlib.Path(winreg.QueryValueEx(k, "SteamPath")[0])
    except OSError:
        steam = pathlib.Path(os.environ.get("ProgramFiles(x86)", r"C:\Program Files (x86)")) / "Steam"
    vdf = steam / "steamapps" / "libraryfolders.vdf"
    paths = [steam]
    if vdf.exists():
        paths += [pathlib.Path(p.replace("\\\\", "\\")) for p in re.findall(r'"path"\s+"([^"]+)"', vdf.read_text(encoding="utf-8", errors="ignore"))]
    return list(dict.fromkeys(paths))


def find_beamng() -> pathlib.Path | None:
    """이 PC에 설치된 BeamNG (appmanifest 의 installdir)"""
    for lib in steam_libraries():
        acf = lib / "steamapps" / f"appmanifest_{STEAM_APP_ID}.acf"
        if acf.exists():
            m = re.search(r'"installdir"\s+"([^"]+)"', acf.read_text(encoding="utf-8", errors="ignore"))
            game = lib / "steamapps" / "common" / (m.group(1) if m else "BeamNG.drive")
            if (game / "BeamNG.drive.exe").exists():
                return game
    return None


def beamng_user_folder(game: pathlib.Path) -> pathlib.Path:
    """startup.ini 의 UserPath(있으면) → 없으면 %LOCALAPPDATA%\\BeamNG\\BeamNG.drive. 버전별 폴더 'current' 가 있으면 그 안"""
    uf = None
    ini = game / "startup.ini"
    if ini.exists():
        for line in ini.read_text(encoding="utf-8", errors="ignore").splitlines():
            k, _, v = line.partition("=")
            if k.strip() == "UserPath" and v.strip():
                uf = pathlib.Path(v.strip())
                if not uf.is_absolute():
                    uf = game / uf
    uf = uf or pathlib.Path(os.environ["LOCALAPPDATA"]) / "BeamNG" / "BeamNG.drive"
    return uf / "current" if (uf / "current").exists() else uf


def setup_beamng() -> str:
    """PC마다 설치된 BeamNG 를 찾아 우리 모드를 연결하고 설정을 점검한다. 결과 한 줄"""
    mod = HERE.parent / "beamng" / "mod" / "askthecar"
    game = find_beamng()
    if not game:
        return "BeamNG 못 찾음 - Steam 에 BeamNG.drive 를 설치하세요"
    uf = beamng_user_folder(game)
    if not uf.exists():
        return f"BeamNG 사용자 폴더가 아직 없음({uf}) - 게임을 한 번 켰다 끈 뒤 브리지를 다시 켜세요"
    link = uf / "mods" / "unpacked" / "askthecar"
    if not (link / "lua").exists() or link.resolve() != mod.resolve():
        link.parent.mkdir(parents=True, exist_ok=True)
        if link.exists() or os.path.islink(link) or link.is_junction():
            if not link.is_junction():
                return f"모드 자리에 다른 폴더가 있음({link}) - 직접 확인하세요(지우지 않음)"
            os.rmdir(link)  # 정션만 지운다(가리키던 원본은 그대로)
        subprocess.run(["cmd", "/c", "mklink", "/J", str(link), str(mod)], capture_output=True)
        if not (link / "lua").exists():
            return f"모드 연결 실패({link})"
    warn = []
    for f in (uf / "settings" / "game-settings.json", uf / "settings" / "cloud" / "settings.json"):
        t = f.read_text(encoding="utf-8", errors="ignore") if f.exists() else ""
        if re.search(r'"protocols_others_enabled"\s*:\s*false', t):
            warn.append("옵션 > 프로토콜 > 기타 프로토콜이 꺼져 있음(켜 주세요)")
        if re.search(r'"multiseat"\s*:\s*true', t):
            warn.append("멀티시트가 켜져 있으면 프로토콜이 안 돌아감")
    if "," in str(uf):
        warn.append("사용자 폴더 경로에 쉼표가 있음")
    return f"BeamNG 준비: 게임 {game} · 모드 {link}" + ("".join(f" · 주의: {w}" for w in warn))


def self_check():
    # 자이로: 북(0°)→동(10°)으로 0.5초 = 시계 방향(오른쪽) → 음수, 360 넘김 처리
    assert abs(yaw_rate((0.0, 0.0), 0.5, 10.0) + math.radians(20)) < 1e-9
    assert abs(yaw_rate((0.0, 355.0), 0.5, 5.0) + math.radians(20)) < 1e-9
    assert yaw_rate(None, 1.0, 10.0) == 0.0
    raw = STRUCT.pack(b"ATC1", 10.0, 90.0, 0.2, 0.5, 0.3, 0.0, b"D\0\0\0", 1, 100.0, 200.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 27.78, 4.0, 1, 4.5, -1.0, 180.0, 1, *([30.0] * 23 + [2.5]), 2, 4.5, -1.0, 180.0, 20.0, 3.0, 0.0, *([0.0] * 18), 0.0, 0.25, 0.0, 0.0, 0.0, 0.0)
    p = parse(raw)
    conv = Converter(ratio=15, sign=-1, airbag_g=5, damage_jump=2000)
    a = conv.convert(p, 0.0)
    assert a["speed"] == 36.0 and a["steer"] == -6.0 and a["brake"] and a["signal"] == "L" and not a["airbag"]
    assert a["roadLimit"] == 100 and a["roadLanes"] == 4 and a["throttlePct"] == 30 and a["brakePct"] == 50
    assert a["simOther"] == {"fwd": 4.5, "right": -1.0, "yaw": 180.0}
    haz = conv.convert(parse(STRUCT.pack(*(list(STRUCT.unpack(raw))[:8] + [7] + list(STRUCT.unpack(raw))[9:]))), 0.06)
    assert haz["hazard"] and haz["signal"] == "none", "비상등이면 지시등 아님"
    assert a["hazard"] is False and conv.convert(parse(STRUCT.pack(*(list(STRUCT.unpack(raw))[:8] + [4] + list(STRUCT.unpack(raw))[9:]))), 0.05)["hazard"]
    assert a["simCars"] == [{"x": 4.5, "y": -1.0, "h": 180.0}, {"x": 20.0, "y": 3.0, "h": 0.0}]
    assert a["openings"]["trunk"] is False and haz["openings"]["hood"] is False
    ops = conv.convert(parse(STRUCT.pack(*(list(STRUCT.unpack(raw))[:8] + [128 | 2048] + list(STRUCT.unpack(raw))[9:]))), 0.07)["openings"]
    assert ops["door_fl"] and ops["hood"] and not ops["trunk"] and not ops["door_rr"], ops
    assert a["zones"]["FR"] == 0.25 and a["zones"]["FL"] == 0.0
    assert a["gamePos"] == {"x": 100.0, "y": 200.0, "h": 0.0}
    assert a["simScan"][23] == 2.5 and a["simScan"][0] == 30.0 and len(a["simScan"]) == 24
    crash = STRUCT.pack(b"ATC1", 0.0, 90.0, 0.2, 1.0, 0.0, 0.0, b"D\0\0\0", 0, 100.0, 200.0, 0.0, 0.0, -80.0, 0.0, 0.0, 500.0, 0.0, 0.0, 0, 0.0, 0.0, 0.0, 0, *([30.0] * 24), 0, *([0.0] * 24), *([0.0] * 6))
    assert conv.convert(parse(crash), 0.1)["airbag"], "8g 충격이면 에어백"
    reset_conv = Converter(ratio=15, sign=-1, airbag_g=5, damage_jump=2000)
    assert reset_conv.convert(parse(crash), 0.0)["airbag"]
    assert not reset_conv.convert(parse(raw), 1.0)["airbag"], "손상 0 = 리셋이면 에어백 해제"
    assert not reset_conv.convert(parse(crash), 2.0)["airbag"], "리셋 뒤 3초 안의 충격·손상 증가는 판정 제외"
    assert reset_conv.convert(parse(crash), 4.1)["airbag"], "3초 지나면 다시 판정"
    bump = Converter(ratio=15, sign=-1, airbag_g=5, damage_jump=8000, impact_jump=4600)
    bump.convert({**parse(raw), "damage": 1.0, "accX": 0.0, "accY": 5.0}, 0.0)  # 풀악셀로 달리는 중
    assert not bump.convert({**parse(raw), "damage": 4000.0, "accX": 0.0, "accY": 5.0}, 0.1)["airbag"] and bump.pulse is None, "기준 아래면 펄스 없음"
    assert not bump.convert({**parse(raw), "damage": 5000.0, "accX": 0.0, "accY": 3.0}, 0.3)["airbag"], "약한 충돌은 에어백이 아님"
    assert bump.pulse is not None and abs(math.hypot(*bump.pulse) - 3.5 * 9.81) < 0.01 and bump.pulse[1] < 0, "직전 가속을 뺀 방향, 크기 3.5g"
    assert not a["ignition"] and conv.screen is False, "시동 꺼진 채 시작하면 첫 값에 화면 끄기"
    on = STRUCT.pack(*(list(STRUCT.unpack(crash))[:8] + [0x20] + list(STRUCT.unpack(crash))[9:]))
    assert conv.convert(parse(on), 0.2)["ignition"] and conv.screen is True, "시동 켜짐(level 2) → 화면 켜기"
    off = STRUCT.pack(*(list(STRUCT.unpack(crash))[:8] + [0] + list(STRUCT.unpack(crash))[9:]))
    assert conv.convert(parse(off), 0.3)["ignition"] and conv.convert(parse(off), 0.45)["ignition"], "리셋의 잠깐 꺼짐은 무시"
    assert conv.convert(parse(on), 0.5)["ignition"] and conv.ign_off_at is None
    conv.convert(parse(off), 2.0)
    assert not conv.convert(parse(off), 2.4)["ignition"] and conv.screen is False, "0.3초 넘게 꺼지면 화면 끄기"
    assert parse(b"BNG1" + raw[4:]) is None
    nan = conv.convert(parse(STRUCT.pack(*(list(STRUCT.unpack(raw))[:1] + [float("nan")] + list(STRUCT.unpack(raw))[2:]))), 0.3)
    assert nan["speed"] == 0.0 and json.dumps(nan), "물리 폭주 NaN 은 0 으로"


if __name__ == "__main__":
    sys.stdout.reconfigure(errors="replace")  # 한국어 콘솔(cp949)이 못 찍는 글자(— 등) 때문에 멈추지 않게
    ap = argparse.ArgumentParser()
    ap.add_argument("--ratio", type=float, default=15.0, help="핸들 각도 / 앞바퀴 각도 (조향비, 가정값)")
    ap.add_argument("--sign", type=float, default=-1.0, help="게임에서 왼쪽으로 꺾었을 때 앞바퀴 값이 음수가 되도록 -1 또는 1")
    ap.add_argument("--vc-steer-sign", type=float, default=-1.0, help="Virtual Console 조향 부호 (SDK 값이 앞바퀴와 같은 부호가 되도록)")
    ap.add_argument("--airbag-g", type=float, default=5.0, help="에어백 합성 충격 임계값(g)")
    # 측정(2026-11-04~05): 경미한 접촉 3천~5천, 정면 충돌 1만 2천 — 2000 은 스침에도 에어백이 됐음
    ap.add_argument("--damage-jump", type=float, default=8000.0, help="0.5초 손상도 증가 임계값")
    ap.add_argument("--impact-jump", type=float, default=4000.0, help="일반 충격 펄스를 넣을 0.5초 손상도 증가. 측정: 리셋 튐 3.1천, 스침 3~4.5천, 19km/h 4.5천, 25km/h 4.8~5.1천")
    ap.add_argument("--no-openings", action="store_true", help="게임의 문·후드·트렁크 열림을 에뮬레이터로 보내지 않음 (에뮬레이터 → 게임은 그대로)")
    ap.add_argument("--no-vc", action="store_true", help="Virtual Console 을 쓰지 않고 모든 값을 앱 보조 통로로 보냄")
    ap.add_argument("--no-obs", action="store_true", help="OBS 를 쓰지 않음 (다각도 영상 없음)")
    ap.add_argument("--obs-password", default=None, help="OBS 도구 > WebSocket 서버 설정의 비밀번호. 없으면 secrets.properties 의 obs.password")
    ap.add_argument("--pc-voice", action="store_true", help="안내 음성을 Pleos TTS 대신 PC Windows 음성으로 (에뮬레이터 소리가 끊길 때)")
    ap.add_argument("--skip-setup", action="store_true", help="BeamNG 찾기·모드 연결 점검을 건너뜀 (launcher.py 가 이미 함)")
    args = ap.parse_args()
    self_check()
    if not args.skip_setup:
        print(setup_beamng())
    game = find_beamng()
    Shared.roads = beamng_user_folder(game) / "askthecar" / "roads.json" if game else None

    if not args.no_obs:
        Shared.obs_password = args.obs_password if args.obs_password is not None else secret("obs.password")
        threading.Thread(target=keep_multicam, args=(Shared.obs_password,), daemon=True).start()
    Shared.pc_voice = args.pc_voice and PcVoice.available()
    print("안내 음성: " + ("PC 스피커 (Heami)" if Shared.pc_voice else "Pleos TTS (에뮬레이터)"), flush=True)
    threading.Thread(target=reset_watcher, daemon=True).start()
    http_srv = http.server.ThreadingHTTPServer(("127.0.0.1", FILE_PORT), FileHandler)
    threading.Thread(target=http_srv.serve_forever, daemon=True).start()

    vc = None
    if not args.no_vc:
        forward_vc_port()
        vc = Shared.vc = VirtualConsole(args.vc_steer_sign, args.no_openings)
        threading.Thread(target=vc.heartbeat_loop, daemon=True).start()
        threading.Thread(target=vc.loop, daemon=True).start()
    Shared.full = args.no_vc

    conv = Converter(args.ratio, args.sign, args.airbag_g, args.damage_jump, args.impact_jump)
    console = None if args.no_vc else EmulatorConsole()
    threading.Thread(target=udp_loop, args=(conv, console), daemon=True).start()
    socketserver.ThreadingTCPServer.allow_reuse_address = True
    # 127.0.0.1 만: 에뮬레이터의 10.0.2.2 = PC 의 127.0.0.1. 같은 와이파이의 다른 기기에 화면 캡처·위치를 열지 않음
    print(f"앱 보조 통로 대기: 127.0.0.1:{STREAM_PORT} (앱 '게임 연동' 켜기)")
    socketserver.ThreadingTCPServer(("127.0.0.1", STREAM_PORT), AppHandler).serve_forever()
