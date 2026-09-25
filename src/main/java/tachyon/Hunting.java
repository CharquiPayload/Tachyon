package tachyon;

import com.google.gson.JsonObject;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.ResourceArgument;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;

import java.util.List;
import java.util.Locale;

/**
 * Hunting a kind of mob, ordered by a command or by the brain. The hunt itself, a tick
 * at a time, is the {@link Hunt} job.
 */
final class Hunting implements Ability {

    @Override
    public void commands(LiteralArgumentBuilder<CommandSourceStack> tachyon, CommandBuildContext context) {
        tachyon.then(Commands.literal("hunt")
                .then(Bots.who()
                        .then(Commands.argument("mob", ResourceArgument.resource(context, Registries.ENTITY_TYPE))
                                .executes(c -> hunt(c, 0))
                                .then(Commands.argument("count", IntegerArgumentType.integer(1, 10_000))
                                        .executes(c -> hunt(c, IntegerArgumentType.getInteger(c, "count")))))));
    }

    /** @param count how many each is to kill; 0: every one around */
    private static int hunt(CommandContext<CommandSourceStack> c, int count) throws CommandSyntaxException {
        List<Bots.Bot> them = Bots.find(c);
        Holder.Reference<EntityType<?>> mob = ResourceArgument.getEntityType(c, "mob");
        String name = mob.key().location().getPath();
        if (mob.value() == EntityType.PLAYER) return Bots.fail(c.getSource(), "players are never prey");
        Bots.Order by = Bots.order(c.getSource(), them);
        for (Bots.Bot p : them) Bots.orderHunt(p, mob.value(), name, count, by);
        return Bots.told(c, them, "hunting " + name + (count > 0 ? ", " + count + " each" : ", every one around"));
    }

    @Override
    public void tools(Tools tools) {
        tools.add(new Tool("hunt", "Hunt a kind of mob with your best weapon and pick up what it drops.",
                // 0.1.0 sent "mob" as optional, its description cut short at "names it": its
                // tools were written as "name:type:description[:optional]", and the colon
                // in the description split it there. Mended: required, since a hunt with
                // no mob is none, and whole.
                List.of(Tool.param("mob", "string", "The mob, as Minecraft names it: cow, pig, sheep, chicken, zombie..."),
                        Tool.optional("count", "integer", "How many to kill; 0 for every one around")),
                call -> {
                    JsonObject a = call.args();
                    BotPlayer b = call.bot().body;
                    // Required, and still a model may leave it out.
                    if (!a.has("mob") || !a.get("mob").isJsonPrimitive()) return "no mob was named: which one? (cow, zombie...)";
                    String mob = a.get("mob").getAsString().toLowerCase(Locale.ROOT).trim().replace(' ', '_');
                    ResourceLocation id = ResourceLocation.tryParse(mob.contains(":") ? mob : "minecraft:" + mob);
                    EntityType<?> type = id == null ? null : BuiltInRegistries.ENTITY_TYPE.getOptional(id).orElse(null);
                    if (type == null) return "there is no mob called " + mob;
                    if (type == EntityType.PLAYER) return "you never hunt players";
                    int count = a.has("count") ? Math.max(0, a.get("count").getAsInt()) : 0;
                    Bots.orderHunt(call.bot(), type, id.getPath(), count, call.order());
                    return "started hunting " + id.getPath() + (count > 0 ? ", " + count : ", every one around") + ", "
                            + (Hunt.damage(b.getMainHandItem()) > 0 ? "with " + Brain.item(b.getMainHandItem()) : "bare-handed (no weapon)")
                            + "; none killed yet, it takes a while";
                }).core());
    }
}
