package com.bridgecore.cc;

import com.bridgecore.display.DisplayContentSink;
import com.bridgecore.display.DisplayRaster;
import com.bridgecore.display.ExternalDisplaySession;
import dan200.computercraft.api.lua.Coerced;
import dan200.computercraft.api.lua.IArguments;
import dan200.computercraft.api.lua.ILuaContext;
import dan200.computercraft.api.lua.LuaException;
import dan200.computercraft.api.lua.MethodResult;
import dan200.computercraft.api.peripheral.IComputerAccess;
import dan200.computercraft.api.peripheral.IDynamicPeripheral;
import dan200.computercraft.api.peripheral.IPeripheral;
import dan200.computercraft.api.peripheral.NotAttachedException;
import dan200.computercraft.core.apis.TermMethods;
import dan200.computercraft.core.terminal.Palette;
import dan200.computercraft.core.terminal.Terminal;
import dan200.computercraft.core.util.Colour;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.Iterator;
import java.util.HashMap;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** One controller, one whole-screen peripheral. All game/state work is main-thread scheduled. */
public final class CCDisplayPeripheral implements IDynamicPeripheral, ExternalDisplaySession {
    private static final Map<String, String> OPERATIONS = Map.ofEntries(
            Map.entry("cellWrite", "write"),
            Map.entry("cellBlit", "blit"),
            Map.entry("cellScroll", "scroll"),
            Map.entry("cellClear", "clear"),
            Map.entry("cellClearLine", "clearLine"),
            Map.entry("getCellCursor", "getCursorPos"),
            Map.entry("setCellCursor", "setCursorPos"),
            Map.entry("getCellCursorBlink", "getCursorBlink"),
            Map.entry("setCellCursorBlink", "setCursorBlink"),
            Map.entry("getCellSize", "getSize"),
            Map.entry("getCellTextColor", "getTextColor"),
            Map.entry("setCellTextColor", "setTextColor"),
            Map.entry("getCellBackgroundColor", "getBackgroundColor"),
            Map.entry("setCellBackgroundColor", "setBackgroundColor"),
            Map.entry("getCellPalette", "getPaletteColor"),
            Map.entry("setCellPalette", "setPaletteColor"),
            Map.entry("getCellScale", "getTextScale"),
            Map.entry("setCellScale", "setTextScale"),
            Map.entry("getPixelSize", "getPixelSize"),
            Map.entry("acquire", "claim"),
            Map.entry("release", "release"),
            Map.entry("getWriter", "getOwner"),
            Map.entry("getLayer", "getMode"),
            Map.entry("setLayer", "setMode"),
            Map.entry("pixelSet", "setPixel"),
            Map.entry("pixelFillRect", "fillRect"),
            Map.entry("pixelRect", "rect"),
            Map.entry("pixelText", "text"),
            Map.entry("pixelMeasure", "measure"),
            Map.entry("pixelClear", "clearPixels"),
            Map.entry("pixelPushClip", "pushClip"),
            Map.entry("pixelPopClip", "popClip"),
            Map.entry("present", "flush"),
            Map.entry("isCcEnabled", "isCcEnabled"));
    private static final String[] METHODS = OPERATIONS.keySet().toArray(String[]::new);
    private final DisplayContentSink sink;
    private final DisplayRaster raster;
    private final BooleanSupplier available;
    private final BooleanSupplier writable;
    private final Consumer<Runnable> server;
    private final Runnable resumeApp;
    // CC invokes attach/detach on its own threads. Only this set is shared with them.
    private final Set<IComputerAccess> computers = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Map<Integer, Long> disconnectEpochs = new HashMap<>(); // guarded by computers
    private final Set<Integer> revoked = new HashSet<>();
    private Integer owner;
    private long ownerEpoch;
    private boolean closed, dirty, termDirty = true, blinkOn;
    private int ticks, scale = 2;
    private String mode = "cell";
    private Terminal terminal;
    private final TermMethods term = new TermMethods() {
        @Override public Terminal getTerminal() { return terminal; }
    };

