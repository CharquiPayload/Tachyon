package tachyon;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Creeper;
import org.slf4j.Logger;
import tachyon.path.Route;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Future;

/**
 * Creepers, one of the two things that killed Masurium's bots most (phantoms are the
 * other), and never with the sword: in melee they blow up. Three distances, Masurium's:
 * <ul>
 * <li>From {@link #BOW_MIN 10} to {@link #VIEW 25} blocks, in sight, with a bow and arrows:
 *     it shoots it, standing ({@code shoot_creepers}, on by default). An arrow does not
 *     light a creeper (only a player close by does), so the long shot costs nothing but
 *     arrows. Not from water, not with a player on or near the line (looked at as the arrow
 *     is let go), not at one swelling within 7, not at one with a name (someone cares for
 *     it: it only runs from that one), not while something else is hitting it (with a zombie
 *     biting and a creeper in range, a Masurium bot that shot the creeper was bitten to
 *     death), and not at one its own kill order is after: the kill shoots it, and counts it.</li>
 * <li>Within 10, or swelling within {@link #SAFE 16}: it drops what it is doing and runs,
 *     sprinting, to anywhere 40 blocks from it ({@link Retreating#away}; 16 in a closed
 *     place), until the creeper is 16 blocks off. A creeper walks nearly as fast as a player: walking away, it never
 *     gets there. Its owner is told only if it is cornered.</li>
 * <li>Farther: none of its business.</li>
 * </ul>
 *
 * <p>Which creepers count, as a player in its place would judge: those it sees, and any
 * within {@link #CLOSE 4} (round a corner it blows all the same), however close: one 6
 * blocks under its feet in the cave below neither sees it nor hurts it through the rock.
 * Beyond {@link #EXPLOSION 7}, not one it would neither run from nor shoot (farther than
 * 16 with no bow to shoot it); not one that has no way on foot to it (looked for on a
 * routes thread, from the creeper to the bot, and taken for a way while the answer is due:
 * when in doubt, it is danger), for 30 s; nor one that made it run three times in 90 s
 * without coming for it (a ledge, water, a one-block gap), for 90 s: with two or three of
 * those around, a Masurium bot did nothing else all night. A creeper walking at it is no
 * such scare, however often: it is coming. Within 7 those rules do not hold: they are for
 * the far scare, never the one on top. And the creeper it runs from counts until it is 16
 * away, whatever the rules say: stopping at 7 from one that keeps coming is where it blows.
 *
 * <p>The search whether a creeper can walk to it is shared: a bot standing within
 * {@link #SHARE 4} blocks of one that asked lately takes that answer; and none is asked
 * while the routes threads are behind ({@link Bots#routesBusy}): a crowd at night must not
 * keep the bots' own walks waiting. Then the creeper counts, as while an answer is due.
 *
 * <p>Its look is {@link Threats}', once every 10 ticks; with a creeper being dealt with,
 * that one is looked at every tick. Fleeing comes before backing off hurt and answering an
 * attacker ({@link #FLEE}), and its guard stays quiet: trading hits next to a bomb is how
 * one dies. The shot comes after both ({@link #SHOT}).
 */
final class Creepers implements Ability {

    private static final Logger LOG = LogUtils.getLogger();

    /** Whether it shoots creepers in range with its bow by itself. */
    static final String SHOOT = "shoot_creepers";
    /** How urgent its hold on the body is when fleeing: over backing off hurt and answering an attacker. */
    static final int FLEE = 40;
    /** And when shooting one: under everything else, since an arrow at a creeper can wait. */
    static final int SHOT = 10;
    /** How far it watches creepers, what a bow reaches; the closest it shoots from; and from here in, it runs. */
    static final double VIEW = 25, BOW_MIN = 10, DANGER = 10;
    /** It runs until the creeper is this far; and from a swelling one this close. */
    static final double SAFE = 16;
    /** Swelling this close, no rule of its own makes it stay: the blast needs no path. */
    static final double EXPLOSION = 7;
    /** Seen or not, a creeper this close counts. */
    static final double CLOSE = 4;
    /** A creeper with no way to it is left alone this long; and one that scared it too often, this long. */
    static final int NO_WAY_TICKS = 20 * 30, SCARED_TICKS = 20 * 90;
    /** Scares by one creeper within this long that make it leave that one alone. */
    static final int SCARES_MAX = 3, SCARES_WINDOW = 20 * 90;
    /** Whether a creeper can walk to it is asked again after this long. */
    static final int REACH_KEPT = 40;
    /** A bot this close to where another asked whether a creeper can walk to it takes that answer. */
    static final double SHARE = 4;
    /** A creeper that moved this far between two scares is not the one that stays put behind its ledge. */
    static final double MOVED = 3;
    /** A creeper that steps toward it this much a tick is coming for it. */
    static final double COMING = 0.02;
    /** A way off is searched again this often; cornered this long, it gives up running. */
    static final int REPLAN = 10, CORNERED_MAX = 20 * 30;
    /** A shot blocked by a block or a player: no other at that creeper for this long, while it moves. */
    static final int BLOCKED_TICKS = 40;
    /** An arrow is watched this long at most. */
    static final int FLIGHT_MAX = 30;

