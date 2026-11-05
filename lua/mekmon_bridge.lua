--[[
mekmon_bridge.lua - BridgeCore native pixel dashboard for Mekanism peripherals.
Run: mekmon_bridge [bridgecore_display-name] | mekmon_bridge debug
Select CC mode in the controller GUI first. No Tom's GPU, image/font download,
monitor API aliases, or Lua resolution changes are required. Original scripts remain unchanged.
Mek reads/conversion are reused from mekmon.lua; discovery from mekmon_gpu.lua.
One device is sampled per 0.5s tick after calls finish (not every device each 0.5s).
Static capacities cache for 300s, network discovery every 10s and attach/detach.
Page arrows switch devices; DETAIL cycles all fields on small screens. Q exits.
Injection +/-2 (sneak +/-10), dump button advances (sneak reverses) existing Mek settings.
Only click these controls if that is intended; otherwise use as a passive dashboard.
]]
local REFRESH=0.5
local J_PER_FE=2.5
local SHOW_DT_BAR=true
local function si(n, dec)              -- 千进制缩写: 1234567 -> "1.23M"
  if type(n) ~= "number" then return "?" end
  local neg = n < 0
  local units = { "", "k", "M", "G", "T", "P" }
  local i = 1
  n = math.abs(n)
  while n >= 1000 and i < #units do n = n / 1000; i = i + 1 end
  local s
  if i == 1 then s = tostring(math.floor(n + 0.5))
  else s = string.format("%." .. (dec or 2) .. "f", n) end
  return (neg and "-" or "") .. s .. units[i]
end

local function amountOf(v)
  if type(v) == "table" then return v.amount or 0 end
  if type(v) == "number" then return v end
  return 0
end

local function ratioOf(v)
  if type(v) ~= "number" then return nil end
  if v > 1 then return v / 100 end
  return v
end

local function fnum(v, fmt)
  if type(v) == "number" then return string.format(fmt, v) end
  return "?"
end

local function safe(p, method, ...)
  local f = p and p[method]
  if type(f) ~= "function" then return nil end
  local ok, v = pcall(f, ...)
  if ok then return v end
  return nil
end

local function toFE(p, joules)         -- Mek 原生方法返回 Joules, 转成 FE
  if type(joules) ~= "number" then return nil end
  local ok, fe = pcall(function() return p.joulesToFE(joules) end)
  if ok and type(fe) == "number" then return fe end
  return joules / J_PER_FE
end

---------------------------------------------------------------
-- 外设发现
---------------------------------------------------------------

local KINDS = {
  { kind = "fusion",  typeName = "fusionReactorLogicAdapter",
    fuzzy = "fusion",  title = "FUSION REACTOR", hd = "hdFus" },
  { kind = "boiler",  typeName = "boilerValve",
    fuzzy = "boiler",  title = "THERMO BOILER",  hd = "hdBoi" },
  { kind = "turbine", typeName = "turbineValve",
    fuzzy = "turbine", title = "IND. TURBINE",   hd = "hdTur" },
  { kind = "induction", typeName = "inductionPort",
    fuzzy = "induction", title = "INDUCTION MATRIX", hd = "hdInd" },
}

local function typesOf(name)
  local res = {}
  local ret = table.pack(pcall(peripheral.getType,name))
  if not ret[1] then return res end
  for i = 2, ret.n do
    if type(ret[i]) == "string" then table.insert(res, ret[i]) end
  end
  return res
end

local function typeMatches(name, def)
  if peripheral.hasType then
    local ok, match = pcall(peripheral.hasType,name,def.typeName)
    if ok and match then return true end
  end
  for _, t in ipairs(typesOf(name)) do
    if t == def.typeName then return true end
    if t:lower():find(def.fuzzy, 1, true) then return true end
  end
  return false
end

local function boundsKey(p)
  local mn, mx = safe(p, "getMinPos"), safe(p, "getMaxPos")
  if mn == nil and mx == nil then return nil end
  local function pk(pos)
    if type(pos) ~= "table" then return tostring(pos) end
    return tostring(pos.x or pos[1] or "?") .. "," ..
           tostring(pos.y or pos[2] or "?") .. "," ..
           tostring(pos.z or pos[3] or "?")
  end
  return pk(mn) .. ";" .. pk(mx)
end

