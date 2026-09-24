package tachyon;

import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import tachyon.path.Route;

import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.UUID;

/**
 * Hunting: the nearest of a kind of mob, chased, hit with the best weapon it carries
 * when the swing is fully charged, and what it drops picked up; then the next, until
 * enough are dead or none is left around.
 *
 * <p>The hand is a player's own: {@link Player#attack} with its cooldown, damage,
 * knockback and sweep, from within a player's reach and with the target in sight.
 * Players are never prey, nor tamed or named mobs. Several hunters of one kind spread
 * over the herd: a target another bot chases is taken only when there is no other.
 */
final class Hunt extends Job {

    /** Prey is looked for this far around. */
    private static final double RADIUS = 48;
    /** Without a target, one is looked for this often. */
    private static final int LOOK_EVERY = 10;
    /** This close, with no route left to walk, it walks straight at it. */
    private static final double CLOSE_IN = 4.0;
    /** A route to the target ends within this of its feet. */
    private static final double CHASE_NEAR = 1.5;
    /** After a kill, at most this long picking up what fell, from this far. */
    private static final int LOOT_TICKS = 60;
    private static final double LOOT_RADIUS = 6;
    /** A target it found no way to this many times running is let go. */
    private static final int FAILS_MAX = 3;
    /**
     * Walking straight at it, this many ticks without getting closer is something in the
     * way (a wall higher than a jump): it searches a way round instead, for a while.
     */
    private static final int STALLED_MAX = 30, ROUTE_ONLY_TICKS = 100;

    private final EntityType<?> prey;
    private final String preyName;
    /** How many to kill; 0: every one there is around. */
    private final int wanted;
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

    private int loot;
    private ItemEntity item;

    Hunt(EntityType<?> prey, String preyName, int wanted) {
        this.prey = prey;
        this.preyName = preyName;
        this.wanted = wanted;
    }

    private String doing() {
        return "hunting " + preyName + ": " + killed + (wanted > 0 ? " of " + wanted : "") + " killed";
    }

    @Override
    String status() {
        return doing() + (loot > 0 && item != null ? ", picking up what fell" : "");
    }

    @Override
    boolean think(Bots.Bot p, long now) {
        BotPlayer b = p.body;
        // A target gone: dead (by its hand or not) or out of this world.
        if (target != null && (!target.isAlive() || target.isRemoved() || target.level() != b.level())) {
            if (target.isDeadOrDying() && target.getLastHurtByMob() == b) {
                killed++;
                loot = LOOT_TICKS;
                item = null;
            }
            target = null;
            if (p.path != null) Bots.halt(p, doing());
        }
        if (loot > 0) {
            loot--;
            if (pickUp(p, now)) return true;
            loot = 0;
        }
        if (wanted > 0 && killed >= wanted) {
            Bots.halt(p, "hunted " + killed + " " + preyName);
            return false;
        }
        if (target == null) {
            if (now - lookedAt < LOOK_EVERY) return true;
            lookedAt = now;
            target = choose(p);
            if (target == null) {
                // How many it killed, said even when none: the words go to its brain.
                Bots.halt(p, "no " + preyName + " left within " + (int) RADIUS + "; "
                        + killed + (wanted > 0 ? " of " + wanted : "") + " killed by it");
                return false;
            }
            fails = 0;
            awaiting = false;
            targetX = Double.NaN;
            closest = Double.MAX_VALUE;
            stalled = 0;
            routeOnlyUntil = 0;
            hold(p, Hunt::damage);
        }
        // The answer to the last search for it: no way, a few times, and it is let go.
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
        Bots.plan(p, target.blockPosition(), CHASE_NEAR, doing());
        return true;
    }

    @Override
    void act(Bots.Bot p) {
        BotPlayer b = p.body;
        if (loot > 0 && item != null && item.isAlive()) {
            if (p.path == null) walkStraight(p, item.position());
            return;
        }
        if (target == null) return;
        if (inReach(p)) {
            Bots.release(b);
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

    private static double distance(Route.Point q, LivingEntity e) {
        double dx = q.x() + 0.5 - e.getX(), dy = q.y() - e.getY(), dz = q.z() + 0.5 - e.getZ();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /** Within a player's reach, and in sight: what a click would hit. */
    private boolean inReach(Bots.Bot p) {
        return target != null && p.body.canInteractWithEntity(target, 0.0) && p.body.hasLineOfSight(target);
    }

    /**
     * The nearest of its kind around, preferring one no other bot chases. Never a
     * player, a tamed mob or a named one.
     */
    private LivingEntity choose(Bots.Bot p) {
        BotPlayer b = p.body;
        Set<Entity> chased = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Bots.Bot q : Bots.all()) {
            if (q != p && q.job instanceof Hunt h && h.target != null) chased.add(h.target);
        }
        LivingEntity best = null, free = null;
        double bestD = Double.MAX_VALUE, freeD = Double.MAX_VALUE;
        for (LivingEntity e : b.level().getEntitiesOfClass(LivingEntity.class, b.getBoundingBox().inflate(RADIUS),
                e -> e.getType() == prey && e.isAlive() && !(e instanceof Player)
                        && !(e instanceof TamableAnimal t && t.isTame()) && !e.hasCustomName()
                        && !letGo.contains(e.getUUID()))) {
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

    /** After a kill: the nearest thing lying around, walked to (a player picks up what it touches). */
    private boolean pickUp(Bots.Bot p, long now) {
        BotPlayer b = p.body;
        if (item == null || !item.isAlive()) {
            item = null;
            double best = Double.MAX_VALUE;
            for (ItemEntity e : b.level().getEntitiesOfClass(ItemEntity.class, b.getBoundingBox().inflate(LOOT_RADIUS),
                    ItemEntity::isAlive)) {
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
            Bots.plan(p, item.blockPosition(), 1.0, status());
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
