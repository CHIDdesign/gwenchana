"""BeamNG 실제 사고 시험 (사람 운전 없이 반복 가능).

준비: 게임을 MCP 켠 상태로 실행 (Steam 실행 옵션 -enablemcp, 또는 옵션 > 게임플레이 > MCP), 시작.cmd 로 연동 켜기.
실행: python crash_test.py [--level west_coast_usa] [--kph 50] [--gap 90] [--no-ignition]
  1) 맵 불러오기(내 차 생성)  2) 시동 끄기→켜기 (차 화면 검어짐 → 시동 그림 → 내비)
  3) 상대 차를 도로 위에 세우기  4) 두 차 모두 게임 AI 로 서로를 향해 주행 → 충돌  5) 게임 정답(손상 부품) vs 앱 기록 비교 출력
게임 MCP: http://127.0.0.1:29292/mcp (JSON-RPC, 게임에 들어 있는 기능, lua/ge/extensions/mcp)
"""
import argparse
import json
import math
import subprocess
import time
import urllib.request

from beamng_bridge import ADB, RUNS

MCP = "http://127.0.0.1:29292/mcp"
# 둘 다 최신 세단 (게임 info: ETK 846 = Sedan 346hp, Bastion sport_gt = 488hp)
MY_CAR = ("etk800", "846_340_A")
OTHER_CAR = ("bastion", "sport_gt_A")
_id = 0


