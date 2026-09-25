package tachyon;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.saveddata.SavedData;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.util.BlockSnapshot;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.BlockGrowFeatureEvent;
import net.neoforged.neoforge.event.level.ExplosionEvent;

import java.util.Arrays;

/**
 * The blocks players placed, so that a bot never takes a piece of someone's build unless
 * a person orders it to: a bot that gathers leaves them alone ({@link Gather}), one that
 * digs through to get somewhere never digs one ({@link Tunnelling}), and its brain's
 * {@code clear} refuses a box that holds one ({@link Clearing}). The day this was
 * written, a bot asked for dirt had nothing to get it with but {@code clear}, and cleared a
 * box around itself: nine blocks of its owner's house.
 *
 * <p>A player sees a build for what it is; the server has only the blocks. So it keeps where
 * players placed them, as they place them (NeoForge's place event, by a player and not a
 * bot: what a bot places, a bot may take back; and only blocks placed from the hand as they
 * are, not what an item's use changed: bone meal on a sapling fires a place event for all 61
 * blocks of the tree it grows), per level, with the world
 * ({@code <dimension>/data/tachyon_placed.dat}, written with the chunks, so that the two
 * agree after a crash too). A place is forgotten when its block is broken (by anyone), blown
 * up, or found to be air or a liquid when asked about (burned, washed away). It is bounded:
 * past {@link #MAX} places in a level the oldest are forgotten, as a player forgets. A
 * sapling that grows into a tree is forgotten as it grows: the tree grew, nobody built it,
 * and the log that takes the sapling's place is no piece of a build. What a piston pushes,
 * sand a player placed that falls, or a block an operator's {@code /fill} put over a placed
 * one, is not followed.
 * Builds from before the mod was there are not in it: {@link Gather} also leaves alone what
 * touches a building block.
 */
final class PlacedBlocks implements Ability {

    /** Places kept per level at most (about 4 MB of memory, 800 kB on disk): past it, the oldest go. */
    static final int MAX = 100_000;
    /** The name of the level's file, under its data folder. */
    private static final String FILE = "tachyon_placed";
    /** How the game makes a level's places: empty, or read from its file. */
    private static final SavedData.Factory<Places> FACTORY = new SavedData.Factory<>(Places::new, Places::load);

    @Override
    public void events(IEventBus bus) {
        // After every other mod had its say (a protection that cancels the place), and never
        // for a place that was cancelled: the block is not there then.
        bus.addListener(EventPriority.LOWEST, BlockEvent.EntityPlaceEvent.class, PlacedBlocks::placed);
        bus.addListener(EventPriority.LOWEST, BlockEvent.BreakEvent.class, e -> {
            if (e.getLevel() instanceof ServerLevel level) forget(level, e.getPos());
        });
        bus.addListener(EventPriority.LOWEST, ExplosionEvent.Detonate.class, e -> {
            if (!(e.getLevel() instanceof ServerLevel level)) return;
            Places places = of(level);
            for (BlockPos pos : e.getAffectedBlocks()) places.remove(pos);
        });
        bus.addListener(EventPriority.LOWEST, BlockGrowFeatureEvent.class, PlacedBlocks::grows);
    }

    /**
     * A block placed: kept, if a player placed it from the hand, as the block it is (the item
     * in either hand is that block's): a bed or a door is two blocks, in one event. What an
     * item's use changed around it (a tree bone meal grew, water a bucket poured) is not a
     * build, and is not kept.
     */
    private static void placed(BlockEvent.EntityPlaceEvent e) {
        if (!(e.getEntity() instanceof Player pl) || Bots.of(pl) != null || !(e.getLevel() instanceof ServerLevel level)) return;
        Places places = of(level);
        if (e instanceof BlockEvent.EntityMultiPlaceEvent many) {
            for (BlockSnapshot s : many.getReplacedBlockSnapshots()) {
                if (fromHand(pl, level.getBlockState(s.getPos()))) places.add(s.getPos());
            }
        } else if (fromHand(pl, e.getPlacedBlock())) {
            places.add(e.getPos());
        }
    }

