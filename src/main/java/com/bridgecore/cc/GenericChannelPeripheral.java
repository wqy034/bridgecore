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

    public GenericChannelPeripheral(String type, List<ChannelDef> channelList) {
        this.type = type;
        for (ChannelDef c : channelList) {
            channels.put(c.name(), c);
        }
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
        try {
            return switch (method) {
                case 0 -> MethodResult.of(new ArrayList<>(channels.keySet()));
                case 1 -> MethodResult.of(read(args.getString(0)));
                case 2 -> {
                    Object result = write(args.getString(0), args.count() > 1 ? args.get(1) : null);
                    yield result == null ? MethodResult.of() : MethodResult.of(result);
                }
                default -> MethodResult.of(readAll());
            };
        } catch (IllegalArgumentException e) {
            // Channel-level input errors (unknown name, read-only, bad value).
            throw new LuaException(e.getMessage());
        }
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
