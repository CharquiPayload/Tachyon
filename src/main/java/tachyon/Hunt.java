package tachyon;

import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import tachyon.path.Route;

import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Hunting: the nearest of what it hunts, chased, hit with the best weapon it carries when
 * the swing is fully charged, and what it drops picked up; then the next, until enough are
 * dead or none is left. Masurium's Hunter, on the server.
 *
 * <p>The hand is a player's own: {@link Player#attack} with its cooldown, damage, knockback
 * and sweep, from within a player's reach and with the target in sight, and the weapon
 * weighed again before every hit ({@link Gear#weapon}: building and digging leave
 * cobblestone in the hand, and a Masurium bot kept hitting with it). What it goes after is
 * a {@link Prey}: kinds of mob (the nearest of any), never a tamed or named one, and a
 * player only by name, for a kill, with {@code hunt_players}. Several hunters of one kind
 * spread over the herd: a target another bot chases is taken only when there is no other.
 *
 * <p>It sees 128 blocks around (what a player's game shows of the mobs), the nearest 48
 * first. With none in sight it does not stand still: it goes out looking for them, in legs
 * of 48 blocks the way it was told or faces, turning right when three legs in a row get it
 * nowhere, for 300 blocks or 3 minutes at most, and twice per errand at most. With no count
 * it hunts every one it finds, and is done after half a minute without seeing more.
 *
 * <p>After a kill it picks up what lies within 12 blocks: not its trash (it would toss it
 * again), not what does not fit, and not an item it stood by for 2 s without it going in.
 * The prey it found no way to gets another chance after each kill (they go in and out of
 * houses). Below 6 health it stops, saying so: staying alive comes first. Every way it ends
 * says how many it killed, in words for its brain.
 */
final class Hunt extends Job {

    /** Prey is looked for this far around, the nearest {@link #NEAR} first. */
    static final double VIEW = 128, NEAR = 48;
    /** Without a target, one is looked for this often. */
    private static final int LOOK_EVERY = 10;
    /** This close, with no route left to walk, it walks straight at it. */
    private static final double CLOSE_IN = 4.0;
    /** A route to the target ends within this of its feet. */
    private static final double CHASE_NEAR = 1.5;
    /** After a kill, what lies this far around is picked up, for this long at most. */
    private static final double LOOT_RADIUS = 12;
    private static final int LOOT_TICKS = 200;
    /** An item it stands by this long without it going in is left on the ground. */
    private static final int BY_ITEM_TICKS = 40;
    /** A target it found no way to this many times running is let go of, until its next kill. */
    private static final int FAILS_MAX = 3;
    /**
     * Walking straight at the target, this many ticks without getting closer is something in
     * the way (a wall higher than a jump): it searches a way round instead, for a while.
     */
    private static final int STALLED_MAX = 30, ROUTE_ONLY_TICKS = 100;
    /** Below this health it stops. */
    static final float HEALTH_MIN = 6.0f;
    /** How many a hunt from its brain kills at most, and when it is not told how many: "hunt cows" is not every cow. */
    static final int COUNT_MAX = 8;
    /** Looking for prey out of sight: legs this long, this far and this long at most, this many times an errand. */
    private static final int LEG = 48, SEARCH_BLOCKS = 300, SEARCH_TICKS = 20 * 180, SEARCHES = 2;
    /** Legs in a row that get it this little further count as none; three of them, it turns; five turns, it gives up. */
    private static final double LEG_PROGRESS = 2.0;
    private static final int LEGS_STUCK = 3, TURNS_MAX = 4;
    /** With no count, it waits this long for more to come in sight before it is done. */
    private static final int NO_PREY_TICKS = 600;

    private final Prey prey;
    /** How many to kill; 0: every one it finds. */
    private final int wanted;
    /** A kill ordered without a bow: said "killed ... by sword", not "hunted". */
    private final boolean killing;
    /** Which way it goes looking first; null: the way it faces. */
    private final Direction toward;
    private int killed;

    private LivingEntity target;
    private long lookedAt = -LOOK_EVERY;
    /** Where the target was at the last search for it (NaN: none yet). */
    private double targetX = Double.NaN, targetZ;
    private boolean awaiting;
    private int fails;
    /** Walking straight at the target: the closest it got, and for how long it has not got closer. */
    private double closest = Double.MAX_VALUE;
    private int stalled;
    /** Until this tick it goes by routes only, even when close. */
    private long routeOnlyUntil;
    private final Set<UUID> letGo = new HashSet<>();

    /** Picking up after a kill: for how long still, what it goes for, and what it gave up on. */
    private int loot;
    private ItemEntity item;
    private int byItem;
    private boolean awaitingItem;
    private final Set<UUID> leftOnGround = new HashSet<>();

    /** Out looking for prey: which way, from where, how far it got, since when, and how it goes. */
    private boolean searching;
    private int searches, legsStuck, turns;
    private Direction heading;
    private double originX, originZ, bestProgress, walkedSearching;
    private long searchStarted;
    private long noPreySince = -1;

    /**
     * @param wanted  how many to kill; 0: every one it finds
     * @param killing a kill without a bow: said as a kill, by sword
     * @param toward  which way it goes looking first when none is in sight; null: the way it faces
     */
    Hunt(Prey prey, int wanted, boolean killing, Direction toward) {
        this.prey = prey;
        this.wanted = wanted;
        this.killing = killing;
        this.toward = toward;
    }

    private String verb() {
        return killing ? "killing" : "hunting";
    }

    private String counted() {
        return killed + (wanted > 0 ? " of " + wanted : "");
    }

    /** "hunted 2 of 8 cow", "killed 1 zombie by sword": what it did, for how it ended. */
    private String done() {
        String n = wanted > 0 && killed < wanted ? counted() : String.valueOf(killed);
        return (killing ? "killed " : "hunted ") + n + " " + prey.words() + (killing ? " by sword" : "");
    }

    @Override
    String status() {
        String s = verb() + " " + prey.words() + ": " + counted() + " killed";
        if (searching) s += ", looking for more to the " + heading.getName() + " (" + Math.round(walkedSearching) + " blocks out)";
        else if (loot > 0 && item != null) s += ", picking up what fell";
        return s;
    }

    @Override
    boolean think(Bots.Bot p, long now) {
        BotPlayer b = p.body;
        if (b.getHealth() < HEALTH_MIN) {
            Bots.halt(p, String.format(Locale.ROOT, "stopped %s %s: very little health left (%.1f); %s", verb(), prey.words(),
                    b.getHealth(), done()));
            return false;
        }
        // A target gone: dead (by its hand or not), out of this world, or no longer prey.
        if (target != null && (!target.isAlive() || target.isRemoved() || target.level() != b.level() || !prey.matches(p, target))) {
            if (target.isDeadOrDying() && target.getLastHurtByMob() == b) {
                killed++;
                loot = LOOT_TICKS;
                item = null;
                letGo.clear();               // the ones it found no way to get another chance
            }
            target = null;
            if (p.path != null || p.pending != null) Bots.halt(p, status());
        }
        if (loot > 0) {
            loot--;
            if (pickUp(p, now)) return true;
            loot = 0;
        }
        if (wanted > 0 && killed >= wanted) {
            Bots.halt(p, done() + " and picked up what they dropped");
            return false;
        }
        if (target == null) {
            if (now - lookedAt < LOOK_EVERY) return true;
            lookedAt = now;
            target = choose(p);
            if (target == null) return lookFurther(p, now);
            if (searching) {
                searching = false;
                Bots.halt(p, status());
            }
            noPreySince = -1;
            fails = 0;
            awaiting = false;
            targetX = Double.NaN;
            closest = Double.MAX_VALUE;
            stalled = 0;
            routeOnlyUntil = 0;
        }
        // The answer to the last search for it: no way, a few times, and it is let go of.
        if (awaiting && p.pending == null) {
            awaiting = false;
            // A route that does not bring it closer (it stands where the search could get
            // it: in a pit, under the one it chases) is no way either.
            boolean closer = !p.searchFailed && p.path != null && !p.path.isEmpty()
                    && distance(p.path.get(p.path.size() - 1), target) < b.distanceTo(target) - 1.0;
            fails = closer || inReach(p) ? 0 : fails + 1;
            if (fails >= FAILS_MAX) {
                letGo.add(target.getUUID());
                target = null;
                return true;
            }
        }
        if (inReach(p) || p.pending != null) return true;
        if (stalled > STALLED_MAX) {
            stalled = 0;
            closest = Double.MAX_VALUE;
            routeOnlyUntil = now + ROUTE_ONLY_TICKS;
            targetX = Double.NaN;                       // search now
        }
        boolean straight = now >= routeOnlyUntil;
        if (straight && p.path == null && b.distanceTo(target) <= CLOSE_IN) return true;     // act() walks it
        // A way to it, searched again only when it moved: the follower's rule.
        boolean moved = Double.isNaN(targetX)
                || Math.abs(target.getX() - targetX) + Math.abs(target.getZ() - targetZ) > Bots.MOVED;
        if (p.path != null && !moved) return true;
        if (now - p.plannedAt < Bots.REPLAN_TICKS) return true;
        p.plannedAt = now;
        targetX = target.getX();
        targetZ = target.getZ();
        awaiting = true;
        Bots.plan(p, target.blockPosition(), CHASE_NEAR, chase(b), status());
        return true;
    }

    /**
     * None in sight: it goes out looking (twice an errand at most), or, with no count, waits
     * half a minute for more to show up; else it is done, saying how many and how far it
     * looked. @return false once it is done
     */
    private boolean lookFurther(Bots.Bot p, long now) {
        BotPlayer b = p.body;
        if (!searching && searches < SEARCHES) {
            searching = true;
            searches++;
            letGo.clear();                   // from elsewhere, a way to them may be found
            heading = searches == 1 && toward != null ? toward : b.getDirection();
            originX = b.getX();
            originZ = b.getZ();
            bestProgress = 0;
            walkedSearching = 0;
            legsStuck = 0;
            turns = 0;
            searchStarted = now;
            Bots.halt(p, status());
        }
        if (searching) return searchLeg(p, now);
        if (wanted == 0) {
            if (noPreySince < 0) noPreySince = now;
            if (now - noPreySince < NO_PREY_TICKS) return true;
            Bots.halt(p, done() + "; half a minute without seeing more");
            return false;
        }
        Bots.halt(p, done() + "; I see no more within " + (int) VIEW);
        return false;
    }

    /**
     * Out looking: legs of {@link #LEG} blocks the way it heads, any height ("X and Z first,
     * Y up close"), each searched from where the last one ended. Three that get it nowhere
     * (sea, cliff, no way) and it turns right. @return false once it gives up, saying why
     */
    private boolean searchLeg(Bots.Bot p, long now) {
        BotPlayer b = p.body;
        double progress = Math.max(0, (b.getX() - originX) * heading.getStepX() + (b.getZ() - originZ) * heading.getStepZ());
        walkedSearching = Math.max(walkedSearching, progress);
        if (now - searchStarted > SEARCH_TICKS) {
            searching = false;
            Bots.halt(p, done() + "; I looked for more for 3 minutes (" + Math.round(walkedSearching) + " blocks to the "
                    + heading.getName() + ") and saw none");
            return false;
        }
        if (p.path != null || p.pending != null) return true;         // on the way
        if (progress >= SEARCH_BLOCKS) {
            searching = false;
            Bots.halt(p, done() + "; I looked for more " + Math.round(progress) + " blocks to the " + heading.getName()
                    + " and saw none");
            return false;
        }
        if (progress < bestProgress + LEG_PROGRESS && now - searchStarted > LOOK_EVERY) {
            if (++legsStuck >= LEGS_STUCK) {
                if (++turns > TURNS_MAX) {
                    searching = false;
                    Bots.halt(p, done() + "; I looked for more on all four sides and found neither a way nor prey");
                    return false;
                }
                heading = heading.getClockWise();
                originX = b.getX();
                originZ = b.getZ();
                bestProgress = 0;
                legsStuck = 0;
            }
        } else {
            bestProgress = progress;
            legsStuck = 0;
        }
        if (now - p.plannedAt < Bots.REPLAN_TICKS) return true;
        p.plannedAt = now;
        int ox = b.getBlockX() + heading.getStepX() * LEG, oz = b.getBlockZ() + heading.getStepZ() * LEG;
        Bots.plan(p, new BlockPos(ox, b.getBlockY(), oz), world -> Route.Meta.onlyXZ(ox, oz), chase(b), status());
        return true;
    }

    @Override
    void act(Bots.Bot p) {
        BotPlayer b = p.body;
        if (loot > 0 && item != null && item.isAlive()) {
            if (p.path == null && p.pending == null) walkStraight(p, item.position());
            return;
        }
        if (target == null) return;
        if (inReach(p)) {
            Bots.release(b);
            // The weapon weighed again before every hit: what the hand holds changes.
            hold(p, Gear::weapon);
            b.lookAt(EntityAnchorArgument.Anchor.EYES, target.getBoundingBox().getCenter());
            if (b.getAttackStrengthScale(0.5f) >= 1.0f) {
                b.attack(target);
                b.swing(InteractionHand.MAIN_HAND);
            }
        } else if (p.path == null && b.distanceTo(target) <= CLOSE_IN
                && b.getServer().getTickCount() >= routeOnlyUntil) {
            double d = b.distanceTo(target);
            if (d < closest - 0.3) {
                closest = d;
                stalled = 0;
            } else {
                stalled++;
            }
            if (!walkStraight(p, target.position())) stalled = STALLED_MAX + 1;     // an edge: a route instead
        }
    }

    @Override
    void end(Bots.Bot p) {
        target = null;
        item = null;
    }

    static double distance(Route.Point q, LivingEntity e) {
        double dx = q.x() + 0.5 - e.getX(), dy = q.y() - e.getY(), dz = q.z() + 0.5 - e.getZ();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /**
     * How a chase searches: partial routes (a stretch that gets closer is walked, and the
     * rest searched from its end), and a fall as long as its health allows, as Masurium's
     * safeFall: 3 blocks, and a block more for every 4 health, 12 at most. A fall past 3
     * costs a point of health a block, and a hunter with full health takes 8.
     */
    static Route.Options chase(BotPlayer b) {
        int fall = Math.max(3, Math.min(12, 3 + (int) Math.floor(b.getHealth()) / 4));
        return new Route.Options(fall, Route.Options.byDefault().maxNodes(), false, true);
    }

    /** Within a player's reach, and in sight: what a click would hit. */
    private boolean inReach(Bots.Bot p) {
        return target != null && p.body.canInteractWithEntity(target, 0.0) && p.body.hasLineOfSight(target);
    }

    /**
     * The nearest prey around, preferring one no other bot chases: within {@link #NEAR}
     * first, and out to {@link #VIEW} only when there is none that near, since the bigger
     * look costs more.
     */
    private LivingEntity choose(Bots.Bot p) {
        Set<Entity> chased = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Bots.Bot q : Bots.all()) {
            if (q != p && q.job instanceof Hunt h && h.target != null) chased.add(h.target);
        }
        LivingEntity near = nearest(p, NEAR, chased);
        return near != null ? near : nearest(p, VIEW, chased);
    }

    private LivingEntity nearest(Bots.Bot p, double radius, Set<Entity> chased) {
        BotPlayer b = p.body;
        LivingEntity best = null, free = null;
        double bestD = radius * radius, freeD = radius * radius;
        for (LivingEntity e : b.level().getEntitiesOfClass(LivingEntity.class, b.getBoundingBox().inflate(radius),
                e -> prey.matches(p, e) && !letGo.contains(e.getUUID()))) {
            double d = e.distanceToSqr(b);
            if (d < bestD) {
                bestD = d;
                best = e;
            }
            if (d < freeD && !chased.contains(e)) {
                freeD = d;
                free = e;
            }
        }
        return free != null ? free : best;
    }

    /**
     * After a kill: the nearest thing lying around that it would keep, walked to (a player
     * picks up what it touches). Not its trash, not what does not fit, not what it gave up
     * on. @return whether it is still at it
     */
    private boolean pickUp(Bots.Bot p, long now) {
        BotPlayer b = p.body;
        if (item != null && !item.isAlive()) {
            item = null;                      // picked up, or gone
            byItem = 0;
        }
        if (item != null && b.distanceTo(item) < 1.5 && ++byItem > BY_ITEM_TICKS) {
            leftOnGround.add(item.getUUID());         // it stood on it, and it did not go in
            item = null;
            byItem = 0;
        }
        if (awaitingItem && p.pending == null) {
            awaitingItem = false;
            if (item != null && (p.searchFailed || p.path == null) && b.distanceTo(item) > CLOSE_IN) {
                leftOnGround.add(item.getUUID());     // no way to it
                item = null;
            }
        }
        if (item == null) {
            byItem = 0;
            double best = Double.MAX_VALUE;
            for (ItemEntity e : b.level().getEntitiesOfClass(ItemEntity.class, b.getBoundingBox().inflate(LOOT_RADIUS),
                    e -> e.isAlive() && !leftOnGround.contains(e.getUUID()))) {
                ItemStack s = e.getItem();
                Entity thrower = e.getOwner();
                if (Tossing.trash(p, s) || !Gear.fits(b.getInventory(), s)
                        || thrower != null && thrower.getUUID().equals(b.getUUID())) continue;
                double d = e.distanceToSqr(b);
                if (d < best) {
                    best = d;
                    item = e;
                }
            }
            if (item == null) return false;
        }
        if (b.distanceTo(item) > CLOSE_IN && p.pending == null && p.path == null
                && now - p.plannedAt >= Bots.REPLAN_TICKS) {
            p.plannedAt = now;
            awaitingItem = true;
            Bots.plan(p, item.blockPosition(), 1.0, chase(b), status());
        }
        return true;
    }

    /** What a hit with it adds to the hand's: the item's attack damage in the main hand. */
    static double damage(ItemStack s) {
        if (s.isEmpty()) return 0;
        double[] sum = {0};
        s.forEachModifier(EquipmentSlot.MAINHAND, (attribute, modifier) -> {
            if (attribute.value() == Attributes.ATTACK_DAMAGE.value()
                    && modifier.operation() == AttributeModifier.Operation.ADD_VALUE) {
                sum[0] += modifier.amount();
            }
        });
        return sum[0];
    }
}
