package tachyon;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.tree.CommandNode;
import com.mojang.logging.LogUtils;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.neoforged.bus.api.IEventBus;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Every {@link Ability}, and the only place that names them all: an ability is plugged
 * in by a line here. Bots and Brain reach the abilities only through what is gathered
 * here (the commands, the brain's tools, the settings) and the hooks called from here.
 */
final class Abilities {

    private static final Logger LOG = LogUtils.getLogger();

    /**
     * Every ability, once. Their order is that of their subcommands under {@code /tachyon},
     * of their tools in what the model is sent, and of their reflexes in a bot's tick.
     */
    static final List<Ability> ALL = List.of(
            new Walking(),
            new Hunting(),
            new Clearing(),
            new Talking(),
            new Looking(),
            new Respawning(),
            new Returning(),
            new Sleeping(),
            new Wielding(),
            new Dressing(),
            new Fighting(),
            new Eating(),
            new Tossing(),
            new Notices(),
            new Recovering(),
            new Complaining());

    /** The brain's tools: every ability's, gathered once. The model is sent them in this order. */
    private static final Tools TOOLS = new Tools();
    /**
     * The settings every ability declared. The server's defaults are read from
     * {@code tachyon.properties}, and read again after {@code /tachyon brain reload}; those
     * set in game, from the world as the server starts (see Bots.onStarting).
     */
    private static final Settings SETTINGS = new Settings(key -> Brain.config().serverDefault(key));

    static {
        for (Ability a : ALL) {
            a.tools(TOOLS);
            a.settings(SETTINGS);
        }
        // Every setting with its words for the menu (a label, a group, a level): one without
        // them stops the start here, where it is seen.
        SETTINGS.check();
    }

    private Abilities() {
    }

    static Tools tools() {
        return TOOLS;
    }

    static Settings settings() {
        return SETTINGS;
    }

    /** Every ability's own event handlers, onto the game's bus: as the mod is made. */
    static void events(IEventBus bus) {
        for (Ability a : ALL) a.events(bus);
    }

    /**
     * Every ability's subcommands, onto {@code /tachyon}. Two of one name would be merged
     * by the command tree without a word, the later one's taking the other's place: said
     * instead, as two tools of one name are. {@code taken}: the names the mod's own
     * subcommands have, besides those already on {@code tachyon}.
     */
    static void commands(LiteralArgumentBuilder<CommandSourceStack> tachyon, CommandBuildContext context, Set<String> taken) {
        commands(ALL, tachyon, context, taken);
    }

    static void commands(List<Ability> abilities, LiteralArgumentBuilder<CommandSourceStack> tachyon,
                         CommandBuildContext context, Set<String> taken) {
        Set<String> names = new HashSet<>(taken);
        for (CommandNode<CommandSourceStack> n : tachyon.getArguments()) names.add(n.getName());
        for (Ability a : abilities) {
            // Each ability's onto a tree of its own first, to see what it adds.
            LiteralArgumentBuilder<CommandSourceStack> its = LiteralArgumentBuilder.literal(tachyon.getLiteral());
            a.commands(its, context);
            for (CommandNode<CommandSourceStack> n : its.getArguments()) {
                if (!names.add(n.getName())) {
                    throw new IllegalStateException(a.getClass().getSimpleName() + " adds /" + tachyon.getLiteral()
                            + " " + n.getName() + ", which is there already");
                }
                tachyon.then(n);
            }
        }
    }

    // The hooks below are called for a bot from Bots and Brain, one ability after another,
    // on the server's thread. One that throws is logged and skipped: a broken reflex must
    // not stop the others, nor take the server down with it (an exception in a player's
    // tick is a crash), and a broken goodbye must not keep a bot's data from being written.

    /** Its reflexes, before its job thinks. (Loops of their own: every tick of every bot.) */
    static void tick(Bots.Bot p, long now) {
        for (Ability a : ALL) {
            try {
                a.tick(p, now);
            } catch (RuntimeException e) {
                failed(a, "tick", p, e);
            }
        }
    }

    /** Its reflexes' hands, after the walk's keys and before the job's hands. */
    static void act(Bots.Bot p, long now) {
        for (Ability a : ALL) {
            try {
                a.act(p, now);
            } catch (RuntimeException e) {
                failed(a, "act", p, e);
            }
        }
    }

    static void joined(Bots.Bot p) {
        each(p, "joined", a -> a.joined(p));
    }

    static void left(Bots.Bot p) {
        each(p, "left", a -> a.left(p));
    }

    static void died(Bots.Bot p, DamageSource cause) {
        each(p, "died", a -> a.died(p, cause));
    }

    static void hurt(Bots.Bot p, DamageSource source, float amount) {
        each(p, "hurt", a -> a.hurt(p, source, amount));
    }

    static void ordered(Bots.Bot p) {
        each(p, "ordered", a -> a.ordered(p));
    }

    static void over(Bots.Bot p, String how) {
        each(p, "over", a -> a.over(p, how));
    }

    /** What the first ability that does not pass makes of words to the bot; PASS when none. */
    static Ability.Heard heard(Bots.Bot p, ServerPlayer from, String text) {
        for (Ability a : ALL) {
            try {
                Ability.Heard h = a.heard(p, from, text);
                if (h != null && h != Ability.Heard.PASS) return h;
            } catch (RuntimeException e) {
                failed(a, "heard", p, e);
            }
        }
        return Ability.Heard.PASS;
    }

    /** Every ability's lines for the brain's instructions, into {@code rules}. */
    static void rules(Bots.Bot p, Collection<String> rules) {
        each(p, "rules", a -> {
            List<String> its = new ArrayList<>();
            a.rules(p, its);
            rules.addAll(its);
        });
    }

    /** Every ability's parts of the brain's state line, into {@code parts}. */
    static void state(Bots.Bot p, Collection<String> parts) {
        each(p, "state", a -> {
            List<String> its = new ArrayList<>();
            a.state(p, its);
            parts.addAll(its);
        });
    }

    private static void each(Bots.Bot p, String hook, Consumer<Ability> call) {
        for (Ability a : ALL) {
            try {
                call.accept(a);
            } catch (RuntimeException e) {
                failed(a, hook, p, e);
            }
        }
    }

    /** When each ability's failure was last logged: once a minute at most, or a reflex that fails every tick floods the log. */
    private static final Map<Ability, Long> WARNED = new HashMap<>();

    private static void failed(Ability a, String hook, Bots.Bot p, RuntimeException e) {
        long now = System.currentTimeMillis();
        Long last = WARNED.get(a);
        if (last != null && now - last < 60_000) return;
        WARNED.put(a, now);
        LOG.warn("[tachyon] {} failed in {} for {} (said once a minute at most)", a.getClass().getSimpleName(), hook, p.name(), e);
    }
}
