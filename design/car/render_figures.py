# 사고 안내 화면용 3D 차 그림(투명 PNG, 바닥 접지 그림자 포함) + 위치 기준점(JSON)을 Blender로 굽는다.
# 실행: blender -b --python design/car/render_figures.py -- <ioniq5.glb> <출력 폴더(절대경로)> <샘플 수> <앞쪽 +1|-1>
#   CAR_VIEWS=front,top 환경 변수로 일부만 렌더. 결과: car_top/front/rear.png(알파 상자 기준 6% 여백으로 잘라냄), car_figures.json(잘라낸 그림 기준 0..1 좌표)
import bpy, sys, json, math, os
import numpy as np
from mathutils import Vector
from bpy_extras.object_utils import world_to_camera_view
glb, out, samples, fs = sys.argv[-4], sys.argv[-3], int(sys.argv[-2]), float(sys.argv[-1])
os.makedirs(out, exist_ok=True)
bpy.ops.wm.read_factory_settings(use_empty=True)
bpy.ops.import_scene.gltf(filepath=glb)
car = [o for o in bpy.context.scene.objects if o.type == 'MESH'][0]
sc = bpy.context.scene
sc.render.engine = 'CYCLES'; sc.cycles.samples = samples; sc.cycles.use_denoising = True
sc.render.film_transparent = True
sc.view_settings.view_transform = 'AgX'; sc.view_settings.look = 'AgX - Medium High Contrast'
w = bpy.data.worlds.new("w"); sc.world = w; w.use_nodes = True
bg = w.node_tree.nodes["Background"]; bg.inputs[1].default_value = 0.85
# 반사용 스튜디오 하늘: 위는 밝게, 수평선 아래는 어둡게 (차체에 띠 모양 반사가 생김)
nt = w.node_tree; tc = nt.nodes.new("ShaderNodeTexCoord"); sx = nt.nodes.new("ShaderNodeSeparateXYZ"); ramp = nt.nodes.new("ShaderNodeValToRGB")
nt.links.new(tc.outputs["Normal"], sx.inputs[0]); nt.links.new(sx.outputs["Z"], ramp.inputs[0]); nt.links.new(ramp.outputs[0], bg.inputs[0])
e = ramp.color_ramp.elements; e[0].position = 0.0; e[0].color = (0.35, 0.37, 0.4, 1); e[1].position = 0.12; e[1].color = (0.86, 0.88, 0.92, 1)
def light(name, loc, energy, size):
    d = bpy.data.lights.new(name, 'AREA'); d.energy = energy; d.size = size
    o = bpy.data.objects.new(name, d); sc.collection.objects.link(o); o.location = loc
    o.rotation_euler = (-Vector(loc)).to_track_quat('-Z', 'Y').to_euler()
light("top", (3.5, -2.5 * fs, 8), 420, 7)  # 정수리에서 살짝 비켜서: 위에서 볼 때 평평한 지붕 유리에 조명이 통째로 비치지 않게
light("key", (-6, 5 * fs, 4), 380, 5)
light("rim", (6, -5 * fs, 3.5), 200, 5)
bpy.ops.mesh.primitive_plane_add(size=60); bpy.context.object.is_shadow_catcher = True
cd = bpy.data.cameras.new("cam"); cam = bpy.data.objects.new("cam", cd); sc.collection.objects.link(cam); sc.camera = cam

xs = [(car.matrix_world @ Vector(c)) for c in car.bound_box]
W = max(v.x for v in xs); y0 = min(v.y for v in xs); y1 = max(v.y for v in xs); H = max(v.z for v in xs)
cy = (y0 + y1) / 2; L = (y1 - y0) / 2
F = Vector((0, fs, 0)); R = Vector((fs, 0, 0))  # 앞, 오른쪽 (위에서 보면 앞을 볼 때 오른쪽)
C = Vector((0, cy, 0))
def edge(deg, z):
    d = F * math.cos(math.radians(deg)) + R * math.sin(math.radians(deg))
    t = min(W / abs(d.x) if abs(d.x) > 1e-6 else 1e9, L / abs(d.y) if abs(d.y) > 1e-6 else 1e9)
    p = C + d * t; p.z = z; return p
dg = bpy.context.evaluated_depsgraph_get()
def surface_z(p):
    hit, loc, *_ = sc.ray_cast(dg, Vector((p.x, p.y, 5.0)), Vector((0, 0, -1)))
    return loc.z if hit else H
named = {f"impact_{a}": edge(a, 0.62) for a in range(0, 360, 45)}
named["trunk"] = C - F * (L - 0.25) + Vector((0, 0, H * 0.82))
named["center"] = C + Vector((0, 0, H))
hood = C + F * (L * 0.70); hood.z = surface_z(hood); named["hood"] = hood
roof = C + F * (L * 0.05); roof.z = surface_z(roof); named["roof"] = roof
# 바퀴 중심: 바닥에서 위로 쏴서 타이어 접지점을 찾고(차체 바닥보다 낮은 곳) 네 구석별 평균, 반지름은 앞뒤 가장자리로
contacts = []
for ix in np.linspace(-W, W, 60):
    for iy in np.linspace(y0, y1, 160):
        hit, loc, *_ = sc.ray_cast(dg, Vector((ix, iy, -1.0)), Vector((0, 0, 1)))
        if hit and loc.z < 0.03:
            contacts.append((ix, iy))
