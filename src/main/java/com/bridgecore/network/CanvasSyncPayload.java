package com.bridgecore.network;

import com.bridgecore.BridgeCoreMod;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * S2C full-canvas sync. Wire format reworked 2026-10-29 (HUD outline-font
 * vertical slice): the packing is now an EXPLICIT {@code encoding} field
 * (0 = raw byte indices, 1 = 4-bit nibble-packed, 2 = RLE) instead of the old
 * length-guessing heuristic, because a 256-color HUD frame under RLE has no
 * predictable length at all. With the 256-entry HUD palette (alpha ladders
 * for antialiased outline text) nibble packing never applies to the HUD;
 * RLE shrinks its mostly-empty 1024² frames to a few KiB. 16-color canvases
 * still nibble-pack (512 KiB for 1024²).
 *
 * Banding: the custom-payload cap is 1 MiB; {@link #MAX_BAND_BYTES} (960 KiB)
 * leaves ~64 KiB of headroom for BlockPos/VarInts/palette. An encoded frame
 * larger than that ships as several packets — {@code bandIndex}/{@code
 * bandCount} slice the ENCODED byte stream (never the decoded pixels), so
 * splitting may cut an RLE run in half; the receiver concatenates the bands
 * in index order and decodes once. Band 0 resets the reassembly buffer.
 * bandCount = 1 is the common single-packet path. A frame that would exceed
 * the cap RAW is never sent raw.
 *
 * RLE format: a sequence of (value byte, run-length VarInt) pairs covering
 * the frame in row-major order. The decoder validates w/h against
 * {@code Canvas.DEFAULT_WIDTH/HEIGHT}, rejects runs that overshoot the frame
 * or a decoded total != w×h, and drops malformed packets with a warn.
 *
 * The client half lives in {@code com.bridgecore.client.HoloTextureCache} and is
 * only referenced inside enqueueWork behind a dist check — dedicated servers must
 * never class-load client code.
 *
 * Palette (2026-09-27, per-instance 16/256-color support): the buffer stays
 * byte indices; the palette the indices resolve through rides here as ARGB
 * ints packed big-endian into paletteBytes (64 B / 1 KiB). EMPTY paletteBytes
 * = the canvas uses the global default palette. A custom palette rides EVERY
 * sync (and every band): late-joining clients must never render through a
 * palette they never received, and version-gated deltas would open exactly
 * that window. paletteVersion mirrors the canvas counter for observability /
 * client-side change detection.
 */
public record CanvasSyncPayload(BlockPos pos, int width, int height, int encoding,
                                int bandIndex, int bandCount, byte[] pixels,
                                int paletteVersion, byte[] paletteBytes)
        implements CustomPacketPayload {

    /** Raw byte indices, one per pixel. */
    public static final int ENCODING_RAW = 0;
    /** 4-bit packed pairs (first pixel of each pair in the HIGH nibble). */
    public static final int ENCODING_NIBBLE = 1;
    /** (value byte, run-length VarInt) pairs over the whole frame. */
    public static final int ENCODING_RLE = 2;

    /** Max palette payload: 256 entries x 4 bytes. */
    private static final int MAX_PALETTE_BYTES = 256 * 4;

    /** Per-packet cap on the encoded pixel bytes: 960 KiB. The custom-payload
     *  hard cap is 1 MiB (1048576 B); the margin covers BlockPos, the VarInt
     *  header fields and the 1 KiB palette table. Frames encoding larger than
     *  this are band-sliced (see class javadoc). */
    public static final int MAX_BAND_BYTES = 960 * 1024;

    public static final Type<CanvasSyncPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(BridgeCoreMod.MOD_ID, "canvas_sync"));

    public static final StreamCodec<FriendlyByteBuf, CanvasSyncPayload> STREAM_CODEC =
            StreamCodec.of(CanvasSyncPayload::write, CanvasSyncPayload::read);

    private static void write(FriendlyByteBuf buf, CanvasSyncPayload p) {
        buf.writeBlockPos(p.pos);
        buf.writeVarInt(p.width);
        buf.writeVarInt(p.height);
        buf.writeVarInt(p.encoding);
        buf.writeVarInt(p.bandIndex);
        buf.writeVarInt(p.bandCount);
        buf.writeByteArray(p.pixels);
        buf.writeVarInt(p.paletteVersion);
        buf.writeByteArray(p.paletteBytes);
    }

    private static CanvasSyncPayload read(FriendlyByteBuf buf) {
        return new CanvasSyncPayload(buf.readBlockPos(),
                buf.readVarInt(), buf.readVarInt(), buf.readVarInt(),
                buf.readVarInt(), buf.readVarInt(),
                buf.readByteArray(MAX_BAND_BYTES), buf.readVarInt(),
                buf.readByteArray(MAX_PALETTE_BYTES));
    }

    /** Single-band convenience constructor (bandCount = 1, bandIndex = 0). */
    public static CanvasSyncPayload single(BlockPos pos, int width, int height, int encoding,
                                           byte[] pixels, int paletteVersion, byte[] paletteBytes) {
        return new CanvasSyncPayload(pos, width, height, encoding, 0, 1,
                pixels, paletteVersion, paletteBytes);
    }

    /** True when every pixel index fits a nibble (any 16-color palette) and
     *  the pixel count is even — then the sync ships 4-bit packed (half size). */
    public static boolean canPackNibbles(byte[] pixels) {
        if ((pixels.length & 1) != 0) {
            return false;
        }
        for (byte b : pixels) {
            if ((b & 0xF0) != 0) {
                return false;
            }
        }
        return true;
    }

    /** 4-bit packing, first pixel of each pair in the HIGH nibble. Caller must
     *  have checked {@link #canPackNibbles}. */
    public static byte[] packNibbles(byte[] pixels) {
        byte[] out = new byte[pixels.length / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) ((pixels[i * 2] << 4) | (pixels[i * 2 + 1] & 0xF));
        }
        return out;
    }

    /** Inverse of {@link #packNibbles}. Caller must have validated that
     *  {@code data.length * 2 == expectedPixels}. */
    public static byte[] unpackNibbles(byte[] data, int expectedPixels) {
        byte[] out = new byte[expectedPixels];
        for (int i = 0; i < data.length; i++) {
            out[i * 2] = (byte) (data[i] >>> 4);
            out[i * 2 + 1] = (byte) (data[i] & 0xF);
        }
        return out;
    }

    /** RLE-pack the frame: (value byte, run-length VarInt) pairs. Runs are
     *  never split by the encoder, so a band boundary may still cut one. */
    public static byte[] packRle(byte[] pixels) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int i = 0;
        while (i < pixels.length) {
            byte value = pixels[i];
            int run = 1;
            while (i + run < pixels.length && pixels[i + run] == value) {
                run++;
            }
            out.write(value);
            writeVarInt(out, run);
            i += run;
        }
        return out.toByteArray();
    }

    /** Inverse of {@link #packRle}; {@code expectedPixels} must be w×h. A run
     *  that overshoots the frame, a truncated VarInt, a zero-length run or a
     *  decoded total != expectedPixels yields null (malformed packet). */
    public static byte @Nullable [] unpackRle(byte[] data, int expectedPixels) {
        byte[] out = new byte[expectedPixels];
        int pos = 0;
        int outIdx = 0;
        while (pos < data.length) {
            byte value = data[pos++];
            int run = 0;
            int shift = 0;
            int b;
            do {
                if (pos >= data.length || shift > 28) {
                    return null;
                }
                b = data[pos++];
                run |= (b & 0x7F) << shift;
                shift += 7;
            } while ((b & 0x80) != 0);
            if (run <= 0 || run > expectedPixels - outIdx) {
                return null;
            }
            Arrays.fill(out, outIdx, outIdx + run, value);
            outIdx += run;
        }
        return outIdx == expectedPixels ? out : null;
    }

    private static void writeVarInt(ByteArrayOutputStream out, int value) {
        int v = value;
        while ((v & 0xFFFFFF80) != 0) {
            out.write((v & 0x7F) | 0x80);
            v >>>= 7;
        }
        out.write(v);
    }

    /** Pack ARGB ints big-endian (null/empty -> EMPTY, meaning "default palette"). */
    public static byte[] encodePalette(int[] palette) {
        if (palette == null || palette.length == 0) {
            return new byte[0];
        }
        byte[] out = new byte[palette.length * 4];
        for (int i = 0; i < palette.length; i++) {
            out[i * 4] = (byte) (palette[i] >>> 24);
            out[i * 4 + 1] = (byte) (palette[i] >>> 16);
            out[i * 4 + 2] = (byte) (palette[i] >>> 8);
            out[i * 4 + 3] = (byte) palette[i];
        }
        return out;
    }

    /** Inverse of encodePalette; malformed lengths (not a multiple of 4)
     *  yield null — the consumer then keeps the default palette. */
    public static int[] decodePalette(byte[] bytes) {
        if (bytes.length == 0 || bytes.length % 4 != 0) {
            return null;
        }
        int[] out = new int[bytes.length / 4];
        for (int i = 0; i < out.length; i++) {
            out[i] = (bytes[i * 4] & 0xFF) << 24 | (bytes[i * 4 + 1] & 0xFF) << 16
                    | (bytes[i * 4 + 2] & 0xFF) << 8 | (bytes[i * 4 + 3] & 0xFF);
        }
        return out;
    }

    @Override
    public @NotNull Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(CanvasSyncPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (FMLEnvironment.dist.isClient()) {
                com.bridgecore.client.HoloTextureCache.update(payload.pos(),
                        payload.width(), payload.height(), payload.encoding(),
                        payload.bandIndex(), payload.bandCount(), payload.pixels(),
                        decodePalette(payload.paletteBytes()));
            }
        });
    }
}
