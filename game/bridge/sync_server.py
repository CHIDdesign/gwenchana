"""차 앱 ↔ 휴대폰 앱 사고 상태 동기화 서버 (시제품용, 표준 라이브러리만).

규칙 (앱 쪽은 shared/java/com/askthecar/HandoffData.kt 의 SyncClient):
  PUT /s/<계정>/incident  본문 = HandoffCodec JSON (t = 사고 id). t 가 바뀌면 새 사고: 단계·닫힘 초기화
  PUT /s/<계정>/step      {"t", "index", "by": "car"|"phone"}  — 지금 사고(t 같음)일 때만
  PUT /s/<계정>/close     {"t"}                                — 사고 아니에요·안내 끝
  PUT /s/<계정>/handover  {"t", "on"}                          — 미리 등록한 가족에게 맡기기/취소. 켜면 "family" 계정이 같은 사고를 본다(시연 보호자 계정 하나)
  PUT /s/<계정>/wipe      {}                                   — 동의 철회: 이 계정의 사고·연결을 지우고 wiped=True (휴대폰도 받아 둔 것을 지움). 다음 사고 업로드에서 풀림
  GET /s/<계정>?since=N   version > N 이 될 때까지 최대 25초 기다림(long-poll) →
                          {"version", "incident": 문자열|null, "step", "stepBy", "closed", "wiped", "notAccident"}
계정 = 시연용 운전자 id. 인증 없음, 메모리에만 보관(끄면 사라짐).
shortcut: 실제 서비스는 현대차 계정 인증 + 클라우드(relay Worker)로 옮겨야 함. 같은 PC 안 에뮬레이터·같은 와이파이 휴대폰 시연용.

두 차 기록 연결 (두 사람 모두 확인·동의해야 연결, 앱 = 증인이라 판단 없음. 클라이언트: shared/.../LinkClient.kt):
  GET /s/<계정>/link/candidates  지금 사고와 ±60초·100m 안의 다른 계정 사고 → {"near": [{"dt": 초, "within": 50|100}]}
                                 계정·위치·내용은 안 줌(후보는 알림용일 뿐, 연결은 코드로만)
  PUT /s/<계정>/link/code        {} → {"code": 6자리, "expiresIn": 초}  지금 사고에 일회용 코드(10분)
  PUT /s/<계정>/link/claim       {"code"}  상대 코드 입력 → 연결(아직 공유 안 함). 틀린 코드 404, 사고 없음 409,
                                 두 사고가 10분 넘게 떨어짐 409, 자기 코드 400, 틀린 시도 많음 429
  PUT /s/<계정>/link/consent     {} 내 기록을 상대에게 보여 주는 데 동의
  PUT /s/<계정>/link/revoke      {} 연결 끊기·동의 철회(코드도 지움). 상대는 더 이상 내 기록을 못 받음
  GET /s/<계정>/link             {"state": "none"} | {"state": "code", "code", "expiresIn"}
                                 | {"state": "linked", "me": 동의, "partner": 동의, "incident": 상대 기록 문자열|null}
                                 incident 는 두 사람 모두 동의했을 때만, 주행 기록만 남긴 것(SHARED_KEYS, 진술·연락처·안내 단계 없음)
  어느 쪽이든 새 사고(t 바뀜)를 올리면 옛 연결은 사라진다.

--one-account: 혼자 하는 PC 시연용. 어느 운전자로 로그인했든 차와 휴대폰을 같은 칸으로 묶는다(다르면 사고 알림이 안 옴).
  "other" 로 시작하는 계정은 묶지 않는다(second_car.py 가 상대 차 앱 대신 쓰는 칸).
실행: python game/bridge/sync_server.py [--port 8767] [--one-account]   /  시험: python game/bridge/sync_server.py --self-check
"""
import json
import math
import secrets
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, unquote, urlparse

WAIT = 25.0
cond = threading.Condition()
ONE_ACCOUNT = "--one-account" in sys.argv
accounts = {}  # 계정 -> {"version", "incident", "t", "step", "stepBy", "closed", "wiped", "notAccident"}


def state(acc):
    return accounts.setdefault(acc, {"version": 0, "incident": None, "t": None, "step": None, "stepBy": None, "closed": False, "wiped": False, "notAccident": False, "seen": None})


