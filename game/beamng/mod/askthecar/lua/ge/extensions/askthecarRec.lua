-- 시연용 다각도 영상: 현대차 내장 카메라 자리에서 본 화면을 창 하나(atc_cameras)에 타일로 띄운다. 녹화는 OBS가 그 창을 잡는다.
-- 위치 근거: docs/research_notes/현대차 카메라 위치/camera_positions.md (부위는 공식 설명서, 숫자는 대부분 추정)
-- /askthecar/rec_on 파일이 있을 때만 켠다(없으면 그래픽 부담 없음).
local M = {}
local im = ui_imgui
local imUtils = require('ui/imguiUtils')
local ON = '/askthecar/rec_on'
local STATS = '/askthecar/rec_stats.json'
local W, H = 480, 270
local DRAWS = 3
local PER_FRAME = 2 -- 한 프레임에 새로 그리는 시점 수. 나머지는 지난 그림을 유지(게임 프레임 지키기)
local turn = 0
local COLUMNS = 3
-- 차례가 곧 타일 배치(왼쪽 위부터). 앱의 영상 보기와 같아야 한다.
-- at: 앞뒤(앞 끝 0 ~ 뒤 끝 1), side: 좌우(왼쪽 -1 ~ 오른쪽 1), up: 바닥에서 높이 비율, look: 수평 방향(앞 0, 오른쪽 90), tilt: 아래로 숙임, fov: 수평 화각
-- shortcut: SVM 실제 화각 약 180도는 일반 시점으로 못 그려 150도로 그린다, 어안 왜곡을 넣으면 고칠 수 있음
local CAMS = {
  { 'dash_front', at = 0.30, side = 0, up = 0.85, look = 0, tilt = 5, fov = 130 },
  { 'svm_front', at = -0.015, side = 0, up = 0.35, look = 0, tilt = 25, fov = 150 },
  { 'dash_rear', at = 0.82, side = 0, up = 0.80, look = 180, tilt = 10, fov = 130 },
  { 'svm_left', at = 0.28, side = -1, up = 0.45, look = -90, tilt = 65, fov = 150 },
  { 'svm_rear', at = 1.015, side = 0, up = 0.45, look = 180, tilt = 25, fov = 150 },
  { 'svm_right', at = 0.28, side = 1, up = 0.45, look = 90, tilt = 65, fov = 150 },
}
local views, failed = nil, false
local centerOffset = nil
local checkT, frames, sum, worst, statT = 0, 0, 0, 0, 0
local windowOpen = im.BoolPtr(true)

local blurOff = false
local function setBlur(off) -- 번갈아 그리면 시점이 많이 움직인 것으로 보여 모션 블러가 그림을 뭉갠다
  local fx = scenetree.findObject('PostFxMotionBlur')
  if not fx then return end
  if off and fx:isEnabled() then fx:disable(); blurOff = true
  elseif not off and blurOff then fx:enable(); blurOff = false end
end

local function setup()
  setBlur(true)
  views = {}
  for i, c in ipairs(CAMS) do
    local name = 'atc_rec_' .. c[1]
    local v = RenderViewManagerInstance:getOrCreateView(name)
    v.luaOwned = true
    v.renderCubemap = false
    v.resolution = Point2I(W, H)
    v.viewPort = RectI(0, 0, W, H)
    v.namedTexTargetColor = name
    v.usePostEffects = true -- 끄면 HDR 톤 조정이 빠져 하얗게 날아감
    local fovY = 2 * math.atan(math.tan(math.rad(c.fov) / 2) * H / W) -- Frustum 은 세로 화각을 받는다(미확인, 화면으로 대조)
    v.frustum = Frustum.construct(false, fovY, W / H, 0.05, 200)
    v.fov = math.deg(fovY)
    v.renderEditorIcons = false
    -- 게임의 화면 분할(render/splitScreen.lua)과 같은 방법: 시점마다 거울·반사를 또 그리면 너무 무겁다
    local q = RenderSettings.getGlobals()
    q.meshDetailAdjust = q.meshDetailAdjust * 0.4
    q.groundCoverScale = 0
    q.vehicleMirrorsEnabled = false
    q.vehicleDynamicReflectionsEnabled = false
    v.settingsOverride = q
    v.settingsOverrideEnabled = true
    v.renderShadows = false
    views[i] = { view = v, tex = '#' .. name, cam = c }
  end
end

local function teardown()
  if not views then return end
  for _, e in ipairs(views) do RenderViewManagerInstance:destroyView(e.view) end
  views = nil
  setBlur(false)
end

