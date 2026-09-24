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
 */
final class Talking implements Ability {

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
