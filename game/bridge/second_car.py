"""두 차 기록 연결 시연: PC 가 '상대 차의 앱' 대신 상대 계정(other)으로 사고 기록을 올리고, 코드 입력·동의를 대신 누른다.

실제로는 상대 차에 깔린 이 앱이 자기 차 SDK 기록을 올리고, 상대 운전자가 자기 휴대폰에서 코드·동의를 누른다(docs/실차_대응표.md).
게임에서는 crash_test.py --record-other 가 남긴 게임 AI 차 기록(위치·속도·방향)을 쓴다. 휴대폰은 1대(내 쪽)만 있으면 된다.

실행 (sync_server.py 가 켜져 있어야 함, 시작.cmd 가 켬):
  python second_car.py --fake                       게임 없이: 서버의 내 사고(--me) 옆 3m 에 가짜 상대 차. 내 사고가 없으면 가짜 내 사고도 올림
  python second_car.py --from runs/other_XXXX.json  crash_test.py --record-other 가 남긴 게임 AI 차 기록
  옵션: --server http://127.0.0.1:8767 --me demo --account other
        --claim 123456  휴대폰 "내 코드 보여 주기"에 나온 코드를 상대 쪽이 대신 입력
        (안 주면) 상대 쪽 코드를 만들어 보여 줌 → 휴대폰 "상대 코드 입력하기"에 넣기
  python second_car.py --self-check
"""
import argparse
import hashlib
import json
import math
import sys
import time
import urllib.error
import urllib.request

from beamng_bridge import yaw_rate
from fake_drive import BASE_LAT, BASE_LON

M_PER_LAT = 110_950.0  # 앱 Recorder.kt fitAfterCrash 와 같은 값


def http(method: str, url: str, body: dict | None = None) -> tuple[int, dict]:
    data = None if body is None else json.dumps(body, ensure_ascii=False).encode()
    req = urllib.request.Request(url, data, {"Content-Type": "application/json; charset=utf-8"}, method=method)
    try:
        with urllib.request.urlopen(req, timeout=10) as r:
            return r.status, json.loads(r.read().decode())
    except urllib.error.HTTPError as e:
        return e.code, json.loads(e.read().decode() or "{}")


def incident(t: float, samples: list, deg: float | None, kind: str = "차", facts: list | None = None) -> dict:
    """HandoffCodec(shared/.../HandoffData.kt) 모양. samples = [(epoch 초, km/h, 자이로 라디안/초|None, 위도, 경도)]
    조향각은 게임 AI 차에서 안 읽어서 null(0 이면 "직진"으로 보임. 앱은 자이로가 있으면 자이로로 회전을 보여 줌)"""
    rows = [[round(s - t, 2), round(v, 2), None, None if w is None else round(w, 4), round(la, 6), round(lo, 6)] for s, v, w, la, lo in samples]
    return {"t": t, "kind": kind, "chosen": False, "deg": deg, "hw": False,
            "hash": hashlib.sha256(json.dumps(rows).encode()).hexdigest()[:16],
            "facts": facts or [], "steps": [], "phoneFrom": 0, "shots": [], "contacts": [],
            "samples": rows, "seen": None, "report": None, "vision": [], "statement": None}


def crash_point(inc: dict) -> tuple[float, float]:
    pts = [s for s in inc.get("samples") or [] if len(s) > 5 and s[4] is not None]
    if not pts:
        return BASE_LAT, BASE_LON
    s = min(pts, key=lambda s: abs(s[0]))
    return s[4], s[5]


def offset(lat: float, lon: float, east: float, north: float) -> tuple[float, float]:
    return lat + north / M_PER_LAT, lon + east / (111_320 * math.cos(math.radians(lat)))


