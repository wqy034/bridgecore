package com.bridgecore.menu;

import com.bridgecore.BCRegistry;
import com.bridgecore.BridgeCoreBlockEntity;
import com.bridgecore.CoreDirectory;
import com.bridgecore.GamepadInput;
import com.bridgecore.MomentumDistributorBlockEntity;
import com.bridgecore.TypewriterInput;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.item.ItemStack;
import org.joml.Vector3d;

/**
 * Bridge core config menu (five tabs: status & switches / gears / key mapping /
 * HUD calibration / control-law tunings).
 * No slots at all — this is a pure config surface; every read-only value rides
 * one ContainerData int array broadcast every tick while the GUI is open, and
 * every write goes C2S via its own payload (CoreGearMultsPayload /
 * CoreKeyBindPayload / CoreHudCalibPayload), ExtractorRatePayload pattern:
 *
 *   0..3  = gear multipliers x1000 (tab 1)
 *   4     = current gear index (tab 1 highlight)
 *   5..19 = channel -> GLFW key code, channels 0..14 (tab 2; -1 = unbound)
 *   76    = keyMap channel 15 (lock_category, added 2026-09-22 — 5..20 would have
 *           collided with DATA_HUD_X at 20, so the 16th entry lives at the tail)
 *   77    = HUD lock target search range x100, blocks (tab 3, 2026-09-23)
 *   78    = keyMap channel 16 (lock_species, 2026-09-23)
 *   79    = HUD multi-lock cone half-angle x100, degrees (tab 3, 2026-09-24)
 *   80    = HUD lock-box count cap x100, incl. primary (tab 3, 2026-09-24)
 *   81    = keyMap channel 17 (lock_release, 2026-09-25)
 *   82    = keyMap channel 18 (channel_cycle, 2026-10-26)
 *   83    = HUD dark-outline switch 0/1 (tab 3, 2026-10-30)
 *   20    = HUD offset X x100, blocks (tab 3)
 *   21    = HUD offset Y x100, blocks
 *   22    = HUD scale x100
 *   62    = HUD eye_offset_x x100, blocks (tab 3)
 *   63    = HUD eye_offset_y x100, blocks
 *   64    = HUD pitch interval x100, degrees
 *   65    = HUD attitude-zone size x100
 *   66    = HUD attitude-zone border x100, fraction 0..0.5
 *   67    = HUD input line switch 0/1
 *   68..75 = control-law tunings x100 (0..5 tab 4, 6..7 tab 1), fixed Tunings
 *            field order: kd / kp / transKv / hoverKp / climbKv / climbRate /
 *            attMaxAlpha / rotPriceMult (v0.14: moved off 23..28 — eight fields
 *            no longer fit below the status block at 29)
 *   29    = control mode ordinal (tab 0; ControlMode order OFF/DAMPING/STARSHIP)
 *   30    = gravity compensation 0/1
 *   31    = redstone face meaning ordinal (0=TORQUE 1=THRUST)
 *   32    = coupled (effective; the self-balance switch forces 1)
 *   33    = self-balance switch 0/1 (starship-only sub-switch)
 *   34    = altitude follow 0/1
 *   35    = keyboard channel 0/1
 *   36    = gamepad linked 0/1
 *   37    = structure distributor pool capacity, kFE (0 = no pool; v0.13 — was the
 *           core buffer FE, removed with the core-local buffer)
 *   38    = same-structure distributor pool total, kFE (0 = no pool; extractor-menu
 *           compression — the N x 4M sum would run out of int headroom raw)
 *   39..53 = gamepadMap, 15 ints (tab 2 gamepad sub-section)
 *   54..56 = ship logical position x/y/z (rounded to int blocks; display only)
 *   57..59 = ship velocity x/y/z, x100 (m/s)
 *   60    = ship mass, kg (clamped to int)
 *   61    = physics alive 0/1 (false -> status tab shows dashes for pos/vel/mass)
 *
 * stillValid does NOT use a distance check (Sable shipyard vs logical
 * coordinates would always fail); removal is the only invalidation.
 */
