package com.bridgecore.client;

import com.bridgecore.BridgeCoreBlockEntity;
import com.bridgecore.BridgeCoreMod;
import com.bridgecore.GamepadInput;
import com.bridgecore.TypewriterInput;
import com.bridgecore.menu.BridgeCoreMenu;
import com.bridgecore.network.CoreGearMultsPayload;
import com.bridgecore.network.CoreHudCalibPayload;
import com.bridgecore.network.CoreKeyBindPayload;
import com.bridgecore.network.CorePadBindPayload;
import com.bridgecore.network.CoreTogglePayload;
import com.bridgecore.network.CoreTuningsPayload;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Bridge core config screen: five tabs switched by code-drawn header buttons.
 *
 *   Tab 0 状态与开关: left column = read-only status lines (the old chat-bar
 *       snapshot() content, itemized: mode / channels / gear / energy / switches
 *       / ship pos-vel-mass); right column = code-drawn toggle buttons firing
 *       CoreTogglePayload — the server handler calls the SAME toggle methods as
 *       the item interactions. ON state = highlighted background.
 *   Tab 1 推力档位: four gear multipliers (EditBox each, one 应用 button), live
 *       top-speed conversion (TOP_SPEED_BASE x mult) and a current-gear marker.
 *       Client validates 0.05..10 and refuses with red text; the server
 *       re-validates (CoreGearMultsPayload).
 *   Tab 2 按键映射: two sub-sections (顶部切换): 键盘 = 20 channels, click a row
 *       to listen for the next key (Esc cancels; every other key is consumed so
 *       the inventory key cannot close the screen mid-rebind), duplicated
 *       bindings render yellow; 手柄 = 6 axis channels (source selector cycles
 *       leftX/leftY/rightX/rightY/LT/RT/双扳机/肩键对/无 + invert toggle) and 3
 *       button channels (id cycles 0..14), writes via CorePadBindPayload.
 *   Tab 3 HUD 校准: eleven code-drawn sliders, each paired with a manual-input
 *       EditBox (Enter / focus-loss commits, clamped to range). 2026-10-26 v2
 *       orthogonalized semantics: X/Y offset position the panel (Y ±8, around
 *       the fixed anchor at 3.85 above the block), scale sizes it AROUND ITS
 *       CENTER (0.5..2.0 slider; size never moves the panel), eye distance
 *       0.5..6 and eye height ±3 are cockpit-geometry compensation placing the
 *       projection EYE POINT only (they never move the panel); pitch interval
 *       1..15°, attitude-zone size 0.2..1.5, attitude-zone border
 *       0..0.5, lock target range 64..512 blocks (integer); plus an input-line
 *       checkbox and a HUD dark-outline checkbox (CoreTogglePayload switch 7,
 *       menu slot DATA_HUD_OUTLINE). Changes push CoreHudCalibPayload;
 *       the core forwards to all holo displays on the structure.
 *   Tab 4 控制手感: six tuning sliders (attitude KD/KP, transKv, hoverKp,
 *       climbKv, climbRate) with per-row gray description lines and LIVE damping
 *       ratios (zeta_att = KD/2√KP, zeta_hov = climbKv/2√hoverKp, computed from
 *       the pending-aware displayed values): <0.7 yellow 易震荡, 0.7..2.0 green,
 *       >2.0 gray 偏肉. Writes go via CoreTuningsPayload.
 *
 * All layout coordinates live in the constant block; the texture is a plain
 * 300x210 panel (textures/gui/bridge_core.png) since every widget is code-drawn.
 */
public class BridgeCoreScreen extends AbstractContainerScreen<BridgeCoreMenu> {
    private static final ResourceLocation TEXTURE =
            ResourceLocation.fromNamespaceAndPath(BridgeCoreMod.MOD_ID, "textures/gui/bridge_core.png");
    private static final int WINDOW_W = 300;
    private static final int WINDOW_H = 210;

    // ---- tab bar ----
    private static final int TAB_COUNT = 5;
    private static final int TAB_W = 58;
    private static final int TAB_H = 14;
    private static final int TAB_Y = 4;
    private static final int[] TAB_X = {2, 62, 122, 182, 242};
    private static final String[] TAB_LANG = {
            "gui.bridgecore.tab_status", "gui.bridgecore.tab_gears", "gui.bridgecore.tab_keys",
            "gui.bridgecore.tab_hud", "gui.bridgecore.tab_tuning"};
    private static final int TAB_BG = 0xFF2A2F3A;
    private static final int TAB_BG_SEL = 0xFF4A5468;
    private static final int TAB_BORDER = 0xFF16181D;

    // ---- tab 0: status & switches ----
    private static final int ST_Y = 26;
    private static final int ST_PITCH = 10;
    private static final int ST_X = 8;
    private static final int SW_X = 184;
    private static final int SW_Y = 26;
    private static final int SW_W = 108;
    private static final int SW_H = 14;
    private static final int SW_PITCH = 18;
    /** Switch rows in display order -> CoreTogglePayload switch ids. */
    private static final int[] SW_IDS = {
            CoreTogglePayload.SWITCH_MODE, CoreTogglePayload.SWITCH_GRAVITY_COMP,
            CoreTogglePayload.SWITCH_COUPLED,
            CoreTogglePayload.SWITCH_SELF_BALANCE, CoreTogglePayload.SWITCH_ALTITUDE_FOLLOW,
            CoreTogglePayload.SWITCH_KEYBOARD};
    private static final int SW_ON_BG = 0xFF2E4A5E;
    private static final int SW_OFF_BG = 0xFF232830;
    /** ControlMode.STARSHIP ordinal — the self-balance switch is starship-only. */
    private static final int MODE_STARSHIP = BridgeCoreBlockEntity.ControlMode.STARSHIP.ordinal();

    // ---- tab 1: gears ----
    private static final int GEAR_ROW_Y = 34;
    private static final int GEAR_ROW_PITCH = 24;
    private static final int GEAR_BOX_X = 34;
    private static final int GEAR_BOX_W = 64;
    private static final int GEAR_BOX_H = 14;
    private static final int GEAR_TOP_X = 112;
    private static final int GEAR_CURRENT_X = 200;
    private static final int GEAR_HINT_Y = 124;
    private static final int GEAR_APPLY_X = 232;
    private static final int GEAR_APPLY_Y = 186;
    private static final int GEAR_ERROR_Y = 190;

    // ---- tab 1 lower half: the two rotation tunings (Tunings fields 6..7),
    //      same slider grammar as tab 4 (label + value line, slider below,
    //      range/unit gloss on the right) ----
    private static final int ROT_TUNE_0 = 6;
    private static final int ROT_ROW_Y = 138;
    private static final int ROT_ROW_PITCH = 24;
    private static final int ROT_SLIDER_X = 8;
    private static final int ROT_SLIDER_W = 124;
    private static final int ROT_SLIDER_H = 6;
    private static final int ROT_VALUE_X = 138;
    private static final int ROT_DESC_X = 140;
    /** Value spec x100, order attMaxAlpha / rotPriceMult: 1..20 rad/s^2 and
     *  0.2..2.0 unitless. Step = 0.1 (TUNE_STEP100) everywhere. */
    private static final int[] ROT_MIN100 = {100, 20};
    private static final int[] ROT_STEPS = {190, 18};
    private static final String[] ROT_LANG = {"att_max_alpha", "rot_price_mult"};

