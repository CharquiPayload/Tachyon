package tachyon;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.neoforged.bus.api.IEventBus;

import java.util.List;

/**
 * One thing the bots can do (walk, hunt, clear, talk...), in files of its own, plugged in
 * by being listed once in {@link Abilities}. {@code Bots} and {@code Brain} reach it only
 * through there; it uses theirs (Bots' commands helpers and orders, the brain's notices).
 * See {@code docs/adding-an-ability.md}.
 *
 * <p>Every hook is optional. Two kinds:
 * <ul>
 * <li>What it brings ({@link #events}, {@link #tools}, {@link #settings},
 *     {@link #commands}): asked as the server starts, not on the server's thread, and
 *     not guarded. A mistake there (two tools of one name) stops the start, where it is
 *     seen at once.</li>
 * <li>What it does for a bot (every other hook): on the server's thread, where the world
 *     is. One that throws is logged, once a minute at most, and skipped: it does not stop
 *     the others, nor take the server down, nor keep a bot's data from being written.
 *     What takes long (a route search, a model's answer) goes to a thread of its own.</li>
 * </ul>
 */
interface Ability {

    // --- what it brings -------------------------------------------------------------------

    /**
     * Its own handlers of the game's events ({@code bus.register(this)} for its
     * {@code @SubscribeEvent} methods): what the other hooks do not cover. As the mod is
     * made. {@link Bots#of} tells whether an event's entity is a bot, and which.
     */
    default void events(IEventBus bus) {
    }

    /** The brain's tools it adds: what the model may call, and what runs when it does. */
    default void tools(Tools tools) {
    }

    /** The settings it declares, for its code to read with {@link Settings#bool(Bots.Bot, String)} and the like. */
    default void settings(Settings settings) {
    }

    /**
     * Its subcommands, under {@code /tachyon}: each time the server registers its commands
     * (as it starts, and on {@code /reload}). {@code context} is what an argument read from
     * a registry (a mob, an item, a block) needs. A subcommand another has too is a
     * mistake, said at once.
     */
    default void commands(LiteralArgumentBuilder<CommandSourceStack> tachyon, CommandBuildContext context) {
    }

    // --- a bot's life -----------------------------------------------------------------------

    /** A bot came in, its data already read: what the ability kept for it is there. */
    default void joined(Bots.Bot p) {
    }

    /**
     * A bot is leaving ({@code p.leaving()} says why: removed, dead, the server stopping),
     * before its data is written: what the ability keeps for it goes into its data now. It
     * is still among {@link Bots#all}, and its order as it was: its job, where it went.
     */
    default void left(Bots.Bot p) {
    }

    /** A bot died, of {@code cause}: before {@link #left}, which follows. */
    default void died(Bots.Bot p, DamageSource cause) {
    }

    /** A bot lost {@code amount} health, from {@code source}: after the hit, before a death it brings. */
    default void hurt(Bots.Bot p, DamageSource source, float amount) {
    }

    // --- reflexes -------------------------------------------------------------------------------

    /**
     * A reflex: every tick of every bot, before its job thinks, in {@link Abilities}' order.
     * It may take the body over for a while ({@link Bots#takeOver}: its order waits, and
     * the reflex walks it) or the hands ({@link Bots#holdHands}). It runs hundreds of times
     * a tick with hundreds of bots, so it looks at what is cheap to look at, and at the
     * rest only every so many ticks ({@code now % 20 == 0}).
     */
    default void tick(Bots.Bot p, long now) {
    }

    /**
     * A reflex's hands: every tick of every bot, after the walk pressed its keys and before
     * the job's hands. Keys pressed here stand for this tick (a sprint to flee); a strike
     * here comes before the job's, which is skipped while the hands are held.
     */
    default void act(Bots.Bot p, long now) {
    }

    // --- orders and words -----------------------------------------------------------------------

    /** A bot was given an order: {@code p.doing} says what, {@code p.order} who is told when it is over. */
    default void ordered(Bots.Bot p) {
    }

    /**
     * A bot's order is over by itself (done, or given up), {@code how} it went: after whoever
     * gave it was told. An order that another replaced is not over by itself.
     */
    default void over(Bots.Bot p, String how) {
    }

    /** What an ability makes of words said to a bot in the chat. */
    enum Heard {
        /** Nothing: the next ability decides, and the rule after them all (its brain hears its owner only). */
        PASS,
        /** Its brain does not hear it: the ability answered it itself (a stop), or it is not for the brain. */
        TAKEN,
        /** Its brain hears it, though the speaker is not its owner. */
        LISTEN
    }

    /**
     * Words said in the chat by {@code from} that name the bot, before its brain hears
     * them (a call to a model someone pays for). The first ability that does not
     * {@link Heard#PASS} decides.
     */
    default Heard heard(Bots.Bot p, ServerPlayer from, String text) {
        return Heard.PASS;
    }

    /**
     * Lines for its brain's instructions, as each turn starts (standing orders, a
     * personality): added after the mod's own. Short, since they are paid for on every
     * turn, and in plain words for the model.
     */
    default void rules(Bots.Bot p, List<String> rules) {
    }

    /**
     * Parts for the state line its brain reads at the head of each turn (a mood, what it
     * is afraid of): added after the mod's own, each a few words.
     */
    default void state(Bots.Bot p, List<String> parts) {
    }
}
