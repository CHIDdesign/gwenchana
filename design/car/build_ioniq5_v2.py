"""사고 3D 화면의 내 차(IONIQ 5 모양) v2: 참고 메시 없이 코드로 직접 만드는 하드서피스 모델.
단면(앞→뒤) 키포인트를 손으로 정해 로프트 → 날카로운 선에만 작은 베벨 → 바퀴 구멍 불리언 → 램프·범퍼·휠은 조각으로 붙임.
로고·글자·번호판 없음. 그림(텍스처) 없음: 재질은 Principled 값만(glTF 그대로 나감).
좌표: Blender, 앞 = -Y, 바닥 z=0. 치수는 이전 모델(own_car) 측정값 + 실차 제원(길이 4635·폭 1890·높이 1605·축거 3000) 기준.

실행: blender -b --python design/car/build_ioniq5_v2.py -- <결과.glb(절대경로)> [<결과.blend>]
"""
import bpy, bmesh, math, sys, time
import numpy as np
from mathutils import Vector

argv = sys.argv[sys.argv.index("--") + 1:]
GLB = argv[0]
BLEND = argv[1] if len(argv) > 1 and argv[1] != "-" else None
t0 = time.time()

bpy.ops.wm.read_factory_settings(use_empty=True)
scene = bpy.context.scene

# ---------- 재질 ----------
def srgb(h):
    c = [(h >> s & 255) / 255 for s in (16, 8, 0)]
    return tuple(v / 12.92 if v <= 0.04045 else ((v + 0.055) / 1.055) ** 2.4 for v in c)


def material(name, h, rough, metal=0.0, coat=0.0, emit=None, emit_strength=0.0):
    m = bpy.data.materials.new(name)
    m.use_nodes = True
    b = m.node_tree.nodes["Principled BSDF"]
    b.inputs["Base Color"].default_value = (*srgb(h), 1)
    b.inputs["Roughness"].default_value = rough
    b.inputs["Metallic"].default_value = metal
    if coat:
        b.inputs["Coat Weight"].default_value = coat
        b.inputs["Coat Roughness"].default_value = 0.05
    if emit is not None:
        b.inputs["Emission Color"].default_value = (*srgb(emit), 1)
        b.inputs["Emission Strength"].default_value = emit_strength
    return m


M = {
    "body": material("body", 0xDADBD9, 0.28, 0.0, coat=1.0),       # 아틀라스 화이트 느낌
    "glass": material("glass", 0x090B0E, 0.06, 0.0),                # 짙은 틴팅 유리·글래스 루프(코트 없음: 위에서 볼 때 하늘 반사로 회색이 되지 않게)
    "trim": material("trim", 0x151619, 0.45),                      # 새틴 블랙(필러·창틀·바닥)
    "gloss": material("gloss", 0x0A0B0D, 0.12, coat=0.6),          # 유광 검정
    "housing": material("housing", 0x08090B, 0.4),                 # 램프 하우징·흡기(반광 검정, 하늘 반사로 밝아지지 않게)
    "cladding": material("cladding", 0x3A3B3F, 0.7),               # 휠아치 클래딩
    "garnish": material("garnish", 0xA9ACB0, 0.3, 0.9),            # 은색 가니시·스키드
    "lamp": material("lamp", 0xF4F6FF, 0.2, emit=0xFFFFFF, emit_strength=2.5),
    "tail": material("tail", 0xC01A14, 0.2, emit=0xFF2A1A, emit_strength=2.0),
    "tire": material("tire", 0x141416, 0.85),
    "rim": material("rim", 0xB2B4B8, 0.3, 0.9),
    "rim_dark": material("rim_dark", 0x1C1D20, 0.5),
}
MATS = list(M.values())
MI = {m.name: i for i, m in enumerate(MATS)}

# ---------- 평면 곡선 도구 ----------
def curve(pts):
    xs, ys = zip(*pts)
    return lambda y: float(np.interp(y, xs, ys))


L0, L1 = -2.58, 2.06                       # 범퍼 끝(y)
AXLE = (-1.713, 1.309)                      # 앞·뒤 차축 y (축거 3.02)
WHEEL_R, WHEEL_Z, WHEEL_X = 0.372, 0.375, 0.76

# 평면도 반폭(아래쪽 점들 공통 배율의 기준, 문 표면 0.93)
def _W(y):
    """평면도 반폭: 가운데 직선 + 앞뒤 둥근 모서리(타원 1/4), 앞은 더 길게 둥글다"""
    if y < -2.0:
        t = min((y + 2.0) / (L0 + 0.015 + 2.0), 1.0)
        return 0.925 - 0.45 * (1 - math.sqrt(max(1 - t * t, 0))) * 1.0 - 0.005 * t
    if y > 1.55:
        t = min((y - 1.55) / (L1 - 1.55), 1.0)
        return 0.93 - 0.30 * (1 - math.sqrt(max(1 - t * t, 0)))
    return float(np.interp(y, [-2.0, -1.6, 1.5, 1.55], [0.925, 0.93, 0.93, 0.93]))


