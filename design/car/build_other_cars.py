"""사고 3D 화면의 상대 차(승용차·SUV·승합차·트럭·버스)를 처음부터 만든다. 다른 모델을 쓰지 않는다. 로고·문자 없음.
실행: blender -b --python design/car/build_other_cars.py -- <결과 폴더>
앞 = Blender -Y (glTF 에서는 +Z, 앱의 fit() 이 앞을 -Z 로 돌린다).
"""
import bpy, bmesh, math, os, sys
import numpy as np

OUT = sys.argv[sys.argv.index("--") + 1]


def srgb(h):
    c = [(h >> s & 255) / 255 for s in (16, 8, 0)]
    return tuple(v / 12.92 if v <= 0.04045 else ((v + 0.055) / 1.055) ** 2.4 for v in c)


def mat(name, h, rough=0.5, metal=0.0):
    m = bpy.data.materials.new(name)
    m.use_nodes = True
    b = m.node_tree.nodes["Principled BSDF"]
    b.inputs["Base Color"].default_value = (*srgb(h), 1)
    b.inputs["Roughness"].default_value = rough
    b.inputs["Metallic"].default_value = metal
    return m


def materials():
    return [mat("paint", 0xC9CCD1, 0.4, 0.4), mat("glass", 0x2E3338, 0.08, 1.0), mat("trim", 0x202124, 0.6), mat("lamp", 0xF7F8FA, 0.2), mat("tail", 0xC8180F, 0.25),
            mat("tire", 0x18191B, 0.9), mat("rim", 0xA8ABB0, 0.3, 0.8)]


PAINT, GLASS, TRIM, LAMP, TAIL, TIRE, RIM = range(7)


def curve(table):
    xs = np.array([p[0] for p in table], float)
    ys = np.array([p[1] for p in table], float)
    h, d = np.diff(xs), np.diff(ys) / np.diff(xs)
    m = np.zeros_like(ys)
    m[0], m[-1] = d[0], d[-1]
    for i in range(1, len(ys) - 1):
        if d[i - 1] * d[i] > 0:
            w1, w2 = 2 * h[i] + h[i - 1], h[i] + 2 * h[i - 1]
            m[i] = (w1 + w2) / (w1 / d[i - 1] + w2 / d[i])

    def f(y):
        y = float(np.clip(y, xs[0], xs[-1]))
        i = min(int(np.searchsorted(xs, y, side="right")) - 1, len(h) - 1)
        t = (y - xs[i]) / h[i]
        return ((2 * t ** 3 - 3 * t ** 2 + 1) * ys[i] + (t ** 3 - 2 * t ** 2 + t) * h[i] * m[i]
                + (-2 * t ** 3 + 3 * t ** 2) * ys[i + 1] + (t ** 3 - t ** 2) * h[i] * m[i + 1])
    return f


SPANS = [(2, 0.0), (2, 0.5), (3, 1.0), (4, 1.0), (4, 0.7), (6, 0.8), (6, 0.5), (0, 1.0)]
BELT_I = sum(s[0] for s in SPANS[:4])
RAIL_I = sum(s[0] for s in SPANS[:5])


def ring(s, y):
    fl, w, top, rail, belt, crown = s["floor"](y), s["half"](y), s["top"](y), s["rail"](y), s["belt"](y), s["crown"]
    belt = min(belt, top - 0.01)
    tz = max(top - crown, belt + 0.006)
    c = [np.array(p, float) for p in ((0, fl), (max(w - 0.14, 0.15), fl), (w - 0.02, fl + 0.05), (w, fl + 0.2), (rail + (w - rail) * 0.8, belt), (rail, tz), (rail * 0.55, top - crown * 0.28), (0, top))]
    out = [c[0]]
    for i in range(len(c) - 1):
        count, ka = SPANS[i]
        a, b = c[i], c[i + 1]
        seg = np.linalg.norm(b - a)
        ta = (b - (c[i - 1] if i else a)) * 0.5 * ka
        tb = ((c[i + 2] if i + 2 < len(c) else b) - a) * 0.5 * SPANS[i + 1][1]
        ta *= min(1, seg / (np.linalg.norm(ta) + 1e-6))
        tb *= min(1, seg / (np.linalg.norm(tb) + 1e-6))
        if i == 0:
            ta = np.array((ta[0], 0.0))
        if i == len(c) - 2:
            tb = np.array((seg * 0.8, 0.0))
        for k in range(1, count + 1):
            t = k / count
            out.append((2 * t ** 3 - 3 * t ** 2 + 1) * a + (t ** 3 - 2 * t ** 2 + t) * ta + (-2 * t ** 3 + 3 * t ** 2) * b + (t ** 3 - t ** 2) * tb)
    return out


