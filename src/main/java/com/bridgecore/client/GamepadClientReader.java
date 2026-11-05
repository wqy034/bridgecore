package com.bridgecore.client;

import com.bridgecore.network.GamepadInputPayload;
import com.getitemfromblock.create_tweaked_controllers.controller.TweakedLinkedControllerClientHandler;
import com.getitemfromblock.create_tweaked_controllers.input.GamepadInputs;
import net.minecraft.client.Minecraft;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.Arrays;

/**
 * M3a gamepad sender — PURE EXTENSION of Tweaked Controllers' active modes:
 * we never poll the hardware ourselves. Whenever TC's controller is ACTIVE —
 * handheld (item in hand + its toggle key) OR lectern-mounted — TC's own
 * client handler already refreshes {@code GamepadInputs.axis[6]/buttons[15]}
 * every client tick at ClientTickEvent.Pre/HIGHEST (source-verified
 * 2026-09-03). We read that public state at ClientTickEvent.Post (same tick,
 * after TC) and ship RAW frames to the server (GamepadInputPayload): on change
 * immediately, otherwise a heartbeat (5 ticks while anything is demanded,
 * 8 ticks when released — both under the server's 600 ms freshness window).
 * Handheld works at FULL precision for us even though TC's own uplink
 * quantizes to 4bit — we read its local state, not its packets. Handheld
 * doubles as the remote-control form: pairing is still player-centric
 * (within 32 blocks of the core); true beyond-range remote waits for the
 * network connector / explicit binding (arch ruling).
 *
 * Axis values are post-profile: if the player enabled custom mappings in the
 * TC GUI, GamepadInputs.axis already carries their rebinding/deadzone/bounds —
 * the player-facing remapping layer is TC's GUI, not our code.
 *
 * This class references the TC API directly and must only be class-loaded when
 * the mod is present — the single guarded reference lives in BCClientSetup.
 */
public final class GamepadClientReader {

    private static final float[] lastAxes = new float[GamepadInputPayload.AXIS_COUNT];
    private static int lastButtons;
    private static int ticksSinceSend;
    /** True once we have sent a nonzero/connected frame — a later disconnect
     *  then sends exactly one zeroed frame so the ship releases the sticks. */
    private static boolean hadGamepad;

    private GamepadClientReader() {
    }

    public static void register(IEventBus gameBus) {
        gameBus.addListener(ClientTickEvent.Post.class, event -> onTick());
    }

    private static void onTick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return;
        }
        // Consume whenever TC itself is ACTIVE (handheld or lectern): in both
        // modes TC polls the hardware every tick anyway, so our reads add zero
        // polling cost. IDLE/BIND and GUI use stay TC's own business.
        boolean active = TweakedLinkedControllerClientHandler.MODE
                == TweakedLinkedControllerClientHandler.Mode.ACTIVE;
        if (!active) {
            if (hadGamepad) {
                hadGamepad = false;
                send(new float[GamepadInputPayload.AXIS_COUNT], 0);
            }
            return;
        }
        float[] axes = GamepadInputs.axis;
        float[] frame = Arrays.copyOf(axes, GamepadInputPayload.AXIS_COUNT);
        int buttons = 0;
        for (int i = 0; i < 15; i++) {
            if (GamepadInputs.buttons[i]) {
                buttons |= 1 << i;
            }
        }
        boolean changed = !Arrays.equals(frame, lastAxes) || buttons != lastButtons;
        // Triggers rest at -1 (GLFW native [-1,1]), so "released" means sticks
        // centered and LT == RT (vertical demand zero), NOT axes all zero.
        boolean allReleased = buttons == 0 && isReleased(frame);
        ticksSinceSend++;
        // Heartbeat: 5 ticks while anything is demanded; 8 ticks when released —
        // BOTH must stay under the server's 600 ms (12 tick) freshness window or
        // the link flaps linked/lost at rest (observed 2026-09-03 with 30 ticks).
        int heartbeat = allReleased ? 8 : 5;
        if (changed || ticksSinceSend >= heartbeat) {
            System.arraycopy(frame, 0, lastAxes, 0, frame.length);
            lastButtons = buttons;
            hadGamepad = true;
            send(frame, buttons);
        }
    }

    /** Sticks centered and triggers balanced (they rest at -1, not 0). */
    private static boolean isReleased(float[] axes) {
        for (int i = 0; i < 4; i++) {
            if (axes[i] != 0.0f) {
                return false;
            }
        }
        return axes[4] == axes[5];
    }

    private static void send(float[] axes, int buttons) {
        ticksSinceSend = 0;
        PacketDistributor.sendToServer(new GamepadInputPayload(axes, buttons));
    }
}
