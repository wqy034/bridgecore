package com.bridgecore.firecontrol;

import com.bridgecore.menu.FireControlMenuActions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class WeaponGroupsTest {
    @Test
    void selectedMembershipWinsEvenWhenAnotherGroupHasATask() {
        int members = WeaponGroups.bit(2) | WeaponGroups.bit(31);
        assertEquals(31, WeaponGroups.owner(members, 31, WeaponGroups.bit(2)));
        assertEquals(2, WeaponGroups.owner(members, 2, WeaponGroups.bit(31)));
        assertEquals(31, WeaponGroups.owner(members, 0, WeaponGroups.bit(31)));
        assertEquals(2, WeaponGroups.owner(members, 0, members));
        assertEquals(-1, WeaponGroups.owner(0, 0, members));
    }

    @Test
    void legacyIndexAndHighestMembershipRemainDistinct() {
        assertEquals(0, WeaponGroups.bit(-1));
        assertEquals(8, WeaponGroups.bit(3));
        assertEquals(0, WeaponGroups.bit(32));
        int members = WeaponGroups.bit(3) | WeaponGroups.bit(31);
        assertEquals(WeaponGroups.bit(3), members & WeaponGroups.validMask(4));
        assertEquals(members, members & WeaponGroups.validMask(32));
        int low = (short) members, high = (short) (members >>> 16);
        assertEquals(members, (low & 0xffff) | (high << 16));
    }

    @Test
    void aLegacyFixedGunKeepsItsDedicatedChannelAndLeavesTheTurretChannel() {
        int left = WeaponGroups.bit(0), axis = WeaponGroups.bit(1), right = WeaponGroups.bit(2);
        assertEquals(axis, WeaponGroups.axialOnly(axis | right, left | right));
        assertEquals(0, WeaponGroups.axialOnly(right, left | right));
    }

    @Test
    void extendedMembershipActionsKeepAllLegacyIdsAndEpochProtection() {
        int turretJoin = FireControlMenuActions.groupMembership(0, false, 31, true);
        int turretLeave = FireControlMenuActions.groupMembership(0, false, 31, false);
        int macroJoin = FireControlMenuActions.groupMembership(0, true, 31, true);
        assertTrue(turretLeave >= FireControlMenuActions.END);
        assertNotEquals(turretJoin, turretLeave);
        assertNotEquals(turretJoin, macroJoin);
        int wire = FireControlMenuActions.guarded(macroJoin, 4095);
        assertEquals(macroJoin, FireControlMenuActions.decodeGuarded(wire, 4095));
        assertEquals(-1, FireControlMenuActions.decodeGuarded(wire, 0));
    }

    @Test
    void namesKeepPlayerTextWithinTheWireLimit() {
        assertEquals("舰艏打击", WeaponGroups.cleanName(" 舰艏打击 "));
        assertEquals("", WeaponGroups.cleanName(" "));
        assertEquals("", WeaponGroups.cleanName("前\n后"));
        assertEquals("", WeaponGroups.cleanName("§a舰艏"));
        assertEquals("", WeaponGroups.cleanName("炮".repeat(25)));
    }
}
