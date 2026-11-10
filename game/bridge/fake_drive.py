"""BeamNG 대신 정해진 사고 시나리오를 앱 테스트 모드로 보낸다.

앱(에뮬레이터)이 10.0.2.2:8765 로 TCP 접속하면 10Hz로 JSON 한 줄씩 보낸다.
같은 PC의 8766 포트는 블랙박스 클립(clips/latest.mp4)을 HTTP로 내준다.
나중에 BeamNG 브리지는 scenario() 대신 게임 값을 넣으면 된다.

실행: python game/bridge/fake_drive.py
"""
import http.server
import json
import math
import pathlib
import socketserver
import threading
import time

STREAM_PORT = 8765
CLIP_PORT = 8766
HZ = 10
CLIP_DIR = pathlib.Path(__file__).parent / "clips"
BASE_LAT, BASE_LON = 37.5000, 127.0300  # 서울 강남 부근 가짜 좌표


def scenario():
    """시내 직진 → 좌측 지시등 → 좌회전 시작 → 급브레이크 → 충돌. (t초, 값 dict) 생성."""
    for i in range(15 * HZ):
        t = i / HZ
        s = {"speed": 50.0, "steer": 0.0, "brake": False, "signal": "none", "airbag": False,
             "gear": "D", "cruise": False}
        if t >= 3:
            s["signal"] = "L"
        if t >= 5:
            s["steer"] = round(-min((t - 5) * 4, 8), 1)  # 앞바퀴 최대 -8°(좌)
            s["speed"] = 50 - (t - 5) * 3
        if t >= 7.5:
            s["brake"] = True
            s["speed"] = max(42.5 - (t - 7.5) * 25, 30)
        if t >= 8.0:  # 충돌: 0.2초 만에 정지
            s["speed"] = max(30 - (t - 8.0) * 150, 0)
            s["airbag"] = t >= 8.1
        if t >= 8.2:
            s.update(speed=0.0, steer=s["steer"], brake=True)
        if t >= 11:  # 충돌 3초 뒤 운전자가 기어 P
            s["gear"] = "P"
        yield t, s


def with_position(samples):
    """자전거 모델로 가짜 위경도를 만든다 (앱의 추측항법과 같은 식)."""
    x = y = yaw = 0.0
    for t, s in samples:
        v = s["speed"] / 3.6
        yaw += v * math.tan(math.radians(-s["steer"])) / 2.9 / HZ  # 좌회전(음수) = 반시계(+), y+ = 서쪽
        x += v * math.cos(yaw) / HZ
        y += v * math.sin(yaw) / HZ
        s["lat"] = round(BASE_LAT + x / 111_320, 7)
        s["lon"] = round(BASE_LON - y / (111_320 * math.cos(math.radians(BASE_LAT))), 7)
        yield t, s


class StreamHandler(socketserver.BaseRequestHandler):
    def handle(self):
        print(f"[stream] 앱 접속: {self.client_address}")
        start = time.time()
        try:
            for t, s in with_position(scenario()):
                s["t"] = round(start + t, 2)
                s["speed"] = round(s["speed"], 1)
                self.request.sendall((json.dumps(s) + "\n").encode())
                time.sleep(1 / HZ)
            print("[stream] 시나리오 끝 — 정차 상태 유지 (앱이 재접속해 사고를 반복하지 않도록 연결 유지)")
            while True:  # 앱이 테스트 모드를 끄거나 연결이 끊길 때까지
                self.request.sendall((json.dumps(s) + "\n").encode())
                time.sleep(1)
        except (BrokenPipeError, ConnectionResetError):
            print("[stream] 앱 연결 끊김")


class ClipHandler(http.server.SimpleHTTPRequestHandler):
    def __init__(self, *a, **kw):
        super().__init__(*a, directory=str(CLIP_DIR), **kw)

    def translate_path(self, path):
        # /clip/latest.mp4 → clips/latest.mp4, 그 외 경로는 거부
        return super().translate_path(path.removeprefix("/clip")) if path.startswith("/clip/") else ""


def self_check():
    rows = list(with_position(scenario()))
    crash = [s for _, s in rows if s["airbag"]]
    assert crash and crash[0]["speed"] < 30, "충돌 시점에 에어백·감속이 있어야 함"
    assert rows[-1][1]["speed"] == 0
    assert rows[60][1]["lat"] != BASE_LAT, "차가 움직여야 함"


if __name__ == "__main__":
    self_check()
    CLIP_DIR.mkdir(exist_ok=True)
    socketserver.ThreadingTCPServer.allow_reuse_address = True
    clip = http.server.ThreadingHTTPServer(("0.0.0.0", CLIP_PORT), ClipHandler)
    threading.Thread(target=clip.serve_forever, daemon=True).start()
    print(f"클립 서버: http://127.0.0.1:{CLIP_PORT}/clip/latest.mp4 ({CLIP_DIR})")
    print(f"주행 스트림 대기: 0.0.0.0:{STREAM_PORT} (앱은 10.0.2.2:{STREAM_PORT})")
    socketserver.ThreadingTCPServer(("0.0.0.0", STREAM_PORT), StreamHandler).serve_forever()