def view(s):
    return {k: s[k] for k in ("version", "incident", "step", "stepBy", "closed", "wiped", "notAccident", "seen")}


def release_family(s):
    """맡긴 사고가 끝나면(취소·새 사고·철회) 가족 칸을 떼어 낸다. 다음 사고까지 가족에게 가지 않게"""
    if accounts.get("family") is s:
        accounts["family"] = {**s, "incident": None, "t": None, "step": None, "stepBy": None, "closed": False, "notAccident": False, "seen": None, "wiped": True, "version": s["version"] + 1}


def apply(acc, what, body):
    """바뀌었으면 True. 잘못된 본문이면 ValueError"""
    with cond:
        s = state(acc)
        if what == "wipe":
            json.loads(body)
            release_family(s)
            s.update(incident=None, t=None, step=None, stepBy=None, closed=False, wiped=True, notAccident=False, seen=None)
            links[:] = [k for k in links if acc not in (k["a"], k["b"])]
        elif what == "incident":
            t = float(json.loads(body)["t"])
            if s["t"] != t:
                release_family(s)
                s.update(t=t, step=None, stepBy=None, closed=False, notAccident=False, seen=None)
            s["incident"] = body
            s["wiped"] = False
        else:
            o = json.loads(body)
            if float(o["t"]) != s["t"]:
                return False  # 옛 사고에 대한 늦은 요청
            if what == "step":
                s.update(step=int(o["index"]), stepBy=str(o["by"]))
            elif what == "close":
                s["closed"] = True
                s["notAccident"] = bool(o.get("notAccident"))
            elif what == "reopen":  # 차에서 "사고 아님"을 되돌림
                s.update(closed=False, notAccident=False)
            elif what == "seen":  # 휴대폰이 이 사고를 받아 저장함. 차의 "휴대폰 도착" 표시는 이것이 있을 때만
                s["seen"] = float(o["t"])
            elif what == "handover":  # shortcut: 보호자 계정 하나로 시연, 실제는 현대차 계정의 가족 연결로 교체
                if o.get("on"):
                    accounts["family"] = s
                else:
                    release_family(s)
            else:
                raise ValueError(what)
        s["version"] += 1
        cond.notify_all()
        return True


CODE_LIFE = 600.0
NEAR_SEC, NEAR_M = 60.0, 100.0  # 후보 기준(추정값, docs/reports/현대차 그림체·차량 간 연동 조사 B)
SAME_CRASH_SEC = 600.0  # 코드로 묶을 수 있는 두 사고 시각 차이 상한(오래된 사고와 잘못 묶이지 않게)
# 상대에게 보내는 항목: 시각·대상·충격 방향·고속도로 여부·기록 지문·주행 기록(속도·조향·회전·위치·브레이크·방향지시등·에어백). 진술·연락처·안내 단계·경위서·사진 인식은 안 보냄
SHARED_KEYS = ("t", "kind", "chosen", "deg", "hw", "hash", "samples")
links = []  # {"a", "ta", "b", "tb", "code", "until", "ok": set(계정)}
fails = {}  # 계정 -> [틀린 코드 시각…] (6자리 코드 맞히기 막기)


def point(row):
    """주행 기록 한 줄에서 (초, 위도, 경도). 모양이 틀린 줄·위치 없는 줄은 None (아무 계정이나 올릴 수 있어서 믿지 않음)"""
    try:
        v = float(row[0]), float(row[4]), float(row[5])
    except (TypeError, ValueError, IndexError, KeyError, OverflowError):
        return None
    return v if all(map(math.isfinite, v)) else None


def crash_point(acc):
    """(t, lat, lon|None). 사고 없으면 None. 위치 = 충돌 순간에 가장 가까운 기록(samples = [충돌 기준 초, 속도, 조향, 자이로, 위도, 경도])"""
    inc = accounts.get(acc, {}).get("incident")
    if not inc:
        return None
    o = json.loads(inc)
    rows = o.get("samples")
    pts = [p for p in map(point, rows if isinstance(rows, list) else []) if p]
    if not pts:
        return float(o["t"]), None, None
    s = min(pts, key=lambda s: abs(s[0]))
    return float(o["t"]), s[1], s[2]