public class BridgeCoreMenu extends AbstractContainerMenu {
    private static final int DATA_GEAR_MULT_0 = 0;
    private static final int DATA_GEAR = 4;
    private static final int DATA_KEYMAP_0 = 5;
    private static final int DATA_HUD_X = 20;
    private static final int DATA_HUD_Y = 21;
    private static final int DATA_HUD_SCALE = 22;
    private static final int DATA_HUD_EYE_X = 62;
    private static final int DATA_HUD_EYE_Y = 63;
    private static final int DATA_HUD_PITCH_STEP = 64;
    private static final int DATA_HUD_ATT_SIZE = 65;
    private static final int DATA_HUD_ATT_BORDER = 66;
    private static final int DATA_HUD_INPUT_LINE = 67;
    private static final int DATA_TUNING_0 = 68;
    private static final int DATA_MODE = 29;
    private static final int DATA_GRAVITY_COMP = 30;
    // slot 31 was DATA_REDSTONE_MODE — retired 2026-09-18 (core body takes no
    // redstone); never written, reads as 0, numbering above it unchanged.
    private static final int DATA_COUPLED = 32;
    private static final int DATA_SELF_BALANCE = 33;
    private static final int DATA_ALT_FOLLOW = 34;
    private static final int DATA_KEYBOARD = 35;
    private static final int DATA_PAD_LINKED = 36;
    // Slot 37 repurposed v0.13 (was core buffer FE, removed): structure pool capacity, kFE.
    private static final int DATA_POOL_CAP_KFE = 37;
    private static final int DATA_POOL_KFE = 38;
    private static final int DATA_PADMAP_0 = 39;
    private static final int DATA_POS_X = 54;
    private static final int DATA_VEL_X = 57;
    private static final int DATA_MASS = 60;
    private static final int DATA_PHYSICS_ALIVE = 61;
    /** keyMap channel 15 (lock_category); 5..19 hold channels 0..14, see javadoc. */
    private static final int DATA_KEYMAP_LOCK_CYCLE = 76;
    private static final int DATA_HUD_LOCK_RANGE = 77;
    /** keyMap channel 16 (lock_species, 2026-09-23). */
    private static final int DATA_KEYMAP_LOCK_SPECIES = 78;
    private static final int DATA_HUD_LOCK_CONE = 79;
    private static final int DATA_HUD_LOCK_MAX = 80;
    /** keyMap channel 17 (lock_release, 2026-09-25). */
    private static final int DATA_KEYMAP_LOCK_RELEASE = 81;
    /** keyMap channel 18 (channel_cycle, 2026-10-26). */
    private static final int DATA_KEYMAP_CHANNEL_CYCLE = 82;
    private static final int DATA_HUD_OUTLINE = 83;
    /** keyMap channel 19 (fire, 2026-10-30 落地②). */
    private static final int DATA_KEYMAP_FIRE = 84;
    private static final int DATA_COUNT = 85;

    /** keyMap channel -> data slot (channels 0..14 are contiguous at 5, the
     *  lock/channel channels sit at the tail — see the class javadoc). */
    private static int keymapSlot(int channel) {
        return switch (channel) {
            case TypewriterInput.CH_LOCK_CATEGORY -> DATA_KEYMAP_LOCK_CYCLE;
            case TypewriterInput.CH_LOCK_SPECIES -> DATA_KEYMAP_LOCK_SPECIES;
            case TypewriterInput.CH_LOCK_RELEASE -> DATA_KEYMAP_LOCK_RELEASE;
            case TypewriterInput.CH_CHANNEL_CYCLE -> DATA_KEYMAP_CHANNEL_CYCLE;
            case TypewriterInput.CH_FIRE -> DATA_KEYMAP_FIRE;
            default -> DATA_KEYMAP_0 + channel;
        };
    }

    private final BridgeCoreBlockEntity be;
    private final SyncData data = new SyncData();

    /** Server-side constructor. */
    public BridgeCoreMenu(int windowId, Inventory playerInv, BridgeCoreBlockEntity be) {
        super(BCRegistry.BRIDGE_CORE_MENU.get(), windowId);
        this.be = be;
        addDataSlots(data);
    }

    /** Client-side constructor (IMenuTypeExtension factory): extra data = BlockPos. */
    public BridgeCoreMenu(int windowId, Inventory playerInv, FriendlyByteBuf buf) {
        this(windowId, playerInv,
                (BridgeCoreBlockEntity) playerInv.player.level().getBlockEntity(buf.readBlockPos()));
    }

