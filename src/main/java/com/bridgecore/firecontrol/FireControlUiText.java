package com.bridgecore.firecontrol;

/** Shared operator-facing labels for the management GUI and tiled console. */
public final class FireControlUiText {
    private FireControlUiText() {}

    public static String channel(int ch) {
        return ch >= 0 && ch < 4 ? "频道" + "一二三四".charAt(ch) : "未编组";
    }

    public static String weapon(String type) {
        return switch (type) {
            case "RAILGUN" -> "粒子炮";
            case "PARTICLE" -> "磁轨炮";
            case "COILGUN" -> "线圈炮";
            case "LASER" -> "激光机炮";
            case "NONE" -> "未装配武器";
            default -> type;
        };
    }

    public static String mount(int code) {
        return switch (code) {
            case 0 -> "未安装武器";
            case 1 -> "就绪";
            case 2 -> "未装配";
            case 3 -> "缺少弹药";
            case 4 -> "能量不足";
            case 5 -> "过热";
            case 6 -> "炮闩忙碌";
            default -> "状态待更新";
        };
    }

    public static String gate(FireDirector.Gate gate) {
        return switch (gate) {
            case FIRE -> "允许开火";
            case NO_TARGET -> "无目标";
            case NO_CHANNEL -> "未编组";
            case AUTO_OFF -> "自动开火关";
            case NOT_READY -> "武器未就绪";
            case AIMING -> "瞄准中";
            case OUT_OF_ARC -> "射界外";
            case CADENCE -> "等待射速间隔";
            case BLOCKED -> "火线遮挡";
        };
    }

    public static String gate(int ordinal) {
        var values = FireDirector.Gate.values();
        return ordinal >= 0 && ordinal < values.length ? gate(values[ordinal]) : "状态待更新";
    }

    public static String mode(int ordinal) {
        var values = ChannelTask.Mode.values();
        if (ordinal < 0 || ordinal >= values.length) return "待命";
        return switch (values[ordinal]) {
            case IDLE -> "待命";
            case PRECISE_LOCK -> "精确锁定";
            case MULTI_LOCK -> "多目标交战";
            case FOLLOW -> "视线跟随";
        };
    }

    public static String source(int ordinal) {
        var values = ChannelTask.Source.values();
        if (ordinal < 0 || ordinal >= values.length) return "无任务";
        return switch (values[ordinal]) {
            case NONE -> "无任务";
            case HUD -> "HUD";
            case CONSOLE -> "控制台";
        };
    }
}