W = _W
Y_COWL, Y_APIL, Y_ROOF_END = -1.42, -0.30, 1.46
ZB = curve([(L0, 0.81), (-2.45, 0.845), (-2.2, 0.90), (-1.9, 0.96), (Y_COWL, 1.03), (-1.25, 1.06), (-0.9, 1.07), (0.22, 1.085), (0.86, 1.10), (1.3, 1.12), (1.60, 1.15), (1.9, 1.14), (L1, 1.10)])  # 벨트 라인(앞 낮고 뒤로 갈수록 올라감)
ZC = curve([(L0, 0.50), (-1.0, 0.50), (0.9, 0.78), (1.2, 0.80), (L1, 0.80)])  # Z 캐릭터 라인(앞 아래 → 뒤 위)
ZTOP = curve([(L0, 0.875), (-2.45, 0.895), (-2.35, 0.93), (-2.2, 0.965), (-2.0, 1.01), (-1.8, 1.04), (-1.6, 1.065), (Y_COWL, 1.07), (-1.36, 1.10), (-0.55, 1.50), (-0.42, 1.54), (Y_APIL, 1.558),
              (-0.15, 1.572), (0.0, 1.578), (1.25, 1.578), (1.38, 1.568), (Y_ROOF_END, 1.548), (1.54, 1.50), (2.0, 1.19), (L1, 1.17)])  # 가운데 윗선(후드·앞유리·지붕·뒷유리)
ZRE = curve([(Y_COWL, 1.05), (-1.25, 1.075), (-0.55, 1.475), (-0.42, 1.52), (Y_APIL, 1.54), (-0.15, 1.553), (0.0, 1.558), (1.25, 1.558), (1.38, 1.55), (Y_ROOF_END, 1.53),
             (1.54, 1.48), (2.0, 1.18), (L1, 1.16)])  # 지붕 가장자리·A필러선
XRE = curve([(Y_COWL, 0.80), (-1.25, 0.78), (-0.55, 0.65), (Y_APIL, 0.635), (0.0, 0.625), (1.2, 0.625), (Y_ROOF_END, 0.635), (1.62, 0.68), (1.85, 0.75), (2.0, 0.76), (L1, 0.70)])

HOOD_GROOVE = (-2.40, Y_COWL - 0.02)
SEAMS = (-0.95, 0.22, 0.86)                 # 문 이음선 y
GROOVE_W, GROOVE_D = 0.005, 0.0045

NAMES = ["floor_c", "floor_e", "sill_b", "garn_t", "door_l", "crease", "hg1", "hg2", "hg3", "hg4", "belt", "glass_b", "glass_t", "roof_e", "roof_c"]
I = {n: i for i, n in enumerate(NAMES)}
BEVEL_LOOPS = {"sill_b", "garn_t", "crease", "belt", "roof_e", "glass_b", "glass_t"}  # 길이 방향으로 날을 세울 선
BEVEL_W = 0.008


def front_plane(z):
    return L0 + (z - 0.30) * 0.20


def rear_plane(z):
    return L1 - (z - 0.30) * 0.04


