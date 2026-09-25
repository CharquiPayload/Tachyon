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
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Gathering a material ("get me some dirt", "chop 20 logs", "I need seeds"), ordered by a
 * command or by the brain. The gathering itself, a tick at a time, is the {@link Gather}
 * job: blocks of those kinds in the open broken with the right tool, and what they drop
 * picked up, never a player's build.
 *
 * <p>A block that players get an item from besides the block of that item counts for it: asked
 * for dirt, grass blocks, podzol, mycelium and dirt paths are gathered too, and the dirt is
 * counted; asked for cobblestone, stone; for cobbled deepslate, deepslate ({@link #also}).
 * Seeds come from cutting grass ({@link #seeds}).
 */
final class Gathering implements Ability {

    /** How many when not told, and how many at most from its brain. */
    static final int COUNT = 16, COUNT_MAX = 256;
    /**
     * Blocks that drop another block's item without silk touch, gathered with it when that
     * block is asked for. Made when asked for (the blocks are the game's registry's, which a
     * test without a game does not have).
     */
    static Map<Block, List<Block>> also() {
        return Map.of(Blocks.DIRT, List.of(Blocks.GRASS_BLOCK, Blocks.PODZOL, Blocks.MYCELIUM, Blocks.DIRT_PATH),
                Blocks.COBBLESTONE, List.of(Blocks.STONE),
                Blocks.COBBLED_DEEPSLATE, List.of(Blocks.DEEPSLATE));
    }

    /** What wheat seeds are cut from, and counted as: about one seed in eight for each plant. */
    static Read seeds() {
        return new Read(Set.of(Blocks.SHORT_GRASS, Blocks.TALL_GRASS, Blocks.FERN, Blocks.LARGE_FERN), Items.WHEAT_SEEDS,
                "wheat_seeds", null);
    }

    /** What a gather is for, read from words: the blocks, the item counted, how to say it; or why it is none. */
    record Read(Set<Block> kinds, Item item, String what, String why) {
    }

    /**
     * Blocks from words: ids ({@code oak_log}, {@code minecraft:sand}, a mod's with its name),
     * several separated by commas, in any case and with spaces for underscores, as a model
     * may write them. Ores are refused (looking for them around is the x-ray), and so is
     * what is not a block. {@code item}: what to count, in words, or null.
     */
    static Read read(String blocks, String itemWords) {
        Set<Block> kinds = new LinkedHashSet<>();
        Item item = null;
        if (itemWords != null && !itemWords.isBlank()) {
            item = Gear.item(itemWords);
            if (item == null) return new Read(null, null, null, "there is no item called " + itemWords.trim());
        }
        Block single = null;
        for (String part : blocks.split("[,;]")) {
            String w = part.trim().toLowerCase(Locale.ROOT).replace(' ', '_');
            if (w.isEmpty()) continue;
            ResourceLocation key = ResourceLocation.tryParse(w.contains(":") ? w : "minecraft:" + w);
            Block k = key == null ? null : BuiltInRegistries.BLOCK.getOptional(key).orElse(null);
            if (k == null || k == Blocks.AIR || k == Blocks.CAVE_AIR || k == Blocks.VOID_AIR) {
                return new Read(null, null, null, "there is no block called " + part.trim() + "; blocks are named as"
                        + " Minecraft names them: oak_log, dirt, sand");
            }
            if (Gather.isOre(key.toString())) {
                return new Read(null, null, null, "ores are not gathered: looking for them all around would be seeing"
                        + " through rock; they are mined, as a player finds them");
            }
            kinds.add(k);
            single = kinds.size() == 1 ? k : null;
        }
        if (kinds.isEmpty()) return new Read(null, null, null, "say which block: oak_log, dirt, sand...");
        String what;
        Map<Block, List<Block>> also = also();
        if (single != null && item == null && also.containsKey(single)) {
            // "dirt": the dirt a player gets from grass too, counted as dirt.
            kinds.addAll(also.get(single));
            item = single.asItem();
            what = Gather.name(single);
        } else if (item != null) {
            what = Gear.id(item);
        } else {
            what = String.join("/", kinds.stream().map(Gather::name).toList());
        }
        return new Read(kinds, item, what, null);
    }

    /** It gathers, in place of whatever it did; {@code by} is told when it is over. */
    static void order(Bots.Bot p, Read r, int count, Direction toward, Bots.Order by) {
        Gather g = new Gather(p, r.kinds(), r.item(), count, toward, r.what());
        Bots.orderJob(p, g, g.status(), by);
    }

    // --- the command -------------------------------------------------------------------------

    @Override
    public void commands(LiteralArgumentBuilder<CommandSourceStack> tachyon, CommandBuildContext context) {
        tachyon.then(Commands.literal("gather")
                .then(Bots.who()
                        .then(Commands.literal("seeds")
                                .executes(c -> seeds(c, COUNT))
                                .then(Commands.argument("count", IntegerArgumentType.integer(1, 10_000))
                                        .executes(c -> seeds(c, IntegerArgumentType.getInteger(c, "count")))))
                        .then(Commands.argument("block", ResourceArgument.resource(context, Registries.BLOCK))
                                .executes(c -> gather(c, COUNT, null))
                                .then(Commands.argument("count", IntegerArgumentType.integer(1, 10_000))
                                        .executes(c -> gather(c, IntegerArgumentType.getInteger(c, "count"), null))
                                        .then(Commands.argument("item", ResourceArgument.resource(context, Registries.ITEM))
                                                .executes(c -> gather(c, IntegerArgumentType.getInteger(c, "count"),
                                                        ResourceArgument.getResource(c, "item", Registries.ITEM))))))));
    }

    private static int gather(CommandContext<CommandSourceStack> c, int count, Holder.Reference<Item> item)
            throws CommandSyntaxException {
        List<Bots.Bot> them = Bots.find(c);
        Holder.Reference<Block> block = ResourceArgument.getResource(c, "block", Registries.BLOCK);
        Read r = read(block.key().location().toString(), item == null ? null : item.key().location().toString());
        if (r.why() != null) return Bots.fail(c.getSource(), r.why());
        Bots.Order by = Bots.order(c.getSource(), them);
        for (Bots.Bot p : them) order(p, r, count, null, by);
        return Bots.told(c, them, "gathering " + count + " " + r.what() + " (from " + Gather.names(r.kinds()) + ")");
    }

    private static int seeds(CommandContext<CommandSourceStack> c, int count) throws CommandSyntaxException {
        List<Bots.Bot> them = Bots.find(c);
        Read r = seeds();
        Bots.Order by = Bots.order(c.getSource(), them);
        for (Bots.Bot p : them) order(p, r, count, null, by);
        return Bots.told(c, them, "cutting grass for " + count + " wheat seeds");
    }

    // --- the brain's tools -------------------------------------------------------------------

    @Override
    public void tools(Tools tools) {
        tools.add(new Tool("gather", "Get a material by breaking blocks of that kind around you and picking up what they"
                + " drop, until you have as many as asked: logs for wood, dirt, sand, gravel, cobblestone, flowers,"
                + " leaves. The one tool to get materials: never clear for that. You take only blocks in the open that"
                + " nobody built with; if you see none, you go out looking.",
                List.of(Tool.param("block", "string", "The block, as Minecraft names it: oak_log, dirt, sand...; several"
                                + " separated by commas (oak_log,birch_log). For dirt, dirt (grass blocks count);"
                                + " for cobblestone, cobblestone (stone counts)"),
                        Tool.optional("count", "integer", "How many, 1 to " + COUNT_MAX + "; " + COUNT
                                + " when the person did not say"),
                        Tool.optional("item", "string", "The item to count when it is not the block itself (flint from"
                                + " gravel); the blocks broken are counted when not said"),
                        Tool.optional("toward", "string", "Which way to go looking if you see none: north, south, east"
                                + " or west; the way you face when not said")),
                call -> {
                    JsonObject a = call.args();
                    Read r = read(text(a, "block"), text(a, "item"));
                    if (r.why() != null) return r.why();
                    int count = count(a, COUNT);
                    order(call.bot(), r, count, toward(a), call.order());
                    return "started gathering " + count + " " + r.what() + " (from " + Gather.names(r.kinds())
                            + "); none yet, it takes a while";
                }).core());
        tools.add(new Tool("gather_seeds", "Get wheat seeds by cutting grass around you (about one seed in eight), until"
                + " you have as many as asked.",
                List.of(Tool.optional("count", "integer", "How many seeds, 1 to " + COUNT_MAX + "; " + COUNT
                        + " when the person did not say")),
                call -> {
                    int count = count(call.args(), COUNT);
                    order(call.bot(), seeds(), count, null, call.order());
                    return "started cutting grass for " + count + " wheat seeds; none yet, it takes a while";
                }));
    }

    /** A string the model gave, or "" (it may leave a required one out, or send another type). */
    private static String text(JsonObject a, String key) {
        return a.has(key) && a.get(key).isJsonPrimitive() ? a.get(key).getAsString() : "";
    }

    /** The count the model gave, within 1 to {@link #COUNT_MAX}; {@code byDefault} when none, or not a number. */
    private static int count(JsonObject a, int byDefault) {
        if (!a.has("count") || !a.get("count").isJsonPrimitive()) return byDefault;
        try {
            return Math.max(1, Math.min(COUNT_MAX, a.get("count").getAsInt()));
        } catch (RuntimeException e) {
            return byDefault;
        }
    }

    /** Which way the model said to go looking, or null. */
    private static Direction toward(JsonObject a) {
        Direction d = Direction.byName(text(a, "toward").trim().toLowerCase(Locale.ROOT));
        return d != null && d.getAxis().isHorizontal() ? d : null;
    }
}
