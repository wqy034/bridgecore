package com.bridgecore.display;

import com.bridgecore.firecontrol.FireControlPanelApp;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.function.Supplier;

/**
 * Tiled-screen app registry (2026-09-18, per the shared handbook §2.25 open
 * item — user ruled the GUI selection over the hardcoded right-click cycle).
 * The production editor exposes fire control and off. Render scheduling,
 * persistence and click routing resolve the same registry entries.
 *
 * Only production apps are selectable. Retired test/energy IDs load as off.
 */
public final class DisplayApps {
    public static final String CC_MODE = "cc";
    public static final String OFF = "off";
    public static final String FIRE_CONTROL = "fire_control";

    public record Entry(String id, String nameKey, @Nullable Supplier<DisplayApp> factory) {
    }

    private static final List<Entry> ENTRIES = List.of(
            new Entry(CC_MODE, "gui.bridgecore.display_app.cc", null),
            new Entry(OFF, "gui.bridgecore.display_app.off", null),
            new Entry(FIRE_CONTROL, "gui.bridgecore.display_app.fire_control", FireControlPanelApp::new));

    private DisplayApps() {
    }

    /** Registry order = GUI list order. */
    public static List<Entry> entries() {
        return ENTRIES;
    }

    public static @Nullable Entry get(String id) {
        for (Entry e : ENTRIES) {
            if (e.id().equals(id)) return e;
        }
        return null;
    }

    public static boolean isValid(String id) {
        return get(id) != null;
    }

    /** Live apps (real DisplayApp behind the id) get the 10 Hz re-render;
     *  off do not (black / repaint-on-rescan only). */
    public static boolean isLiveApp(String id) {
        Entry e = get(id);
        return e != null && e.factory() != null;
    }
}