def half_section(s, seam_inset=0.0, cowl_inset=0.0):
    """역 s(공칭 y)의 반쪽 단면 점(x,y,z) 목록. 평면 끝에서는 y를 앞·뒤 면 평면으로 자른다."""
    w = W(s) / 0.93
    zb, zc = ZB(s), ZC(s)
    hood = s < Y_COWL
    pts = {}
    pts["floor_c"] = (0.0, 0.20)
    pts["floor_e"] = (0.78 * w, 0.20)
    pts["sill_b"] = (0.885 * w, 0.31)
    pts["garn_t"] = (0.905 * w, 0.455)
    pts["door_l"] = (0.925 * w, 0.475)
    pts["crease"] = (0.950 * w, zc)
    pts["belt"] = (0.925 * w, zb)
    # 후드 이음선(펜더 위 2~3cm 아래): 후드 구간에서만 홈, 나머지는 직선 위 점
    cx, bx = pts["crease"], pts["belt"]
    if HOOD_GROOVE[0] < s < HOOD_GROOVE[1]:
        xg = bx[0] + (cx[0] - bx[0]) * 0.03 / max(zb - zc, 1e-3)
        pts["hg1"] = (xg, zb - 0.032)
        pts["hg2"] = (xg - GROOVE_D, zb - 0.030)
        pts["hg3"] = (xg - GROOVE_D, zb - 0.022)
        pts["hg4"] = (xg + 0.0005, zb - 0.020)
    else:
        for k, f in (("hg1", 0.90), ("hg2", 0.92), ("hg3", 0.94), ("hg4", 0.96)):
            pts[k] = (cx[0] + (bx[0] - cx[0]) * f, cx[1] + (bx[1] - cx[1]) * f)
    if hood:
        pts["glass_b"] = (0.925 * w - 0.02, zb + 0.012)
        pts["glass_t"] = (0.925 * w - 0.06, zb + 0.024)
        pts["roof_e"] = (0.925 * w - 0.10, zb + 0.035)
    else:
        pts["glass_b"] = (0.895 * w, zb + 0.02)
        xr, zr = XRE(s), ZRE(s)
        pts["roof_e"] = (xr, zr)
        gb = pts["glass_b"]
        f = 1 - 0.075 / max(zr - gb[1], 0.1)  # 창 윗선: 지붕 가장자리 7.5cm 아래, 유리면 연장선 위
        pts["glass_t"] = (gb[0] + (xr - gb[0]) * f, gb[1] + (zr - gb[1]) * f)
    pts["roof_c"] = (0.0, ZTOP(s))
    out = []
    for n in NAMES:
        x, z = pts[n]
        if seam_inset and I["sill_b"] <= I[n] <= I["glass_b"]:
            x -= seam_inset
        if cowl_inset and I[n] >= I["glass_b"]:
            z -= cowl_inset
        y = s
        if s < -2.4:
            y = max(s, front_plane(z))
            x *= W(y) / W(s)
        elif s > 1.9:
            y = min(s, rear_plane(z))
            x *= W(y) / W(s)
        out.append((x, y, z))
    return out


# 역 목록: 균일 + 제어점 + 홈 띠
stations = set(np.round(np.arange(L0, L1 + 1e-6, 0.06), 4)) | set(np.round(np.arange(L0, -2.0, 0.02), 4)) | set(np.round(np.arange(1.55, L1, 0.02), 4))
for y in (L0, -2.57, -2.56, -2.55, -2.54, -2.53, -2.52, -2.51, -2.49, -2.46, -2.42, -2.38, -2.30, -1.9, -1.5, -1.36, -0.5, -0.38, Y_APIL, -0.15, 0.0, 1.25, 1.38, Y_ROOF_END, 1.54, 1.62,
          1.72, 1.86, 1.96, 2.0, 2.02, 2.03, 2.04, 2.05, L1, HOOD_GROOVE[0], HOOD_GROOVE[1], -1.0, 0.9, 1.2):
    stations.add(round(y, 4))
stations = sorted(stations)
# 같은 y의 홈 쌍은 '보통→파임' 순서(앞쪽 벽), '파임→보통'(뒤쪽 벽)이 되도록 정렬
rows = []
for y in stations:
    rows.append((y, 0.0, 0.0))
for sy in SEAMS:
    rows += [(sy - GROOVE_W, 0, 0), (sy - GROOVE_W + 1e-5, GROOVE_D, 0), (sy + GROOVE_W - 1e-5, GROOVE_D, 0), (sy + GROOVE_W, 0, 0)]
rows += [(Y_COWL - 0.004, 0, 0), (Y_COWL - 0.004 + 1e-5, 0, 0.006), (Y_COWL + 0.004 - 1e-5, 0, 0.006), (Y_COWL + 0.004, 0, 0)]
rows.sort(key=lambda r: r[0])

N = len(NAMES)
RING = 2 * N - 2  # 가운데 점 2개는 공유
bm = bmesh.new()
bw = bm.edges.layers.float.new("bevel_weight_edge")
rings = []
for (y, ins, cw) in rows:
    half = half_section(y, ins, cw)
    ring = [bm.verts.new(p) for p in half]
    ring += [bm.verts.new((-p[0], p[1], p[2])) for p in reversed(half[1:-1])]
    rings.append(ring)
# 면 재질: 띠 번호(점 i→i+1) + y 구간
Y_GLASS0 = -1.25  # 옆 유리 시작(앞 쿼터)
Y_GLASS1 = 1.60   # 옆 유리 끝(C필러), 그 뒤 해치 옆면은 차체색


