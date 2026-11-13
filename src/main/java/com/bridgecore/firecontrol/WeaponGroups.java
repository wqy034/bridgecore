package com.bridgecore.firecontrol;

/** Stable group indices. Save migration maps a legacy single index to one bit. */
public final class WeaponGroups {
    public static final int DEFAULT_COUNT = 4;
    public static final int MAX = 32;
    public static final int NAME_LIMIT = 24;

    private WeaponGroups() {}

    public static int bit(int group) {
        return group >= 0 && group < MAX ? 1 << group : 0;
    }

    public static int validMask(int count) {
        return count == MAX ? -1 : (1 << count) - 1;
    }

    public static int axialOnly(int fixedGunGroups, int turretGroups) {
        return fixedGunGroups & ~turretGroups;
    }

    public static int owner(int members, int selected, int engaged) {
        if ((members & bit(selected)) != 0) return selected;
        int candidates = members & engaged;
        if (candidates == 0) candidates = members;
        return candidates == 0 ? -1 : Integer.numberOfTrailingZeros(candidates);
    }

    public static String cleanName(String name) {
        String cleaned = name.strip();
        if (cleaned.isEmpty() || cleaned.length() > NAME_LIMIT
                || cleaned.codePoints().anyMatch(c -> Character.isISOControl(c) || c == 0xa7
                        || Character.getType(c) == Character.FORMAT)) return "";
        return cleaned;
    }
}
