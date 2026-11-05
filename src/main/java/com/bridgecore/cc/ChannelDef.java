package com.bridgecore.cc;

import java.util.function.Function;
import java.util.function.Supplier;

/**
 * One channel in a block entity's CC channel table (2026-09-09 channel-table
 * ruling): name -> read/write, exposed through the single generic peripheral
 * (list/read/write/readAll meta methods). New peripheral capability = a new
 * ChannelDef row, never a new Java-exposed method.
 *
 * Pure Java ON PURPOSE — no CC classes in this signature, so the channel
 * declarations class-load fine with CC absent (soft dependency).
 *
 * Writer protocol: receives the raw Lua argument as delivered by CC (String /
 * Double / Boolean / Map) and returns the Lua result (null = no return value);
 * throw IllegalArgumentException on invalid input — the peripheral translates it
 * into a Lua error. Readers and writers run on the SERVER thread (CC invokes
 * peripheral methods on the main thread) and must never block.
 */
public record ChannelDef(String name, boolean readable, boolean writable,
                         Supplier<Object> reader, Function<Object, Object> writer, String doc) {

    public static ChannelDef read(String name, Supplier<Object> reader, String doc) {
        return new ChannelDef(name, true, false, reader, null, doc);
    }

    public static ChannelDef write(String name, Function<Object, Object> writer, String doc) {
        return new ChannelDef(name, false, true, null, writer, doc);
    }
}