    /** A creeper being dealt with: fled from, or shot at. */
    private static final class Watch {
        Creeper creeper;
        boolean fleeing;
        long plannedAt = -REPLAN;
        boolean awaiting, near;
        long corneredAt = -1;
        int drawn, arrows, flown, flightWait;
        boolean inFlight;
        float healthAtShot;
    }

    /** The bots dealing with a creeper. Empty nearly always: then only the look every 10 ticks costs anything. */
    private static final Map<Bots.Bot, Watch> WATCHES = new IdentityHashMap<>();

    /** Whether a creeper can walk to it: the search's answer, and until when it is taken as good. */
    private record Reach(Future<Boolean> answer, long until) {
    }

    /** The last search for each creeper, whoever asked it, and from where: for the bots around to share. */
    private record Asked(Reach reach, double x, double y, double z) {
    }

    private static final Map<UUID, Asked> ASKED = new HashMap<>();

    /** A scare: when, and where the creeper stood. */
    private record Scare(long at, double x, double z) {
    }

    /**
     * What a bot knows of the creepers around it, while it is in the game (its slot): which it
     * leaves alone and until when, how often each scared it, whether each can walk to it,
     * which it may not shoot at for a moment. By their ids and ticks: plain data, for a test.
     */
    static final class Known {
        private final Map<UUID, Long> leftAlone = new HashMap<>();
        private final Map<UUID, ArrayDeque<Scare>> scares = new HashMap<>();
        private final Map<UUID, Reach> reach = new HashMap<>();
        private final Map<UUID, Long> noShot = new HashMap<>();

        /** Whether it leaves the creeper {@code id} alone at tick {@code now}. */
        boolean leftAlone(UUID id, long now) {
            Long until = leftAlone.get(id);
            return until != null && until > now;
        }

        /** It leaves the creeper {@code id} alone until tick {@code until}. */
        void leaveAlone(UUID id, long until) {
            leftAlone.put(id, until);
        }

        /**
         * One more scare by the creeper {@code id}, at tick {@code now}, standing at
         * ({@code x}, {@code z}): whether it is the {@link #SCARES_MAX third} within
         * {@link #SCARES_WINDOW 90 s} without it coming; then that one is left alone for 90 s,
         * and its count starts again. A creeper {@code coming} at it, or one that moved
         * {@link #MOVED 3 blocks} or more since the last scare, is not staying away: its count
         * starts again (with this scare, if it is not coming now).
         */
        boolean scared(UUID id, long now, double x, double z, boolean coming) {
            ArrayDeque<Scare> q = scares.computeIfAbsent(id, k -> new ArrayDeque<>());
            while (!q.isEmpty() && now - q.peekFirst().at() > SCARES_WINDOW) q.removeFirst();
            Scare last = q.peekLast();
            if (coming || last != null && Math.hypot(x - last.x(), z - last.z()) >= MOVED) q.clear();
            if (coming) return false;
            q.addLast(new Scare(now, x, z));
            if (q.size() < SCARES_MAX) return false;
            q.clear();
            leftAlone.put(id, now + SCARED_TICKS);
            return true;
        }

        /** What is old news forgotten, so that the maps stay small. */
        void forget(long now) {
            leftAlone.values().removeIf(until -> until <= now);
            noShot.values().removeIf(until -> until <= now);
            reach.values().removeIf(r -> r.answer().isDone() && r.until() <= now);
            scares.values().removeIf(q -> q.isEmpty() || now - q.peekLast().at() > SCARES_WINDOW);
        }
    }

    @Override
    public void settings(Settings settings) {
        settings.bool(SHOOT, true, "It shoots creepers 10 to 25 blocks away with its bow, by itself. When it is off, it"
                + " only runs from those that come close.", Settings.Who.OWNER)
                .label("Shoot creepers").group("Life").basic();
    }

    /** Whether it is running from a creeper now: its guard does not trade hits then. */
    static boolean fleeing(Bots.Bot p) {
        if (WATCHES.isEmpty()) return false;
        Watch w = WATCHES.get(p);
        return w != null && w.fleeing;
    }

