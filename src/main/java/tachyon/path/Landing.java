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
    /**
     * How far under a tile in the air, not worth building up to, the ground is looked for
     * ({@link #leavable}): as far as the path finder looks down for a way down.
     */
    public static final int DEEP = 24;

    private static final int[][] SIDES = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    private Landing() {
    }

    /**
     * The tile asked, if one can stand there; else the first standable tile under it, down
     * to {@link #DOWN} blocks. Null when there is none.
     */
    public static Route.Point land(World m, Route.Point p) {
        return land(m, p, DOWN);
    }

    /** The same, looking {@code down} blocks under it at most. */
    public static Route.Point land(World m, Route.Point p, int down) {
        if (m.canStand(p.x(), p.y(), p.z())) return p;
        for (int fall = 1; fall <= down; fall++) {
            if (m.canStand(p.x(), p.y() - fall, p.z())) return new Route.Point(p.x(), p.y() - fall, p.z());
        }
        return null;
    }

    /**
     * Whether a tile in the air is worth building up to: once on top of the tower, one can
     * get off it again, onto a tile beside it (at its level, a step up or a step down), or by
     * a fall of {@code maxFall} blocks at most (into water, from any height) beside it. A
     * tower to a tile with neither left a bot 12 blocks up on its own pillar, with no way
     * down: it cannot dig its tower back, and every search from there bridged on through
     * the air. Such a tile, most likely a height read wrong, is taken as the ground under it.
     */
    public static boolean leavable(World m, Route.Point p, int maxFall) {
        for (int[] s : SIDES) {
            int x = p.x() + s[0], z = p.z() + s[1];
            if (m.canStand(x, p.y(), z) || m.canStand(x, p.y() + 1, z) || m.canStand(x, p.y() - 1, z)) return true;
            if (m.solid(x, p.y(), z) || m.solid(x, p.y() + 1, z)) continue;
            for (int fall = 2; fall <= DEEP; fall++) {
                int y = p.y() - fall;
                if (m.solid(x, y + 1, z) || m.lava(x, y, z)) break;
                if (!m.canStand(x, y, z)) continue;
                if (m.water(x, y, z) || fall <= maxFall) return true;
                break;
            }
        }
        return false;
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