    // ---- tab 2: key mapping (keyboard / gamepad sub-sections) ----
    private static final int KEYSUB_Y = 24;
    private static final int KEYSUB_W = 60;
    private static final int KEYSUB_H = 12;
    private static final int KEYSUB_KBD_X = 8;
    private static final int KEYSUB_PAD_X = 72;
    private static final int KEY_ROW_Y = 42;
    /** 20 rows (fire added 2026-10-30 落地②): at pitch 9 row 19 would end
     *  past the 210px window bottom; at 8 the last row text sits at y=195+8px. */
    private static final int KEY_ROW_PITCH = 8;
    private static final int KEY_NAME_X = 10;
    private static final int KEY_CODE_X = 130;
    private static final String[] CHANNEL_LANG = {
            "forward", "backward", "left", "right", "up", "down",
            "pitch_up", "pitch_down", "yaw_left", "yaw_right", "roll_left", "roll_right",
            "gear_up", "gear_down", "level", "lock_category", "lock_species",
            "lock_release", "channel_cycle", "fire"};
    private static final int ROW_LISTEN_BG = 0x5535B5E8;
    private static final int GLFW_ESC = 256;
    /** Gamepad sub-section rows: 6 axis channels then 3 button channels. */
    private static final int PAD_ROW_Y = 42;
    private static final int PAD_ROW_PITCH = 18;
    private static final int PAD_NAME_X = 10;
    private static final int PAD_SRC_X = 120;
    private static final int PAD_SRC_W = 110;
    private static final int PAD_INV_X = 236;
    private static final int PAD_INV_W = 56;
    private static final int PAD_BTN_H = 13;
    private static final String[] PAD_AXIS_LANG = {"pitch", "roll", "yaw", "fwd", "vert", "lat"};
    private static final String[] PAD_BTN_LANG = {"level", "gear_up", "gear_down"};
    /** Well-known GLFW-order button names for ids 0..5; others render as 按钮 N. */
    private static final String[] PAD_BTN_NAMES = {"A", "B", "X", "Y", "LB", "RB"};

    // ---- tab 3: HUD calibration (11 params, two columns x six rows + checkbox) ----
    private static final int HUD_PARAM_COUNT = 11;
    private static final int[] HUD_COL_X = {8, 154};
    private static final int HUD_ROW_Y = 26;
    /** 11 params (lock_cone/lock_max, 2026-09-24) made six rows: pitch 30 -> 26
     *  so row 6 (label y=156, box bottom 178) still clears the checkbox at
     *  HUD_CHECK_Y=180. */
    private static final int HUD_ROW_PITCH = 26;
    private static final int HUD_SLIDER_W = 84;
    private static final int HUD_SLIDER_H = 6;
    private static final int HUD_SLIDER_DY = 11;
    private static final int HUD_BOX_DX = 90;
    private static final int HUD_BOX_W = 46;
    private static final int HUD_BOX_H = 14;
    private static final int HUD_BOX_DY = 8;
    private static final int HUD_CHECK_Y = 180;
    /** Second checkbox on the same row: HUD dark-outline toggle. Write goes via
     *  CoreTogglePayload.SWITCH_HUD_OUTLINE; state arrives via the menu's
     *  DATA_HUD_OUTLINE slot (no optimistic flag — same pattern as tab 0). */
    private static final int HUD_CHECK2_X = 152;
    private static final int SLIDER_TRACK = 0xFF1A1E26;
    private static final int SLIDER_FILL = 0xFF35B5E8;
    private static final int SLIDER_HANDLE = 0xFFC7D0DD;
    /** Slider value spec, x100, in param order: offsetX, offsetY, scale, eyeX,
     *  eyeY, pitchStep, attSize, attBorder, lockRange (64..512 blocks, step 16),
     *  lockCone (5..45 deg, step 5), lockMax (1..8, step 1).
     *  2026-10-26 v2: offsetY widened to ±8 (fixed anchor sits at 3.85), eyeY
     *  to ±3 (redefined as seat-height compensation for the projection eye).
     *  Text boxes accept any x100 value in range; sliders quantize to these
     *  steps. */
    private static final int[] HUD_MIN100 = {-200, -800, 50, 50, -300, 100, 20, 0, 6400,
            500, 100};
    private static final int[] HUD_STEPS = {80, 320, 30, 55, 120, 28, 26, 25, 28, 8, 7};
    private static final int[] HUD_STEP100 = {5, 5, 5, 10, 5, 50, 5, 2, 1600, 500, 100};
    private static final String[] HUD_LANG = {"x", "y", "scale", "eye_x", "eye_y",
            "pitch_step", "att_size", "att_border", "lock_range", "lock_cone", "lock_max"};

    private static final int TEXT_COLOR = 0xFFC7D0DD;
    private static final int WARN_COLOR = 0xFFFF8855;
    private static final int ERROR_COLOR = 0xFFFF5555;
    private static final int DESC_COLOR = 0xFF8B94A3;
    private static final int OK_COLOR = 0xFF55FF55;

    // ---- tab 4: control-law tunings ----
    private static final int TUNE_ROW_Y = 26;
    private static final int TUNE_ROW_PITCH = 26;
    private static final int TUNE_SLIDER_X = 8;
    private static final int TUNE_SLIDER_W = 124;
    private static final int TUNE_SLIDER_H = 6;
    private static final int TUNE_VALUE_X = 138;
    private static final int TUNE_DESC_X = 140;
    private static final int TUNE_ZETA_ATT_Y = 184;
    private static final int TUNE_ZETA_HOV_Y = 195;
    /** Lang key suffixes + value ranges, fixed Tunings field order
     *  (kd / kp / transKv / hoverKp / climbKv / climbRate). Step = 0.1 everywhere. */
    private static final String[] TUNE_LANG = {"kd", "kp", "trans_kv", "hover_kp", "climb_kv", "climb_rate"};
    private static final int[] TUNE_MIN100 = {50, 50, 0, 50, 50, 100};
    private static final int[] TUNE_STEPS = {195, 155, 80, 155, 155, 110};
    private static final int TUNE_STEP100 = 10;

    private int tab;
    /** Tab 2 keyboard sub-section: channel index listening for a key, -1 = none. */
    private int listening = -1;
    /** Tab 2: 0 = keyboard sub-section, 1 = gamepad sub-section. */
    private int keysSubTab;
    /** Tab 1: validation error line (null = none). */
    private Component gearError;
    /** Tab 1: user has typed into a gear box — until 应用 succeeds, unfocused boxes
     *  keep their edited text instead of tracking the server broadcast. */
    private boolean gearDirty;
    /** Tab 1: suppresses the dirty flag while containerTick itself writes box text
     *  (EditBox.setValue fires the responder — a programmatic sync is not an edit). */
    private boolean syncingBoxes;
    /** Tab 3: optimistic slider values x100 until ContainerData catches up. */
    private static final int NO_PENDING = Integer.MIN_VALUE;
    private final int[] pendingHud = {NO_PENDING, NO_PENDING, NO_PENDING, NO_PENDING,
            NO_PENDING, NO_PENDING, NO_PENDING, NO_PENDING, NO_PENDING, NO_PENDING,
            NO_PENDING};
    private int hudDragging = -1;
    /** Tab 3: optimistic input-line flag (-1 = none). */
    private int pendingHudInputLine = -1;
    /** Tab 3: manual-input boxes; created by rebuildTabWidgets when tab 3 is shown. */
    private final EditBox[] hudBoxes = new EditBox[HUD_PARAM_COUNT];
    private final boolean[] hudBoxDirty = new boolean[HUD_PARAM_COUNT];
    /** Tabs 1+4: same optimistic pattern for the eight tuning sliders. */
    private final int[] pendingTune = {NO_PENDING, NO_PENDING, NO_PENDING,
            NO_PENDING, NO_PENDING, NO_PENDING, NO_PENDING, NO_PENDING};
    private int tuneDragging = -1;

