"""사고 3D 화면의 내 차 모델을 직접 만든다(리토폴로지).
참고 모델의 표면 위치·방향·색 구역만 재서, 메시·재질·휠은 여기서 새로 만든다. 참고 모델의 메시·그림은 결과에 들어가지 않는다. 로고·글자 없음.
참고: "Hyundai Ioniq 5 Lowpoly" (Sketchfab andikapratamaw, CC BY 4.0) → NOTICE.md 출처 표기.

실행: blender -b --disable-autoexec <참고.blend> --python design/car/build_car_model.py -- <결과.glb> [<결과.blend>] [그림 크기]
앞 = Blender -Y (glTF 에서는 +Z, 앱의 fit() 이 앞을 -Z 로 돌린다).
"""
import bpy, bmesh, math, sys, time
import numpy as np
from mathutils import Vector
from mathutils.bvhtree import BVHTree

argv = sys.argv[sys.argv.index("--") + 1:]
GLB = argv[0]
BLEND = argv[1] if len(argv) > 1 and argv[1] != "-" else None
TEX = int(argv[2]) if len(argv) > 2 else 1536

# ---------- 참고 모델 읽기: 측정 전용 ----------
ref = bpy.data.objects["SM_Ioniq_5_Body"]
atlas_img = bpy.data.images["T_Ioniq_5_D.png"]
AW, AH = atlas_img.size
ATLAS = np.empty(AW * AH * 4, np.float32)
atlas_img.pixels.foreach_get(ATLAS)
ATLAS = ATLAS.reshape(AH, AW, 4)
EMBLEM = (470, 465, 712, 592)
INFO = (612, 10, 850, 316)
S_DARK = (885, 248, 1012, 372)
S_LIGHT = (885, 378, 1012, 503)
S_BLACK = (885, 506, 1012, 628)
S_GREY = (885, 628, 1012, 750)


def in_rect(u, v, r):
    x, y = u * AW, (1 - v) * AH
    return r[0] <= x <= r[2] and r[1] <= y <= r[3]


me = ref.data
me.calc_loop_triangles()
uvd = me.uv_layers.active.data
cn = me.corner_normals
mw = ref.matrix_world
nm3 = mw.to_3x3()
verts = [mw @ v.co for v in me.vertices]
mats = [m.name if m else "" for m in me.materials]

# 떨어진 덩어리 번호
parent = list(range(len(me.vertices)))


def find(a):
    while parent[a] != a:
        parent[a] = parent[parent[a]]
        a = parent[a]
    return a


for e in me.edges:
    a, b = find(e.vertices[0]), find(e.vertices[1])
    if a != b:
        parent[a] = b


class Tri:
    __slots__ = ("idx", "mat", "uv", "n")


GROUPS = {"body": [], "mirror": [], "fin": [], "wing": []}
island_size = {}
for t in me.loop_triangles:
    island_size[find(t.vertices[0])] = island_size.get(find(t.vertices[0]), 0) + 1
main_island = max(island_size, key=island_size.get)
for t in me.loop_triangles:
    tr = Tri()
    tr.idx = tuple(t.vertices)
    tr.mat = mats[t.material_index]
    tr.uv = [tuple(uvd[l].uv) for l in t.loops]
    tr.n = [nm3 @ Vector(cn[l].vector) for l in t.loops]
    c = sum((verts[i] for i in tr.idx), Vector()) / 3
    u, v = sum(p[0] for p in tr.uv) / 3, sum(p[1] for p in tr.uv) / 3
    if tr.mat == "M_Ioniq" and (in_rect(u, v, EMBLEM) or in_rect(u, v, INFO)):
        continue  # 로고·글자
    root = find(t.vertices[0])
    if root != main_island and island_size[root] <= 8 and abs(c.x) < 0.1 and c.z > 0.65:
        continue  # 앞뒤 로고·문자 조각
    if root != main_island and abs(c.x) > 0.78 and 1.05 < c.z < 1.26 and c.y < -0.75:
        GROUPS["mirror"].append(tr)
    elif root != main_island and island_size[root] > 40 and c.z > 1.5:
        GROUPS["fin"].append(tr)
    elif root == main_island and c.y > 1.30 and c.z > 1.40:
        GROUPS["wing"].append(tr)
    else:
        GROUPS["body"].append(tr)
