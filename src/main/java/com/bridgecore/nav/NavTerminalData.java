package com.bridgecore.nav;

import com.bridgecore.control.CruiseHeading;
import com.bridgecore.control.NavigationController;
import com.bridgecore.control.RemoteManeuver;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Navigation terminal item data (auto-navigation-research.md v2 §4).
 * All state lives in the item's CUSTOM_DATA component: a persistent terminal
 * UUID (first use), the bound flight core (pos+dimension), and the named
 * waypoint list. Dropping/sharing the item transfers control by design
 * (single-permission set: the core accepts only its bound terminal).
 */
public final class NavTerminalData {
    public static final double STRIKE_RANGE = 256.0;
    private static final String K_UUID = "uuid";
    private static final String K_CORE_POS = "corePos";
    private static final String K_CORE_DIM = "coreDim";
    private static final String K_MARKS = "marks";

    private static final String K_SETTINGS = "settings";

    public record Settings(double cruiseSpeed, double turnDegrees, int nudgeTenths) {
        public static final Settings DEFAULT = new Settings(NavigationController.DEFAULT_CRUISE_SPEED,
                CruiseHeading.DEFAULT_RATE_DEGREES, 20);

        public boolean valid() {
            return cruiseSpeed >= NavigationController.MIN_TERMINAL_SPEED && cruiseSpeed <= NavigationController.MAX_SPEED
                    && turnDegrees >= CruiseHeading.MIN_RATE_DEGREES && turnDegrees <= CruiseHeading.MAX_RATE_DEGREES
                    && nudgeTenths >= RemoteManeuver.MIN_SPEED_TENTHS && nudgeTenths <= RemoteManeuver.MAX_SPEED_TENTHS;
        }
    }

    public static Settings settings(ItemStack stack) {
        var data = root(stack).getCompound(K_SETTINGS);
        var settings = new Settings(data.getDouble("cruiseSpeed"), data.getDouble("turnDegrees"), data.getInt("nudgeTenths"));
        return settings.valid() ? settings : Settings.DEFAULT;
    }

    public static boolean saveSettings(ItemStack stack, Settings settings) {
        if (!settings.valid()) return false;
        var data = new CompoundTag();
        data.putDouble("cruiseSpeed", settings.cruiseSpeed());
        data.putDouble("turnDegrees", settings.turnDegrees());
        data.putInt("nudgeTenths", settings.nudgeTenths());
        var tag = root(stack);
        tag.put(K_SETTINGS, data);
        write(stack, tag);
        return true;
    }

    private static final String K_QUICK_STRIKE = "quickStrike";

    public record QuickStrike(int channel, String name, int mode) {}

    public static @Nullable QuickStrike quickStrike(ItemStack stack) {
        var data = root(stack).getCompound(K_QUICK_STRIKE);
        return data.contains("channel") ? new QuickStrike(data.getInt("channel"), data.getString("name"), data.getInt("mode")) : null;
    }

    public static void saveQuickStrike(ItemStack stack, @Nullable QuickStrike profile) {
        var tag = root(stack);
        if (profile == null) {
            tag.remove(K_QUICK_STRIKE);
        } else {
            var data = new CompoundTag();
            data.putInt("channel", profile.channel());
            data.putString("name", profile.name());
            data.putInt("mode", profile.mode());
            tag.put(K_QUICK_STRIKE, data);
        }
        write(stack, tag);
    }

    /** One named mark (waypoint or strike point). Pos is world coordinates. */
    public record Mark(String name, BlockPos pos, ResourceLocation dim, boolean strike) {
        CompoundTag save() {
            CompoundTag t = new CompoundTag();
            t.putString("name", name);
            t.putLong("pos", pos.asLong());
            t.putString("dim", dim.toString());
            t.putBoolean("strike", strike);
            return t;
        }

        static @Nullable Mark load(Tag tag) {
            if (!(tag instanceof CompoundTag t)) return null;
            ResourceLocation dim = ResourceLocation.tryParse(t.getString("dim"));
            if (dim == null) return null;
            return new Mark(t.getString("name"), BlockPos.of(t.getLong("pos")), dim,
                    t.getBoolean("strike"));
        }
    }

    private NavTerminalData() {}

    public static @Nullable ItemStack heldTerminal(net.minecraft.world.entity.player.Player player, UUID uuid) {
        for (var hand : net.minecraft.world.InteractionHand.values()) {
            ItemStack stack = player.getItemInHand(hand);
            if (stack.getItem() instanceof NavTerminalItem && uuid.equals(peekUuid(stack))) return stack;
        }
        return null;
    }