def fake(mine: dict | None) -> tuple[dict, dict | None]:
    """(상대 사고, 새로 올릴 가짜 내 사고|None). 내 차는 북쪽으로 40km/h, 상대 차는 동쪽에서 서쪽으로 30km/h → 충돌 뒤 둘 다 멈춤"""
    new_mine = None
    if mine is None:
        t0 = round(time.time(), 2)
        rows = [(t0 + k / 10, 40.0 if k < 0 else 0.0, 0.0, *offset(BASE_LAT, BASE_LON, 0, 11.1 * min(k, 0) / 10)) for k in range(-100, 51)]
        new_mine = incident(t0, rows, 90.0, facts=["[시험] second_car.py --fake 가 만든 가짜 내 사고"])
        mine = new_mine
    lat, lon = crash_point(mine)
    t = round(mine["t"] + 0.3, 2)  # 상대 앱은 자기 센서로 조금 늦게 감지했다고 가정
    rows = [(t + k / 10, 30.0 if k < 0 else 0.0, 0.0, *offset(lat, lon, 3 + 8.33 * max(-k, 0) / 10, 0)) for k in range(-100, 51)]
    return incident(t, rows, 0.0, facts=["[시험] second_car.py --fake 가 만든 가짜 상대 차"]), new_mine


def from_game(path: str) -> dict:
    """crash_test.py --record-other 파일: {"hit": epoch, "deg": 도, "records": [[epoch, km/h, 방향(북 기준 시계 도), x, y], ...]}
    위치는 브리지와 같은 식으로 위도·경도(beamng_bridge.Converter.convert), 회전은 브리지와 같은 yaw_rate"""
    g = json.loads(open(path, encoding="utf-8").read())
    hit, rows, prev = g["hit"], [], None
    for t, kph, heading, x, y in g["records"]:
        w = yaw_rate(prev, t, heading)
        prev = (t, heading)
        if hit - 10 <= t <= hit + 5:
            rows.append((t, kph, w, BASE_LAT + y / 111_320, BASE_LON + x / (111_320 * math.cos(math.radians(BASE_LAT)))))
    return incident(round(hit, 2), rows, g["deg"])


def link(base: str, account: str, claim: str | None = None, on_code=print, timeout: float = 600) -> bool:
    """상대 쪽 연결: 코드 만들기(또는 휴대폰 코드 입력) → 연결되면 상대 쪽 동의를 대신 누름 → 두 사람 다 동의할 때까지 기다림"""
    root = f"{base.rstrip('/')}/s/{account}/link"
    if claim:
        code, r = http("PUT", f"{root}/claim", {"code": claim})
        if code != 200:
            print(f"코드 입력 실패 (HTTP {code}, {r.get('why')})")
            return False
        print("휴대폰 코드로 연결했어요")
    else:
        code, r = http("PUT", f"{root}/code", {})
        if code != 200:
            print(f"코드 만들기 실패 (HTTP {code})")
            return False
        on_code(r["code"])
    linked, end = False, time.time() + timeout
    while time.time() < end:
        _, v = http("GET", root)
        if v["state"] == "linked":
            linked = True
            if not v["me"]:
                http("PUT", f"{root}/consent", {})
                print("상대 쪽 동의를 대신 눌렀어요 (시연용. 실제로는 상대 운전자가 자기 휴대폰에서 누름)")
            elif v["partner"]:
                print("두 사람 모두 동의: 휴대폰에 두 차 기록이 나란히 나와요")
                return True
        elif linked:
            print("연결이 끊겼어요 (휴대폰에서 철회)")
            return False
        time.sleep(1)
    print("시간 초과")
    return False