print("REF_TRIS", {k: len(v) for k, v in GROUPS.items()})

# 색 구역: 참고 그림을 그대로 쓰지 않고 몇 가지 재질로 나눈다
PAINT, GLASS, GLOSS, TRIM, CLAD, CHROME, WHITE, RED = range(8)
#       색(sRGB 16진)  거칠기  금속
LOOK = {PAINT: (0xBEB9B1, 0.36, 0.55), GLASS: (0x31363B, 0.06, 1.0), GLOSS: (0x08090A, 0.2, 0.0), TRIM: (0x202124, 0.55, 0.0),
        CLAD: (0x75767B, 0.62, 0.0), CHROME: (0xB5B8BC, 0.25, 0.9), WHITE: (0xFAFBFD, 0.15, 0.0), RED: (0xC8180F, 0.2, 0.0)}


def classify(tr, w0, w1, w2):
    if tr.mat == "M_Gravity_Gold_Matte":
        return PAINT
    if tr.mat == "M_Emission":
        return WHITE
    if tr.mat == "M_Black_Dark":
        return GLOSS
    u = tr.uv[0][0] * w0 + tr.uv[1][0] * w1 + tr.uv[2][0] * w2
    v = tr.uv[0][1] * w0 + tr.uv[1][1] * w1 + tr.uv[2][1] * w2
    x, y = min(max(int(u * AW), 0), AW - 1), min(max(int(v * AH), 0), AH - 1)
    r, g, b, a = ATLAS[y, x]
    py = AH - 1 - y
    if S_LIGHT[0] <= x <= S_LIGHT[2] and S_LIGHT[1] <= py <= S_LIGHT[3]:
        return GLASS
    if a < 0.5:
        return None  # 투명: 뒤에 있는 면을 본다
    if S_DARK[0] <= x <= S_DARK[2] and S_DARK[1] <= py <= S_DARK[3]:
        return GLASS
    if S_BLACK[0] <= x <= S_BLACK[2] and S_BLACK[1] <= py <= S_BLACK[3]:
        return GLOSS
    if S_GREY[0] <= x <= S_GREY[2] and S_GREY[1] <= py <= S_GREY[3]:
        return CLAD
    lum = (r + g + b) / 3
    if r > 0.3 and r > g * 1.7:
        return RED
    if lum < 0.17:
        return GLOSS
    if lum < 0.42:
        return TRIM
    if lum < 0.72:
        return CHROME
    return WHITE


class Target:
    def __init__(self, tris):
        self.tris = tris
        self.bvh = BVHTree.FromPolygons(verts, [t.idx for t in tris], all_triangles=True, epsilon=0.0)

    def hit(self, origin, direction, maxd=20.0):
        """(위치, 매끈 법선, 재질 번호) 또는 None. 그림이 투명인 조각은 뚫고 지나간다"""
        o = origin
        for _ in range(4):
            loc, fn, i, dist = self.bvh.ray_cast(o, direction, maxd)
            if loc is None:
                return None
            tr = self.tris[i]
            a, b, c = (verts[k] for k in tr.idx)
            v0, v1, v2 = b - a, c - a, loc - a
            d00, d01, d11, d20, d21 = v0.dot(v0), v0.dot(v1), v1.dot(v1), v2.dot(v0), v2.dot(v1)
            den = d00 * d11 - d01 * d01 or 1e-12
            w1 = (d11 * d20 - d01 * d21) / den
            w2 = (d00 * d21 - d01 * d20) / den
            w0 = 1 - w1 - w2
            cl = classify(tr, w0, w1, w2)
            if cl is not None:
                n = tr.n[0] * w0 + tr.n[1] * w1 + tr.n[2] * w2
                if n.dot(direction) > 0:
                    n = -n
                return loc, n.normalized(), cl
            o = loc + direction * 0.0005
        return None


