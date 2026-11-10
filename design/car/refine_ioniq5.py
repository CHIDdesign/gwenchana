# 원래 차 모델(ioniq5.glb)의 모양은 그대로 두고 재질만 다듬어 다시 내보낸다.
# 실행: blender -b --python design/car/refine_ioniq5.py -- <원본 glb 절대경로> <출력 glb 절대경로> <법선 세기 0~1>
# 법선 세기: 구운 법선 그림이 판에 물결을 만들어서 약하게(0이면 끔). 모양(정점)은 건드리지 않는다
import bpy, sys
src, dst, nstr = sys.argv[-3], sys.argv[-2], float(sys.argv[-1])
bpy.ops.wm.read_factory_settings(use_empty=True)
bpy.ops.import_scene.gltf(filepath=src)
car = [o for o in bpy.context.scene.objects if o.type == 'MESH'][0]
for m in car.data.materials:
    nt = m.node_tree; b = nt.nodes.get("Principled BSDF")
    if m.name == "body":  # 흰 펄 도장: 맑은 코팅 + 낮은 거칠기
        nm = next((n for n in nt.nodes if n.type == 'NORMAL_MAP'), None)
        if nm is not None:
            if nstr <= 0:
                for l in list(b.inputs["Normal"].links): nt.links.remove(l)
            else:
                nm.inputs["Strength"].default_value = nstr
        b.inputs["Roughness"].default_value = 0.3
        b.inputs["Coat Weight"].default_value = 1.0; b.inputs["Coat Roughness"].default_value = 0.04
    elif m.name == "part_2":  # 유리: 거의 검은 짙은 색, 매끈
        b.inputs["Base Color"].default_value = (0.01, 0.012, 0.016, 1); b.inputs["Roughness"].default_value = 0.12  # 코팅 없음: 밝은 하늘 반사로 회색이 되지 않게
    elif m.name == "tire":
        b.inputs["Base Color"].default_value = (0.02, 0.02, 0.022, 1); b.inputs["Roughness"].default_value = 0.85
    elif m.name == "rim_blade":  # 휠 날개: 밝은 금속
        b.inputs["Base Color"].default_value = (0.42, 0.44, 0.47, 1); b.inputs["Metallic"].default_value = 0.9; b.inputs["Roughness"].default_value = 0.22
    elif m.name == "rim_base":
        b.inputs["Roughness"].default_value = 0.4
bpy.ops.export_scene.gltf(filepath=dst, export_format='GLB', use_selection=False)
print("REFINED", dst)
