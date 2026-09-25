package tachyon;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;

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
 * not monsters, and Masurium's guard did not see phantoms for weeks because of it. The look
 * is the level's, walls and all; what a player in the bot's place would make of it (what
 * it sees, what is right next to it) is each reflex's to judge ({@link #noticed}).
 *
 * <p>On a peaceful server nothing is hostile (the game removes hostile mobs there), and
 * nothing is looked up.
 */
final class Threats {

    /** How far a look reaches: what a bow reaches, the farthest any of the reflexes cares about. */
    static final double RADIUS = 25;
    /** A bot is looked around every this many ticks. */
    static final int EVERY = 10;

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
     * The hostile mobs alive within {@link #RADIUS} of it (in a box that far around), walls
     * or not; none on a peaceful server. Looked up once a tick for a bot, whoever asks.
     */
    static List<Mob> around(Bots.Bot p, long now) {
        if (p == lastBot && now == lastTick) return last;
        BotPlayer b = p.body;
        List<Mob> found = b.level().getDifficulty() == Difficulty.PEACEFUL ? List.of()
                : b.level().getEntitiesOfClass(Mob.class, b.getBoundingBox().inflate(RADIUS),
                        e -> e instanceof Enemy && e.isAlive());
        lastBot = p;
        lastTick = now;
        last = found;
        return found;
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
