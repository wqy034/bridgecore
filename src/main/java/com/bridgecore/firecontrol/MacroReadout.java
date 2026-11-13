package com.bridgecore.firecontrol;

import java.util.Locale;

/** Latest server-side reading of one independent, hull-fixed macro cannon. */
public record MacroReadout(int channel, long taskRev, long tick, int hardware,
                           long energy, long capacity, int heat, int maxHeat,
                           int shotCost, int warmup, int cooldown, Phase phase, Aim aim) {
    public enum Phase {
        UNKNOWN("武器忙"), CHARGING("充能中"), READY("待发"), WARMUP("预热中"), FIRING("发射中"), COOLDOWN("冷却中");
        private final String label;
        Phase(String label) { this.label = label; }
        public String label() { return label; }
    }

    public String label() { return aim.label(hardware, phase); }

    public enum Gate { UNKNOWN, UNGROUPED, HARDWARE, NEED_LOCK, NO_TARGET, NO_BEAM, OUT_OF_RANGE, ALIGNING, READY }

    public record Aim(Gate gate, double degrees, double distance, double range) {
        public static Aim of(Gate gate) { return new Aim(gate, -1, -1, -1); }
        public boolean ready() { return gate == Gate.READY; }
        public String label(int hardware, Phase phase) {
            return switch (gate) {
                case UNKNOWN -> "状态待更新";
                case UNGROUPED -> "未编组";
                case HARDWARE -> hardware < 0 ? "宏炮未加载" : hardware == MountLink.STATUS_BREECH_BUSY
                        ? phase.label() : FireControlUiText.mount(hardware);
                case NEED_LOCK -> "需要精确锁";
                case NO_TARGET -> "无目标";
                case NO_BEAM -> "射束不可用";
                case OUT_OF_RANGE -> "超射程";
                case ALIGNING -> String.format(Locale.ROOT, "射束偏 %.1f°", degrees);
                case READY -> "可射";
            };
        }
    }
}