def inside(ranges, y):
    return any(a <= y <= b for a, b in ranges)


def loft(name, spec, ms):
    """앞뒤 단면을 이어 매끈한 몸통 하나. 재질은 면 위치로 나눈다"""
    s = {k: curve(v) if k in ("floor", "top", "belt", "half", "rail") else v for k, v in spec.items()}
    y0, y1 = spec["top"][0][0], spec["top"][-1][0]
    n = spec.get("rings", 70)
    extra = sorted({v for r in spec.get("side", []) + spec.get("front_glass", []) + spec.get("rear_glass", []) for v in r})
    ys = sorted(set(round(y0 + (y1 - y0) * (0.65 * u + 0.35 * (math.sin(u * math.pi - math.pi / 2) + 1) / 2), 3) for u in np.linspace(0, 1, n)) | {round(v, 3) for v in extra if y0 < v < y1})
    cap = 8
    first, last = ring(s, y0), ring(s, y1)
    zf, zr = spec["cap_z"]
    grid = []
    for k in range(cap):
        q = math.pi / 2 * k / cap
        grid.append([(p[0] * math.sin(q), y0 - spec["bulge"] * math.cos(q), zf + (p[1] - zf) * math.sin(q)) for p in first])
    for y in ys:
        grid.append([(p[0], y, p[1]) for p in ring(s, y)])
    for k in range(cap - 1, -1, -1):
        q = math.pi / 2 * k / cap
        grid.append([(p[0] * math.sin(q), y1 + spec["bulge"] * math.cos(q), zr + (p[1] - zr) * math.sin(q)) for p in last])
    NS, NT = len(grid), len(grid[0])
    bm = bmesh.new()
    R = [[bm.verts.new(p) for p in row] for row in grid]
    L = [[R[i][j] if j in (0, NT - 1) else bm.verts.new((-grid[i][j][0], grid[i][j][1], grid[i][j][2])) for j in range(NT)] for i in range(NS)]
    lamp_z, tail_z = spec["lamps"]
    for vs, fl in ((R, False), (L, True)):
        for i in range(NS - 1):
            for j in range(NT - 1):
                q = [vs[i][j], vs[i + 1][j], vs[i + 1][j + 1], vs[i][j + 1]]
                try:
                    f = bm.faces.new(q[::-1] if fl else q)
                except ValueError:
                    continue
                c = [grid[a][b] for a, b in ((i, j), (i + 1, j), (i + 1, j + 1), (i, j + 1))]
                x, y, z = (sum(p[d] for p in c) / 4 for d in range(3))
                m = PAINT
                if BELT_I <= j < RAIL_I and inside(spec.get("side", []), y):
                    m = GLASS
                elif j >= RAIL_I and (inside(spec.get("front_glass", []), y) or inside(spec.get("rear_glass", []), y)):
                    m = GLASS
                elif z < s["floor"](y) + 0.1:
                    m = TRIM
                if i < cap + 4 and abs(z - lamp_z) < 0.055 and x > 0.3 * s["half"](y0):
                    m = LAMP
                if i >= NS - cap - 5 and abs(z - tail_z) < 0.06 and x > 0.3 * s["half"](y1):
                    m = TAIL
                f.material_index = m
                f.smooth = True
    bmesh.ops.remove_doubles(bm, verts=bm.verts, dist=1e-4)
    bmesh.ops.dissolve_degenerate(bm, dist=1e-5, edges=bm.edges)
    bmesh.ops.recalc_face_normals(bm, faces=bm.faces)
    me = bpy.data.meshes.new(name)
    bm.to_mesh(me)
    bm.free()
    for x in ms:
        me.materials.append(x)
    o = bpy.data.objects.new(name, me)
    bpy.context.scene.collection.objects.link(o)
    return o


