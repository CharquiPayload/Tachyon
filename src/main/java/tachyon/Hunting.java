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
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.Locale;

/**
 * Hunting kinds of mob, ordered by a command or by the brain. The hunt itself, a tick at a
 * time, is the {@link Hunt} job.
 *
 * <p>A command hunts as many as it says, or, without a count, every one the bot finds. Its
 * brain hunts 8 at most, 8 when it is not told how many (Masurium's rule: "hunt cows" is
 * not every cow), or with 0 every one it finds; it may name several kinds, and which way to
 * go looking when none is in sight. Players are never hunted: a kill goes after one, by
 * name, when the operators allow it ({@link Fighting}).
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

    /** @param count how many each is to kill; 0: every one it finds */
    private static int hunt(CommandContext<CommandSourceStack> c, int count) throws CommandSyntaxException {
        List<Bots.Bot> them = Bots.find(c);
        Holder.Reference<EntityType<?>> mob = ResourceArgument.getEntityType(c, "mob");
        String name = mob.key().location().getPath();
        if (mob.value() == EntityType.PLAYER) return Bots.fail(c.getSource(), "players are never prey");
        if (mob.value() == EntityType.CREEPER) return Bots.fail(c.getSource(), "creepers are not hunted by sword: they blow up; kill shoots them with a bow");
        Bots.Order by = Bots.order(c.getSource(), them);
        for (Bots.Bot p : them) Bots.orderHunt(p, new Hunt(Prey.of(mob.value()), count, false, null), by);
        return Bots.told(c, them, "hunting " + name + (count > 0 ? ", " + count + " each" : ", every one it finds"));
    }

    @Override
    public void tools(Tools tools) {
        tools.add(new Tool("hunt", "Hunt mobs with your best weapon and pick up what they drop. If you see none, you go"
                + " out looking for them.",
                // 0.1.0 sent "mob" as optional, its description cut short at "names it": its
                // tools were written as "name:type:description[:optional]", and the colon
                // in the description split it there. Mended: required, since a hunt with
                // no mob is none, and whole. Then it took Masurium's: several kinds, 8 at
                // most, and which way to go looking.
                List.of(Tool.param("mob", "string", "The mob, as Minecraft names it: cow, pig, sheep, chicken, zombie...;"
                                + " several separated by commas (cow,pig) for the nearest of any"),
                        Tool.optional("count", "integer", "How many to kill, 1 to 8; 8 when the person did not say;"
                                + " 0 for every one you find, until told to stop"),
                        Tool.optional("toward", "string", "Which way to go looking if you see none: north, south, east"
                                + " or west; the way you face when not said")),
                call -> {
                    JsonObject a = call.args();
                    Bots.Bot p = call.bot();
                    BotPlayer b = p.body;
                    // Required, and still a model may leave it out.
                    String words = a.has("mob") && a.get("mob").isJsonPrimitive() ? a.get("mob").getAsString() : "";
                    Prey.Read read = Prey.read(words, b.getServer(), false);
                    if (read.prey() == null) return read.why();
                    Prey prey = read.prey();
                    if (prey.kinds().contains(EntityType.CREEPER)) {
                        return "creepers are not hunted by sword: they blow up. kill shoots them with a bow, from afar";
                    }
                    int count = Hunt.COUNT_MAX;
                    if (a.has("count") && a.get("count").isJsonPrimitive()) {
                        try {
                            count = Math.max(0, Math.min(Hunt.COUNT_MAX, a.get("count").getAsInt()));
                        } catch (RuntimeException e) {
                            // Not a number: as if not said.
                        }
                    }
                    Direction toward = null;
                    if (a.has("toward") && a.get("toward").isJsonPrimitive()) {
                        Direction d = Direction.byName(a.get("toward").getAsString().trim().toLowerCase(Locale.ROOT));
                        if (d != null && d.getAxis().isHorizontal()) toward = d;
                    }
                    Bots.orderHunt(p, new Hunt(prey, count, false, toward), call.order());
                    ItemStack weapon = Gear.bestWeapon(b.getInventory());
                    return "started hunting " + prey.words() + (count > 0 ? ", " + count : ", every one I find") + ", "
                            + (weapon.isEmpty() ? "bare-handed (no weapon)" : "with " + Gear.id(weapon))
                            + "; none killed yet, it takes a while";
                }).core());
    }
}
