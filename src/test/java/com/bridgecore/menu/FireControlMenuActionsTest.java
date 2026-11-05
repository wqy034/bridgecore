package com.bridgecore.menu;

import static org.junit.jupiter.api.Assertions.*;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class FireControlMenuActionsTest {
    @Test
    void newCommandsDoNotOverlapLegacyOrEachOther() {
        assertEquals(8, FireControlMenu.MAX_TURRETS);
        assertEquals(32768, FireControlMenu.ARC_SET_BASE);
        assertEquals(FireControlMenu.ARC_SET_BASE + FireControlMenu.MAX_TURRETS * 4096,
                FireControlMenuActions.RECALIBRATE_BASE);
        Set<Integer> ids = new HashSet<>();
        for (int row = 0; row < 8; row++) {
            assertTrue(ids.add(FireControlMenuActions.RECALIBRATE_BASE + row));
            assertTrue(ids.add(FireControlMenuActions.CADENCE_DOWN_BASE + row));
            assertTrue(ids.add(FireControlMenuActions.CADENCE_UP_BASE + row));
            for (int ch = -1; ch < 4; ch++) assertTrue(ids.add(FireControlMenuActions.membership(row, ch)));
        }
        for (int ch = 0; ch < 4; ch++) {
            assertTrue(ids.add(FireControlMenuActions.CHANNEL_SELECT_BASE + ch));
            assertTrue(ids.add(FireControlMenuActions.CHANNEL_AUTO_BASE + ch));
            assertTrue(ids.add(FireControlMenuActions.CHANNEL_PROMOTE_BASE + ch));
        }
        assertTrue(ids.stream().allMatch(id -> id >= 65536 && id < FireControlMenuActions.END));
        assertEquals(FireControlMenuActions.END - 65536, ids.size());
    }

    @Test
    void delayedClicksAfterRosterChangeAreRejected() {
        int[] actions = {0, 7, 102, 104, 4096, 65535,
                FireControlMenuActions.RECALIBRATE_BASE + 7,
                FireControlMenuActions.membership(7, 3)};
        for (int epoch : new int[]{0, 1, 4095}) for (int action : actions) {
            int encoded = FireControlMenuActions.guarded(action, epoch);
            assertEquals(action, FireControlMenuActions.decodeGuarded(encoded, epoch));
            assertEquals(-1, FireControlMenuActions.decodeGuarded(encoded, (epoch + 1) & 4095));
            assertTrue(encoded > 0);
        }
        assertEquals(-1, FireControlMenuActions.decodeGuarded(-1, 0));
        assertEquals(-1, FireControlMenuActions.decodeGuarded(0, 0));
        assertEquals(-1, FireControlMenuActions.decodeGuarded(FireControlMenuActions.GUARDED_END, 0));
    }

    @Test
    void invalidTargetsCannotBecomeAnOperationOnAnotherTurret() {
        assertThrows(IllegalArgumentException.class, () -> FireControlMenuActions.membership(-1, 0));
        assertThrows(IllegalArgumentException.class, () -> FireControlMenuActions.membership(8, 0));
        assertThrows(IllegalArgumentException.class, () -> FireControlMenuActions.membership(0, 4));
        assertThrows(IllegalArgumentException.class, () -> FireControlMenuActions.guarded(-1, 0));
        assertThrows(IllegalArgumentException.class, () -> FireControlMenuActions.guarded(131072, 0));
        assertThrows(IllegalArgumentException.class, () -> FireControlMenuActions.guarded(1, 4096));
    }
}
