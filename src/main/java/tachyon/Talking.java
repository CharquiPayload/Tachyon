package tachyon;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;

/**
 * Words to a bot from a command, as if said to it in the chat. What is said in the chat
 * itself reaches it through Bots.onChat; what it answers is its {@link Brain}'s.
 *
 * <p>And how big a brain it has: {@code brain_lite} sends its model only the core tools
 * ({@link Tool#core}), for a small local model, which chooses badly among many; read where
 * the tools are chosen ({@link Tools#offered}).
 */
final class Talking implements Ability {

    /** Whether its brain is sent only the core tools. */
    static final String BRAIN_LITE = "brain_lite";

    @Override
    public void settings(Settings settings) {
        // Advanced: a player on a hosted model never needs it, and it makes a bot do less.
        settings.bool(BRAIN_LITE, false, "Its brain is sent only the core tools, which a small local model chooses"
                        + " from better.",
                Settings.Who.OWNER).label("Lite brain").group("Brain").advanced();
    }

    @Override
    public void commands(LiteralArgumentBuilder<CommandSourceStack> tachyon, CommandBuildContext context) {
        tachyon.then(Commands.literal("tell")
                .then(Bots.who()
                        .then(Commands.argument("text", StringArgumentType.greedyString())
                                .executes(Talking::tell))));
    }

    /** Words to a bot from the console or a command, as if said to it in the chat. */
    private static int tell(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        List<Bots.Bot> them = Bots.find(c);
        String text = StringArgumentType.getString(c, "text");
        ServerPlayer from = c.getSource().getPlayer();
        for (Bots.Bot p : them) {
            Bots.brain(p).hear(from == null ? null : from.getUUID(), c.getSource().getTextName(), text);
        }
        return Bots.told(c, them, "heard it");
    }
}
