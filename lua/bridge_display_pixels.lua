-- Controller GUI must select CC mode. Lua cannot override that selection.
-- Run: bridge_display_pixels [peripheral-name]. All drawing/touch positions are ZERO based.
local args = { ... }
local display = args[1] and peripheral.wrap(args[1]) or peripheral.find("bridgecore_display")
assert(display and display.acquire, "Connect a BridgeCore display controller")
local name = peripheral.getName(display)
local count = 0
local ok, err = pcall(function()
    assert(display.isCcEnabled(), "Select CC mode in the controller GUI first")
    display.acquire()
    display.setLayer("pixel")
    local function paint()
        local w, h = display.getPixelSize()
        display.pixelClear(0x101820) -- RGB24, opaque. ARGB is rejected.
        display.pixelText(4, 4, "BRIDGECORE PIXELS", 0x38B6FF)
        display.pixelRect(3, 17, math.max(0, w - 6), math.max(0, h - 20), 0x38B6FF)
        display.pixelPushClip(5, 19, math.max(0, w - 10), math.max(0, h - 24))
        display.pixelFillRect(8, 24, 94, 22, 0x226644)
        display.pixelText(12, 31, ("TOUCHES %d"):format(count), 0xFFFFFF)
        display.pixelText(8, 54, "SNEAK-TOUCH / Q: EXIT", 0xAAAAAA)
        display.pixelPopClip()
        display.present() -- Optional: changes are also presented automatically at the next server tick.
    end
    paint()
    while true do
        local event, source, x, y, sneak = os.pullEvent()
        if event == "bridge_display_touch" and source == name then
            if sneak then break end
            if x >= 8 and x < 102 and y >= 24 and y < 46 then count = count + 1; paint() end
        elseif event == "bridge_display_resize" and source == name then
            local w, h = display.getPixelSize()
            if w == 0 or h == 0 then break end
            paint()
        elseif event == "bridge_display_released" and source == name then
            break
        elseif event == "char" and source:lower() == "q" then
            break
        end
    end
end)
pcall(display.release)
if not ok then error(err, 0) end