    @Override
    public void tick(Bots.Bot p, long now) {
        Watch w = WATCHES.isEmpty() ? null : WATCHES.get(p);
        boolean look = Threats.due(p, now);
        if (w == null && !look) return;
        BotPlayer b = p.body;
        Creeper c = w == null ? null : w.creeper;
        if (look || c == null || !c.isAlive() || c.level() != b.level()) c = choose(p, w, now);
        if (c == null) {
            if (w != null) end(p, w, w.creeper != null && !w.creeper.isAlive() ? "it is dead" : "no creeper around any more");
            return;
        }
        double d = b.distanceTo(c);
        boolean swelling = c.getSwellDir() > 0 || c.isIgnited();
        Known known = p.slot(Known.class, Known::new);
        Long noShot = known.noShot.get(c.getUUID());
        boolean shot = Settings.bool(p, SHOOT) && d >= BOW_MIN && d <= VIEW && !(swelling && d <= EXPLOSION)
                && !Prey.spared(c) && Bow.Misses.worthIt(c.getUUID()) && (noShot == null || noShot <= now)
                && !b.isInWater() && !Defending.fighting(p) && !killing(p, c) && Bow.missing(b) == null
                && b.hasLineOfSight(c);
        boolean run = d <= DANGER || swelling && d <= SAFE || w != null && w.fleeing && d < SAFE;
        if (shot) {
            w = watch(p, w, c);
            if (w.fleeing) {
                w.fleeing = false;
                Notices.technical(LOG, p, p.name() + " stops running: the creeper is " + Threats.blocks(d)
                        + " away, in range; it shoots");
            }
            String doing = "shooting a creeper " + Threats.blocks(d) + " away";
            if (!Bots.takeOver(p, this, SHOT, doing)) return;
            if (p.path != null || p.pending != null) Bots.halt(p, doing);      // it shoots standing
            p.doing = doing;
            return;
        }
        if (run) {
            boolean scare = w == null || !w.fleeing;
            // A new scare. The third from one creeper in 90 s without it coming: it cannot
            // (a ledge, water), and running again is doing nothing else all night.
            if (scare && d > EXPLOSION && scaredTooOften(p, known, c, now)) {
                if (w != null) end(p, w, null);
                return;
            }
            w = watch(p, w, c);              // the nearest, if another came closer
            if (scare) {
                w.fleeing = true;
                w.corneredAt = -1;
                w.near = false;
                Notices.technical(LOG, p, p.name() + " runs from a creeper " + Threats.blocks(d) + " away"
                        + (swelling ? ", swelling" : ""));
            }
            flee(p, w, d, now);
            return;
        }
        if (w != null) end(p, w, w.fleeing ? "the creeper is " + Threats.blocks(d) + " away" : null);
    }

    /** The creeper it deals with, new or the same, as the watch of its bot. */
    private static Watch watch(Bots.Bot p, Watch w, Creeper c) {
        if (w == null) {
            w = new Watch();
            WATCHES.put(p, w);
        }
        if (w.creeper != c) {
            w.creeper = c;
            w.inFlight = false;
            w.drawn = 0;
        }
        return w;
    }

    /** Running, a way off searched every half second when it has none; cornered, its owner told. */
    private void flee(Bots.Bot p, Watch w, double d, long now) {
        BotPlayer b = p.body;
        String doing = "running from a creeper " + Threats.blocks(d) + " away";
        boolean had = Bots.holding(p) == this;          // for a shot, maybe: now as urgently as running is
        if (!Bots.takeOver(p, this, FLEE, doing)) return;
        if (!had) {
            w.awaiting = false;
            w.plannedAt = now - REPLAN;
        }
        if (w.awaiting && p.pending == null) {
            w.awaiting = false;
            if (p.path != null) {
                w.corneredAt = -1;
                w.near = false;
            } else if (!w.near) {
                w.near = true;                   // nowhere 40 off (a closed place): 16 off will do
                w.plannedAt = now - REPLAN;
            } else if (w.corneredAt < 0) {
                w.corneredAt = now;
                Retreating.cornered(p, "a creeper " + Threats.blocks(d) + " away");
            } else if (now - w.corneredAt >= CORNERED_MAX) {
                end(p, w, "cornered for " + CORNERED_MAX / 20 + " s");
                return;
            }
        }
        if (p.path == null && p.pending == null && now - w.plannedAt >= REPLAN) {
            w.plannedAt = now;
            w.awaiting = true;
            Retreating.away(p, w.creeper, w.near ? SAFE : Retreating.FLEE_FAR, doing);
        } else {
            p.doing = doing;
        }
    }