T_BODY, T_FIN, T_WING = (Target(GROUPS[k]) for k in ("body", "fin", "wing"))

# ---------- 차체: 가운데 선에서 밖으로 뻗는 격자 ----------
Y0, Y1, ZC = -1.70, 1.30, 0.72
CAP_N, BODY_N, NT = 44, 110, 60  # 앞 뚜껑·몸통·뒤 뚜껑 고리 수, 절반 둘레 점 수
NS = CAP_N + BODY_N + CAP_N + 1


def rays(p, t):
    """p: 고리 번호(소수 가능), t: 0 아래 가운데 ~ NT-1 위 가운데. 배열 지원. (가운데 점, 방향)"""
    p, t = np.asarray(p, float), np.asarray(t, float)
    th = math.pi * t / (NT - 1)
    sx, cz = np.sin(th), -np.cos(th)
    front = p < CAP_N
    rear = p > CAP_N + BODY_N
    phi = np.where(front, p / CAP_N, np.where(rear, (NS - 1 - p) / CAP_N, 1.0)) * math.pi / 2
    sy = np.where(front, Y0, np.where(rear, Y1, Y0 + (Y1 - Y0) * (p - CAP_N) / BODY_N))
    dy = np.where(front, -np.cos(phi), np.where(rear, np.cos(phi), 0.0))
    s = np.sin(phi)
    d = np.stack((sx * s, dy, cz * s), axis=-1)
    o = np.stack((np.zeros_like(sy), sy, np.full_like(sy, ZC)), axis=-1)
    return o, d


t0 = time.time()
WHEELS = (-1.713, 1.309)
WHEEL_R, WHEEL_Z = 0.3757, 0.377
WHEEL_X, WHEEL_W = 0.747, 0.25


def side_x(y, z):
    h = T_BODY.bvh.ray_cast(Vector((2, y, z)), Vector((-1, 0, 0)), 3)
    return h[0].x if h[0] is not None else 0.0


ARCHES = {}
for wy in WHEELS:
    pts = []
    for k in range(45):
        a = math.radians(-24 + 228 * k / 44)
        c, s_ = math.cos(a), math.sin(a)
        edge = 0.72
        r = 0.34
        while r < 0.74:
            if side_x(wy + r * c, WHEEL_Z + r * s_) > 0.80:
                edge = r
                break
            r += 0.004
        pts.append((wy + edge * c, WHEEL_Z + edge * s_))
    ARCHES[wy] = pts
print("ARCH_TOP", {wy: round(max(p[1] for p in pts), 3) for wy, pts in ARCHES.items()})


def in_poly(y, z, pts):
    inside = False
    for (y0, z0), (y1, z1) in zip(pts, pts[1:] + pts[:1]):
        if (z0 > z) != (z1 > z) and y < y0 + (z - z0) / (z1 - z0) * (y1 - y0):
            inside = not inside
    return inside


arch_polys = {wy: pts + [(pts[-1][0], -0.3), (pts[0][0], -0.3)] for wy, pts in ARCHES.items()}

R = np.zeros((NS, NT))
bad = np.zeros((NS, NT), bool)
for i in range(NS):
    o, d = rays(np.full(NT, float(i)), np.arange(NT, dtype=float))
    for j in range(NT):
        D = Vector(d[j])
        C0 = Vector(o[j])
        h = T_BODY.hit(C0 + D * 5, -D, 5.0)
        if h is None:
            bad[i, j] = True
            continue
        P = h[0]
        R[i, j] = (P - C0).length
        if abs(D.x) > 0.35 and any(in_poly(C0.y + D.y * R[i, j], C0.z + D.z * R[i, j], poly) for poly in arch_polys.values()):
            bad[i, j] = True  # 바퀴 구멍 자리
