package tachyon;

import tachyon.path.World;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.BubbleColumnBlock;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.material.FluidState;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * The path finder's view of the world for a bot, read OFF the server's thread.
 *
 * <p>The server's own thread takes the snapshot: which chunks are loaded around the
 * start and the goal ({@code getChunkNow} only answers on that thread, and never loads
 * one). The search then runs on a thread of its own and reads those chunks' blocks
 * directly: reading is not synchronised with the server writing, so a block changed in
 * the middle of a search may be read before or after the change, and a read that trips
 * on a section being resized counts as a wall. What a search costs the tick is taking
 * the snapshot, not the search.
 *
 * <p>What is solid, a floor, a door or a danger is decided as Masurium's client bots
 * decide it. Two things of the bot's own go with a snapshot, copied as it is taken, so the
 * search thread reads them without touching the bot: the tiles it got stuck on lately
 * ({@link #vetoing}: not stood on, except where it stands now), and the blocks it may break
 * on its own to get through ({@link #breaking}: its break list, for a route that digs).
 */
final class SnapshotWorld implements World, BlockGetter {

    private static final byte AIR = 0, SOLID = 1, WATER = 2, LAVA = 3, DOOR = 4, DANGER = 5;

    private final Map<Long, LevelChunk> chunks;
    private final int minY, height;
    private final Map<Long, Byte> cache = new HashMap<>();
    private final BlockPos.MutableBlockPos aux = new BlockPos.MutableBlockPos();
    /** Tiles not to stand on (where the bot got stuck lately): {@link BlockPos#asLong} of each. */
    private Set<Long> vetoed = Set.of();
    /** The blocks it may break on its own to get through; none unless a route may dig. */
    private Set<Block> mayBreak = Set.of();

    private SnapshotWorld(Map<Long, LevelChunk> chunks, int minY, int height) {
        this.chunks = chunks;
        this.minY = minY;
        this.height = height;
    }

    /**
     * The loaded chunks of the box between two points, widened by {@code margin} blocks.
     * On the server's thread only. At most {@code maxChunks} of them, taken in rings
     * outward from {@code a}'s chunk (the bot's): taken row by row from a corner, a box
     * bigger than that left out the chunks the bot stood in, which read as bedrock, and
     * every search failed at its start. A longer trip is searched a stretch at a time.
     */
    static SnapshotWorld around(ServerLevel level, BlockPos a, BlockPos b, int margin, int maxChunks) {
        int x0 = (Math.min(a.getX(), b.getX()) - margin) >> 4, x1 = (Math.max(a.getX(), b.getX()) + margin) >> 4;
        int z0 = (Math.min(a.getZ(), b.getZ()) - margin) >> 4, z1 = (Math.max(a.getZ(), b.getZ()) + margin) >> 4;
        Map<Long, LevelChunk> found = new HashMap<>();
        int ax = a.getX() >> 4, az = a.getZ() >> 4;
        int rings = Math.max(Math.max(ax - x0, x1 - ax), Math.max(az - z0, z1 - az));
        take(level, found, ax, az, x0, x1, z0, z1);
        // Each ring is walked round its edge only: the chunks inside it are taken already.
        for (int ring = 1; ring <= rings && found.size() < maxChunks; ring++) {
            for (int i = -ring; i <= ring && found.size() < maxChunks; i++) {
                take(level, found, ax + i, az - ring, x0, x1, z0, z1);
                take(level, found, ax + i, az + ring, x0, x1, z0, z1);
            }
            for (int i = -ring + 1; i < ring && found.size() < maxChunks; i++) {
                take(level, found, ax - ring, az + i, x0, x1, z0, z1);
                take(level, found, ax + ring, az + i, x0, x1, z0, z1);
            }
        }
        return new SnapshotWorld(found, level.getMinBuildHeight(), level.getHeight());
    }

    /** A chunk of the box, if loaded; none outside the box. */
    private static void take(ServerLevel level, Map<Long, LevelChunk> found, int cx, int cz, int x0, int x1, int z0, int z1) {
        if (cx < x0 || cx > x1 || cz < z0 || cz > z1) return;
        LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
        if (chunk != null) found.put(ChunkPos.asLong(cx, cz), chunk);
    }

    /**
     * Tiles not to stand on: where the bot got stuck lately ({@code BlockPos.asLong} of
     * each, a copy the search may keep). As it is taken, before the search starts.
     */
    SnapshotWorld vetoing(Set<Long> tiles) {
        this.vetoed = tiles;
        return this;
    }

    /**
     * The blocks it may break on its own to get through, for a route that digs (a copy the
     * search may keep). As it is taken, before the search starts.
     */
    SnapshotWorld breaking(Set<Block> blocks) {
        this.mayBreak = blocks;
        return this;
    }

    int chunkCount() {
        return chunks.size();
    }

    int queried() {
        return cache.size();
    }

    // --- BlockGetter: what a block's collision shape may ask about its neighbours ---

    @Override
    public BlockState getBlockState(BlockPos pos) {
        LevelChunk chunk = chunks.get(ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4));
        if (chunk == null) return Blocks.BEDROCK.defaultBlockState();   // unknown is a wall
        return chunk.getBlockState(pos);
    }

    @Override
    public FluidState getFluidState(BlockPos pos) {
        return getBlockState(pos).getFluidState();
    }

    @Override
    public BlockEntity getBlockEntity(BlockPos pos) {
        return null;
    }

    @Override
    public int getHeight() {
        return height;
    }

    @Override
    public int getMinBuildHeight() {
        return minY;
    }

    // --- World: the path finder's questions ---

    /** Whether the column's chunk is in the snapshot: the rest is read as bedrock. */
    @Override
    public boolean known(int x, int z) {
        return chunks.containsKey(ChunkPos.asLong(x >> 4, z >> 4));
    }

    @Override
    public boolean solid(int x, int y, int z) {
        return type(x, y, z) == SOLID;
    }

    @Override
    public boolean water(int x, int y, int z) {
        return type(x, y, z) == WATER;
    }

    @Override
    public boolean lava(int x, int y, int z) {
        return type(x, y, z) == LAVA;
    }

    @Override
    public boolean door(int x, int y, int z) {
        return type(x, y, z) == DOOR;
    }

    @Override
    public boolean dangerous(int x, int y, int z) {
        return type(x, y, z) == DANGER;
    }

    /**
     * The usual, minus the tiles where the bot got stuck lately: the same search from the
     * same place found the same route again, into the tile the body had just failed to get
     * into six times. Masurium's stuck spots.
     */
    @Override
    public boolean canStand(int x, int y, int z) {
        if (!vetoed.isEmpty() && vetoed.contains(BlockPos.asLong(x, y, z))) return false;
        return canStandWithoutVeto(x, y, z);
    }

    /**
     * Whether one can stand here, stuck spots or not: for the tile a search starts from.
     * Vetoing the tile the bot stands on left it unable to start any trip for 90 s.
     */
    boolean canStandWithoutVeto(int x, int y, int z) {
        if (!World.super.canStand(x, y, z)) return false;
        aux.set(x, y - 1, z);
        BlockState ground = read(aux);
        if (ground.is(Blocks.MAGMA_BLOCK)) return false;
        return !(ground.is(BlockTags.CAMPFIRES) && ground.getValue(CampfireBlock.LIT));
    }

    /**
     * Whether a route may go through this block by breaking it: solid, not unbreakable
     * (bedrock, barriers), and on the bot's break list. Asked only by a search that may dig,
     * and only of blocks already in the way of a step.
     */
    @Override
    public boolean breakable(int x, int y, int z) {
        if (mayBreak.isEmpty() || !solid(x, y, z)) return false;
        aux.set(x, y, z);
        BlockState state = read(aux);
        if (!mayBreak.contains(state.getBlock())) return false;
        try {
            return state.getDestroySpeed(this, aux) >= 0;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private BlockState read(BlockPos pos) {
        try {
            return getBlockState(pos);
        } catch (RuntimeException e) {
            // Read while the server was writing that section: taken for a wall.
            return Blocks.BEDROCK.defaultBlockState();
        }
    }

    private byte type(int x, int y, int z) {
        long key = BlockPos.asLong(x, y, z);
        Byte saved = cache.get(key);
        if (saved != null) return saved;
        byte t = classify(x, y, z);
        cache.put(key, t);
        return t;
    }

    private byte classify(int x, int y, int z) {
        if (y < minY || y >= minY + height) return y < minY ? SOLID : AIR;
        aux.set(x, y, z);
        BlockState state = read(aux);
        byte t;
        if (state.is(Blocks.BUBBLE_COLUMN) && state.getValue(BubbleColumnBlock.DRAG_DOWN)) {
            t = LAVA;
        } else if (!state.getFluidState().isEmpty()) {
            t = state.getFluidState().is(FluidTags.LAVA) ? LAVA : WATER;
        } else if (state.isAir()) {
            t = AIR;
        } else {
            var box = safeShape(state, aux);
            if (box == null) {
                t = SOLID;
            } else if (box.isEmpty()) {
                t = AIR;
            } else if (box.max(Direction.Axis.Y) <= 0.5
                    || (state.is(BlockTags.TRAPDOORS) && state.getValue(TrapDoorBlock.OPEN))) {
                t = AIR;
            } else {
                t = SOLID;
            }
            if (t == AIR && (state.is(Blocks.SWEET_BERRY_BUSH) || state.is(Blocks.FIRE)
                    || state.is(Blocks.SOUL_FIRE) || state.is(Blocks.POWDER_SNOW)
                    || state.is(Blocks.WITHER_ROSE))) {
                t = DANGER;
            }
            if (t == SOLID && (state.is(BlockTags.WOODEN_DOORS) || state.is(BlockTags.FENCE_GATES))) {
                t = DOOR;
            }
        }
        // Fences and walls are 1.5 tall: the cell above one is taken too. Not above a closed
        // fence gate, which is as tall but opens: its upper half counted as a wall left the
        // head's tile blocked, and a gate was a wall even to a bot that opens it.
        if (t == AIR) {
            aux.set(x, y - 1, z);
            BlockState below = read(aux);
            var belowBox = below.isAir() ? null : safeShape(below, aux);
            if (belowBox != null && !belowBox.isEmpty() && belowBox.max(Direction.Axis.Y) > 1.0
                    && !below.is(BlockTags.FENCE_GATES)) {
                t = SOLID;
            }
        }
        return t;
    }

    private net.minecraft.world.phys.shapes.VoxelShape safeShape(BlockState state, BlockPos pos) {
        try {
            return state.getCollisionShape(this, pos);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
