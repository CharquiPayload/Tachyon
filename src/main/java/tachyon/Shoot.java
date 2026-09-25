package tachyon;

import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Creeper;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Killing with arrows, Masurium's Archer on the server: the nearest of what it was told to
 * kill, taken down with the bow from 10 to 25 blocks away, in sight; farther, or out of
 * sight, it walks (a route to within 2 of it) until it has range and sight; closer than
 * 10 it goes in, and what is within reach it finishes with its best weapon, as anyone with
 * two hands would (a weapon brought to hand hits from the next tick, as {@link Hunt}'s). It
 * does not pick up what they drop: that is hunting.
 *
 * <p>A creeper is never gone in on nor hit: it walks to within 16 of it at most, and shoots
 * from there; closer, running from it is {@link Creepers}', which lets this kill do the
 * shooting, and the kill takes it up again from farther off.
 *
 * <p>It takes only what it sees ({@link Hunt#noticed}), the nearest within 32 first and out
 * to 128 every 2 s, each killer on a tick of its own, as {@link Hunt} does.
 *
 * <p>After each arrow it stays to watch the shot (a tick or two per 2.5 blocks, 30 at most):
 * neither drawing again nor walking while it flies, and the shot is judged by the target's
 * health, which the server knows exactly. Three arrows in a row that do not lower it and the
 * target is let go of, by every shooter ({@link Bow.Misses}), until it hurts a bot. One it
 * finds no way to for 5 s is let go of too; both get another chance after the next kill.
 *
 * <p>It stops, saying how many it killed: once it has killed as many as it was told; when it
 * sees no more (with no count, after half a minute without seeing more); below 6 health; and
 * when it has no bow or no arrows left.
 */
final class Shoot extends Job {

    /** The bow is for this far at most; the sword, for what is closer than {@link #CLOSE}. */
    private static final double SHOT_MAX = 25.0, CLOSE = 10.0;
    /** Targets are looked for this far around, in sight: 128, as far as a player's game shows mobs. */
    static final double VIEW = 128.0, NEAR = 32.0;
    /** A creeper is gone toward to within this, and no nearer: it blows up. */
    private static final double CREEPER_NEAR = Creepers.SAFE;
    /** This close, with no route left to walk, it walks straight at it. */
    private static final double CLOSE_IN = 3.5;
    /** Without a target, one is looked for this often, out to {@link #VIEW} this often; a way to one searched no oftener. */
    private static final int EVERY = 10, FAR_EVERY = 40;
    /** With no count, it waits this long for more to come in sight before it is done. */
    private static final int NO_TARGET_TICKS = 600;
    /** A target it finds no way to for this long is let go of. */
    private static final int NO_WAY_TICKS = 100;
    /** An arrow is watched this long at most. */
    private static final int FLIGHT_MAX = 30;
    /** Below this health it stops: staying alive comes first. */
    static final float HEALTH_MIN = 6.0f;
    /** Walking straight, this many ticks without getting closer is a wall: routes only, for a while. */
    private static final int STALLED_MAX = 30, ROUTE_ONLY_TICKS = 100;
    /** After a blocked shot, it walks this long before it draws again. */
    private static final int WALK_AFTER_BLOCKED = 40;

    private final Prey prey;
    /** How many to kill; 0: every one it sees. */
    private final int wanted;
    private int killed, arrows;

    private LivingEntity target;
    private long noTargetSince = -1, plannedAt = -EVERY, walkUntil;
    /** Where the target was at the last search for a way to it (NaN: none yet), and whether its answer is due. */
    private double planX = Double.NaN, planZ;
    private boolean awaiting;
    /** Since when its searches find nothing that gets closer (-1: they do). */
    private long noWaySince = -1;
    /**
     * Walking straight at it, the closest it got and for how long it has not got closer:
     * something in the way (a wall higher than a jump), and it goes by routes for a while.
     */
    private double closest = Double.MAX_VALUE;
    private int stalled;
    private long routeOnlyUntil;
    /** An arrow in the air: how long it has flown, how long it is given, and the target's health as it left. */
    private boolean inFlight;
    private int flown, flightWait, drawn, missesInARow;
    private float healthAtShot;
    private final Set<UUID> letGo = new HashSet<>();

    Shoot(Prey prey, int wanted) {
        this.prey = prey;
        this.wanted = wanted;
    }

    private String counted() {
        return killed + (wanted > 0 ? " of " + wanted : "");
    }

    /** What it goes after: a mob of these kinds that hurts it is left to this kill to fight ({@link Defending}). */
    Prey prey() {
        return prey;
    }

    @Override
    String status() {
        return "killing " + prey.words() + " with arrows: " + counted() + " killed" + (inFlight ? ", an arrow in the air" : "");
    }

    @Override
    boolean think(Bots.Bot p, long now) {
        BotPlayer b = p.body;
        if (b.getHealth() < HEALTH_MIN) {
            Bots.halt(p, String.format(Locale.ROOT, "stopped killing %s: very little health left (%.1f); %s killed with arrows",
                    prey.words(), b.getHealth(), counted()));
            return false;
        }
        String noBow = Bow.missing(b);
        if (noBow != null) {
            Bots.halt(p, "stopped killing " + prey.words() + ": " + noBow + " left; " + counted() + " killed with arrows");
            return false;
        }
        if (target != null && (!target.isAlive() || target.isRemoved() || target.level() != b.level() || !prey.matches(p, target))) {
            landed(b);
            target = null;
            inFlight = false;
        }
        if (wanted > 0 && killed >= wanted) {
            Bots.halt(p, "killed " + killed + " " + prey.words() + (arrows > 0 ? " with arrows (" + arrows + " arrows)"
                    : " by hand (no shot was clear for the bow)") + "; what they dropped stayed on the ground");
            return false;
        }
        if (target == null) {
            if (!Hunt.due(p, now, EVERY)) return true;
            boolean far = Hunt.due(p, now, FAR_EVERY);
            target = choose(p, far);
            if (target == null && !far) return true;        // the look out to 128 decides, every 2 s
            if (target == null) {
                if (wanted == 0) {
                    if (noTargetSince < 0) noTargetSince = now;
                    if (now - noTargetSince < NO_TARGET_TICKS) return true;
                    Bots.halt(p, "killed " + killed + " " + prey.words() + " with arrows; half a minute without seeing more");
                    return false;
                }
                Bots.halt(p, "killed " + counted() + " " + prey.words() + " with arrows; I see no more within " + (int) VIEW);
                return false;
            }
            noTargetSince = -1;
            noWaySince = -1;
            closest = Double.MAX_VALUE;
            stalled = 0;
            routeOnlyUntil = 0;
            awaiting = false;
            missesInARow = 0;
            drawn = 0;
            planX = Double.NaN;
            if (p.path != null || p.pending != null) Bots.halt(p, status());
        }
        double d = b.distanceTo(target);
        if (inFlight) {
            watch(p, d);
            return true;
        }
        if (inReach(p) || shooting(p, d, now)) {
            if (p.path != null || p.pending != null) Bots.halt(p, status());
            return true;
        }
        // Range or sight to get: a way to within 2 of it, searched again when it moved. A
        // search that finds nothing that gets closer (it is in a house, across a river) is
        // counted, and past 5 s of them the target is let go of.
        if (awaiting && p.pending == null) {
            awaiting = false;
            boolean closer = !p.searchFailed && p.path != null && !p.path.isEmpty()
                    && Hunt.distance(p.path.get(p.path.size() - 1), target) < d - 1.0;
            if (closer) noWaySince = -1;
            else if (noWaySince < 0) noWaySince = now;
        }
        if (noWaySince >= 0 && now - noWaySince >= NO_WAY_TICKS) {
            letGo.add(target.getUUID());
            target = null;
            Bots.halt(p, status());
            return true;
        }
        boolean creeper = target instanceof Creeper;
        // Near enough, and in sight: it shoots from here, or runs (Creepers). Out of sight, a
        // route may find it sight, or finds nothing and the creeper is let go of.
        if (creeper && d <= CREEPER_NEAR && p.path == null && b.hasLineOfSight(target)) return true;
        if (!creeper && d <= CLOSE_IN && p.path == null && now >= routeOnlyUntil) return true;     // act() walks the last steps
        if (p.pending != null || now - plannedAt < EVERY) return true;
        boolean moved = Double.isNaN(planX) || Math.abs(target.getX() - planX) + Math.abs(target.getZ() - planZ) > Bots.MOVED;
        if (p.path != null && !moved) return true;
        plannedAt = now;
        planX = target.getX();
        planZ = target.getZ();
        awaiting = true;
        Bots.plan(p, target.blockPosition(), creeper ? CREEPER_NEAR : 2.0, Hunt.chase(b), status());
        return true;
    }

    /** Its target dead by its hand: counted, and the ones it could not reach get another chance. */
    private void landed(BotPlayer b) {
        if (target != null && target.isDeadOrDying() && target.getLastHurtByMob() == b) {
            killed++;
            letGo.clear();
        }
    }

    /**
     * Whether it shoots from here: in a bow's range, not too close (10; a creeper, which is
     * never gone in on, 7, where its blast ends), in sight, out of water, and not just blocked.
     */
    private boolean shooting(Bots.Bot p, double d, long now) {
        double min = target instanceof Creeper ? Creepers.EXPLOSION : CLOSE;
        return d > min && d <= SHOT_MAX && now >= walkUntil && !p.body.isInWater() && p.body.hasLineOfSight(target);
    }

    /** An arrow in the air, watched: a hit once the target's health drops, a miss once its time is up. */
    private void watch(Bots.Bot p, double d) {
        if (d <= CLOSE) {                       // it came at it meanwhile: distance decides, not the shot
            inFlight = false;
            return;
        }
        if (target.getHealth() < healthAtShot - 0.01f) {
            inFlight = false;
            missesInARow = 0;
            Bow.Misses.hit(target.getUUID());
            return;
        }
        if (++flown < flightWait) return;
        inFlight = false;
        missesInARow++;
        int shared = Bow.Misses.missed(target.getUUID());
        if (missesInARow >= Bow.Misses.PATIENCE || shared >= Bow.Misses.PATIENCE) {
            // Almost always a block eating the shots: more only gives arrows away.
            letGo.add(target.getUUID());
            target = null;
            Bow.stop(p);
        }
    }

    @Override
    void act(Bots.Bot p) {
        if (target == null) return;
        BotPlayer b = p.body;
        long now = b.getServer().getTickCount();
        if (inReach(p) && !(target instanceof Creeper)) {
            // On top of it: finished by hand, as anyone would, with the best weapon it has
            // (brought to hand, it hits from the next tick). Never a creeper: it blows up.
            Bow.stop(p);
            Bots.release(b);
            b.lookAt(EntityAnchorArgument.Anchor.EYES, target.getBoundingBox().getCenter());
            if (swapped(p, Gear::weapon)) return;
            if (b.getAttackStrengthScale(0.5f) >= 1.0f) {
                b.attack(target);
                b.swing(InteractionHand.MAIN_HAND);
            }
            return;
        }
        double d = b.distanceTo(target);
        if (inFlight) {
            Bow.aim(b, target);                 // the eyes stay on it
            return;
        }
        if (!shooting(p, d, now)) {
            Bow.stop(p);
            drawn = 0;
            if (d <= CLOSE_IN && p.path == null && now >= routeOnlyUntil && !(target instanceof Creeper)) {
                if (d < closest - 0.3) {
                    closest = d;
                    stalled = 0;
                } else if (++stalled > STALLED_MAX) {
                    stalled = 0;
                    closest = Double.MAX_VALUE;
                    routeOnlyUntil = now + ROUTE_ONLY_TICKS;
                }
                if (!walkStraight(p, target.position())) routeOnlyUntil = now + ROUTE_ONLY_TICKS;   // an edge: a route instead
            }
            return;
        }
        Bots.release(b);
        if (++drawn > Bow.DRAW_MAX) {
            Bow.stop(p);
            drawn = 0;
            walkUntil = now + WALK_AFTER_BLOCKED;
            return;
        }
        switch (Bow.step(p, target)) {
            case SHOT -> {
                arrows++;
                drawn = 0;
                inFlight = true;
                flown = 0;
                flightWait = Math.min(FLIGHT_MAX, (int) Math.ceil(d / 2.5) + 6);
                healthAtShot = target.getHealth();
            }
            case BLOCKED -> {
                // A block in the arc, or a player in the way: another spot, then it draws again.
                Bow.stop(p);
                drawn = 0;
                walkUntil = now + WALK_AFTER_BLOCKED;
            }
            default -> {
            }
        }
    }

    /**
     * It ends, or is set aside (a reflex took the body). A target it killed is counted here:
     * it is counted on its next think, and a reflex that takes the body in between would
     * have it dead and never counted.
     */
    @Override
    void end(Bots.Bot p) {
        Bow.stop(p);
        landed(p.body);
        target = null;
        inFlight = false;
        drawn = 0;
    }

    /** Within a player's reach, and in sight: what a click would hit. */
    private boolean inReach(Bots.Bot p) {
        return target != null && p.body.canInteractWithEntity(target, 0.0) && p.body.hasLineOfSight(target);
    }

    /**
     * The nearest of what it was told to kill that it sees, within {@link #VIEW}, not let go
     * of, and worth an arrow ({@link Bow.Misses}). Near first: the ring out to 128 only when
     * there is none within 32 and it is a tick for the far look ({@code far}), since the
     * bigger look costs more.
     */
    private LivingEntity choose(Bots.Bot p, boolean far) {
        LivingEntity near = nearest(p, NEAR);
        return near != null || !far ? near : nearest(p, VIEW);
    }

    private LivingEntity nearest(Bots.Bot p, double radius) {
        BotPlayer b = p.body;
        List<LivingEntity> found = b.level().getEntitiesOfClass(LivingEntity.class, b.getBoundingBox().inflate(radius),
                e -> prey.matches(p, e) && !letGo.contains(e.getUUID()) && Bow.Misses.worthIt(e.getUUID())
                        && e.distanceToSqr(b) <= radius * radius);
        return Hunt.nearestSeen(b, found, Set.of());
    }
}
