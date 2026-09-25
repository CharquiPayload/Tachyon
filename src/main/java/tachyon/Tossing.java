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
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.util.TriState;
import net.neoforged.neoforge.event.entity.player.ItemEntityPickupEvent;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Tossing things: what its brain asks it to toss (to the ground, or to someone), and its
 * trash, what it tosses by itself. A toss is a player's throw from its inventory
 * ({@code Player.drop}), so the item carries its thrower, flies where it looks, and lies
 * 2 s before anyone can take it and 5 minutes before it is gone.
 *
 * <p><b>It never picks up again what it tossed itself</b>, for the item's whole life:
 * anyone else, player or bot, picks it up as usual, since that is what it was tossed for.
 * A Masurium bot once tossed 768 cobblestone as asked, walked past the pile, and carried 704
 * again three minutes later; only the server can refuse a pickup, and it does here
 * ({@link ItemEntityPickupEvent.Pre}). Nor does a bot pick up what another bot tossed as its
 * trash ({@link #TOSSED_AS_TRASH}), if it is trash to it too: bots working together would
 * pass their trash back and forth, each toss a new item with five fresh minutes to lie
 * there, and it would never go. (What a bot tosses to someone on purpose, anyone takes.)
 * What a bot drops as it dies has no thrower, so it does get that back.
 *
 * <p><b>Its trash</b> ({@link #TRASH}: cobblestone, cobbled deepslate, tuff, granite,
 * diorite, andesite, dirt and gravel to start with, which its brain may change, as
 * Masurium's could, since it concerns only what it carries) is tossed when its backpack is
 * full, and then only: surplus stone is surplus only when it is in the way. With
 * {@code trash_at_once}, as soon as it picks it up. Either way it keeps one stack of each
 * trash block it can build with (it plugs gaps and crosses ravines with it): the one in its
 * hand, else the biggest; and never what it is using right now. The check is made on a
 * pickup, the only way a backpack fills while it works, so it costs nothing while nothing
 * comes in. What it tossed, and a backpack full with nothing to toss, its brain hears (a
 * paid call, once in 10 minutes at most, with its tools: it may toss something); its
 * {@code notices} setting has the last word: {@code plain}, a line to its owner instead,
 * with no call; {@code off}, nothing.
 */
final class Tossing implements Ability {

    private static final Logger LOG = LogUtils.getLogger();

    /** What it tosses as trash, to start with: what fills a backpack in any mine. */
    static final ItemList TRASH = new ItemList("trash", List.of("minecraft:cobblestone", "minecraft:cobbled_deepslate",
            "minecraft:tuff", "minecraft:granite", "minecraft:diorite", "minecraft:andesite", "minecraft:dirt",
            "minecraft:gravel"));
    /** Whether it tosses trash as soon as it picks it up, and not only when its backpack is full. */
    static final String AT_ONCE = "trash_at_once";
    /** Its brain is told of a full backpack at most this often (every notice is a paid call to a model). */
    private static final long NOTICE_MS = 10 * 60_000L;
    /** A person it tosses to: this far at most, as far as a player makes out someone to throw to. */
    private static final double SEE = 64;
    /** The tag on what a bot tossed as its trash (kept with the item, as a command's tags are). */
    static final String TOSSED_AS_TRASH = "tachyon_trash";

    /**
     * The bots that picked something up, or touched something they had no room for, since
     * their last look: their backpack is looked at on their next tick. Empty nearly always.
     */
    private static final Set<Bots.Bot> PICKED = new LinkedHashSet<>();

    /**
     * A bot's full backpack, as its brain was told of it (when, and whether it still is), and
     * whether it was full with nothing to toss at its last look, and when that was.
     */
    private static final class Full {
        long toldAt = -NOTICE_MS;
        boolean told;
        boolean stuck;
        long lookedAt;
    }

    @Override
    public void settings(Settings settings) {
        settings.bool(AT_ONCE, false, "whether it tosses its trash as soon as it picks it up; false: only when its backpack"
                + " is full", Settings.Who.OWNER).label("Toss trash at once").group("Gear").advanced();
    }

    // --- picking up ---------------------------------------------------------------------------

    @Override
    public void events(IEventBus bus) {
        bus.addListener(ItemEntityPickupEvent.Pre.class, e -> {
            Bots.Bot p = Bots.of(e.getPlayer());
            if (p == null) return;
            // What it tossed itself stays on the ground for it: never picked up again. Nor
            // what another bot tossed as trash that is its trash too: it would only toss it again.
            Entity thrower = e.getItemEntity().getOwner();
            if (thrower != null && thrower.getUUID().equals(e.getPlayer().getUUID())
                    || e.getItemEntity().getTags().contains(TOSSED_AS_TRASH) && trash(p, e.getItemEntity().getItem())) {
                e.setCanPickup(TriState.FALSE);
                return;
            }
            // No room for it: a full backpack, looked at on its next tick (trash tossed, or
            // its brain told). Touching an item happens every tick it stands on it: a mark only.
            if (!e.getItemEntity().hasPickUpDelay() && !Gear.fits(p.body.getInventory(), e.getItemEntity().getItem())) {
                PICKED.add(p);
            }
        });
        bus.addListener(ItemEntityPickupEvent.Post.class, e -> {
            Bots.Bot p = Bots.of(e.getPlayer());
            if (p != null) PICKED.add(p);
        });
    }

    /**
     * After a pickup: its trash tossed, if its backpack is full or its setting says at once;
     * and its brain told when it is full with nothing to toss. Only for a bot that picked
     * something up since its last look.
     */
    @Override
    public void tick(Bots.Bot p, long now) {
        if (PICKED.isEmpty() || !PICKED.remove(p)) return;
        BotPlayer b = p.body;
        if (!b.isAlive()) return;
        Full f = p.slot(Full.class, Full::new);
        // Full with nothing to toss, it stands on what it cannot take and touches it every
        // tick: looked at again once a second at most.
        if (f.stuck && now - f.lookedAt < 20) return;
        f.lookedAt = now;
        Inventory inv = b.getInventory();
        boolean full = inv.getFreeSlot() < 0;
        if (!full && !Settings.bool(p, AT_ONCE)) {
            f.told = false;
            f.stuck = false;
            return;
        }
        Map<String, Integer> tossed = tossTrash(p);
        full = inv.getFreeSlot() < 0;
        f.stuck = full && tossed.isEmpty();
        if (!full) f.told = false;
        if (tossed.isEmpty() && !full) return;
        String what = words(tossed);
        if (!tossed.isEmpty()) LOG.info("[tachyon] {} tossed its trash: {}", p.name(), what);
        long ms = System.currentTimeMillis();
        if (Settings.bool(p, AT_ONCE) && !full) return;       // tossing as it comes in is what it was told to do
        if (f.told || ms - f.toldAt < NOTICE_MS) return;
        f.told = true;
        f.toldAt = ms;
        String how = Settings.choice(p, Notices.NOTICES);
        if (how.equals(Notices.OFF)) return;
        boolean brain = how.equals(Notices.BRAIN) && !Brain.config().url(p.name()).isEmpty();
        if (!tossed.isEmpty() && !full) {
            if (brain) {
                Bots.brain(p).notice("Your backpack was full: you tossed " + what + " (your trash list) to make room."
                        + " Tell your owner only if it matters to them.");
            } else {
                Notices.say(p, "backpack", "its backpack was full: it tossed " + what + " (its trash) to make room");
            }
        } else if (brain) {
            Bots.brain(p).notice("Your backpack is full (36 of 36 slots) and nothing in it is on your trash list: what you"
                    + " walk over stays on the ground. If your owner wants, toss something (toss) or add to your trash list"
                    + " (trash); tell them only if it matters.");
        } else {
            Notices.say(p, "backpack", "its backpack is full (36 of 36 slots) and nothing in it is its trash: what it walks"
                    + " over stays on the ground");
        }
    }

    /**
     * Every stack of its trash tossed but, of a trash block it can build with, one: the stack
     * in its hand if it holds that block (it may be building with it), else the biggest. Never
     * what it is using right now (a bite). On the server's thread.
     *
     * @return how many of what it tossed, by id; empty when nothing
     */
    static Map<String, Integer> tossTrash(Bots.Bot p) {
        BotPlayer b = p.body;
        Inventory inv = b.getInventory();
        Set<String> trash = new LinkedHashSet<>(TRASH.ids(p.data));
        Map<Item, List<Integer>> slots = new LinkedHashMap<>();
        for (int i = 0; i < Gear.SLOTS; i++) {
            ItemStack s = inv.getItem(i);
            if (s.isEmpty() || i == inv.selected && b.isUsingItem()) continue;
            if (trash.contains(BuiltInRegistries.ITEM.getKey(s.getItem()).toString())) {
                slots.computeIfAbsent(s.getItem(), k -> new ArrayList<>()).add(i);
            }
        }
        Map<String, Integer> tossed = new LinkedHashMap<>();
        for (Map.Entry<Item, List<Integer>> e : slots.entrySet()) {
            int keep = -1;
            if (scaffold(inv.getItem(e.getValue().get(0)))) {
                for (int i : e.getValue()) {
                    if (keep < 0 || inv.getItem(i).getCount() > inv.getItem(keep).getCount()) keep = i;
                }
                if (e.getValue().contains(inv.selected)) keep = inv.selected;
            }
            for (int i : e.getValue()) {
                if (i == keep) continue;
                ItemStack s = inv.removeItemNoUpdate(i);
                int n = s.getCount();
                ItemEntity thrown = b.drop(s, true);
                if (thrown == null) continue;
                thrown.addTag(TOSSED_AS_TRASH);
                tossed.merge(Gear.id(e.getKey()), n, Integer::sum);
            }
        }
        return tossed;
    }

    /**
     * A block it can build its way with: a full, solid cube that does not fall (gravel and
     * sand do), as Masurium's scaffolding was (dirt, stones, planks).
     */
    static boolean scaffold(ItemStack s) {
        if (!(s.getItem() instanceof BlockItem bi) || bi.getBlock() instanceof FallingBlock) return false;
        BlockState state = bi.getBlock().defaultBlockState();
        if (s.is(ItemTags.PLANKS) || s.is(ItemTags.DIRT)) return true;
        return state.isCollisionShapeFullBlock(EmptyBlockGetter.INSTANCE, BlockPos.ZERO)
                && (state.is(BlockTags.BASE_STONE_OVERWORLD) || state.is(BlockTags.BASE_STONE_NETHER)
                || s.is(Items.COBBLESTONE) || s.is(Items.COBBLED_DEEPSLATE));
    }

    /** "3 stacks of cobblestone (192), dirt (12)": what was tossed, in words. */
    private static String words(Map<String, Integer> tossed) {
        List<String> out = new ArrayList<>();
        tossed.forEach((id, n) -> out.add(n + " " + id));
        return String.join(", ", out);
    }

    /** Whether an item is its trash: a pickup a job does not walk to (Hunt's loot). */
    static boolean trash(Bots.Bot p, ItemStack s) {
        return !s.isEmpty() && TRASH.has(p.data, BuiltInRegistries.ITEM.getKey(s.getItem()).toString());
    }

    @Override
    public void left(Bots.Bot p) {
        PICKED.remove(p);
    }

    // --- the brain's tools ----------------------------------------------------------------------

    @Override
    public void tools(Tools tools) {
        tools.add(new Tool("toss", "Toss things on the ground, or to someone: you turn to them first. Without a count,"
                + " all you carry of it. You say how many really went. What you wear is not tossed (remove_armor"
                + " first). Tossed things vanish after 5 minutes, and you never pick up again what you tossed yourself.",
                List.of(Tool.param("what", "string", "The item's id in English (cobblestone)"),
                        Tool.optional("count", "integer", "How many; leave it out for all of it"),
                        Tool.optional("to", "string", "Whom it is for, a player or a bot: their exact name")),
                call -> toss(call.bot(), call.args())));
        tools.add(new Tool("trash", "Your trash list: what you toss by yourself when your backpack is full (of a block you"
                + " can build with you keep one stack). Without arguments, it says it. 'toss the stone when you are full'"
                + " -> add=stone; 'gravel is not trash' -> remove=gravel.",
                List.of(Tool.optional("add", "string", "An item's id in English, to put on the list"),
                        Tool.optional("remove", "string", "An item's id in English, to take off the list")),
                call -> trashTool(call.bot(), call.args())));
    }

    private static String toss(Bots.Bot p, JsonObject a) {
        BotPlayer b = p.body;
        if (!a.has("what") || !a.get("what").isJsonPrimitive()) return "toss what? (an item's id: cobblestone...)";
        String words = a.get("what").getAsString();
        Item item = Gear.item(words);
        if (item == null) return "there is no item called " + words.trim();
        Inventory inv = b.getInventory();
        int had = Gear.count(inv, item);
        if (had == 0) {
            return "I carry no " + Gear.id(item) + (worn(b, item) ? " (I wear it: remove_armor first)" : "");
        }
        int wanted = had;
        if (a.has("count") && a.get("count").isJsonPrimitive()) {
            try {
                int n = a.get("count").getAsInt();
                if (n < 1) return "the count is a number from 1 up; leave it out for all of it";
                wanted = Math.min(had, n);
            } catch (RuntimeException e) {
                // "everything", as Masurium's tool took it: all of it.
            }
        }
        String to = a.has("to") && a.get("to").isJsonPrimitive() ? a.get("to").getAsString().trim() : "";
        String looking = "";
        if (!to.isEmpty()) {
            ServerPlayer whom = b.getServer().getPlayerList().getPlayerByName(to);
            // Someone it sees: a throw at a name behind a wall would find the wall, and a
            // player in its place would not know where they are.
            if (whom != null && whom != b && whom.level() == b.level() && whom.distanceTo(b) <= SEE && b.hasLineOfSight(whom)) {
                // The throw flies where it looks, at once: the server's rotation is the one.
                b.lookAt(EntityAnchorArgument.Anchor.EYES, whom.getEyePosition());
                looking = String.format(" looking at %s (%.1f blocks away)", whom.getGameProfile().getName(), whom.distanceTo(b));
            } else {
                looking = " where I was looking: I do not see " + to + " near me";
            }
        }
        if (b.isUsingItem() && b.getUseItem().is(item)) b.stopUsingItem();
        int left = wanted;
        // What is in its hand goes last of all: the other stacks first, then that one.
        for (int pass = 0; pass < 2 && left > 0; pass++) {
            boolean handPass = pass == 1;
            for (int i = 0; i < Gear.SLOTS && left > 0; i++) {
                if ((i == inv.selected) != handPass || !inv.getItem(i).is(item)) continue;
                ItemStack part = inv.removeItem(i, left);
                int n = part.getCount();
                if (b.drop(part, true) == null) break;          // a mod refused the toss
                left -= n;
            }
        }
        int now = Gear.count(inv, item);
        int went = had - now;
        LOG.info("[tachyon] {} tossed {} {}{}", p.name(), went, Gear.id(item), looking);
        return "I tossed " + went + " " + Gear.id(item) + looking + "; I have " + now + " left."
                + " Tossed things vanish after 5 minutes, and I do not pick up again what I tossed.";
    }

    private static boolean worn(BotPlayer b, Item item) {
        for (ItemStack s : b.getInventory().armor) {
            if (s.is(item)) return true;
        }
        return b.getOffhandItem().is(item);
    }

    private static String trashTool(Bots.Bot p, JsonObject a) {
        String change = "";
        for (String how : new String[]{"add", "remove"}) {
            if (!a.has(how) || !a.get(how).isJsonPrimitive() || a.get(how).getAsString().isBlank()) continue;
            String words = a.get(how).getAsString();
            Item item = Gear.item(words);
            if (item == null) return "there is no item called " + words.trim();
            String id = BuiltInRegistries.ITEM.getKey(item).toString();
            boolean changed = how.equals("add") ? TRASH.add(p.data, id) : TRASH.remove(p.data, id);
            change += (how.equals("add") ? (changed ? Gear.id(item) + " is trash now. " : Gear.id(item) + " was trash already. ")
                    : (changed ? Gear.id(item) + " is not trash any more. " : Gear.id(item) + " was not trash. "));
        }
        if (!change.isEmpty()) PICKED.add(p);             // looked at again: it may be full of what is trash now
        return change + list(p);
    }

    /** Its trash list, in words for the model. */
    private static String list(Bots.Bot p) {
        List<String> ids = TRASH.ids(p.data);
        if (ids.isEmpty()) return "My trash list is empty.";
        List<String> names = new ArrayList<>();
        for (String id : ids) names.add(id.startsWith("minecraft:") ? id.substring(10) : id);
        return "With my backpack full I toss: " + String.join(", ", names) + " (of a block I can build with I keep one stack).";
    }

    // --- /tachyon trash -------------------------------------------------------------------------

    /**
     * {@code /tachyon trash <who> [add|remove|default <item>]}: its trash list, listed or
     * changed, by its owner or an operator (its brain may change it too).
     */
    @Override
    public void commands(LiteralArgumentBuilder<CommandSourceStack> tachyon, CommandBuildContext context) {
        tachyon.then(Commands.literal("trash")
                .then(Bots.who()
                        .executes(Tossing::listCommand)
                        .then(change("add"))
                        .then(change("remove"))
                        .then(change("default"))));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> change(String how) {
        return Commands.literal(how).then(Commands.argument("item", ResourceLocationArgument.id())
                .suggests((c, sb) -> SharedSuggestionProvider.suggestResource(BuiltInRegistries.ITEM.keySet(), sb))
                .executes(c -> change(c, how)));
    }

    private static int listCommand(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        List<Bots.Bot> them = Bots.find(c);
        List<String> lines = new ArrayList<>();
        for (Bots.Bot p : them) lines.add(p.name() + ": " + list(p));
        return them.isEmpty() ? 0 : Bots.say(c.getSource(), String.join("\n", lines));
    }

    private static int change(CommandContext<CommandSourceStack> c, String how) throws CommandSyntaxException {
        List<Bots.Bot> them = Bots.find(c);
        if (them.isEmpty()) return 0;
        ResourceLocation key = ResourceLocationArgument.getId(c, "item");
        Item item = BuiltInRegistries.ITEM.getOptional(key).orElse(null);
        if (item == null || item == Items.AIR) return Bots.fail(c.getSource(), "there is no item called " + key);
        String id = key.toString();
        for (Bots.Bot p : them) {
            switch (how) {
                case "add" -> TRASH.add(p.data, id);
                case "remove" -> TRASH.remove(p.data, id);
                default -> TRASH.reset(p.data, id);
            }
            PICKED.add(p);
        }
        return Bots.told(c, them, TRASH.has(them.get(0).data, id) ? Gear.id(item) + " is trash" : Gear.id(item) + " is not trash");
    }
}