    public BridgeCoreScreen(BridgeCoreMenu menu, Inventory playerInv, Component title) {
        super(menu, playerInv, title);
        imageWidth = WINDOW_W;
        imageHeight = WINDOW_H;
    }

    @Override
    protected void init() {
        super.init();
        rebuildTabWidgets();
    }

    /** Tab-specific vanilla widgets (tab 1 gear boxes, tab 3 HUD value boxes):
     *  recreated on open / resize / tab switch. Switching tabs re-reads the server
     *  values into the boxes. */
    private void rebuildTabWidgets() {
        clearWidgets();
        java.util.Arrays.fill(hudBoxes, null);
        if (tab == 1) {
            for (int i = 0; i < BridgeCoreBlockEntity.GEAR_COUNT; i++) {
                EditBox box = new EditBox(font, leftPos + GEAR_BOX_X,
                        topPos + GEAR_ROW_Y + i * GEAR_ROW_PITCH, GEAR_BOX_W, GEAR_BOX_H,
                        Component.literal("G" + (i + 1)));
                box.setMaxLength(8);
                box.setFilter(s -> s.matches("[0-9.]*"));
                box.setValue(Double.toString(menu.gearMult(i)));
                box.setResponder(s -> {
                    if (!syncingBoxes) {
                        gearDirty = true;
                    }
                });
                addRenderableWidget(box);
            }
            gearDirty = false; // the setValue calls above are initialization, not edits
            addRenderableWidget(Button.builder(Component.translatable("gui.bridgecore.apply"),
                            b -> applyGears())
                    .bounds(leftPos + GEAR_APPLY_X, topPos + GEAR_APPLY_Y, 60, 16)
                    .build());
        } else if (tab == 3) {
            for (int i = 0; i < HUD_PARAM_COUNT; i++) {
                EditBox box = new EditBox(font,
                        leftPos + HUD_COL_X[i % 2] + HUD_BOX_DX,
                        topPos + HUD_ROW_Y + (i / 2) * HUD_ROW_PITCH + HUD_BOX_DY,
                        HUD_BOX_W, HUD_BOX_H, Component.literal(HUD_LANG[i]));
                box.setMaxLength(8);
                box.setFilter(s -> s.matches("[0-9.\\-]*"));
                box.setValue(hudText(i));
                final int idx = i;
                box.setResponder(s -> {
                    if (!syncingBoxes) {
                        hudBoxDirty[idx] = true;
                    }
                });
                hudBoxes[i] = box;
                addRenderableWidget(box);
            }
            java.util.Arrays.fill(hudBoxDirty, false);
        }
    }

    // ---- tab 1 logic (gears) ----

    /** Unfocused gear boxes track server truth while untouched (also covers the
     *  first-tick gap where ContainerData hasn't arrived yet and the boxes would
     *  otherwise read 0.0); once the user types anywhere, sync freezes until a
     *  successful 应用 so in-progress edits are never clobbered. */
    @Override
    protected void containerTick() {
        super.containerTick();
        if (tab == 1 && !gearDirty) {
            int i = 0;
            syncingBoxes = true;
            for (var child : children()) {
                if (child instanceof EditBox box) {
                    box.setValue(Double.toString(menu.gearMult(i)));
                    i++;
                }
            }
            syncingBoxes = false;
        } else if (tab == 3) {
            // HUD boxes track the (pending-aware) server values unless the user is
            // actively editing that box (focused or uncommitted text).
            syncingBoxes = true;
            for (int i = 0; i < HUD_PARAM_COUNT; i++) {
                EditBox box = hudBoxes[i];
                if (box != null && !box.isFocused() && !hudBoxDirty[i]) {
                    box.setValue(hudText(i));
                }
            }
            syncingBoxes = false;
        }
    }

    private void applyGears() {
        int[] mults = new int[BridgeCoreBlockEntity.GEAR_COUNT];
        int boxIndex = 0;
        for (var child : children()) {
            if (!(child instanceof EditBox box)) {
                continue;
            }
            double v;
            try {
                v = Double.parseDouble(box.getValue());
            } catch (NumberFormatException e) {
                v = Double.NaN;
            }
            if (Double.isNaN(v) || v < BridgeCoreBlockEntity.GEAR_MULT_MIN
                    || v > BridgeCoreBlockEntity.GEAR_MULT_MAX) {
                gearError = Component.translatable("gui.bridgecore.gear_invalid", boxIndex + 1);
                return;
            }
            mults[boxIndex++] = (int) Math.round(v * 1000.0);
        }
        gearError = null;
        gearDirty = false; // resync boxes to the server-confirmed values
        PacketDistributor.sendToServer(new CoreGearMultsPayload(menu.corePos(),
                mults[0], mults[1], mults[2], mults[3]));
    }

    // ---- tab 3 logic (HUD calibration sliders + boxes + checkbox) ----

    private int displayedHud(int slider) {
        int menuValue = switch (slider) {
            case 0 -> menu.hudX100();
            case 1 -> menu.hudY100();
            case 2 -> menu.hudScale100();
            case 3 -> menu.hudEyeX100();
            case 4 -> menu.hudEyeY100();
            case 5 -> menu.hudPitchStep100();
            case 6 -> menu.hudAttSize100();
            case 7 -> menu.hudAttBorder100();
            case 8 -> menu.hudLockRange100();
            case 9 -> menu.hudLockCone100();
            default -> menu.hudLockMax100();
        };
        if (pendingHud[slider] != NO_PENDING) {
            if (menuValue == pendingHud[slider]) {
                pendingHud[slider] = NO_PENDING; // server confirmed
            } else {
                return pendingHud[slider];
            }
        }
        return menuValue;
    }

    private boolean displayedInputLine() {
        boolean menuValue = menu.hudInputLine();
        if (pendingHudInputLine >= 0) {
            if ((menuValue ? 1 : 0) == pendingHudInputLine) {
                pendingHudInputLine = -1; // server confirmed
            } else {
                return pendingHudInputLine != 0;
            }
        }
        return menuValue;
    }

    /** Full-state write: every change sends all twelve values (x100 ints + flag). */
    private void sendHudCalib(boolean inputLine) {
        PacketDistributor.sendToServer(new CoreHudCalibPayload(menu.corePos(),
                displayedHud(0), displayedHud(1), displayedHud(2), displayedHud(3),
                displayedHud(4), displayedHud(5), displayedHud(6), displayedHud(7),
                inputLine ? 1 : 0, displayedHud(8), displayedHud(9), displayedHud(10)));
    }

