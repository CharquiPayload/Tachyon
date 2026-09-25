package tachyon.path;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The short detour, Masurium's: when a bot got stuck and there is no route from where it
 * stands, it walks to a tile a few blocks away and searches again from there.
 *
 * <p>Where it stands is often the trouble itself: the edge of a tile the route cannot
 * start from, a tile next to the one it failed to get into (vetoed now, see the stuck
 * spots). From a few blocks off the world looks different to the path finder. The
 * farthest tiles are tried first (one block away gets it out of nothing), and only a few,
 * each with a small budget: this is a way out of a corner, not a second trip.
 */
public final class Detour {

    /** How far the detour's tile is at most (on each axis), and at least (squared: 2 blocks). */
    static final int RADIUS = 5, NEAR_SQ = 4;
    /** How many tiles are tried, and each search's budget. */
    static final int TRIES = 6, NODES = 1500;

    private Detour() {
    }

    /**
     * A route to a standable tile 2 to {@value #RADIUS} blocks from {@code here} (a block
     * up or down at most), the farthest first; null when none of the first {@value #TRIES}
     * can be reached. {@code op} gives the fall and whether it may build; its node budget
     * is this one's.
     */
    public static Route.Result find(World m, Route.Point here, Route.Options op) {
        List<Route.Point> candidates = new ArrayList<>();
        for (int dx = -RADIUS; dx <= RADIUS; dx++) {
            for (int dz = -RADIUS; dz <= RADIUS; dz++) {
                if (dx * dx + dz * dz < NEAR_SQ) continue;
                for (int dy = -1; dy <= 1; dy++) {
                    int x = here.x() + dx, y = here.y() + dy, z = here.z() + dz;
                    if (m.canStand(x, y, z)) candidates.add(new Route.Point(x, y, z));
                }
            }
        }
        candidates.sort(Comparator.comparingInt((Route.Point c) -> {
            int dx = c.x() - here.x(), dz = c.z() - here.z();
            return -(dx * dx + dz * dz);
        }));
        Route.Options small = new Route.Options(op.maxFall(), NODES, op.canBuild(), false, op.deadlineMs(), null,
                op.canBreak());
        int tried = 0;
        for (Route.Point c : candidates) {
            if (tried++ >= TRIES) break;
            Route.Result r = Route.search(m, here, c, small);
            if (r.hasRoute() && r.steps().size() >= 2) return r;
        }
        return null;
    }
}
