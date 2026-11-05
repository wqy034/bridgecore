package com.bridgecore.firecontrol;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;

import org.jetbrains.annotations.Nullable;

/**
 * One channel's authoritative task (频道任务协议 v1, shared handbook §2.29 —
 * the fire-control core is the single source of truth; the HUD and the console
 * are input sources, never owners). Pure data + transition methods, zero MC
 * types, so the state machine stays unit-testable (TargetAssigner pattern).
 *
 * <p>Fields per protocol §1:
 * <ul>
 *   <li>{@code source} — who issued the task: NONE (no task) / HUD / CONSOLE;</li>
 *   <li>{@code mode} — IDLE / MULTI_LOCK / PRECISE_LOCK / FOLLOW;</li>
 *   <li>{@code rev} — per-channel monotone revision (协议 §2): EVERY task change
 *       (establish / replace / cancel / source switch / mode switch / selection
 *       change) bumps it; {@code promoteSuppressed} and preference flips do NOT.
 *       Assignment tables carry the rev they were computed from and die the
 *       moment the task's rev moves on;</li>
 *   <li>task payload — CONSOLE multi-lock: the click-selected
 *       {@code selection} set (≤N, N = paired turrets of the channel);
 *       PRECISE_LOCK: {@code preciseId}; FOLLOW: {@code followPlayer}.
 *       HUD MULTI_LOCK stores NO set — its candidates are the live prelock
 *       pool held by the core;</li>
 *   <li>{@code promoteSuppressed} — auto-promotion pause after a manual
 *       release (裁断稿 §5); the only recovery entries are another G or
 *       re-enabling auto-promotion.</li>
 * </ul>
 *
 * <p>NBT mapping lives in the block entity (CompoundTag is an MC type and must
 * not leak in here); {@link #restore} is the load-time entry point.
 */
public final class ChannelTask {

    public enum Source { NONE, HUD, CONSOLE }

    public enum Mode { IDLE, MULTI_LOCK, PRECISE_LOCK, FOLLOW }

    /** Outcome of a console click-select toggle (the chat echo picks one). */
    public enum SelectResult { ADDED, REMOVED, FULL }

    private Source source = Source.NONE;
    private Mode mode = Mode.IDLE;
    private long rev;
    private final LinkedHashSet<String> selection = new LinkedHashSet<>();
    private @Nullable String preciseId;
    private @Nullable UUID followPlayer;
    private boolean promoteSuppressed;

    public Source source() {
        return source;
    }

    public Mode mode() {
        return mode;
    }

    public long rev() {
        return rev;
    }

    /** Live view of the console selection set (insertion-ordered). Mutate ONLY
     *  through {@link #toggleSelection}/{@link #pruneSelection}/
     *  {@link #clearSelection} so the rev rules hold. */
    public java.util.Set<String> selection() {
        return java.util.Collections.unmodifiableSet(selection);
    }

    public @Nullable String preciseId() {
        return preciseId;
    }

    public @Nullable UUID followPlayer() {
        return followPlayer;
    }

    public boolean promoteSuppressed() {
        return promoteSuppressed;
    }

    /** 协议 §2: promoteSuppressed 翻转不 bump rev. */
    public void setPromoteSuppressed(boolean v) {
        promoteSuppressed = v;
    }

    /** Explicit rev bump for payload mutations that keep mode/source (scan-time
     *  selection pruning calls this through pruneSelection; exposed for the
     *  core's orchestration only). */
    public void bumpRev() {
        rev++;
    }

    /** Release the task: back to IDLE/NONE, payload cleared, rev+1.
     *  promoteSuppressed is NOT touched (协议 §5: 撤销动作与升格暂停是两个状态). */
    public void release() {
        source = Source.NONE;
        mode = Mode.IDLE;
        selection.clear();
        preciseId = null;
        followPlayer = null;
        rev++;
    }

    /** Enter multi-lock. CONSOLE keeps the selection set (it IS the task
     *  payload); HUD stores nothing (its candidates are the core's live
     *  prelock pool). rev+1. */
    public void toMulti(Source s) {
        if (s == Source.NONE) throw new IllegalArgumentException("task source NONE");
        source = s;
        mode = Mode.MULTI_LOCK;
        preciseId = null;
        followPlayer = null;
        if (s != Source.CONSOLE) selection.clear();
        rev++;
    }

    /** Enter precise lock on one anchored target. rev+1. */
    public void toPrecise(Source s, String id) {
        if (s == Source.NONE) throw new IllegalArgumentException("task source NONE");
        if (id == null || id.isEmpty()) throw new IllegalArgumentException("precise id");
        source = s;
        mode = Mode.PRECISE_LOCK;
        preciseId = id;
        followPlayer = null;
        selection.clear();
        rev++;
    }

    /** Enter 视线跟随 (console-origin special task — 裁断稿 §7). rev+1. */
    public void toFollow(UUID player) {
        if (player == null) throw new IllegalArgumentException("follow player");
        source = Source.CONSOLE;
        mode = Mode.FOLLOW;
        followPlayer = player;
        preciseId = null;
        selection.clear();
        rev++;
    }

    /** Console click-select: toggles {@code id} in the selection set.
     *  Adding beyond {@code capacity} is refused (FULL — the panel chat-echoes
     *  "已选满 m/N，不覆盖"; 裁断稿 §3). Any actual change bumps rev. */
    public SelectResult toggleSelection(String id, int capacity) {
        if (selection.remove(id)) {
            rev++;
            return SelectResult.REMOVED;
        }
        if (selection.size() >= capacity) return SelectResult.FULL;
        selection.add(id);
        rev++;
        return SelectResult.ADDED;
    }

    /** Drop every selected id rejected by {@code keep} (scan-time pruning:
     *  targets that left the radius or became whitelisted).
     *  @return true when the set changed (rev bumped) */
    public boolean pruneSelection(Predicate<String> keep) {
        boolean changed = selection.removeIf(keep.negate());
        if (changed) rev++;
        return changed;
    }

    /** Clear the selection set; bumps rev when it was non-empty.
     *  @return true when it was non-empty */
    public boolean clearSelection() {
        if (selection.isEmpty()) return false;
        selection.clear();
        rev++;
        return true;
    }

    /** NBT load (merge-style entry): restores a persisted task verbatim,
     *  taking the max rev so a stale rev can never move backwards. */
    public void restore(Source s, Mode m, long rev, List<String> selection,
                        @Nullable String preciseId, @Nullable UUID followPlayer,
                        boolean promoteSuppressed) {
        this.source = s;
        this.mode = m;
        this.rev = Math.max(this.rev, rev);
        this.selection.clear();
        this.selection.addAll(selection);
        this.preciseId = preciseId;
        this.followPlayer = followPlayer;
        this.promoteSuppressed = promoteSuppressed;
    }
}
