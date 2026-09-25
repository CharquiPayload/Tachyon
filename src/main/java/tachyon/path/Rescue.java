package tachyon.path;

import java.util.ArrayList;
import java.util.List;

/**
 * Getting out of a hole, and only that: Masurium's rescue.
 *
 * <p>A bot once fell into a hole with 448 dirt in its inventory and stayed there until a
 * zombie killed it. That was not a bug: it was the rule that building to move may be
 * turned off, taken to its last consequence. So the exception is as narrow as it can be:
 * <ul>
 *   <li>only when there is no route at all to where it was sent, and building is off (able
 *       to build, the search would have found the tower itself);</li>
 *   <li>only if the bot is shut in on all four sides, which is what tells "I am in a hole"
 *       from "there is a wall ahead";</li>
 *   <li>only upwards, a block under its own feet at a time: never a bridge, never a block
 *       against anything of anyone's;</li>
 *   <li>at most {@value #TALL_MAX} blocks, and only if it can walk out from up there.</li>
 * </ul>
 * Otherwise nothing is built, and the bot says it is trapped.
 */
public final class Rescue {

    /** The highest tower it climbs out on. */
    public static final int TALL_MAX = 8;

    private static final int[][] SIDES = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    private Rescue() {
    }

    /**
     * Whether walking does not get it out: no standable tile beside it, at its level or a
     * step up (what it climbs without placing anything).
     */
    public static boolean inAHole(World m, Route.Point me) {
        for (int[] s : SIDES) {
            int x = me.x() + s[0], z = me.z() + s[1];
            if (m.canStand(x, me.y(), z) || m.canStand(x, me.y() + 1, z)) return false;
        }
        return true;
    }

    /**
     * How many blocks it has to climb to walk out: the first height with a standable tile
     * beside the column. -1 when there is none within {@link #TALL_MAX}, or a ceiling on
     * the way (jumping to place a block under the feet needs room over the head).
     */
    public static int exitHeight(World m, Route.Point me) {
        for (int tall = 1; tall <= TALL_MAX; tall++) {
            int y = me.y() + tall;
            if (m.solid(me.x(), y + 1, me.z())) return -1;
            for (int[] s : SIDES) {
                if (m.canStand(me.x() + s[0], y, me.z() + s[1])) return tall;
            }
        }
        return -1;
    }

    /**
     * The straight way up, for the legs to walk: each point one block over the last, which
     * the walk's tower step climbs (a block placed under the feet at the top of each jump).
     * No second copy of that lesson: the tower is the one bridges and towers use.
     */
    public static List<Route.Point> staircase(Route.Point me, int tall) {
        List<Route.Point> steps = new ArrayList<>(tall + 1);
        for (int i = 0; i <= tall; i++) steps.add(new Route.Point(me.x(), me.y() + i, me.z()));
        return steps;
    }
}
