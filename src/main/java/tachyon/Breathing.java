package tachyon;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForgeMod;
import org.slf4j.Logger;
import tachyon.path.Route;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Air, which nobody watched in Masurium until a bot drowned crossing a lake on its way
 * somewhere: two health a second, seven times, with nobody hitting it. Two halves here.
 *
 * <p><b>Afloat.</b> A player standing still in deep water holds jump, or sinks: a bot waiting
 * in water (following someone who stopped on a lake, an order over, a job waiting) keeps its
 * head above it the same way, pressing jump whenever the water is over its waist
 * ({@link #AFLOAT}) and it is not walking a route (the legs jump in water by themselves).
 * Two bots drowned in the tests while they waited. It costs a look at one of the body's
 * fields a tick.
 *
 * <p><b>Coming up for air.</b> Under water with {@link #AIR_LOW 100} of its 300 air left (five
 * seconds), it drops what it is doing and swims up, and goes back to it once its air is
 * {@link #AIR_ENOUGH 250} again: letting go as soon as the head is out, it went back down the
 * next second and never got out of the water. Straight up, jump held, as a player swims;
 * with something over its head (ice, a ledge, a cave's roof), or not rising, along a route
 * to the nearest place under open water, and straight up from there. This comes before
 * anything but digging out ({@link #URGENCY}). Drowning with no way up (or kept down by
 * something more urgent), its owner is told: there is nothing left it can do.
 */
final class Breathing implements Ability {

    private static final Logger LOG = LogUtils.getLogger();

    /** How urgent its hold on the body is: over everything but digging out when buried. */
    static final int URGENCY = 50;
    /** Air (of 300) at which it drops everything to come up, and what it waits for before it goes back. */
    static final int AIR_LOW = 100, AIR_ENOUGH = 250;
    /** Water higher over its feet than this (above the waist), standing still, it holds jump. */
    static final double AFLOAT = 1.0;
    /** How high above its head it looks for something in the way of swimming straight up. */
    static final int LOOK_UP = 20;
    /** Swimming straight up and not rising for this long, it looks for a way instead. */
    static final int NOT_RISING = 30;
    /** A way up is searched again this often. */
    static final int REPLAN = 20;
    /** Swimming a way up: a column counts as reached this close to its middle, flat; and no closer for this long, it looks again. */
    static final double REACHED = 0.4;
    static final int STUCK = 40;
    /** Going up a column it came to by a way, it keeps this close to its middle. */
    static final double CENTRED = 0.1;

    /** A bot coming up for air. */
    private static final class Surfacing {
        final long since;
        final int air;
        double lastY;
        long roseAt;
        /** Going by a route: there is something over its head. */
        boolean routing, awaiting;
        long plannedAt = -REPLAN;
        /**
         * The way to open water it swims, column by column (the next one of them, and how it
         * goes): swum by itself, not walked by the legs (see {@link #swim}).
         */
        List<Route.Point> way;
        int next;
        double closest;
        long closerAt;
        /** The middle of the open column it came to by a way: there, it goes straight up. */
        Vec3 up;
        /** Said already, this time: no way up, drowning. */
        boolean noWaySaid, drowningSaid;

        Surfacing(long since, int air) {
            this.since = since;
            this.air = air;
        }
    }

    /** The bots coming up for air. Empty nearly always. */
    private static final Map<Bots.Bot, Surfacing> SURFACING = new IdentityHashMap<>();

    @Override
    public void tick(Bots.Bot p, long now) {
        Surfacing s = SURFACING.isEmpty() ? null : SURFACING.get(p);
        BotPlayer b = p.body;
        if (s == null) {
            if (!b.isUnderWater() || b.getAirSupply() > AIR_LOW) return;       // the body's own fields
            s = new Surfacing(now, b.getAirSupply());
            SURFACING.put(p, s);
            Notices.technical(LOG, p, p.name() + " comes up for air: " + s.air + " of 300 left under water at "
                    + Brain.pos(b.blockPosition()));
        }
        int air = b.getAirSupply();
        if (air >= AIR_ENOUGH) {
            SURFACING.remove(p);
            Notices.technical(LOG, p, p.name() + " has air again (" + air + " of 300) after "
                    + Math.round((now - s.since) / 20.0) + " s");
            Bots.giveBack(p, this);
            return;
        }
        String doing = "coming up for air (" + Math.max(0, air) + " of 300 left)";
        boolean had = Bots.holding(p) == this;
        if (!Bots.takeOver(p, this, URGENCY, doing)) return;
        if (!had) {                               // taken (again): from where it is now
            s.routing = false;
            s.awaiting = false;
            s.way = null;
            s.up = null;
            s.lastY = b.getY();
            s.roseAt = now;
        }
        p.doing = doing;
        if (!b.isUnderWater()) {                  // its head is out: it breathes, afloat, until it has enough
            s.way = null;
            s.up = null;
            return;
        }
        if (b.getY() > s.lastY + 0.2) {
            s.lastY = b.getY();
            s.roseAt = now;
        }
        if (!s.routing && (now - s.roseAt > NOT_RISING || !had && roofed(b))) {
            s.routing = true;
            s.plannedAt = now - REPLAN;
        }
        if (!s.routing) return;
        // Under open water now (on its way, or at its end): straight up, from the middle of
        // the column (off it, the body is still half under the roof, and cannot rise).
        if (!roofed(b)) {
            s.routing = false;
            s.way = null;
            s.roseAt = now;
            BlockPos column = BlockPos.containing(b.getEyePosition());
            s.up = new Vec3(column.getX() + 0.5, b.getY(), column.getZ() + 0.5);
            if (p.path != null || p.pending != null) Bots.halt(p, doing);
            return;
        }
        if (p.pending != null) return;
        if (p.path != null) {
            // A way found: taken from the legs, and swum.
            s.awaiting = false;
            s.way = List.copyOf(p.path);
            s.next = Math.min(1, s.way.size() - 1);
            s.closest = Double.MAX_VALUE;
            s.closerAt = now;
            Bots.halt(p, doing);
            p.doing = doing;
        }
        if (s.way != null) {
            if (swim(b, s, now)) return;
            s.way = null;                        // at its end and still roofed, or not getting closer: another way
        }
        if (s.awaiting) {
            s.awaiting = false;
            if (!s.noWaySaid) {
                s.noWaySaid = true;
                Notices.technical(LOG, p, p.name() + " finds no way up to air from " + Brain.pos(b.blockPosition()));
            }
        }
        if (now - s.plannedAt >= REPLAN) {
            s.plannedAt = now;
            s.awaiting = true;
            Bots.plan(p, b.blockPosition(), Breathing::open, new Route.Options(3, Route.Options.byDefault().maxNodes(), false, false),
                    doing);
        }
    }

    /**
     * Jump held: coming up for air (straight up, when there is no route to walk), or afloat,
     * standing still with the water over its waist. Walking, the legs press their own keys.
     */
    @Override
    public void act(Bots.Bot p, long now) {
        BotPlayer b = p.body;
        if (!b.isInWater() || p.path != null) return;
        Surfacing s = SURFACING.isEmpty() || Bots.holding(p) != this ? null : SURFACING.get(p);
        if (s != null && s.way != null) {
            Route.Point to = s.way.get(s.next);
            Job.walkStraight(p, new Vec3(to.x() + 0.5, b.getY(), to.z() + 0.5));      // in water it swims up as it goes
            return;
        }
        if (s != null && s.up != null && Bots.horizontal(b.position(), s.up) > CENTRED) {
            Job.walkStraight(p, new Vec3(s.up.x, b.getY(), s.up.z));
            return;
        }
        if (s != null || b.getFluidTypeHeight(NeoForgeMod.WATER_TYPE.value()) > AFLOAT) b.setJumping(true);
    }

    /**
     * Along its way to open water, a column at a time: the next once over the one it is at,
     * whatever its height. Not the legs' walk: pressed up against the roof, in the swimming
     * pose (a body 0.6 tall), it floats a block or more over the tiles a player's route
     * goes by, and the walk, which counts a point reached only at its height, never would.
     *
     * @return whether it is still on its way (false: at its end, or not getting closer for 2 s)
     */
    private static boolean swim(BotPlayer b, Surfacing s, long now) {
        while (s.next < s.way.size()) {
            Route.Point to = s.way.get(s.next);
            if (Bots.horizontal(b.position(), new Vec3(to.x() + 0.5, b.getY(), to.z() + 0.5)) > REACHED) break;
            s.next++;
            s.closest = Double.MAX_VALUE;
            s.closerAt = now;
        }
        if (s.next >= s.way.size()) return false;
        Route.Point to = s.way.get(s.next);
        double d = Bots.horizontal(b.position(), new Vec3(to.x() + 0.5, b.getY(), to.z() + 0.5));
        if (d < s.closest - 0.1) {
            s.closest = d;
            s.closerAt = now;
        }
        return now - s.closerAt < STUCK;
    }

    /**
     * Drowning while it comes up for air: said once in the log; and to its owner when there
     * is nothing left it can do: it found no way up, or something more urgent keeps it down.
     * Not the blow that kills it (its death is told), nor a drowning it never ran out of air
     * for (a command's, a mod's).
     */
    @Override
    public void hurt(Bots.Bot p, DamageSource source, float amount) {
        if (!source.is(DamageTypes.DROWN) || SURFACING.isEmpty()) return;
        Surfacing s = SURFACING.get(p);
        BotPlayer b = p.body;
        if (s == null || b.isDeadOrDying()) return;
        if (!s.drowningSaid) {
            s.drowningSaid = true;
            Notices.technical(LOG, p, p.name() + " is drowning at " + Brain.pos(b.blockPosition()) + " ("
                    + Retreating.health(b) + " health) on its way up");
        }
        if (!s.noWaySaid && Bots.holding(p) == this) return;       // still on its way up
        Notices.say(p, "drowning", "is drowning at " + Brain.pos(b.blockPosition()) + " (" + Retreating.health(b)
                + " health): it finds no way up to air");
    }

    @Override
    public void died(Bots.Bot p, DamageSource cause) {
        SURFACING.remove(p);
    }

    @Override
    public void left(Bots.Bot p) {
        SURFACING.remove(p);
    }

    /** Something over its head, under {@link #LOOK_UP} blocks of water: swimming straight up gets it nowhere. */
    private static boolean roofed(BotPlayer b) {
        BlockPos.MutableBlockPos at = BlockPos.containing(b.getEyePosition()).mutable();
        for (int i = 0; i < LOOK_UP; i++, at.move(0, 1, 0)) {
            BlockState s = b.level().getBlockState(at);
            if (s.getFluidState().is(FluidTags.WATER)) continue;
            return !s.getCollisionShape(b.level(), at).isEmpty();
        }
        return true;
    }

    /**
     * Where a route up ends, in the world a search reads: a spot it can be at (standing, or
     * afloat) under open water, where going straight up, through nothing but water, gets its
     * head into the air (the path finder has no step straight up: it walks there, and swims up
     * from there). The nearest by cost: there is no estimate of what is left, since where that
     * is, it does not know.
     */
    private static Route.Meta open(SnapshotWorld world) {
        return new Route.Meta() {
            @Override
            public boolean isGoal(int x, int y, int z) {
                for (int up = y + 1; up <= y + LOOK_UP; up++) {
                    if (world.water(x, up, z)) continue;
                    return !world.solid(x, up, z);
                }
                return false;
            }

            @Override
            public double heuristic(int x, int y, int z) {
                return 0;
            }

            @Override
            public String toString() {
                return "open water over the head";
            }
        };
    }
}