    /**
     * Whether a block is what the player holds, in either hand: placed as it is, from the hand.
     * By the block's own item, which a wall torch or a wall sign shares with the standing one.
     */
    private static boolean fromHand(Player pl, BlockState placed) {
        Item item = placed.getBlock().asItem();
        if (item == Items.AIR) return false;
        for (InteractionHand hand : InteractionHand.values()) {
            if (pl.getItemInHand(hand).is(item)) return true;
        }
        return false;
    }

    /**
     * A sapling (or a mushroom, a fungus) about to grow into a tree: forgotten, and the other
     * saplings of a 2x2 tree with it, beside it at its level, still saplings as it fires.
     */
    private static void grows(BlockGrowFeatureEvent e) {
        if (!(e.getLevel() instanceof ServerLevel level)) return;
        Places places = of(level);
        BlockPos at = e.getPos();
        places.remove(at);
        for (BlockPos n : BlockPos.betweenClosed(at.offset(-1, 0, -1), at.offset(1, 0, 1))) {
            if (places.has(n) && level.getBlockState(n).is(BlockTags.SAPLINGS)) places.remove(n.immutable());
        }
    }

    private static void forget(ServerLevel level, BlockPos pos) {
        of(level).remove(pos);
    }

    /** Whether a player placed the block at {@code pos}, and it is still there (see {@link Places#byPlayer}). */
    static boolean byPlayer(ServerLevel level, BlockPos pos) {
        return of(level).byPlayer(level, pos);
    }

    /**
     * How many blocks players placed are still in the box: its blocks looked up, or the
     * level's places, whichever are fewer. For a box to clear (100,000 blocks at most).
     */
    static int count(ServerLevel level, BlockPos a, BlockPos b) {
        Places places = of(level);
        int x0 = Math.min(a.getX(), b.getX()), x1 = Math.max(a.getX(), b.getX());
        int y0 = Math.min(a.getY(), b.getY()), y1 = Math.max(a.getY(), b.getY());
        int z0 = Math.min(a.getZ(), b.getZ()), z1 = Math.max(a.getZ(), b.getZ());
        int n = 0;
        if (Bots.volume(a, b) <= places.size()) {
            for (BlockPos pos : BlockPos.betweenClosed(x0, y0, z0, x1, y1, z1)) {
                if (places.has(pos) && !gone(level.getBlockState(pos))) n++;
            }
            return n;
        }
        for (LongIterator it = places.iterator(); it.hasNext(); ) {
            BlockPos pos = BlockPos.of(it.nextLong());
            if (pos.getX() >= x0 && pos.getX() <= x1 && pos.getY() >= y0 && pos.getY() <= y1 && pos.getZ() >= z0
                    && pos.getZ() <= z1 && !gone(level.getBlockState(pos))) {
                n++;
            }
        }
        return n;
    }

    /** A place whose block is gone: air, or a liquid over it (a torch washed away). */
    private static boolean gone(BlockState s) {
        return s.isAir() || s.getBlock() instanceof LiquidBlock;
    }

    /**
     * A level's places, read from its file the first time they are asked for (then kept with
     * the level). A job that asks about many blocks takes them once. On the server's thread.
     */
    static Places of(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(FACTORY, FILE);
    }

    /**
     * One level's places, oldest first, as {@code BlockPos.asLong} numbers: the game's own
     * saved data, written with the level when it saves, whenever it changed.
     */
    static final class Places extends SavedData {
        private final LongLinkedOpenHashSet set = new LongLinkedOpenHashSet();
        /**
         * The same places by chunk ({@link ChunkPos#asLong}), each chunk's a sorted array
         * that a change replaces whole and nothing writes into after: what a route search
         * reads off the server's thread ({@link #inChunk}), where the set, changing under
         * it, could not be read. Players place a few blocks a second at most, so copying a
         * chunk's array on each costs nothing that shows.
         */
        private final Long2ObjectOpenHashMap<long[]> byChunk = new Long2ObjectOpenHashMap<>();