def meters(a, b):
    """두 (lat, lon) 사이 거리(m), 가까운 거리용 평면 근사"""
    dn = (a[0] - b[0]) * 110_950
    de = (a[1] - b[1]) * 111_320 * math.cos(math.radians(a[0]))
    return math.hypot(dn, de)


def alive(k):
    """양쪽이 연결할 때 올린 사고가 그대로이고, 코드만 있는 상태면 시간이 안 지났을 때"""
    def same(acc, t):
        return accounts.get(acc, {}).get("t") == t
    if k["b"] is None:
        return same(k["a"], k["ta"]) and time.time() <= k["until"]
    return same(k["a"], k["ta"]) and same(k["b"], k["tb"])


def link_of(acc):
    """이 계정의 살아 있는 연결(코드만 만든 상태도 포함). 죽은 연결은 여기서 지운다"""
    links[:] = [k for k in links if alive(k)]
    return next((k for k in links if acc in (k["a"], k["b"])), None)


def candidates(acc):
    with cond:
        me = crash_point(acc)
        if not me or me[1] is None:
            return {"near": []}
        out = []
        for other in accounts:
            o = crash_point(other) if other != acc and other != "family" and accounts[other] is not accounts[acc] else None
            if o and o[1] is not None and abs(o[0] - me[0]) <= NEAR_SEC:
                m = meters(me[1:], o[1:])
                if m <= NEAR_M:
                    out.append({"dt": round(o[0] - me[0]), "within": 50 if m <= 50 else 100})
        return {"near": out}


def link_view(acc):
    with cond:
        k = link_of(acc)
        if not k:
            return {"state": "none"}
        if k["b"] is None:
            return {"state": "code", "code": k["code"], "expiresIn": round(k["until"] - time.time())}
        partner = k["b"] if k["a"] == acc else k["a"]
        both = acc in k["ok"] and partner in k["ok"]
        inc = None
        if both and accounts[partner]["incident"]:
            o = json.loads(accounts[partner]["incident"])
            # 안 보내는 항목은 빈 값으로(앱의 HandoffCodec.decode 가 꼭 있어야 하는 키)
            inc = json.dumps({"facts": [], "steps": [], "phoneFrom": 0, "shots": [], "contacts": [], "seen": None,
                              **{key: o[key] for key in SHARED_KEYS if key in o}}, ensure_ascii=False)
        return {"state": "linked", "me": acc in k["ok"], "partner": partner in k["ok"], "incident": inc}


def link_op(acc, op, body):
    """(HTTP 코드, 응답). 잘못된 본문이면 ValueError"""
    o = json.loads(body or "{}")
    with cond:
        k = link_of(acc)
        t = accounts.get(acc, {}).get("t")
        if op == "revoke":
            if k:
                links.remove(k)
            return 200, {"ok": True}
        if op == "consent":
            if not k or k["b"] is None:
                return 409, {"ok": False}
            k["ok"].add(acc)
            return 200, {"ok": True}
        if t is None:
            return 409, {"ok": False, "why": "no-incident"}
        if op == "code":
            if k:
                links.remove(k)  # 새 코드 = 옛 코드·연결 버림
            used = {x["code"] for x in links}
            code = f"{secrets.randbelow(10**6):06d}"
            while code in used:
                code = f"{secrets.randbelow(10**6):06d}"
            links.append({"a": acc, "ta": t, "b": None, "tb": None, "code": code, "until": time.time() + CODE_LIFE, "ok": set()})
            return 200, {"code": code, "expiresIn": round(CODE_LIFE)}
        if op == "claim":
            now = time.time()
            recent = [x for x in fails.get(acc, []) if now - x < 300]
            fails[acc] = recent
            if len(recent) >= 10:
                return 429, {"ok": False, "why": "too-many"}
            code = str(o["code"]).strip()
            hit = next((x for x in links if x["b"] is None and x["code"] == code), None)  # link_of 가 죽은 코드는 이미 지움
            if not hit:
                recent.append(now)
                return 404, {"ok": False, "why": "wrong-code"}
            if hit["a"] == acc:
                return 400, {"ok": False, "why": "own-code"}
            if abs(hit["ta"] - t) > SAME_CRASH_SEC:
                return 409, {"ok": False, "why": "far-apart"}
            if k:
                links.remove(k)
            hit.update(b=acc, tb=t, code=None)
            return 200, {"ok": True}
        raise ValueError(op)


