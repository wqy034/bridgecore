-- Controller GUI must select CC mode. Lua cannot override that selection.
-- First select CC mode in the controller GUI. Uses BridgeCore's own cell API.
local args = { ... }
local screen = args[1] and peripheral.wrap(args[1]) or peripheral.find("bridgecore_display")
assert(screen and screen.acquire, "Connect a BridgeCore display controller")
local name = peripheral.getName(screen)
local ok, err = pcall(function()
    assert(screen.isCcEnabled(), "Select CC mode in the controller GUI first")
    screen.acquire()
    screen.setCellScale(0.5)
    local function paint(message)
        screen.cellClear()
        screen.setCellCursor(1, 1)
        screen.cellWrite("BRIDGECORE")
        local w, h = screen.getCellSize()
        if h > 1 then
            screen.setCellCursor(1, 2)
            screen.cellWrite((message or "Touch screen; Q exits"):sub(1, w))
        end
    end
    paint()
    while true do
        local event, source, x, y = os.pullEvent()
        if event == "bridge_display_cell_touch" and source == name then
            paint(("Cell %d,%d"):format(x, y))
        elseif event == "bridge_display_cell_resize" and source == name then paint()
        elseif event == "bridge_display_released" and source == name then break
        elseif event == "char" and source:lower() == "q" then break end
    end
end)
pcall(screen.release)
if not ok then error(err, 0) end