        /** A place, the newest now (placed again: it goes to the end); the oldest forgotten past {@link #MAX}. */
        void add(BlockPos pos) {
            long at = pos.asLong();
            if (set.addAndMoveToLast(at)) index(at, true);
            while (set.size() > MAX) index(set.removeFirstLong(), false);
            setDirty();
        }

        void remove(BlockPos pos) {
            long at = pos.asLong();
            if (set.remove(at)) {
                index(at, false);
                setDirty();
            }
        }

        /**
         * The places in one chunk, sorted ({@code BlockPos.asLong}), or null for none. The
         * array is never changed after it is handed out: a search on another thread may keep
         * it and look places up in it ({@code Arrays.binarySearch}) while players build on.
         */
        long[] inChunk(long chunk) {
            return byChunk.get(chunk);
        }

        /** A place put in its chunk's array, or taken out of it: a new array either way. */
        private void index(long at, boolean in) {
            long chunk = chunkOf(at);
            long[] old = byChunk.get(chunk);
            int i = old == null ? -1 : Arrays.binarySearch(old, at);
            if (in) {
                if (i >= 0) return;
                int to = old == null ? 0 : -i - 1;
                long[] now = new long[old == null ? 1 : old.length + 1];
                if (old != null) {
                    System.arraycopy(old, 0, now, 0, to);
                    System.arraycopy(old, to, now, to + 1, old.length - to);
                }
                now[to] = at;
                byChunk.put(chunk, now);
            } else if (i >= 0) {
                if (old.length == 1) {
                    byChunk.remove(chunk);
                    return;
                }
                long[] now = new long[old.length - 1];
                System.arraycopy(old, 0, now, 0, i);
                System.arraycopy(old, i + 1, now, i, old.length - i - 1);
                byChunk.put(chunk, now);
            }
        }

        /** Every chunk's array made from the set at once: as a level's places are read. */
        private void reindex() {
            Long2ObjectOpenHashMap<LongArrayList> lists = new Long2ObjectOpenHashMap<>();
            for (LongIterator it = set.iterator(); it.hasNext(); ) {
                long at = it.nextLong();
                lists.computeIfAbsent(chunkOf(at), c -> new LongArrayList()).add(at);
            }
            byChunk.clear();
            for (var e : lists.long2ObjectEntrySet()) {
                long[] a = e.getValue().toLongArray();
                Arrays.sort(a);
                byChunk.put(e.getLongKey(), a);
            }
        }

        private static long chunkOf(long at) {
            return ChunkPos.asLong(BlockPos.getX(at) >> 4, BlockPos.getZ(at) >> 4);
        }

        boolean has(BlockPos pos) {
            return set.contains(pos.asLong());
        }

        /**
         * Whether a player placed the block at {@code pos}, and it is still there: a place
         * whose block is gone (air, or a liquid over it) is forgotten here. On the server's
         * thread.
         */
        boolean byPlayer(ServerLevel level, BlockPos pos) {
            if (!has(pos)) return false;
            if (gone(level.getBlockState(pos))) {
                remove(pos);
                return false;
            }
            return true;
        }

        int size() {
            return set.size();
        }

        LongIterator iterator() {
            return set.iterator();
        }

        @Override
        public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
            tag.putLongArray("places", set.toLongArray());
            return tag;
        }

        static Places load(CompoundTag tag, HolderLookup.Provider registries) {
            Places p = new Places();
            for (long pos : tag.getLongArray("places")) p.set.add(pos);
            while (p.set.size() > MAX) p.set.removeFirstLong();
            p.reindex();
            return p;
        }
    }
}