def wait(acc, since, timeout=WAIT):
    end = time.time() + timeout
    with cond:
        while state(acc)["version"] <= since and time.time() < end:
            cond.wait(end - time.time())
        return view(state(acc))


class Handler(BaseHTTPRequestHandler):
    def _parts(self):
        u = urlparse(self.path)
        p = [unquote(x) for x in u.path.strip("/").split("/")]
        if ONE_ACCOUNT and len(p) > 1 and not p[1].startswith("other") and p[1] != "family":
            p[1] = "demo"
        return (p, parse_qs(u.query)) if len(p) >= 2 and p[0] == "s" and p[1] else (None, None)

    def _reply(self, code, obj=None):
        data = json.dumps(obj if obj is not None else {"ok": code == 200}, ensure_ascii=False).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        p, q = self._parts()
        if p and p[2:] in (["link"], ["link", "candidates"]):
            try:
                out = link_view(p[1]) if len(p) == 3 else candidates(p[1])
            except Exception as e:  # 기록 하나가 이상해도 응답은 돌려준다
                print(f"[sync] link 오류: {e!r}", flush=True)
                return self._reply(500)
            return self._reply(200, out)
        if not p or len(p) != 2:
            return self._reply(404)
        try:
            since = int(q.get("since", ["-1"])[0])
        except ValueError:
            return self._reply(400)
        self._reply(200, wait(p[1], since))

    def do_PUT(self):
        p, _ = self._parts()
        is_link = p is not None and len(p) == 4 and p[2] == "link"
        if is_link and p[1] == "family":  # 맡은 가족은 운전자 기록을 상대 차와 잇지 못한다
            return self._reply(403)
        if not p or len(p) != 3 and not is_link:
            return self._reply(404)
        try:
            n = int(self.headers.get("Content-Length") or 0)
        except ValueError:
            return self._reply(400)
        if n < 0:  # rfile.read(-1) 은 연결이 끊길 때까지 막힘
            return self._reply(400)
        if n > 512 * 1024:
            return self._reply(413)
        try:
            body = self.rfile.read(n).decode("utf-8")
            if is_link:
                return self._reply(*link_op(p[1], p[3], body))
            done = apply(p[1], p[2], body)
            if p[2] == "handover" and not done:  # 서버가 다시 켜져 사고가 없으면 "보냈어요"라고 하지 않게
                return self._reply(409)
        except (ValueError, KeyError, TypeError, OverflowError, RecursionError):
            return self._reply(400)
        self._reply(200)

    def log_message(self, fmt, *args):
        print("[sync] " + self.address_string() + " " + (fmt % args), flush=True)