def band_material(i, y):
    if i in (I["floor_c"], I["floor_e"]):
        return MI["trim"]
    if i == I["sill_b"]:
        return MI["garnish"]
    if i == I["glass_b"]:
        return MI["glass"] if Y_GLASS0 < y < Y_GLASS1 else MI["body"]
    if i == I["glass_t"]:  # 필러 윗부분: A필러·C필러는 유광 검정, 지붕 레일은 차체색
        if Y_COWL < y < Y_APIL or 1.30 < y < Y_GLASS1:
            return MI["trim"]
        return MI["body"]
    if i == I["roof_e"]:  # 앞유리·뒷유리는 유리, 지붕은 차체색(글래스 루프 판은 따로 얹음)
        return MI["glass"] if y > Y_COWL else MI["body"]
    return MI["body"]


faces_band = {}
for k in range(len(rings) - 1):
    a, b = rings[k], rings[k + 1]
    ymid = (rows[k][0] + rows[k + 1][0]) / 2
    for j in range(RING):
        q = (a[j], a[(j + 1) % RING], b[(j + 1) % RING], b[j])
        if len(set(q)) < 4:
            continue
        try:
            f = bm.faces.new(q)
        except ValueError:
            continue
        band = j if j < N - 1 else RING - 1 - j  # 왼쪽은 거꾸로
        f.material_index = band_material(band, ymid)
        f.smooth = True
        faces_band[f] = band
# 앞·뒤 덮개
for ring, flip in ((rings[0], True), (rings[-1], False)):
    vs = list(dict.fromkeys(ring))
    if len(vs) >= 3:
        f = bm.faces.new(vs[::-1] if flip else vs)
        f.material_index = MI["body"]
        f.smooth = True
        for e in f.edges:
            e[bw] = 1.0
# 점 번호로 길이 방향 선 찾기: 각 링에서 j번째 점끼리 잇는 선
for k in range(len(rings) - 1):
    a, b = rings[k], rings[k + 1]
    for j in range(RING):
        band = j if j < N else RING - j
        name = NAMES[band] if band < N else None
        e = bm.edges.get((a[j], b[j]))
        if e is None or name is None:
            continue
        if name in BEVEL_LOOPS:
            e[bw] = 1.0
bmesh.ops.remove_doubles(bm, verts=bm.verts, dist=1e-5)
bmesh.ops.dissolve_degenerate(bm, dist=1e-6, edges=bm.edges)
bmesh.ops.recalc_face_normals(bm, faces=bm.faces)
me = bpy.data.meshes.new("body")
bm.to_mesh(me)
bm.free()
for m in MATS:
    me.materials.append(m)
car = bpy.data.objects.new("own_car", me)
scene.collection.objects.link(car)
bpy.context.view_layer.objects.active = car
car.select_set(True)
print("LOFT faces", len(me.polygons), round(time.time() - t0, 1))


def apply(obj, mod):
    bpy.context.view_layer.objects.active = obj
    bpy.ops.object.modifier_apply(modifier=mod.name)


bv = car.modifiers.new("bevel", "BEVEL")
bv.width = BEVEL_W
bv.segments = 2
bv.limit_method = "WEIGHT"
bv.harden_normals = True
bv.miter_outer = "MITER_ARC"
apply(car, bv)
print("BEVEL faces", len(me.polygons), round(time.time() - t0, 1))


# ---------- 조각 메시 도구 ----------
def add_mesh(name, vv, ff, mat, smooth=False, parent_mats=True):
    m = bpy.data.meshes.new(name)
    m.from_pydata(vv, [], ff)
    for mm in MATS:
        m.materials.append(mm)
    for p in m.polygons:
        p.material_index = MI[mat]
        p.use_smooth = smooth
    b2 = bmesh.new()
    b2.from_mesh(m)
    bmesh.ops.recalc_face_normals(b2, faces=b2.faces)
    b2.to_mesh(m)
    b2.free()
    o = bpy.data.objects.new(name, m)
    scene.collection.objects.link(o)
    return o


def box(name, c, size, mat, smooth=False, rot=None):
    sx, sy, sz = (s / 2 for s in size)
    vv = [(c[0] + dx * sx, c[1] + dy * sy, c[2] + dz * sz) for dz in (-1, 1) for dy in (-1, 1) for dx in (-1, 1)]
    ff = [(0, 1, 3, 2), (4, 6, 7, 5), (0, 4, 5, 1), (2, 3, 7, 6), (0, 2, 6, 4), (1, 5, 7, 3)]
    o = add_mesh(name, vv, ff, mat, smooth)
    if rot:
        o.rotation_euler = rot
    return o


def prism(name, poly_yz, x0, x1, mat, smooth=False):
    """YZ 다각형을 x0..x1로 뽑은 기둥"""
    n = len(poly_yz)
    vv = [(x0, p[0], p[1]) for p in poly_yz] + [(x1, p[0], p[1]) for p in poly_yz]
    ff = [tuple(range(n)), tuple(range(2 * n - 1, n - 1, -1))] + [(k, (k + 1) % n, n + (k + 1) % n, n + k) for k in range(n)]
    return add_mesh(name, vv, ff, mat, smooth)


