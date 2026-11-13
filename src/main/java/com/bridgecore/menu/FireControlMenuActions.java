package com.bridgecore.menu;

/** Append-only menu protocol. Legacy arc ids occupy [32768, 65536). */
public final class FireControlMenuActions {
    private FireControlMenuActions() {}

    public static final int ROWS = 8;
    public static final int CHANNELS = 4;
    public static final int RECALIBRATE_BASE = 65536;
    public static final int CHANNEL_SELECT_BASE = RECALIBRATE_BASE + ROWS;
    public static final int CHANNEL_AUTO_BASE = CHANNEL_SELECT_BASE + CHANNELS;
    public static final int CHANNEL_PROMOTE_BASE = CHANNEL_AUTO_BASE + CHANNELS;
    /** Five explicit choices per turret: ungrouped, then channels 0..3. */
    public static final int TURRET_CHANNEL_BASE = CHANNEL_PROMOTE_BASE + CHANNELS;
    public static final int CADENCE_DOWN_BASE = TURRET_CHANNEL_BASE + ROWS * (CHANNELS + 1);
    public static final int CADENCE_UP_BASE = CADENCE_DOWN_BASE + ROWS;
    public static final int CADENCE_END = CADENCE_UP_BASE + ROWS;
    /** Axis-gun (轴炮) channel membership: four guns, same five explicit
     *  choices per gun (ungrouped, then channels 0..3). */
    public static final int FIXED_GUNS = 4;
    public static final int FIXED_CHANNEL_BASE = CADENCE_END;
    public static final int END = FIXED_CHANNEL_BASE + FIXED_GUNS * (CHANNELS + 1);
    public static final int GROUP_ADD = END;
    public static final int GROUP_SELECT = GROUP_ADD + 1;
    public static final int GROUP_AUTO = GROUP_SELECT + com.bridgecore.firecontrol.WeaponGroups.MAX;
    public static final int GROUP_PROMOTE = GROUP_AUTO + com.bridgecore.firecontrol.WeaponGroups.MAX;
    public static final int GROUP_MEMBERSHIP = GROUP_PROMOTE + com.bridgecore.firecontrol.WeaponGroups.MAX;
    public static final int GROUP_END = GROUP_MEMBERSHIP
            + (ROWS + FIXED_GUNS) * com.bridgecore.firecontrol.WeaponGroups.MAX * 2;
    public static final int SECTOR_RESUME_BASE = GROUP_END;

    public static int groupMembership(int row, boolean macro, int group, boolean join) {
        int max = com.bridgecore.firecontrol.WeaponGroups.MAX;
        if (row < 0 || row >= (macro ? FIXED_GUNS : ROWS) || group < 0 || group >= max)
            throw new IllegalArgumentException("Invalid weapon/group");
        return GROUP_MEMBERSHIP + ((macro ? ROWS + row : row) * max + group) * 2 + (join ? 1 : 0);
    }

    /** New GUI wraps commands with a menu-local roster epoch. Old ids remain
     *  valid for existing callers. Reject a delayed click after another player
     *  removes/reorders a turret or mount instead of targeting the next row. */
    public static final int GUARDED_BASE = 1 << 29;
    public static final int OP_BITS = 17;
    public static final int EPOCH_MASK = 4095;
    public static final int GUARDED_END = GUARDED_BASE + ((EPOCH_MASK + 1) << OP_BITS);

    public static int guarded(int action, int epoch) {
        if (action < 0 || action >= (1 << OP_BITS) || epoch < 0 || epoch > EPOCH_MASK) {
            throw new IllegalArgumentException("Invalid menu action/epoch");
        }
        return GUARDED_BASE + (epoch << OP_BITS) + action;
    }

    /** -1 means malformed or stale. Never decode it as a row-zero action. */
    public static int decodeGuarded(int encoded, int currentEpoch) {
        if (encoded < GUARDED_BASE || encoded >= GUARDED_END) return -1;
        int payload = encoded - GUARDED_BASE;
        return (payload >>> OP_BITS) == currentEpoch ? payload & ((1 << OP_BITS) - 1) : -1;
    }

    public static int membership(int row, int channel) {
        if (row < 0 || row >= ROWS || channel < -1 || channel >= CHANNELS) {
            throw new IllegalArgumentException("Invalid turret/channel");
        }
        return TURRET_CHANNEL_BASE + row * (CHANNELS + 1) + channel + 1;
    }

    public static int fixedMembership(int row, int channel) {
        if (row < 0 || row >= FIXED_GUNS || channel < -1 || channel >= CHANNELS) {
            throw new IllegalArgumentException("Invalid fixed-gun/channel");
        }
        return FIXED_CHANNEL_BASE + row * (CHANNELS + 1) + channel + 1;
    }
}