def self_check():
    apply("ho", "incident", json.dumps({"t": 9.0}))
    assert wait("family", -1, 0)["incident"] is None
    assert not apply("ho", "handover", json.dumps({"t": 8.0, "on": True}))  # 옛 사고
    assert apply("ho", "handover", json.dumps({"t": 9.0, "on": True})) and wait("family", 0, 0)["incident"]
    assert apply("ho", "handover", json.dumps({"t": 9.0, "on": False}))
    f = wait("family", 0, 0); assert f["incident"] is None and f["wiped"]
    assert apply("ho", "handover", json.dumps({"t": 9.0, "on": True})) and wait("family", 0, 0)["incident"]
    apply("ho", "incident", json.dumps({"t": 10.0}))  # 다음 사고는 가족에게 가지 않는다
    assert wait("family", 0, 0)["incident"] is None and accounts["family"] is not accounts["ho"]
    a = "test"
    inc = json.dumps({"t": 100.0, "kind": "차"})
    assert apply(a, "incident", inc) and wait(a, -1, 0)["incident"] == inc
    assert apply(a, "step", json.dumps({"t": 100.0, "index": 3, "by": "car"}))
    v = wait(a, 0, 0)
    assert v["step"] == 3 and v["stepBy"] == "car" and v["version"] == 2
    assert not apply(a, "step", json.dumps({"t": 99.0, "index": 5, "by": "phone"}))  # 옛 사고
    # long-poll: 다른 스레드가 0.2초 뒤 바꾸면 바로 깨어남
    threading.Timer(0.2, lambda: apply(a, "step", json.dumps({"t": 100.0, "index": 4, "by": "phone"}))).start()
    t0 = time.time(); v = wait(a, 2, 5)
    assert v["step"] == 4 and v["stepBy"] == "phone" and time.time() - t0 < 2
    assert apply(a, "close", json.dumps({"t": 100.0})) and wait(a, 0, 0)["closed"]
    assert apply(a, "close", json.dumps({"t": 100.0, "notAccident": True})) and wait(a, 0, 0)["notAccident"]
    assert apply(a, "close", json.dumps({"t": 100.0, "notAccident": False}))  # 차에서 "사고가 맞아요"
    assert wait(a, 0, 0)["closed"] and not wait(a, 0, 0)["notAccident"]
    apply(a, "incident", json.dumps({"t": 200.0}))  # 새 사고면 초기화
    v = wait(a, 0, 0)
    assert v["step"] is None and not v["closed"]
    try:
        apply(a, "nope", json.dumps({"t": 200.0})); raise AssertionError("bad op accepted")
    except ValueError:
        pass
    assert wait("other", -1, 0)["incident"] is None  # 계정끼리 섞이지 않음

    # 두 차 기록 연결
    def inc(t, lat, lon, **more):
        return json.dumps({"t": t, "kind": "차", "chosen": False, "deg": 0, "hw": False, "hash": "ab",
                           "samples": [[-1.0, 30, 0, None, lat, lon], [0.0, 20, 0, None, lat, lon]],
                           "statement": "비밀 진술", "contacts": [{"name": "보험", "phone": "1588"}], **more})
    apply("la", "incident", inc(1000.0, 37.5, 127.03))
    apply("lb", "incident", inc(1020.0, 37.5004, 127.03))  # 약 44m, 20초 차이
    apply("lc", "incident", inc(1030.0, 37.51, 127.03))  # 약 1.1km → 후보 아님
    assert candidates("la") == {"near": [{"dt": 20, "within": 50}]}, candidates("la")
    assert link_view("la") == {"state": "none"}
    assert link_op("lb", "consent", "{}")[0] == 409  # 연결 전 동의 불가
    code = link_op("la", "code", "{}")[1]["code"]
    assert len(code) == 6 and code.isdigit() and link_view("la")["state"] == "code"
    assert link_op("la", "claim", json.dumps({"code": code}))[0] == 400  # 자기 코드
    wrong = "000000" if code != "000000" else "111111"
    assert link_op("lb", "claim", json.dumps({"code": wrong}))[0] == 404
    assert link_op("lb", "claim", json.dumps({"code": code}))[0] == 200
    va, vb = link_view("la"), link_view("lb")
    assert va["state"] == vb["state"] == "linked" and va["incident"] is None and vb["incident"] is None
    link_op("la", "consent", "{}")
    assert link_view("lb") == {"state": "linked", "me": False, "partner": True, "incident": None}  # 한쪽만 동의 = 안 보임
    link_op("lb", "consent", "{}")
    got = json.loads(link_view("la")["incident"])
    assert got["t"] == 1020.0 and got["samples"] and "statement" not in got and got["contacts"] == []  # 주행 기록만
    assert json.loads(link_view("lb")["incident"])["t"] == 1000.0
    assert link_op("lc", "claim", json.dumps({"code": code}))[0] == 404  # 쓴 코드는 다시 못 씀
    link_op("lb", "revoke", "{}")
    assert link_view("la") == {"state": "none"} and link_view("lb") == {"state": "none"}  # 철회하면 양쪽 다 끊김
    code = link_op("la", "code", "{}")[1]["code"]
    link_op("lb", "claim", json.dumps({"code": code})); link_op("la", "consent", "{}"); link_op("lb", "consent", "{}")
    apply("la", "incident", inc(5000.0, 37.5, 127.03))  # 새 사고면 옛 연결은 사라짐
    assert link_view("lb") == {"state": "none"} and link_view("la") == {"state": "none"}
    code = link_op("la", "code", "{}")[1]["code"]
    assert link_op("lb", "claim", json.dumps({"code": code}))[0] == 409  # 4000초 떨어진 사고끼리는 안 묶음
    assert link_op("nobody", "code", "{}")[0] == 409  # 사고 없음
    for _ in range(10):
        link_op("lc", "claim", json.dumps({"code": wrong}))
    assert link_op("lc", "claim", json.dumps({"code": code}))[0] == 429  # 코드 맞히기 막기

    # 모양이 틀린 기록을 올린 계정이 있어도 다른 계정의 후보 찾기는 그대로
    for i, bad in enumerate(([1], [[0, 0, 0, None, "x", 1], [0, 0, 0, None, 1e999, 1], {"a": 1}], 5, "abcdefg", [10**400])):
        apply(f"bad{i}", "incident", json.dumps({"t": 5000.0, "samples": bad}))
    apply("mix", "incident", json.dumps({"t": 5001.0, "samples": [1, [0.0, 10, 0, None, 37.5, 127.03]]}))
    assert candidates("la") == {"near": [{"dt": 1, "within": 50}]}, candidates("la")  # 좋은 줄은 쓰고 틀린 줄만 건너뜀
    assert candidates("bad0") == {"near": []}

    # 차에서 철회: 사고·연결이 지워지고 wiped 가 남음, 기다리던 쪽도 깨어남, 다음 사고에서 풀림
    apply("wa", "incident", inc(7000.0, 37.6, 127.1))
    apply("wb", "incident", inc(7010.0, 37.6, 127.1))
    apply("wa", "step", json.dumps({"t": 7000.0, "index": 2, "by": "car"}))
    apply("wa", "close", json.dumps({"t": 7000.0}))
    code = link_op("wa", "code", "{}")[1]["code"]
    link_op("wb", "claim", json.dumps({"code": code})); link_op("wa", "consent", "{}"); link_op("wb", "consent", "{}")
    assert link_view("wb")["incident"]
    v0 = wait("wa", -1, 0)
    assert not v0["wiped"] and v0["closed"]
    threading.Timer(0.1, lambda: apply("wa", "wipe", "{}")).start()
    t0 = time.time(); v = wait("wa", v0["version"], 5)
    assert v == {"version": v0["version"] + 1, "incident": None, "step": None, "stepBy": None, "closed": False, "wiped": True, "notAccident": False, "seen": None}, v
    assert time.time() - t0 < 2 and accounts["wa"]["t"] is None
    assert link_view("wb") == {"state": "none"} and link_view("wa") == {"state": "none"}
    assert not apply("wa", "step", json.dumps({"t": 7000.0, "index": 3, "by": "car"}))  # 지운 사고에 늦게 온 요청
    assert wait("wb", -1, 0)["incident"] and not wait("wb", -1, 0)["wiped"]  # 상대 계정의 자기 사고는 그대로
    apply("wa", "incident", inc(8000.0, 37.6, 127.1))
    v = wait("wa", -1, 0)
    assert not v["wiped"] and v["incident"]

    # HTTP: wipe 경로, 잘못된 Content-Length 는 400 (rfile.read(-1) 에 안 걸림)
    import http.client
    srv = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    def conn():
        return http.client.HTTPConnection("127.0.0.1", srv.server_port, timeout=5)
    c = conn(); c.request("PUT", "/s/wa/wipe", "{}"); assert c.getresponse().status == 200; c.close()
    c = conn(); c.request("GET", "/s/wa?since=-1"); assert json.loads(c.getresponse().read())["wiped"]; c.close()
    for bad in ("-1", "abc"):
        c = conn(); c.putrequest("PUT", "/s/wa/step"); c.putheader("Content-Length", bad); c.endheaders()
        assert c.getresponse().status == 400, bad
        c.close()
    srv.shutdown()
    print("sync_server self-check OK")


if __name__ == "__main__":
    if "--self-check" in sys.argv:
        self_check(); sys.exit(0)
    port = int(sys.argv[sys.argv.index("--port") + 1]) if "--port" in sys.argv else 8767
    # 0.0.0.0: 같은 와이파이의 실제 휴대폰도 붙을 수 있게 (에뮬레이터만이면 127.0.0.1 로 충분)
    print(f"sync server on :{port}", flush=True)
    ThreadingHTTPServer(("0.0.0.0", port), Handler).serve_forever()
