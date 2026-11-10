"""여러 사고·주행을 실제 BeamNG 물리로 돌려 앱 기록을 채점한다 (시험 전용, 앱·APK 와 무관).

앱이 쓰는 값은 실차와 같은 통로(VHAL·센서·위치)로만 들어간다. 게임 정답(브리지 runs/*.jsonl)은 끝난 뒤 이 PC 에서 채점할 때만 읽는다.
준비: 게임을 -enablemcp 로 켜고 시작.cmd. 실행: python crash_suite.py [--spots a,b] [--only 이름,이름]  /  시험: python crash_suite.py --self-check
결과: 화면 표 + runs/suite_<시각>.json
"""
import argparse
import glob
import json
import math
import os
import time

import crash_test as c

SPOTS = ["skidpadSpawn", "spawns_dragstrip", "spawns_logistics", "spawns_chinatown"]
MP_LAT = 110_950.0


# ---- 앱과 같은 계산 (shared/.../Recorder.kt 를 옮겨 씀. 바꾸면 같이 바꿀 것) ----
def ease(ss):
    def eased(key):
        out = [s[key] for s in ss]
        start = 0
        for i in range(1, len(ss)):
            if ss[i][key] == ss[i - 1][key]:
                continue
            frm = start
            while ss[i]["t"] - ss[frm]["t"] > 1.5:
                frm += 1
            for j in range(frm, i):
                out[j] = ss[frm][key] + (ss[i][key] - ss[frm][key]) * (ss[j]["t"] - ss[frm]["t"]) / (ss[i]["t"] - ss[frm]["t"])
            start = i
        return out
    v, w = eased("speed"), eased("yawRate")
    return [dict(s, speed=a, yawRate=b) for s, a, b in zip(ss, v, w)]


def track(ss):
    x = y = yaw = 0.0
    out = [(0.0, 0.0, 0.0)]
    for a, b in zip(ss, ss[1:]):
        dt = b["t"] - a["t"]
        v = a["speed"] / 3.6 * (-1 if a["gear"] == "R" else 1)
        if abs(v) > 0.3:
            yaw += a["yawRate"] * dt
        x += v * math.cos(yaw) * dt
        y += v * math.sin(yaw) * dt
        out.append((x, y, yaw))
    return out


def fit_after_crash(ss, poses, ci):
    if ss[0].get("lat") is None:
        return poses
    mp = 111_320.0 * math.cos(math.radians(ss[0]["lat"]))
    en = lambda s: ((s["lon"] - ss[0]["lon"]) * mp, (s["lat"] - ss[0]["lat"]) * MP_LAT)
    fx = []
    for s in ss:
        if not fx or (s["lat"], s["lon"]) != (fx[-1]["lat"], fx[-1]["lon"]):
            fx.append(s)
    at = ss[ci]["t"] + 1.0
    pre = [f for f in fx if f["t"] <= at]
    aft = [f for f in fx if f["t"] > at]
    if not pre:
        return poses
    g = en(pre[-1])
    if aft:
        a = en(aft[0])
        f = (at - pre[-1]["t"]) / (aft[0]["t"] - pre[-1]["t"])
        g = (g[0] + (a[0] - g[0]) * f, g[1] + (a[1] - g[1]) * f)
    e = en(fx[-1])
    cp, ep = poses[ci], poses[-1]
    d = math.hypot(ep[0] - cp[0], ep[1] - cp[1])
    if d < 1:
        return poses
    k = min(1.0, (math.hypot(e[0] - g[0], e[1] - g[1]) + 1.5) / d)
    return [p if i <= ci else (cp[0] + (p[0] - cp[0]) * k, cp[1] + (p[1] - cp[1]) * k, p[2]) for i, p in enumerate(poses)]


def replay(log, crash_t):
    win = [s for s in log if crash_t - 10 <= s["t"] <= crash_t + 5]
    e = ease(win)
    ci = max(i for i, s in enumerate(win) if s["t"] <= crash_t)
    return win, fit_after_crash(e, track(e), ci), ci


def angle_diff(a, b):
    return abs((a - b + 180) % 360 - 180)


