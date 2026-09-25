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
import net.minecraft.world.phys.Vec3;

import java.util.Arrays;
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
 * ({@link #vetoing}: not stood on, except the tile the search starts from, see
 * {@link #startingAt}), and the blocks it may break on its own to get through
 * ({@link #breaking}: its break list, for a route that digs), with the blocks players placed
 * in its chunks, which it never digs ({@link #sparing}).
 */
final class SnapshotWorld implements World, BlockGetter {

    private static final byte AIR = 0, SOLID = 1, WATER = 2, LAVA = 3, DOOR = 4, DANGER = 5;

    private final Map<Long, LevelChunk> chunks;
    private final int minY, height;
    private final Map<Long, Byte> cache = new HashMap<>();
    private final BlockPos.MutableBlockPos aux = new BlockPos.MutableBlockPos();
    /** Tiles not to stand on (where the bot got stuck lately): {@link BlockPos#asLong} of each. */
    private Set<Long> vetoed = Set.of();
    /** The tile the search starts from ({@link BlockPos#asLong}), never vetoed: see {@link #startingAt}. */
    private long start = Long.MIN_VALUE;
    /** The blocks it may break on its own to get through; none unless a route may dig. */
    private Set<Block> mayBreak = Set.of();
    /** The blocks players placed, by chunk, sorted ({@link PlacedBlocks.Places#inChunk}): never dug through. */
    private Map<Long, long[]> placed = Map.of();
    /** What {@link #breakable} answered for each block asked, and whether each kind of block is one players build with. */
    private final Map<Long, Boolean> breakables = new HashMap<>();
    private final Map<BlockState, Boolean> building = new HashMap<>();

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
     *
     * <p>Each ring is walked only where it lies in the box, and the rings stop at the first
     * one with no loaded chunk in it: every way from the bot to what lies beyond it crosses
     * it, and it reads as rock, so nothing beyond could be reached. A trip 29,000 blocks off
     * once walked rings all the way out to the destination's distance, 19 ms on the
     * server's thread for each of its legs; now it costs what the loaded chunks between
     * cost, a few hundred lookups at most.
     */
    static SnapshotWorld around(ServerLevel level, BlockPos a, BlockPos b, int margin, int maxChunks) {
        int x0 = (Math.min(a.getX(), b.getX()) - margin) >> 4, x1 = (Math.max(a.getX(), b.getX()) + margin) >> 4;
        int z0 = (Math.min(a.getZ(), b.getZ()) - margin) >> 4, z1 = (Math.max(a.getZ(), b.getZ()) + margin) >> 4;
        Map<Long, LevelChunk> found = new HashMap<>();
        int ax = a.getX() >> 4, az = a.getZ() >> 4;
        int rings = Math.max(Math.max(ax - x0, x1 - ax), Math.max(az - z0, z1 - az));
        take(level, found, ax, az);
        for (int ring = 1; ring <= rings && found.size() < maxChunks; ring++) {
            int before = found.size();
            // Its rows north and south, then its columns west and east without their
            // corners (the rows have them): each clipped to the box.
            int from = Math.max(ax - ring, x0), to = Math.min(ax + ring, x1);
            for (int cz : new int[]{az - ring, az + ring}) {
                if (cz < z0 || cz > z1) continue;
                for (int cx = from; cx <= to && found.size() < maxChunks; cx++) take(level, found, cx, cz);
            }
            int zFrom = Math.max(az - ring + 1, z0), zTo = Math.min(az + ring - 1, z1);
            for (int cx : new int[]{ax - ring, ax + ring}) {
                if (cx < x0 || cx > x1) continue;
                for (int cz = zFrom; cz <= zTo && found.size() < maxChunks; cz++) take(level, found, cx, cz);
            }
            if (found.size() == before) break;
        }
        return new SnapshotWorld(found, level.getMinBuildHeight(), level.getHeight());
    }

    /** A chunk, if loaded. */
    private static void take(ServerLevel level, Map<Long, LevelChunk> found, int cx, int cz) {
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

    /** Whether any tile is vetoed now: a search that failed with some may be tried without them. */
    boolean vetoes() {
        return !vetoed.isEmpty();
    }

    /** How many tiles are vetoed, for the words of a search that failed with them. */
    int vetoedCount() {
        return vetoed.size();
    }

    /**
     * The tile the search starts from, on the search's thread, before it runs: never vetoed,
     * though the bot got stuck there. The body is on it: vetoing it left the bot unable to
     * start any search for 90 s ("where I am is not a spot where one can stand"), and a crowd
     * pushes bots back onto the very tiles they gave up on.
     */
    void startingAt(int x, int y, int z) {
        start = BlockPos.asLong(x, y, z);
    }

    /**
     * The blocks it may break on its own to get through, for a route that digs (a copy the
     * search may keep). As it is taken, before the search starts.
     */
    SnapshotWorld breaking(Set<Block> blocks) {
        this.mayBreak = blocks;
        return this;
    }

    /**
     * The blocks players placed in its chunks, which a route never digs through whatever its
     * break list says: a player's build is broken only on a person's order. Only for a route
     * that digs; as it is taken, on the server's thread. What it keeps are the level's own
     * arrays, which are never written after they are handed out, so nothing is copied.
     */
    SnapshotWorld sparing(PlacedBlocks.Places places) {
        Map<Long, long[]> out = new HashMap<>();
        for (long chunk : chunks.keySet()) {
            long[] in = places.inChunk(chunk);
            if (in != null) out.put(chunk, in);
        }
        this.placed = out;
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
        if (vetoed(x, y, z)) return false;
        return canStandWithoutVeto(x, y, z);
    }

    /** A tile it got stuck on lately, unless the search starts there. */
    @Override
    public boolean vetoed(int x, int y, int z) {
        if (vetoed.isEmpty()) return false;
        long at = BlockPos.asLong(x, y, z);
        return at != start && vetoed.contains(at);
    }

    /**
     * Whether one can stand here, stuck spots or not: for finding the tile a search starts
     * from, which is then exempt from them ({@link #startingAt}).
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
     * (bedrock, barriers), on the bot's break list, and neither placed by a player nor a
     * piece of a build older than the mod (touching a block players build with, or one they
     * placed: {@link Gather#building}, the rule a gatherer keeps); and no lava over it or
     * beside it, which would pour into the tunnel once the block is gone (Masurium's miners
     * never open a block with lava next to it). Asked only by a search that may dig, and only
     * of blocks already in the way of a step; each answer kept for the search.
     */
    @Override
    public boolean breakable(int x, int y, int z) {
        if (mayBreak.isEmpty() || !solid(x, y, z)) return false;
        long key = BlockPos.asLong(x, y, z);
        Boolean known = breakables.get(key);
        if (known != null) return known;
        boolean yes = digs(x, y, z);
        breakables.put(key, yes);
        return yes;
    }

    private boolean digs(int x, int y, int z) {
        BlockPos at = new BlockPos(x, y, z);
        BlockState state = read(at);
        if (!mayBreak.contains(state.getBlock()) || placedAt(x, y, z)) return false;
        if (lava(x, y + 1, z) || lava(x + 1, y, z) || lava(x - 1, y, z) || lava(x, y, z + 1) || lava(x, y, z - 1)) {
            return false;
        }
        for (Direction d : Direction.values()) {
            BlockPos n = at.relative(d);
            if (building.computeIfAbsent(read(n), Gather::building) || placedAt(n.getX(), n.getY(), n.getZ())) return false;
        }
        try {
            return state.getDestroySpeed(this, at) >= 0;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Whether a player placed the block there, as the snapshot's arrays have it. */
    private boolean placedAt(int x, int y, int z) {
        long[] built = placed.get(ChunkPos.asLong(x >> 4, z >> 4));
        return built != null && Arrays.binarySearch(built, BlockPos.asLong(x, y, z)) >= 0;
    }

    /**
     * Whether eyes at ({@code ex}, {@code ey}, {@code ez}) see a bit of the block at {@code pos}
     * ({@link Gather#sees}), over the snapshot: for a walk to where a block can be seen and
     * clicked. A read that trips on a section being written is no sight. On the search's
     * thread.
     */
    boolean sees(double ex, double ey, double ez, BlockPos pos) {
        try {
            return Gather.sees(this, new Vec3(ex, ey, ez), pos);
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