def extrude_xz(name, poly_xz, y0, y1, mat, smooth=False):
    """XZ 다각형(앞·뒤 면 조각)을 y0..y1로 뽑은 기둥"""
    n = len(poly_xz)
    vv = [(p[0], y0, p[1]) for p in poly_xz] + [(p[0], y1, p[1]) for p in poly_xz]
    ff = [tuple(range(n)), tuple(range(2 * n - 1, n - 1, -1))] + [(k, (k + 1) % n, n + (k + 1) % n, n + k) for k in range(n)]
    return add_mesh(name, vv, ff, mat, smooth)


def boolean(target, cutter, op="DIFFERENCE"):
    mod = target.modifiers.new("bool", "BOOLEAN")
    mod.operation = op
    mod.solver = "EXACT"
    mod.use_self = False
    mod.use_hole_tolerant = True
    mod.object = cutter
    apply(target, mod)
    bpy.data.objects.remove(cutter)


# ---------- 바퀴 구멍(각진 아치) + 클래딩 ----------
def arch_outline(cy, r, n=22, a0=-22, a1=202, squareness=0.55):
    """바퀴 중심 기준 각진(둥근 네모에 가까운) 아치 윤곽, 아래쪽은 사이드실까지 수직으로"""
    pts = []
    for k in range(n + 1):
        a = math.radians(a0 + (a1 - a0) * k / n)
        c, s = math.cos(a), math.sin(a)
        rr = r / (abs(c) ** 4 + abs(s) ** 4) ** 0.25 * (1 - squareness) + r * squareness  # 원과 네모 사이
        pts.append((cy + rr * c, WHEEL_Z + rr * s))
    return pts


def side_x(y, z):
    """로프트 공칭 표면의 옆면 x(클래딩을 표면에 얹을 때)"""
    pts = half_section(y)
    zs = [p[2] for p in pts[I["sill_b"]:I["belt"] + 1]]
    xs = [p[0] for p in pts[I["sill_b"]:I["belt"] + 1]]
    return float(np.interp(z, zs, xs))


R_OPEN, R_CLAD = 0.445, 0.515
for ay in AXLE:
    inner = arch_outline(ay, R_OPEN)
    poly = inner + [(inner[-1][0], 0.24), (inner[0][0], 0.24)]
    for side in (-1, 1):
        boolean(car, prism("cut", poly, side * 0.55, side * 1.4, "trim"))
    # 클래딩 고리: 바깥 가장자리(표면), 올라온 띠, 안쪽 립(구멍 속으로)
    outer = arch_outline(ay, R_CLAD, squareness=0.7)
    for side in (-1, 1):
        vv, ff = [], []
        rows_c = []
        for (yi, zi), (yo, zo) in zip(inner, outer):
            zo = max(zo, 0.30)
            zi = max(zi, 0.30)
            xo = side_x(yo, zo) * side
            xi = side_x(yi, zi) * side
            base = len(vv)
            vv += [(xo - side * 0.002, yo, zo), (xo + side * 0.014, yo, zo), (xi + side * 0.014, yi, zi), (xi - side * 0.07, yi, zi)]
            rows_c.append(base)
        for a, b in zip(rows_c, rows_c[1:]):
            for q in range(3):
                f = (a + q, a + q + 1, b + q + 1, b + q)
                ff.append(f if side > 0 else f[::-1])
        add_mesh("clad", vv, ff, "cladding", smooth=False)
    # 휠하우스 안쪽(검정): 구멍 속이 비어 보이지 않게
    for side in (-1, 1):
        liner = inner[:]
        poly_l = [(y, z) for y, z in liner] + [(liner[-1][0], 0.19), (liner[0][0], 0.19)]
        prism("liner", [(y, z - 0.012) if z > WHEEL_Z else (y, z) for y, z in poly_l], side * 0.52, side * 0.86, "trim")
print("ARCH faces", len(me.polygons), round(time.time() - t0, 1))

# ---------- 앞면: 램프 띠·픽셀 DRL·하단 흡기·스키드 ----------
def face_part(name, x0, x1, z0, z1, depth, mat, plane, sgn):
    """앞(sgn=-1)·뒤(+1) 끝면 평면 위에 붙는 상자: 뒷면은 기울어진 평면을 따라가고 depth 만큼 바깥으로 튀어나옴"""
    vv = []
    for z in (z0, z1):
        for x in (x0, x1):
            yb = plane(z) - sgn * 0.004
            vv += [(x, yb, z), (x, yb + sgn * depth, z)]
    # 꼭짓점: (z0,x0,back)(z0,x0,front)(z0,x1,b)(z0,x1,f)(z1,x0,b)(z1,x0,f)(z1,x1,b)(z1,x1,f)
    ff = [(0, 2, 6, 4), (1, 5, 7, 3), (0, 1, 3, 2), (4, 6, 7, 5), (0, 4, 5, 1), (2, 3, 7, 6)]
    return add_mesh(name, vv, ff, mat)