    /**
     * The hands and keys: sprinting away, or the bow at the creeper, standing (a draw, the
     * arrow let go when full and the shot clear, then watched until it lands or its time is
     * up, as the kill's {@link Shoot} does).
     */
    @Override
    public void act(Bots.Bot p, long now) {
        if (WATCHES.isEmpty()) return;
        Watch w = WATCHES.get(p);
        if (w == null) return;
        if (Bots.holding(p) != this) {
            // Taken by something more urgent (or an order): whatever the bow did is let down.
            if (w.drawn > 0 || w.inFlight) letDown(p, w);
            return;
        }
        if (w.fleeing) {
            if (w.drawn > 0) letDown(p, w);
            Retreating.sprint(p);
            return;
        }
        BotPlayer b = p.body;
        Creeper c = w.creeper;
        Bots.release(b);
        if (!Bots.holdHands(p, this, 5)) return;
        if (w.inFlight) {
            Bow.aim(b, c);
            if (c.getHealth() < w.healthAtShot - 0.01f) {
                w.inFlight = false;
                Bow.Misses.hit(c.getUUID());
            } else if (++w.flown >= w.flightWait) {
                w.inFlight = false;
                Bow.Misses.missed(c.getUUID());
            }
            return;
        }
        if (++w.drawn > Bow.DRAW_MAX) {
            blocked(p, w, now);
            return;
        }
        switch (Bow.step(p, c)) {
            case SHOT -> {
                w.arrows++;
                w.drawn = 0;
                w.inFlight = true;
                w.flown = 0;
                w.flightWait = Math.min(FLIGHT_MAX, (int) Math.ceil(b.distanceTo(c) / 2.5) + 6);
                w.healthAtShot = c.getHealth();
            }
            case BLOCKED, NONE -> blocked(p, w, now);
            default -> {
            }
        }
    }

    /** No shot from here: a block in the arc, a player in the way, no arrow. That creeper is not shot for 2 s. */
    private void blocked(Bots.Bot p, Watch w, long now) {
        p.slot(Known.class, Known::new).noShot.put(w.creeper.getUUID(), now + BLOCKED_TICKS);
        letDown(p, w);
    }

    private void letDown(Bots.Bot p, Watch w) {
        Bow.stop(p);
        Bots.freeHands(p, this);
        w.drawn = 0;
        w.inFlight = false;
    }

    /** Done with it: the body given back, the bow let down, and a line for the log when there is something to say. */
    private void end(Bots.Bot p, Watch w, String how) {
        WATCHES.remove(p);
        letDown(p, w);
        if (w.arrows > 0) {
            Notices.technical(LOG, p, p.name() + " shot " + w.arrows + " arrow" + (w.arrows == 1 ? "" : "s")
                    + " at a creeper: " + (w.creeper.isAlive() ? "it is still alive, "
                    + (how == null ? "out of its range now" : how) : "it is dead"));
        } else if (how != null && w.fleeing) {
            Notices.technical(LOG, p, p.name() + " stops running: " + how);
        }
        Bots.giveBack(p, this);
    }

    @Override
    public void died(Bots.Bot p, DamageSource cause) {
        WATCHES.remove(p);
    }

    @Override
    public void left(Bots.Bot p) {
        WATCHES.remove(p);
    }

    /** Whether its kill order (at it, or set aside while a reflex holds it) is after this creeper: then the kill shoots it. */
    private static boolean killing(Bots.Bot p, Creeper c) {
        return Bots.job(p) instanceof Shoot s && s.prey().matches(p, c);
    }

    // --- which creeper counts ----------------------------------------------------------------------

    /**
     * The nearest creeper that counts: within {@link #VIEW}, one it makes out (sees, or within
     * 4); and beyond {@link #EXPLOSION}, one that is its business (within 16, or it has a bow
     * to shoot it with), neither left alone (no way to it, too many scares) nor without a way
     * on foot to it. The one it runs from ({@code w}'s) counts whatever the rules say.
     */
    private static Creeper choose(Bots.Bot p, Watch w, long now) {
        BotPlayer b = p.body;
        Creeper fled = w != null && w.fleeing ? w.creeper : null;
        Creeper best = null;
        double bestD = VIEW * VIEW;
        Known known = null;
        int bow = -1;               // whether it shoots creepers, looked at once, if needed
        for (Mob m : Threats.around(p, now)) {
            if (!(m instanceof Creeper c)) continue;
            double d = c.distanceToSqr(b);
            if (d > bestD) continue;
            if (c != fled) {
                if (d > EXPLOSION * EXPLOSION) {
                    if (known == null) {
                        known = p.slot(Known.class, Known::new);
                        known.forget(now);
                    }
                    if (known.leftAlone(c.getUUID(), now)) continue;
                    if (d > SAFE * SAFE) {
                        if (bow < 0) bow = Settings.bool(p, SHOOT) && Bow.missing(b) == null ? 1 : 0;
                        if (bow == 0) continue;             // it would neither run from it nor shoot it
                    }
                    if (!Threats.noticed(b, c, CLOSE) || !reaches(p, known, c, now)) continue;
                } else if (!Threats.noticed(b, c, CLOSE)) {
                    continue;
                }
            }
            best = c;
            bestD = d;
        }
        return best;
    }