def score_replay(win, poses, truth):
    """truth(t)=(x,y,나침반 yaw). 시작 방향은 앱이 모르므로 최소제곱 회전으로 맞춘 뒤 비교"""
    tr = [truth(s["t"]) for s in win]
    x0, y0 = tr[0][0], tr[0][1]
    c_ = s_ = 0.0
    for p, t in zip(poses, tr):
        e, n = t[0] - x0, t[1] - y0
        c_ += e * p[0] + n * p[1]
        s_ += n * p[0] - e * p[1]
    th = math.atan2(s_, c_)
    cs, sn = math.cos(th), math.sin(th)
    er = [math.hypot(p[0] * cs - p[1] * sn - (t[0] - x0), p[0] * sn + p[1] * cs - (t[1] - y0)) for p, t in zip(poses, tr)]
    # 멈칫·튐: 게임 실제 속도와 화면 속도 차이가 큰 구간의 비율
    jerky = bad = 0
    for i in range(len(poses) - 1):
        dt = win[i + 1]["t"] - win[i]["t"]
        if dt <= 0:
            continue
        anim = math.hypot(poses[i + 1][0] - poses[i][0], poses[i + 1][1] - poses[i][1]) / dt * 3.6
        real = math.hypot(tr[i + 1][0] - tr[i][0], tr[i + 1][1] - tr[i][1]) / dt * 3.6
        jerky += 1
        bad += abs(anim - real) > max(15, real * 0.5)
    yaw_true = -sum((b[2] - a[2] + 180) % 360 - 180 for a, b in zip(tr, tr[1:]))  # 나침반은 시계방향, 앱은 반시계
    length = sum(math.hypot(b[0] - a[0], b[1] - a[1]) for a, b in zip(tr, tr[1:]))
    return {"mean": round(sum(er) / len(er), 1), "max": round(max(er), 1), "end": round(er[-1], 1), "trueLen": round(length),
            "jerky%": round(100 * bad / max(jerky, 1)), "yawErr": round(abs(math.degrees(poses[-1][2]) - yaw_true))}


