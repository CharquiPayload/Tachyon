package tachyon;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.NeutralMob;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.CrossbowAttackMob;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.monster.RangedAttackMob;
import net.minecraft.world.entity.monster.Spider;
import net.minecraft.world.entity.monster.Witch;
import net.minecraft.world.entity.monster.piglin.AbstractPiglin;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.TridentItem;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * What is hostile around a bot, for the reflexes that watch for it ({@link Creepers},
 * {@link Defending}, {@link Retreating}): the hostile mobs within {@link #RADIUS} blocks,
 * looked up once however many of them ask in the same tick, and for each bot only every
 * {@link #EVERY} ticks, on a tick of its own ({@link #due}). A look is a lookup in the
 * level's entity sections around the bot: cheap, but not free, and with hundreds of bots
 * it is what the reflexes cost while nothing happens.
 *
 * <p>Hostile is {@link Enemy}, not {@code Monster}, on purpose: phantoms and slimes are
 * not monsters, and Masurium's guard did not see phantoms for weeks because of it. But an
 * enemy that is neutral until provoked (an enderman, a zombified piglin, a piglin, a spider
 * in the light) is hostile only once it is after this bot ({@link #hostile}): one hit on a
 * zombified piglin angers its whole group, and a bot backing off in the nether from every
 * calm one within 24 would never stop. The look is the level's, walls and all; what a
 * player in the bot's place would make of it (what it sees, what is right next to it) is
 * each reflex's to judge ({@link #noticed}).
 *
 * <p>On a peaceful server nothing is hostile (the game removes hostile mobs there), and
 * nothing is looked up.
 */
final class Threats {

    /** How far a look reaches: what a bow reaches, the farthest any of the reflexes cares about. */
    static final double RADIUS = 25;
    /** A bot is looked around every this many ticks. */
    static final int EVERY = 10;
    /** The cosine of the most a mob's head may be turned away from the bot and still face it: 25 degrees. */
    private static final double FACING = Math.cos(Math.toRadians(25));

    /** The last look, kept for the other reflexes asking in the same tick. The server's thread's. */
    private static Bots.Bot lastBot;
    private static long lastTick = -1;
    private static List<Mob> last = List.of();

    private Threats() {
    }

    /** Whether this is the tick its reflexes look around, one in {@link #EVERY}: each bot on a tick of its own. */
    static boolean due(Bots.Bot p, long now) {
        return Math.floorMod(now + p.name().hashCode(), EVERY) == 0;
    }

    /**
     * The mobs alive within {@link #RADIUS} of it (in a box that far around) that are
     * {@link #hostile} to it, walls or not; none on a peaceful server. Looked up once a tick
     * for a bot, whoever asks.
     */
    static List<Mob> around(Bots.Bot p, long now) {
        if (p == lastBot && now == lastTick) return last;
        BotPlayer b = p.body;
        List<Mob> found = b.level().getDifficulty() == Difficulty.PEACEFUL ? List.of()
                : b.level().getEntitiesOfClass(Mob.class, b.getBoundingBox().inflate(RADIUS), e -> hostile(e, b));
        lastBot = p;
        lastTick = now;
        last = found;
        return found;
    }

    /**
     * Whether {@code e} is hostile to {@code to}: an {@link Enemy}, alive, and, if it is one
     * that leaves players alone until provoked (a {@link NeutralMob}: an enderman, a
     * zombified piglin; a piglin, which minds only players without gold; a spider, calm in
     * the light), only once it is after {@code to}: angry at it, or its target. The game's
     * own reading of whom it is after, where a player reads it off the mob running at them.
     */
    static boolean hostile(Entity e, LivingEntity to) {
        if (!(e instanceof Enemy) || !e.isAlive()) return false;
        if (e instanceof NeutralMob n) return n.getTarget() == to || n.isAngryAt(to);
        if (e instanceof AbstractPiglin || e instanceof Spider) return ((Mob) e).getTarget() == to;
        return true;
    }

    /**
     * Whether a mob looks after {@code b}, as a player would read it: it faces it (its head
     * within about 25 degrees of the bot's eyes, which the game shows every player), and
     * shows it means it: its arms up or its bow drawn (the game's "aggressive", which every
     * client sees as a pose), a crossbow being loaded or held loaded, or, a witch, just
     * facing it (her potions are in no hand until they fly).
     */
    static boolean threatens(Mob m, BotPlayer b) {
        boolean shows = m.isAggressive() || m instanceof Witch
                || m.isHolding(s -> s.getItem() instanceof CrossbowItem)
                && (m.isUsingItem() || m.isHolding(CrossbowItem::isCharged));
        return shows && facing(m, b);
    }

    /**
     * Whether a mob that shoots takes aim at {@code b}: a skeleton, a pillager, a witch, a
     * drowned with a trident, holding what it shoots with (a drowned without a trident
     * shoots nothing, and a player sees its empty hands), and {@link #threatens} it.
     */
    static boolean takingAim(Mob m, BotPlayer b) {
        if (m instanceof Creeper || !(m instanceof RangedAttackMob || m instanceof CrossbowAttackMob)) return false;
        boolean armed = m instanceof Witch || m.isHolding(s -> s.getItem() instanceof BowItem
                || s.getItem() instanceof CrossbowItem || s.getItem() instanceof TridentItem);
        return armed && threatens(m, b);
    }

    /** Whether its head is turned to the bot's eyes, within about 25 degrees. */
    static boolean facing(Mob m, BotPlayer b) {
        Vec3 to = b.getEyePosition().subtract(m.getEyePosition());
        double len = to.length();
        return len < 0.01 || m.getViewVector(1.0f).dot(to) > FACING * len;
    }

    /**
     * Whether a player in its place makes it out: it sees it, or it is within {@code close}
     * blocks, seen or not (heard, or round the corner). Looking by distance alone goes
     * through rock: a creeper in the next cave kept a Masurium bot from going down.
     */
    static boolean noticed(BotPlayer b, Entity e, double close) {
        return b.distanceToSqr(e) <= close * close || b.hasLineOfSight(e);
    }

    /** What something is called in a line: a mob by its kind ("zombie"), a player by name. */
    static String name(Entity e) {
        return e instanceof Player pl ? pl.getGameProfile().getName() : BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).getPath();
    }

    /** "1 block", "12 blocks": a distance in words, rounded. */
    static String blocks(double d) {
        long n = Math.round(d);
        return n + (n == 1 ? " block" : " blocks");
    }

    /** "a zombie", "an enderman", or a player's name bare. */
    static String a(Entity e) {
        if (e instanceof Player) return name(e);
        String n = name(e);
        return ("aeiou".indexOf(n.charAt(0)) >= 0 ? "an " : "a ") + n;
    }
}
