package tachyon;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.EnderMan;
import net.minecraft.world.entity.monster.breeze.Breeze;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The bow, as a player's hands work it: into the hand, drawn, aimed, let go. What to shoot
 * at, and what to make of a shot, is the caller's (the {@link Shoot} job); only the
 * gesture is here, and the few rules no shot breaks.
 *
 * <p>The draw is the bow's own use, as the server runs a player's: {@code gameMode.useItem}
 * starts it, the game counts it tick by tick, and {@code releaseUsingItem} lets the arrow go
 * with the power that count gives ({@code BowItem.getPowerForTime}): a full draw's arrow
 * leaves at 3 blocks a tick, with a player's spread, damage and critical hit, and takes an
 * arrow from the quiver as a player's does. Nothing on the server ever lets a draw down by
 * itself (a player's client does, when the key is released): {@link #stop} does, on every
 * way out, or the bot walks on at a fifth of its pace with a bow raised at nothing.
 *
 * <p>The aim is where the arrow will be: the target's middle, led by how far it moves while
 * the arrow flies, and raised for the drop, found by flying the arrow as the game does
 * ({@link #pitch}: 0.99 of its speed kept each tick, 0.05 taken by gravity), not guessed
 * along a straight line. The same flight, against the blocks, says whether a shot is clear
 * ({@link #clear}): a block in the arc eats the arrow, and the bot moves instead.
 *
 * <p>Never shot: from water (a Masurium bot spent minutes floating in a lake, shooting at the
 * shore; it gets out first), at a breeze (it deflects arrows) or an enderman (it teleports
 * away), and with a player on top of the target or in the line of fire, extended 8 blocks
 * past it (a guard once killed its boss with an arrow).
 */
final class Bow {

    /** A full draw: the game gives it at 20 ticks; two more, as Masurium's, for a margin. */
    static final int DRAW = 22;
    /** A draw held this long without a shot is let down: something keeps it from shooting. */
    static final int DRAW_MAX = 100;
    /** A full draw's arrow speed, blocks a tick, and what the air and gravity take each tick. */
    static final double SPEED = 3.0, DRAG = 0.99, GRAVITY = 0.05;
    /** The arrow leaves this far under the eyes. */
    static final double BELOW_EYES = 0.1;

    private Bow() {
    }

    /** What a step of the draw came to. */
    enum Step {
        /** Drawing (or the bow just came into the hand): nothing flew yet. */
        DRAWING,
        /** The arrow left, this tick. */
        SHOT,
        /** No shot now: the arc meets a block, or a player is in the way. It waits drawn, for the caller to move. */
        BLOCKED,
        /** No bow, or no arrow for it. */
        NONE
    }

    /** Whether arrows are no use against it: a breeze deflects them, an enderman teleports away. */
    static boolean immune(Entity e) {
        return e instanceof Breeze || e instanceof EnderMan;
    }

    /** The slot of a bow it has arrows for, the hotbar first; -1 when none. */
    static int slot(BotPlayer b) {
        Inventory inv = b.getInventory();
        for (int i = 0; i < Gear.SLOTS; i++) {
            ItemStack s = inv.getItem(i);
            if (s.getItem() instanceof BowItem && !b.getProjectile(s).isEmpty()) return i;
        }
        return -1;
    }

    /** Why it cannot shoot, in words ("I carry no bow"), or null when it has a bow and arrows. */
    static String missing(BotPlayer b) {
        Inventory inv = b.getInventory();
        for (int i = 0; i < Gear.SLOTS; i++) {
            ItemStack s = inv.getItem(i);
            if (s.getItem() instanceof BowItem) return b.getProjectile(s).isEmpty() ? "I carry a bow but no arrows" : null;
        }
        return "I carry no bow";
    }

    /** How many arrows it has for its bow: what the bow would shoot, counted in the 36 slots and the off hand. */
    static int arrows(BotPlayer b) {
        int slot = slot(b);
        if (slot < 0) return 0;
        ItemStack arrow = b.getProjectile(b.getInventory().getItem(slot));
        if (arrow.isEmpty()) return 0;
        int n = Gear.count(b.getInventory(), arrow.getItem());
        return n + (b.getOffhandItem().is(arrow.getItem()) ? b.getOffhandItem().getCount() : 0);
    }

    /**
     * One tick of the draw at {@code target}: the bow into the hand (a tick of its own: the
     * hand changing resets what it does), aimed, drawn, and let go once it is full and the
     * shot is clear. The hands are the caller's for as long as it draws.
     */
    static Step step(Bots.Bot p, LivingEntity target) {
        BotPlayer b = p.body;
        if (!(b.getMainHandItem().getItem() instanceof BowItem) || b.getProjectile(b.getMainHandItem()).isEmpty()) {
            int slot = slot(b);
            if (slot < 0) return Step.NONE;
            stop(p);
            Gear.toHand(p, slot);
            return Step.DRAWING;
        }
        if (!aim(b, target)) return Step.BLOCKED;           // out of a bow's reach
        if (!b.isUsingItem()) {
            b.gameMode.useItem(b, b.level(), b.getMainHandItem(), InteractionHand.MAIN_HAND);
            return b.isUsingItem() ? Step.DRAWING : Step.NONE;
        }
        if (b.getTicksUsingItem() < DRAW) return Step.DRAWING;
        if (!clear(b, target) || someoneInTheLine(b, target)) return Step.BLOCKED;
        b.releaseUsingItem();
        return Step.SHOT;
    }

    /** A draw let down without a shot, if a bow is drawn: on every way out of a shooting. */
    static void stop(Bots.Bot p) {
        BotPlayer b = p.body;
        if (b.isUsingItem() && b.getUseItem().getItem() instanceof BowItem) b.stopUsingItem();
    }

    // --- the aim --------------------------------------------------------------------------------

    /** Where the arrow leaves from. */
    static Vec3 from(BotPlayer b) {
        return new Vec3(b.getX(), b.getEyeY() - BELOW_EYES, b.getZ());
    }

    /**
     * The eyes on where the arrow meets the target: its middle, led by where it goes while the
     * arrow flies (its last tick's step, which is what a player sees of how it moves), and
     * raised for the drop. @return false when no pitch reaches it (too far for a bow)
     */
    static boolean aim(BotPlayer b, LivingEntity target) {
        Vec3 at = from(b);
        Vec3 middle = target.position().add(0, target.getBbHeight() * 0.5, 0);
        Vec3 step = new Vec3(target.getX() - target.xo, 0, target.getZ() - target.zo);
        Vec3 aimAt = middle;
        double[] shot = null;
        for (int i = 0; i < 3; i++) {                 // the lead moves the aim, which changes the flight: twice more
            double dx = aimAt.x - at.x, dz = aimAt.z - at.z;
            shot = pitch(Math.sqrt(dx * dx + dz * dz), aimAt.y - at.y);
            if (shot == null) return false;
            aimAt = middle.add(step.scale(shot[1]));
        }
        double dx = aimAt.x - at.x, dz = aimAt.z - at.z;
        float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        b.setYRot(yaw);
        b.setYHeadRot(yaw);
        b.setXRot((float) -shot[0]);
        return true;
    }

    /**
     * The pitch, in degrees up, that a full draw's arrow needs to meet a point {@code d}
     * blocks away and {@code h} blocks up (down, negative) from where it leaves, and the
     * ticks it flies to get there: the lower of the two arcs that meet it, found by flying
     * the arrow as the game does ({@link #fly}). Null when none does: too far for a bow.
     */
    static double[] pitch(double d, double h) {
        if (d < 0.5) return new double[]{Math.toDegrees(Math.atan2(h, Math.max(d, 0.01))), 1};
        // The height it passes d at grows with the pitch up to the pitch that climbs highest
        // there (45 degrees far off, steeper up close), and falls past it: the lower arc,
        // searched below that one.
        double lo = -80, hi = 45;
        double[] top = fly(hi, d);
        for (double up = 50; up <= 85; up += 5) {
            double[] at = fly(up, d);
            if (at != null && (top == null || at[0] > top[0])) {
                hi = up;
                top = at;
            }
        }
        if (top == null || top[0] < h) return null;
        double[] bottom = fly(lo, d);
        if (bottom != null && bottom[0] >= h) return new double[]{lo, bottom[1]};
        for (int i = 0; i < 40; i++) {
            double mid = (lo + hi) / 2;
            double[] at = fly(mid, d);
            if (at != null && at[0] >= h) hi = mid;
            else lo = mid;
        }
        double[] at = fly(hi, d);
        return new double[]{hi, at == null ? 0 : at[1]};
    }

    /**
     * A full draw's arrow, shot at {@code pitch} degrees up, flown as the game flies it
     * (AbstractArrow.tick: it moves by its speed, then keeps 0.99 of it, then gravity takes
     * 0.05): its height when it has gone {@code d} blocks across, and the ticks that took.
     * Null when it never gets that far (it fell 64 below first, or flew 200 ticks).
     */
    static double[] fly(double pitch, double d) {
        double r = Math.toRadians(pitch);
        double vx = SPEED * Math.cos(r), vy = SPEED * Math.sin(r);
        double x = 0, y = 0;
        for (int t = 1; t <= 200; t++) {
            double nx = x + vx, ny = y + vy;
            if (nx >= d) {
                double f = (d - x) / (nx - x);
                return new double[]{y + (ny - y) * f, t - 1 + f};
            }
            x = nx;
            y = ny;
            vx *= DRAG;
            vy = vy * DRAG - GRAVITY;
            if (y < -64 || vx < 1e-3) return null;
        }
        return null;
    }

    // --- is the shot clear ---------------------------------------------------------------------

    /**
     * Whether the arrow, as it is aimed now, gets to the target without meeting a block: its
     * flight, tick by tick, against the level's blocks, as the game will test it. What a
     * player sees before letting go.
     */
    static boolean clear(BotPlayer b, LivingEntity target) {
        Vec3 at = from(b);
        double yaw = Math.toRadians(b.getYRot()), pitchUp = Math.toRadians(-b.getXRot());
        double dirX = -Math.sin(yaw), dirZ = Math.cos(yaw);
        double vh = SPEED * Math.cos(pitchUp), vy = SPEED * Math.sin(pitchUp);
        double far = Math.sqrt(Math.pow(target.getX() - at.x, 2) + Math.pow(target.getZ() - at.z, 2))
                - target.getBbWidth() / 2;
        double gone = 0;
        Vec3 pos = at;
        for (int t = 0; t < 100 && gone < far; t++) {
            double step = Math.min(vh, far - gone + 0.01);
            double f = vh <= 0 ? 1 : step / vh;
            Vec3 next = pos.add(dirX * vh * f, vy * f, dirZ * vh * f);
            HitResult hit = b.level().clip(new ClipContext(pos, next, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, b));
            if (hit.getType() != HitResult.Type.MISS) return false;
            gone += step;
            pos = next;
            vh *= DRAG;
            vy = vy * DRAG - GRAVITY;
        }
        return true;
    }

    /**
     * Whether another player is on top of the target (within 3 blocks of it) or near the
     * line of fire (within 1.5 of it), extended 8 blocks past the target: an arrow does not
     * choose. A target that is itself a player is not in its own way.
     */
    static boolean someoneInTheLine(BotPlayer b, LivingEntity target) {
        Vec3 eyes = b.getEyePosition();
        Vec3 middle = target.position().add(0, target.getBbHeight() * 0.5, 0);
        Vec3 line = middle.subtract(eyes);
        double length = line.length();
        if (length < 0.01) return false;
        Vec3 dir = line.scale(1 / length);
        for (Player other : b.level().players()) {
            if (other == b || other == target || !other.isAlive() || other.isSpectator()) continue;
            Vec3 c = other.position().add(0, other.getBbHeight() * 0.5, 0);
            if (c.distanceTo(middle) < 3.0) return true;
            Vec3 a = c.subtract(eyes);
            double along = a.dot(dir);
            if (along <= 0 || along >= length + 8.0) continue;
            if (a.subtract(dir.scale(along)).length() < 1.5) return true;
        }
        return false;
    }

    // --- the targets not worth another arrow -------------------------------------------------------

    /**
     * Which targets are not worth another arrow, shared by every shooter: the count belongs
     * to the target, not to whoever aims (a creeper behind a block cannot be hit by any of
     * them). {@value #PATIENCE} arrows that do not lower its health and it is left alone,
     * until it hurts a bot, which shows it can reach and be reached. A Masurium bot once
     * emptied twenty arrows into a creeper it never touched: a block ate every shot. The last
     * {@value #REMEMBERED} targets are remembered, the oldest forgotten first. The server's
     * thread's.
     */
    static final class Misses {
        static final int PATIENCE = 3;
        static final int REMEMBERED = 64;

        private static final Map<UUID, Integer> BY_TARGET = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<UUID, Integer> eldest) {
                return size() > REMEMBERED;
            }
        };

        private Misses() {
        }

        /** Whether another arrow at it is worth it. */
        static boolean worthIt(UUID target) {
            Integer n = BY_TARGET.get(target);
            return n == null || n < PATIENCE;
        }

        /** An arrow at it that did not lower its health. @return how many in a row now */
        static int missed(UUID target) {
            return BY_TARGET.merge(target, 1, Integer::sum);
        }

        /** An arrow at it that did: the count starts again. */
        static void hit(UUID target) {
            BY_TARGET.remove(target);
        }

        /** It hurt a bot: whatever was in the way is not any more, and it is worth arrows again. */
        static void hurtMe(UUID target) {
            BY_TARGET.remove(target);
        }

        /** How many targets are remembered. */
        static int remembered() {
            return BY_TARGET.size();
        }

        /** Nothing remembered: for the tests. */
        static void forget() {
            BY_TARGET.clear();
        }
    }
}