local function scanDevices()
  local list, seen = {}, {}
  local names=peripheral.getNames();table.sort(names)
  for _, def in ipairs(KINDS) do
    for _, name in ipairs(names) do
      if typeMatches(name, def) then
        local p = peripheral.wrap(name)
        if p then
          local key = def.kind .. "|" .. (boundsKey(p) or name)
          if not seen[key] then
            seen[key] = true
            table.insert(list, { def = def, name = name, p = p, key = key })
          end
        end
      end
    end
  end
  return list
end

local STATIC_TTL = 300  -- 兜底: 5 分钟强制重读一次

local function pctOf(amt, cap)  -- 本地算百分比, 替代 FilledPercentage 远程调用
  if amt and cap and cap > 0 then return amt / cap end
  return nil
end

local function readStatics(dev, activeCool)
  local p, kind = dev.p, dev.def.kind
  local s = {}
  if kind == "fusion" then
    s.fuelCap    = safe(p, "getDTFuelCapacity")
    s.waterCap   = safe(p, "getWaterCapacity")
    s.steamCap   = safe(p, "getSteamCapacity")
    s.ignitionT  = safe(p, "getIgnitionTemperature", activeCool == true)
  elseif kind == "boiler" then
    s.boilCap    = safe(p, "getBoilCapacity")
    s.superheat  = safe(p, "getSuperheaters")
    s.waterCap   = safe(p, "getWaterCapacity")
    s.steamCap   = safe(p, "getSteamCapacity")
  elseif kind == "turbine" then
    s.steamCap   = safe(p, "getSteamCapacity")
    s.maxFlow    = safe(p, "getMaxFlowRate")
    s.maxProd    = toFE(p, safe(p, "getMaxProduction"))
    s.energyMax  = toFE(p, safe(p, "getMaxEnergy"))
    s.blades     = safe(p, "getBlades")
    s.coils      = safe(p, "getCoils")
    s.condensers = safe(p, "getCondensers")
  elseif kind == "induction" then
    s.energyMax   = toFE(p, safe(p, "getMaxEnergy"))
    s.transferCap = toFE(p, safe(p, "getTransferCap"))
    s.cells       = safe(p, "getInstalledCells")
    s.providers   = safe(p, "getInstalledProviders")
  end
  return s
end