print("BAD", int(bad.sum()), "of", bad.size)
# 구멍·못 맞힌 점은 주변에서 메운다(바퀴 구멍은 뒤에서 파낸다)
R[bad] = R[~bad].mean()
for _ in range(600):
    avg = R.copy()
    avg[1:-1, 1:-1] = (R[:-2, 1:-1] + R[2:, 1:-1] + R[1:-1, :-2] + R[1:-1, 2:]) / 4
    avg[0], avg[-1] = avg[1], avg[-2]
    avg[:, 0], avg[:, -1] = avg[:, 1], avg[:, -2]
    R[bad] = avg[bad]
# 손잡이·장식 조각이 뜬 만큼 생긴 울퉁불퉁함을 없앤다. 잔 요철은 법선 그림이 맡는다
stack = np.stack((R[1:-1, 1:-1], R[:-2, 1:-1], R[2:, 1:-1], R[1:-1, :-2], R[1:-1, 2:]))
R[1:-1, 1:-1] = np.median(stack, axis=0)
for _ in range(2):
    S = R.copy()
    S[1:-1, 1:-1] = (4 * R[1:-1, 1:-1] + 2 * (R[:-2, 1:-1] + R[2:, 1:-1] + R[1:-1, :-2] + R[1:-1, 2:]) + R[:-2, :-2] + R[2:, 2:] + R[:-2, 2:] + R[2:, :-2]) / 16
    R = S
ii, jj = np.meshgrid(np.arange(NS, dtype=float), np.arange(NT, dtype=float), indexing="ij")
O_, D_ = rays(ii, jj)
G = O_ + D_ * R[..., None]
G[..., 0] = np.maximum(G[..., 0], 0)
G[:, 0, 0] = 0
G[:, -1, 0] = 0
print("GRID", round(time.time() - t0))

# ---------- 굽기: 그림 칸마다 참고 표면을 재서 재질 구역·법선을 얻는다 ----------
us = np.linspace(0, NS - 1, TEX)
vs = np.linspace(0, NT - 1, TEX)
P_, T_ = np.meshgrid(us, vs)  # 줄 = 둘레, 칸 = 앞뒤
O2, D2 = rays(P_, T_)
ST = O2 + D2 * 5
CLS = np.full((TEX, TEX), TRIM, np.int8)
NW = np.zeros((TEX, TEX, 3), np.float32)
HAS = np.zeros((TEX, TEX), bool)
hit = T_BODY.hit
for j in range(TEX):
    sr, dr = ST[j], D2[j]
    for i in range(TEX):
        d = Vector(dr[i])
        h = hit(Vector(sr[i]), -d, 5.0)
        if h is not None:
            CLS[j, i], NW[j, i], HAS[j, i] = h[2], h[1], True
    if j % 200 == 0:
        print("BAKE", j, round(time.time() - t0), flush=True)
print("BAKED", round(time.time() - t0))

# 낮은 격자의 면 방향
i0 = np.clip(us.astype(int), 0, NS - 2)
j0 = np.clip(vs.astype(int), 0, NT - 2)
fu = (us - i0)[None, :, None]
fv = (vs - j0)[:, None, None]
GG = G.transpose(1, 0, 2).astype(np.float32)
P = (GG[j0][:, i0] * (1 - fu) + GG[j0][:, i0 + 1] * fu) * (1 - fv) + (GG[j0 + 1][:, i0] * (1 - fu) + GG[j0 + 1][:, i0 + 1] * fu) * fv
dU, dV = np.gradient(P, axis=1), np.gradient(P, axis=0)
N = np.cross(dU, dV)
N /= np.linalg.norm(N, axis=2, keepdims=True) + 1e-9
Tn = dU / (np.linalg.norm(dU, axis=2, keepdims=True) + 1e-9)
Bn = np.cross(N, Tn)
nx, ny, nz = (NW * Tn).sum(2), (NW * Bn).sum(2), (NW * N).sum(2)
far = (~HAS) | (nz < 0.15)  # 못 맞혔거나 너무 꺾인 곳은 낮은 면 그대로
nx[far], ny[far], nz[far] = 0, 0, 1
ln = np.sqrt(nx * nx + ny * ny + nz * nz) + 1e-9
NORM = np.dstack((nx / ln * 0.5 + 0.5, ny / ln * 0.5 + 0.5, nz / ln * 0.5 + 0.5, np.ones_like(nx)))


