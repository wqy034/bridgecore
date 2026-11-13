package com.bridgecore.cc;

import dan200.computercraft.api.lua.IArguments;
import dan200.computercraft.api.lua.ILuaContext;
import dan200.computercraft.api.lua.LuaException;
import dan200.computercraft.api.lua.MethodResult;
import dan200.computercraft.api.peripheral.IComputerAccess;
import dan200.computercraft.api.peripheral.IDynamicPeripheral;
import dan200.computercraft.api.peripheral.IPeripheral;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The single generic CC peripheral every bridgecore block entity exposes
 * (channel-table design): four meta methods over the BE's declared channels —
 *   list()           -> array of channel names, declaration order
 *   read(name)       -> channel value; Lua error on unknown/write-only channel
 *   write(name, val) -> writer result; Lua error on unknown/read-only/bad value
 *   readAll()        -> map name -> value of every readable channel
 * Handwritten IDynamicPeripheral — no @LuaFunction annotation scanning — because
 * the method set is fixed and the channels are runtime data. Lua-side version
 * tolerance: probe with list(), channels are only ever added.
 */
public final class GenericChannelPeripheral implements IDynamicPeripheral {
    private static final String[] METHODS = {"list", "read", "write", "readAll"};

    private final String type;
    private final Map<String, ChannelDef> channels = new LinkedHashMap<>();
    /** R4-5：BE 存活/挂载检查（CCIntegration 注入）；R4-1 之外的附加防线。 */
    private final java.util.function.BooleanSupplier available;
    /** CC 的 attach/detach 走它自己的线程，只这个集合与之共享。 */
    private final java.util.Set<IComputerAccess> computers =
            java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());

    public GenericChannelPeripheral(String type, List<ChannelDef> channelList) {
        this(type, channelList, () -> true);
    }

    public GenericChannelPeripheral(String type, List<ChannelDef> channelList,
                                    java.util.function.BooleanSupplier available) {
        this.type = type;
        this.available = available;
        for (ChannelDef c : channelList) {
            channels.put(c.name(), c);
        }
    }

    @Override public void attach(IComputerAccess computer) {
        synchronized (computers) { computers.add(computer); }
    }

    @Override public void detach(IComputerAccess computer) {
        synchronized (computers) { computers.remove(computer); }
    }

    @Override
    public String getType() {
        return type;
    }

    @Override
    public String[] getMethodNames() {
        return METHODS;
    }

    @Override
    public MethodResult callMethod(IComputerAccess computer, ILuaContext context,
                                   int method, IArguments args) throws LuaException {
        // R4-1：与显示外设同款——CC 在它自己的线程调我们，所有读写统一派发到
        // 主线程执行（ChannelDef 注释"readers/writers run on the SERVER thread"
        // 由此才成立）。R4-5：attached + available 双检查。
        args.escapes(); // 参数要比 CC 协程调用活得久（主线程任务后置执行）。
        return context.executeMainThreadTask(() -> {
            boolean attached;
            synchronized (computers) { attached = computers.contains(computer); }
            if (!attached || !available.getAsBoolean()) {
                throw new LuaException("Peripheral detached");
            }
            try {
                return switch (method) {
                    case 0 -> new Object[]{new ArrayList<>(channels.keySet())};
                    case 1 -> new Object[]{read(args.getString(0))};
                    case 2 -> {
                        Object result = write(args.getString(0), args.count() > 1 ? args.get(1) : null);
                        yield result == null ? new Object[0] : new Object[]{result};
                    }
                    default -> new Object[]{readAll()};
                };
            } catch (IllegalArgumentException e) {
                // Channel-level input errors (unknown name, read-only, bad value).
                throw new LuaException(e.getMessage());
            }
        });
    }

    private Object read(String name) {
        ChannelDef c = channels.get(name);
        if (c == null) {
            throw new IllegalArgumentException("unknown channel '" + name + "' (see list())");
        }
        if (!c.readable()) {
            throw new IllegalArgumentException("channel '" + name + "' is write-only");
        }
        return c.reader().get();
    }

    private Object write(String name, Object value) {
        ChannelDef c = channels.get(name);
        if (c == null) {
            throw new IllegalArgumentException("unknown channel '" + name + "' (see list())");
        }
        if (!c.writable()) {
            throw new IllegalArgumentException("channel '" + name + "' is read-only");
        }
        return c.writer().apply(value);
    }

    private Map<String, Object> readAll() {
        Map<String, Object> out = new LinkedHashMap<>();
        for (ChannelDef c : channels.values()) {
            if (c.readable()) {
                out.put(c.name(), c.reader().get());
            }
        }
        return out;
    }

    /** Peripheral identity is object identity — CCIntegration caches one instance
     *  per block entity, and equals drives CC's peripheral change detection. */
    @Override
    public boolean equals(IPeripheral other) {
        return this == other;
    }
}
