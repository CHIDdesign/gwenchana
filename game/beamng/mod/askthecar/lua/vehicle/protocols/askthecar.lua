-- Hey, Why not ask the Car? - BeamNG UDP protocol (modelled on the game's protocols/outgauge.lua)
-- Loaded by lua/vehicle/protocols.lua when Options > Other > protocols "others" is enabled.
-- Sends to game/bridge/beamng_bridge.py on this PC. Ctrl+R reloads after edits.
local M = {}

-- current road from the game's road graph (same data the AI uses: mapmgr.mapData.graph[n1][n2])
local road = {limit = 0, lanes = 0, oneWay = false}
local roadTimer = 0
local mapReqTimer, mapReqLeft = 0, 6
local function updateRoad(dt)
  roadTimer = roadTimer - dt
  if roadTimer > 0 then return end
  roadTimer = 0.5
  road.limit, road.lanes, road.oneWay = 0, 0, false
  if mapmgr and not mapmgr.mapData and mapReqLeft > 0 then
    -- the vehicle only receives the road graph after asking (the game AI does this in ai.lua); ask every 5 s, give up
    -- after 30 s (maps without roads would otherwise ask forever)
    mapReqTimer = mapReqTimer - 0.5
    if mapReqTimer <= 0 then mapReqTimer = 5; mapReqLeft = mapReqLeft - 1; mapmgr.requestMap() end
  end
  if not (mapmgr and mapmgr.mapData and mapmgr.mapData.graph) then return end
  local ok, n1, n2, dist = pcall(mapmgr.findClosestRoad)
  if not ok or not n1 or not n2 or (dist or 99) > 15 then return end
  local edge = mapmgr.mapData.graph[n1] and mapmgr.mapData.graph[n1][n2]
  if not edge then return end
  road.limit = (edge.speedLimit and edge.speedLimit < 1e6) and edge.speedLimit or 0
  road.lanes = type(edge.lanes) == "string" and #edge.lanes or 0
  road.oneWay = edge.oneWay and true or false
end

-- road network for the app's navigation screen: written once per map to <user folder>/askthecar/roads.json
-- (same graph the minimap and AI use). nodes = {x, y, radius}, edges = {a, b, speedLimit m/s, lanes, oneWay} (1-based)
local exportedFor
local function r1(v) return math.floor(v * 10 + 0.5) / 10 end
local function exportRoads()
  local d = mapmgr and mapmgr.mapData
  if not (d and d.positions and d.graph) or d == exportedFor then return end
  exportedFor = d
  local idx, nodes, edges = {}, {}, {}
  for id, p in pairs(d.positions) do
    nodes[#nodes + 1] = {r1(p.x), r1(p.y), r1(d.radius and d.radius[id] or 2)}
    idx[id] = #nodes
  end
  for a, nb in pairs(d.graph) do
    for b, e in pairs(nb) do
      local ia, ib = idx[a], idx[b]
      if ia and ib and ia < ib then
        edges[#edges + 1] = {ia, ib, r1((e.speedLimit and e.speedLimit < 1e6) and e.speedLimit or 0),
                             type(e.lanes) == "string" and #e.lanes or 0, e.oneWay and 1 or 0}
      end
    end
  end
  jsonWriteFile("askthecar/roads.json", {nodes = nodes, edges = edges})
end

-- nearest other vehicle within 30 m, in this car's frame (simulator ground truth only — a real car can't know this).
-- mapmgr.getObjects() = the same per-vehicle table the game AI uses (ge/map.lua: id, pos, vel, dirVec, ...)
local other = {valid = 0, fwd = 0, right = 0, yaw = 0}
-- up to 8 vehicles within 60 m, nearest first: {d, fwd, right, yaw} (drawn in the app's crash 3D view)
local OBJ_N, OBJ_MAX = 8, 60
local near = {}
local function updateOther()
  other.valid = 0
  near = {}
  local objs = mapmgr and mapmgr.getObjects and mapmgr.getObjects()
  if not objs then return end
  local myId, p, f = obj:getId(), obj:getPosition(), obj:getDirectionVector()
  local n = math.sqrt(f.x * f.x + f.y * f.y)
  if n < 1e-6 then return end
  local fx, fy = f.x / n, f.y / n -- forward on the ground plane; right = (fy, -fx) with z up
  local best = 30
  for id, v in pairs(objs) do
    if id ~= myId and v.pos and v.dirVec then
      local dx, dy = v.pos.x - p.x, v.pos.y - p.y
      local d = math.sqrt(dx * dx + dy * dy)
      local ox, oy = v.dirVec.x, v.dirVec.y
      local fwd, right = dx * fx + dy * fy, dx * fy - dy * fx
      local yaw = math.deg(math.atan2(ox * fy - oy * fx, ox * fx + oy * fy)) -- its heading, clockwise from ours
      if d < best then
        best = d
        other.valid, other.fwd, other.right, other.yaw = 1, fwd, right, yaw
      end
      if d < OBJ_MAX then near[#near + 1] = {d, v.pos.x, v.pos.y, math.deg(math.atan2(ox, oy)) % 360} end -- world x, y, heading
    end
  end
end

-- static ray scan around the car (walls, guardrails, buildings, poles), 24 directions every 15 deg, 0.6 m above the
-- reference node, 30 m max, every 0.5 s. obj:castRayStatic is what the game AI uses (ai.lua) and ignores vehicles.
-- Simulator ground truth only: a real car app cannot see this.
local SCAN_N, SCAN_MAX = 24, 30
local scan = {}
for i = 1, SCAN_N do scan[i] = SCAN_MAX end
local scanTimer = 0
local function updateScan(dt)
  scanTimer = scanTimer - dt
  if scanTimer > 0 then return end
  scanTimer = 0.5
  local p, f = obj:getPosition(), obj:getDirectionVector()
  local n = math.sqrt(f.x * f.x + f.y * f.y)
  if n < 1e-6 then return end
  local fx, fy = f.x / n, f.y / n
  local origin = vec3(p.x, p.y, p.z + 0.6)
  for i = 1, SCAN_N do
    local a = math.rad((i - 1) * 360 / SCAN_N) -- clockwise from forward
    local c, sn = math.cos(a), math.sin(a)
    scan[i] = obj:castRayStatic(origin, vec3(fx * c + fy * sn, fy * c - fx * sn, 0), SCAN_MAX)
  end
end

-- body zone damage 0..1 (beamstate.getBodyZoneDamage: fraction of broken/deformed beams per zone).
-- Stands in for a real car's crash sensors (airbag control unit front/side sensors) — ground truth only.
local ZONES = {"FL", "FR", "ML", "MR", "RL", "RR"}
local zoneDmg, zoneTimer = {0, 0, 0, 0, 0, 0}, 0
local function updateZones(dt)
  zoneTimer = zoneTimer - dt
  if zoneTimer > 0 then return end
  zoneTimer = 0.25
  local z = beamstate.getBodyZoneDamage and beamstate.getBodyZoneDamage()
  if not z then return end
  for i, k in ipairs(ZONES) do zoneDmg[i] = z[k] or 0 end
end

-- openings (doors, hood, trunk): the car's latch controllers (advancedCouplerControl: "attached" = closed).
-- name -> {flag bit, controller name patterns}. A hood can have two latches (latch + catch): open = any not attached.
local OPENINGS = {
  {"trunk", 64, {"trunk", "tailgate"}}, {"door_fl", 128, {"door_fl"}}, {"door_fr", 256, {"door_fr"}},
  {"door_rl", 512, {"door_rl"}}, {"door_rr", 1024, {"door_rr"}}, {"hood", 2048, {"hood"}},
}
local openCtrls -- name -> list of controllers
local openRetry = 0
local function openings()
  if not openCtrls or (next(openCtrls) == nil and os.clock() > openRetry) then
    -- controllers may not be loaded on the first call: an empty result is retried every 5 s instead of kept until reset
    openRetry = os.clock() + 5
    openCtrls = {}
    for _, c in pairs(controller.getAllControllers() or {}) do
      local n = (c.name or ""):lower()
      if c.getGroupState then
        for _, o in ipairs(OPENINGS) do
          for _, pat in ipairs(o[3]) do
            if n:find(pat, 1, true) then openCtrls[o[1]] = openCtrls[o[1]] or {}; table.insert(openCtrls[o[1]], c) end
          end
        end
      end
    end
  end
  return openCtrls
end

-- commands from the bridge on 127.0.0.1:4446 ("<name> 1" open / "<name> 0" close, e.g. "trunk 1", "door_fl 0", "hood 1"):
-- the app/emulator can open and close the game car's doors, hood and trunk too
local cmdSock
-- a close is a push toward the latch: a wide-open door may need a few pushes → retry every 0.5 s for 3 s until it latches
local pending = {} -- name -> {want "1"/"0", time left, time to next try}
local function applyPending(dt)
  for name, p in pairs(pending) do
    p[2], p[3] = p[2] - dt, p[3] - dt
    local done = true
    for _, c in ipairs(openings()[name] or {}) do
      local closed = c.getGroupState() == "attached"
      if (p[1] == "0") ~= closed then -- not yet where we want it
        done = false
        if p[3] <= 0 then if p[1] == "1" then c.detachGroup() else c.tryAttachGroupImpulse() end end
      end
    end
    if p[3] <= 0 then p[3] = 0.5 end
    if done or p[2] <= 0 then pending[name] = nil end
  end
end
local function pollCommands(dt)
  if not cmdSock and socket then
    local s = socket.udp()
    if s and s:setsockname("127.0.0.1", 4446) then s:settimeout(0); cmdSock = s elseif s then s:close() end
  end
  if not cmdSock then return end
  local msg = cmdSock:receive()
  while msg do
    local name, v = msg:match("^(%S+) ([01])$")
    if name and openings()[name] then pending[name] = {v, 3, 0} end
    msg = cmdSock:receive()
  end
  applyPending(dt or 0.05)
end

local function init() end
local function reset() roadTimer = 0; mapReqLeft = 6; openCtrls = nil end
local function getAddress()        return "127.0.0.1" end
local function getPort()           return 4445 end
local function getMaxUpdateRate()  return 20 end
local function isPhysicsStepUsed() return false end

-- keep in sync with STRUCT in game/bridge/beamng_bridge.py
local function getStructDefinition()
  return [[
    char  format[4];      // "ATC1"
    float speed;          // m/s (airspeed = 차가 실제로 움직이는 속도)
    float steeringWheel;  // steering wheel angle, degrees (electrics.values.steering)
    float steeringInput;  // -1..1
    float brake;          // 0..1
    float throttle;       // 0..1
    float parkingbrake;   // 0..1
    char  gear[4];        // electrics.values.gear, e.g. "P" "R" "N" "D" "1"
    unsigned flags;       // bit0 signal L, bit1 signal R, bit2 hazard, bit3 engine running, bit4-5 ignitionLevel 0..3, bit6 trunk, bit7-10 doors FL FR RL RR, bit11 hood open
    float posX, posY, posZ;
    float yaw;            // heading, degrees clockwise from +y (north), from obj:getDirectionVector()
    float accX, accY, accZ; // smoothed, vehicle frame, gravity excluded (m/s^2)
    float damage;         // beamstate.damage (ground truth only, never sent to the app)
    float roadSpeedLimit; // m/s of the closest road, 0 = unknown (stands in for Pleos NaviHelper onCurrentRoadSpeedLimit)
    float roadLanes;      // total lanes of the closest road, 0 = unknown
    unsigned roadFlags;   // bit0 one-way
    float otherFwd, otherRight; // nearest other vehicle centre in this car's frame, metres (ground truth only)
    float otherYaw;       // its heading relative to ours, degrees, clockwise
    unsigned otherValid;  // 1 = a vehicle within 30 m
    float scan[24];       // distance (m) to static geometry every 15 deg clockwise from forward, 30 = nothing (ground truth only)
    unsigned objCount;    // vehicles within 60 m (max 8, nearest first)
    float obj[24];        // objCount x (x, y, heading) in world coords like posX/posY/yaw (ground truth only)
    float zones[6];       // body zone damage 0..1: FL FR ML MR RL RR (ground truth "crash sensors")
  ]]
end

local function fillStruct(o, dtSim)
  local ev = electrics.values
  if not ev.watertemp and not ev.wheelspeed then return end -- vehicle not initialised yet
  o.format = "ATC1"
  o.speed = ev.airspeed or ev.wheelspeed or 0 -- 실제로 움직이는 속도 (뒤집혀 바퀴만 헛돌 때 속도가 나오지 않게)
  if o.speed < 0.4 then o.speed = 0 end -- 실차 속도계처럼 아주 느린 움직임(D단 미끄러짐·떨림, 약 1.4km/h 미만)은 0
  o.steeringWheel = ev.steering or 0
  o.steeringInput = ev.steering_input or 0
  o.brake = ev.brake or 0
  o.throttle = ev.throttle or 0
  o.parkingbrake = ev.parkingbrake or 0
  o.gear = tostring(ev.gear or "")
  local flags = 0
  -- switch positions, not the blinking lamps (signal_L/R/hazard flash on/off and looked like the signal toggling)
  if (ev.signal_left_input or 0) == 1 then flags = bit.bor(flags, 1) end
  if (ev.signal_right_input or 0) == 1 then flags = bit.bor(flags, 2) end
  if ev.hazard_enabled == true or ev.hazard_enabled == 1 then flags = bit.bor(flags, 4) end
  if (ev.engineRunning or 0) ~= 0 then flags = bit.bor(flags, 8) end
  flags = bit.bor(flags, bit.lshift(math.min(3, math.floor(ev.ignitionLevel or 0)), 4)) -- 0 꺼짐 1 ACC 2 켜짐 3 시동 중
  for _, o in ipairs(OPENINGS) do
    for _, c in ipairs(openings()[o[1]] or {}) do
      if c.getGroupState() ~= "attached" then flags = bit.bor(flags, o[2]); break end
    end
  end
  o.flags = flags
  pollCommands(dtSim)
  -- same instant and same source as the other vehicles (protocols.yawPosSmoothed lagged and pointed ~180 deg off)
  local mp, mf = obj:getPosition(), obj:getDirectionVector()
  o.posX, o.posY, o.posZ = mp.x, mp.y, mp.z
  o.yaw = math.deg(math.atan2(mf.x, mf.y)) % 360
  o.accX, o.accY, o.accZ = protocols.accXSmoothed or 0, protocols.accYSmoothed or 0, protocols.accZSmoothed or 0
  o.damage = beamstate.damage or 0
  updateRoad(dtSim)
  exportRoads()
  o.roadSpeedLimit = road.limit
  o.roadLanes = road.lanes
  o.roadFlags = road.oneWay and 1 or 0
  updateOther()
  o.otherFwd, o.otherRight, o.otherYaw, o.otherValid = other.fwd, other.right, other.yaw, other.valid
  updateScan(dtSim)
  for i = 1, SCAN_N do o.scan[i - 1] = scan[i] end
  updateZones(dtSim)
  for i = 1, 6 do o.zones[i - 1] = zoneDmg[i] end
  table.sort(near, function(a, b) return a[1] < b[1] end)
  o.objCount = math.min(#near, OBJ_N)
  for i = 1, OBJ_N do
    local v = near[i] or {0, 0, 0, 0}
    o.obj[(i - 1) * 3], o.obj[(i - 1) * 3 + 1], o.obj[(i - 1) * 3 + 2] = v[2], v[3], v[4]
  end
end

M.init = init
M.reset = reset
M.getAddress = getAddress
M.getPort = getPort
M.getMaxUpdateRate = getMaxUpdateRate
M.getStructDefinition = getStructDefinition
M.fillStruct = fillStruct
M.isPhysicsStepUsed = isPhysicsStepUsed

return M