def hex_rgb(h):
    return [(h >> s & 255) / 255 for s in (16, 8, 0)]


def to_linear(c):
    return tuple(v / 12.92 if v <= 0.04045 else ((v + 0.055) / 1.055) ** 2.4 for v in c)


COLOR = np.ones((TEX, TEX, 4), np.float32)
ORM = np.ones((TEX, TEX, 4), np.float32)
for k, (h, ro, mt) in LOOK.items():
    m = CLS == k
    COLOR[m, :3] = hex_rgb(h)  # 그림 칸에는 sRGB 값을 그대로 넣는다
    ORM[m, 1], ORM[m, 2] = ro, mt
COLOR[:6, :6, :3] = hex_rgb(LOOK[TRIM][0])  # 파낸 면(UV 0,0)은 검게
ORM[:6, :6, 1], ORM[:6, :6, 2] = 0.8, 0
NORM[:6, :6] = (0.5, 0.5, 1, 1)


def image(name, arr, non_color):
    img = bpy.data.images.new(name, TEX, TEX, alpha=False)
    if non_color:
        img.colorspace_settings.name = "Non-Color"
    img.pixels.foreach_set(arr.astype(np.float32).ravel())
    img.update()
    img.pack()
    return img


# ---------- 결과 장면: 새로 만든 것만 담는다. 작은 부품 측정은 참고 메시 메모리(verts)만 쓴다 ----------
for o in list(bpy.data.objects):
    bpy.data.objects.remove(o)
scene = bpy.context.scene


def flat(name, k=None, rgb=None, rough=0.5, metal=0.0):
    m = bpy.data.materials.new(name)
    m.use_nodes = True
    b = m.node_tree.nodes["Principled BSDF"]
    if k is not None:
        h, rough, metal = LOOK[k]
        rgb = to_linear(hex_rgb(h))
    b.inputs["Base Color"].default_value = (*rgb, 1)
    b.inputs["Roughness"].default_value = rough
    b.inputs["Metallic"].default_value = metal
    return m


FLATS = {k: flat(f"part_{k}", k) for k in LOOK}
TIRE = flat("tire", rgb=to_linear((0.09, 0.09, 0.1)), rough=0.9)
RIM_BASE = flat("rim_base", rgb=to_linear((0.07, 0.07, 0.08)), rough=0.55)
RIM_BLADE = flat("rim_blade", rgb=to_linear((0.34, 0.35, 0.37)), rough=0.35, metal=0.6)

BODY = bpy.data.materials.new("body")
BODY.use_nodes = True
nt = BODY.node_tree
b = nt.nodes["Principled BSDF"]
tc = nt.nodes.new("ShaderNodeTexImage")
tc.image = image("car_color", COLOR, False)
tc.extension = "EXTEND"
nt.links.new(tc.outputs["Color"], b.inputs["Base Color"])
tn = nt.nodes.new("ShaderNodeTexImage")
tn.image = image("car_normal", NORM, True)
tn.extension = "EXTEND"
nmn = nt.nodes.new("ShaderNodeNormalMap")
nt.links.new(tn.outputs["Color"], nmn.inputs["Color"])
nt.links.new(nmn.outputs["Normal"], b.inputs["Normal"])
to = nt.nodes.new("ShaderNodeTexImage")
to.image = image("car_orm", ORM, True)
to.extension = "EXTEND"
sp = nt.nodes.new("ShaderNodeSeparateColor")
nt.links.new(to.outputs["Color"], sp.inputs["Color"])
nt.links.new(sp.outputs["Green"], b.inputs["Roughness"])
nt.links.new(sp.outputs["Blue"], b.inputs["Metallic"])

