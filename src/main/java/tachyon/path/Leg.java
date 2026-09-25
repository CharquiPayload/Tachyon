package tachyon.path;

import java.util.List;

/**
 * One leg of a trip: the search a bot runs from where it stands toward where it was sent,
 * and what it falls back on when that finds nothing. Masurium's {@code /go} (the first leg)
 * and its Traveler (the legs after), in one piece that only asks the {@link World}, so that
 * a test holds it and the search thread runs it over a snapshot.
 *
 * <p>The first leg does not know yet where the trip ends: the tile asked may be no place to
 * stand (a bed, a chest, a player's feet inside a path block), so it is landed, and then
 * its four sides are tried ({@link Landing}). The legs after it aim at the destination's
 * column first, at any height, and only then at the tile: a player who gave the position
 * they stood at on a hilltop is reached by walking up the hill, not by a tower built 37
 * blocks into the air toward that exact tile.
 *
 * <p>When the search finds nothing, in this order: a bot shut in a hole climbs out
 * ({@link Rescue}), one in water swims straight on ({@link Swim}), and one that got stuck
 * walks a short detour ({@link Detour}) to search again from there. Only then is there no
 * way, and the search's own words say why.
 */
public final class Leg {

    /** What a leg is: how it was found, which says what comes after it. */
    public enum Kind {
        /** A route searched to the destination (or a stretch of one that gets closer). */
        WALK,
        /** A route to a tile next to the one asked, which was no place to stand or reach. */
        BESIDE,
        /** A tower straight up out of a hole; the trip is searched again from the top. */
        RESCUE,
        /** A straight swim along the surface, with no search. */
        SWIM,
        /** A few blocks aside, after getting stuck, to search again from there. */
        DETOUR
    }

    /**
     * What the leg's search found: the route (or why there is none), what kind of leg it is,
     * and where the trip ends once it is known (the tile asked, landed, or the one beside it
     * that was reached; null when this leg does not tell).
     */
    public record Found(Route.Result route, Kind kind, Route.Point destination) {
        public boolean hasRoute() {
            return route.hasRoute();
        }
    }

    /**
     * Where the leg starts and how it may go.
     *
     * @param here       the tile it stands on
     * @param x          where its body is, flat (a swim is aimed from there)
     * @param z          the same
     * @param inWater    whether its body is in water
     * @param options    the search's: fall, budget, building, breaking, deadline
     * @param hasBlocks  whether it carries blocks it may build with (for climbing out of a hole)
     * @param afterStuck whether it got stuck walking the last leg (a detour is tried then)
     */
    public record Ask(Route.Point here, double x, double z, boolean inWater, Route.Options options,
                      boolean hasBlocks, boolean afterStuck) {
    }

    /** A destination this close, flat, is reached: a hair more than the legs' own arrival (1.4). */
    public static final double ARRIVED = 1.6;

    private Leg() {
    }

    /**
     * The trip's first leg, to the tile asked: landed and tried; its sides (or, with none, the
     * tile over it); then climbing out of a hole, swimming, a detour. A tile too far off to be
     * seen is walked toward, and judged by a leg from closer.
     */
    public static Found first(World m, Ask a, Route.Point asked) {
        long until = until(a.options());
        Route.Options op = a.options();
        Found sea = openSea(m, a, asked, null);
        if (sea != null) return sea;
        if (!m.known(asked.x(), asked.z())) {
            // Too far off to be seen yet (its chunk is not loaded): it can be neither landed
            // nor stood beside, so the leg goes toward it, as far as the world is known, and
            // the next one looks again from closer.
            Route.Result r = Route.search(m, a.here(), toward(m, a.here(), asked), left(op, until));
            if (r.hasRoute()) return new Found(r, Kind.WALK, null);
            Found out = fallBack(m, a, asked, until, null);
            return out != null ? out : new Found(r, Kind.WALK, null);
        }
        // Able to build, a tile in the air is where it goes (it builds up to it): landing it
        // would turn "climb up there" into "stay down here". Else, the ground under it.
        Route.Point landed = op.canBuild() ? asked : Landing.land(m, asked);
        Route.Result r = null;
        if (landed != null) {
            r = Route.search(m, a.here(), landed, left(op, until));
            if (r.hasRoute()) return new Found(r, Kind.WALK, landed);
        }
        List<Route.Point> sides = Landing.sides(m, asked, landed);
        if (!sides.isEmpty()) {
            Route.Result s = Route.search(m, a.here(), Landing.anyOf(sides), left(op, until));
            if (s.hasRoute()) return new Found(s, Kind.BESIDE, nearest(sides, last(s)));
            if (r == null) r = s;
        } else {
            // Nothing under it nor beside it: the tile over it (a path's own cell was asked).
            Route.Point over = Landing.over(m, asked);
            if (over != null) {
                r = Route.search(m, a.here(), over, left(op, until));
                if (r.hasRoute()) return new Found(r, Kind.WALK, over);
            }
        }
        // (A swim does not tell where the trip ends: the tile asked may lie in chunks not
        // loaded yet, read as rock. The legs after it land it again, once they can see it.)
        Found out = fallBack(m, a, landed != null ? landed : asked, until, null);
        if (out != null) return out;
        if (r == null) {
            return failed("I find no ground at " + asked.x() + " " + asked.y() + " " + asked.z()
                    + " neither right below nor next to it");
        }
        return new Found(r, Kind.WALK, null);
    }