local function readDevice(dev)
  local p, kind = dev.p, dev.def.kind
  local d = { formed = safe(p, "isFormed") == true }

  -- 静态缓存失效条件: 无缓存 / 结构曾损坏(未成型→成型) / 超过 TTL
  if d.formed then
    local stale = dev.statics == nil
      or dev.wasFormed == false
      or (os.clock() - (dev.staticsTime or 0)) > STATIC_TTL
    if stale then
      dev.statics = readStatics(dev, dev.data and dev.data.activeCool)
      dev.staticsTime = os.clock()
    end
  end
  dev.wasFormed = d.formed
  if not d.formed then return d end
  local st = dev.statics or {}

  if kind == "fusion" then
    d.plasma     = safe(p, "getPlasmaTemperature")
    d.casing     = safe(p, "getCaseTemperature")
    d.injection  = safe(p, "getInjectionRate")
    d.fuelAmt    = amountOf(safe(p, "getDTFuel"))
    d.fuelCap    = st.fuelCap
    d.fuelPct    = pctOf(d.fuelAmt, st.fuelCap)
    d.deuterium  = amountOf(safe(p, "getDeuterium"))
    d.tritium    = amountOf(safe(p, "getTritium"))
    d.production = toFE(p, safe(p, "getProductionRate"))
    d.activeCool = safe(p, "isActiveCooledLogic") == true
    d.logicMode  = tostring(safe(p, "getLogicMode") or "?")
    d.ignited    = safe(p, "isIgnited") == true
    -- 点火温度与冷却模式相关, 模式翻牌时重读静态
    if dev.staticsCool ~= d.activeCool then
      dev.statics = readStatics(dev, d.activeCool)
      dev.staticsTime = os.clock()
      st = dev.statics
      d.fuelCap = st.fuelCap
    end
    dev.staticsCool = d.activeCool
    d.ignitionT  = st.ignitionT
    local h      = safe(p, "getHohlraum")
    d.hohlraum   = type(h) == "table" and (h.count or 0) > 0
    -- 水/蒸汽罐无条件读取: 逻辑适配器模式不代表物理冷却方式
    d.waterAmt   = amountOf(safe(p, "getWater"))
    d.waterCap   = st.waterCap
    d.waterPct   = pctOf(d.waterAmt, st.waterCap)
    d.steamAmt   = amountOf(safe(p, "getSteam"))
    d.steamCap   = st.steamCap
    d.steamPct   = pctOf(d.steamAmt, st.steamCap)
    -- 物理水冷判定: 罐内有水或蒸汽即按水冷运行处理
    d.waterCool  = (d.waterAmt or 0) > 0 or (d.steamAmt or 0) > 0

  elseif kind == "boiler" then
    d.temp       = safe(p, "getTemperature")
    d.boilRate   = safe(p, "getBoilRate")
    -- 注意: getMaxBoilRate 是历史峰值追踪器, 属于动态值, 不能缓存
    d.maxBoil    = safe(p, "getMaxBoilRate")
    d.boilCap    = st.boilCap
    d.superheat  = st.superheat
    d.envLoss    = safe(p, "getEnvironmentalLoss")
    d.waterAmt   = amountOf(safe(p, "getWater"))
    d.waterCap   = st.waterCap
    d.waterPct   = pctOf(d.waterAmt, st.waterCap)
    d.steamAmt   = amountOf(safe(p, "getSteam"))
    d.steamCap   = st.steamCap
    d.steamPct   = pctOf(d.steamAmt, st.steamCap)

  elseif kind == "turbine" then
    d.steamAmt   = amountOf(safe(p, "getSteam"))
    d.steamCap   = st.steamCap
    d.steamPct   = pctOf(d.steamAmt, st.steamCap)
    d.steamIn    = safe(p, "getLastSteamInputRate")
    d.flow       = safe(p, "getFlowRate")
    d.maxFlow    = st.maxFlow
    d.production = toFE(p, safe(p, "getProductionRate"))
    d.maxProd    = st.maxProd
    d.dumping    = tostring(safe(p, "getDumpingMode") or "?")
    -- 阀门上的 getEnergy/getMaxEnergy 是 Mek 原生方法, 返回 Joules, 需换算
    d.energy     = toFE(p, safe(p, "getEnergy"))
    d.energyMax  = st.energyMax
    d.energyPct  = pctOf(d.energy, st.energyMax)
    d.blades     = st.blades
    d.coils      = st.coils
    d.condensers = st.condensers

  elseif kind == "induction" then
    -- 输导矩阵: Mek 原生方法, 能量均为 Joules, 需换算
    d.energy      = toFE(p, safe(p, "getEnergy"))
    d.energyMax   = st.energyMax
    d.energyPct   = pctOf(d.energy, st.energyMax)
    d.input       = toFE(p, safe(p, "getLastInput"))
    d.output      = toFE(p, safe(p, "getLastOutput"))
    d.transferCap = st.transferCap
    d.cells       = st.cells
    d.providers   = st.providers
  end
  return d
end

-- BridgeCore pixel UI. All positions below are zero-based; colors are opaque RGB24.
local C = { bg=0x101820, panel=0x182637, text=0xE3EDF5, gray=0x9CB0C1,
  bar=0x304050, header=0x245778, good=0x44CC88, warn=0xFFD05A, bad=0xEE7766,
  fusion=0x9E4139, boiler=0x286C99, turbine=0x287D55, induction=0x704A99 }
local screen, screenName, acquired
local devices, page, details, buttons = {}, 1, {}, {}
local W, H, advance, glyphHeight = 0, 0, 6, 7
local HEADER, FOOTER, GAP, LINE = 22, 18, 3, 11
local columns, gridRows, cardW, cardH, perPage, bodyRows
local painted, headerPainted = {}, nil
local lastAction = "Q: EXIT"

local function fit(s, width)
  s = tostring(s)
  local count = math.max(0, math.floor(width / advance))
  if #s <= count then return s end
  if count < 3 then return s:sub(1, count) end
  return s:sub(1, count - 2) .. ".."
end
local function text(x, y, s, color, width)
  screen.pixelText(math.floor(x), math.floor(y), fit(s, width), color or C.text)