def add_mesh(name, vv, ff, ms, slot=0, smooth=True):
    me = bpy.data.meshes.new(name)
    me.from_pydata(vv, [], ff)
    for x in ms:
        me.materials.append(x)
    for p in me.polygons:
        p.use_smooth = smooth
        p.material_index = slot
    o = bpy.data.objects.new(name, me)
    bpy.context.scene.collection.objects.link(o)
    return o


def cut_arches(body, wheels, r, half, ms):
    for wy in wheels:
        for side in (-1, 1):
            pts = []
            for k in range(33):
                a = math.radians(-12 + 204 * k / 32)
                pts.append((wy + (r + 0.05) * math.cos(a), r + (r + 0.05) * math.sin(a)))
            pts += [(wy + r + 0.07, -0.3), (wy - r - 0.07, -0.3)]
            m = len(pts)
            x0, x1 = side * (half - 0.42), side * (half + 0.6)
            vv = [(x0, *p) for p in pts] + [(x1, *p) for p in pts]
            ff = [tuple(range(m)), tuple(range(2 * m - 1, m - 1, -1))] + [(k, (k + 1) % m, m + (k + 1) % m, m + k) for k in range(m)]
            cut = add_mesh("cut", vv, ff, ms, TRIM, smooth=False)
            b2 = bmesh.new()
            b2.from_mesh(cut.data)
            bmesh.ops.recalc_face_normals(b2, faces=b2.faces)
            b2.to_mesh(cut.data)
            b2.free()
            mod = body.modifiers.new("arch", "BOOLEAN")
            mod.operation, mod.solver, mod.object = "DIFFERENCE", "EXACT", cut
            mod.use_self = mod.use_hole_tolerant = True
            mod.material_mode = "INDEX"
            bpy.context.view_layer.objects.active = body
            bpy.ops.object.modifier_apply(modifier=mod.name)
            bpy.data.objects.remove(cut)


def wheel(wy, side, r, width, xc, ms, seg=28):
    tire = [(r * 0.68, -width / 2), (r * 0.93, -width / 2), (r, -width * 0.3), (r, width * 0.3), (r * 0.93, width / 2), (r * 0.68, width / 2)]
    rim = [(r * 0.68, width / 2), (r * 0.64, width * 0.34), (r * 0.2, width * 0.38), (0, width * 0.42)]
    for prof, slot in ((tire, TIRE), (rim, RIM)):
        vv, ff = [], []
        n = len(prof)
        for k in range(seg):
            a = 2 * math.pi * k / seg
            for rr, d in prof:
                vv.append((side * (xc + d), wy + rr * math.cos(a), r + rr * math.sin(a)))
        for k in range(seg):
            for i in range(n - 1):
                q = (k * n + i, ((k + 1) % seg) * n + i, ((k + 1) % seg) * n + i + 1, k * n + i + 1)
                ff.append(q if side > 0 else q[::-1])
        add_mesh("wheel", vv, ff, ms, slot)


def finish(kind):
    objs = list(bpy.context.scene.objects)
    for o in objs:
        o.select_set(True)
    bpy.context.view_layer.objects.active = objs[0]
    bpy.ops.object.join()
    car = bpy.context.view_layer.objects.active
    car.name = kind
    bpy.ops.object.shade_smooth_by_angle(angle=math.radians(40))
    car.data.calc_loop_triangles()
    print("BUILT", kind, "TRIS", len(car.data.loop_triangles), "SIZE", [round(v, 2) for v in car.dimensions])
    bpy.ops.export_scene.gltf(filepath=os.path.join(OUT, kind + ".glb"), export_format="GLB", use_selection=False, export_apply=True, export_yup=True,
                              export_materials="EXPORT", export_normals=True, export_texcoords=False)