    /**
     * A leg after the first, to where the trip ends: straight swimming on open sea; else its
     * column first and then the tile; then the same falls back as the first leg's.
     */
    public static Found next(World m, Ask a, Route.Point to) {
        long until = until(a.options());
        Route.Options op = a.options();
        double dx = to.x() + 0.5 - a.x(), dz = to.z() + 0.5 - a.z();
        double flat = Math.sqrt(dx * dx + dz * dz);
        Found sea = openSea(m, a, to, to);
        if (sea != null) return sea;
        Route.Result r;
        if (flat > ARRIVED) {
            r = Route.search(m, a.here(), Route.Meta.onlyXZ(to.x(), to.z()), left(op, until));
            if (!r.hasRoute()) r = Route.search(m, a.here(), to, left(op, until));
        } else {
            r = Route.search(m, a.here(), to, left(op, until));
        }
        if (r.hasRoute()) return new Found(r, Kind.WALK, to);
        Found out = fallBack(m, a, to, until, to);
        return out != null ? out : new Found(r, Kind.WALK, to);
    }

    /**
     * What is left when the search found nothing: climbing out of a hole (only with building
     * off: able to build, the search would have found the tower), swimming on (in water, or
     * from a shore with water ahead), a detour after getting stuck. Null: none of them.
     * {@code end}: where the trip ends, if known, for the swim to say.
     */
    private static Found fallBack(World m, Ask a, Route.Point toward, long until, Route.Point end) {
        if (!a.options().canBuild() && Rescue.inAHole(m, a.here())) {
            int tall = Rescue.exitHeight(m, a.here());
            if (tall < 0) {
                return failed("I am trapped in a hole and see no way out within " + Rescue.TALL_MAX + " blocks upwards");
            }
            if (!a.hasBlocks()) {
                return failed("I am trapped in a hole and carry no blocks to climb out on (dirt, stone, cobblestone,"
                        + " planks...)");
            }
            return new Found(new Route.Result(Rescue.staircase(a.here(), tall),
                    "trapped in a hole: climbing " + tall + " blocks to get out", 0), Kind.RESCUE, null);
        }
        List<Route.Point> swim = Swim.stretch(m, a.here(), a.x(), a.z(), toward);
        if (swim != null) return swimming(swim, end);
        if (a.afterStuck()) {
            Route.Result d = Detour.find(m, a.here(), left(a.options(), until));
            if (d != null) {
                return new Found(new Route.Result(d.steps(), "no route from here: a detour to search again from there",
                        d.looked()), Kind.DETOUR, null);
            }
        }
        return null;
    }

    /** How far off the edge of the known world must be, flat, to be a leg's end: a leg is worth a walk. */
    static final int EDGE_FAR = 16;

    /**
     * Toward a column not known yet: the column itself, or the edge of the known world (a
     * tile beside a column nothing is known of), {@value #EDGE_FAR} blocks off at least. What
     * is not known is read as rock, so a search toward it explores all it knows and says
     * "there is no route" (it did not run out of time, it ran out of world): the edge nearest
     * the column is where to go from here, to see more.
     */
    static Route.Meta toward(World m, Route.Point from, Route.Point to) {
        Route.Meta column = Route.Meta.onlyXZ(to.x(), to.z());
        return new Route.Meta() {
            public boolean isGoal(int x, int y, int z) {
                if (column.isGoal(x, y, z)) return true;
                int dx = x - from.x(), dz = z - from.z();
                if (dx * dx + dz * dz < EDGE_FAR * EDGE_FAR) return false;
                return !m.known(x + 1, z) || !m.known(x - 1, z) || !m.known(x, z + 1) || !m.known(x, z - 1);
            }

            public double heuristic(int x, int y, int z) {
                return column.heuristic(x, y, z);
            }

            public String toString() {
                return "toward " + column;
            }
        };
    }

    /**
     * When the leg's time is up: its searches share the deadline the options give (a leg is
     * one search's worth of time, however many it runs), 0 for none.
     */
    private static long until(Route.Options op) {
        return op.deadlineMs() > 0 ? System.currentTimeMillis() + op.deadlineMs() : 0;
    }

    /** The options with what is left of the leg's time: a millisecond at least (0 would be none). */
    private static Route.Options left(Route.Options op, long until) {
        if (until == 0) return op;
        return op.withDeadline(Math.max(1, until - System.currentTimeMillis()));
    }

    /**
     * On open water and far off (more than {@value Swim#LENGTH} blocks), no search at all:
     * over water every tile costs the same, and a search spreads like a stain and gets
     * nowhere, where a straight line along the surface is free. Null: not so.
     */
    private static Found openSea(World m, Ask a, Route.Point toward, Route.Point end) {
        if (!a.inWater()) return null;
        double dx = toward.x() + 0.5 - a.x(), dz = toward.z() + 0.5 - a.z();
        if (dx * dx + dz * dz <= Swim.LENGTH * Swim.LENGTH) return null;
        List<Route.Point> swim = Swim.stretch(m, a.here(), a.x(), a.z(), toward);
        return swim == null ? null : swimming(swim, end);
    }

    private static Found swimming(List<Route.Point> points, Route.Point toward) {
        return new Found(new Route.Result(points, "swimming straight on along the surface", 0), Kind.SWIM, toward);
    }

    private static Found failed(String why) {
        return new Found(new Route.Result(null, why, 0), Kind.WALK, null);
    }

    private static Route.Point last(Route.Result r) {
        return r.steps().get(r.steps().size() - 1);
    }

    /** Of the tiles, the one nearest {@code at}: which side a stretch toward any of them heads for. */
    private static Route.Point nearest(List<Route.Point> tiles, Route.Point at) {
        Route.Point best = tiles.get(0);
        double bestD = Double.MAX_VALUE;
        for (Route.Point t : tiles) {
            double dx = t.x() - at.x(), dy = t.y() - at.y(), dz = t.z() - at.z();
            double d = dx * dx + dy * dy + dz * dz;
            if (d < bestD) {
                bestD = d;
                best = t;
            }
        }
        return best;
    }
}