bm = bmesh.new()
uv = bm.loops.layers.uv.new("UVMap")
right = [[bm.verts.new(G[i, j]) for j in range(NT)] for i in range(NS)]
left = [[right[i][j] if j in (0, NT - 1) else bm.verts.new((-G[i, j, 0], G[i, j, 1], G[i, j, 2])) for j in range(NT)] for i in range(NS)]
for vs_, fl in ((right, False), (left, True)):
    for i in range(NS - 1):
        for j in range(NT - 1):
            q = [(i, j), (i + 1, j), (i + 1, j + 1), (i, j + 1)]
            if fl:
                q = q[::-1]
            try:
                f = bm.faces.new([vs_[a][c] for a, c in q])
            except ValueError:
                continue
            for lp, (a, c) in zip(f.loops, q):
                lp[uv].uv = (a / (NS - 1), c / (NT - 1))
for f in bm.faces:
    f.smooth = True
bmesh.ops.remove_doubles(bm, verts=bm.verts, dist=1e-4)
bmesh.ops.dissolve_degenerate(bm, dist=1e-5, edges=bm.edges)
bmesh.ops.recalc_face_normals(bm, faces=bm.faces)
body = bpy.data.meshes.new("body")
bm.to_mesh(body)
bm.free()
body.materials.append(BODY)
car = bpy.data.objects.new("body", body)
scene.collection.objects.link(car)


def add_mesh(name, vv, ff, material, smooth=True, fix=True):
    m = bpy.data.meshes.new(name)
    m.from_pydata(vv, [], ff)
    if isinstance(material, list):
        for x in material:
            m.materials.append(x)
    else:
        m.materials.append(material)
    for p in m.polygons:
        p.use_smooth = smooth
    if fix:
        b2 = bmesh.new()
        b2.from_mesh(m)
        bmesh.ops.recalc_face_normals(b2, faces=b2.faces)
        b2.to_mesh(m)
        b2.free()
    o = bpy.data.objects.new(name, m)
    scene.collection.objects.link(o)
    return o


# 바퀴 구멍: 잰 모양대로 파낸다
for wy, poly in arch_polys.items():
    for side in (-1, 1):
        n = len(poly)
        x0, x1 = side * 0.46, side * 1.3
        vv = [(x0, p[0], p[1]) for p in poly] + [(x1, p[0], p[1]) for p in poly]
        ff = [tuple(range(n)), tuple(range(2 * n - 1, n - 1, -1))] + [(k, (k + 1) % n, n + (k + 1) % n, n + k) for k in range(n)]
        cut = add_mesh("cut", vv, ff, BODY, smooth=False)
        mod = car.modifiers.new("arch", "BOOLEAN")
        mod.operation = "DIFFERENCE"
        mod.solver = "EXACT"
        mod.use_self = True
        mod.use_hole_tolerant = True
        mod.object = cut
        bpy.context.view_layer.objects.active = car
        bpy.ops.object.modifier_apply(modifier=mod.name)
        bpy.data.objects.remove(cut)
print("BODY_FACES", len(car.data.polygons))

# ---------- 작은 부품: 공 모양 격자를 안으로 쏴서 표면을 잰다 ----------
SLOTS = [FLATS[k] for k in range(8)]