HATCH = dict(floor=[(-2.35, 0.3), (-2.1, 0.2), (2.0, 0.2), (2.35, 0.32)], crown=0.05, bulge=0.04, cap_z=(0.55, 0.65))
SPECS = {
    "sedan": dict(HATCH,
        top=[(-2.35, 0.70), (-2.15, 0.78), (-1.3, 0.96), (-1.05, 1.0), (-0.45, 1.38), (0.1, 1.44), (0.75, 1.40), (1.3, 1.08), (2.05, 1.02), (2.35, 0.90)],
        belt=[(-2.35, 0.66), (-1.1, 0.92), (1.3, 0.98), (2.35, 0.84)],
        half=[(-2.35, 0.66), (-2.2, 0.82), (-1.9, 0.89), (0, 0.91), (1.9, 0.89), (2.25, 0.8), (2.35, 0.66)],
        rail=[(-2.35, 0.5), (-1.1, 0.74), (-0.4, 0.66), (0.8, 0.64), (1.3, 0.68), (2.35, 0.5)],
        side=[(-0.78, 0.0), (0.09, 1.02)], front_glass=[(-1.0, -0.47)], rear_glass=[(0.82, 1.24)], lamps=(0.68, 0.84), wheels=(-1.42, 1.42), r=0.33),
    "suv": dict(HATCH,
        top=[(-2.35, 0.86), (-2.15, 0.98), (-1.35, 1.16), (-1.15, 1.2), (-0.55, 1.62), (0.1, 1.70), (1.3, 1.68), (1.75, 1.60), (2.2, 1.12), (2.35, 1.02)],
        belt=[(-2.35, 0.82), (-1.15, 1.12), (1.5, 1.2), (2.35, 0.98)],
        half=[(-2.35, 0.7), (-2.2, 0.86), (-1.9, 0.93), (0, 0.95), (1.9, 0.94), (2.25, 0.84), (2.35, 0.7)],
        rail=[(-2.35, 0.54), (-1.15, 0.78), (-0.5, 0.70), (1.4, 0.68), (2.35, 0.54)],
        side=[(-0.95, 0.12), (0.2, 1.05), (1.13, 1.62)], front_glass=[(-1.08, -0.6)], rear_glass=[(1.78, 2.14)], lamps=(0.84, 1.0), wheels=(-1.42, 1.42), r=0.37, floor2=None),
    "van": dict(floor=[(-2.5, 0.34), (-2.25, 0.24), (2.2, 0.24), (2.5, 0.34)], crown=0.07, bulge=0.05, cap_z=(0.7, 1.05),
        top=[(-2.5, 0.9), (-2.3, 1.1), (-1.85, 1.3), (-1.65, 1.36), (-1.0, 1.92), (-0.4, 2.0), (1.8, 2.0), (2.35, 1.92), (2.5, 1.6)],
        belt=[(-2.5, 0.86), (-1.65, 1.26), (2.35, 1.3), (2.5, 1.2)],
        half=[(-2.5, 0.72), (-2.35, 0.88), (-2.0, 0.95), (0, 0.97), (2.3, 0.96), (2.45, 0.86), (2.5, 0.72)],
        rail=[(-2.5, 0.6), (-1.65, 0.82), (-0.9, 0.8), (2.3, 0.8), (2.5, 0.68)],
        side=[(-1.4, -0.5), (-0.4, 0.75), (0.85, 2.05)], front_glass=[(-1.6, -1.02)], rear_glass=[(2.38, 2.48)], lamps=(0.9, 1.25), wheels=(-1.62, 1.6), r=0.36),
    "truck": dict(floor=[(-3.5, 0.5), (-3.3, 0.42), (-1.2, 0.42)], crown=0.07, bulge=0.05, cap_z=(1.0, 1.2),
        top=[(-3.5, 1.3), (-3.35, 1.8), (-3.0, 2.5), (-2.6, 2.62), (-1.2, 2.62)],
        belt=[(-3.5, 1.2), (-3.2, 1.55), (-1.2, 1.6)],
        half=[(-3.5, 0.9), (-3.4, 1.05), (-3.1, 1.13), (-1.2, 1.15)],
        rail=[(-3.5, 0.8), (-3.2, 0.98), (-2.8, 1.0), (-1.2, 1.0)],
        side=[(-3.05, -2.3)], front_glass=[(-3.32, -2.96)], lamps=(0.82, 0.8), wheels=(-2.4, 1.1, 2.4), r=0.5, box=[(-1.05, 3.5), 1.22, 0.8, 3.0]),
    "bus": dict(floor=[(-5.5, 0.42), (-5.2, 0.34), (5.2, 0.34), (5.5, 0.42)], crown=0.08, bulge=0.06, cap_z=(1.2, 1.7), rings=90,
        top=[(-5.5, 1.5), (-5.42, 2.4), (-5.1, 3.06), (-4.6, 3.16), (5.0, 3.16), (5.42, 3.06), (5.5, 2.6)],
        belt=[(-5.5, 1.1), (-5.2, 1.32), (5.2, 1.42), (5.5, 1.32)],
        half=[(-5.5, 1.08), (-5.35, 1.2), (-4.9, 1.24), (4.9, 1.24), (5.35, 1.2), (5.5, 1.08)],
        rail=[(-5.5, 1.0), (-5.1, 1.12), (5.1, 1.12), (5.5, 1.0)],
        side=[(-4.85, -3.2), (-3.1, -1.5), (-1.4, 0.2), (0.3, 2.0), (2.1, 3.8), (3.9, 5.1)], front_glass=[(-5.36, -5.08)], rear_glass=[(5.1, 5.42)], lamps=(0.95, 1.0), wheels=(-3.1, 3.2), r=0.52),
}

