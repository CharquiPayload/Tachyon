package tachyon;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import tachyon.path.Leg;
import tachyon.path.Route;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A trip to a place (go to, come here), near or hundreds of blocks off, walked a leg at a
 * time (see {@link Leg}) and judged as a whole: Masurium's Traveler.
 *
 * <p>Each leg is the route a search finds from where the last one ended: all the way, or
 * the stretch that gets closest. A leg's walk may end short (stuck, out of time), and the
 * next is searched from wherever the body is. What ends the trip, and each way says so with
 * the numbers:
 * <ul>
 * <li>arriving: within {@link Leg#ARRIVED} blocks of where it ends, flat, and 2 up or down;</li>
 * <li>three legs in a row that end no closer, by a block (in three dimensions: climbing
 *     toward a high place is getting closer), is being stuck, not bad luck: it stops,
 *     saying how the last walk ended short;</li>
 * <li>legs that find no way at all, for {@value #NO_LEG_TICKS} ticks (the world changes,
 *     chunks load, a mob moves off), a search every {@value #RETRY_TICKS}: it stops with the
 *     search's own words. The first leg is not waited for: with no way from the start there
 *     is nothing to wait for, as Masurium's {@code /go} answered at once.</li>
 * </ul>
 * The trip belongs to the legs ({@link Bots}), on the server's thread.
 */
final class Trip {

    /** Legs in a row that end no closer, before it gives up. */
    static final int STALLED_MAX = 3;
    /** Legs that find no way: searched again this often, for this long (5 s), before it gives up. */
    static final int RETRY_TICKS = 20, NO_LEG_TICKS = 100;
    /** Closer than this (blocks, 3D) is getting closer. */
    private static final double CLOSER = 1.0;
    /** Arrived is this close up or down too. */
    private static final double ARRIVED_HEIGHT = 2.0;

    /** The tile it was sent to. */
    final BlockPos asked;
    /** Where it ends: the tile asked, landed, or beside it; null until a leg finds out. */
    Route.Point destination;
    /** Whether that is beside the tile asked, which was no place to stand. */
    boolean beside;
    /** Legs walked, and blocks placed and dug on the way. */
    int legs, placed, dug;
    /** The closest a leg's end got (3D), and how many legs in a row ended no closer. */
    private double best = Double.MAX_VALUE;
    private int stalled;
    /** Whether the next leg is searched after getting stuck: a detour may be tried then. */
    boolean afterStuck;
    /** Since when its legs find no way (-1: the last one did), and when to search again. */
    long noLegSince = -1, retryAt;
    /** How the last leg's walk ended short, for the report: the walk's own words. */
    String why;

    Trip(BlockPos asked) {
        this.asked = asked;
    }

    /** The trip's first leg is still to find: where it ends is not known yet. */
    boolean first() {
        return destination == null;
    }

    /** Where it aims: where it ends, once known; the tile asked till then. */
    Route.Point aim() {
        return destination != null ? destination : new Route.Point(asked.getX(), asked.getY(), asked.getZ());
    }

    /** Whether a body at {@code at} is there. */
    boolean arrived(Vec3 at) {
        if (destination == null) return false;
        double dx = destination.x() + 0.5 - at.x, dz = destination.z() + 0.5 - at.z;
        return dx * dx + dz * dz <= Leg.ARRIVED * Leg.ARRIVED && Math.abs(destination.y() - at.y) <= ARRIVED_HEIGHT;
    }

    /** How far a body at {@code at} is from where it goes, in three dimensions. */
    double left(Vec3 at) {
        Route.Point to = aim();
        double dx = to.x() + 0.5 - at.x, dy = to.y() - at.y, dz = to.z() + 0.5 - at.z;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /**
     * A leg's walk is over, the body at {@code at}, short of the end: whether that is the
     * {@value #STALLED_MAX}th in a row that got no closer.
     */
    boolean stalled(Vec3 at) {
        double d = left(at);
        if (d < best - CLOSER) {
            best = d;
            stalled = 0;
            return false;
        }
        return ++stalled >= STALLED_MAX;
    }

    /** A leg found a way: the legs find ways again. */
    void found() {
        noLegSince = -1;
        legs++;
    }

    /**
     * A leg found no way, at tick {@code now}: whether to search again in a moment (true), or
     * give up (false). The first leg is not waited for.
     */
    boolean waitFor(long now) {
        if (legs == 0) return false;
        if (noLegSince < 0) noLegSince = now;
        if (now - noLegSince >= NO_LEG_TICKS) return false;
        retryAt = now + RETRY_TICKS;
        return true;
    }

    /** The trip's end, arrived: where, how far from the tile, and the numbers of the way. */
    String arrivedWords(Vec3 at) {
        Route.Point to = destination;
        double dx = to.x() + 0.5 - at.x, dz = to.z() + 0.5 - at.z;
        String where = to.x() + ", " + to.y() + ", " + to.z();
        String asked = this.asked.toShortString();
        String how = beside ? "arrived beside " + asked + ", at " + where
                : where.equals(asked) ? "arrived at " + asked
                : to.y() > this.asked.getY() ? "arrived at " + where + ", over " + asked
                : "arrived at " + where + ", the ground under " + asked;
        return String.format(Locale.ROOT, "%s (%.1f from it)", how, Math.sqrt(dx * dx + dz * dz)) + numbers();
    }

    /** The trip's end, short of it: stuck, or no way on. {@code how} says which, in words. */
    String shortWords(Vec3 at, String how) {
        Route.Point to = aim();
        return String.format(Locale.ROOT, "%s at %d %d %d, %.0f blocks from %d %d %d%s", how,
                (int) Math.floor(at.x), (int) Math.floor(at.y), (int) Math.floor(at.z), left(at), to.x(), to.y(), to.z(),
                why == null ? "" : ": " + why) + numbers();
    }

    /** "; 3 legs, 12 blocks placed, 4 dug", for what there is to say. */
    private String numbers() {
        List<String> parts = new ArrayList<>();
        if (legs > 1) parts.add(legs + " legs");
        if (placed > 0) parts.add(placed + " block" + (placed == 1 ? "" : "s") + " placed");
        if (dug > 0) parts.add(dug + " dug");
        return parts.isEmpty() ? "" : "; " + String.join(", ", parts);
    }
}