def wrap(name, target, center, radii, nu=22, nv=14, mirror_x=False):
    vv, cl = [], []
    grid = []
    for i in range(nv + 1):
        el = math.pi * i / nv
        row = []
        for k in range(nu):
            az = 2 * math.pi * k / nu
            D = Vector((math.sin(el) * math.cos(az) * radii[0], math.sin(el) * math.sin(az) * radii[1], math.cos(el) * radii[2])).normalized()
            h = target.hit(Vector(center) + D * 3, -D, 3)
            p = h[0] if h else Vector(center)
            row.append(len(vv))
            vv.append(tuple(p))
            cl.append(h[2] if h else TRIM)
        grid.append(row)
    ff, mi = [], []
    for i in range(nv):
        for k in range(nu):
            q = (grid[i][k], grid[i][(k + 1) % nu], grid[i + 1][(k + 1) % nu], grid[i + 1][k])
            ff.append(q)
            c_ = [cl[x] for x in q]
            mi.append(max(set(c_), key=c_.count))
    for sgn in ((1, -1) if mirror_x else (1,)):
        o = add_mesh(name, [(p[0] * sgn, p[1], p[2]) for p in vv], ff, SLOTS, fix=False)
        for p, m_ in zip(o.data.polygons, mi):
            p.material_index = m_
        b2 = bmesh.new()
        b2.from_mesh(o.data)
        bmesh.ops.remove_doubles(b2, verts=b2.verts, dist=1e-4)
        bmesh.ops.dissolve_degenerate(b2, dist=1e-5, edges=b2.edges)
        bmesh.ops.recalc_face_normals(b2, faces=b2.faces)
        b2.to_mesh(o.data)
        b2.free()


mir = [t for t in GROUPS["mirror"] if verts[t.idx[0]].x > 0]
mx = [verts[i] for t in mir for i in t.idx]
mc = tuple((min(p[i] for p in mx) + max(p[i] for p in mx)) / 2 for i in range(3))
wrap("mirror", Target(mir), mc, (0.15, 0.12, 0.08), nu=24, nv=16, mirror_x=True)
fx = [verts[i] for t in GROUPS["fin"] for i in t.idx]
wrap("fin", T_FIN, (0, (min(p.y for p in fx) + max(p.y for p in fx)) / 2, min(p.z for p in fx) + 0.02), (0.05, 0.09, 0.07), nu=12, nv=8)

# 뒤 날개: 위아래에서 쏴서 판을 만든다
wx = [verts[i] for t in GROUPS["wing"] for i in t.idx]
xr, yr = max(abs(p.x) for p in wx), (min(p.y for p in wx), max(p.y for p in wx))
print("WING", round(xr, 3), [round(v, 3) for v in yr], round(min(p.z for p in wx), 3), round(max(p.z for p in wx), 3))
NX_, NY_ = 44, 14
for sgn_z in (1, -1):
    idx = {}
    vv, cl = [], []
    start = Vector((0, 0, 3.0 if sgn_z > 0 else 1.2))
    down = Vector((0, 0, -sgn_z))
    for ix in range(NX_ + 1):
        x = -xr * 0.998 + 2 * xr * 0.998 * ix / NX_
        # 이 줄에서 날개가 시작·끝나는 곳을 찾아 그 사이만 나눈다(가장자리가 들쭉날쭉하지 않게)
        found = [y for y in np.arange(yr[0], yr[1] + 0.002, 0.002) if T_WING.bvh.ray_cast(Vector((x, y, start.z)), down, 3)[0] is not None]
        if len(found) < 2:
            continue
        for iy in range(NY_ + 1):
            y = found[0] + (found[-1] - found[0]) * iy / NY_
            loc = T_WING.bvh.ray_cast(Vector((x, y, start.z)), down, 3)[0]
            if loc is not None:  # 날개 틈은 메운다: 위는 차체색 판, 아래는 검정
                idx[ix, iy] = len(vv)
                vv.append(list(loc))
    for _ in range(4):  # 틈 자리의 높낮이 차이를 고른다
        zs = [p[2] for p in vv]
        for (ix, iy), k in idx.items():
            nb = [zs[idx[q]] for q in ((ix - 1, iy), (ix + 1, iy), (ix, iy - 1), (ix, iy + 1)) if q in idx]
            vv[k][2] = (zs[k] * 2 + sum(nb)) / (2 + len(nb))
    ff = []
    for ix in range(NX_):
        for iy in range(NY_):
            q = [(ix, iy), (ix + 1, iy), (ix + 1, iy + 1), (ix, iy + 1)]
            if all(k in idx for k in q):
                ids = [idx[k] for k in q]
                ff.append(tuple(ids if sgn_z > 0 else ids[::-1]))
    add_mesh("wing", [tuple(p) for p in vv], ff, FLATS[PAINT] if sgn_z > 0 else FLATS[GLOSS], fix=False)