def rpc(method: str, params: dict | None = None) -> dict:
    global _id
    _id += 1
    body = json.dumps({"jsonrpc": "2.0", "id": _id, "method": method, "params": params or {}}).encode()
    req = urllib.request.Request(MCP, body, {"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=60) as r:
        out = json.loads(r.read().decode("utf-8", "ignore"))
    if "error" in out:
        raise RuntimeError(out["error"])
    return out["result"]


def tool(_tool: str, **args) -> str:
    r = rpc("tools/call", {"name": _tool, "arguments": args})
    return "".join(c.get("text", "") for c in r.get("content", []))


def jtool(_tool: str, **args):
    return json.loads(tool(_tool, **args))


def lua(code: str) -> str:
    return tool("run_lua", code=code)


def vlua(vid: int, code: str, wait: float = 0.6) -> str:
    """차 VM 은 비동기: 한 번 보내고 잠시 뒤 같은 코드를 다시 보내 결과를 받는다"""
    tool("run_lua_vehicle", id=vid, code=code)
    time.sleep(wait)
    return tool("run_lua_vehicle", id=vid, code=code)


def adb(*a: str) -> str:
    return subprocess.run([str(ADB), "-s", "emulator-5554", *a], capture_output=True, text=True, errors="ignore").stdout


def wait_power(awake: bool) -> str:
    """게임 값이 브리지를 거쳐 화면에 닿기까지 몇 초 걸려서, 정해진 시간만 기다리면 틀리게 읽음"""
    state = "?"
    for _ in range(20):
        state = adb("shell", "dumpsys", "power").split("mWakefulness=")[1].split()[0]
        if (state == "Awake") == awake:
            break
        time.sleep(0.5)
    return state


def pose(vid: int) -> tuple[list[float], list[float]]:
    s = lua(f"local v=be:getObjectByID({vid}); local p=v:getPosition(); local d=v:getDirectionVector(); "
            f"return string.format('%f,%f,%f,%f,%f,%f',p.x,p.y,p.z,d.x,d.y,d.z)")
    n = [float(x) for x in s.strip().split(",")]
    return n[:3], n[3:]


def speed_kph(vid: int) -> float:
    return float(lua(f"return be:getObjectByID({vid}):getVelocity():length()*3.6"))


def drive_state(vid: int) -> list[float]:
    """[시각, km/h, 방향(북 기준 시계 도), x, y]. 상대 차 앱 대신 쓰는 값(second_car.py --from), 한 번의 호출로"""
    s = lua(f"local v=be:getObjectByID({vid}); local p=v:getPosition(); local d=v:getDirectionVector(); "
            f"return string.format('%f,%f,%f,%f,%f',p.x,p.y,d.x,d.y,v:getVelocity():length()*3.6)")
    x, y, dx, dy, kph = (float(n) for n in s.strip().split(","))
    return [time.time(), kph, math.degrees(math.atan2(dx, dy)) % 360, x, y]


def damage(vid: int) -> float:
    return float(json.loads(tool("get_vehicle_damage", id=vid)).get("damageSum", 0) or 0)


def stop(vid: int):
    """AI 를 끄면 마지막 페달(후진 풀악셀 등)이 남는다 → 페달 떼고 N + 주차 브레이크"""
    tool("set_ai", id=vid, mode="disabled")
    time.sleep(0.5)
    for e, v in (("throttle", 0), ("brake", 1), ("steering", 0), ("parkingbrake", 1)):
        tool("inject_input", id=vid, event=e, value=v)
    vlua(vid, "controller.mainController.shiftToGearIndex(0)")


def unlock_keys():
    """MCP 명령(차 다시 불러오기 등) 뒤 게임 키보드가 Esc 말고 막히는 일이 있어(원인 미확인) 메뉴를 한 번 오가 되돌림"""
    for _ in range(2):
        tool("trigger_action", action="toggleMenues")
        time.sleep(1.5)


def step(msg: str):
    print(f"\n== {msg}", flush=True)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--level", default="west_coast_usa", help="기본 도시 맵")
    ap.add_argument("--spot", default="spawns_chinatown", help="출발 위치(맵의 SpawnSphere 이름). 기본 = 도시 맵 시내. 없는 맵이면 그대로")
    ap.add_argument("--kph", type=float, default=50, help="부딪힐 때 속도")
    ap.add_argument("--gap", type=float, default=90, help="상대 차까지 거리(m)")
    ap.add_argument("--no-ignition", action="store_true", help="시동 끄기·켜기 시험 건너뜀")
    ap.add_argument("--straight", action="store_true", help="세워 둔 차를 곧게 달려 들이받음 (평평한 맵 gridmap_v2 와 함께)")
    ap.add_argument("--record-other", action="store_true", help="상대 차(게임 AI)의 위치·속도·방향을 기록해 runs/other_*.json 으로 (두 차 기록 연결 시연: second_car.py --from)")
    a = ap.parse_args()
    rpc("initialize", {"protocolVersion": "2025-06-18", "capabilities": {}, "clientInfo": {"name": "askthecar", "version": "1"}})

    st = jtool("get_status")
    if a.level not in (st.get("level") or "") or not st.get("vehicleCount"):
        step(f"맵 불러오기: {a.level}")
        tool("load_level", name=a.level)
        for _ in range(120):
            time.sleep(3)
            try:
                st = jtool("get_status")
                if st.get("vehicleCount"):
                    break
            except Exception:
                pass
        time.sleep(5)
    me = int(tool("get_player_vehicle_id"))
    if next((v for v in jtool("get_vehicles") if v["id"] == me), {}).get("jbeam") != MY_CAR[0]:
        step(f"내 차를 세단으로: {MY_CAR}")
        tool("spawn", model=MY_CAR[0], config=MY_CAR[1])  # 지금 차를 바꿈(새 id)
        time.sleep(6)
        me = int(tool("get_player_vehicle_id"))
    if a.spot:  # 기본 스폰은 시내가 아니다
        print(lua(f"local o=scenetree.findObject('{a.spot}'); if not o then return 'spot 없음' end; spawn.safeTeleport(be:getObjectByID({me}),o:getPosition(),quat(o:getRotation())); return 'spot {a.spot}'"))
        time.sleep(3)
    print(f"맵 {st.get('level')}, 내 차 id {me}")

    if not a.no_ignition:
        step("시동 끄기 → 에뮬레이터 화면 꺼지는지")
        vlua(me, "electrics.setIgnitionLevel(0)")
        time.sleep(1)  # 끄기는 앱의 검은 덮개가 한다(Android 는 안 재움)
        step("시동 켜기 → 시동 그림 뒤 내비")
        vlua(me, "electrics.setIgnitionLevel(2)")
        print("에뮬레이터:", wait_power(True))
        time.sleep(1)
        top = [l for l in adb("shell", "dumpsys", "activity", "activities").splitlines() if "topResumedActivity" in l]
        print("맨 앞 화면:", top[0].strip() if top else "?")
        time.sleep(10)  # 시동 그림 약 9초

    step(f"상대 차를 도로 위 약 {a.gap:.0f} m 떨어진 곳에 세우기")
    others = [v["id"] for v in jtool("get_vehicles") if v["id"] != me and v.get("jbeam") == OTHER_CAR[0]]
    if not others:  # 처음 한 번만 만든다 (다시 돌리면 그 차를 다시 세움)
        for v in jtool("get_vehicles"):  # 예전 시험 차(다른 모델)는 치움
            if v["id"] != me:
                tool("remove_vehicle", id=v["id"])
        tool("spawn", model=OTHER_CAR[0], config=OTHER_CAR[1], add=True)
        time.sleep(4)
        others = [v["id"] for v in jtool("get_vehicles") if v["id"] != me]
        lua(f"be:enterVehicle(0, be:getObjectByID({me}))")  # 새 차로 옮겨 간 조종을 내 차로 되돌림
    other = others[0]
    p, d = pose(me)
    tool("set_position", id=me, pos={"x": p[0], "y": p[1], "z": p[2] + 0.3})  # 멈추고 수리
    # 게임 도로망(map.getMap().nodes)에서 내 차로부터 gap 에 가장 가까운 도로 지점 → 건물 속에 안 놓임
    spot = lua(f"local p = be:getObjectByID({me}):getPosition(); local best, bd; "
               f"for _, n in pairs(map.getMap().nodes) do local e = math.abs((n.pos - p):length() - {a.gap}); "
               f"if not bd or e < bd then best, bd = n.pos, e end end; "
               f"return best and string.format('%f,%f,%f', best.x, best.y, best.z) or ''")
    q = [float(v) for v in spot.split(",")] if "," in spot and not a.straight else [p[0] + d[0] * a.gap, p[1] + d[1] * a.gap, p[2]]
    tool("set_position", id=other, pos={"x": q[0], "y": q[1], "z": q[2] + 0.5})
    time.sleep(3)
    q, _ = pose(other)
    print(f"상대 차 id {other}: 내 차에서 {((q[0]-p[0])**2 + (q[1]-p[1])**2) ** 0.5:.1f} m (도로 위)")
    for v in (me, other):
        vlua(v, "electrics.setIgnitionLevel(3)")
    time.sleep(3)

    step(f"두 차가 서로를 향해 주행 → 충돌 (게임 AI chase, 최대 {a.kph:.0f} km/h)")
    d0 = damage(me)
    if a.straight:  # AI 는 부딪히기 전에 속도를 줄여 스치기만 할 때가 있음 → 세워 둔 차를 곧게 달려 들이받음
        tool("inject_input", id=me, event="parkingbrake", value=0)
        tool("inject_input", id=me, event="brake", value=0)  # stop() 이 남긴 브레이크
        vlua(me, "controller.mainController.shiftToGearIndex(2)")  # 자동변속기 번호: 1 = P, 2 = D
        tool("inject_input", id=me, event="steering", value=0)
        tool("inject_input", id=me, event="throttle", value=1)
    else:
        for v, t in ((me, other), (other, me)):  # 상대 차도 AI 로 움직임
            tool("inject_input", id=v, event="parkingbrake", value=0)
            tool("set_ai", id=v, mode="chase", targetObjectID=t, aggression=0.8, routeSpeed=a.kph / 3.6, speedMode="limit")
    hit_t, top_kph, rec, deg = None, 0.0, [], None
    t0 = time.time()
    while time.time() - t0 < 60:
        top_kph = max(top_kph, speed_kph(me))
        if a.record_other:
            rec.append(drive_state(other))
        if damage(me) - d0 > 500:
            hit_t = time.time()
            break
        time.sleep(0.2)
    if hit_t and a.record_other:
        # 상대 차 기준 내 차 방향(앞 0°, 오른쪽 90°). 실제 상대 앱은 가속도로 재는 값 — 게임에선 위치로 대신함
        (mx, my), (ox, oy), (dx, dy) = pose(me)[0][:2], rec[-1][3:5], pose(other)[1][:2]
        n = math.hypot(dx, dy) or 1
        deg = round(math.degrees(math.atan2(((mx - ox) * dy - (my - oy) * dx) / n, ((mx - ox) * dx + (my - oy) * dy) / n)) % 360, 1)
    while hit_t and time.time() - hit_t < 1.5:  # 부딪힌 뒤 잠깐 그대로(멈추는 모습)
        if a.record_other:
            rec.append(drive_state(other))
        time.sleep(0.2)
    stop(other)
    stop(me)
    if hit_t and a.record_other:
        while time.time() - hit_t < 5:  # 충돌 뒤 5초까지(앱 재현 범위)
            rec.append(drive_state(other))
            time.sleep(0.2)
        out = RUNS / f"other_{time.strftime('%Y%m%d_%H%M%S')}.json"
        RUNS.mkdir(exist_ok=True)
        out.write_text(json.dumps({"hit": hit_t, "deg": deg, "records": rec}), encoding="utf-8")
        print(f"상대 차 기록 저장: {out} ({len(rec)}개) → python second_car.py --from {out}")
    if not hit_t:
        print(f"충돌 안 남 (최고 {top_kph:.0f} km/h) - 방향·거리 확인 필요")
        return
    print(f"충돌! 직전 최고 속도 {top_kph:.0f} km/h, 손상 {damage(me) - d0:.0f}")
    ev = adb("shell", "dumpsys", "sensorservice").splitlines()  # 안드로이드가 실제로 받은 가속도 (x 좌우, y 중력, z 앞뒤)
    k = next((i for i, l in enumerate(ev) if "Accelerometer: last" in l), None)
    if k is not None:
        print("앱 센서 값(최근, x 좌우·y 위·z 앞뒤):", " | ".join(l.split(")")[-1].strip() for l in ev[k + 20:k + 51] if "wall=" in l))
    parts = vlua(me, "local s=beamstate.getPartDamageScores and beamstate.getPartDamageScores() or {}; local o={}; "
                     "for k,v in pairs(s) do if v>0.05 then o[#o+1]=k..'='..string.format('%.2f',v) end end; return table.concat(o,', ')", 1.0)
    print("게임 정답 - 손상 부품:", parts[:500])

    step("앱 기록 기다림 (사고 후 10초 + 저장)")
    time.sleep(25)
    ls = adb("exec-out", "run-as", "com.askthecar", "--user", "10", "ls", "-t", "files/crashes").split()
    if not ls:
        print("앱 기록 없음")
        return
    run = f"files/crashes/{ls[0]}"
    print("앱 기록:", run)
    for f in ("impact.json", "report.txt"):
        out = adb("exec-out", "run-as", "com.askthecar", "--user", "10", "cat", f"{run}/{f}")
        print(f"--- {f}\n{out[:900]}")


def release_controls():
    """시험이 끝나면 AI·페달·UI 를 풀어 사람이 바로 운전할 수 있게. 주차 브레이크는 그대로(풀면 언덕에서 굴러감)"""
    me = int(tool("get_player_vehicle_id"))
    tool("set_ai", id=me, mode="disabled")
    for e in ("throttle", "brake", "steering"):
        tool("inject_input", id=me, event=e, value=0)
    lua("ui_visibility.setCef(true); ui_visibility.setImgui(true)")
    unlock_keys()


if __name__ == "__main__":
    try:
        main()
    finally:
        release_controls()