def self_check():
    log = [{"t": i * 0.1, "speed": 36.0, "yawRate": 0.0, "gear": "D", "lat": 37.5, "lon": 127.0 + (min(i // 10, 3) * 10 + (i > 30) * 5) / (111_320 * math.cos(math.radians(37.5)))} for i in range(51)]
    win, p, ci = replay(log, 3.0)
    assert ci == 30 and abs(p[-1][0] - 32) < 2.5, p[-1]  # GPS 1초 지연을 빼고 재므로 끝이 약간 짧다
    r = score_replay(win, p, lambda t: (min(t, 3) * 10 + max(0, t - 3) * 2.5, 0.0, 90.0))
    assert r["mean"] < 2.5, r
    assert angle_diff(350, 10) == 20
    assert track([{"t": 0, "speed": 36, "yawRate": 0, "gear": "R"}, {"t": 1, "speed": 36, "yawRate": 0, "gear": "R"}])[-1][0] == -10
    print("crash_suite self-check OK")


# ---- 게임 조작 ----
def place(vid, pos, d):
    c.tool("set_position", id=vid, pos={"x": pos[0], "y": pos[1], "z": pos[2] + 0.3})
    c.lua(f"spawn.safeTeleport(be:getObjectByID({vid}), vec3({pos[0]},{pos[1]},{pos[2]}), quatFromDir(vec3({d[0]},{d[1]},0), vec3(0,0,1)))")


def hold(vid):
    for e, v in (("throttle", 0), ("brake", 1), ("steering", 0), ("parkingbrake", 1)):
        c.tool("inject_input", id=vid, event=e, value=v)
    c.vlua(vid, "electrics.setIgnitionLevel(3)", 0.2)  # 게임 설정이 '꺼진 채 시작'이어도 시험은 시동 켠 채로(화면이 꺼지면 사진을 못 찍음)


def drive(vid, gear=2, throttle=1.0, steer=0.0):
    """자동변속기 번호: 1 = P, 2 = D, -1 = R"""
    c.tool("inject_input", id=vid, event="parkingbrake", value=0)
    c.tool("inject_input", id=vid, event="brake", value=0)
    c.vlua(vid, "electrics.setIgnitionLevel(3)", 0.2)  # 리셋 뒤 시동이 꺼져 있다
    time.sleep(1.5)
    c.vlua(vid, f"controller.mainController.shiftToGearIndex({gear})", 0.2)
    c.tool("inject_input", id=vid, event="steering", value=steer)
    c.tool("inject_input", id=vid, event="throttle", value=throttle)


def wait_hit(vid, d0, sec=12):
    t0 = time.time()
    while time.time() - t0 < sec:
        if c.damage(vid) - d0 > 300:
            return True
        time.sleep(0.1)
    return False


def setup(me, other, spot, keep_other=True):
    """스폰 위치에 내 차를 놓고 상대 차는 멀리 치워 둔다. 차 방향은 맵이 정한 그대로"""
    r = c.lua(f"local o=scenetree.findObject('{spot}'); if not o then return '' end; local p=o:getPosition(); local q=quat(o:getRotation()); local d=q*vec3(0,1,0); "
              f"return string.format('%f,%f,%f,%f,%f',p.x,p.y,p.z,d.x,d.y)")
    if not r:
        raise RuntimeError(f"위치 없음: {spot}")
    x, y, z, dx, dy = [float(v) for v in r.split(",")]
    n = math.hypot(dx, dy) or 1
    dx, dy = dx / n, dy / n
    hold(other)
    place(other, (x + 400, y + 400, z + 300), (1, 0))  # 안 쓰는 동안 하늘에 두지 않게 다음 place 로 옮긴다
    hold(me)
    place(me, (x, y, z), (dx, dy))
    time.sleep(3)
    (px, py, pz), (fx, fy, _) = c.pose(me)
    return (px, py, pz), (fx, fy)


def offset(pos, f, fwd, right):
    return pos[0] + f[0] * fwd + f[1] * right, pos[1] + f[1] * fwd - f[0] * right, pos[2]


def into(me, other, pos, f, fwd, right):
    """상대 차를 내 차 기준 위치에 내 차를 향하게 놓고 달려오게 한다"""
    p = offset(pos, f, fwd, right)
    place(other, p, (pos[0] - p[0], pos[1] - p[1]))
    time.sleep(2.5)
    d0 = c.damage(me)
    drive(other)
    return wait_hit(me, d0)


def parked(me, other, pos, f, fwd, right, gear=2, steer=0.0):
    """상대 차를 세워 두고 내 차가 달려가 들이받는다"""
    place(other, offset(pos, f, fwd, right), f)
    time.sleep(2.5)
    hold(other)
    d0 = c.damage(me)
    drive(me, gear, steer=steer)
    return wait_hit(me, d0)


# 부딪히는 시험: 이름, 기대 충격 방향(앞 0·오른쪽 90·뒤 180·왼쪽 270), 진행
HITS = [
    ("front", 0, lambda me, o, p, f: parked(me, o, p, f, 18, 0)),
    ("front_far", 0, lambda me, o, p, f: parked(me, o, p, f, 45, 0)),
    ("front_right", 30, lambda me, o, p, f: parked(me, o, p, f, 18, 1.4)),
    ("front_left", 330, lambda me, o, p, f: parked(me, o, p, f, 18, -1.4)),
    ("rear_hit", 180, lambda me, o, p, f: into(me, o, p, f, -22, 0)),
    ("right_hit", 90, lambda me, o, p, f: into(me, o, p, f, 0, 22)),
    ("left_hit", 270, lambda me, o, p, f: into(me, o, p, f, 0, -22)),
    ("reverse", 180, lambda me, o, p, f: parked(me, o, p, f, -15, 0, gear=-1)),
]


# 안 부딪히는 주행: 사고로 잡히면 오탐. 끝에 연습 신호로 기록만 남겨 재현을 채점
def hard_brake(me):
    drive(me)
    time.sleep(5)
    c.tool("inject_input", id=me, event="throttle", value=0)
    c.tool("inject_input", id=me, event="brake", value=1)
    time.sleep(4)


def slalom(me):
    drive(me, throttle=0.5)
    for i in range(8):
        c.tool("inject_input", id=me, event="steering", value=0.5 if i % 2 else -0.5)
        time.sleep(1.2)


def circle(me):
    drive(me, throttle=0.4, steer=0.6)
    time.sleep(9)


def spin(me):
    drive(me)
    time.sleep(4)
    c.tool("inject_input", id=me, event="throttle", value=0)
    c.tool("inject_input", id=me, event="steering", value=1)
    c.tool("inject_input", id=me, event="parkingbrake", value=1)
    time.sleep(4)


def reverse_arc(me):
    drive(me, gear=-1, throttle=0.5, steer=-0.5)
    time.sleep(6)


DRIVES = [("hard_brake", hard_brake), ("slalom", slalom), ("circle", circle), ("spin", spin), ("reverse_arc", reverse_arc)]


# ---- 앱 기록 읽기 ----
def app_dirs():
    return c.adb("exec-out", "run-as", "com.askthecar", "--user", "10", "ls", "-t", "files/crashes").split()


def app_read(d, name):
    return c.adb("exec-out", "run-as", "com.askthecar", "--user", "10", "cat", f"files/crashes/{d}/{name}")


def wait_record(before, sec=30):
    t0 = time.time()
    while time.time() - t0 < sec:
        new = [d for d in app_dirs() if d not in before]
        if new and "impact" in " ".join(c.adb("exec-out", "run-as", "com.askthecar", "--user", "10", "ls", f"files/crashes/{new[0]}").split()):
            time.sleep(1.5)
            return new[0]
        time.sleep(1)
    return None


class Truth:
    """브리지 정답지. 앱과 PC 시계 차이는 속도 곡선을 맞춰 찾는다"""

    def __init__(self, log):
        f = sorted(glob.glob("runs/*.jsonl"), key=os.path.getmtime)[-1]
        t0, t1 = log[0]["t"] - 5, log[-1]["t"] + 5
        rows = []
        with open(f, encoding="utf-8") as h:
            for line in h:
                try:
                    r = json.loads(line)
                except ValueError:
                    continue
                if "game" in r and t0 < r["t"] < t1:
                    rows.append((r["t"], r["game"]))
        self.rows = rows
        self.ts = [t for t, _ in rows]
        self.off = min(((sum(abs(s["speed"] / 3.6 - self.at(s["t"] + o / 100)["speed"]) for s in log[::3]), o / 100) for o in range(-300, 300, 10)))[1]

    def at(self, t):
        import bisect
        return self.rows[min(bisect.bisect_left(self.ts, t), len(self.ts) - 1)][1]

    def pos(self, t):
        g = self.at(t + self.off)
        return g["posX"], g["posY"], g["yaw"]

    def first_hit(self, after):
        base = None
        for t, g in self.rows:
            if t + 0 < after:
                base = g["damage"]
                continue
            if base is not None and g["damage"] - base > 300:
                return t
        return None


def collect(name, spot, record, expect):
    row = {"name": name, "spot": spot.replace("spawns_", ""), "record": record}
    if not record:
        return row
    log = json.loads(app_read(record, "log.json"))
    imp = json.loads(app_read(record, "impact.json"))
    tr = Truth(log)
    win, poses, _ = replay(log, imp["t"])
    row.update(score_replay(win, poses, tr.pos))
    w = win
    row["hz"] = round(sum(1 for a, b in zip(w, w[1:]) if a["speed"] != b["speed"]) / max(w[-1]["t"] - w[0]["t"], 1), 1)
    row["g"] = round(imp.get("peakG") or 0, 1)
    if expect is not None:
        row["deg"] = None if imp.get("deg") is None else round(imp["deg"])
        row["degErr"] = None if imp.get("deg") is None else round(angle_diff(imp["deg"], expect))
        hit = tr.first_hit(imp["t"] + tr.off - 8)
        row["delay"] = None if hit is None else round(imp["t"] + tr.off - hit, 1)
    ds = [g["damage"] for _, g in tr.rows]
    row["dmg"] = round(max(ds) - ds[0]) if ds else None  # 게임 손상 증가: 진짜 부딪혔는지 채점용
    row["kind"] = app_read(record, "kind.txt").strip()
    files = app_read(record, "vision_detect.json")
    row["photo"] = "camera.jpg" in c.adb("exec-out", "run-as", "com.askthecar", "--user", "10", "ls", f"files/crashes/{record}")
    row["seen"] = json.loads(files).get("label") if files.startswith("{") else None
    return row


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--spots", default=",".join(SPOTS))
    ap.add_argument("--only", default="", help="쉼표로 시험 이름")
    ap.add_argument("--self-check", action="store_true")
    a = ap.parse_args()
    if a.self_check:
        return self_check()
    c.rpc("initialize", {"protocolVersion": "2025-06-18", "capabilities": {}, "clientInfo": {"name": "askthecar", "version": "1"}})
    me = int(c.tool("get_player_vehicle_id"))
    if next((v for v in c.jtool("get_vehicles") if v["id"] == me), {}).get("jbeam") != c.MY_CAR[0]:
        c.tool("spawn", model=c.MY_CAR[0], config=c.MY_CAR[1])
        time.sleep(6)
        me = int(c.tool("get_player_vehicle_id"))
    others = [v["id"] for v in c.jtool("get_vehicles") if v["id"] != me]
    if not others:
        c.tool("spawn", model=c.OTHER_CAR[0], config=c.OTHER_CAR[1], add=True)
        time.sleep(4)
        others = [v["id"] for v in c.jtool("get_vehicles") if v["id"] != me]
        c.lua(f"be:enterVehicle(0, be:getObjectByID({me}))")
    other = others[0]
    only = set(filter(None, a.only.split(",")))
    results = []
    out = f"runs/suite_{time.strftime('%Y%m%d_%H%M%S')}.json"

    def run(name, spot, expect, act):
        print(f"\n== {name} @ {spot}", flush=True)
        try:
            pos, f = setup(me, other, spot)
            before = app_dirs()
            time.sleep(11)  # 재현에 쓸 앞 10초 기록이 쌓이게
            act(pos, f)
            for v in (me, other):  # 사람처럼 부딪히면 바로 발을 뗀다. 안 떼면 바퀴만 돌아 속도 신호가 실제와 달라진다
                c.tool("inject_input", id=v, event="throttle", value=0)
            time.sleep(0.5)
            c.tool("inject_input", id=me, event="brake", value=0.6)
            hold(other)
            detected = len([d for d in app_dirs() if d not in before]) > 0
            if expect is None and not detected:  # 사고가 아니어야 맞다. 기록만 남겨 재현 채점
                c.adb("shell", "am", "broadcast", "--user", "10", "-a", "com.askthecar.TEST_CRASH", "-n", "com.askthecar/.SimReceiver")
            rec = wait_record(before)
            hold(me)
            row = collect(name, spot, rec, expect)
            row["detected"] = detected
            row["false"] = expect is None and detected and (row.get("dmg") or 0) < 100  # 안 부딪혔는데 사고로 잡음
            print(row, flush=True)
        except Exception as e:
            row = {"name": name, "spot": spot, "error": repr(e)}
            print(row, flush=True)
        results.append(row)
        with open(out, "w", encoding="utf-8") as h:
            json.dump(results, h, ensure_ascii=False, indent=1)

    try:
        for spot in a.spots.split(","):
            for name, expect, act in HITS:
                if not only or name in only:
                    run(name, spot, expect, lambda pos, f, act=act: act(me, other, pos, f))
            for name, act in DRIVES:
                if not only or name in only:
                    run(name, spot, None, lambda pos, f, act=act: act(me))
    finally:
        c.tool("set_ai", id=me, mode="disabled")
        c.release_controls()
    print("\n결과:", out)
    for r in results:
        print({k: r.get(k) for k in ("name", "spot", "detected", "false", "degErr", "g", "delay", "mean", "end", "jerky%", "yawErr", "hz", "kind", "seen", "photo", "error")})


if __name__ == "__main__":
    main()
