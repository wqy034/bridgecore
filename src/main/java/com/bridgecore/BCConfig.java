package com.bridgecore;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Server-side config (bridgecore-server.toml). First config file in the project —
 * created for the tiled display system; future server tunables go here too.
 */
public final class BCConfig {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    /** Max screen array edge length, in tiles (both axes). */
    public static final ModConfigSpec.IntValue DISPLAY_MAX_SCREEN_SIZE;
    /** Pixels per screen tile edge; read when the GPU (re)connects its array. */
    public static final ModConfigSpec.IntValue DISPLAY_TILE_RESOLUTION;
    /** Hard cap on one GPU's frame buffer (bytes); oversized arrays refuse to connect. */
    public static final ModConfigSpec.IntValue DISPLAY_FRAMEBUFFER_MAX_BYTES;
    /** Periodic array rescan interval (ticks). Events (neighbor changes, tile
     *  removal) cover in-place edits; this catches tiles added at the far end of
     *  the array, where nothing is adjacent to the controller to report it. */
    public static final ModConfigSpec.IntValue DISPLAY_RESCAN_INTERVAL_TICKS;
    /** HUD big-number outline font (2026-10-29 A/B experiment, decided
     *  2026-10-30): RAJDHANI (Medium, default — won the on-screen A/B) vs
     *  BARLOW (SemiCondensed Medium, kept as an option). Server-side
     *  because the canvas renders on the server; applies on world load —
     *  no live switching. */
    public static final ModConfigSpec.EnumValue<HudDigitFont> HUD_DIGIT_FONT;

    /** Selectable digit fonts for the HUD big-number readouts; ordinals must
     *  never be persisted (name-based on the wire/NBT nowhere — config only). */
    public enum HudDigitFont {
        BARLOW,
        RAJDHANI
    }

    /** Presentation only: clean blueprint spacing or the original dense ladder. */
    public static final ModConfigSpec.BooleanValue HUD_CLEAN_LAYOUT;

    public static final ModConfigSpec SPEC;

    static {
        BUILDER.push("display");
        DISPLAY_MAX_SCREEN_SIZE = BUILDER
                .comment("Maximum screen wall size, in tiles per axis")
                .defineInRange("maxScreenSize", 16, 1, 64);
        DISPLAY_TILE_RESOLUTION = BUILDER
                .comment("Default pixels per screen tile; each controller can override this in Display Settings.",
                        "Automatic mode uses at least 256. Dirty tiles use RGB24 run/literal compression.")
                .defineInRange("tileResolution", 256, 16, 512);
        DISPLAY_FRAMEBUFFER_MAX_BYTES = BUILDER
                .comment("Frame buffer size cap per display controller, in bytes")
                .defineInRange("framebufferMaxBytes", 64 * 1024 * 1024, 1024 * 1024, 512 * 1024 * 1024);
        DISPLAY_RESCAN_INTERVAL_TICKS = BUILDER
                .comment("Periodic screen array rescan interval, in ticks (20 = 1 s)")
                .defineInRange("rescanIntervalTicks", 20, 5, 200);
        BUILDER.pop();
        BUILDER.push("hud");
        HUD_CLEAN_LAYOUT = BUILDER
                .comment("Visual preset only; panel/eye calibration and saved attitude values stay intact.",
                        "true: blueprint spacing (5 logical px/degree), ladder interval at least 10 degrees.",
                        "false: original spacing (8 logical px/degree) and configured pitchStep.")
                .define("cleanLayout", true);
        HUD_DIGIT_FONT = BUILDER
                .comment("HUD big-number outline font (A/B decided 2026-10-30):",
                        "RAJDHANI = Rajdhani Medium (default, won the on-screen A/B),",
                        "BARLOW = Barlow SemiCondensed Medium (kept as an option). The HUD",
                        "canvas renders on the server, so this is a server config; takes",
                        "effect on the next world load, no live switching.")
                .defineEnum("digitFont", HudDigitFont.RAJDHANI);
        BUILDER.pop();
        SPEC = BUILDER.build();
    }

    private BCConfig() {
    }
}
