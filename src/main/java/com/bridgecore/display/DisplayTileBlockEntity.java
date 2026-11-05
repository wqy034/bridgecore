package com.bridgecore.display;

import com.bridgecore.BCRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Screen tile: the dumb cell of a tiled display. Stores only its own pixel slice
 * (row-major ABGR, width×width), its facing, and a back-pointer to the GPU block
 * that owns the array. All intelligence lives in {@link DisplayGpuBlockEntity}.
 *
 * Sync: the slice rides the BE update tag for the initial chunk sync (tracked
 * chunks, i.e. ground builds); on ships the plot chunk is untracked, so live
 * updates arrive via DisplayTileSyncPayload broadcast and land straight in the
 * client texture cache keyed by position — see HudScreenBlockEntity for the why.
 */
public class DisplayTileBlockEntity extends BlockEntity {
    private BlockPos gpuPos;
    private int[] slice = new int[0];
    private int width;

    public DisplayTileBlockEntity(BlockPos pos, BlockState state) {
        super(BCRegistry.DISPLAY_TILE_BE.get(), pos, state);
    }

    public Direction getDirection() {
        return getBlockState().getValue(DisplayTileBlock.FACING);
    }

    /**
     * Block position of the array cell (x, y) relative to this tile, in the
     * screen's own frame: +x = right, +y = up as seen looking AT the screen.
     * The six-way table is what makes floor/ceiling screens tile correctly.
     */
    public BlockPos getOffset(int x, int y, Direction d) {
        int bx = worldPosition.getX();
        int by = worldPosition.getY();
        int bz = worldPosition.getZ();
        return switch (d) {
            case DOWN -> new BlockPos(bx + x, by, bz + y);
            case UP -> new BlockPos(bx + x, by, bz - y);
            case NORTH -> new BlockPos(bx - x, by + y, bz);
            case SOUTH -> new BlockPos(bx + x, by + y, bz);
            case WEST -> new BlockPos(bx, by + y, bz + x);
            case EAST -> new BlockPos(bx, by + y, bz - x);
        };
    }

    public DisplayTileBlockEntity connect(BlockPos gpuPos) {
        if (!gpuPos.equals(this.gpuPos)) {
            this.gpuPos = gpuPos;
            setChanged();
        }
        return this;
    }

    /** Drops the ownership link only. Controllers release tiles through
     *  DisplayGpuBlockEntity.releaseTile, which also wipes the slice and clears
     *  the clients' cached texture. */
    public void disconnect() {
        if (this.gpuPos != null) { this.gpuPos = null; setChanged(); }
    }

    public BlockPos gpuPos() {
        return gpuPos;
    }

    /** Server side: store the slice the GPU cut for us (dirty-check reference and
     *  initial chunk sync source). Returns the previous slice. */
    public int[] setSlice(int[] newSlice, int newWidth) {
        int[] old = this.slice;
        this.slice = newSlice;
        this.width = newWidth;
        return old;
    }

    public int[] slice() {
        return slice;
    }

    public int sliceWidth() {
        return width;
    }

    /** Right-click on the front face: convert the hit to tile-local pixels and
     *  report to the owning GPU. */
    public void onBlockActivated(Direction side, double x, double y, double z, boolean sneak,
                                 Player player) {
        if (getDirection() == side && gpuPos != null && width > 0 && level.hasChunkAt(gpuPos)
                && level.getBlockEntity(gpuPos) instanceof DisplayGpuBlockEntity gpu) {
            PixelPos p = getMonitorPixel(side, x, y, z);
            gpu.monitorClick(this, player, p.x(), p.y(), sneak);
        }
    }

    /** Hit point (block-local 0..1) → tile-local pixel. Handles all six facings. */
    public PixelPos getMonitorPixel(Direction side, double x, double y, double z) {
        int px;
        int py;
        if (side.getAxis() != Direction.Axis.Y) {
            double yPos = 1F - y;
            double xPos = Math.abs(side.getStepZ()) * x + Math.abs(side.getStepX()) * z;
            if ((side.getAxisDirection() == Direction.AxisDirection.NEGATIVE) != (side.getAxis() == Direction.Axis.X)) {
                xPos = 1F - xPos;
            }
            px = Mth.floor(xPos * width);
            py = Mth.floor(yPos * width);
        } else {
            double yPos = side == Direction.DOWN ? (1F - z) : z;
            px = Mth.floor(x * width);
            py = Mth.floor(yPos * width);
        }
        // A hit landing exactly on the far edge gives width; clamp into range.
        return new PixelPos(Mth.clamp(px, 0, width - 1), Mth.clamp(py, 0, width - 1));
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        if (gpuPos != null) {
            tag.putLong("gpu", gpuPos.asLong());
        }
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        if (tag.contains("gpu")) {
            gpuPos = BlockPos.of(tag.getLong("gpu"));
        }
        if (tag.contains("p") || tag.contains("s")) {
            width = tag.getShort("w");
            try {
                if (width < 0 || width > DisplayResolution.MAX) throw new IllegalArgumentException("Invalid display width");
                // The old raw array is read only for legacy update tags, never written.
                slice = tag.contains("p") ? DisplayPixelCodec.decode(tag.getByteArray("p"), width * width) : tag.getIntArray("s");
                if (slice.length != width * width) throw new IllegalArgumentException("Invalid display slice");
            } catch (IllegalArgumentException e) {
                width = 0; slice = new int[0];
                com.bridgecore.BridgeCoreMod.LOGGER.warn("Ignoring malformed initial display slice at {}", worldPosition);
            }
            if (net.neoforged.fml.loading.FMLEnvironment.dist.isClient()) {
                if (width > 0) com.bridgecore.client.DisplayTileTextureCache.update(getBlockPos(), width, slice);
                else com.bridgecore.client.DisplayTileTextureCache.remove(getBlockPos());
            }
        }
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = super.getUpdateTag(registries);
        tag.putByteArray("p", DisplayPixelCodec.encode(slice));
        tag.putShort("w", (short) width);
        return tag;
    }

    @Override
    public ClientboundBlockEntityDataPacket getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public void setRemoved() {
        super.setRemoved();
        // Client side only: drop the cached texture so a later tile at this same
        // position cannot show this tenant's last frame. The client class
        // reference is guarded by the dist check and never resolves on a server.
        if (level != null && level.isClientSide) {
            com.bridgecore.client.DisplayTileTextureCache.remove(getBlockPos());
        }
    }

    /** Tile-local pixel, 0-based. */
    public record PixelPos(int x, int y) {
    }
}
