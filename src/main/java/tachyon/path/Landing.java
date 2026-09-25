package tachyon.path;

import java.util.ArrayList;
import java.util.List;

/**
 * Where a bot can really go when it is asked for one tile: Masurium's courtesies for
 * {@code /go}.
 *
 * <p>"Go to the bed" cannot mean "get inside the bed". A bed, a chest, a wall or a tree
 * are not places to stand, and asking the path finder for them got "the destination is not
 * a spot where one can stand": true, and of no use, since what is wanted is to stand next
 * to it. So a tile that is no good is landed first (the ground under it), and then its
 * four sides are tried. Not the top of it: "go to the chest" is not "climb on the chest".
 * Only when neither the ground under it nor a side will do, the tile over it: a player
 * who read where they stand on a dirt path (15/16 of a block) gave the path's own cell,
 * with the ground around it as solid as the path.
 */
public final class Landing {

    /** How far under the tile asked the ground is looked for. */
    public static final int DOWN = 8;

    private static final int[][] SIDES = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    private Landing() {
    }

    /**
     * The tile asked, if one can stand there; else the first standable tile under it, down
     * to {@link #DOWN} blocks. Null when there is none.
     */
    public static Route.Point land(World m, Route.Point p) {
        if (m.canStand(p.x(), p.y(), p.z())) return p;
        for (int fall = 1; fall <= DOWN; fall++) {
            if (m.canStand(p.x(), p.y() - fall, p.z())) return new Route.Point(p.x(), p.y() - fall, p.z());
        }
        return null;
    }

    /** The tile over {@code p}, if {@code p} is solid and one can stand there; else null. */
    public static Route.Point over(World m, Route.Point p) {
        if (!m.solid(p.x(), p.y(), p.z()) || !m.canStand(p.x(), p.y() + 1, p.z())) return null;
        return new Route.Point(p.x(), p.y() + 1, p.z());
    }

    /**
     * The four sides of {@code p}, each landed, without the ones that land nowhere, without
     * repeats and without {@code first} (the tile already tried). Plan B: "next to what I was
     * asked", never before the tile itself, so that "climb up there" still climbs.
     */
    public static List<Route.Point> sides(World m, Route.Point p, Route.Point first) {
        List<Route.Point> out = new ArrayList<>(4);
        for (int[] s : SIDES) {
            Route.Point side = land(m, new Route.Point(p.x() + s[0], p.y(), p.z() + s[1]));
            if (side != null && !side.equals(first) && !out.contains(side)) out.add(side);
        }
        return out;
    }

    /**
     * Any of these tiles, as one goal: the path finder takes the one it reaches cheapest.
     * Its estimate is the smallest of the tiles' own, so it never estimates over.
     */
    public static Route.Meta anyOf(List<Route.Point> tiles) {
        List<Route.Meta> each = new ArrayList<>(tiles.size());
        for (Route.Point t : tiles) each.add(Route.Meta.exact(t));
        return new Route.Meta() {
            public boolean isGoal(int x, int y, int z) {
                for (Route.Point t : tiles) {
                    if (t.x() == x && t.y() == y && t.z() == z) return true;
                }
                return false;
            }

            public double heuristic(int x, int y, int z) {
                double best = Double.MAX_VALUE;
                for (Route.Meta m : each) best = Math.min(best, m.heuristic(x, y, z));
                return best;
            }

            public String toString() {
                return "any of " + tiles;
            }
        };
    }
}