def front_part(name, x0, x1, z0, z1, depth, mat, smooth=False):
    return face_part(name, x0, x1, z0, z1, depth, mat, front_plane, -1)


LAMP_Z0, LAMP_Z1 = 0.775, 0.853
front_part("lamp_band", -0.84, 0.84, LAMP_Z0, LAMP_Z1, 0.006, "housing")
front_part("brow", -0.86, 0.86, LAMP_Z1, LAMP_Z1 + 0.016, 0.012, "body")  # 후드 앞 끝이 램프 띠 위로 살짝 나옴
PIX, PITCH = 0.034, 0.044
for side in (-1, 1):
    for cluster in (0, 1):
        xc = side * (0.745 - cluster * 0.215)
        for r in range(2):
            for c in range(4):
                x = xc + (c - 1.5) * PITCH
                z = 0.814 + (r - 0.5) * PITCH
                front_part("drl", x - PIX / 2, x + PIX / 2, z - PIX / 2, z + PIX / 2, 0.008, "lamp")
# 하단: 검정 흡기 띠(픽셀 무늬) + 은색 스키드 플레이트
front_part("intake", -0.64, 0.64, 0.42, 0.58, 0.006, "housing")
for r in range(2):
    for c in range(-15, 16):
        x, z = c * 0.040, 0.4625 + r * 0.045
        if abs(x) > 0.60:
            continue
        front_part("pix", x - 0.012, x + 0.012, z - 0.012, z + 0.012, 0.005, "cladding")
skid = [(-0.50, 0.29), (0.50, 0.29), (0.42, 0.40), (-0.42, 0.40)]
yb = front_plane(0.34) + 0.004
extrude_xz("skid_f", skid, yb - 0.02, yb, "garnish")

# ---------- 뒷면: 픽셀 테일램프 띠·반사판·스키드 ----------
def rear_part(name, x0, x1, z0, z1, depth, mat):
    return face_part(name, x0, x1, z0, z1, depth, mat, rear_plane, 1)


TAIL_Z0, TAIL_Z1 = 0.92, 1.03
rear_part("tail_band", -0.86, 0.86, TAIL_Z0, TAIL_Z1, 0.006, "housing")
rear_part("tail_line", -0.84, 0.84, 0.968, 0.982, 0.010, "tail")
for side in (-1, 1):
    for r in range(2):
        for c in range(5):
            x = side * (0.80 - c * 0.044)
            z = 0.975 + (r - 0.5) * 0.046
            rear_part("tpix", x - 0.017, x + 0.017, z - 0.017, z + 0.017, 0.012, "tail")
rear_part("rear_lower", -0.70, 0.70, 0.42, 0.60, 0.006, "housing")
for side in (-1, 1):
    for r in range(2):
        for c in range(4):
            x = side * (0.62 - c * 0.04)
            z = 0.50 + (r - 0.5) * 0.04
            rear_part("rref", x - 0.014, x + 0.014, z - 0.014, z + 0.014, 0.018, "tail")
yb = rear_plane(0.34) - 0.004
extrude_xz("skid_r", [(-0.50, 0.29), (0.50, 0.29), (0.42, 0.40), (-0.42, 0.40)], yb, yb + 0.02, "garnish")

# ---------- 지붕 스포일러·샤크핀·필러·손잡이·미러 ----------
sp = [(1.30, 1.580), (1.56, 1.552), (1.57, 1.535), (1.46, 1.530)]
prism("spoiler", sp, -0.60, 0.60, "body")
prism("spoiler_lip", [(1.50, 1.550), (1.57, 1.550), (1.57, 1.533), (1.50, 1.533)], -0.62, 0.62, "trim")
fin = [(1.05, 1.577), (1.30, 1.577), (1.28, 1.612), (1.22, 1.640), (1.10, 1.640)]
prism("fin", fin, -0.03, 0.03, "body")
# 필러 띠(새틴 블랙, 유리면에서 3mm)
def pillar(name, p0, p1, width, mat="trim"):
    """옆면 위 (y,z) 두 점을 잇는 띠. x는 그 높이의 유리면"""
    def X(y, z):
        pts = half_section(y)
        zs = [pts[I["glass_b"]][2], pts[I["glass_t"]][2], pts[I["roof_e"]][2]]
        xs = [pts[I["glass_b"]][0], pts[I["glass_t"]][0], pts[I["roof_e"]][0]]
        return float(np.interp(z, zs, xs))
    d = Vector((p1[0] - p0[0], p1[1] - p0[1])).normalized()
    nrm = Vector((-d.y, d.x)) * width / 2
    for side in (-1, 1):
        vv, ff = [], []
        for (y, z) in (p0 - nrm, p0 + nrm, p1 + nrm, p1 - nrm):
            x = X(y, z)
            vv += [(side * x, y, z), (side * (x + 0.003), y, z)]
        for k in range(4):
            a, b = 2 * k, 2 * ((k + 1) % 4)
            f = (a + 1, b + 1, b, a)
            ff.append(f if side > 0 else f[::-1])
        ff.append((1, 3, 5, 7) if side > 0 else (7, 5, 3, 1))
        add_mesh(name, vv, ff, mat)