end
local function button(x, y, w, h, label, action, group)
  if w <= 0 or h <= 0 then return end
  screen.pixelFillRect(x, y, w, h, C.header)
  text(x + 2, y + 2, label, C.text, w - 4)
  buttons[#buttons+1] = {x=x,y=y,w=w,h=h,action=action,group=group}
end
local function eraseButtons(group)
  for i=#buttons,1,-1 do if buttons[i].group == group then table.remove(buttons,i) end end
end
local function rowsFor(dev)
  local d, list = dev.data, {}
  local function row(label, value, color, ratio, action)
    list[#list+1] = {label=label,value=tostring(value or "?"),color=color or C.text,ratio=ratio,action=action}
  end
  if not d then row("STATE", "WAITING SAMPLE", C.gray); return list end
  if not d.formed then row("STATE", "NOT FORMED / OFFLINE", C.bad); return list end
  local function tank(label, amount, cap, ratio) row(label, si(amount).."/"..si(cap),C.text,ratio) end
  if dev.def.kind == "fusion" then
    row("PLASMA", si(d.plasma).." K", d.ignited and C.good or C.warn)
    row("CASING", si(d.casing).." K")
    row("IGNITION", si(d.ignitionT).." K")
    row("COOL", d.waterCool and "WATER" or "AIR")
    row("LOGIC", d.logicMode)
    row("HOHLRAUM", d.hohlraum and "YES" or "NO")
    if SHOW_DT_BAR then tank("D-T",d.fuelAmt,d.fuelCap,d.fuelPct) end
    row("DEUTERIUM",si(d.deuterium).." mB")
    row("TRITIUM",si(d.tritium).." mB")
    tank("WATER",d.waterAmt,d.waterCap,d.waterPct)
    tank("STEAM",d.steamAmt,d.steamCap,d.steamPct)
    row("OUTPUT",si(d.production).." FE/t",C.warn)
    row("INJECT",tostring(d.injection or "?"),C.warn,nil,"inject")
  elseif dev.def.kind == "boiler" then
    row("TEMP",si(d.temp).." K")
    tank("WATER",d.waterAmt,d.waterCap,d.waterPct)
    tank("STEAM",d.steamAmt,d.steamCap,d.steamPct)
    row("BOIL",si(d.boilRate).." mB/t")
    row("PEAK",si(d.maxBoil).." mB/t")
    row("CAPACITY",si(d.boilCap).." mB/t")
    row("HEATERS",d.superheat)
    row("LOSS",fnum(d.envLoss,"%.3f").." K/t")
  elseif dev.def.kind == "turbine" then
    row("OUTPUT",si(d.production).." FE/t",C.warn)
    row("MAX OUT",si(d.maxProd).." FE/t")
    tank("STEAM",d.steamAmt,d.steamCap,d.steamPct)
    row("INPUT",si(d.steamIn).." mB/t")
    row("FLOW",si(d.flow).."/"..si(d.maxFlow))
    tank("ENERGY",d.energy,d.energyMax,d.energyPct)
    row("BLADES",d.blades); row("COILS",d.coils); row("CONDENSERS",d.condensers)
    row("DUMP",d.dumping,C.warn,nil,"dump")
  else
    tank("ENERGY",d.energy,d.energyMax,d.energyPct)
    row("INPUT",si(d.input).." FE/t",C.good)
    row("OUTPUT",si(d.output).." FE/t",C.warn)
    row("TRANSFER",si(d.transferCap).." FE/t")
    row("CELLS",d.cells); row("PROVIDERS",d.providers)
  end
  return list
end
local pendingSample
local function operate(dev, kind, delta, sneak)
  -- Reject an old queued touch after the player has changed the GUI source or released us.
  if not screen.isCcEnabled() or screen.getWriter() ~= os.getComputerID() then
    error("Display ownership ended",0)
  end
  -- Writes are the existing Mek Lua actions, never BridgeCore Java control permissions.
  local method, value
  if kind == "inject" then
    local current = safe(dev.p,"getInjectionRate")
    if type(current) ~= "number" then lastAction="INJECTION READ FAILED"; return end
    value=math.max(0,math.min(98,current + delta * (sneak and 5 or 1)))
    value=value - value % 2; method="setInjectionRate"
  else method=sneak and "decrementDumpingMode" or "incrementDumpingMode" end
  if type(dev.p[method]) ~= "function" then lastAction="METHOD UNAVAILABLE"; return end
  local ok, result
  if kind == "inject" then ok,result=pcall(dev.p[method],value) else ok,result=pcall(dev.p[method]) end
  lastAction=ok and result ~= false and "COMMAND SENT" or "COMMAND FAILED"
  pendingSample=dev -- refresh only this device on a sampling tick, not from paint()
end
local function layout()
  W,H=screen.getPixelSize()
  if W==0 or H==0 then return false end
  advance,glyphHeight=screen.pixelMeasure("M")
  assert(advance > 0 and glyphHeight > 0,"Invalid display font metrics")
  LINE=glyphHeight+4
  columns=math.max(1,math.floor(W/240))
  gridRows=math.min(math.max(1,math.ceil(#devices/columns)),math.max(1,math.floor((H-HEADER-FOOTER)/150)))
  perPage=columns*gridRows
  cardW=math.floor((W-GAP*(columns-1))/columns)
  cardH=math.floor((H-HEADER-FOOTER-GAP*(gridRows-1))/gridRows)
  bodyRows=math.max(1,math.floor((cardH-16-14-4)/LINE))
  page=math.min(page,math.max(1,math.ceil(#devices/perPage)))
  painted={}; headerPainted=nil; buttons={}
  screen.pixelClear(C.bg)
  return true
end
local function paintHeader()
  local total, waiting=0,0
  for _,dev in ipairs(devices) do
    if dev.data then total=total+(dev.data.production or 0) else waiting=waiting+1 end
  end
  local top="MEK "..si(total).." FE/t  "..textutils.formatTime(os.time(),false)
  local bottom=lastAction..(waiting > 0 and ("  WAIT "..waiting) or "")
  local signature=top..bottom
  if signature==headerPainted then return end
  headerPainted=signature
  screen.pixelFillRect(0,0,W,HEADER,C.bg)
  text(3,2,top,C.warn,W-6); text(3,12,bottom,C.gray,W-6)
end
local function paintNav()
  eraseButtons("nav")
  screen.pixelFillRect(0,H-FOOTER,W,FOOTER,C.bg)
  local pages=math.max(1,math.ceil(#devices/perPage))
  local function turn(delta) page=(page-1+delta)%pages+1; painted={}; buttons={}; screen.pixelClear(C.bg); headerPainted=nil end
  button(2,H-FOOTER+2,28,13,"<",function() turn(-1) end,"nav")
  text(34,H-FOOTER+4,page.."/"..pages,C.text,math.max(0,W-98))
  button(W-60,H-FOOTER+2,28,13,">",function() turn(1) end,"nav")
  button(W-30,H-FOOTER+2,28,13,"Q",function() return "quit" end,"nav")
end
local function paintCard(slot,dev,index)
  local list=rowsFor(dev)
  local detailPages=math.max(1,math.ceil(#list/bodyRows))
  local detail=math.min(details[dev.key] or 1,detailPages); details[dev.key]=detail
  local signature=dev.key..":"..detail..":"..tostring(dev.sampledAt)..":"..tostring(dev.data)
  if painted[slot]==signature then return end
  painted[slot]=signature; eraseButtons(slot)
  local x=((slot-1)%columns)*(cardW+GAP)
  local y=HEADER+math.floor((slot-1)/columns)*(cardH+GAP)
  screen.pixelPushClip(x,y,cardW,cardH)
  screen.pixelFillRect(x,y,cardW,cardH,C.panel)
  screen.pixelFillRect(x,y,cardW,13,C[dev.def.kind])
  text(x+3,y+3,dev.def.kind.." #"..index,C.text,cardW-6)
  for line=1,bodyRows do
    local row=list[(detail-1)*bodyRows+line]
    if not row then break end
    local ry=y+16+(line-1)*LINE
    if row.ratio then
      local ratio=math.max(0,math.min(1,row.ratio))
      screen.pixelFillRect(x+2,ry,math.floor((cardW-4)*ratio),glyphHeight+2,C.bar)
    end
    if row.action=="inject" then
      text(x+3,ry,"INJ "..row.value,row.color,cardW-58)
      button(x+cardW-54,ry,24,10,"-2",function(sneak) operate(dev,"inject",-2,sneak) end,slot)
      button(x+cardW-27,ry,24,10,"+2",function(sneak) operate(dev,"inject",2,sneak) end,slot)
    elseif row.action=="dump" then
      button(x+2,ry,cardW-4,10,"DUMP "..row.value,function(sneak) operate(dev,"dump",nil,sneak) end,slot)
    else
      local value=fit(row.value,math.floor((cardW-6)*0.62))
      local vx=x+cardW-3-#value*advance
      text(x+3,ry,row.label,C.gray,vx-x-6)
      text(vx,ry,value,row.color,#value*advance)
    end
  end
  button(x+2,y+cardH-13,cardW-4,11,"DETAIL "..detail.."/"..detailPages,function()
    details[dev.key]=detail%detailPages+1
  end,slot)
  screen.pixelPopClip()
end
local function paint(full)
  if full and not layout() then return false end
  if W==0 or H==0 then return false end
  if W<96 or H<96 then
    buttons={};screen.pixelClear(C.bg);text(0,0,"SCREEN TOO SMALL",C.bad,W);screen.present();return true
  end
  paintHeader()
  if #devices==0 then
    if not painted.empty then
      screen.pixelFillRect(0,HEADER,W,H-HEADER-FOOTER,C.bg)
      text(3,HEADER+6,"NO MEK DEVICES",C.warn,W-6)
      text(3,HEADER+18,"CHECK WIRED MODEMS",C.gray,W-6);painted.empty=true
    end
  else
    for slot=1,perPage do
      local index=(page-1)*perPage+slot
      if devices[index] then paintCard(slot,devices[index],index) end
    end
  end
  -- Navigation only needs repaint on layout/page changes.
  if not painted.nav then paintNav();painted.nav=true end
  screen.present()
  return true
end
local function rescan()
  local cache={};for _,dev in ipairs(devices) do cache[dev.key]=dev end
  local fresh=scanDevices()
  for _,dev in ipairs(fresh) do
    local old=cache[dev.key]
    if old then
      dev.data=old.data;dev.statics=old.statics;dev.staticsTime=old.staticsTime
      dev.staticsCool=old.staticsCool;dev.wasFormed=old.wasFormed;dev.sampledAt=old.sampledAt
    end
  end
  devices=fresh; pendingSample=nil
end
local function findDisplay(requested)
  if requested then
    assert(peripheral.hasType(requested,"bridgecore_display"),"Not a BridgeCore display: "..requested)
    return assert(peripheral.wrap(requested)),requested
  end
  local found
  for _,name in ipairs(peripheral.getNames()) do
    if peripheral.hasType(name,"bridgecore_display") then
      assert(not found,"Multiple BridgeCore displays: pass the intended peripheral name")
      found=name
    end
  end
  assert(found,"Connect a BridgeCore display controller via adjacency or wired modem")
  return assert(peripheral.wrap(found)),found
end
local function main(...)
  local args={...}
  if args[1]=="debug" then
    for _,name in ipairs(peripheral.getNames()) do print(name..": "..table.concat(typesOf(name),", ")) end
    print("Select CC mode in the controller GUI; run mekmon_bridge [display-name].")
    return
  end
  screen,screenName=findDisplay(args[1])
  assert(screen.isCcEnabled(),"Select CC mode in the controller GUI first")
  screen.acquire();acquired=true;screen.setLayer("pixel")
  rescan();if not paint(true) then return end
  local cursor=0
  local sampleTimer=os.startTimer(0)
  local clockTimer=os.startTimer(1)
  local scanTimer=os.startTimer(10)
  while true do
    local event,source,x,y,sneak=os.pullEvent()
    if event=="char" and source:lower()=="q" then break
    elseif event=="bridge_display_released" and source==screenName then acquired=false;break
    elseif event=="peripheral_detach" and source==screenName then acquired=false;break
    elseif event=="bridge_display_resize" and source==screenName then
      if not paint(true) then break end
    elseif event=="bridge_display_touch" and source==screenName then
      for _,b in ipairs(buttons) do
        if x>=b.x and y>=b.y and x<b.x+b.w and y<b.y+b.h then
          if b.action(sneak)=="quit" then return end
          paint(false);break
        end
      end
    elseif event=="timer" and source==sampleTimer then
      local dev=pendingSample;pendingSample=nil
      if not dev and #devices>0 then cursor=cursor%#devices+1;dev=devices[cursor] end
      if dev then dev.data=readDevice(dev);dev.sampledAt=os.clock() end
      paint(false);sampleTimer=os.startTimer(REFRESH)
    elseif event=="timer" and source==clockTimer then
      paint(false);clockTimer=os.startTimer(1)
    elseif (event=="timer" and source==scanTimer) or event=="peripheral" or event=="peripheral_detach" then
      -- Device replacement must not retain a prior device's controls/cache under the same name.
      if event=="peripheral_detach" then
        for i=#devices,1,-1 do if devices[i].name==source then table.remove(devices,i) end end
      end
      rescan();paint(true)
      if event=="timer" then scanTimer=os.startTimer(10) end
    end
  end
end
local ok,err=pcall(main,...)
if acquired and screen then pcall(screen.release) end
if not ok then error(err,0) end