    public CCDisplayPeripheral(DisplayContentSink sink, BooleanSupplier available,
                               Consumer<Runnable> server, Runnable resumeApp) {
        this(sink, available, () -> true, server, resumeApp);
    }
    public CCDisplayPeripheral(DisplayContentSink sink, BooleanSupplier available, BooleanSupplier writable,
                               Consumer<Runnable> server, Runnable resumeApp) {
        this.sink = sink; this.available = available; this.writable = writable; this.server = server; this.resumeApp = resumeApp;
        raster = new DisplayRaster(sink);
        resetTerminal();
    }
    private void resetTerminal() {
        scale = 2; mode = "cell"; ticks = 0; blinkOn = false;
        terminal = new Terminal(Math.max(1, columns()), Math.max(1, rows()), true, () -> termDirty = true);
        raster.resetClip(); termDirty = true;
    }
    private int columns() { return sink.width() / (6 * scale); }
    private int rows() { return sink.height() / (9 * scale); }
    @Override public String getType() { return "bridgecore_display"; }
    @Override public Set<String> getAdditionalTypes() { return Set.of(); }
    @Override public Object getTarget() { return sink; }
    @Override public String[] getMethodNames() { return METHODS.clone(); }
    @Override public boolean equals(IPeripheral other) { return this == other; }
    @Override public void attach(IComputerAccess computer) { synchronized (computers) { computers.add(computer); } }
    @Override public void detach(IComputerAccess computer) {
        int id = computer.getID();
        final long epoch;
        synchronized (computers) {
            computers.remove(computer);
            if (hasComputer(id)) return;
            epoch = disconnectEpochs.merge(id, 1L, Long::sum);
        }
        server.accept(() -> {
            if (!closed && owner != null && owner == id && ownerEpoch < epoch) revoke("detached");
        });
    }
    private long epoch(int id) { synchronized (computers) { return disconnectEpochs.getOrDefault(id, 0L); } }
    private void checkDisconnected() {
        if (owner != null && (!hasComputer(owner) || epoch(owner) != ownerEpoch)) revoke("detached");
    }
    private boolean hasComputer(int id) {
        synchronized (computers) { return computers.stream().anyMatch(c -> c.getID() == id); }
    }
    private boolean attached(IComputerAccess computer) {
        synchronized (computers) { return computers.contains(computer); }
    }
    @Override public MethodResult callMethod(IComputerAccess computer, ILuaContext context,
                                             int method, IArguments args) throws LuaException {
        if (method < 0 || method >= METHODS.length) throw new LuaException("Unknown method");
        args.escapes(); // The arguments outlive this CC coroutine call until the server task runs.
        return context.executeMainThreadTask(() -> invoke(computer, METHODS[method], args));
    }