    @Override
    public void broadcastChanges() {
        double[] gm = be.gearMults();
        for (int i = 0; i < BridgeCoreBlockEntity.GEAR_COUNT; i++) {
            data.set(DATA_GEAR_MULT_0 + i, (int) Math.round(gm[i] * 1000.0));
        }
        data.set(DATA_GEAR, be.gear());
        int[] map = be.keyMap();
        for (int i = 0; i < TypewriterInput.CHANNEL_COUNT; i++) {
            data.set(keymapSlot(i), map[i]);
        }
        data.set(DATA_HUD_X, (int) Math.round(be.hudOffsetX() * 100.0));
        data.set(DATA_HUD_Y, (int) Math.round(be.hudOffsetY() * 100.0));
        data.set(DATA_HUD_SCALE, (int) Math.round(be.hudScale() * 100.0));
        data.set(DATA_HUD_EYE_X, (int) Math.round(be.hudEyeX() * 100.0));
        data.set(DATA_HUD_EYE_Y, (int) Math.round(be.hudEyeY() * 100.0));
        data.set(DATA_HUD_PITCH_STEP, (int) Math.round(be.hudPitchStep() * 100.0));
        data.set(DATA_HUD_ATT_SIZE, (int) Math.round(be.hudAttSize() * 100.0));
        data.set(DATA_HUD_ATT_BORDER, (int) Math.round(be.hudAttBorder() * 100.0));
        data.set(DATA_HUD_INPUT_LINE, be.hudInputLine() ? 1 : 0);
        data.set(DATA_HUD_OUTLINE, be.hudOutline() ? 1 : 0);
        data.set(DATA_HUD_LOCK_RANGE, be.hudLockRange() * 100);
        data.set(DATA_HUD_LOCK_CONE, be.hudLockConeDeg() * 100);
        data.set(DATA_HUD_LOCK_MAX, be.hudLockMaxCount() * 100);
        double[] t = be.tunings().toArray();
        for (int i = 0; i < BridgeCoreBlockEntity.Tunings.COUNT; i++) {
            data.set(DATA_TUNING_0 + i, (int) Math.round(t[i] * 100.0));
        }
        // Status tab (tab 0) + gamepad map.
        data.set(DATA_MODE, be.mode().ordinal());
        data.set(DATA_GRAVITY_COMP, be.gravityCompOn() ? 1 : 0);
        data.set(DATA_COUPLED, be.coupledOn() ? 1 : 0);
        data.set(DATA_SELF_BALANCE, be.selfBalanceOn() ? 1 : 0);
        data.set(DATA_ALT_FOLLOW, be.altitudeFollowOn() ? 1 : 0);
        data.set(DATA_KEYBOARD, be.keyboardChannelOn() ? 1 : 0);
        data.set(DATA_PAD_LINKED, be.gamepadLinked() ? 1 : 0);
        ServerSubLevel sub = be.getLevel() != null
                && Sable.HELPER.getContaining(be.getLevel(), be.getBlockPos()) instanceof ServerSubLevel s
                ? s : null;
        long poolTotal = 0;
        long poolCap = 0;
        long mass = 0;
        if (sub != null) {
            for (MomentumDistributorBlockEntity pool : CoreDirectory.powerPoolsOf(sub)) {
                poolTotal += pool.energy();
                poolCap += MomentumDistributorBlockEntity.CAPACITY;
            }
            if (sub.getMassTracker() != null) {
                mass = (long) sub.getMassTracker().getMass();
            }
        }
        data.set(DATA_POOL_CAP_KFE, (int) Math.min(Integer.MAX_VALUE, poolCap / 1000));
        data.set(DATA_POOL_KFE, (int) Math.min(Integer.MAX_VALUE, poolTotal / 1000));
        int[] pad = be.gamepadMap();
        for (int i = 0; i < GamepadInput.MAP_LENGTH; i++) {
            data.set(DATA_PADMAP_0 + i, pad[i]);
        }
        Vector3d p = be.lastLogicalPosition();
        Vector3d v = be.lastVelocity();
        data.set(DATA_PHYSICS_ALIVE, p != null ? 1 : 0);
        data.set(DATA_POS_X, p != null ? (int) Math.round(p.x) : 0);
        data.set(DATA_POS_X + 1, p != null ? (int) Math.round(p.y) : 0);
        data.set(DATA_POS_X + 2, p != null ? (int) Math.round(p.z) : 0);
        data.set(DATA_VEL_X, v != null ? (int) Math.round(v.x * 100.0) : 0);
        data.set(DATA_VEL_X + 1, v != null ? (int) Math.round(v.y * 100.0) : 0);
        data.set(DATA_VEL_X + 2, v != null ? (int) Math.round(v.z * 100.0) : 0);
        data.set(DATA_MASS, (int) Math.min(Integer.MAX_VALUE, mass));
        super.broadcastChanges();
    }

