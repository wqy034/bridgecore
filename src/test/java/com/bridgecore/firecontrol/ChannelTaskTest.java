package com.bridgecore.firecontrol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.bridgecore.firecontrol.ChannelTask.Mode;
import com.bridgecore.firecontrol.ChannelTask.SelectResult;
import com.bridgecore.firecontrol.ChannelTask.Source;

/** Channel task state-machine contract (频道任务协议 v1, handbook §2.29 §1/§2):
 *  every task change bumps the monotone rev; promoteSuppressed flips do NOT;
 *  release returns to IDLE/NONE and clears the payload; console selection is
 *  capacity-bounded; restore takes the max rev. */
class ChannelTaskTest {

    @Test
    void startsIdleAndClean() {
        ChannelTask t = new ChannelTask();
        assertEquals(Source.NONE, t.source());
        assertEquals(Mode.IDLE, t.mode());
        assertEquals(0, t.rev());
        assertTrue(t.selection().isEmpty());
        assertNull(t.preciseId());
        assertNull(t.followPlayer());
        assertFalse(t.promoteSuppressed());
    }

    @Test
    void everyTransitionBumpsRev() {
        ChannelTask t = new ChannelTask();
        t.toMulti(Source.HUD);
        assertEquals(1, t.rev());
        assertEquals(Mode.MULTI_LOCK, t.mode());
        assertEquals(Source.HUD, t.source());
        t.toPrecise(Source.HUD, "a");
        assertEquals(2, t.rev());
        assertEquals("a", t.preciseId());
        t.release();
        assertEquals(3, t.rev());
        assertEquals(Mode.IDLE, t.mode());
        assertEquals(Source.NONE, t.source());
        assertNull(t.preciseId());
    }

    @Test
    void promoteSuppressedFlipDoesNotBumpRev() {
        ChannelTask t = new ChannelTask();
        t.toMulti(Source.HUD);
        long rev = t.rev();
        t.setPromoteSuppressed(true);
        t.setPromoteSuppressed(false);
        assertEquals(rev, t.rev());
    }

    @Test
    void releaseKeepsPromoteSuppressed() {
        // 协议 §5: 撤销与升格暂停是两个状态 — G 解除先 release 再置位,
        // release 自己不动 promoteSuppressed
        ChannelTask t = new ChannelTask();
        t.setPromoteSuppressed(true);
        t.release();
        assertTrue(t.promoteSuppressed());
    }

    @Test
    void hudMultiLockStoresNoSelection() {
        // HUD MULTI_LOCK 不存集合 — 候选吃实时 prelock (协议 §1)
        ChannelTask t = new ChannelTask();
        t.toMulti(Source.CONSOLE);
        t.toggleSelection("a", 4);
        t.toggleSelection("b", 4);
        long rev = t.rev();
        t.toMulti(Source.HUD);
        assertTrue(t.selection().isEmpty());
        assertEquals(rev + 1, t.rev());
    }

    @Test
    void consoleMultiLockKeepsSelection() {
        ChannelTask t = new ChannelTask();
        t.toMulti(Source.CONSOLE);
        t.toggleSelection("a", 4);
        t.toMulti(Source.CONSOLE); // source/mode 不变也算任务变更
        assertTrue(t.selection().contains("a"));
    }

    @Test
    void selectionToggleIsCapacityBounded() {
        ChannelTask t = new ChannelTask();
        t.toMulti(Source.CONSOLE);
        assertEquals(SelectResult.ADDED, t.toggleSelection("a", 2));
        assertEquals(SelectResult.ADDED, t.toggleSelection("b", 2));
        long rev = t.rev();
        // 满则拒绝,不覆盖 (裁断稿 §3), rev 不动
        assertEquals(SelectResult.FULL, t.toggleSelection("c", 2));
        assertEquals(rev, t.rev());
        assertFalse(t.selection().contains("c"));
        // 再点已选项 = 移除, rev+1
        assertEquals(SelectResult.REMOVED, t.toggleSelection("a", 2));
        assertEquals(rev + 1, t.rev());
        assertFalse(t.selection().contains("a"));
    }

    @Test
    void selectionInsertionOrderStable() {
        ChannelTask t = new ChannelTask();
        t.toMulti(Source.CONSOLE);
        t.toggleSelection("b", 4);
        t.toggleSelection("a", 4);
        assertEquals(List.of("b", "a"), List.copyOf(t.selection()));
    }

    @Test
    void pruneSelectionBumpsRevOnlyOnChange() {
        ChannelTask t = new ChannelTask();
        t.toMulti(Source.CONSOLE);
        t.toggleSelection("a", 4);
        t.toggleSelection("b", 4);
        long rev = t.rev();
        assertFalse(t.pruneSelection(id -> true)); // keep all
        assertEquals(rev, t.rev());
        assertTrue(t.pruneSelection(id -> id.equals("a"))); // drop b
        assertEquals(rev + 1, t.rev());
        assertEquals(List.of("a"), List.copyOf(t.selection()));
    }

    @Test
    void followCarriesPlayerAndClearsPayload() {
        ChannelTask t = new ChannelTask();
        t.toMulti(Source.CONSOLE);
        t.toggleSelection("a", 4);
        UUID p = UUID.randomUUID();
        t.toFollow(p);
        assertEquals(Mode.FOLLOW, t.mode());
        assertEquals(Source.CONSOLE, t.source());
        assertEquals(p, t.followPlayer());
        assertTrue(t.selection().isEmpty());
        t.release();
        assertNull(t.followPlayer());
    }

    @Test
    void invalidTransitionsRejected() {
        ChannelTask t = new ChannelTask();
        assertThrows(IllegalArgumentException.class, () -> t.toMulti(Source.NONE));
        assertThrows(IllegalArgumentException.class, () -> t.toPrecise(Source.HUD, null));
        assertThrows(IllegalArgumentException.class, () -> t.toPrecise(Source.HUD, ""));
        assertThrows(IllegalArgumentException.class, () -> t.toFollow(null));
    }

    @Test
    void restoreTakesMaxRevAndVerbatimPayload() {
        ChannelTask t = new ChannelTask();
        t.toPrecise(Source.HUD, "fresh");
        long liveRev = t.rev();
        UUID p = UUID.randomUUID();
        t.restore(Source.CONSOLE, Mode.FOLLOW, liveRev - 1, List.of("x"), null, p, true);
        assertEquals(liveRev, t.rev()); // rev 只进不退
        assertEquals(Mode.FOLLOW, t.mode());
        assertEquals(Source.CONSOLE, t.source());
        assertEquals(p, t.followPlayer());
        assertTrue(t.promoteSuppressed());
        assertEquals(List.of("x"), List.copyOf(t.selection()));
        // 持久化 rev 更大时采用它
        ChannelTask u = new ChannelTask();
        u.restore(Source.HUD, Mode.MULTI_LOCK, 41, List.of(), null, null, false);
        assertEquals(41, u.rev());
        u.toPrecise(Source.HUD, "a");
        assertEquals(42, u.rev());
    }
}