pillar("b_pillar", Vector((SEAMS[1], ZB(SEAMS[1]) + 0.02)), Vector((SEAMS[1], ZRE(SEAMS[1]) - 0.07)), 0.10, "trim")
pillar("c_pillar", Vector((1.60, ZB(1.60) + 0.02)), Vector((1.38, ZRE(1.38) - 0.075)), 0.07, "trim")
pillar("c_pillar2", Vector((0.90, ZB(0.90) + 0.02)), Vector((0.88, ZRE(0.88) - 0.075)), 0.04, "trim")
# 창 아래 몰딩(검정, 벨트 바로 위)
for side in (-1, 1):
    vv, ff = [], []
    for k, y in enumerate(np.linspace(Y_GLASS0, Y_GLASS1, 30)):
        p = half_section(y)
        x, z = p[I["glass_b"]][0], p[I["glass_b"]][2]
        vv += [(side * x, y, z - 0.004), (side * (x + 0.004), y, z - 0.004), (side * (x + 0.004), y, z + 0.016), (side * x, y, z + 0.016)]
        if k:
            b = 4 * k
            for q in range(3):
                f = (b - 4 + q, b - 4 + q + 1, b + q + 1, b + q)
                ff.append(f if side > 0 else f[::-1])
    add_mesh("belt_trim", vv, ff, "trim")
# 플러시 도어 손잡이
for hy in (-0.18, 0.86 - 0.02):
    for side in (-1, 1):
        hz = ZB(hy) - 0.11
        x = side_x(hy, hz) * side
        box("handle", (x + side * 0.0025, hy, hz), (0.005, 0.15, 0.028), "trim")
# 미러: 몸통(도장) + 받침(검정) + 거울면
for side in (-1, 1):
    my, mz = -0.93, 1.14
    xo = side_x(my, ZB(my) - 0.02) * side
    body_m = box("mirror", (xo + side * 0.095, my, mz), (0.12, 0.17, 0.075), "body")
    for v in body_m.data.vertices:  # 바깥쪽 끝은 작게, 앞쪽은 둥글게 깎인 느낌
        if v.co.x * side > xo * side + 0.1:
            v.co.y = my + (v.co.y - my) * 0.8
            v.co.z = mz + (v.co.z - mz) * 0.75
        if v.co.y < my:
            v.co.z = mz + (v.co.z - mz) * 0.8
    bvm = body_m.modifiers.new("b", "BEVEL")
    bvm.width = 0.018
    bvm.segments = 3
    bvm.harden_normals = True
    apply(body_m, bvm)
    for p in body_m.data.polygons:
        p.use_smooth = True
    box("mirror_base", (xo + side * 0.03, my - 0.01, mz - 0.055), (0.06, 0.10, 0.06), "trim")
    box("mirror_glass", (xo + side * 0.095, my + 0.0865, mz), (0.10, 0.004, 0.06), "glass")

# ---------- 바퀴: 타이어·림·핀휠 블레이드 ----------
def lathe(name, profile, side, y, mat, seg=48, smooth=True):
    vv, ff = [], []
    m = len(profile)
    for k in range(seg):
        a = 2 * math.pi * k / seg
        for r, d in profile:
            vv.append((side * (WHEEL_X + d), y + r * math.cos(a), WHEEL_Z + r * math.sin(a)))
    for k in range(seg):
        for i in range(m - 1):
            q = (k * m + i, ((k + 1) % seg) * m + i, ((k + 1) % seg) * m + i + 1, k * m + i + 1)
            ff.append(q if side > 0 else q[::-1])
    return add_mesh(name, vv, ff, mat, smooth)