    // ---- client-side accessors for the screen ----

    public double gearMult(int index) {
        return data.get(DATA_GEAR_MULT_0 + index) / 1000.0;
    }

    public int currentGear() {
        return data.get(DATA_GEAR);
    }

    public int keyCode(int channel) {
        return data.get(keymapSlot(channel));
    }

    public int hudX100() {
        return data.get(DATA_HUD_X);
    }

    public int hudY100() {
        return data.get(DATA_HUD_Y);
    }

    public int hudScale100() {
        return data.get(DATA_HUD_SCALE);
    }

    public int hudEyeX100() {
        return data.get(DATA_HUD_EYE_X);
    }

    public int hudEyeY100() {
        return data.get(DATA_HUD_EYE_Y);
    }

    public int hudPitchStep100() {
        return data.get(DATA_HUD_PITCH_STEP);
    }

    public int hudLockRange100() {
        return data.get(DATA_HUD_LOCK_RANGE);
    }

    public int hudLockCone100() {
        return data.get(DATA_HUD_LOCK_CONE);
    }

    public int hudLockMax100() {
        return data.get(DATA_HUD_LOCK_MAX);
    }

    public int hudAttSize100() {
        return data.get(DATA_HUD_ATT_SIZE);
    }

    public int hudAttBorder100() {
        return data.get(DATA_HUD_ATT_BORDER);
    }

    public boolean hudInputLine() {
        return data.get(DATA_HUD_INPUT_LINE) != 0;
    }

    /** Authoritative HUD-outline switch for a future tab-3 button label.
     * Write via CoreTogglePayload.SWITCH_HUD_OUTLINE, not client-side BE state. */
    public boolean hudOutline() {
        return data.get(DATA_HUD_OUTLINE) != 0;
    }

    /** Tuning value x100; index in the fixed Tunings field order (0=kd ... 5=climbRate). */
    public int tuning100(int index) {
        return data.get(DATA_TUNING_0 + index);
    }

    // ---- tab 0 (status & switches) + tab 2 gamepad sub-section accessors ----

    public int modeOrdinal() {
        return data.get(DATA_MODE);
    }

    public boolean gravityComp() {
        return data.get(DATA_GRAVITY_COMP) != 0;
    }

    public boolean coupled() {
        return data.get(DATA_COUPLED) != 0;
    }

    public boolean selfBalance() {
        return data.get(DATA_SELF_BALANCE) != 0;
    }

    public boolean altitudeFollow() {
        return data.get(DATA_ALT_FOLLOW) != 0;
    }

    public boolean keyboardChannel() {
        return data.get(DATA_KEYBOARD) != 0;
    }

    public boolean gamepadLinked() {
        return data.get(DATA_PAD_LINKED) != 0;
    }

    public int poolCapKfe() {
        return data.get(DATA_POOL_CAP_KFE);
    }

    public int poolTotalKfe() {
        return data.get(DATA_POOL_KFE);
    }

    /** Gamepad map value; index per GamepadInput.MAP_LENGTH layout. */
    public int padMap(int index) {
        return data.get(DATA_PADMAP_0 + index);
    }

    public boolean physicsAlive() {
        return data.get(DATA_PHYSICS_ALIVE) != 0;
    }

    public int posX() {
        return data.get(DATA_POS_X);
    }

    public int posY() {
        return data.get(DATA_POS_X + 1);
    }

    public int posZ() {
        return data.get(DATA_POS_X + 2);
    }

    public double velX() {
        return data.get(DATA_VEL_X) / 100.0;
    }

    public double velY() {
        return data.get(DATA_VEL_X + 1) / 100.0;
    }

    public double velZ() {
        return data.get(DATA_VEL_X + 2) / 100.0;
    }

    public int massKg() {
        return data.get(DATA_MASS);
    }

    /** BE position — the screen needs it for the three config payloads. */
    public net.minecraft.core.BlockPos corePos() {
        return be.getBlockPos();
    }

    @Override
    public boolean stillValid(Player player) {
        // No distance check — shipyard vs logical coordinates (see class javadoc).
        return !be.isRemoved();
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        return ItemStack.EMPTY; // no slots
    }

    private static final class SyncData implements ContainerData {
        private final int[] values = new int[DATA_COUNT];

        @Override
        public int get(int index) {
            return values[index];
        }

        @Override
        public void set(int index, int value) {
            values[index] = value;
        }

        @Override
        public int getCount() {
            return DATA_COUNT;
        }
    }
}