# ---------- 바퀴: 직접 만든 터빈 모양 ----------
def lathe(name, profile, side, y, material, seg=40):
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
    return add_mesh(name, vv, ff, material, fix=False)


Rr = WHEEL_R
# 프로필 순서: 안쪽 → 바깥(옆면 쪽). 면이 밖을 보게 앞에서부터 지름을 줄인다
TIRE_P = [(0.262, -0.125), (Rr - 0.03, -0.125), (Rr - 0.008, -0.105), (Rr, -0.07), (Rr, 0.07), (Rr - 0.008, 0.105), (Rr - 0.03, 0.125), (0.285, 0.128), (0.262, 0.118)]
RIM_P = [(0.262, 0.118), (0.255, 0.128), (0.243, 0.128), (0.232, 0.095), (0.17, 0.08), (0.05, 0.08), (0.0, 0.082)]
for wy in WHEELS:
    for side in (-1, 1):
        lathe("tire", TIRE_P, side, wy, TIRE)
        lathe("rim", RIM_P, side, wy, RIM_BASE)
        vv, ff = [], []  # 휘어진 날개 18개
        for k in range(18):
            a0 = 2 * math.pi * k / 18
            rows = 7
            base = len(vv)
            for i in range(rows):
                t = i / (rows - 1)
                r = 0.062 + t * 0.175
                a = a0 + t * 0.95
                w = 0.006 + t * 0.016
                h_ = 0.083 + t * 0.042
                for off, lift in ((-w, 0.0), (w, 0.012)):
                    a2 = off / r
                    vv.append((side * (WHEEL_X + h_ + lift), wy + r * math.cos(a + a2), WHEEL_Z + r * math.sin(a + a2)))
            for i in range(rows - 1):
                q = (base + 2 * i, base + 2 * i + 2, base + 2 * i + 3, base + 2 * i + 1)
                ff.append(q if side > 0 else q[::-1])
        add_mesh("blades", vv, ff, RIM_BLADE, smooth=False, fix=False)

# ---------- 합치기 ----------
for o in scene.objects:
    o.select_set(True)
bpy.context.view_layer.objects.active = car
bpy.ops.object.join()
car = bpy.context.view_layer.objects.active
car.name = "own_car"
bpy.ops.object.shade_smooth_by_angle(angle=math.radians(40))
bpy.context.view_layer.update()
car.data.calc_loop_triangles()
print("TRIS", len(car.data.loop_triangles), "SIZE", [round(v, 3) for v in car.dimensions], "MATERIALS", len(car.data.materials), "SECONDS", round(time.time() - t0))

# 참고 파일의 그림·재질이 결과에 섞이지 않게 지운다
for im in list(bpy.data.images):
    if im.name not in ("car_color", "car_normal", "car_orm"):
        bpy.data.images.remove(im)
keep = {m.name for m in car.data.materials if m}
for mt in list(bpy.data.materials):
    if mt.name not in keep:
        bpy.data.materials.remove(mt)
bpy.data.orphans_purge(do_recursive=True)
if BLEND:
    bpy.ops.wm.save_as_mainfile(filepath=BLEND)
bpy.ops.export_scene.gltf(filepath=GLB, export_format="GLB", use_selection=False, export_apply=True, export_yup=True,
                          export_materials="EXPORT", export_normals=True, export_texcoords=True,
                          export_image_format="JPEG", export_jpeg_quality=90)
print("GLB", GLB)