Rr, Rw = WHEEL_R, 0.258  # 타이어 반지름, 림 반지름(20인치)
TIRE_P = [(Rw - 0.01, -0.125), (Rr - 0.035, -0.125), (Rr - 0.010, -0.112), (Rr, -0.085), (Rr, 0.085), (Rr - 0.010, 0.112), (Rr - 0.035, 0.125), (Rw + 0.004, 0.128), (Rw - 0.01, 0.120)]
RIM_P = [(Rw - 0.01, 0.120), (Rw, 0.128), (Rw - 0.006, 0.128), (Rw - 0.014, 0.112), (Rw - 0.022, 0.104), (0.08, 0.075), (0.0, 0.075)]
BACK_P = [(Rw - 0.02, 0.098), (0.0, 0.098)]
for ay in AXLE:
    for side in (-1, 1):
        lathe("tire", TIRE_P, side, ay, "tire")
        lathe("rim", RIM_P, side, ay, "rim_dark", smooth=True)
        # 핀휠: 5쌍의 각진 블레이드(바깥으로 갈수록 휘어짐·두꺼워짐), 사이는 어두운 바닥
        vv, ff = [], []
        for k in range(5):
            a0 = 2 * math.pi * k / 5
            rows_b = 6
            base = len(vv)
            for i in range(rows_b):
                t = i / (rows_b - 1)
                r = 0.065 + t * (Rw - 0.016 - 0.065)
                a = a0 + t * 0.42                       # 바깥으로 갈수록 한쪽으로 쓸리는 핀휠
                wl, wr = 0.030 + t * 0.040, 0.030 + t * 0.012  # 앞선 쪽이 넓음(비대칭 날)
                h_lo, h_hi = 0.100 + t * 0.024, 0.126 + t * 0.006
                prof = ((-wl / r, h_lo), (-wl * 0.35 / r, h_hi), (wr * 0.45 / r, h_hi), (wr / r, h_lo - 0.012))  # 평평한 면 + 꺾인 면
                for (dang, hh) in prof:
                    vv.append((side * (WHEEL_X + hh), ay + r * math.cos(a + dang), WHEEL_Z + r * math.sin(a + dang)))
            for i in range(rows_b - 1):
                for q in range(3):
                    f = (base + 4 * i + q, base + 4 * i + q + 1, base + 4 * (i + 1) + q + 1, base + 4 * (i + 1) + q)
                    ff.append(f if side > 0 else f[::-1])
            # 날 옆면(두께)
            for q, (ia, ib) in enumerate(((0, 0), (3, 3))):
                pass
        add_mesh("blades", vv, ff, "rim", smooth=False)
        # 허브 캡(무지)
        lathe("hub", [(0.075, 0.118), (0.06, 0.128), (0.0, 0.130)], side, ay, "rim_dark")

# ---------- 합치기·정리·내보내기 ----------
for o in scene.objects:
    o.select_set(o.type == "MESH")
bpy.context.view_layer.objects.active = car
bpy.ops.object.join()
car = bpy.context.view_layer.objects.active
car.name = "own_car"
# 각진 곳은 날카롭게(법선 분리), 완만한 곳은 매끈하게. 베벨이 만든 커스텀 법선은 유지
bpy.ops.object.mode_set(mode="EDIT")
bpy.ops.mesh.select_all(action="SELECT")
bpy.ops.mesh.normals_make_consistent(inside=False)
bpy.ops.mesh.select_all(action="DESELECT")
bpy.ops.object.mode_set(mode="OBJECT")
bm = bmesh.new()
bm.from_mesh(car.data)
for e in bm.edges:
    if len(e.link_faces) == 2 and e.calc_face_angle(0) > math.radians(34):
        e.smooth = False
bm.to_mesh(car.data)
bm.free()
# 몸체 조각은 매끈 표시, 상자 조각들은 평면 표시(이미 각각 지정됨)
car.data.calc_loop_triangles()
dims = [round(v, 3) for v in car.dimensions]
bb = [car.matrix_world @ Vector(c) for c in car.bound_box]
print("TRIS", len(car.data.loop_triangles), "DIMS", dims, "BBOX", [round(min(v[i] for v in bb), 3) for i in range(3)], [round(max(v[i] for v in bb), 3) for i in range(3)], "SECONDS", round(time.time() - t0))
assert len(car.data.loop_triangles) < 90000, "too many triangles"
assert 2.0 < dims[0] < 2.3 and 4.5 < dims[1] < 4.8 and 1.5 < dims[2] < 1.7, dims
bpy.data.orphans_purge(do_recursive=True)
if BLEND:
    bpy.ops.wm.save_as_mainfile(filepath=BLEND)
bpy.ops.export_scene.gltf(filepath=GLB, export_format="GLB", use_selection=False, export_apply=True, export_yup=True,
                          export_materials="EXPORT", export_normals=True, export_texcoords=False)
print("GLB", GLB)
