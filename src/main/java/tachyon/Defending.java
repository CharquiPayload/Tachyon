package tachyon;

import com.mojang.logging.LogUtils;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.monster.Phantom;
import net.minecraft.world.entity.monster.Witch;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.player.SweepAttackEvent;
import org.slf4j.Logger;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Fighting back, without its brain: a fight is measured in ticks, and a call to a model in
 * seconds. A zombie once killed a Masurium bot, a diamond sword in its hand, while its brain
 * was thinking what to answer; there was no fight. Masurium's guard and its lookout's
 * answer to whoever shoots at it, with their numbers.
 *
 * <p><b>The guard</b>, its hands: for {@link #ALERT 8 s} after something hurt it, it hits
 * the nearest hostile mob within its reach (a player's, and in sight), or what hurt it, with
 * its best weapon ({@link Gear#weapon}: brought to hand first, which costs the tick a new
 * item's charge starts again in), each hit once the attack is {@link #CHARGED 90%} charged.
 * It never hits first (a fall or a fire starts nothing: only a hit from something), never a
 * creeper (next to a bomb is where one dies; {@link Creepers} deals with them), never a mob
 * of its owner's (a pet), and never an enemy that is neutral until provoked and is not after
 * it ({@link Threats#hostile}: a calm enderman or zombified piglin next to the zombie that
 * bit it), and stays quiet while it runs from a creeper. What hurts it and is not a hostile
 * mob (an iron golem, a wolf, a goat) it does not fight, as Masurium's guard did not: backing
 * off hurt ({@link Retreating}) is its answer to those. It does not take the body: whatever
 * it was doing, walking and all, goes on while it hits. It waits for a bite to be over.
 *
 * <p><b>The answer</b>, its body ({@link #URGENCY}): what hurt it out of its reach, within
 * {@link #FAR 25} blocks, and a mob with a bow or crossbow that it sees taking aim at it
 * ({@link Threats#takingAim}: the pose and the head a player sees), before the first arrow:
 * standing still fifteen blocks from a skeleton is being killed in turns, as a Masurium bot
 * was. From 10 blocks out, with a bow, it shoots back; not at a witch (she drinks potions
 * and outheals arrows) nor at a breeze or an enderman. A phantom it does not chase along the
 * ground: for a few seconds after its hit, it stands and watches for its dive; any other
 * flyer (a blaze, a ghast) never comes down, and its order goes on, the guard's hands ready.
 * Else it goes for it (a route to within 2 of it, searched again as it moves), and the guard
 * hits once in reach. It lets go of one that has not hurt it for {@link #PATIENCE 15 s} or
 * got farther than 25; one it finds no way to it does not go for again for
 * {@link #NO_WAY_TICKS 30 s} (a skeleton on a pillar would otherwise take it from its order
 * every second and a half, all night). A hunt or a kill ordered against that very kind of
 * mob is left to fight it as it does.
 *
 * <p><b>Players</b> are fought only with its {@code defend_from_players} on (off by
 * default, the operators' to change: whom a bot may fight is the server's business): then
 * a player who hurts it, or who hurts its owner within 16 blocks of it where it sees them,
 * is answered as a mob that hurt it is. Never its owner, a bot of its owner's, or a player
 * in creative or spectator; and never for a sword's sweep ({@link #swept}), whose swing was
 * at someone else: two bots side by side against zombies catch each other's sweeps. Without
 * it, a player's hits bring its owner a notice ({@link Complaining}), and nothing more.
 *
 * <p>With nothing going on it costs a look at an empty map, and {@link Threats}' look every
 * 10 ticks for someone taking aim.
 */
final class Defending implements Ability {

    private static final Logger LOG = LogUtils.getLogger();

    /** Whether it also fights players who hurt it or its owner. */
    static final String PLAYERS = "defend_from_players";
    /**
     * How urgent its hold on the body is: over a shot at a creeper (with a zombie biting,
     * the creeper waits), under backing off hurt and running from a creeper.
     */
    static final int URGENCY = 20;
    /** The guard hits back for this long after a hit: 8 s. */
    static final int ALERT = 20 * 8;
    /** How far it looks for what to hit back: what is on top of it, not what is around. */
    static final double LOOK = 6;
    /** How far an attacker is answered, and how long one that has not hurt it is. */
    static final double FAR = 25;
    static final int PATIENCE = 20 * 15;
    /** The closest it shoots from: nearer, a bow is slow and misses, and the sword is for that. */
    static final double BOW_MIN = 10;
    /** A hit waits for the attack to be charged this much, as Masurium's guard. */
    static final float CHARGED = 0.9f;
    /** A way to the attacker is searched again this often, once it moved; this many in a row find none, it lets it be. */
    static final int REPLAN = 10, NO_WAY_MAX = 3;
    /** Without anything in reach, the guard looks again every this many ticks. */
    static final int GUARD_EVERY = 4;
    /** A player who hurts its owner this far from it, in its sight, is answered. */
    static final double OWNER_SEES = 16;
    /** A shot blocked by a block or a player: no other for this long, and it goes for it meanwhile. */
    static final int BLOCKED_TICKS = 40;
    /** An arrow is watched this long at most. */
    static final int FLIGHT_MAX = 30;
    /** One it found no way to is not gone for again for this long: 30 s, as a creeper with no way to it. */
    static final int NO_WAY_TICKS = 20 * 30;
    /** A phantom is watched for, standing, this long after its last hit at most. */
    static final int WATCH_TICKS = 20 * 5;

    /** How it answers an attacker out of its reach. */
    private enum Answer { NONE, BOW, WATCH, CHARGE }

    /** A fight: the guard's alert, the attacker it answers, and what came of it. */
    private static final class Fight {
        final long since;
        long alertUntil;
        LivingEntity attacker;
        /** When it last hurt it (or its owner), or was seen taking aim at it. */
        long lastHit;
        /** The one the guard is hitting, while it stays in reach. */
        LivingEntity engaged;
        Answer answer = Answer.NONE;
        long plannedAt = -REPLAN;
        double planX = Double.NaN, planZ;
        boolean awaiting;
        /** Searches in a row that found no way to it. */
        int noWay;
        long noBowUntil;
        int drawn, flown, flightWait;
        boolean inFlight;
        float healthAtShot;
        // What it did, for the log.
        int hits, arrows;
        String with;
        final Set<String> foes = new LinkedHashSet<>();

        Fight(long since) {
            this.since = since;
        }
    }

    /** The bots in a fight. Empty nearly always: then its reflex looks at nothing but the map. */
    private static final Map<Bots.Bot, Fight> FIGHTS = new IdentityHashMap<>();

    /**
     * The attackers a bot found no way to, by id, and until when it does not go for them
     * again (its slot, while it is in the game). Plain data.
     */
    static final class NoWay {
        private final Map<UUID, Long> until = new HashMap<>();

        /** Whether it does not go for {@code id} at tick {@code now}. */
        boolean has(UUID id, long now) {
            Long t = until.get(id);
            return t != null && t > now;
        }

        /** It found no way to {@code id} at tick {@code now}: not for 30 s. What is over is forgotten. */
        void add(UUID id, long now) {
            until.values().removeIf(t -> t <= now);
            until.put(id, now + NO_WAY_TICKS);
        }
    }

    /**
     * The sword sweeps of this tick: for each player that swung, at whom (its id) and when.
     * The game hurts what the sweep catches with the same player's attack as the one it swung
     * at, and says whom it swung at only after both: this is how a hit is told from a sweep.
     */
    private static final Map<UUID, long[]> SWEEPS = new HashMap<>();

    @Override
    public void settings(Settings settings) {
        settings.bool(PLAYERS, false, "It also fights players who attack it or its owner, as it fights mobs that do."
                        + " When it is off, a player's hits only bring its owner a notice.", Settings.Who.OPERATOR)
                .label("Defend from players").group("Fighting").advanced();
    }

    /** Whether it is in a fight now: hit lately, or answering an attacker. */
    static boolean fighting(Bots.Bot p) {
        return !FIGHTS.isEmpty() && FIGHTS.containsKey(p);
    }

    // --- who it fights ----------------------------------------------------------------------------

    /**
     * A hit: the guard is alert for 8 s, and what hurt it (the shooter, for an arrow) is its
     * attacker if it is one to fight. A hurt with no one behind it (a fall, a fire,
     * drowning) starts nothing.
     */
    @Override
    public void hurt(Bots.Bot p, DamageSource source, float amount) {
        Entity by = source.getEntity();
        if (by == null || by == p.body) return;
        if (by instanceof Player pl && swept(pl, p.body)) return;       // caught in a swing at someone else
        long now = p.body.getServer().getTickCount();
        Fight f = fight(p, now);
        f.alertUntil = now + ALERT;
        if (by instanceof LivingEntity e && foe(p, e)) attack(p, f, e, now, "hit it");
    }

    /**
     * Whether {@code hurt} was caught by {@code hitter}'s sword sweep this tick, the swing
     * being at someone else: not a hit meant for it.
     */
    static boolean swept(Player hitter, LivingEntity hurt) {
        if (SWEEPS.isEmpty()) return false;
        long[] s = SWEEPS.get(hitter.getUUID());
        return s != null && s[1] == hitter.getServer().getTickCount() && s[0] != hurt.getId();
    }

    /**
     * Its owner hurt by a player: the bots of theirs that see it, with the setting on, answer
     * that player. And every sword sweep noted, at whom it was ({@link #swept}), last of the
     * handlers, when whether it sweeps at all is settled.
     */
    @Override
    public void events(IEventBus bus) {
        bus.addListener(EventPriority.LOWEST, true, SweepAttackEvent.class, e -> {
            if (!e.isSweeping() || e.getEntity().level().isClientSide()) return;
            long now = e.getEntity().getServer().getTickCount();
            SWEEPS.values().removeIf(s -> s[1] != now);
            SWEEPS.put(e.getEntity().getUUID(), new long[]{e.getTarget().getId(), now});
        });
        bus.addListener(LivingDamageEvent.Post.class, e -> {
            if (!(e.getEntity() instanceof ServerPlayer owner) || !(e.getSource().getEntity() instanceof Player hitter)
                    || hitter == owner || swept(hitter, owner)) return;
            for (Bots.Bot q : Bots.all()) {
                if (!owner.getUUID().equals(q.owner) || q.body == hitter || q.body == owner) continue;
                BotPlayer b = q.body;
                if (!b.isAlive() || b.level() != owner.level() || b.distanceToSqr(owner) > OWNER_SEES * OWNER_SEES) continue;
                if (!foe(q, hitter) || !b.hasLineOfSight(owner) && !b.hasLineOfSight(hitter)) continue;
                long now = b.getServer().getTickCount();
                Fight f = fight(q, now);
                f.alertUntil = now + ALERT;
                attack(q, f, hitter, now, "hit its owner");
            }
        });
    }

    private static Fight fight(Bots.Bot p, long now) {
        Fight f = FIGHTS.get(p);
        if (f == null) {
            f = new Fight(now);
            FIGHTS.put(p, f);
        }
        return f;
    }

    /** {@code e} is its attacker from now on: it {@code did} so ("hit it", "hit its owner", "took aim at it"). */
    private static void attack(Bots.Bot p, Fight f, LivingEntity e, long now, String did) {
        if (f.attacker != e) {
            f.attacker = e;
            f.answer = Answer.NONE;
            f.planX = Double.NaN;
            Notices.technical(LOG, p, p.name() + " answers " + Threats.a(e) + ", "
                    + Threats.blocks(p.body.distanceTo(e)) + " away, which " + did);
        }
        f.lastHit = now;
        f.foes.add(Threats.name(e));
    }

    /**
     * Whether it fights {@code e} when it hurts it: a hostile mob (an {@link Enemy}, not a
     * creeper, nor its owner's pet), or a player with its {@code defend_from_players} on
     * (never its owner, a bot of its owner's, or a player in creative or spectator). Not a
     * mob that is no enemy (an iron golem, a wolf, a bee), as Masurium's guard fought only
     * enemies: a village's golem angry at it would be fought to the death, and it is the
     * stronger; a bot badly hurt by one backs off instead ({@link Retreating}).
     */
    static boolean foe(Bots.Bot p, LivingEntity e) {
        if (e == p.body || !e.isAlive() || e instanceof Creeper) return false;
        if (e instanceof Player pl) {
            if (!Settings.bool(p, PLAYERS) || pl.isCreative() || pl.isSpectator() || pl.getUUID().equals(p.owner)) return false;
            Bots.Bot other = Bots.of(pl);
            return other == null || p.owner == null || !p.owner.equals(other.owner);
        }
        return e instanceof Enemy && !(e instanceof OwnableEntity o && p.owner != null && p.owner.equals(o.getOwnerUUID()));
    }

    // --- the answer: the body ---------------------------------------------------------------------

    @Override
    public void tick(Bots.Bot p, long now) {
        Fight f = FIGHTS.isEmpty() ? null : FIGHTS.get(p);
        if (f == null) {
            // Nothing going on: every 10 ticks, whether a mob with a bow takes aim at it.
            if (!Threats.due(p, now)) return;
            Mob archer = aiming(p, now);
            if (archer == null) return;
            f = fight(p, now);
            attack(p, f, archer, now, "takes aim at it");
        } else if (Threats.due(p, now) && f.attacker instanceof Mob m && Threats.threatens(m, p.body) && p.body.hasLineOfSight(m)) {
            f.lastHit = now;             // still at it, as it looks: it has not let it be
        } else if (f.attacker == null && Threats.due(p, now)) {
            Mob archer = aiming(p, now);
            if (archer != null) attack(p, f, archer, now, "takes aim at it");
        }
        if (f.attacker != null) {
            String gone = gone(p, f, now);
            if (gone != null) {
                Notices.technical(LOG, p, p.name() + " lets " + Threats.a(f.attacker) + " be: " + gone);
                f.attacker = null;
                f.answer = Answer.NONE;
            }
        }
        if (f.attacker == null && now > f.alertUntil && f.engaged == null) {
            end(p, f);
            return;
        }
        answer(p, f, now);
    }

    /** Why it lets its attacker be, or null while it answers it. */
    private static String gone(Bots.Bot p, Fight f, long now) {
        LivingEntity a = f.attacker;
        BotPlayer b = p.body;
        if (!a.isAlive() || a.isRemoved()) return a.isDeadOrDying() ? "it died" : "it is gone";
        if (a.level() != b.level()) return "it is gone";
        if (b.distanceToSqr(a) > FAR * FAR) return "it is more than " + (int) FAR + " blocks away";
        if (now - f.lastHit > PATIENCE) return "it has not hurt it for " + PATIENCE / 20 + " s";
        if (a instanceof Player && !foe(p, a)) return "players are not to be fought (" + Settings.named(PLAYERS) + " is off)";
        return null;
    }

    /** Its attacker out of reach: shot at, watched (a flyer), or gone for; in reach, the guard's, standing. */
    private void answer(Bots.Bot p, Fight f, long now) {
        LivingEntity a = f.attacker;
        BotPlayer b = p.body;
        if (a == null || leftToTheJob(p, a)) {
            letGo(p, f);
            return;
        }
        double d = b.distanceTo(a);
        boolean held = Bots.holding(p) == this;
        if (reach(p, a)) {
            // The guard hits it. If it went for it, it stands: its order's walk would take it away mid-fight.
            f.answer = Answer.NONE;
            if (held) {
                if (p.path != null || p.pending != null) Bots.halt(p, "fighting " + Threats.a(a));
                p.doing = "fighting " + Threats.a(a);
            }
            return;
        }
        String doing;
        Answer how;
        if (bow(p, f, a, d, now)) {
            how = Answer.BOW;
            doing = "shooting back at " + Threats.a(a) + " " + Threats.blocks(d) + " away";
        } else if (!a.onGround() && a.getY() > b.getY() + 1.5 && !a.isInWater()) {
            // A flyer is not chased along the ground: a route "to it" is one step, done, over
            // and over. A phantom dives: it stands and watches for that, a few seconds after
            // each hit. A blaze or a ghast never comes down, and standing under one is its
            // order kept waiting for nothing: the guard's hands are ready if it comes in reach.
            if (!(a instanceof Phantom) || now - f.lastHit > WATCH_TICKS) {
                if (held) letGo(p, f);
                f.answer = Answer.NONE;
                return;
            }
            how = Answer.WATCH;
            doing = "watching " + Threats.a(a) + " overhead";
        } else {
            if (p.slot(NoWay.class, NoWay::new).has(a.getUUID(), now)) {
                // No way to it lately: its order goes on, and the guard hits it if it comes.
                if (held) letGo(p, f);
                f.answer = Answer.NONE;
                return;
            }
            how = Answer.CHARGE;
            doing = "going for " + Threats.a(a) + " " + Threats.blocks(d) + " away";
        }
        if (!Bots.takeOver(p, this, URGENCY, doing)) return;          // something more urgent has it
        if (!held || f.answer != how) {
            if (how != Answer.CHARGE && (p.path != null || p.pending != null)) Bots.halt(p, doing);
            f.answer = how;
            f.awaiting = false;
            f.noWay = 0;
            f.plannedAt = now - REPLAN;
            f.planX = Double.NaN;
        }
        if (how == Answer.CHARGE && f.awaiting && p.pending == null) {
            f.awaiting = false;
            f.noWay = p.path == null ? f.noWay + 1 : 0;
            if (f.noWay >= NO_WAY_MAX) {
                // Neither a shot nor a way: nothing to do from here, and holding the body would
                // keep its order waiting for nothing; asking again at once would take the body
                // back every few ticks. (What the search said is its doing now.)
                p.slot(NoWay.class, NoWay::new).add(a.getUUID(), now);
                Notices.technical(LOG, p, p.name() + " does not go for " + Threats.a(a) + " for "
                        + (NO_WAY_TICKS / 20) + " s: it finds no way to it (" + p.doing + ")");
                letGo(p, f);
                return;
            }
        }
        p.doing = doing;
        if (how != Answer.CHARGE) return;
        boolean moved = Double.isNaN(f.planX) || Math.abs(a.getX() - f.planX) + Math.abs(a.getZ() - f.planZ) > Bots.MOVED;
        if (p.pending == null && (p.path == null || moved) && now - f.plannedAt >= REPLAN) {
            f.plannedAt = now;
            f.planX = a.getX();
            f.planZ = a.getZ();
            f.awaiting = true;
            Bots.plan(p, a.blockPosition(), 2.0, Hunt.chase(b), doing);
        }
    }

    /**
     * Whether it shoots back at it from here: a bow and arrows, 10 blocks or more, in sight,
     * and worth an arrow. Whether the shot is clear (no block in the arc, nobody in the line)
     * is looked at as the arrow is let go ({@link Bow#step}); a blocked one sends it at the
     * attacker for 2 s instead.
     */
    private static boolean bow(Bots.Bot p, Fight f, LivingEntity a, double d, long now) {
        BotPlayer b = p.body;
        return d >= BOW_MIN && now >= f.noBowUntil && !(a instanceof Witch) && !Bow.immune(a) && !b.isInWater()
                && Bow.Misses.worthIt(a.getUUID()) && Bow.missing(b) == null && b.hasLineOfSight(a);
    }

    /**
     * A hunt or a kill ordered against that very kind: its job fights it as it does (and
     * counts it), and the answer does not take it away from it.
     */
    private static boolean leftToTheJob(Bots.Bot p, LivingEntity a) {
        Job j = Bots.job(p);
        return j instanceof Hunt h && h.prey().matches(p, a) || j instanceof Shoot s && s.prey().matches(p, a);
    }

    /** Its body given back, if it held it, and a draw let down. */
    private void letGo(Bots.Bot p, Fight f) {
        f.answer = Answer.NONE;
        if (f.drawn > 0 || f.inFlight) {
            Bow.stop(p);
            f.drawn = 0;
            f.inFlight = false;
        }
        Bots.giveBack(p, this);
    }

    /** The fight is over: what it did said in a line for the log, and what it held let go of. */
    private void end(Bots.Bot p, Fight f) {
        FIGHTS.remove(p);
        letGo(p, f);
        Bots.freeHands(p, this);
        if (f.hits == 0 && f.arrows == 0) return;
        BotPlayer b = p.body;
        StringBuilder s = new StringBuilder();
        if (f.hits > 0) s.append(f.hits).append(f.hits == 1 ? " hit" : " hits").append(" with ").append(f.with);
        if (f.arrows > 0) s.append(s.length() > 0 ? " and " : "").append(f.arrows).append(f.arrows == 1 ? " arrow" : " arrows");
        Notices.technical(LOG, p, p.name() + " fought back (" + String.join(", ", f.foes) + "): " + s + " in "
                + Math.round((b.getServer().getTickCount() - f.since) / 20.0) + " s; " + Retreating.health(b)
                + " health");
    }

    // --- the guard: the hands ----------------------------------------------------------------------

    @Override
    public void act(Bots.Bot p, long now) {
        if (FIGHTS.isEmpty()) return;
        Fight f = FIGHTS.get(p);
        if (f == null) return;
        BotPlayer b = p.body;
        boolean held = Bots.holding(p) == this;
        if (f.answer == Answer.BOW && held) {
            shoot(p, f, now);
            return;
        }
        if (f.drawn > 0 || f.inFlight) {
            Bow.stop(p);
            f.drawn = 0;
            f.inFlight = false;
        }
        if (f.answer == Answer.WATCH && held && f.attacker != null) {
            Bots.release(b);
            b.lookAt(EntityAnchorArgument.Anchor.EYES, f.attacker.getBoundingBox().getCenter());
        }
        if (Creepers.fleeing(p)) {
            f.engaged = null;
            Bots.freeHands(p, this);
            return;
        }
        LivingEntity t = f.engaged;
        if (t != null && (!t.isAlive() || t.isRemoved() || !reach(p, t) || t != f.attacker && now > f.alertUntil)) t = null;
        if (t == null && (now - f.since) % GUARD_EVERY == 0) t = target(p, f, now);
        f.engaged = t;
        if (t == null) {
            Bots.freeHands(p, this);
            return;
        }
        // Its hands for as long as the fight is in reach: a job's between two hits would put its tool back in them.
        if (!Bots.holdHands(p, this, 5)) return;            // a bite: it waits for it to be over
        ItemStack before = b.getMainHandItem();
        Job.wield(p, Gear::weapon);
        if (b.getMainHandItem() != before) return;          // a tick lost on purpose: a new item's charge starts again
        if (b.getAttackStrengthScale(0.5f) < CHARGED) return;
        face(p, t);
        b.attack(t);
        b.swing(InteractionHand.MAIN_HAND);
        f.hits++;
        ItemStack hand = b.getMainHandItem();
        f.with = hand.isEmpty() ? "its bare hands" : Gear.id(hand);
        f.foes.add(Threats.name(t));
    }

    /**
     * What the guard hits: its attacker, in reach; else, while alert, the nearest mob hostile
     * to it ({@link Threats#hostile}: not a calm enderman or zombified piglin), not a creeper,
     * within its reach and sight.
     */
    private static LivingEntity target(Bots.Bot p, Fight f, long now) {
        LivingEntity a = f.attacker;
        if (a != null && a.isAlive() && reach(p, a)) return a;
        if (now > f.alertUntil) return null;
        BotPlayer b = p.body;
        LivingEntity best = null;
        double bestD = LOOK * LOOK;
        for (Mob m : b.level().getEntitiesOfClass(Mob.class, b.getBoundingBox().inflate(LOOK),
                e -> !(e instanceof Creeper) && Threats.hostile(e, b))) {
            double d = m.distanceToSqr(b);
            if (d < bestD && reach(p, m) && foe(p, m)) {
                bestD = d;
                best = m;
            }
        }
        return best;
    }

    /**
     * The eyes on what it hits. Walking (its order goes on), only the head turns: the body
     * moves the way it faces, and facing the mob would walk it into the mob.
     */
    private static void face(Bots.Bot p, LivingEntity t) {
        BotPlayer b = p.body;
        if (p.path == null) {
            b.lookAt(EntityAnchorArgument.Anchor.EYES, t.getBoundingBox().getCenter());
        } else {
            b.setYHeadRot((float) (Math.toDegrees(Math.atan2(t.getZ() - b.getZ(), t.getX() - b.getX())) - 90.0));
        }
    }

    /** One tick of shooting back, standing: drawn, let go when full and clear, the arrow watched. */
    private void shoot(Bots.Bot p, Fight f, long now) {
        BotPlayer b = p.body;
        LivingEntity a = f.attacker;
        Bots.release(b);
        if (a == null || !Bots.holdHands(p, this, 5)) return;
        if (f.inFlight) {
            Bow.aim(b, a);
            if (a.getHealth() < f.healthAtShot - 0.01f) {
                f.inFlight = false;
                Bow.Misses.hit(a.getUUID());
            } else if (++f.flown >= f.flightWait) {
                f.inFlight = false;
                Bow.Misses.missed(a.getUUID());
            }
            return;
        }
        if (++f.drawn > Bow.DRAW_MAX) {
            blocked(p, f, now);
            return;
        }
        switch (Bow.step(p, a)) {
            case SHOT -> {
                f.arrows++;
                f.drawn = 0;
                f.inFlight = true;
                f.flown = 0;
                f.flightWait = Math.min(FLIGHT_MAX, (int) Math.ceil(b.distanceTo(a) / 2.5) + 6);
                f.healthAtShot = a.getHealth();
            }
            case BLOCKED, NONE -> blocked(p, f, now);
            default -> {
            }
        }
    }

    /** No shot from here (a block in the arc, a player in the way): it goes for it instead, for 2 s. */
    private static void blocked(Bots.Bot p, Fight f, long now) {
        Bow.stop(p);
        f.drawn = 0;
        f.noBowUntil = now + BLOCKED_TICKS;
    }

    /** Within a player's reach, and in sight: what a click would hit. */
    private static boolean reach(Bots.Bot p, LivingEntity e) {
        return p.body.canInteractWithEntity(e, 0.0) && p.body.hasLineOfSight(e);
    }

    /**
     * A mob with a bow or a crossbow (a witch too) taking aim at it, as a player sees it
     * ({@link Threats#takingAim}: drawn, facing it), in its sight, within 25; not one it
     * found no way to lately, unless it can shoot back. The nearest, or null.
     */
    private static Mob aiming(Bots.Bot p, long now) {
        BotPlayer b = p.body;
        List<Mob> around = Threats.around(p, now);
        if (around.isEmpty()) return null;
        Mob best = null;
        double bestD = FAR * FAR;
        NoWay noWay = null;
        for (Mob m : around) {
            if (!Threats.takingAim(m, b)) continue;
            double d = m.distanceToSqr(b);
            if (d >= bestD) continue;
            if (noWay == null) noWay = p.slot(NoWay.class, NoWay::new);
            if (noWay.has(m.getUUID(), now) && Bow.missing(b) != null) continue;
            if (b.hasLineOfSight(m)) {
                bestD = d;
                best = m;
            }
        }
        return best;
    }

    @Override
    public void died(Bots.Bot p, DamageSource cause) {
        FIGHTS.remove(p);
    }

    @Override
    public void left(Bots.Bot p) {
        FIGHTS.remove(p);
    }

    /** While it fights, its brain knows: "fighting back a zombie". */
    @Override
    public void state(Bots.Bot p, List<String> parts) {
        Fight f = FIGHTS.isEmpty() ? null : FIGHTS.get(p);
        if (f == null) return;
        LivingEntity who = f.attacker != null ? f.attacker : f.engaged;
        parts.add(who != null ? "fighting back " + Threats.a(who) : "something hit you just now");
    }
}
