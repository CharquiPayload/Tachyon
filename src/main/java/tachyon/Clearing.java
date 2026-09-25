package tachyon;

import com.google.gson.JsonObject;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;

import java.util.List;

/**
 * Clearing a box, ordered by a command or by the brain. The breaking itself, a tick at a
 * time and shared by every bot told to clear the same box, is the {@link Clear} job.
 *
 * <p>The brain's {@code clear} is for an area someone asked to have cleared, never to get a
 * material ({@link Gathering} is): a bot asked for dirt once cleared a box around itself,
 * and nine blocks of its owner's house went. So its brain's clear refuses a box that holds
 * blocks players placed ({@link PlacedBlocks}), and says a person may order it with the
 * command, which a person gives knowing what is in the box.
 */
final class Clearing implements Ability {

    @Override
    public void commands(LiteralArgumentBuilder<CommandSourceStack> tachyon, CommandBuildContext context) {
        tachyon.then(Commands.literal("clear")
                .then(Bots.who()
                        .then(Commands.argument("from", BlockPosArgument.blockPos())
                                .then(Commands.argument("to", BlockPosArgument.blockPos())
                                        .executes(Clearing::clear)))));
    }

    private static int clear(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        List<Bots.Bot> them = Bots.find(c);
        BlockPos a = BlockPosArgument.getLoadedBlockPos(c, "from"), b = BlockPosArgument.getLoadedBlockPos(c, "to");
        String refused = Bots.tooBig(a, b);
        if (refused != null) return Bots.fail(c.getSource(), refused);
        // One box for all of them: they share it, a block each.
        Clear.Area area = new Clear.Area(c.getSource().getLevel(), a, b);
        Bots.Order by = Bots.order(c.getSource(), them);
        for (Bots.Bot p : them) Bots.clearWith(p, area, by);
        return Bots.told(c, them, "clearing " + area.box() + " (" + Bots.volume(a, b) + " blocks)");
    }

    @Override
    public void tools(Tools tools) {
        tools.add(new Tool("clear", "Break every block in a box, top layer first, with your best tools. Only to clear"
                + " an area someone asked you to clear, dig out or flatten; never to get a material: that is gather.",
                List.of(Tool.param("x1", "integer", "A corner's X"), Tool.param("y1", "integer", "A corner's Y"),
                        Tool.param("z1", "integer", "A corner's Z"), Tool.param("x2", "integer", "The other corner's X"),
                        Tool.param("y2", "integer", "The other corner's Y"), Tool.param("z2", "integer", "The other corner's Z")),
                call -> {
                    JsonObject a = call.args();
                    BlockPos c1 = new BlockPos(a.get("x1").getAsInt(), a.get("y1").getAsInt(), a.get("z1").getAsInt());
                    BlockPos c2 = new BlockPos(a.get("x2").getAsInt(), a.get("y2").getAsInt(), a.get("z2").getAsInt());
                    String refused = Bots.tooBig(c1, c2);
                    if (refused != null) return refused;
                    int placed = PlacedBlocks.count(call.bot().body.serverLevel(), c1, c2);
                    if (placed > 0) {
                        return "not cleared: that box holds " + placed + (placed == 1 ? " block" : " blocks") + " that players"
                                + " placed (someone's build), and you may not break a build. If a person wants it cleared,"
                                + " they can order it themselves: /tachyon clear " + call.bot().name() + " " + Brain.pos(c1)
                                + " " + Brain.pos(c2);
                    }
                    refused = Bots.orderClear(call.bot(), c1, c2, call.order());
                    return refused != null ? refused : "started clearing " + Brain.pos(c1) + " to " + Brain.pos(c2)
                            + "; nothing broken yet, it takes a while";
                }).core());
    }
}
