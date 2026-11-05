package com.bridgecore.cc;

import com.bridgecore.display.DisplayContentSink;
import com.bridgecore.display.DisplayRaster;
import dan200.computercraft.api.lua.*;
import dan200.computercraft.api.peripheral.IComputerAccess;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CCDisplayPeripheralTest {
    static class Sink implements DisplayContentSink {
        int w = 120, h = 72, flushes, resumes;
        int[][] pixels = new int[120][72];
        boolean available = true;
        @Override public int width() { return w; }
        @Override public int height() { return h; }
        void resize(int w, int h) { this.w = w; this.h = h; pixels = new int[w][h]; }
        @Override public void setPixel(int x, int y, int c) {
            assertTrue(x >= 0 && y >= 0 && x < w && y < h, "backend must only receive clipped pixels");
            pixels[x][y] = c;
        }
        @Override public void fill(int x, int y, int w, int h, int c) {
            for (int dx = 0; dx < w; dx++) for (int dy = 0; dy < h; dy++) setPixel(x + dx, y + dy, c);
        }
        @Override public void drawText(int x, int y, String s, int c) { fail("Use Canvas font path"); }
        @Override public void clear() { for (int[] col : pixels) Arrays.fill(col, 0); }
        @Override public void flush() { flushes++; }
    }
    record Event(String name, Object[] values) {}
    static class Computer {
        final List<Event> events = new ArrayList<>();
        final IComputerAccess access;
        boolean disconnected;
        Computer(int id, String name) {
            access = (IComputerAccess)Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{IComputerAccess.class},
                    (p, m, a) -> switch (m.getName()) {
                        case "getID" -> id;
                        case "getAttachmentName" -> {
                            if (disconnected) throw new dan200.computercraft.api.peripheral.NotAttachedException();
                            yield name;
                        }
                        case "queueEvent" -> { events.add(new Event((String)a[0], (Object[])a[1])); yield null; }
                        case "hashCode" -> System.identityHashCode(p);
                        case "equals" -> p == a[0];
                        case "toString" -> name;
                        default -> null;
                    });
        }
        Event last(String name) { return events.stream().filter(e -> e.name.equals(name)).reduce((a,b) -> b).orElseThrow(); }
        long count(String name) { return events.stream().filter(e -> e.name.equals(name)).count(); }
    }
    static class Rig {
        final Sink sink = new Sink();
        final List<Runnable> scheduled = new ArrayList<>();
        final Computer a = new Computer(1, "left"), b = new Computer(2, "display_9");
        final CCDisplayPeripheral p = new CCDisplayPeripheral(sink, () -> sink.available,
                scheduled::add, () -> sink.resumes++);
        Rig() { p.attach(a.access); p.attach(b.access); }
        Object[] call(String method, Object... args) throws LuaException { return call(a, method, args); }
        Object[] call(Computer c, String method, Object... args) throws LuaException {
            return p.invoke(c.access, method, new ObjectArguments(args));
        }
    }

    @Test void writeBlitCursorClippingAndClearMatchTerminalSemantics() throws Exception {
        Rig r = new Rig();
        assertArrayEquals(new Object[]{10, 4}, r.call("getSize"));
        r.call("setCursorPos", 10, 1); r.call("write", "XYZ");
        assertArrayEquals(new Object[]{13, 1}, r.call("getCursorPos")); // no wrap, including offscreen bytes
        r.call("setCursorPos", 0, 2); r.call("write", "AB");
        assertArrayEquals(new Object[]{2, 2}, r.call("getCursorPos"));
        r.call("setPaletteColor", 1, 0x123456); r.call("setPaletteColour", 32768, 0xABCDEF);
        r.call("setCursorPos", 1, 1); r.call("blit", "A ", "00", "ff"); r.p.tick();
        assertEquals(0x123456, r.sink.pixels[2][0]); // A top row, CC white slot 0
        assertEquals(0xABCDEF, r.sink.pixels[0][0]); // black slot f
        assertArrayEquals(new Object[]{3, 1}, r.call("getCursorPos"));
        assertThrows(LuaException.class, () -> r.call("blit", "ab", "0", "ff"));
        r.call("setBackgroundColor", 1); r.call("clearLine"); r.p.tick();
        assertEquals(0x123456, r.sink.pixels[0][0]);
        assertArrayEquals(new Object[]{3, 1}, r.call("getCursorPos"));
    }
    @Test void scrollMovesCharactersAndFillsWithCurrentColorsInBothDirections() throws Exception {
        Rig r = new Rig();
        r.call("setPaletteColor", 1, 0xFF0000); r.call("setPaletteColor", 32768, 0x0000FF);
        r.call("setCursorPos", 1, 2); r.call("write", "A"); r.call("scroll", 1); r.p.tick();
        assertEquals(0xFF0000, r.sink.pixels[2][0]);
        r.call("scroll", -1); r.p.tick();
        assertEquals(0x0000FF, r.sink.pixels[2][0]);
        assertEquals(0xFF0000, r.sink.pixels[2][18]);
        r.call("scroll", Integer.MAX_VALUE); r.p.tick();
        assertEquals(0x0000FF, r.sink.pixels[2][18]);
    }
    @Test void blitInvalidHexUsesCcWhiteForegroundAndBlackBackgroundFallbacks() throws Exception {
        Rig r = new Rig();
        r.call("setPaletteColor", 1, 0x123456); r.call("setPaletteColor", 32768, 0xABCDEF);
        r.call("blit", "A", "g", "g"); r.p.tick();
        assertEquals(0x123456, r.sink.pixels[2][0]);
        assertEquals(0xABCDEF, r.sink.pixels[0][0]);
    }
    @Test void scalesUseHalfStepsAndReportCharacterAndPixelDimensionsSeparately() throws Exception {
        Rig r = new Rig();
        r.call("setTextScale", 0.5);
        assertArrayEquals(new Object[]{20, 8}, r.call("getSize"));
        assertArrayEquals(new Object[]{120, 72}, r.call("getPixelSize"));
        assertArrayEquals(new Object[]{0.5}, r.call("getTextScale"));
        assertEquals(1, r.a.count("bridge_display_cell_resize"));
        r.call("setTextScale", 0.7); // same floor(2*s) rule as the installed CC 1.120.0
        assertEquals(1, r.a.count("bridge_display_cell_resize"));
        assertThrows(LuaException.class, () -> r.call("setTextScale", Double.NaN));
        assertThrows(LuaException.class, () -> r.call("setTextScale", 0.4));
        r.call("setTextScale", 5);
        assertArrayEquals(new Object[]{2, 0}, r.call("getSize"));
    }
    @Test void leasesAllowOneComputerAndSurviveOneOfItsMultipleConnectionsDetaching() throws Exception {
        Rig r = new Rig(); Computer second = new Computer(1, "right"); r.p.attach(second.access);
        assertArrayEquals(new Object[]{null}, r.call("getOwner"));
        r.call("write", "owner");
        assertThrows(LuaException.class, () -> r.call(r.b, "clear"));
        assertArrayEquals(new Object[]{false}, r.call(r.b, "release"));
        assertArrayEquals(new Object[]{10, 4}, r.call(r.b, "getSize"));
        r.p.detach(r.a.access); assertTrue(r.scheduled.isEmpty()); assertTrue(r.p.isActive());
        r.p.detach(second.access); assertTrue(r.p.isActive()); r.scheduled.removeFirst().run();
        assertFalse(r.p.isActive()); assertEquals(1, r.sink.resumes);
        r.call(r.b, "claim"); assertTrue(r.p.isActive());
    }
    @Test void manualSettingsReclaimRequiresExplicitReacquireAndDoesNotGrantOtherComputerRelease() throws Exception {
        Rig r = new Rig(); r.call("write", "hello"); r.p.revoke("settings");
        assertArrayEquals(new Object[]{"left", "settings"}, r.a.last("bridge_display_released").values);
        assertFalse(r.p.isActive());
        assertThrows(LuaException.class, () -> r.call("write", "stale script"));
        r.call("claim"); assertTrue(r.p.isActive());
        r.call("release"); assertFalse(r.p.isActive()); assertEquals(2, r.sink.resumes);
    }
    @Test void rapidSameIdReconnectCannotInheritOldLeaseOrBeRevokedByOldDetachTask() throws Exception {
        Rig r = new Rig(); r.call("claim"); r.call("setTextScale", 0.5);
        r.p.detach(r.a.access);
        Computer rebooted = new Computer(1, "left"); r.p.attach(rebooted.access);
        r.call(rebooted, "claim");
        assertArrayEquals(new Object[]{1.0}, r.call(rebooted, "getTextScale"));
        assertEquals(0, rebooted.count("bridge_display_released"));
        r.scheduled.removeFirst().run(); assertTrue(r.p.isActive());
        assertEquals(1, r.sink.resumes);
    }
    @Test void ccWrapperCanBecomeDetachedBeforeCallbackWithoutCrashingEventDispatch() throws Exception {
        Rig r = new Rig(); r.call("claim"); r.a.disconnected = true;
        assertDoesNotThrow(() -> r.p.touch(0, 0, false));
        r.p.tick(); assertFalse(r.p.isActive()); assertEquals(1, r.sink.resumes);
    }
    @Test void allDrawCallsBatchUntilTickAndPixelsAreRgb24WithExplicitModeSwitching() throws Exception {
        Rig r = new Rig();
        r.call("clearPixels", 0x123456); r.call("setPixel", 0, 0, 0xFFFFFF); r.call("flush");
        assertEquals(0, r.sink.flushes); r.p.tick(); assertEquals(1, r.sink.flushes);
        assertEquals(0xFFFFFF, r.sink.pixels[0][0]); assertEquals(0x123456, r.sink.pixels[1][0]);
        r.p.tick(); assertEquals(1, r.sink.flushes);
        assertThrows(LuaException.class, () -> r.call("setPixel", 0, 0, 0xFF123456L));
        assertThrows(LuaException.class, () -> r.call("fillRect", 0, 0, -1, 2, 0));
        r.call("setCursorPos", 1, 1); r.call("write", "A"); r.p.tick();
        assertArrayEquals(new Object[]{"cell"}, r.call("getMode"));
        assertNotEquals(0x123456, r.sink.pixels[1][0]);
    }
    @Test void pixelClipsIntersectAndFontMeasurementUsesTheActualCanvasAdvance() throws Exception {
        Rig r = new Rig(); r.call("setMode", "pixel");
        r.call("pushClip", 2, 3, 8, 9); r.call("pushClip", 4, 5, 8, 9);
        r.call("fillRect", -100, -100, 1000, 1000, 0xFFFFFF); r.call("popClip"); r.call("popClip");
        assertEquals(0, r.sink.pixels[3][5]); assertEquals(0xFFFFFF, r.sink.pixels[4][5]);
        assertEquals(0, r.sink.pixels[10][5]);
        assertArrayEquals(new Object[]{36, 14}, r.call("measure", "abc", 2));
        assertThrows(LuaException.class, () -> r.call("popClip"));
        assertDoesNotThrow(() -> r.call("rect", Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, 0));
    }
    @Test void lowercaseMatchesCanvasUppercaseAndUnsupportedGlyphIsBlank() throws Exception {
        Sink a = new Sink(), b = new Sink(); DisplayRaster ra = new DisplayRaster(a), rb = new DisplayRaster(b);
        ra.text(0, 0, "abc", 0xABCDEF, 1); rb.text(0, 0, "ABC", 0xABCDEF, 1);
        assertTrue(Arrays.deepEquals(a.pixels, b.pixels));
        a.clear(); ra.text(0, 0, "\u4e2d", 0xFFFFFF, 1);
        assertEquals(0, Arrays.stream(a.pixels).flatMapToInt(Arrays::stream).sum());
    }
    @Test void touchesUseAttachmentNamesAndSeparateSneakAndRemainderPixelsFromStandardEvents() throws Exception {
        Rig r = new Rig(); r.call("claim"); r.p.touch(13, 20, false);
        assertArrayEquals(new Object[]{"left", 2, 2}, r.a.last("bridge_display_cell_touch").values);
        assertArrayEquals(new Object[]{"left", 13, 20, false}, r.a.last("bridge_display_touch").values);
        assertEquals(0, r.b.count("bridge_display_cell_touch"));
        r.p.touch(13, 20, true); assertEquals(1, r.a.count("bridge_display_cell_touch"));
        assertArrayEquals(new Object[]{"left", 13, 20, true}, r.a.last("bridge_display_touch").values);
        r.sink.resize(121, 73); r.p.surfaceChanged(); r.p.touch(120, 72, false);
        assertEquals(1, r.a.count("bridge_display_cell_touch")); assertEquals(3, r.a.count("bridge_display_touch"));
    }
    @Test void geometryChangePreservesTermOverlapButClearsPixelModeAndRevokesOnTotalScreenLoss() throws Exception {
        Rig r = new Rig(); r.call("setPaletteColor", 1, 0xFF0000); r.call("write", "A"); r.p.tick();
        r.sink.resize(240, 72); r.p.surfaceChanged(); r.p.tick();
        assertEquals(0xFF0000, r.sink.pixels[2][0]);
        assertArrayEquals(new Object[]{"left", 240, 72, 20, 4}, r.a.last("bridge_display_resize").values);
        assertEquals(1, r.b.count("bridge_display_cell_resize"));
        r.call("clearPixels", 0xFFFFFF); r.p.surfaceChanged(); r.p.tick(); assertEquals(0, r.sink.pixels[0][0]);
        r.sink.resize(0, 0); r.p.surfaceChanged(); assertFalse(r.p.isActive());
        assertArrayEquals(new Object[]{0, 0}, r.call("getSize"));
        assertThrows(LuaException.class, () -> r.call("claim"));
        assertArrayEquals(new Object[]{"left", "screen_lost"}, r.a.last("bridge_display_released").values);
    }
    @Test void closeAndUnavailableHostRejectStaleCallsAndCloseIsIdempotent() throws Exception {
        Rig r = new Rig(); r.call("write", "hi"); r.p.close(); r.p.close();
        assertFalse(r.p.isActive()); assertEquals(1, r.sink.resumes);
        assertThrows(LuaException.class, () -> r.call("write", "late"));
        Rig other = new Rig(); other.sink.available = false;
        assertThrows(LuaException.class, () -> other.call("getSize"));
    }
    @Test void realPeripheralEntryDefersStateMutationToMainThreadTask() throws Exception {
        Rig r = new Rig(); final LuaTask[] queued = {null};
        ILuaContext context = task -> { queued[0] = task; return 1; };
        int method = Arrays.asList(r.p.getMethodNames()).indexOf("cellWrite");
        r.p.callMethod(r.a.access, context, method, new ObjectArguments("hi"));
        assertNotNull(queued[0]); assertFalse(r.p.isActive());
        queued[0].execute(); assertTrue(r.p.isActive()); assertEquals(0, r.sink.flushes);
        r.p.tick(); assertEquals(1, r.sink.flushes);
    }
    @Test void cursorBlinkActuallyRendersAndTurnsOffWithoutMovingCursor() throws Exception {
        Rig r = new Rig(); r.call("setPaletteColor", 1, 0xFFFFFF); r.call("setPaletteColor", 32768, 0); r.call("setCursorBlink", true); r.p.tick();
        assertEquals(0xFFFFFF, r.sink.pixels[0][14]);
        for (int i = 0; i < 9; i++) r.p.tick();
        assertEquals(0, r.sink.pixels[0][14]);
        assertArrayEquals(new Object[]{1, 1}, r.call("getCursorPos"));
        assertArrayEquals(new Object[]{true}, r.call("getCursorBlink"));
    }
    @Test void guiGateRejectsEvenExplicitAcquireAndNoStandardMonitorIdentityOrMethodsExist() throws Exception {
        Sink sink = new Sink(); boolean[] allowed = {false}; Computer c = new Computer(8, "left");
        CCDisplayPeripheral p = new CCDisplayPeripheral(sink, () -> true, () -> allowed[0], Runnable::run, () -> {});
        p.attach(c.access);
        assertEquals("bridgecore_display", p.getType()); assertTrue(p.getAdditionalTypes().isEmpty());
        assertFalse(Arrays.asList(p.getMethodNames()).contains("write"));
        assertFalse(Arrays.asList(p.getMethodNames()).contains("getSize"));
        assertThrows(LuaException.class, () -> p.invoke(c.access, "acquire", new ObjectArguments()));
        allowed[0] = true; p.invoke(c.access, "acquire", new ObjectArguments()); assertTrue(p.isActive());
        allowed[0] = false; p.revoke("settings");
        assertThrows(LuaException.class, () -> p.invoke(c.access, "acquire", new ObjectArguments()));
        assertThrows(LuaException.class, () -> p.invoke(c.access, "cellWrite", new ObjectArguments("stale")));
    }
}