    private void setHudFromMouse(int slider, double mouseX) {
        double frac = (mouseX - leftPos - HUD_COL_X[slider % 2]) / (double) HUD_SLIDER_W;
        int step = Math.max(0, Math.min(HUD_STEPS[slider],
                (int) Math.round(frac * HUD_STEPS[slider])));
        int value = HUD_MIN100[slider] + step * HUD_STEP100[slider];
        if (value != displayedHud(slider)) {
            pendingHud[slider] = value;
            hudBoxDirty[slider] = false; // box re-syncs to the new value
            sendHudCalib(displayedInputLine());
        }
    }

    private String hudText(int slider) {
        // lock_range/lock_cone/lock_max are integers — "160", not "160.00".
        if (slider >= 8) {
            return Integer.toString(displayedHud(slider) / 100);
        }
        return String.format("%.2f", displayedHud(slider) / 100.0);
    }

    /** Text-box commit (Enter / focus loss): parse, clamp to the param range, send. */
    private void commitHudBox(int i) {
        EditBox box = hudBoxes[i];
        if (box == null || !hudBoxDirty[i]) {
            return;
        }
        hudBoxDirty[i] = false;
        double v;
        try {
            v = Double.parseDouble(box.getValue());
        } catch (NumberFormatException e) {
            box.setValue(hudText(i)); // unparseable: revert to the current value
            return;
        }
        int min = HUD_MIN100[i];
        int max = HUD_MIN100[i] + HUD_STEPS[i] * HUD_STEP100[i];
        // lock_range/lock_cone/lock_max store integers: snap the typed value.
        int raw = i >= 8 ? (int) Math.round(v) * 100 : (int) Math.round(v * 100.0);
        int value = Math.max(min, Math.min(max, raw));
        if (value != displayedHud(i)) {
            pendingHud[i] = value;
            sendHudCalib(displayedInputLine());
        }
        box.setValue(hudText(i));
    }

    private int hudSliderX(int slider) {
        return leftPos + HUD_COL_X[slider % 2];
    }

    private int hudSliderY(int slider) {
        return topPos + HUD_ROW_Y + (slider / 2) * HUD_ROW_PITCH + HUD_SLIDER_DY;
    }

    private boolean overHudSlider(int slider, double mouseX, double mouseY) {
        int sy = hudSliderY(slider);
        return mouseX >= hudSliderX(slider) - 2
                && mouseX <= hudSliderX(slider) + HUD_SLIDER_W + 2
                && mouseY >= sy - 2 && mouseY <= sy + HUD_SLIDER_H + 2;
    }

    // ---- tuning sliders (tab 4: fields 0..5; tab 1 lower half: fields 6..7) ----

    private int tuneMin100(int slider) {
        return slider < ROT_TUNE_0 ? TUNE_MIN100[slider] : ROT_MIN100[slider - ROT_TUNE_0];
    }

    private int tuneSteps(int slider) {
        return slider < ROT_TUNE_0 ? TUNE_STEPS[slider] : ROT_STEPS[slider - ROT_TUNE_0];
    }

    private int displayedTune(int slider) {
        int menuValue = menu.tuning100(slider);
        if (pendingTune[slider] != NO_PENDING) {
            if (menuValue == pendingTune[slider]) {
                pendingTune[slider] = NO_PENDING; // server confirmed
            } else {
                return pendingTune[slider];
            }
        }
        return menuValue;
    }

    /** Full-state write: every change sends all eight values (x100 ints). */
    private void sendTunings() {
        PacketDistributor.sendToServer(new CoreTuningsPayload(menu.corePos(),
                displayedTune(0), displayedTune(1), displayedTune(2), displayedTune(3),
                displayedTune(4), displayedTune(5), displayedTune(6), displayedTune(7)));
    }

    private void setTuneFromMouse(int slider, double mouseX, int sliderX, int sliderW) {
        double frac = (mouseX - leftPos - sliderX) / (double) sliderW;
        int step = Math.max(0, Math.min(tuneSteps(slider),
                (int) Math.round(frac * tuneSteps(slider))));
        int value = tuneMin100(slider) + step * TUNE_STEP100;
        if (value != displayedTune(slider)) {
            pendingTune[slider] = value;
            sendTunings();
        }
    }

    private int tuneSliderY(int slider) {
        return topPos + TUNE_ROW_Y + slider * TUNE_ROW_PITCH + 11;
    }

    private boolean overTuneSlider(int slider, double mouseX, double mouseY) {
        int sy = tuneSliderY(slider);
        return mouseX >= leftPos + TUNE_SLIDER_X - 2
                && mouseX <= leftPos + TUNE_SLIDER_X + TUNE_SLIDER_W + 2
                && mouseY >= sy - 2 && mouseY <= sy + TUNE_SLIDER_H + 2;
    }

    /** Tab 1 rotation-tuning row (j = 0 attMaxAlpha, 1 rotPriceMult). */
    private int rotSliderY(int j) {
        return topPos + ROT_ROW_Y + j * ROT_ROW_PITCH + 11;
    }

    private boolean overRotSlider(int j, double mouseX, double mouseY) {
        int sy = rotSliderY(j);
        return mouseX >= leftPos + ROT_SLIDER_X - 2
                && mouseX <= leftPos + ROT_SLIDER_X + ROT_SLIDER_W + 2
                && mouseY >= sy - 2 && mouseY <= sy + ROT_SLIDER_H + 2;
    }

