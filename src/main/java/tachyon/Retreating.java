package tachyon;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Creeper;
import org.slf4j.Logger;
import tachyon.path.Route;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Backing off when badly hurt, Masurium's retreat: with {@link #BADLY_HURT 6 health} or less
 * (or poisoned), and something hostile at hand, it drops what it is doing and backs off,
 * toward anywhere {@link #FLEE_FAR 40 blocks} from it at a time, and does not stop until
 * nothing hostile is left within {@link #SAFE 24 blocks} that it makes out, health or no
 * health: it heals as it walks, and stopping as soon as it had a little back is how the
 * zombie behind caught up with a Masurium bot, over and over. Then it goes back to what it
 * was doing: a pause, not an order given up ({@link Bots#takeOver}).
 *
 * <p>Two conditions, on purpose. Low health alone is no emergency (digging with two hearts
 * in an empty mine kills nobody, and a bot that stops to wait for food it has not got
 * stays stopped for ever); what kills is low health with something that hits. That is
 * whatever hurt it in the last 15 s, at any distance up to {@link #ATTACKER 25} (a skeleton
 * shoots from fifteen blocks and stays there: a Masurium bot died of one without moving,
 * the retreat looking only near), or a mob hostile to it ({@link Threats#hostile}) within
 * {@link #NEAR 12} that it sees (or that is within 4, round a corner). Once backing off it
 * looks out to 24: what it sees or has within 4, and, out of its sight, what is after it
 * (a zombie behind a tree is still coming) within {@link #BELOW 8} blocks up or down; not
 * what walks the cave under its feet, unseen and not after it, which is no reason to back
 * off for ever. Creepers are left to {@link Creepers}, which knows more about them.
 *
 * <p>The way off is a route to anywhere 40 blocks from what it backs off from (24, in a
 * closed place with nowhere that far), walked a stretch at a time, sprinting while it has
 * the food for it (above 6, as a player), never building or breaking: fleeing is for now.
 * The creepers' escape uses it too ({@link #away}). With no way off (cornered), its owner
 * is told, and it keeps looking for one; cornered for 30 s, it stands its ground, for 30 s
 * more. Backing off for {@link #RETREAT_MAX 60 s} with something still after it, it stands
 * its ground too, for 30 s: a bot that backs off all night does nothing else, and it has
 * its guard. Its {@code retreat_when_hurt} setting turns it off.
 *
 * <p>Health drops only with a hit (a poison's too), so it looks at nothing until a hit leaves
 * a bot badly hurt: reading every bot's health every tick cost more than all the rest of it.
 * Then it looks on the bot's next tick, and every 10 ticks after.
 */
final class Retreating implements Ability {

    private static final Logger LOG = LogUtils.getLogger();

    /** Whether it backs off when badly hurt. */
    static final String RETREAT = "retreat_when_hurt";
    /**
     * How urgent its hold on the body is: over answering an attacker (a bot with two hearts
     * does not charge a skeleton), under fleeing a creeper, coming up for air and digging out.
     */
    static final int URGENCY = 30;
    /** At this health or less it is badly hurt: three hearts, where the hunts already stop. */
    static final float BADLY_HURT = 6.0f;
    /** Whatever hurt it this long ago at most is still after it: 15 s. */
    static final int PATIENCE = 20 * 15;
    /** How far what hurt it is taken into account; a hostile mob it sees, this far; and once backing off, this far. */
    static final double ATTACKER = 25, NEAR = 12, SAFE = 24;
    /** How far it heads away: with 16, routes ended at nine and the zombie caught up walking. */
    static final double FLEE_FAR = 40;
    /** A mob this close counts, seen or not; and one after it, unseen, this far up or down at most. */
    static final double CLOSE = 4, BELOW = 8;
    /** A way off is searched again this often, when it is not walking one. */
    static final int REPLAN = 10;
    /** Cornered this long, it gives up backing off: 30 s. */
    static final int CORNERED_MAX = 20 * 30;
    /** Backing off this long, it stands its ground: 60 s. */
    static final int RETREAT_MAX = 20 * 60;

    /**
     * Who hurt a bot last, and when (its slot): what it backs off from first; and, after it
     * stood its ground cornered, until when it does not back off again.
     */
    private static final class Hurt {
        LivingEntity by;
        long at;
        long standUntil;
        /** A hit just left it badly hurt: looked at on its next tick, not its next look's. */
        boolean fresh;
    }

    /** A bot backing off: from what, since when, and how the search for a way off goes. */
    private static final class Retreat {
        LivingEntity from;
        final long since;
        long plannedAt = -REPLAN;
        boolean awaiting;
        /** Its last search found nowhere 40 off: the next asks for 24. */
        boolean near;
        /** Since when it has found no way off (-1: it has one). */
        long corneredAt = -1;

        Retreat(LivingEntity from, long since) {
            this.from = from;
            this.since = since;
        }
    }

    /** The bots backing off. Empty nearly always. */
    private static final Map<Bots.Bot, Retreat> RETREATS = new IdentityHashMap<>();
    /**
     * The bots a hit left badly hurt, looked at until they are not: health only drops with a
     * hit (a poison's too), so the rest are never looked at. Empty nearly always.
     */
    private static final Set<Bots.Bot> LOW = Collections.newSetFromMap(new IdentityHashMap<>());

    @Override
    public void settings(Settings settings) {
        settings.bool(RETREAT, true, "Badly hurt (6 health or less, or poisoned) with something hostile at hand, it"
                        + " breaks off what it is doing and backs off, until it makes out nothing hostile within 24 blocks"
                        + " (a minute at most).",
                Settings.Who.OWNER).label("Back off when badly hurt").group("Life").basic();
    }

    /**
     * A hit: whether it left it badly hurt (then it is looked at until it is not); and who
     * dealt it, remembered as the first thing it backs off from (creepers are
     * {@link Creepers}').
     */
    @Override
    public void hurt(Bots.Bot p, DamageSource source, float amount) {
        if (badlyHurt(p.body)) {
            LOW.add(p);
            p.slot(Hurt.class, Hurt::new).fresh = true;
        }
        if (!(source.getEntity() instanceof LivingEntity by) || by == p.body || by instanceof Creeper) return;
        Hurt h = p.slot(Hurt.class, Hurt::new);
        h.by = by;
        h.at = p.body.getServer().getTickCount();
    }

    /** Whether it counts as badly hurt: 6 health or less, or poisoned (it keeps losing health, and leaves the last hit to anyone). */
    static boolean badlyHurt(BotPlayer b) {
        return b.getHealth() <= BADLY_HURT || !b.getActiveEffectsMap().isEmpty() && b.hasEffect(MobEffects.POISON);
    }

    @Override
    public void tick(Bots.Bot p, long now) {
        if (RETREATS.isEmpty() && LOW.isEmpty()) return;
        Retreat r = RETREATS.get(p);
        BotPlayer b = p.body;
        if (r == null) {
            // Badly hurt by a hit: looked at at once, then every 10 ticks, until it is not. At
            // once: half a second is five blocks walked, and a bot walking off on its order
            // was out of the 12 that count before its next look.
            if (!LOW.contains(p)) return;
            Hurt h = p.slot(Hurt.class, Hurt::new);
            if (!h.fresh && !Threats.due(p, now)) return;
            h.fresh = false;
            if (!badlyHurt(b)) {
                LOW.remove(p);
                return;
            }
            if (!Settings.bool(p, RETREAT) || now < h.standUntil) return;
            LivingEntity from = hostile(p, now, false);
            if (from == null) return;
            r = new Retreat(from, now);
            RETREATS.put(p, r);
            Notices.technical(LOG, p, p.name() + " backs off: " + health(b) + " health, " + Threats.a(from) + " "
                    + Threats.blocks(b.distanceTo(from)) + " away");
        }
        if (!Settings.bool(p, RETREAT)) {
            end(p, r, "its retreat_when_hurt was turned off");
            return;
        }
        if (Threats.due(p, now) || !r.from.isAlive() || r.from.level() != b.level()) {
            LivingEntity from = hostile(p, now, true);
            if (from == null) {
                end(p, r, null);
                return;
            }
            r.from = from;
            if (now - r.since >= RETREAT_MAX) {
                // Backed off a minute, and still something after it: running on is doing
                // nothing else; it goes on with what it was doing, its guard's hands ready,
                // and for as long again it does not start over.
                p.slot(Hurt.class, Hurt::new).standUntil = now + CORNERED_MAX;
                end(p, r, "backed off for " + RETREAT_MAX / 20 + " s with " + Threats.a(from) + " still after it; it stands its"
                        + " ground");
                return;
            }
        }
        String doing = "backing off: " + health(b) + " health, " + Threats.a(r.from) + " " + Threats.blocks(b.distanceTo(r.from))
                + " away";
        if (Bots.holding(p) != this) {
            if (!Bots.takeOver(p, this, URGENCY, doing)) return;         // something more urgent has it
            r.awaiting = false;
            r.plannedAt = now - REPLAN;
        }
        if (r.awaiting && p.pending == null) {
            r.awaiting = false;
            if (p.path != null) {
                r.corneredAt = -1;
                r.near = false;
            } else if (!r.near) {
                r.near = true;                   // nowhere 40 off (a closed place): 24 off will do
                r.plannedAt = now - REPLAN;
            } else if (r.corneredAt < 0) {
                r.corneredAt = now;
                cornered(p, "badly hurt (" + health(b) + " health) with " + Threats.a(r.from) + " "
                        + Threats.blocks(b.distanceTo(r.from)) + " away");
            } else if (now - r.corneredAt >= CORNERED_MAX) {
                // Nowhere to go: it stands its ground (its guard hits what comes) and goes
                // on with what it was doing, rather than stand there backing off for ever;
                // and for as long again, it does not start over.
                p.slot(Hurt.class, Hurt::new).standUntil = now + CORNERED_MAX;
                end(p, r, "cornered for " + CORNERED_MAX / 20 + " s, it stands its ground");
                return;
            }
        }
        if (p.path == null && p.pending == null && now - r.plannedAt >= REPLAN) {
            r.plannedAt = now;
            r.awaiting = true;
            away(p, r.from, r.near ? SAFE : FLEE_FAR, doing);
        } else if (p.path != null) {
            p.doing = doing;
        }
    }

    /** Running while it has the food for it, as a player can: above 6. */
    @Override
    public void act(Bots.Bot p, long now) {
        if (RETREATS.isEmpty() || !RETREATS.containsKey(p) || Bots.holding(p) != this) return;
        sprint(p);
    }

    @Override
    public void died(Bots.Bot p, DamageSource cause) {
        RETREATS.remove(p);
        LOW.remove(p);
        p.slot(Hurt.class, Hurt::new).by = null;
    }

    @Override
    public void left(Bots.Bot p) {
        RETREATS.remove(p);
        LOW.remove(p);
    }

    /** Over: nothing hostile near any more (said with null), or {@code why} not. Its order goes on. */
    private void end(Bots.Bot p, Retreat r, String why) {
        RETREATS.remove(p);
        BotPlayer b = p.body;
        long seconds = Math.round((b.getServer().getTickCount() - r.since) / 20.0);
        if (why == null) {
            Notices.technical(LOG, p, p.name() + " shook them off: nothing hostile within " + (int) SAFE
                    + " blocks after " + seconds + " s, " + health(b) + " health, at " + Brain.pos(b.blockPosition()));
        } else {
            Notices.technical(LOG, p, p.name() + " stops backing off after " + seconds + " s: " + why);
        }
        Bots.giveBack(p, this);
    }

    /** No way off from where it is: its owner hears of it (once in a while), and it keeps looking. */
    static void cornered(Bots.Bot p, String what) {
        BotPlayer b = p.body;
        Notices.technical(LOG, p, p.name() + " is cornered at " + Brain.pos(b.blockPosition()) + ": " + what
                + ", and no way off");
        Notices.say(p, "cornered", "is cornered at " + Brain.pos(b.blockPosition()) + ": " + what + ", and it finds no way off");
    }

    /**
     * What it backs off from: whatever hurt it in the last 15 s, alive and within 25 (not a
     * creeper); else the nearest mob hostile to it (not a creeper) within 12 that it makes
     * out; or, once backing off, within 24, that it makes out or that is after it (its
     * target) within 8 up or down. Null: nothing.
     */
    private static LivingEntity hostile(Bots.Bot p, long now, boolean backingOff) {
        BotPlayer b = p.body;
        Hurt h = p.slot(Hurt.class, Hurt::new);
        if (h.by != null && h.by.isAlive() && !h.by.isRemoved() && h.by.level() == b.level() && now - h.at <= PATIENCE
                && b.distanceToSqr(h.by) <= ATTACKER * ATTACKER) {
            return h.by;
        }
        double reach = backingOff ? SAFE : NEAR;
        Mob best = null;
        double bestD = reach * reach;
        for (Mob m : Threats.around(p, now)) {
            if (m instanceof Creeper) continue;
            double d = m.distanceToSqr(b);
            if (d > bestD) continue;
            boolean after = backingOff && m.getTarget() == b && Math.abs(m.getY() - b.getY()) <= BELOW;
            if (!after && !Threats.noticed(b, m, CLOSE)) continue;
            best = m;
            bestD = d;
        }
        return best;
    }

    /**
     * A way away from {@code from}, searched: to anywhere {@code far} blocks from it (flat, in
     * any direction, at any height), a stretch at a time, with the fall its health allows
     * ({@link Hunt#chase}), never building or breaking. The chunks read are those toward the
     * point that far straight off, and 24 blocks around. The walk is the legs', once the
     * search comes back; its answer is due a tick or two later. In a closed place (a cave, a
     * house) with nowhere that far the search finds none, and the caller asks for less.
     */
    static void away(Bots.Bot p, Entity from, double far, String doing) {
        BotPlayer b = p.body;
        double dx = b.getX() - from.getX(), dz = b.getZ() - from.getZ();
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 0.01) {           // on top of it: any way will do
            dx = 1;
            dz = 0;
            len = 1;
        }
        BlockPos toward = BlockPos.containing(b.getX() + dx / len * far, b.getY(), b.getZ() + dz / len * far);
        double fx = from.getX(), fz = from.getZ();
        Bots.plan(p, toward, world -> Route.Meta.awayFrom(fx, fz, far), Hunt.chase(b), doing);
    }

    /** Sprinting along the way off while it has the food for it (above 6, as a player's client allows). */
    static void sprint(Bots.Bot p) {
        BotPlayer b = p.body;
        if (p.path != null && b.getFoodData().getFoodLevel() > 6 && !b.isUsingItem()) b.setSprinting(true);
    }

    static String health(BotPlayer b) {
        float h = b.getHealth();
        return h == Math.rint(h) ? String.valueOf((int) h) : String.format(Locale.ROOT, "%.1f", h);
    }
}
