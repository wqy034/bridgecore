package com.bridgecore.display;

import org.jetbrains.annotations.Nullable;

/** Compact right-side weapon instrument. Presentation only; never commands
 * tasks or derives permission from the HUD's local prelock/pin state. */
public final class HudWeaponStatusLayer {
    private static final int S = 2, LEFT = 339 * S, RIGHT = 496 * S;
    private static final int LABEL = 22, NAME = 28;
    private int previousChannel = -1;
    private long expandedUntil, lastTick = Long.MIN_VALUE;

    public void render(Canvas c, @Nullable HudTargetProvider.WeaponStatusView view, long tick) {
        if (view == null) {
            previousChannel = -1;
            expandedUntil = 0;
            return;
        }
        var task = view.task();
        if (tick < lastTick) expandedUntil = 0;
        if (previousChannel >= 0 && previousChannel != task.channel()) expandedUntil = tick + 60;
        previousChannel = task.channel();
        lastTick = tick;
        boolean active = task.mode() != HudTargetProvider.TaskMode.IDLE;
        String header = "WPN " + String.format(java.util.Locale.ROOT, "%02d", task.channel() + 1);
        if (!active && tick >= expandedUntil) {
            right(c, RIGHT, 118 * S - LABEL, fit(header + " / " + task.name(), LABEL, RIGHT - LEFT),
                    LABEL, HudPalette.LADDER_DIM_BLUE);
            return;
        }
        c.drawThickLine(LEFT, 84 * S, RIGHT, 84 * S, 8, 3);
        c.drawThickLine(RIGHT, 84 * S, RIGHT, 88 * S, 8, 3);
        text(c, LEFT, 99 * S - LABEL, header, LABEL, HudPalette.LADDER_LABEL);
        String status = view.salvoWaiting() ? "等待齐射" : active ? "跟踪" : "待命";
        int statusColor = view.salvoWaiting() ? HudPalette.LADDER_YELLOW : HudPalette.LADDER_LABEL;
        right(c, RIGHT, 99 * S - LABEL, status, LABEL, statusColor);
        int end = RIGHT - width(status, LABEL) - 12;
        int start = LEFT + width(header, LABEL) + 12;
        int n = view.turrets().size();
        // Pairing order is retained; state changes never reorder identities.
        int visible = Math.min(n, Math.min(12, Math.max(0, (end - start + 5) / 9)));
        String overflow = "";
        if (visible < n) {
            visible = Math.min(n, Math.min(12, Math.max(0, (end - start - width("+" + n, 18) - 8 + 5) / 9)));
            overflow = "+" + (n - visible);
            right(c, end, 99 * S - LABEL, overflow, 18, HudPalette.LADDER_LABEL);
            end -= width(overflow, 18) + 8;
        }
        int segment = visible == 0 ? 0 : Math.min(12, Math.max(4, (end - start - (visible - 1) * 5) / visible));
        int x = end - visible * segment - Math.max(0, visible - 1) * 5;
        for (int i = 0; i < visible; i++) {
            int color = switch (view.turrets().get(i).lamp()) {
                case AUTO_ALLOWED -> 6;
                case FAULT -> 5;
                case TRACKING, WAITING -> 4;
                default -> 9;
            };
            c.fillRect(x + i * (segment + 5), 94 * S, segment, 6, color);
        }
        long tracking = view.turrets().stream().filter(HudTargetProvider.TurretStatusView::tracking).count();
        String count = tracking + "/" + n;
        right(c, RIGHT, 118 * S - LABEL, count, LABEL, HudPalette.LADDER_LABEL);
        text(c, LEFT, 118 * S - NAME, fit(task.name(), NAME, RIGHT - LEFT - width(count, LABEL) - 16),
                NAME, active ? HudPalette.LADDER_WHITE : HudPalette.LADDER_LABEL);

        Double ratio = view.minimumEnergyRatio();
        text(c, LEFT, 138 * S - 20, ratio == null ? "E --" : view.partialEnergy() ? "E 部分" : "E 最低",
                20, HudPalette.LADDER_LABEL);
        if (ratio != null) {
            int bx = LEFT + 66, by = 135 * S, bw = 98;
            double clamped = Math.max(0, Math.min(1, ratio));
            c.fillRect(bx, by, bw, 5, 9);
            int color = clamped < .2 ? 5 : clamped < .5 ? 4 : 6;
            c.fillRect(bx, by, (int) Math.round(bw * clamped), 5, color);
        }
        right(c, RIGHT, 138 * S - 20, view.autoFire() ? "自动扳机" : "手动扳机", 20,
                view.autoFire() ? HudPalette.LADDER_YELLOW : HudPalette.LADDER_LABEL);
        if (active) {
            String source = task.source() == HudTargetProvider.TaskSource.CONSOLE ? "FCS"
                    : task.source() == HudTargetProvider.TaskSource.HUD ? "HUD" : "--";
            String mode = switch (task.mode()) {
                case MULTI_LOCK -> "多目标";
                case PRECISE_LOCK -> "精确锁";
                case FOLLOW -> "视线跟随";
                default -> "待命";
            };
            String targets = task.mode() == HudTargetProvider.TaskMode.FOLLOW ? ""
                    : " · " + task.assignedTargetIds().size() + "目标";
            right(c, RIGHT, 156 * S - 20, fit(source + " · " + mode + targets, 20, RIGHT - LEFT),
                    20, HudPalette.LADDER_LABEL);
        }
    }

    private static int width(String text, int size) {
        return OutlineTextRenderer.outlineTextWidth(OutlineTextRenderer.FONT_NOTO, size, text);
    }

    private static String fit(String value, int size, int available) {
        if (width(value, size) <= available) return value;
        int end = value.length();
        while (end > 0 && width(value.substring(0, end) + "…", size) > available) {
            end = value.offsetByCodePoints(end, -1);
        }
        return end == 0 ? "" : value.substring(0, end) + "…";
    }

    private static void right(Canvas c, int x, int y, String value, int size, int ladder) {
        text(c, x - width(value, size), y, value, size, ladder);
    }

    private static void text(Canvas c, int x, int y, String value, int size, int ladder) {
        OutlineTextRenderer.drawOutlineText(c, x, y, value, size, OutlineTextRenderer.FONT_NOTO, ladder);
    }
}
