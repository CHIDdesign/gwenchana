-- 차에 달린 카메라 시점 사진(게임 시연 전용). 메인 화면·UI·조작은 건드리지 않고 화면 밖에서 찍는다.
-- 브리지가 /askthecar/cam_request 파일을 만들면 앞 사진을 찍어 /askthecar/cam/ 에 저장한다.
-- 게임의 render_renderViews.takeScreenshot 은 찍는 동안 UI 를 끄고 안티에일리어싱 설정도 바꿔서(여러 장이면 꺼진 채 남음) 쓰지 않는다.
local M = {}
local REQUEST = '/askthecar/cam_request'
local DIR = '/askthecar/cam/'
local W, H, FOV = 960, 540, 100
local FRAMES_TO_RENDER = 5 -- 뷰를 만들고 그림이 나올 때까지 기다리는 프레임
local waited = 0
local queue, current = {}, nil
local blurOff = false -- 찍는 동안만 끈 모션 블러(설정값은 안 바꿈)

local function setBlur(off)
  local fx = scenetree.findObject('PostFxMotionBlur')
  if not fx then return end
  if off and fx:isEnabled() then fx:disable(); blurOff = true
  elseif not off and blurOff then fx:enable(); blurOff = false end
end

local function place()
  local v = be:getPlayerVehicle(0)
  if not v then return {} end
  local p = v:getPosition()
  local d = v:getDirectionVector():normalized()
  local up = vec3(0, 0, 1)
  local len, hgt = v:getInitialLength() / 2, v:getInitialHeight()
  -- 앱이 쓰는 앞 사진만 찍는다: 저장 한 번마다 게임이 약 0.4초 멈춘다(실측, 4장이면 2초 동안 4번 멈춤)
  return {
    { 'front', p + d * (len * 0.25) + up * (hgt * 0.8), d }, -- 앞유리 안쪽(블랙박스 자리). 범퍼 앞이면 붙은 차의 일부만 찍혀 인식이 안 됨
  }
end

local function begin(cam)
  local name = 'atc_' .. cam[1]
  local view = RenderViewManagerInstance:getOrCreateView(name)
  view.luaOwned = true
  local rot = quatFromDir(cam[3], vec3(0, 0, 1))
  local mat = QuatF(rot.x, rot.y, rot.z, rot.w):getMatrix()
  mat:setPosition(cam[2])
  view.renderCubemap = false
  view.cameraMatrix = mat
  view.resolution = Point2I(W, H)
  view.viewPort = RectI(0, 0, W, H)
  view.namedTexTargetColor = name
  view.usePostEffects = true -- 끄면 HDR 톤 조정이 빠져 하얗게 날아감
  view.frustum = Frustum.construct(false, math.rad(FOV), W / H, 0.1, 2000)
  view.fov = FOV
  view.renderEditorIcons = false
  -- 메인 화면의 밝기를 그대로 쓴다. 새 뷰는 밝기 적응 전이라 하얗게 날아간다
  local exposure = scenetree.findObject('PostEffectLocalExposureObject')
  local main = RenderViewManagerInstance:getView('main')
  if exposure and exposure.autoExposure == true then
    view.useManualEV = false
    if main and main.currentExposure and main.currentExposure > 0 then view.currentExposure = main.currentExposure end
  elseif exposure then
    view.useManualEV = true
    view.manualEV = exposure.manualEV
  end
  current = { view = view, file = DIR .. cam[1] .. '.png', frames = 0 }
end

-- R 키 등으로 차가 리셋되면 브리지에 알린다(시연 때 앱·에뮬레이터도 처음으로)
function M.onVehicleResetted(vid)
  local v = be:getPlayerVehicle(0)
  if v and v:getID() == vid then
    jsonWriteFile('/askthecar/reset', { t = os.time() })
  end
end

function M.onUpdate(dt)
  if current then
    current.frames = current.frames + 1
    if current.frames >= FRAMES_TO_RENDER then
      current.view:saveToDisk(current.file)
      RenderViewManagerInstance:destroyView(current.view)
      current = nil
    end
    return
  end
  if #queue > 0 then
    begin(table.remove(queue, 1))
    return
  end
  setBlur(false)
  waited = waited + dt
  if waited < 0.2 then return end
  waited = 0
  if FS:fileExists(REQUEST) then
    FS:removeFile(REQUEST)
    queue = place()
    setBlur(true) -- 잔상이 있으면 차량 인식이 안 됨
  end
end

return M