for name, sf, sr in (("fl_wheel", 1, -1), ("fr_wheel", 1, 1), ("rl_wheel", -1, -1), ("rr_wheel", -1, 1)):
    pts = [p for p in contacts if (Vector((p[0], p[1], 0)) - C).dot(F) * sf > 0 and (Vector((p[0], p[1], 0)) - C).dot(R) * sr > 0]
    if not pts: continue
    wx = sum(p[0] for p in pts) / len(pts); wy = sum(p[1] for p in pts) / len(pts)
    hit, loc, *_ = sc.ray_cast(dg, Vector((wx, wy + 1.5 * fs, 0.3)), Vector((0, -fs, 0)))
    r = abs(loc.y - wy) if hit else 0.37
    named[name] = Vector((wx, wy, r))

def shoot(name, res, loc=None, look=None, ortho=None, rot_z=0.0):
    sc.render.resolution_x, sc.render.resolution_y = res; sc.render.resolution_percentage = 100
    if ortho:
        cd.type = 'ORTHO'; cd.ortho_scale = ortho
        cam.location = (0, cy, 20); cam.rotation_euler = (0, 0, rot_z)
    else:
        cd.type = 'PERSP'; cd.lens = 50; cam.location = loc
        cam.rotation_euler = (Vector(look) - Vector(loc)).to_track_quat('-Z', 'Y').to_euler()
    bpy.context.view_layer.update()
    path = os.path.join(out, name + ".png")
    sc.render.filepath = path; bpy.ops.render.render(write_still=True)
    pts = {}
    for k, p in named.items():
        v = world_to_camera_view(sc, cam, p); pts[k] = [v.x, 1 - v.y]
    return finish(path, res, pts)

def box_blur(a, r):
    """r 픽셀 상자 흐림(분리형, 누적합)"""
    for axis in (0, 1):
        p = np.pad(a, [(r + 1, r) if ax == axis else (0, 0) for ax in (0, 1)], mode='edge')
        c = np.cumsum(p, axis=axis)
        a = (np.take(c, range(2 * r + 1, c.shape[axis]), axis=axis) - np.take(c, range(0, c.shape[axis] - 2 * r - 1), axis=axis)) / (2 * r + 1)
    return a

def finish(path, res, pts):
    """그림자를 차 가까이만 남기고(멀어질수록 알파 0), 알파 상자 + 6% 여백으로 잘라낸 뒤 기준점을 잘라낸 그림 좌표로 바꾼다"""
    img = bpy.data.images.load(path)
    wpx, hpx = img.size
    px = np.empty(wpx * hpx * 4, np.float32); img.pixels.foreach_get(px)
    px = px.reshape(hpx, wpx, 4)[::-1]  # 위가 0행이 되게
    a = px[..., 3]
    car_mask = (a > 0.97).astype(np.float32)
    ys_, xs_ = np.where(car_mask > 0)
    size = max(ys_.max() - ys_.min(), xs_.max() - xs_.min())
    r = max(int(size * 0.045), 4)
    near = np.clip(box_blur(box_blur(car_mask, r), r) * 2.2, 0, 1)  # 차에서 약 r 픽셀까지 1, 그 뒤 부드럽게 0
    a = np.where(car_mask > 0, a, a * near)
    px[..., 3] = a
    ys_, xs_ = np.where(a > 0.02)
    m = int(size * 0.06)
    x0, x1 = max(xs_.min() - m, 0), min(xs_.max() + m + 1, wpx); y0_, y1_ = max(ys_.min() - m, 0), min(ys_.max() + m + 1, hpx)
    crop = px[y0_:y1_, x0:x1]
    ch, cw = crop.shape[:2]
    outimg = bpy.data.images.new(os.path.basename(path), cw, ch, alpha=True)
    outimg.colorspace_settings.name = 'sRGB'; outimg.alpha_mode = 'STRAIGHT'
    outimg.pixels.foreach_set(np.ascontiguousarray(crop[::-1]).ravel())
    outimg.filepath_raw = path; outimg.file_format = 'PNG'; outimg.save()
    bpy.data.images.remove(img)
    for k, (u, v) in pts.items():
        pts[k] = [round((u * wpx - x0) / cw, 4), round((v * hpx - y0_) / ch, 4)]
    return {"w": cw, "h": ch, "points": pts}

meta = {}
# 위에서 본 모습: 앞이 위쪽 (차량 제어 화면과 같은 방향)
views = os.environ.get("CAR_VIEWS", "top,front,rear").split(",")
if "top" in views: meta["top"] = shoot("car_top", (1000, 1500), ortho=6.4, rot_z=0 if fs > 0 else math.pi)
# 앞 왼쪽 위에서 본 3/4 (왼쪽 주행 화면과 비슷한 각도)
if "front" in views: meta["front"] = shoot("car_front", (1600, 1100), loc=C + F * 6.2 - R * 6.4 + Vector((0, 0, 4.6)), look=C + Vector((0, 0, 0.55)))
# 뒤 오른쪽 위에서 본 3/4 (트렁크)
if "rear" in views: meta["rear"] = shoot("car_rear", (1600, 1100), loc=C - F * 6.4 + R * 6.0 + Vector((0, 0, 4.4)), look=C + Vector((0, 0, 0.6)))
if len(views) == 3: json.dump(meta, open(os.path.join(out, "car_figures.json"), "w"), indent=1)
print("DONE", W, y0, y1, H)