    private static CompoundTag root(ItemStack stack) {
        return stack.getOrDefault(net.minecraft.core.component.DataComponents.CUSTOM_DATA,
                CustomData.EMPTY).copyTag();
    }

    private static void write(ItemStack stack, CompoundTag tag) {
        stack.set(net.minecraft.core.component.DataComponents.CUSTOM_DATA, CustomData.of(tag));
    }

    /** The terminal's persistent identity; generated on first call. */
    public static UUID terminalUuid(ItemStack stack) {
        CompoundTag tag = root(stack);
        // N1（R7-2 同型）：长度≠4 的畸形数组按缺失处理，不打 IAE。
        if (!com.bridgecore.NbtGuards.hasUuid4(tag, K_UUID)) {
            tag.putUUID(K_UUID, UUID.randomUUID());
            write(stack, tag);
        }
        return tag.getUUID(K_UUID);
    }

    public static @Nullable UUID peekUuid(ItemStack stack) {
        CompoundTag tag = root(stack);
        return com.bridgecore.NbtGuards.hasUuid4(tag, K_UUID) ? tag.getUUID(K_UUID) : null;
    }

    public static @Nullable BlockPos boundCorePos(ItemStack stack) {
        CompoundTag tag = root(stack);
        return tag.contains(K_CORE_POS) ? BlockPos.of(tag.getLong(K_CORE_POS)) : null;
    }

    public static @Nullable ResourceLocation boundCoreDim(ItemStack stack) {
        CompoundTag tag = root(stack);
        return tag.contains(K_CORE_DIM)
                ? ResourceLocation.tryParse(tag.getString(K_CORE_DIM)) : null;
    }

    public static void bindCore(ItemStack stack, BlockPos pos, ResourceLocation dim) {
        CompoundTag tag = root(stack);
        tag.putLong(K_CORE_POS, pos.asLong());
        tag.putString(K_CORE_DIM, dim.toString());
        write(stack, tag);
    }

    public static void unbindCore(ItemStack stack) {
        CompoundTag tag = root(stack);
        tag.remove(K_CORE_POS);
        tag.remove(K_CORE_DIM);
        write(stack, tag);
    }

    public static List<Mark> marks(ItemStack stack) {
        List<Mark> out = new ArrayList<>();
        for (Tag t : root(stack).getList(K_MARKS, Tag.TAG_COMPOUND)) {
            Mark m = Mark.load(t);
            if (m != null) out.add(m);
        }
        return out;
    }

    /** Add a named mark; returns false when a same-name mark exists. */
    public static boolean addMark(ItemStack stack, Mark mark) {
        CompoundTag tag = root(stack);
        ListTag list = tag.getList(K_MARKS, Tag.TAG_COMPOUND);
        for (Tag t : list) {
            Mark m = Mark.load(t);
            if (m != null && m.name().equals(mark.name())) return false;
        }
        list.add(mark.save());
        tag.put(K_MARKS, list);
        write(stack, tag);
        return true;
    }

    public static boolean removeMark(ItemStack stack, String name) {
        CompoundTag tag = root(stack);
        ListTag old = tag.getList(K_MARKS, Tag.TAG_COMPOUND);
        ListTag next = new ListTag();
        boolean removed = false;
        for (Tag t : old) {
            Mark m = Mark.load(t);
            if (m != null && m.name().equals(name)) {
                removed = true;
                continue;
            }
            next.add(t);
        }
        if (removed) {
            tag.put(K_MARKS, next);
            write(stack, tag);
        }
        return removed;
    }

    public static boolean renameMark(ItemStack stack, String oldName, String newName) {
        CompoundTag tag = root(stack);
        ListTag old = tag.getList(K_MARKS, Tag.TAG_COMPOUND);
        ListTag next = new ListTag();
        boolean found = false;
        for (Tag t : old) {
            Mark m = Mark.load(t);
            if (m != null && !oldName.equals(newName) && m.name().equals(newName)) return false;
            if (m != null && m.name().equals(oldName)) {
                if (found) return false;
                found = true;
                t = new Mark(newName, m.pos(), m.dim(), m.strike()).save();
            }
            next.add(t);
        }
        if (found) {
            tag.put(K_MARKS, next);
            write(stack, tag);
        }
        return found;
    }
}
