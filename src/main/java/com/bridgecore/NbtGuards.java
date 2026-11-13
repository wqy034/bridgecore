package com.bridgecore;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

/** NBT 读取守卫（审查 R7-2/R7-5）。 */
public final class NbtGuards {
    private NbtGuards() {}

    /** R7-2：hasUUID 只判类型不判长度，长度≠4 的 getUUID 会抛 IAE——
     *  统一先查长度，缺失/畸形按"无此键"处理。 */
    public static boolean hasUuid4(CompoundTag tag, String key) {
        return tag.contains(key, Tag.TAG_INT_ARRAY) && tag.getIntArray(key).length == 4;
    }

    /** R7-5：NaN/Inf 载入统一回退默认值（Math.min/max 不拦 NaN）。 */
    public static double finiteOr(double value, double fallback) {
        return Double.isFinite(value) ? value : fallback;
    }
}