local function place()
  local v = be:getPlayerVehicle(0)
  if not v then return end
  local d, u = v:getDirectionVector():normalized(), v:getDirectionVectorUp():normalized()
  local r = d:cross(u):normalized()
  -- 차 중심은 멀쩡할 때 한 번만 잰다. 찌그러지면 외곽 상자 중심이 밀려 카메라가 차 안·엔진룸으로 들어간다
  if not centerOffset then
    local cx, cy, cz = be:getObjectOOBBCenterXYZ(v:getID())
    local off = vec3(cx, cy, cz) - v:getPosition()
    centerOffset = { off:dot(d), off:dot(r), off:dot(u) }
  end
  local center = v:getPosition() + d * centerOffset[1] + r * centerOffset[2] + u * centerOffset[3]
  local len, wid, hgt = v:getInitialLength(), v:getInitialWidth(), v:getInitialHeight()
  local exposure = scenetree.findObject('PostEffectLocalExposureObject')
  local main = RenderViewManagerInstance:getView('main')
  for i, e in ipairs(views) do
    e.view.active = (i - turn - 1) % #views < PER_FRAME
    local c = e.cam
    local pos = center + d * (len * (0.5 - c.at)) + r * (wid * 0.5 * c.side) + u * (hgt * (c.up - 0.5))
    local yaw, tilt = math.rad(c.look), math.rad(c.tilt)
    local flat = d * math.cos(yaw) + r * math.sin(yaw)
    local dir = flat * math.cos(tilt) - u * math.sin(tilt)
    local rot = quatFromDir(dir, u)
    local mat = QuatF(rot.x, rot.y, rot.z, rot.w):getMatrix()
    mat:setPosition(pos)
    e.view.cameraMatrix = mat
    if exposure and exposure.autoExposure == true then -- 메인 화면 밝기를 그대로. 새 뷰는 밝기 적응 전이라 하얗게 날아간다
      e.view.useManualEV = false
      if main and main.currentExposure and main.currentExposure > 0 then e.view.currentExposure = main.currentExposure end
    elseif exposure then
      e.view.useManualEV = true
      e.view.manualEV = exposure.manualEV
    end
  end
end

local function draw()
  local rows = math.ceil(#CAMS / COLUMNS)
  im.SetNextWindowPos(im.ImVec2(-3000, 100), im.Cond_Always)
  im.SetNextWindowSize(im.ImVec2(W * COLUMNS, H * rows), im.Cond_Always)
  im.PushStyleVar2(im.StyleVar_WindowPadding, im.ImVec2(0, 0))
  im.PushStyleVar2(im.StyleVar_ItemSpacing, im.ImVec2(0, 0))
  local flags = bit.bor(im.WindowFlags_NoTitleBar, im.WindowFlags_NoResize, im.WindowFlags_NoSavedSettings, im.WindowFlags_NoScrollbar)
  im.Begin('atc_cameras', windowOpen, flags)
  for i, e in ipairs(views) do
    local col, row = (i - 1) % COLUMNS, math.floor((i - 1) / COLUMNS)
    local id = imUtils.texObj(e.tex).tex:getID()
    for _ = 1, DRAWS do -- 게임 그림의 알파 값이 고르지 않아 한 번만 그리면 얼룩진다. 겹쳐 그려 불투명하게 만든다
      im.SetCursorPos(im.ImVec2(col * W, row * H))
      im.Image(id, im.ImVec2(W, H))
    end
  end
  im.End()
  im.PopStyleVar(2)
end

function M.onVehicleResetted(vid)
  local v = be:getPlayerVehicle(0)
  if v and v:getID() == vid then centerOffset = nil end
end

function M.onVehicleSwitched()
  centerOffset = nil
end

function M.onUpdate(dtReal)
  if failed then return end
  checkT = checkT + dtReal
  if checkT > 1 then
    checkT = 0
    local want = FS:fileExists(ON)
    if want and not views then
      setup()
    elseif not want and views then teardown() end
  end
  frames, sum, statT = frames + 1, sum + dtReal, statT + dtReal
  worst = math.max(worst, dtReal)
  if statT > 3 then
    jsonWriteFile(STATS, { fps = frames / sum, worstMs = worst * 1000, views = views ~= nil, t = os.time() })
    frames, sum, worst, statT = 0, 0, 0, 0
  end
  if not views then return end
  turn = turn + PER_FRAME
  local ok, err = pcall(function() place(); draw() end)
  if not ok then
    failed = true
    jsonWriteFile(STATS, { error = tostring(err) })
    teardown()
    return
  end
end

return M
