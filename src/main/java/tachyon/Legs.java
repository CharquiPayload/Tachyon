package tachyon;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import tachyon.path.Route;

/**
 * Going out looking for something none of which is in sight, as Masurium's bots did and
 * {@link Hunt} does for prey: in legs of {@link #LEG} blocks one way (the way it was told,
 * or the way it faces), any height ("X and Z first, Y up close"), each searched from where
 * the last one ended; three legs in a row that get it nowhere (sea, cliff, no way) and it
 * turns right; for {@link #BLOCKS} blocks or {@link #TICKS 3 minutes} at most, and
 * {@link #SEARCHES twice} an errand at most. The job that owns it looks around as it walks
 * and stops it once something is in sight. Hunt keeps a copy of its own, from before this
 * was made; the numbers are the same.
 *
 * <p>A search is a walk the legs plan (a route search on a thread of its own, at most once
 * a second): nothing heavy runs on the server's thread.
 */
final class Legs {

    /** A leg's length, how far and how long a search goes at most, and how many searches an errand has. */
    static final int LEG = 48, BLOCKS = 300, TICKS = 20 * 180, SEARCHES = 2;
    /** A leg that gets it this little further counts as none; three of them, it turns; four turns, it gives up. */
    private static final double PROGRESS = 2.0;
    private static final int STUCK = 3, TURNS_MAX = 4;

    private boolean out;
    /** A leg was searched and has not been looked at since: it is walked, or it found no way. */
    private boolean leg;
    private int searches, stuck, turns;
    private Direction heading;
    private double originX, originZ, best, far;
    private long started;

    /** Whether it is out looking now. */
    boolean out() {
        return out;
    }

    /** It found something: the search is over (it may start another later, while it has searches left). */
    void found() {
        out = false;
    }

    /** "to the north (40 blocks out)": where it looks, for a job's status. */
    String where() {
        return "to the " + heading.getName() + " (" + Math.round(far) + " blocks out)";
    }

    /**
     * A search from where it stands, if it has one left this errand: the first one {@code
     * toward} (null: the way it faces), the next the way it faces then.
     *
     * @return false when its searches are spent
     */
    boolean begin(Bots.Bot p, long now, Direction toward) {
        if (searches >= SEARCHES) return false;
        BotPlayer b = p.body;
        out = true;
        searches++;
        heading = searches == 1 && toward != null ? toward : b.getDirection();
        originX = b.getX();
        originZ = b.getZ();
        best = 0;
        far = 0;
        stuck = 0;
        turns = 0;
        leg = false;
        started = now;
        return true;
    }

    /**
     * A tick of the search: the next leg searched when the last is walked, or found no way.
     * Each leg is weighed once, as it ends: one that got it nowhere counts toward a turn.
     * {@code doing} is what the bot says meanwhile.
     *
     * @return null while it goes on; once it is over, what it did, in words for the job's
     *         last line ("I looked 300 blocks to the north")
     */
    String step(Bots.Bot p, long now, String doing) {
        BotPlayer b = p.body;
        double progress = Math.max(0, (b.getX() - originX) * heading.getStepX() + (b.getZ() - originZ) * heading.getStepZ());
        far = Math.max(far, progress);
        if (now - started > TICKS) {
            out = false;
            return "I looked for 3 minutes (" + Math.round(far) + " blocks to the " + heading.getName() + ")";
        }
        if (p.path != null || p.pending != null) return null;          // on the way
        if (progress >= BLOCKS) {
            out = false;
            return "I looked " + Math.round(progress) + " blocks to the " + heading.getName();
        }
        if (leg) {
            leg = false;
            weigh(b, progress);
            if (turns > TURNS_MAX) {
                out = false;
                return "I looked on all four sides and found no way on";
            }
        }
        if (now - p.plannedAt < Bots.REPLAN_TICKS) return null;
        p.plannedAt = now;
        leg = true;
        int ox = b.getBlockX() + heading.getStepX() * LEG, oz = b.getBlockZ() + heading.getStepZ() * LEG;
        Bots.plan(p, new BlockPos(ox, b.getBlockY(), oz), world -> Route.Meta.onlyXZ(ox, oz), Hunt.chase(b), doing);
        return null;
    }

    /** A leg ended {@code progress} blocks out: further than before, or one more that got it nowhere. */
    private void weigh(BotPlayer b, double progress) {
        if (progress < best + PROGRESS) {
            if (++stuck >= STUCK) {
                if (++turns > TURNS_MAX) return;
                heading = heading.getClockWise();
                originX = b.getX();
                originZ = b.getZ();
                best = 0;
                stuck = 0;
            }
        } else {
            best = progress;
            stuck = 0;
        }
    }
}