def self_check():
    import threading
    from http.server import ThreadingHTTPServer
    import sync_server

    inc, mine = fake(None)
    assert mine and mine["deg"] == 90.0 and len(inc["samples"]) == 151 and inc["samples"][100][0] == 0.0
    la, lo = crash_point(inc)
    assert abs((lo - crash_point(mine)[1]) * 111_320 * math.cos(math.radians(la)) - 3) < 0.1  # 충돌 순간 3m 동쪽 (경도 소수 6자리 = 약 9cm)
    assert set(inc) >= {"t", "kind", "chosen", "deg", "hw", "hash", "facts", "steps", "phoneFrom", "shots", "contacts"}  # decode 필수 키
    assert all(s[2] is None for s in inc["samples"])  # 안 읽은 조향은 비워 둠

    import os, tempfile
    with tempfile.NamedTemporaryFile("w", suffix=".json", delete=False, encoding="utf-8") as f:
        json.dump({"hit": 100.0, "deg": 10.0, "records": [[89.0, 50, 0.0, 0, 0], [99.5, 50, 0.0, 0, 6.9], [100.0, 40, 10.0, 0, 12.5], [106.0, 0, 10.0, 1, 13]]}, f)
    g = from_game(f.name)
    os.unlink(f.name)
    assert [s[0] for s in g["samples"]] == [-0.5, 0.0]  # 충돌 -10~+5초만
    assert g["samples"][1][3] < 0 and abs(g["samples"][1][4] - (BASE_LAT + 12.5 / 111_320)) < 1e-6  # 오른쪽으로 돎 = 음수

    srv = ThreadingHTTPServer(("127.0.0.1", 0), sync_server.Handler)
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    base = f"http://127.0.0.1:{srv.server_port}"
    assert http("PUT", f"{base}/s/me/incident", mine)[0] == 200
    assert http("PUT", f"{base}/s/other/incident", inc)[0] == 200
    assert http("GET", f"{base}/s/me/link/candidates")[1] == {"near": [{"dt": 0, "within": 50}]}
    code = http("PUT", f"{base}/s/me/link/code", {})[1]["code"]

    def phone():  # 휴대폰(내 쪽): 연결되면 동의
        for _ in range(50):
            if http("GET", f"{base}/s/me/link")[1]["state"] == "linked":
                http("PUT", f"{base}/s/me/link/consent", {})
                return
            time.sleep(0.1)
    threading.Thread(target=phone, daemon=True).start()
    assert link(base, "other", claim=code, timeout=10)
    got = json.loads(http("GET", f"{base}/s/me/link")[1]["incident"])
    assert got["t"] == inc["t"] and got["samples"] == inc["samples"]
    srv.shutdown()
    print("second_car self-check OK")


if __name__ == "__main__":
    if "--self-check" in sys.argv:
        self_check(); sys.exit(0)
    ap = argparse.ArgumentParser()
    src = ap.add_mutually_exclusive_group(required=True)
    src.add_argument("--fake", action="store_true")
    src.add_argument("--from", dest="path")
    ap.add_argument("--server", default="http://127.0.0.1:8767")
    ap.add_argument("--me", default="demo", help="휴대폰이 로그인한 계정(시작.cmd 의 --one-account 면 아무거나 demo 로 묶임)")
    ap.add_argument("--account", default="other", help="상대 차 계정. other 로 시작해야 --one-account 에서 안 묶임")
    ap.add_argument("--claim", help="휴대폰에 나온 6자리 코드")
    a = ap.parse_args()
    if not a.account.startswith("other"):
        sys.exit("--account 는 other 로 시작해야 해요 (sync_server --one-account 가 다른 이름은 내 계정과 묶음)")
    if a.fake:
        _, s = http("GET", f"{a.server.rstrip('/')}/s/{a.me}?since=-1")
        mine = json.loads(s["incident"]) if s.get("incident") else None
        other, new_mine = fake(mine)
        if new_mine:
            http("PUT", f"{a.server.rstrip('/')}/s/{a.me}/incident", new_mine)
            print("서버에 내 사고가 없어 가짜 내 사고도 올렸어요 (휴대폰에 사고 알림이 갈 수 있음)")
    else:
        other = from_game(a.path)
    code, _ = http("PUT", f"{a.server.rstrip('/')}/s/{a.account}/incident", other)
    if code != 200:
        sys.exit(f"상대 차 기록 올리기 실패 (HTTP {code})")
    print(f"상대 차 기록을 올렸어요: 충돌 기준 {len(other['samples'])}개 값, 충격 방향 {other['deg']}°")
    ok = link(a.server, a.account, a.claim, on_code=lambda c: print(f"\n  상대 코드: {c[:3]} {c[3:]}\n  휴대폰 > 사고 기록 > 상대 차 기록과 연결 > 상대 코드 입력하기 에 넣으세요\n"))
    sys.exit(0 if ok else 1)
