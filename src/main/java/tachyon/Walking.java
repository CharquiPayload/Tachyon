package tachyon;

import com.google.gson.JsonObject;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;

/**
 * Walking: to a place, after a player, and standing still, ordered by a command or by
 * the brain. The legs themselves (the searches, the keys, the doors) are Bots': this is
 * what gives them somewhere to go, and the setting that says whether they may run.
 */
final class Walking implements Ability {

    /** Whether it may sprint when walking; read where Bots decides to (canRun). */
    static final String SPRINT = "sprint";

    @Override
    public void commands(LiteralArgumentBuilder<CommandSourceStack> tachyon, CommandBuildContext context) {
        tachyon.then(Commands.literal("goto")
                        .then(Bots.who()
                                .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                        .executes(Walking::goTo))))
                .then(Commands.literal("follow")
                        .then(Bots.who()
                                .then(Commands.argument("player", EntityArgument.player())
                                        .executes(Walking::follow))))
                .then(Commands.literal("stop")
                        .then(Bots.who().executes(Walking::stop)));
    }

    private static int goTo(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        List<Bots.Bot> them = Bots.find(c);
        BlockPos to = BlockPosArgument.getBlockPos(c, "pos");
        Bots.Order by = Bots.order(c.getSource(), them);
        for (Bots.Bot p : them) Bots.orderGoto(p, to, by);
        return Bots.told(c, them, "searching a way to " + to.toShortString());
    }

    private static int follow(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        ServerPlayer leader = EntityArgument.getPlayer(c, "player");
        String name = leader.getGameProfile().getName();
        // A bot the pattern also takes in does not follow itself.
        List<Bots.Bot> them = Bots.find(c).stream().filter(p -> p.body != leader).toList();
        Bots.Order by = Bots.order(c.getSource(), them);
        for (Bots.Bot p : them) Bots.orderFollow(p, leader, by);
        return Bots.told(c, them, "following " + name);
    }

    private static int stop(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        List<Bots.Bot> them = Bots.find(c);
        for (Bots.Bot p : them) Bots.orderStop(p);
        return Bots.told(c, them, "standing still");
    }

    @Override
    public void tools(Tools tools) {
        tools.add(new Tool("come_here", "Walk to the player who is speaking to you, once.", List.of(), call -> {
            ServerPlayer speaker = call.speaker();
            if (speaker == null) return "nobody to go to: the one speaking is not in the game";
            Bots.orderGoto(call.bot(), speaker.blockPosition(), call.order());
            return "started walking to " + call.speakerName() + ", " + Math.round(speaker.distanceTo(call.bot().body))
                    + " blocks away; not there yet";
        }).core());
        tools.add(new Tool("follow", "Keep walking after a player until told to stop.",
                List.of(Tool.optional("player", "string", "Their name; leave it out for the one speaking to you")),
                call -> {
                    JsonObject a = call.args();
                    String who = a.has("player") ? a.get("player").getAsString() : call.speakerName();
                    ServerPlayer leader = call.bot().body.getServer().getPlayerList().getPlayerByName(who);
                    if (leader == null) return "no player " + who + " in the game";
                    if (leader == call.bot().body) return "you cannot follow yourself";
                    Bots.orderFollow(call.bot(), leader, call.order());
                    return "following " + who;
                }).core());
        tools.add(new Tool("go_to", "Walk to a place.",
                List.of(Tool.param("x", "integer", "X"), Tool.param("y", "integer", "Y"), Tool.param("z", "integer", "Z")),
                call -> {
                    JsonObject a = call.args();
                    BlockPos to = new BlockPos(a.get("x").getAsInt(), a.get("y").getAsInt(), a.get("z").getAsInt());
                    Bots.orderGoto(call.bot(), to, call.order());
                    return "started walking to " + Brain.pos(to) + ", "
                            + Math.round(Math.sqrt(to.distToCenterSqr(call.bot().body.position())))
                            + " blocks away; not there yet";
                }).core());
        tools.add(new Tool("stop", "Stop what you are doing and stand still.", List.of(), call -> {
            Bots.orderStop(call.bot());
            return "standing still";
        }).core());
    }

    @Override
    public void settings(Settings settings) {
        settings.bool(SPRINT, true, "whether it may sprint when walking", Settings.Who.OWNER)
                .label("Sprint when walking").group("Walking").basic();
    }
}