    // ---- input ----

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0) {
            // Tab bar.
            for (int i = 0; i < TAB_COUNT; i++) {
                if (mouseX >= leftPos + TAB_X[i] && mouseX < leftPos + TAB_X[i] + TAB_W
                        && mouseY >= topPos + TAB_Y && mouseY < topPos + TAB_Y + TAB_H) {
                    if (tab != i) {
                        tab = i;
                        listening = -1;
                        gearError = null;
                        gearDirty = false;
                        rebuildTabWidgets();
                    }
                    return true;
                }
            }
            if (tab == 0) {
                for (int i = 0; i < SW_IDS.length; i++) {
                    if (mouseX >= leftPos + SW_X && mouseX < leftPos + SW_X + SW_W
                            && mouseY >= topPos + SW_Y + i * SW_PITCH
                            && mouseY < topPos + SW_Y + i * SW_PITCH + SW_H) {
                        if (!switchEnabled(SW_IDS[i])) {
                            return true; // grayed out (self-balance outside STARSHIP)
                        }
                        PacketDistributor.sendToServer(
                                new CoreTogglePayload(menu.corePos(), SW_IDS[i]));
                        return true;
                    }
                }
            } else if (tab == 2) {
                // Sub-section toggle.
                if (mouseY >= topPos + KEYSUB_Y && mouseY < topPos + KEYSUB_Y + KEYSUB_H) {
                    if (mouseX >= leftPos + KEYSUB_KBD_X && mouseX < leftPos + KEYSUB_KBD_X + KEYSUB_W) {
                        keysSubTab = 0;
                        listening = -1;
                        return true;
                    }
                    if (mouseX >= leftPos + KEYSUB_PAD_X && mouseX < leftPos + KEYSUB_PAD_X + KEYSUB_W) {
                        keysSubTab = 1;
                        listening = -1;
                        return true;
                    }
                }
                if (keysSubTab == 0) {
                    int row = (int) (mouseY - topPos - KEY_ROW_Y) / KEY_ROW_PITCH;
                    if (row >= 0 && row < TypewriterInput.CHANNEL_COUNT
                            && mouseX >= leftPos && mouseX < leftPos + WINDOW_W) {
                        listening = row;
                        return true;
                    }
                    listening = -1;
                } else {
                    int row = (int) (mouseY - topPos - PAD_ROW_Y) / PAD_ROW_PITCH;
                    if (row < 0 || row >= GamepadInput.AXIS_CHANNEL_COUNT + GamepadInput.BTN_CHANNEL_COUNT
                            || mouseX < leftPos) {
                        return super.mouseClicked(mouseX, mouseY, button);
                    }
                    if (row < GamepadInput.AXIS_CHANNEL_COUNT) {
                        int src = menu.padMap(row * 2);
                        int inv = menu.padMap(row * 2 + 1);
                        if (mouseX >= leftPos + PAD_SRC_X && mouseX < leftPos + PAD_SRC_X + PAD_SRC_W) {
                            PacketDistributor.sendToServer(new CorePadBindPayload(menu.corePos(),
                                    CorePadBindPayload.KIND_AXIS, row,
                                    (src + 1) % GamepadInput.SRC_COUNT, inv));
                            return true;
                        }
                        if (mouseX >= leftPos + PAD_INV_X && mouseX < leftPos + PAD_INV_X + PAD_INV_W) {
                            PacketDistributor.sendToServer(new CorePadBindPayload(menu.corePos(),
                                    CorePadBindPayload.KIND_AXIS, row, src, inv == 0 ? 1 : 0));
                            return true;
                        }
                    } else {
                        int ch = row - GamepadInput.AXIS_CHANNEL_COUNT;
                        if (mouseX >= leftPos + PAD_SRC_X && mouseX < leftPos + PAD_SRC_X + PAD_SRC_W) {
                            int cur = menu.padMap(GamepadInput.AXIS_CHANNEL_COUNT * 2 + ch);
                            PacketDistributor.sendToServer(new CorePadBindPayload(menu.corePos(),
                                    CorePadBindPayload.KIND_BUTTON, ch,
                                    (cur + 1) % (GamepadInput.MAX_BUTTON_ID + 1), 0));
                            return true;
                        }
                    }
                }
            } else if (tab == 3) {
                // Focus-loss commit: clicking outside the focused box applies its text.
                for (int i = 0; i < HUD_PARAM_COUNT; i++) {
                    EditBox box = hudBoxes[i];
                    if (box != null && box.isFocused()
                            && !(mouseX >= box.getX() && mouseX < box.getX() + HUD_BOX_W
                            && mouseY >= box.getY() && mouseY < box.getY() + HUD_BOX_H)) {
                        commitHudBox(i);
                    }
                }
                // Input-line checkbox.
                if (mouseX >= leftPos + 8 && mouseX < leftPos + 140
                        && mouseY >= topPos + HUD_CHECK_Y && mouseY < topPos + HUD_CHECK_Y + 12) {
                    boolean next = !displayedInputLine();
                    pendingHudInputLine = next ? 1 : 0;
                    sendHudCalib(next);
                    return true;
                }
                // HUD dark-outline checkbox (CoreTogglePayload switch 7).
                if (mouseX >= leftPos + HUD_CHECK2_X && mouseX < leftPos + HUD_CHECK2_X + 140
                        && mouseY >= topPos + HUD_CHECK_Y && mouseY < topPos + HUD_CHECK_Y + 12) {
                    PacketDistributor.sendToServer(new CoreTogglePayload(menu.corePos(),
                            CoreTogglePayload.SWITCH_HUD_OUTLINE));
                    return true;
                }
                for (int i = 0; i < HUD_PARAM_COUNT; i++) {
                    if (overHudSlider(i, mouseX, mouseY)) {
                        hudDragging = i;
                        setHudFromMouse(i, mouseX);
                        return true;
                    }
                }
            } else if (tab == 1) {
                // Rotation tunings (Tunings fields 6..7) live under the gear rows.
                for (int j = 0; j < ROT_LANG.length; j++) {
                    if (overRotSlider(j, mouseX, mouseY)) {
                        tuneDragging = ROT_TUNE_0 + j;
                        setTuneFromMouse(tuneDragging, mouseX, ROT_SLIDER_X, ROT_SLIDER_W);
                        return true;
                    }
                }
            } else if (tab == 4) {
                for (int i = 0; i < TUNE_LANG.length; i++) {
                    if (overTuneSlider(i, mouseX, mouseY)) {
                        tuneDragging = i;
                        setTuneFromMouse(i, mouseX, TUNE_SLIDER_X, TUNE_SLIDER_W);
                        return true;
                    }
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (hudDragging >= 0) {
            setHudFromMouse(hudDragging, mouseX);
            return true;
        }
        if (tuneDragging >= 0) {
            if (tuneDragging >= ROT_TUNE_0) {
                setTuneFromMouse(tuneDragging, mouseX, ROT_SLIDER_X, ROT_SLIDER_W);
            } else {
                setTuneFromMouse(tuneDragging, mouseX, TUNE_SLIDER_X, TUNE_SLIDER_W);
            }
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        hudDragging = -1;
        tuneDragging = -1;
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (listening >= 0) {
            // Consume EVERY key while listening (E included — it must not close the
            // screen); Esc cancels, anything else becomes the new binding.
            if (keyCode != GLFW_ESC) {
                PacketDistributor.sendToServer(
                        new CoreKeyBindPayload(menu.corePos(), listening, keyCode));
            }
            listening = -1;
            return true;
        }
        // Tab 3: Enter commits the focused HUD value box.
        if (tab == 3 && (keyCode == 257 || keyCode == 335)) {
            for (int i = 0; i < HUD_PARAM_COUNT; i++) {
                if (hudBoxes[i] != null && hudBoxes[i].isFocused()) {
                    commitHudBox(i);
                    hudBoxes[i].setFocused(false);
                    return true;
                }
            }
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    // ---- rendering ----

    @Override
    protected void renderBg(GuiGraphics guiGraphics, float partialTick, int mouseX, int mouseY) {
        guiGraphics.blit(TEXTURE, leftPos, topPos, 0, 0, WINDOW_W, WINDOW_H, 512, 256);
        // Tab bar.
        for (int i = 0; i < TAB_COUNT; i++) {
            int x = leftPos + TAB_X[i];
            int y = topPos + TAB_Y;
            guiGraphics.fill(x, y, x + TAB_W, y + TAB_H, TAB_BORDER);
            guiGraphics.fill(x + 1, y + 1, x + TAB_W - 1, y + TAB_H - 1,
                    i == tab ? TAB_BG_SEL : TAB_BG);
        }
        if (tab == 0) {
            // Switch buttons: ON = highlighted, OFF = dim, unavailable = dim + no highlight.
            boolean[] on = switchStates();
            for (int i = 0; i < SW_IDS.length; i++) {
                int x = leftPos + SW_X;
                int y = topPos + SW_Y + i * SW_PITCH;
                guiGraphics.fill(x, y, x + SW_W, y + SW_H, TAB_BORDER);
                guiGraphics.fill(x + 1, y + 1, x + SW_W - 1, y + SW_H - 1,
                        on[i] && switchEnabled(SW_IDS[i]) ? SW_ON_BG : SW_OFF_BG);
            }
        }
        if (tab == 2) {
            // Sub-section toggle buttons.
            int[] subX = {KEYSUB_KBD_X, KEYSUB_PAD_X};
            for (int i = 0; i < 2; i++) {
                int x = leftPos + subX[i];
                int y = topPos + KEYSUB_Y;
                guiGraphics.fill(x, y, x + KEYSUB_W, y + KEYSUB_H, TAB_BORDER);
                guiGraphics.fill(x + 1, y + 1, x + KEYSUB_W - 1, y + KEYSUB_H - 1,
                        i == keysSubTab ? TAB_BG_SEL : TAB_BG);
            }
            if (keysSubTab == 0 && listening >= 0) {
                guiGraphics.fill(leftPos + 4, topPos + KEY_ROW_Y + listening * KEY_ROW_PITCH - 1,
                        leftPos + WINDOW_W - 4,
                        topPos + KEY_ROW_Y + listening * KEY_ROW_PITCH + KEY_ROW_PITCH - 1,
                        ROW_LISTEN_BG);
            }
            if (keysSubTab == 1) {
                // Gamepad selector buttons (source / invert / button id).
                for (int row = 0;
                        row < GamepadInput.AXIS_CHANNEL_COUNT + GamepadInput.BTN_CHANNEL_COUNT; row++) {
                    int y = topPos + PAD_ROW_Y + row * PAD_ROW_PITCH;
                    int x = leftPos + PAD_SRC_X;
                    guiGraphics.fill(x, y, x + PAD_SRC_W, y + PAD_BTN_H, TAB_BORDER);
                    guiGraphics.fill(x + 1, y + 1, x + PAD_SRC_W - 1, y + PAD_BTN_H - 1, TAB_BG);
                    if (row < GamepadInput.AXIS_CHANNEL_COUNT) {
                        int xi = leftPos + PAD_INV_X;
                        guiGraphics.fill(xi, y, xi + PAD_INV_W, y + PAD_BTN_H, TAB_BORDER);
                        guiGraphics.fill(xi + 1, y + 1, xi + PAD_INV_W - 1, y + PAD_BTN_H - 1,
                                menu.padMap(row * 2 + 1) != 0 ? SW_ON_BG : TAB_BG);
                    }
                }
            }
        }
        if (tab == 3) {
            for (int i = 0; i < HUD_PARAM_COUNT; i++) {
                int sx = hudSliderX(i);
                int sy = hudSliderY(i);
                int value = displayedHud(i);
                int fracW = (value - HUD_MIN100[i]) * HUD_SLIDER_W
                        / (HUD_STEPS[i] * HUD_STEP100[i]);
                guiGraphics.fill(sx - 1, sy - 1, sx + HUD_SLIDER_W + 1, sy + HUD_SLIDER_H + 1,
                        SLIDER_TRACK);
                guiGraphics.fill(sx, sy, sx + fracW, sy + HUD_SLIDER_H, SLIDER_FILL);
                guiGraphics.fill(sx + fracW - 1, sy - 2, sx + fracW + 2, sy + HUD_SLIDER_H + 2,
                        SLIDER_HANDLE);
            }
            // Input-line checkbox.
            int cy = topPos + HUD_CHECK_Y;
            guiGraphics.fill(leftPos + 8, cy, leftPos + 20, cy + 12, TAB_BORDER);
            guiGraphics.fill(leftPos + 9, cy + 1, leftPos + 19, cy + 11,
                    displayedInputLine() ? SW_ON_BG : SW_OFF_BG);
            // HUD dark-outline checkbox (state via menu.hudOutline()).
            guiGraphics.fill(leftPos + HUD_CHECK2_X, cy, leftPos + HUD_CHECK2_X + 12, cy + 12,
                    TAB_BORDER);
            guiGraphics.fill(leftPos + HUD_CHECK2_X + 1, cy + 1, leftPos + HUD_CHECK2_X + 11,
                    cy + 11, menu.hudOutline() ? SW_ON_BG : SW_OFF_BG);
        }
        if (tab == 1) {
            // Rotation tuning sliders (Tunings fields 6..7), same look as tab 4.
            for (int j = 0; j < ROT_LANG.length; j++) {
                int sx = leftPos + ROT_SLIDER_X;
                int sy = rotSliderY(j);
                int value = displayedTune(ROT_TUNE_0 + j);
                int fracW = (value - ROT_MIN100[j]) * ROT_SLIDER_W
                        / (ROT_STEPS[j] * TUNE_STEP100);
                guiGraphics.fill(sx - 1, sy - 1, sx + ROT_SLIDER_W + 1, sy + ROT_SLIDER_H + 1,
                        SLIDER_TRACK);
                guiGraphics.fill(sx, sy, sx + fracW, sy + ROT_SLIDER_H, SLIDER_FILL);
                guiGraphics.fill(sx + fracW - 1, sy - 2, sx + fracW + 2, sy + ROT_SLIDER_H + 2,
                        SLIDER_HANDLE);
            }
        }
        if (tab == 4) {
            for (int i = 0; i < TUNE_LANG.length; i++) {
                int sx = leftPos + TUNE_SLIDER_X;
                int sy = tuneSliderY(i);
                int value = displayedTune(i);
                int fracW = (value - TUNE_MIN100[i]) * TUNE_SLIDER_W
                        / (TUNE_STEPS[i] * TUNE_STEP100);
                guiGraphics.fill(sx - 1, sy - 1, sx + TUNE_SLIDER_W + 1, sy + TUNE_SLIDER_H + 1,
                        SLIDER_TRACK);
                guiGraphics.fill(sx, sy, sx + fracW, sy + TUNE_SLIDER_H, SLIDER_FILL);
                guiGraphics.fill(sx + fracW - 1, sy - 2, sx + fracW + 2, sy + TUNE_SLIDER_H + 2,
                        SLIDER_HANDLE);
            }
        }
    }

    @Override
    protected void renderLabels(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        for (int i = 0; i < TAB_COUNT; i++) {
            Component name = Component.translatable(TAB_LANG[i]);
            guiGraphics.drawString(font, name,
                    TAB_X[i] + (TAB_W - font.width(name)) / 2, TAB_Y + 3, TEXT_COLOR, false);
        }
        switch (tab) {
            case 0 -> renderStatusLabels(guiGraphics);
            case 1 -> renderGearLabels(guiGraphics);
            case 2 -> renderKeyLabels(guiGraphics);
            case 3 -> renderHudLabels(guiGraphics);
            case 4 -> renderTuneLabels(guiGraphics);
            default -> {
            }
        }
    }

    // ---- tab 0: status & switches ----

    /** ON/OFF highlight per switch row, in SW_IDS order. */
    private boolean[] switchStates() {
        return new boolean[]{
                menu.modeOrdinal() != 0, // mode: OFF counts as "off", any active mode as "on"
                menu.gravityComp(),
                menu.coupled(),
                menu.selfBalance(),
                menu.altitudeFollow(),
                menu.keyboardChannel()};
    }

    /** Whether a switch row accepts clicks right now (self-balance is starship-only). */
    private boolean switchEnabled(int switchId) {
        return switchId != CoreTogglePayload.SWITCH_SELF_BALANCE
                || menu.modeOrdinal() == MODE_STARSHIP;
    }

    private Component onOff(boolean on) {
        return Component.translatable(on ? "gui.bridgecore.on" : "gui.bridgecore.off");
    }

    private Component modeName(int ordinal) {
        String[] keys = {"off", "damping", "starship"};
        return Component.translatable("gui.bridgecore.mode."
                + keys[Math.max(0, Math.min(keys.length - 1, ordinal))]);
    }

    private void renderStatusLabels(GuiGraphics guiGraphics) {
        // Status lines (left column) — the old chat-bar snapshot(), itemized.
        int y = ST_Y;
        guiGraphics.drawString(font, Component.translatable("gui.bridgecore.st.mode",
                modeName(menu.modeOrdinal())), ST_X, y, TEXT_COLOR, false);
        y += ST_PITCH;
        guiGraphics.drawString(font, Component.translatable("gui.bridgecore.st.keyboard",
                onOff(menu.keyboardChannel())), ST_X, y, TEXT_COLOR, false);
        y += ST_PITCH;
        guiGraphics.drawString(font, Component.translatable("gui.bridgecore.st.gamepad",
                Component.translatable(menu.gamepadLinked()
                        ? "gui.bridgecore.linked" : "gui.bridgecore.unlinked")),
                ST_X, y, menu.gamepadLinked() ? OK_COLOR : DESC_COLOR, false);
        y += ST_PITCH;
        int g = menu.currentGear();
        guiGraphics.drawString(font, Component.translatable("gui.bridgecore.st.gear",
                "G" + (g + 1), String.format("%.2f", menu.gearMult(g))), ST_X, y, TEXT_COLOR, false);
        y += ST_PITCH;
        guiGraphics.drawString(font, menu.poolCapKfe() > 0
                        ? Component.translatable("gui.bridgecore.st.pool",
                                String.format("%,d", menu.poolTotalKfe()),
                                String.format("%,d", menu.poolCapKfe()))
                        : Component.translatable("gui.bridgecore.st.pool_none"),
                ST_X, y, menu.poolCapKfe() > 0 ? TEXT_COLOR : WARN_COLOR, false);
        y += ST_PITCH;
        guiGraphics.drawString(font, Component.translatable("gui.bridgecore.st.coupled",
                Component.translatable(menu.coupled()
                        ? "gui.bridgecore.coupled" : "gui.bridgecore.decoupled")),
                ST_X, y, TEXT_COLOR, false);
        y += ST_PITCH;
        // The three remaining switch states, so tab 0 shows every switch at a glance.
        guiGraphics.drawString(font, Component.translatable("gui.bridgecore.st.gravity_comp",
                onOff(menu.gravityComp())), ST_X, y, TEXT_COLOR, false);
        y += ST_PITCH;
        guiGraphics.drawString(font, Component.translatable("gui.bridgecore.st.self_balance",
                onOff(menu.selfBalance())), ST_X, y,
                menu.modeOrdinal() == MODE_STARSHIP ? TEXT_COLOR : DESC_COLOR, false);
        y += ST_PITCH;
        guiGraphics.drawString(font, Component.translatable("gui.bridgecore.st.alt_follow",
                onOff(menu.altitudeFollow())), ST_X, y, TEXT_COLOR, false);
        y += ST_PITCH;
        guiGraphics.drawString(font, Component.translatable("gui.bridgecore.st.pos",
                menu.physicsAlive()
                        ? String.format("(%d, %d, %d)", menu.posX(), menu.posY(), menu.posZ()) : "—"),
                ST_X, y, TEXT_COLOR, false);
        y += ST_PITCH;
        guiGraphics.drawString(font, Component.translatable("gui.bridgecore.st.vel",
                menu.physicsAlive()
                        ? String.format("(%.1f, %.1f, %.1f)", menu.velX(), menu.velY(), menu.velZ())
                        : "—"), ST_X, y, TEXT_COLOR, false);
        y += ST_PITCH;
        guiGraphics.drawString(font, Component.translatable("gui.bridgecore.st.mass",
                menu.physicsAlive() ? String.format("%,d", menu.massKg()) : "—"),
                ST_X, y, TEXT_COLOR, false);

        // Switch buttons (right column): label = current state, click toggles.
        Component[] labels = {
                Component.translatable("gui.bridgecore.sw.mode", modeName(menu.modeOrdinal())),
                Component.translatable("gui.bridgecore.sw.gravity_comp", onOff(menu.gravityComp())),
                Component.translatable("gui.bridgecore.sw.coupled",
                        Component.translatable(menu.coupled()
                                ? "gui.bridgecore.coupled" : "gui.bridgecore.decoupled")),
                Component.translatable("gui.bridgecore.sw.self_balance", onOff(menu.selfBalance())),
                Component.translatable("gui.bridgecore.sw.alt_follow", onOff(menu.altitudeFollow())),
                Component.translatable("gui.bridgecore.sw.keyboard", onOff(menu.keyboardChannel()))};
        for (int i = 0; i < SW_IDS.length; i++) {
            Component label = labels[i];
            guiGraphics.drawString(font, label,
                    SW_X + (SW_W - font.width(label)) / 2, SW_Y + i * SW_PITCH + 3,
                    switchEnabled(SW_IDS[i]) ? TEXT_COLOR : DESC_COLOR, false);
        }
    }

    private void renderGearLabels(GuiGraphics guiGraphics) {
        for (int i = 0; i < BridgeCoreBlockEntity.GEAR_COUNT; i++) {
            int y = GEAR_ROW_Y + i * GEAR_ROW_PITCH + 3;
            guiGraphics.drawString(font, "G" + (i + 1), 10, y, TEXT_COLOR, false);
            guiGraphics.drawString(font, Component.translatable("gui.bridgecore.gear_top",
                            String.format("%.1f", BridgeCoreBlockEntity.TOP_SPEED_BASE * menu.gearMult(i))),
                    GEAR_TOP_X, y, TEXT_COLOR, false);
            if (menu.currentGear() == i) {
                guiGraphics.drawString(font, Component.translatable("gui.bridgecore.gear_current"),
                        GEAR_CURRENT_X, y, TEXT_COLOR, false);
            }
        }
        guiGraphics.drawString(font, Component.translatable("gui.bridgecore.gear_range_hint"),
                8, GEAR_HINT_Y, TEXT_COLOR, false);
        // Rotation tunings (Tunings fields 6..7): label + value line, range/unit
        // gloss on the right — same grammar as the tab 4 rows.
        for (int j = 0; j < ROT_LANG.length; j++) {
            int y = ROT_ROW_Y + j * ROT_ROW_PITCH;
            guiGraphics.drawString(font,
                    Component.translatable("gui.bridgecore.tune." + ROT_LANG[j]),
                    ROT_SLIDER_X, y, TEXT_COLOR, false);
            guiGraphics.drawString(font,
                    String.format("%.1f", displayedTune(ROT_TUNE_0 + j) / 100.0),
                    ROT_VALUE_X, y, TEXT_COLOR, false);
            guiGraphics.drawString(font,
                    Component.translatable("gui.bridgecore.tune." + ROT_LANG[j] + "_desc"),
                    ROT_DESC_X, y + 12, DESC_COLOR, false);
        }
        if (gearError != null) {
            guiGraphics.drawString(font, gearError, 8, GEAR_ERROR_Y, ERROR_COLOR, false);
        }
    }

    private void renderKeyLabels(GuiGraphics guiGraphics) {
        // Sub-section toggle labels.
        for (int i = 0; i < 2; i++) {
            Component name = Component.translatable(i == 0
                    ? "gui.bridgecore.sub_keyboard" : "gui.bridgecore.sub_gamepad");
            int x = (i == 0 ? KEYSUB_KBD_X : KEYSUB_PAD_X);
            guiGraphics.drawString(font, name,
                    x + (KEYSUB_W - font.width(name)) / 2, KEYSUB_Y + 2, TEXT_COLOR, false);
        }
        if (keysSubTab == 0) {
            java.util.Map<Integer, Integer> counts = new java.util.HashMap<>();
            for (int ch = 0; ch < TypewriterInput.CHANNEL_COUNT; ch++) {
                int code = menu.keyCode(ch);
                if (code >= 0) {
                    counts.merge(code, 1, Integer::sum);
                }
            }
            for (int ch = 0; ch < TypewriterInput.CHANNEL_COUNT; ch++) {
                int y = KEY_ROW_Y + ch * KEY_ROW_PITCH + 1;
                guiGraphics.drawString(font,
                        Component.translatable("gui.bridgecore.key." + CHANNEL_LANG[ch]),
                        KEY_NAME_X, y, TEXT_COLOR, false);
                int code = menu.keyCode(ch);
                Component keyName = ch == listening
                        ? Component.translatable("gui.bridgecore.key_listening")
                        : com.mojang.blaze3d.platform.InputConstants.getKey(code, 0).getDisplayName();
                boolean conflict = code >= 0 && counts.getOrDefault(code, 0) > 1;
                guiGraphics.drawString(font, keyName, KEY_CODE_X, y,
                        ch == listening || conflict ? WARN_COLOR : TEXT_COLOR, false);
            }
        } else {
            for (int row = 0;
                    row < GamepadInput.AXIS_CHANNEL_COUNT + GamepadInput.BTN_CHANNEL_COUNT; row++) {
                int y = PAD_ROW_Y + row * PAD_ROW_PITCH + 3;
                boolean isAxis = row < GamepadInput.AXIS_CHANNEL_COUNT;
                Component name = Component.translatable("gui.bridgecore.pad."
                        + (isAxis ? PAD_AXIS_LANG[row] : PAD_BTN_LANG[row - GamepadInput.AXIS_CHANNEL_COUNT]));
                guiGraphics.drawString(font, name, PAD_NAME_X, y, TEXT_COLOR, false);
                Component selector;
                if (isAxis) {
                    selector = Component.translatable("gui.bridgecore.src." + menu.padMap(row * 2));
                    Component inv = Component.translatable(menu.padMap(row * 2 + 1) != 0
                            ? "gui.bridgecore.inverted" : "gui.bridgecore.normal");
                    guiGraphics.drawString(font, inv,
                            PAD_INV_X + (PAD_INV_W - font.width(inv)) / 2, y, TEXT_COLOR, false);
                } else {
                    int btn = menu.padMap(GamepadInput.AXIS_CHANNEL_COUNT * 2
                            + row - GamepadInput.AXIS_CHANNEL_COUNT);
                    selector = btn < PAD_BTN_NAMES.length
                            ? Component.literal(PAD_BTN_NAMES[btn])
                            : Component.translatable("gui.bridgecore.padbtn_other", btn);
                }
                guiGraphics.drawString(font, selector,
                        PAD_SRC_X + (PAD_SRC_W - font.width(selector)) / 2, y, TEXT_COLOR, false);
            }
        }
    }

    private void renderHudLabels(GuiGraphics guiGraphics) {
        for (int i = 0; i < HUD_PARAM_COUNT; i++) {
            int labelY = HUD_ROW_Y + (i / 2) * HUD_ROW_PITCH;
            guiGraphics.drawString(font,
                    Component.translatable("gui.bridgecore.hud_" + HUD_LANG[i]),
                    HUD_COL_X[i % 2], labelY, TEXT_COLOR, false);
        }
        guiGraphics.drawString(font,
                Component.translatable("gui.bridgecore.hud_input_line",
                        onOff(displayedInputLine())),
                26, HUD_CHECK_Y + 2, TEXT_COLOR, false);
        guiGraphics.drawString(font,
                Component.translatable("gui.bridgecore.hud_outline",
                        onOff(menu.hudOutline())),
                HUD_CHECK2_X + 18, HUD_CHECK_Y + 2, TEXT_COLOR, false);
        guiGraphics.drawString(font, Component.translatable("gui.bridgecore.hud_hint"),
                8, HUD_CHECK_Y + 16, DESC_COLOR, false);
    }

    private void renderTuneLabels(GuiGraphics guiGraphics) {
        for (int i = 0; i < TUNE_LANG.length; i++) {
            int y = TUNE_ROW_Y + i * TUNE_ROW_PITCH;
            guiGraphics.drawString(font,
                    Component.translatable("gui.bridgecore.tune." + TUNE_LANG[i]),
                    TUNE_SLIDER_X, y, TEXT_COLOR, false);
            guiGraphics.drawString(font, String.format("%.1f", displayedTune(i) / 100.0),
                    TUNE_VALUE_X, y, TEXT_COLOR, false);
            guiGraphics.drawString(font,
                    Component.translatable("gui.bridgecore.tune." + TUNE_LANG[i] + "_desc"),
                    TUNE_DESC_X, y + 12, DESC_COLOR, false);
        }
        // Live damping ratios from the DISPLAYED (pending-aware) values:
        //   zeta < 0.7  underdamped (yellow, 易震荡)
        //   0.7..2.0    healthy (green)
        //   > 2.0       overdamped (gray, 偏肉)
        double zetaAtt = displayedTune(0) / (2.0 * Math.sqrt(displayedTune(1)));
        double zetaHov = displayedTune(4) / (2.0 * Math.sqrt(displayedTune(3)));
        drawZeta(guiGraphics, "gui.bridgecore.tune_zeta_att", zetaAtt, TUNE_ZETA_ATT_Y);
        drawZeta(guiGraphics, "gui.bridgecore.tune_zeta_hov", zetaHov, TUNE_ZETA_HOV_Y);
    }

    private void drawZeta(GuiGraphics guiGraphics, String langKey, double zeta, int y) {
        String statusKey = zeta < 0.7 ? "gui.bridgecore.zeta_low"
                : zeta > 2.0 ? "gui.bridgecore.zeta_high" : "gui.bridgecore.zeta_ok";
        int color = zeta < 0.7 ? WARN_COLOR : zeta > 2.0 ? DESC_COLOR : OK_COLOR;
        guiGraphics.drawString(font, Component.translatable(langKey,
                        String.format("%.2f", zeta), Component.translatable(statusKey)),
                8, y, color, false);
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        super.render(guiGraphics, mouseX, mouseY, partialTick);
        renderTooltip(guiGraphics, mouseX, mouseY);
    }
}
