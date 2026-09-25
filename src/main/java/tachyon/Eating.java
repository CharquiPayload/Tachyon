package tachyon;

import com.google.gson.JsonObject;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.logging.LogUtils;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.entity.living.LivingEntityUseItemEvent;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Eating: a player's use of a food item, over its eating time (1.6 s for most, less for
 * dried kelp), on the server, as a player's client asks for it. The food is put in the
 * hand ({@link Gear#toHand}), used ({@code gameMode.useItem}, the server's side of a
 * right click), and the game runs the bite by itself: the hunger and saturation it gives,
 * its effects, the bowl it leaves. It walks at a fifth of its pace meanwhile (BotPlayer).
 * The hands are held for the bite ({@link Bots#holdHands}): a job's {@link Job#hold} would
 * swap the food for a sword, and any change of what is in hand ends a use.
 *
 * <p>What it may eat on its own, and what only when a person asks by name: the food on its
 * banned list ({@link #BANNED}: the golden apples, unless its owner or an operator changes
 * it with {@code /tachyon food}; its brain only reads it), and the food that harms (rotten
 * flesh, spider eyes, raw chicken, pufferfish, a poisonous potato: any whose effects are
 * harmful, a mod's too), a suspicious stew (who knows) and a chorus fruit (it teleports).
 *
 * <p>Eating when hungry, as a reflex, is another ability's: it calls {@link #eatBest} and
 * looks at {@link #eating}.
 */
final class Eating implements Ability {

    private static final Logger LOG = LogUtils.getLogger();

    /** The food a bot does not eat on its own: what nobody wants to see go in a dip of hunger. */
    static final ItemList BANNED = new ItemList("food", List.of("minecraft:golden_apple", "minecraft:enchanted_golden_apple"));
    /** A bite that has not ended this long after its eating time is over is let go of. */
    private static final int SLACK = 10;

    /** A bite under way: what, from what hunger, until when at most, and who holds the hands. */
    private static final class Bite {
        final Item food;
        final int before;
        final long until;
        final Ability by;
        /** The eat tool's answer, once the bite is over; null for a reflex's bite. */
        final CompletableFuture<String> told;
        /** The game said it was eaten (its use finished). */
        boolean done;

        Bite(Item food, int before, long until, Ability by, CompletableFuture<String> told) {
            this.food = food;
            this.before = before;
            this.until = until;
            this.by = by;
            this.told = told;
        }
    }

    /**
     * The bites under way, by bot: a few at a time at most. Looked at every tick of every
     * bot, and empty nearly always: a reflex that costs nothing when nobody eats.
     */
    private static final Map<Bots.Bot, Bite> BITES = new IdentityHashMap<>();

    // --- the bite -------------------------------------------------------------------------

    /** Whether it is eating right now. */
    static boolean eating(Bots.Bot p) {
        return !BITES.isEmpty() && BITES.containsKey(p);
    }

    /**
     * It starts eating the best food it may eat on its own ({@link #bestFood}), its hands
     * held by {@code by} for the bite: the primitive for a reflex that eats when hungry. The
     * bite goes on by itself; {@link #eating} says when it is over. On the server's thread.
     *
     * @return null once it is eating; else why not, in words ("I carry nothing I eat on my
     *         own", "my hunger is full", "my hands are busy (...)")
     */
    static String eatBest(Bots.Bot p, Ability by) {
        int slot = bestFood(p);
        if (slot < 0) return "I carry nothing I eat on my own" + banned(p);
        return start(p, slot, by, null);
    }

    /**
     * The food in {@code slot} into the hand and used, the hands held by {@code by} for its
     * eating time. {@code told}, if any, is completed with how it went once the bite is over.
     *
     * @return null once it is eating; else why not
     */
    private static String start(Bots.Bot p, int slot, Ability by, CompletableFuture<String> told) {
        BotPlayer b = p.body;
        if (!b.isAlive()) return "I am dead";
        if (eating(p)) return "I am already eating";
        ItemStack food = b.getInventory().getItem(slot);
        FoodProperties f = food.getFoodProperties(b);
        if (f == null) return Gear.id(food) + " is not food";
        if (!b.canEat(f.canAlwaysEat())) {
            return "my hunger is full (" + b.getFoodData().getFoodLevel() + "/20): eating now would do nothing";
        }
        int ticks = food.getUseDuration(b);
        if (!Bots.holdHands(p, by, ticks + SLACK)) return "my hands are busy right now (" + Wielding.busy(p) + ")";
        if (b.isUsingItem()) b.stopUsingItem();          // a bow drawn is let down, as a player's click would
        Gear.toHand(p, slot);
        ItemStack held = b.getMainHandItem();
        b.gameMode.useItem(b, b.level(), held, InteractionHand.MAIN_HAND);
        if (!b.isUsingItem()) {
            Bots.freeHands(p, by);
            return "the game did not let me eat " + Gear.id(held);
        }
        long now = b.getServer().getTickCount();
        BITES.put(p, new Bite(held.getItem(), b.getFoodData().getFoodLevel(), now + ticks + SLACK, by, told));
        return null;
    }

    /**
     * A bite looked at, every tick: over once the game ended its use (eaten, or cut short:
     * what was in hand changed) or its time ran out. Then its hands are let go of, and the
     * eat tool, if it asked, is told how it went.
     */
    @Override
    public void tick(Bots.Bot p, long now) {
        if (BITES.isEmpty()) return;
        Bite bite = BITES.get(p);
        if (bite == null || (p.body.isUsingItem() && now < bite.until)) return;
        end(p, bite, null);
    }

    /** A bite over: {@code why} it was cut short, or null to say it from what happened. */
    private static void end(Bots.Bot p, Bite bite, String why) {
        BITES.remove(p);
        Bots.freeHands(p, bite.by);
        BotPlayer b = p.body;
        if (b.isUsingItem() && b.getUseItem().is(bite.food)) b.stopUsingItem();
        int after = b.getFoodData().getFoodLevel();
        String how;
        if (why != null) {
            how = "I did not finish eating " + Gear.id(bite.food) + ": " + why;
        } else if (bite.done) {
            how = String.format(Locale.ROOT, "I ate %s: food %d -> %d/20, health %d/20", Gear.id(bite.food),
                    bite.before, after, Math.round(b.getHealth()));
        } else {
            ItemStack hand = b.getMainHandItem();
            how = "I did not finish eating " + Gear.id(bite.food) + ": "
                    + (hand.is(bite.food) ? "the bite was cut short" : "something else was put in my hand (" + (hand.isEmpty() ? "nothing" : Gear.id(hand)) + ")")
                    + "; food still " + after + "/20";
        }
        Notices.technical(LOG, p, p.name() + ": " + how);
        if (bite.told != null) bite.told.complete(how);
    }

    @Override
    public void events(IEventBus bus) {
        // The game's word that a use was over as it should be: the food was eaten.
        bus.addListener(LivingEntityUseItemEvent.Finish.class, e -> {
            Bots.Bot p = Bots.of(e.getEntity());
            if (p == null || BITES.isEmpty()) return;
            Bite bite = BITES.get(p);
            if (bite != null && e.getItem().is(bite.food)) bite.done = true;
        });
    }

    @Override
    public void died(Bots.Bot p, net.minecraft.world.damagesource.DamageSource cause) {
        Bite bite = BITES.get(p);
        if (bite != null) end(p, bite, "I died");
    }

    @Override
    public void left(Bots.Bot p) {
        Bite bite = BITES.get(p);
        if (bite != null) end(p, bite, "I left the game");
    }

    // --- what it eats --------------------------------------------------------------------------

    /**
     * Food it may eat on its own: food that is not on its banned list, harms nobody (no
     * harmful effect: rotten flesh, spider eyes, raw chicken, pufferfish, poisonous potatoes,
     * a mod's poisons too), and is no gamble (a suspicious stew's effect is unknown; a chorus
     * fruit teleports whoever eats it).
     */
    static boolean ownChoice(Bots.Bot p, ItemStack s) {
        FoodProperties f = s.getFoodProperties(p.body);
        if (f == null || s.is(Items.CHORUS_FRUIT) || s.has(DataComponents.SUSPICIOUS_STEW_EFFECTS)) return false;
        for (FoodProperties.PossibleEffect e : f.effects()) {
            if (e.effect().getEffect().value().getCategory() == MobEffectCategory.HARMFUL) return false;
        }
        return !BANNED.has(p.data, BuiltInRegistries.ITEM.getKey(s.getItem()).toString());
    }

    /**
     * The slot of the best food it may eat on its own, or -1 when it has none: the one that
     * fills most of the hunger it lacks, then the one with most saturation (the one that
     * keeps it fed, and healing, longest). Its 36 slots: what is in the backpack is brought
     * up (a Masurium bot once sat at hunger 5 with twelve porkchops in its backpack).
     */
    static int bestFood(Bots.Bot p) {
        BotPlayer b = p.body;
        Inventory inv = b.getInventory();
        int missing = 20 - b.getFoodData().getFoodLevel();
        int best = -1;
        double bestFill = -1, bestSat = -1;
        for (int i = 0; i < Gear.SLOTS; i++) {
            ItemStack s = inv.getItem(i);
            if (s.isEmpty() || !ownChoice(p, s)) continue;
            FoodProperties f = s.getFoodProperties(b);
            double fill = Math.min(f.nutrition(), Math.max(missing, 1));
            if (fill > bestFill || fill == bestFill && f.saturation() > bestSat) {
                best = i;
                bestFill = fill;
                bestSat = f.saturation();
            }
        }
        return best;
    }

    /** ", but I carry X, which is on my banned list", when it does: words for why it did not eat. */
    private static String banned(Bots.Bot p) {
        Inventory inv = p.body.getInventory();
        List<String> kept = new ArrayList<>();
        for (int i = 0; i < Gear.SLOTS; i++) {
            ItemStack s = inv.getItem(i);
            if (s.isEmpty() || s.getFoodProperties(p.body) == null) continue;
            String id = Gear.id(s);
            if (!kept.contains(id)) kept.add(id);
        }
        return kept.isEmpty() ? "" : " (I carry " + String.join(", ", kept)
                + ", which I eat only if a person asks me for it by name)";
    }

    // --- the brain's tools ----------------------------------------------------------------------

    @Override
    public void tools(Tools tools) {
        tools.add(Tool.later("eat", "Eat something you carry, to fill your hunger; with your hunger full your health"
                        + " comes back by itself. Without saying what, you choose, never food on your banned list or food"
                        + " that poisons. Banned food only if the person speaking to you asked for it by name, and then"
                        + " with who=<their name>.",
                List.of(Tool.optional("what", "string", "The food's id in English (cooked_beef); leave it out to choose"),
                        Tool.optional("who", "string", "Only for banned food: the exact name of the person who asked you to eat it")),
                call -> eatTool(call, this)));
        tools.add(new Tool("food_ban", "The food you do not eat on your own (your banned list). You cannot change it:"
                + " your owner or an operator does, with /tachyon food.", List.of(),
                call -> {
                    List<String> ids = BANNED.ids(call.bot().data);
                    if (ids.isEmpty()) return "I have no banned food";
                    List<String> names = new ArrayList<>();
                    for (String id : ids) names.add(shortId(id));
                    return "I do not eat on my own: " + String.join(", ", names)
                            + ". If a person asks me for one by name, I eat it. My owner or an operator changes the list"
                            + " with /tachyon food " + call.bot().name() + " ban|allow <food>.";
                }));
    }

    /**
     * The eat tool: started here, answered once the bite is over (about 2 s), or at once when
     * it cannot eat. {@code self} holds the hands for the bite.
     */
    private static CompletableFuture<String> eatTool(Tool.Call call, Eating self) {
        Bots.Bot p = call.bot();
        BotPlayer b = p.body;
        JsonObject a = call.args();
        String what = a.has("what") && a.get("what").isJsonPrimitive() ? a.get("what").getAsString().trim() : "";
        CompletableFuture<String> told = new CompletableFuture<>();
        int slot;
        if (what.isEmpty()) {
            if (!b.getFoodData().needsFood()) return CompletableFuture.completedFuture("my hunger is full (20/20): eating now would do nothing");
            slot = bestFood(p);
            if (slot < 0) return CompletableFuture.completedFuture("I carry nothing I eat on my own" + banned(p));
        } else {
            Item item = Gear.item(what);
            if (item == null) return CompletableFuture.completedFuture("there is no item called " + what);
            slot = Gear.find(b.getInventory(), item);
            if (slot < 0) return CompletableFuture.completedFuture("I do not carry " + Gear.id(item));
            String id = BuiltInRegistries.ITEM.getKey(item).toString();
            if (BANNED.has(p.data, id)) {
                // Asked by name, and by a person who really spoke this turn: its brain alone,
                // or a notice, does not get to eat what is kept aside.
                String who = a.has("who") && a.get("who").isJsonPrimitive() ? a.get("who").getAsString().trim() : "";
                boolean asked = call.words() != null && !who.isEmpty() && who.equalsIgnoreCase(call.speakerName());
                if (!asked) {
                    return CompletableFuture.completedFuture(Gear.id(item) + " is on my banned food list: I do not eat it on my"
                            + " own. If the person who spoke to you now asked you to eat it by name, call eat again with"
                            + " who=<their name>; if not, say you are hungry and carry only banned food.");
                }
            }
        }
        String refused = start(p, slot, self, told);
        return refused != null ? CompletableFuture.completedFuture(refused) : told;
    }

    // --- /tachyon food --------------------------------------------------------------------------

    /**
     * {@code /tachyon food <who> [ban|allow|default <item>]}: its banned food, listed or
     * changed, by its owner or an operator (its brain only reads it). {@code default} undoes
     * the bot's own change for that food.
     */
    @Override
    public void commands(LiteralArgumentBuilder<CommandSourceStack> tachyon, CommandBuildContext context) {
        tachyon.then(Commands.literal("food")
                .then(Bots.who()
                        .executes(Eating::list)
                        .then(change("ban"))
                        .then(change("allow"))
                        .then(change("default"))));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> change(String how) {
        return Commands.literal(how).then(Commands.argument("food", ResourceLocationArgument.id())
                .suggests((c, sb) -> SharedSuggestionProvider.suggestResource(BuiltInRegistries.ITEM.stream()
                        .filter(i -> i.components().has(DataComponents.FOOD)).map(BuiltInRegistries.ITEM::getKey), sb))
                .executes(c -> change(c, how)));
    }

    private static int list(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        List<Bots.Bot> them = Bots.find(c);
        List<String> lines = new ArrayList<>();
        for (Bots.Bot p : them) {
            List<String> names = new ArrayList<>();
            for (String id : BANNED.ids(p.data)) names.add(shortId(id));
            lines.add(p.name() + " does not eat on its own: " + (names.isEmpty() ? "nothing banned" : String.join(", ", names)));
        }
        return them.isEmpty() ? 0 : Bots.say(c.getSource(), String.join("\n", lines));
    }

    private static int change(CommandContext<CommandSourceStack> c, String how) throws CommandSyntaxException {
        List<Bots.Bot> them = Bots.find(c);
        if (them.isEmpty()) return 0;
        ResourceLocation key = ResourceLocationArgument.getId(c, "food");
        Item item = BuiltInRegistries.ITEM.getOptional(key).orElse(null);
        if (item == null || item == Items.AIR) return Bots.fail(c.getSource(), "there is no item called " + key);
        if (!item.components().has(DataComponents.FOOD)) return Bots.fail(c.getSource(), Gear.id(item) + " is not food");
        String id = key.toString();
        for (Bots.Bot p : them) {
            switch (how) {
                case "ban" -> BANNED.add(p.data, id);
                case "allow" -> BANNED.remove(p.data, id);
                default -> BANNED.reset(p.data, id);
            }
        }
        String now = BANNED.has(them.get(0).data, id) ? "does not eat " + Gear.id(item) + " on its own"
                : "may eat " + Gear.id(item) + " on its own";
        return Bots.told(c, them, now);
    }

    /** A full id as players say it: {@code golden_apple}, a mod's with its name. */
    private static String shortId(String id) {
        return id.startsWith("minecraft:") ? id.substring("minecraft:".length()) : id;
    }
}