for kind, sp in SPECS.items():
    bpy.ops.wm.read_factory_settings(use_empty=True)
    ms = materials()
    fields = {k: v for k, v in sp.items() if k in ("floor", "crown", "bulge", "cap_z", "top", "belt", "half", "rail", "side", "front_glass", "rear_glass", "lamps", "rings")}
    body = loft("body", fields, ms)
    half = max(p[1] for p in sp["half"])
    r = sp["r"]
    if "box" in sp:  # 트럭 짐칸: 네모난 몸통
        (ya, yb), hw, zb, zt = sp["box"]
        bs = dict(floor=[(ya, zb), (yb, zb)], crown=0.03, bulge=0.02, cap_z=((zb + zt) / 2, (zb + zt) / 2), top=[(ya, zt), (yb, zt)], belt=[(ya, zt - 0.06), (yb, zt - 0.06)],
                  half=[(ya, hw - 0.02), (ya + 0.04, hw), (yb - 0.04, hw), (yb, hw - 0.02)], rail=[(ya, hw - 0.03), (yb, hw - 0.03)], lamps=(zb - 1, zb + 0.15), rings=16)
        loft("box", bs, ms)
        add_mesh("chassis", [(-hw * 0.8, -1.3, 0.5), (hw * 0.8, -1.3, 0.5), (hw * 0.8, 3.45, 0.5), (-hw * 0.8, 3.45, 0.5), (-hw * 0.8, -1.3, 0.85), (hw * 0.8, -1.3, 0.85), (hw * 0.8, 3.45, 0.85), (-hw * 0.8, 3.45, 0.85)],
                 [(0, 3, 2, 1), (4, 5, 6, 7), (0, 1, 5, 4), (2, 3, 7, 6), (1, 2, 6, 5), (3, 0, 4, 7)], ms, TRIM, smooth=False)
    cut_arches(body, [w for w in sp["wheels"] if w < -1.2 or "box" not in sp], r, half, ms)
    for wy in sp["wheels"]:
        for side in (-1, 1):
            wheel(wy, side, r, r * 0.68, half - r * 0.34 - 0.005, ms)
    finish(kind)
