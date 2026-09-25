package tachyon;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Going back for what it dropped when it died ({@link Recovering}): to each grave in turn,
 * by the routes the legs walk, then, there, to each of its own things still lying within
 * {@link Recovering#RADIUS} of it, the nearest first, until for 3 s it sees none it can
 * reach; a player picks up what it touches. Masurium's ItemRecovery, with its numbers.
 *
 * <p>How each grave went is said to its owner ({@link Notices}), with the numbers: the
 * items got back, those gone (vanished, or taken by someone), those it saw and could not
 * reach. A grave is given up when it finds no way there (3 searches in a row find none,
 * or 5 bring it no closer), when 6 minutes have passed since the death, or when it is
 * taken to another dimension. A thing it cannot get to (3 searches find no way, or 20 s
 * pass) is left, and so is one its inventory has no room for.
 */
final class Recover extends Job {

    /** At the grave: within this, flat, of where it died, and this, up or down. */
    private static final double ARRIVED = 6.0, ARRIVED_HEIGHT = 4.0;
    /** A route to the grave ends within this of the ground under it. */
    private static final double NEAR_GRAVE = 2.0;
    /** At the grave, it looks for what is left this often, in ticks... */
    private static final int LOOK_EVERY = 10;
    /** ...and seeing none it can reach for this long (3 s) is: nothing left. */
    private static final int NOTHING_TICKS = 60;
    /** Searches in a row that find no way before a place or a thing is given up. */
    private static final int FAILS_MAX = 3;
    /** Searches in a row that start no closer (by a block) than the closest yet: stuck. */
    private static final int NO_CLOSER_MAX = 5;
    /** This close to a thing, with no route to walk, it walks straight at it. */
    private static final double CLOSE_IN = 4.0;
    /** A thing not reached in this long (20 s) is out of reach. */
    private static final int THING_TICKS = 20 * 20;
    /** Walking straight at a thing, this many ticks without getting closer: a route instead, for this long. */
    private static final int STALLED_MAX = 30, ROUTE_ONLY_TICKS = 100;

    /** The bot's graves (its slot, the same while it is in the game): what it picks up is counted there. */
    private final Recovering.Graves graves;
    /** The graves still to go to, the next first. */
    private final Deque<Recovering.Grave> ahead;
    /** The one it goes to now, or null before the first. */
    private Recovering.Grave grave;
    /** At it, picking up; else on its way. */
    private boolean there;

    // On its way.
    private boolean awaiting;
    private int fails, noCloser;
    private double closest = Double.MAX_VALUE;

    // At the grave: the thing it goes to, since when, and what it gave up on.
    private ItemEntity thing;
    private long thingSince, lookedAt, sawAt;
    private final Set<UUID> outOfReach = new HashSet<>();
    private final Set<UUID> noRoom = new HashSet<>();
    private double thingClosest = Double.MAX_VALUE;
    private int stalled;
    private long routeOnlyUntil;

    Recover(Recovering.Graves graves, List<Recovering.Grave> go) {
        this.graves = graves;
        this.ahead = new ArrayDeque<>(go);
    }

    @Override
    String status() {
        if (grave == null) return "going back for its things";
        if (!there) {
            return "going back for its things at " + grave.pos() + " (try " + grave.tries + " of " + Recovering.TRIES
                    + ", " + grave.left() + " items)";
        }
        return "picking up its things at " + grave.pos() + ": " + Math.min(grave.picked, grave.dropped) + " of "
                + grave.dropped + " items back";
    }

    @Override
    boolean think(Bots.Bot p, long now) {
        if (!Settings.bool(p, Recovering.RECOVER)) {
            graves.running = null;
            Bots.halt(p, "stopped going back for its things: recover_items is false");
            return false;
        }
        if (grave == null && !next(p, now)) return over(p, "had nothing left to go back for");
        BotPlayer b = p.body;
        if (!b.level().dimension().location().toString().equals(grave.dimension)) {
            return done(p, now, "dimension", "was taken to " + Respawning.dimension(b.level().dimension().location().toString())
                    + " on its way back to " + grave.pos() + ": it does not cross dimensions yet; " + grave.left()
                    + " of the " + grave.dropped + " items it dropped there are left, " + grave.leftFor(now));
        }
        return there ? pickUp(p, now) : travel(p, now);
    }

    /** The next grave, if one is still worth the trip; its try counted. */
    private boolean next(Bots.Bot p, long now) {
        String here = p.body.level().dimension().location().toString();
        while (!ahead.isEmpty()) {
            Recovering.Grave g = ahead.pollFirst();
            if (g.done || g.left() == 0 || g.skip(here, now) != null) continue;
            grave = g;
            g.tries++;
            there = false;
            awaiting = false;
            fails = 0;
            noCloser = 0;
            closest = Double.MAX_VALUE;
            thing = null;
            outOfReach.clear();
            noRoom.clear();
            Bots.halt(p, status());
            return true;
        }
        return false;
    }

    /** To the grave, a route at a time (a long way is walked a stretch at a time). */
    private boolean travel(Bots.Bot p, long now) {
        BotPlayer b = p.body;
        Vec3 at = Vec3.atBottomCenterOf(grave.at);
        if (now - grave.died > Recovering.DEADLINE_TICKS) {
            return done(p, now, "late", "did not get back to " + grave.pos() + " in time: " + (now - grave.died) / 1200
                    + " minutes have passed since it died, and the " + grave.left() + " items it dropped there vanish after 5");
        }
        if (Bots.horizontal(b.position(), at) <= ARRIVED && Math.abs(b.getY() - at.y) <= ARRIVED_HEIGHT) {
            there = true;
            sawAt = now;
            lookedAt = now - LOOK_EVERY;
            awaiting = false;
            fails = 0;
            Bots.halt(p, status());
            return true;
        }
        if (p.pending != null) return true;                 // a search on its way
        if (awaiting) {
            awaiting = false;
            if (!p.searchFailed) fails = 0;
            else if (++fails >= FAILS_MAX) {
                return done(p, now, "noway", "found no way back to " + grave.pos() + ", where it died, from "
                        + Math.round(b.position().distanceTo(at)) + " blocks away; the " + grave.left()
                        + " items it dropped there are left, " + grave.leftFor(now));
            }
        }
        if (p.path != null) return true;                    // walking a stretch
        if (now - p.plannedAt < Bots.REPLAN_TICKS) return true;
        // Each search starts where the last stretch ended: a few in a row that start no
        // closer are a way round that goes nowhere.
        double d = b.position().distanceTo(at);
        if (d < closest - 1) {
            closest = d;
            noCloser = 0;
        } else if (++noCloser >= NO_CLOSER_MAX) {
            return done(p, now, "noway", "could not get closer than " + Math.round(closest) + " blocks to " + grave.pos()
                    + ", where it died; the " + grave.left() + " items it dropped there are left, " + grave.leftFor(now));
        }
        p.plannedAt = now;
        awaiting = true;
        Bots.plan(p, grave.at, NEAR_GRAVE, status());
        return true;
    }

    /** At the grave: to its own things still lying around, the nearest first, until it sees none it can reach. */
    private boolean pickUp(Bots.Bot p, long now) {
        BotPlayer b = p.body;
        ServerLevel level = b.serverLevel();
        if (thing != null && (!thing.isAlive() || thing.level() != level)) {
            thing = null;                                   // picked up (or gone)
            Bots.halt(p, status());
        } else if (thing != null && now - thingSince > THING_TICKS) {
            outOfReach.add(thing.getUUID());
            thing = null;
            Bots.halt(p, status());
        }
        if (thing == null && now - lookedAt >= LOOK_EVERY) {
            lookedAt = now;
            thing = nearest(p, level);
            if (thing != null) {
                thingSince = now;
                fails = 0;
                awaiting = false;
                thingClosest = Double.MAX_VALUE;
                stalled = 0;
                routeOnlyUntil = 0;
            }
        }
        if (thing == null) {
            if (now - sawAt < NOTHING_TICKS) return true;
            return done(p, now, "back", outcome(level));
        }
        sawAt = now;
        if (awaiting && p.pending == null) {
            awaiting = false;
            if (!p.searchFailed) fails = 0;
            else if (++fails >= FAILS_MAX) {
                outOfReach.add(thing.getUUID());
                thing = null;
                return true;
            }
        }
        if (p.pending != null || p.path != null) return true;
        if (now >= routeOnlyUntil && b.distanceTo(thing) <= CLOSE_IN) return true;     // act() walks it
        if (now - p.plannedAt < Bots.REPLAN_TICKS) return true;
        p.plannedAt = now;
        awaiting = true;
        Bots.plan(p, thing.blockPosition(), 1.0, status());
        return true;
    }

    /** The last steps to a thing, straight at it: a player picks up what it touches. */
    @Override
    void act(Bots.Bot p) {
        if (!there || thing == null || !thing.isAlive() || p.path != null || p.pending != null) return;
        BotPlayer b = p.body;
        long now = b.getServer().getTickCount();
        double d = b.distanceTo(thing);
        if (now < routeOnlyUntil || d > CLOSE_IN) return;
        if (d < thingClosest - 0.3) {
            thingClosest = d;
            stalled = 0;
        } else {
            stalled++;
        }
        // Something in the way (a wall higher than a jump, an edge): a route, for a while.
        if (!walkStraight(p, thing.position()) || stalled > STALLED_MAX) {
            stalled = 0;
            thingClosest = Double.MAX_VALUE;
            routeOnlyUntil = now + ROUTE_ONLY_TICKS;
            Bots.release(b);
        }
    }

    @Override
    void end(Bots.Bot p) {
        // Set aside (a reflex took the body) or over: taken up again, it searches afresh.
        thing = null;
        awaiting = false;
    }

    /**
     * Its own things lying within {@link Recovering#RADIUS} of the grave that it has not given
     * up on, the nearest; null when there is none. What is too much for its inventory is
     * counted apart. A handful of look-ups by id, every half second, at the grave only.
     */
    private ItemEntity nearest(Bots.Bot p, ServerLevel level) {
        BotPlayer b = p.body;
        Vec3 centre = Vec3.atCenterOf(grave.at);
        Inventory inv = b.getInventory();
        ItemEntity best = null;
        double bestD = Double.MAX_VALUE;
        for (UUID id : grave.items) {
            if (outOfReach.contains(id)) continue;
            Entity e = level.getEntity(id);
            if (!(e instanceof ItemEntity it) || !it.isAlive()
                    || it.distanceToSqr(centre) > Recovering.RADIUS * Recovering.RADIUS) continue;
            if (inv.getSlotWithRemainingSpace(it.getItem()) < 0 && inv.getFreeSlot() < 0) {
                noRoom.add(id);
                continue;
            }
            noRoom.remove(id);
            double d = it.distanceToSqr(b);
            if (d < bestD) {
                bestD = d;
                best = it;
            }
        }
        return best;
    }

    /** How it went, once it sees nothing more it can reach: what it got back, what was gone, what it had to leave. */
    private String outcome(ServerLevel level) {
        Vec3 centre = Vec3.atCenterOf(grave.at);
        int seen = 0;
        for (UUID id : grave.items) {
            Entity e = level.getEntity(id);
            if (e instanceof ItemEntity it && it.isAlive() && it.distanceToSqr(centre) <= Recovering.RADIUS * Recovering.RADIUS) {
                seen += it.getItem().getCount();
            }
        }
        return outcome(grave.pos(), grave.dropped, grave.picked, seen, !noRoom.isEmpty());
    }

    /**
     * How a grave went, in words for its owner, from plain numbers: {@code dropped} items
     * dropped there, {@code picked} got back, {@code seen} still lying there that it could
     * not get ({@code full}: its inventory had no room for some); the rest were gone.
     */
    static String outcome(String where, int dropped, int picked, int seen, boolean full) {
        int got = Math.min(picked, dropped);
        int left = Math.min(seen, dropped - got);
        int gone = dropped - got - left;
        String there = " it dropped when it died at " + where;
        String s;
        if (got == dropped) s = "got back all " + dropped + " items" + there;
        else if (got == 0 && left == 0) s = "went back to " + where + ", where it died: none of the " + dropped
                + " items it dropped was left (vanished, or taken by someone)";
        else s = "got back " + got + " of the " + dropped + " items" + there;
        if (gone > 0 && (got > 0 || left > 0)) s += "; " + gone + " were gone (vanished, or taken by someone)";
        if (left > 0) s += "; " + left + " it saw there and could not get to" + (full ? " or had no room for" : "");
        return s;
    }

    /**
     * A grave done with, however ({@code what}, a word for the kind of notice: "back",
     * "noway"...): its owner told {@code how}, kept with its death; then the next, or over.
     */
    private boolean done(Bots.Bot p, long now, String what, String how) {
        grave.done = true;
        Notices.say(p, Recovering.key(grave, what), how);
        Respawning.things(p, how);
        grave = null;
        there = false;
        thing = null;
        if (next(p, now)) return true;
        return over(p, how);
    }

    /** The trip over by itself, saying how: nobody gave it, so nobody else is told. */
    private boolean over(Bots.Bot p, String how) {
        graves.running = null;
        Bots.halt(p, how);
        return false;
    }

    /**
     * An order came first: the trip is over, and the grave it went to let go of. Its owner
     * hears what it leaves behind. From {@link Recovering#ordered}, its job already ended.
     */
    void stopped(Bots.Bot p, long now) {
        if (grave == null) return;
        grave.done = true;
        String how = "stopped going back for its things at " + grave.pos() + ": an order came first; " + grave.left()
                + " of the " + grave.dropped + " items it dropped there are left, " + grave.leftFor(now);
        Notices.say(p, Recovering.key(grave, "stopped"), how);
        Respawning.things(p, how);
        grave = null;
    }
}
