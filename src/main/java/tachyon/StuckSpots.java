package tachyon;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * The tiles a bot's legs failed to get into lately, Masurium's stuck spots: for
 * {@value #LASTS_TICKS} ticks (90 s) no search of that bot stands on them, builds onto them
 * or digs into them, except the tile it starts from ({@link SnapshotWorld#startingAt}).
 *
 * <p>Searching again from the same place used to find the same route: to the path finder
 * that tile was still one to stand on, though the body had just jumped six times without
 * getting in (a mob in the way, a thin block the path finder takes for air, a corner the
 * body catches on). Vetoed, every search of the bot (a trip, a follow, a job's) goes round
 * what just failed. Not for ever, and never for the other bots: the world changes, a stuck
 * spot may have been a pig in the way, and a crowd pushing one bot aside must not close a
 * tile to all of them.
 *
 * <p>Each bot's own, on the server's thread. It holds {@value #MAX} tiles at most, the
 * oldest dropped first, and only those of one dimension: nothing grows without limit.
 */
final class StuckSpots {

    /** How long a tile stays vetoed: 90 s. */
    static final int LASTS_TICKS = 20 * 90;
    /** How many tiles are kept at most. */
    static final int MAX = 32;

    /** Each tile ({@link BlockPos#asLong}), and the tick it is free again; oldest first. */
    private final Map<Long, Long> until = new LinkedHashMap<>();
    private ResourceKey<Level> level;

    /** The tile it failed to get into, vetoed from now. */
    void mark(ResourceKey<Level> in, int x, int y, int z, long now) {
        if (!in.equals(level)) {
            until.clear();
            level = in;
        }
        long key = BlockPos.asLong(x, y, z);
        until.remove(key);
        until.put(key, now + LASTS_TICKS);
        while (until.size() > MAX) {
            Iterator<Long> oldest = until.keySet().iterator();
            oldest.next();
            oldest.remove();
        }
    }

    /** The tiles vetoed now in that dimension: a copy, for a search to keep. Empty nearly always. */
    Set<Long> now(ResourceKey<Level> in, long now) {
        if (until.isEmpty() || !in.equals(level)) return Set.of();
        until.values().removeIf(t -> t <= now);
        return until.isEmpty() ? Set.of() : Set.copyOf(until.keySet());
    }
}