    /** Package-visible for contract tests; production entry is always executeMainThreadTask. */
    Object[] invoke(IComputerAccess computer, String name, IArguments a) throws LuaException {
        if (closed || !available.getAsBoolean() || !attached(computer)) throw new LuaException("Display detached");
        checkDisconnected();
        name = OPERATIONS.getOrDefault(name, name).replace("Colour", "Color");
        try {
            switch (name) {
                case "isCcEnabled": return new Object[]{writable.getAsBoolean()};
                case "getSize": return new Object[]{columns(), rows()};
                case "getPixelSize": return new Object[]{sink.width(), sink.height()};
                case "getCursorPos": return term.getCursorPos();
                case "getCursorBlink": return new Object[]{term.getCursorBlink()};
                case "getTextColor": return new Object[]{term.getTextColour()};
                case "getBackgroundColor": return new Object[]{term.getBackgroundColour()};
                case "getPaletteColor": return term.getPaletteColour(a.getInt(0));
                case "isColor": return new Object[]{true};
                case "getTextScale": return new Object[]{scale / 2.0};
                case "getOwner": return new Object[]{owner};
                case "getMode": return new Object[]{mode};
                case "measure": return new Object[]{6 * fontScale(a, 1) * text(a, 0).length(), 7 * fontScale(a, 1)};
                case "claim": claim(computer, true); return new Object[]{true};
                case "release":
                    if (owner != null && owner == computer.getID()) { revoke("released"); return new Object[]{true}; }
                    return new Object[]{false};
            }
            claim(computer, false);
            switch (name) {
                case "setMode": switchMode(a.getString(0)); break;
                case "flush": dirty = true; break; // Actual sync is deferred to tick, even for explicit flush.
                case "setPixel": switchMode("pixel"); raster.fill(a.getInt(0), a.getInt(1), 1, 1, rgb(a, 2)); dirty = true; break;
                case "fillRect": case "rect": {
                    int x = a.getInt(0), y = a.getInt(1), w = a.getInt(2), h = a.getInt(3), c = rgb(a, 4);
                    if (w < 0 || h < 0) throw new LuaException("negative rectangle size");
                    switchMode("pixel");
                    if (name.equals("rect")) raster.rect(x, y, w, h, c); else raster.fill(x, y, w, h, c);
                    dirty = true; break;
                }
                case "text": {
                    int x = a.getInt(0), y = a.getInt(1), c = rgb(a, 3), s = fontScale(a, 4);
                    String value = text(a, 2);
                    switchMode("pixel"); raster.text(x, y, value, c, s); dirty = true; break;
                }
                case "clearPixels": {
                    int c = a.count() == 0 ? 0 : rgb(a, 0);
                    switchMode("pixel"); raster.fill(0, 0, sink.width(), sink.height(), c); dirty = true; break;
                }
                case "pushClip": switchMode("pixel"); raster.pushClip(a.getInt(0), a.getInt(1), a.getInt(2), a.getInt(3)); break;
                case "popClip": switchMode("pixel"); raster.popClip(); break;
                case "setTextScale": {
                    double requested = a.getFiniteDouble(0);
                    int next = (int)(requested * 2);
                    if (next < 1 || next > 10) throw new LuaException("Expected number in range 0.5-5");
                    switchMode("cell");
                    if (next != scale) {
                        int w = columns(), h = rows(); scale = next;
                        resizeTerminal();
                        if (columns() != w || rows() != h) event("bridge_display_cell_resize", false);
                    }
                    break;
                }
                default:
                    switchMode("cell");
                    switch (name) {
                        case "write": term.write(new Coerced<>(text(a, 0))); break;
                        case "blit": {
                            if (a.getBytes(0).remaining() > 32768) throw new LuaException("text limit is 32768 bytes");
                            term.blit(a.getBytes(0), a.getBytes(1), a.getBytes(2)); break;
                        }
                        case "scroll": term.scroll(a.getInt(0)); break;
                        case "clear": term.clear(); break;
                        case "clearLine": term.clearLine(); break;
                        case "setCursorPos": term.setCursorPos(a.getInt(0), a.getInt(1)); break;
                        case "setCursorBlink": term.setCursorBlink(a.getBoolean(0)); break;
                        case "setTextColor": term.setTextColour(a.getInt(0)); break;
                        case "setBackgroundColor": term.setBackgroundColour(a.getInt(0)); break;
                        case "setPaletteColor": term.setPaletteColour(a); break;
                        default: throw new LuaException("Unknown method " + name);
                    }
            }
            return new Object[0];
        } catch (IllegalArgumentException e) { throw new LuaException(e.getMessage()); }
    }
    private static String text(IArguments a, int index) throws LuaException {
        String value = a.getStringCoerced(index);
        if (value.length() > 32768) throw new LuaException("text limit is 32768 bytes");
        return value;
    }
    private static int fontScale(IArguments a, int index) throws LuaException {
        int s = a.optInt(index, 1);
        if (s < 1 || s > 10) throw new LuaException("font scale must be 1..10");
        return s;
    }
    private static int rgb(IArguments a, int index) throws LuaException {
        long value = a.getLong(index);
        if (value < 0 || value > 0xFFFFFFL) throw new LuaException("Expected RGB24 0xRRGGBB (no alpha)");
        return (int)value;
    }
    private void claim(IComputerAccess computer, boolean explicit) throws LuaException {
        if (!writable.getAsBoolean()) throw new LuaException("Select CC mode in the controller GUI first");
        int id = computer.getID();
        if (owner != null && owner != id) throw new LuaException("Display busy (computer " + owner + ")");
        if (!explicit && revoked.contains(id)) throw new LuaException("Display reclaimed in controller settings; call claim() to reacquire");
        if (sink.width() == 0 || sink.height() == 0) throw new LuaException("No screen connected");
        if (explicit) revoked.remove(id);
        if (owner == null) { owner = id; ownerEpoch = epoch(id); resetTerminal(); sink.clear(); dirty = true; }
    }
    private void switchMode(String next) throws LuaException {
        if (!next.equals("cell") && !next.equals("pixel")) throw new LuaException("layer must be cell or pixel");
        if (next.equals(mode)) return;
        mode = next; raster.resetClip(); sink.clear(); dirty = true; termDirty = true;
    }
    private void resizeTerminal() {
        terminal.resize(Math.max(1, columns()), Math.max(1, rows()));
        raster.resetClip(); termDirty = true;
    }
    @Override public boolean isActive() { return owner != null && !closed; }
    @Override public void surfaceChanged() {
        int w = terminal.getWidth(), h = terminal.getHeight();
        resizeTerminal();
        if (isActive()) { sink.clear(); dirty = true; }
        if (columns() != w || rows() != h) event("bridge_display_cell_resize", false);
        event("bridge_display_resize", false, sink.width(), sink.height(), columns(), rows());
        if (sink.width() == 0 || sink.height() == 0) revoke("screen_lost");
    }
    @Override public void revoke(String reason) {
        if (owner == null) return;
        if (!reason.equals("detached")) event("bridge_display_released", true, reason);
        if (reason.equals("settings")) revoked.add(owner);
        int previousColumns = columns(), previousRows = rows();
        owner = null; dirty = false; resetTerminal();
        if (columns() != previousColumns || rows() != previousRows) event("bridge_display_cell_resize", false);
        resumeApp.run();
    }
    @Override public void close() {
        if (closed) return;
        revoke("controller_unloaded"); closed = true;
        synchronized (computers) { computers.clear(); }
    }
    public void touch(int x, int y, boolean sneak) {
        if (!isActive() || !writable.getAsBoolean()) return;
        // BridgeCore cell event uses character positions; the pixel event includes sneak.
        int cx = x / (6 * scale) + 1, cy = y / (9 * scale) + 1;
        if (!sneak && cx <= columns() && cy <= rows()) event("bridge_display_cell_touch", true, cx, cy);
        event("bridge_display_touch", true, x, y, sneak);
    }
    private void event(String name, boolean onlyOwner, Object... args) {
        synchronized (computers) {
            for (Iterator<IComputerAccess> it = computers.iterator(); it.hasNext();) {
                IComputerAccess c = it.next();
                try {
                    if (onlyOwner && (owner == null || c.getID() != owner)) continue;
                    Object[] values = new Object[args.length + 1]; values[0] = c.getAttachmentName();
                    System.arraycopy(args, 0, values, 1, args.length);
                    c.queueEvent(name, values);
                } catch (NotAttachedException ignored) {
                    // CC marks its wrapper detached before calling our detach callback.
                    it.remove();
                    if (!hasComputer(c.getID())) disconnectEpochs.merge(c.getID(), 1L, Long::sum);
                }
            }
        }
    }
    @Override public void tick() {
        checkDisconnected();
        if (isActive() && !writable.getAsBoolean()) revoke("settings");
        if (!isActive()) return;
        boolean nextBlink = (++ticks / 10) % 2 == 0;
        if (nextBlink != blinkOn && terminal.getCursorBlink()) termDirty = true;
        blinkOn = nextBlink;
        if (mode.equals("cell") && termDirty) { renderTerminal(); termDirty = false; dirty = true; }
        if (dirty) { sink.flush(); dirty = false; }
    }
    private int color(char hex) {
        return color(hex, Colour.WHITE);
    }
    private int color(char hex, Colour fallback) {
        return Palette.encodeRGB8(terminal.getPalette().getColour(15 - Terminal.getColour(hex, fallback)));
    }
    private void renderTerminal() {
        raster.resetClip();
        sink.fill(0, 0, sink.width(), sink.height(), color(Character.forDigit(terminal.getBackgroundColour(), 16)));
        for (int y = 0; y < rows(); y++) for (int x = 0; x < columns(); x++) {
            int px = x * 6 * scale, py = y * 9 * scale;
            raster.fill(px, py, 6 * scale, 9 * scale, color(terminal.getBackgroundColourLine(y).charAt(x), Colour.BLACK));
            raster.text(px, py, String.valueOf(terminal.getLine(y).charAt(x)), color(terminal.getTextColourLine(y).charAt(x)), scale);
        }
        int x = terminal.getCursorX(), y = terminal.getCursorY();
        if (terminal.getCursorBlink() && blinkOn && x >= 0 && y >= 0 && x < columns() && y < rows())
            raster.fill(x * 6 * scale, y * 9 * scale + 7 * scale, 5 * scale, scale,
                    color(Character.forDigit(terminal.getTextColour(), 16)));
    }
}
