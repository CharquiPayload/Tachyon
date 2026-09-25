package tachyon;

import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.saveddata.SavedData;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.util.BlockSnapshot;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.ExplosionEvent;

/**
 * The blocks players placed, so that a bot never takes a piece of someone's build unless
 * a person orders it to: a bot that gathers leaves them alone ({@link Gather}), and its
 * brain's {@code clear} refuses a box that holds one ({@link Clearing}). The day this was
 * written, a bot asked for dirt had nothing to get it with but {@code clear}, and cleared a
 * box around itself: nine blocks of its owner's house.
 *
 * <p>A player sees a build for what it is; the server has only the blocks. So it keeps where
 * players placed them, as they place them (NeoForge's place event, by a player and not a
 * bot: what a bot places, a bot may take back), per level, with the world
 * ({@code <dimension>/data/tachyon_placed.dat}, written with the chunks, so that the two
 * agree after a crash too). A place is forgotten when its block is broken (by anyone), blown
 * up, or found to be air or a liquid when asked about (burned, washed away). It is bounded:
 * past {@link #MAX} places in a level the oldest are forgotten, as a player forgets. What a
 * piston pushes, or sand a player placed that falls, is not followed to where it goes.
 * Builds from before the mod was there are not in it: {@link Gather} also leaves alone what
 * touches a building block.
 */
final class PlacedBlocks implements Ability {

    /** Places kept per level at most (about 3 MB of memory, 800 kB on disk): past it, the oldest go. */
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
    }

    /** A block placed: kept, if a player placed it. A bed or a door is two blocks, in one event. */
    private static void placed(BlockEvent.EntityPlaceEvent e) {
        if (!(e.getEntity() instanceof Player pl) || Bots.of(pl) != null || !(e.getLevel() instanceof ServerLevel level)) return;
        Places places = of(level);
        if (e instanceof BlockEvent.EntityMultiPlaceEvent many) {
            for (BlockSnapshot s : many.getReplacedBlockSnapshots()) places.add(s.getPos());
        } else {
            places.add(e.getPos());
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

        /** A place, the newest now (placed again: it goes to the end); the oldest forgotten past {@link #MAX}. */
        void add(BlockPos pos) {
            set.addAndMoveToLast(pos.asLong());
            while (set.size() > MAX) set.removeFirstLong();
            setDirty();
        }

        void remove(BlockPos pos) {
            if (set.remove(pos.asLong())) setDirty();
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
            return p;
        }
    }
}