    /**
     * Whether the creeper has a way on foot to it: the answer of a search from the creeper to
     * the bot (a fall of 8 at most, 3000 tiles: Masurium's), asked again after 2 s, or that of
     * a bot within 4 of it that asked lately. Due, or not asked (the routes threads are
     * behind), it counts as a way. None, the creeper is left alone for 30 s.
     */
    private static boolean reaches(Bots.Bot p, Known known, Creeper c, long now) {
        BotPlayer b = p.body;
        Reach r = known.reach.get(c.getUUID());
        boolean old = r != null && r.answer().isDone() && now >= r.until() && answer(r);
        if (r == null || old) {
            // None of its own lately: one a bot next to it asked will do.
            Asked a = ASKED.get(c.getUUID());
            if (a != null && now < a.reach().until() && b.distanceToSqr(a.x(), a.y(), a.z()) <= SHARE * SHARE) {
                r = a.reach();
                known.reach.put(c.getUUID(), r);
            }
        }
        if (r != null) {
            if (!r.answer().isDone()) return true;
            if (!answer(r)) {
                known.reach.remove(c.getUUID());
                known.leaveAlone(c.getUUID(), now + NO_WAY_TICKS);
                Notices.technical(LOG, p, p.name() + " leaves alone a creeper " + Threats.blocks(b.distanceTo(c))
                        + " away: it has no way on foot to it (for " + (NO_WAY_TICKS / 20) + " s)");
                return false;
            }
            if (now < r.until()) return true;
        }
        if (Bots.routesBusy()) return true;             // no answer for now: when in doubt, danger
        BlockPos from = c.blockPosition(), to = b.blockPosition();
        r = new Reach(Bots.search(b.serverLevel(), from, to, world -> way(world, from, to)), now + REACH_KEPT);
        known.reach.put(c.getUUID(), r);
        if (ASKED.size() > 256) ASKED.values().removeIf(x -> now >= x.reach().until());
        ASKED.put(c.getUUID(), new Asked(r, b.getX(), b.getY(), b.getZ()));
        return true;
    }

    private static boolean answer(Reach r) {
        try {
            return !Boolean.FALSE.equals(r.answer().get());
        } catch (Exception e) {
            return true;            // the search failed: when in doubt, danger
        }
    }

    /** On a routes thread: whether a creeper standing at {@code from} can walk to within 2 of {@code to}. */
    private static boolean way(SnapshotWorld world, BlockPos from, BlockPos to) {
        for (int dy : new int[]{0, -1, 1}) {
            if (!world.canStand(from.getX(), from.getY() + dy, from.getZ())) continue;
            Route.Point start = new Route.Point(from.getX(), from.getY() + dy, from.getZ());
            return Route.search(world, start, Route.Meta.near(new Route.Point(to.getX(), to.getY(), to.getZ()), 2.0),
                    new Route.Options(8, 3000, false, false)).hasRoute();
        }
        return true;                // no tile to start from (in water, in the air): when in doubt, danger
    }

    /**
     * One more scare by {@code c}: whether it is the third within 90 s without it coming, and
     * then it is left alone for 90 s. Coming is what a player sees: its last step was toward
     * the bot.
     */
    private static boolean scaredTooOften(Bots.Bot p, Known known, Creeper c, long now) {
        BotPlayer b = p.body;
        double dx = b.getX() - c.getX(), dz = b.getZ() - c.getZ(), len = Math.hypot(dx, dz);
        boolean coming = len > 0.01 && ((c.getX() - c.xo) * dx + (c.getZ() - c.zo) * dz) / len > COMING;
        if (!known.scared(c.getUUID(), now, c.getX(), c.getZ(), coming)) return false;
        Notices.technical(LOG, p, p.name() + " leaves alone a creeper " + Threats.blocks(b.distanceTo(c)) + " away: "
                + SCARES_MAX + " scares in " + (SCARES_WINDOW / 20) + " s without it coming (for "
                + (SCARED_TICKS / 20) + " s)");
        return true;
    }
}
