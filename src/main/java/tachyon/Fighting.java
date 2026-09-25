package tachyon;

import com.google.gson.JsonObject;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.commands.arguments.ResourceArgument;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Fighting when its brain (or a command) asks: {@code attack}, a few hits at what is within
 * reach, and {@code kill}, taking mobs down with the bow ({@link Shoot}), or by sword when
 * it has no bow and arrows or the bow is no use against them ({@link Hunt}, said as a
 * kill). Self-defense, fighting back when hurt, is a reflex of its own elsewhere.
 *
 * <p><b>Players.</b> A bot fights a player only by the player's name, only with
 * {@code hunt_players} on (off by default, the operators' to change: whom a bot may fight is
 * the server's business), and never one in creative or spectator. See {@link Prey}.
 *
 * <p><b>Creepers</b> are never gone after by sword: in melee they blow up, and take the
 * ground with them. With a bow, a kill takes them from afar. A creeper named in an attack is
 * hit, since whoever asked named it.
 *
 * <p>An attack is not an order: it takes the hands for a few hits ({@link Bots#holdHands})
 * and leaves what the bot was doing as it was, walking and all; its brain gets how it went
 * once the hits are over, a few seconds later, as the answer of the tool. Unnamed, what it
 * hits is the nearest mob hostile to it ({@link Threats#hostile}: not a calm enderman), and
 * what it says is out of reach is only what it sees: a player in its place knows nothing
 * of the zombie behind the wall.
 */
final class Fighting implements Ability {

    /** Whether its brain's attack and kill may go after a player named to them. */
    static final String HUNT_PLAYERS = "hunt_players";
    /** An attack looks this far for what to hit, and says where the nearest is when it is out of reach. */
    private static final double LOOK = 24;
    /** An attack gives this many hits when not told, and this many at most. */
    private static final int HITS = 3, HITS_MAX = 10;
    /** An attack is over after this many ticks per hit asked for, whatever came of it. */
    private static final int TICKS_PER_HIT = 40;

    /** An attack under way: at what, how many hits asked for and given, until when, and who is told. */
    private static final class Strikes {
        final LivingEntity target;
        final int times;
        final long until;
        final CompletableFuture<String> told;
        int hits;
        String with = "my bare hands";

        Strikes(LivingEntity target, int times, long until, CompletableFuture<String> told) {
            this.target = target;
            this.times = times;
            this.until = until;
            this.told = told;
        }
    }

    /** The attacks under way, by bot. Empty nearly always: nothing to look at then. */
    private static final Map<Bots.Bot, Strikes> STRIKES = new IdentityHashMap<>();

    @Override
    public void settings(Settings settings) {
        settings.bool(HUNT_PLAYERS, false, "Its brain's attack and kill may go after a player named to them.",
                Settings.Who.OPERATOR).label("Fight players by name").group("Fighting").advanced();
    }

    // --- attack: a few hits, with the hands ------------------------------------------------------

    /**
     * An attack looked at, every tick: over once it gave its hits, its target died or got out
     * of its reach, or its time ran out. Its hands are held meanwhile.
     */
    @Override
    public void tick(Bots.Bot p, long now) {
        if (STRIKES.isEmpty()) return;
        Strikes s = STRIKES.get(p);
        if (s == null) return;
        String over = null;
        if (!s.target.isAlive() || s.target.isRemoved()) over = DIED;
        else if (s.hits >= s.times || now > s.until) over = "it is still standing";
        else if (!reach(p, s.target)) over = "then it got out of my reach";
        else if (!Bots.holdHands(p, this, 5)) over = "then my hands were needed for something else";
        if (over != null) end(p, s, over);
    }

    @Override
    public void act(Bots.Bot p, long now) {
        if (STRIKES.isEmpty()) return;
        Strikes s = STRIKES.get(p);
        if (s == null || s.hits >= s.times) return;
        BotPlayer b = p.body;
        b.lookAt(EntityAnchorArgument.Anchor.EYES, s.target.getBoundingBox().getCenter());
        // The best weapon again before each hit. One brought to hand hits from the next tick:
        // the game starts its charge again and gives the hand its damage after this tick's
        // hands, as a player's; hit now, the old item's damage would land under its name.
        ItemStack before = b.getMainHandItem();
        Job.wield(p, Gear::weapon);
        if (b.getMainHandItem() != before) return;
        if (b.getAttackStrengthScale(0.5f) < 1.0f) return;
        ItemStack hand = b.getMainHandItem();
        s.with = hand.isEmpty() ? "my bare hands" : Gear.id(hand);
        b.attack(s.target);
        b.swing(InteractionHand.MAIN_HAND);
        s.hits++;
    }

    /** How an attack ended when its target died. */
    private static final String DIED = "it died";

    /** An attack over, {@code how}: "it died", "it is still standing", "then it got out of my reach"... */
    private void end(Bots.Bot p, Strikes s, String how) {
        STRIKES.remove(p);
        Bots.freeHands(p, this);
        String name = called(s.target);
        if (s.hits == 0) {
            s.told.complete("I did not get to hit " + name + ": " + how);
            return;
        }
        String hits = s.hits == 1 ? "once" : s.hits + " times";
        s.told.complete("I hit " + name + " " + hits + " with " + s.with + (how.equals(DIED) ? ", and it died" : "; " + how));
    }

    @Override
    public void died(Bots.Bot p, DamageSource cause) {
        Strikes s = STRIKES.get(p);
        if (s != null) end(p, s, "then I died");
    }

    @Override
    public void left(Bots.Bot p) {
        Strikes s = STRIKES.get(p);
        if (s != null) end(p, s, "then I left the game");
    }

    /** What hurt a bot is worth arrows again, from any shooter: it can reach and be reached. */
    @Override
    public void hurt(Bots.Bot p, DamageSource source, float amount) {
        Entity by = source.getEntity();
        if (by != null) Bow.Misses.hurtMe(by.getUUID());
    }

    /** Within a player's reach, and in sight: what a click would hit. */
    private static boolean reach(Bots.Bot p, LivingEntity e) {
        return p.body.canInteractWithEntity(e, 0.0) && p.body.hasLineOfSight(e);
    }

    private static String name(Entity e) {
        return e instanceof Player pl ? pl.getGameProfile().getName() : BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).getPath();
    }

    /** What it hit, as a sentence names it: a player by name ("Steve"), a mob by kind ("the zombie"). */
    private static String called(Entity e) {
        return e instanceof Player ? name(e) : "the " + name(e);
    }

    /**
     * The attack tool: what to hit chosen here, the hits given in the ticks that follow, and
     * the answer once they are over. Nothing within reach: where the nearest is, instead.
     */
    private CompletableFuture<String> attack(Tool.Call call) {
        Bots.Bot p = call.bot();
        BotPlayer b = p.body;
        JsonObject a = call.args();
        int times = HITS;
        if (a.has("times") && a.get("times").isJsonPrimitive()) {
            try {
                times = Math.max(1, Math.min(HITS_MAX, a.get("times").getAsInt()));
            } catch (RuntimeException e) {
                // Not a number: the usual three.
            }
        }
        String what = a.has("target") && a.get("target").isJsonPrimitive() ? a.get("target").getAsString().trim() : "";
        Prey prey = null;
        if (!what.isEmpty()) {
            String refused = players(p, what);
            if (refused != null) return CompletableFuture.completedFuture(refused);
            Prey.Read read = Prey.read(what, b.getServer(), true);
            if (read.prey() == null) return CompletableFuture.completedFuture(read.why());
            prey = read.prey();
        }
        if (STRIKES.containsKey(p)) return CompletableFuture.completedFuture("I am attacking already");
        if (!Bots.handsFree(p)) return CompletableFuture.completedFuture("my hands are busy right now (" + Wielding.busy(p) + ")");
        LivingEntity nearest = null, inReach = null;
        Prey wanted = prey;
        List<LivingEntity> found = b.level().getEntitiesOfClass(LivingEntity.class, b.getBoundingBox().inflate(LOOK),
                e -> (wanted != null ? wanted.matches(p, e) : !(e instanceof Creeper) && Threats.hostile(e, b))
                        && e.distanceToSqr(b) <= LOOK * LOOK);           // a sphere, not the box's corners: 24 means 24
        found.sort(Comparator.comparingDouble(e -> e.distanceToSqr(b)));
        int tried = 0;
        for (LivingEntity e : found) {
            // The nearest it sees, and the nearest in its reach: the sight tried on a few, the nearest first.
            if (tried++ >= Hunt.SIGHTS_MAX || inReach != null) break;
            if (!Hunt.noticed(b, e)) continue;
            if (nearest == null) nearest = e;
            if (reach(p, e)) inReach = e;
        }
        double nearestD = nearest == null ? 0 : nearest.distanceToSqr(b);
        String kind = prey != null ? prey.words() : "hostile mob";
        if (inReach == null) {
            if (nearest == null) {
                return CompletableFuture.completedFuture("I see no " + kind + " within " + (int) LOOK + " blocks"
                        + (prey == null ? " (creepers I do not hit unless told to by name)" : ""));
            }
            return CompletableFuture.completedFuture(String.format("the nearest %s is %d blocks away %s, at %s: out of my"
                            + " reach, so I did not hit it. I would have to go closer (kill goes after it).", name(nearest),
                    Math.round(Math.sqrt(nearestD)), Brain.direction(b.getX(), b.getZ(), nearest.getX(), nearest.getZ()),
                    Brain.pos(nearest.blockPosition())));
        }
        Bots.holdHands(p, this, 5);
        CompletableFuture<String> told = new CompletableFuture<>();
        STRIKES.put(p, new Strikes(inReach, times, b.getServer().getTickCount() + (long) times * TICKS_PER_HIT, told));
        return told;
    }

    // --- kill: the bow, or the sword ------------------------------------------------------------

    /**
     * A kill of {@code prey}, {@code count} of them (0: every one it sees): with the bow when
     * it has one and arrows, by sword when not, or when arrows are no use against them.
     *
     * @return why not, or null once ordered; {@code how} gets "with arrows" or "by sword"
     */
    static String orderKill(Bots.Bot p, Prey prey, int count, Bots.Order by, StringBuilder how) {
        BotPlayer b = p.body;
        boolean immune = false;
        for (EntityType<?> kind : prey.kinds()) {
            if (kind == EntityType.BREEZE || kind == EntityType.ENDERMAN) immune = true;
        }
        boolean bow = Bow.missing(b) == null && !immune;
        if (!bow && prey.kinds().contains(EntityType.CREEPER)) {
            return "a creeper is not fought by sword: it blows up. With a bow and arrows I shoot it from afar;"
                    + " without them I keep away from it";
        }
        if (bow) {
            Bots.orderJob(p, new Shoot(prey, count), "killing " + prey.words() + " with arrows", by);
            how.append("with arrows (").append(Bow.arrows(b)).append(" arrows)");
        } else {
            Bots.orderHunt(p, new Hunt(prey, count, true, null), by);
            how.append("by sword");
        }
        return null;
    }

    /** Why a player named to it is not to be fought (its setting is off), or null (not a player's name, or it is on). */
    private static String players(Bots.Bot p, String words) {
        Player pl = p.body.getServer().getPlayerList().getPlayerByName(words.trim());
        if (pl == null || Settings.bool(p, HUNT_PLAYERS)) return null;
        return words.trim() + " is a player: I fight players only when the operators turn on hunt_players";
    }

    @Override
    public void tools(Tools tools) {
        tools.add(Tool.later("attack", "Hit what is within your reach now, a few times, with your best weapon, each hit"
                        + " fully charged; you keep doing what you were doing. Without a target, the nearest hostile mob."
                        + " Out of reach, you are told where the nearest is.",
                List.of(Tool.optional("target", "string", "What to hit: a mob as Minecraft names it (zombie); leave it"
                                + " out for the nearest hostile"),
                        Tool.optional("times", "integer", "How many hits, 1 to 10; 3 when not said")),
                this::attack));
        tools.add(new Tool("kill", "Kill mobs with your bow, from 10 to 25 blocks away: whenever told to KILL something, this"
                + " and not hunt (the bow is for killing, the sword for hunting). Without a bow or arrows, or against a breeze"
                + " or an enderman, you go by sword. What they drop stays on the ground.",
                List.of(Tool.param("target", "string", "The mob, as Minecraft names it (zombie, skeleton); a player's exact"
                                + " name only if the operators allow fighting players"),
                        Tool.optional("count", "integer", "How many, 1 to 8; 1 when not said; 0 for every one you see")),
                call -> {
                    Bots.Bot p = call.bot();
                    JsonObject a = call.args();
                    String what = a.has("target") && a.get("target").isJsonPrimitive() ? a.get("target").getAsString().trim() : "";
                    String refused = what.isEmpty() ? null : players(p, what);
                    if (refused != null) return refused;
                    Prey.Read read = Prey.read(what, p.body.getServer(), true);
                    if (read.prey() == null) return read.why();
                    int count = 1;
                    if (a.has("count") && a.get("count").isJsonPrimitive()) {
                        try {
                            count = Math.max(0, Math.min(Hunt.COUNT_MAX, a.get("count").getAsInt()));
                        } catch (RuntimeException e) {
                            // Not a number: one.
                        }
                    }
                    StringBuilder how = new StringBuilder();
                    String no = orderKill(p, read.prey(), count, call.order(), how);
                    if (no != null) return no;
                    String many = count == 0 ? "every " + read.prey().words() + " I see" : count + " " + read.prey().words();
                    return how.toString().startsWith("by sword")
                            ? "started killing " + many + "; none killed yet, it takes a while. Say you are on it; no need to"
                            + " talk about the weapon"
                            : "started killing " + many + " " + how + "; none killed yet, it takes a while."
                            + " What they drop stays on the ground: that is for hunting";
                }));
    }

    // --- /tachyon kill ------------------------------------------------------------------------

    /** {@code /tachyon kill <who> <mob> [count]}: as the brain's kill; without a count, 1; 0, every one it sees. */
    @Override
    public void commands(LiteralArgumentBuilder<CommandSourceStack> tachyon, CommandBuildContext context) {
        tachyon.then(Commands.literal("kill")
                .then(Bots.who()
                        .then(Commands.argument("mob", ResourceArgument.resource(context, Registries.ENTITY_TYPE))
                                .executes(c -> kill(c, 1))
                                .then(Commands.argument("count", IntegerArgumentType.integer(0, 10_000))
                                        .executes(c -> kill(c, IntegerArgumentType.getInteger(c, "count")))))));
    }

    private static int kill(CommandContext<CommandSourceStack> c, int count) throws CommandSyntaxException {
        List<Bots.Bot> them = Bots.find(c);
        Holder.Reference<EntityType<?>> mob = ResourceArgument.getEntityType(c, "mob");
        if (mob.value() == EntityType.PLAYER) {
            return Bots.fail(c.getSource(), "players are prey only by their name, through the brain's kill, with hunt_players");
        }
        Prey prey = Prey.of(mob.value());
        Bots.Order by = Bots.order(c.getSource(), them);
        String refused = null, how = "";
        int ordered = 0;
        for (Bots.Bot p : them) {
            StringBuilder h = new StringBuilder();
            String no = orderKill(p, prey, count, by, h);
            if (no != null) refused = no;
            else {
                ordered++;
                how = h.toString();
            }
        }
        if (ordered == 0) return refused == null ? 0 : Bots.fail(c.getSource(), refused);
        return Bots.told(c, them, "killing " + prey.words() + (count > 0 ? ", " + count + " each" : ", every one in sight")
                + (them.size() == 1 ? " " + how : ""));
    }
}
