package tachyon;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import tachyon.path.Route;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Digging through to get somewhere, Masurium's {@code break_to_advance}: with it on (it is
 * off unless its owner turns it on), when there is no way on foot, a route may go through
 * blocks on the bot's break list, and the legs dig the head's block and then the feet's,
 * with the best tool it carries and the time the block takes, as a player digs.
 *
 * <p><b>The break list</b> is what a bot may break ON ITS OWN to make its way: cobblestone,
 * dirt, grass blocks and stone to start with, changed for it by its owner or an operator
 * ({@code /tachyon break}); its brain reads it and cannot change it. It rules only there:
 * what a bot is TOLD to break (clear a box) never needed it, the order is the permission.
 * The list is looked at again on the block itself as it is dug: a route searched a moment
 * ago is no permission for a block that changed since.
 *
 * <p>Breaking destroys other people's world, which building to move only adds to: that is
 * why it is off unless asked, and why the path finder charges 40 ticks a block for it,
 * more than any reasonable way round.
 */
final class Tunnelling implements Ability {

    /** Whether a route may go through blocks on its break list, digging them. */
    static final String BREAK = "break_to_advance";

    /** What every bot may break on its own to begin with, Masurium's: the common ground in the way everywhere. */
    static final ItemList MAY_BREAK = new ItemList("break", List.of("minecraft:cobblestone", "minecraft:dirt",
            "minecraft:grass_block", "minecraft:stone"));

    /** A block that takes longer than this to dig (30 s) is given up, as Clear gives it up. */
    private static final int DIG_MAX = 20 * 30;

    @Override
    public void settings(Settings settings) {
        settings.bool(BREAK, false, "whether it may dig through blocks on its break list (/tachyon break) to get where"
                        + " it goes when there is no way on foot", Settings.Who.OWNER)
                .label("Dig through to get there").group("Walking").basic();
    }

    /** Whether its walks may dig through (its setting). */
    static boolean advancing(Bots.Bot p) {
        return Settings.bool(p, BREAK);
    }

    /** Its break list as blocks, for a search to read off the server's thread. */
    static Set<Block> blocks(Bots.Bot p) {
        Set<Block> out = new HashSet<>();
        for (String id : MAY_BREAK.ids(p.data)) {
            ResourceLocation key = ResourceLocation.tryParse(id);
            if (key != null) BuiltInRegistries.BLOCK.getOptional(key).ifPresent(out::add);
        }
        out.remove(Blocks.AIR);
        return Set.copyOf(out);
    }

    /** Whether that block is on its break list. */
    static boolean mayBreak(Bots.Bot p, BlockState s) {
        return MAY_BREAK.has(p.data, BuiltInRegistries.BLOCK.getKey(s.getBlock()).toString());
    }

    // --- the walk's dig step ----------------------------------------------------------------

    /**
     * This tick of the walk, if the route's next point is blocked at the head or the feet by
     * a block it may dig: the head's first, then the feet's, as a demolition goes, top down.
     * It presses the keys itself then. Called by the legs for a route searched with digging
     * allowed.
     *
     * @return whether this tick went to digging (the walk presses nothing else)
     */
    static boolean step(Bots.Bot p, Route.Point goal) {
        BotPlayer b = p.body;
        ServerLevel level = b.serverLevel();
        if (goal.y() != Bots.floorY(p)) {
            abort(p);
            return false;
        }
        BlockPos feet = new BlockPos(goal.x(), goal.y(), goal.z());
        BlockPos plug = inTheWay(level, feet.above()) ? feet.above() : inTheWay(level, feet) ? feet : null;
        if (plug == null) {
            abort(p);
            return false;
        }
        if (p.digging != null && !p.digging.equals(plug)) abort(p);
        if (!b.canInteractWithBlock(plug, 1.0)) return false;        // not there yet: walk on
        BlockState s = level.getBlockState(plug);
        String name = name(s);
        if (!mayBreak(p, s)) {
            Bots.blocked(p, "the way is blocked by " + name + " and I have no permission to break it");
            return true;
        }
        if (s.getDestroySpeed(level, plug) < 0) {
            Bots.blocked(p, "the way is blocked by " + name + ", which cannot be broken");
            return true;
        }
        if (!level.mayInteract(b, plug)) {
            Bots.blocked(p, "the way is blocked by " + name + " and I may not break blocks there (the spawn's"
                    + " protection, or the world's border)");
            return true;
        }
        Bots.release(b);
        if (!Bots.handsFree(p)) return true;           // a bite being swallowed: a moment
        if (p.digging == null) {
            Job.wield(p, stack -> stack.getDestroySpeed(s) + (stack.isCorrectToolForDrops(s) ? 0.5 : 0));
            p.digging = plug;
            p.digTicks = 0;
            p.digFace = Direction.getNearest(b.getEyePosition().subtract(Vec3.atCenterOf(plug)));
            b.lookAt(EntityAnchorArgument.Anchor.EYES, Vec3.atCenterOf(plug));
            b.swing(InteractionHand.MAIN_HAND);
            action(b, plug, p.digFace, ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK);
            gone(p, level, plug);                      // some blocks break at the first stroke
            return true;
        }
        if (++p.digTicks > DIG_MAX) {
            abort(p);
            Bots.blocked(p, "breaking " + name + " took more than " + DIG_MAX / 20 + " s");
            return true;
        }
        b.lookAt(EntityAnchorArgument.Anchor.EYES, Vec3.atCenterOf(plug));
        if (p.digTicks % 4 == 0) b.swing(InteractionHand.MAIN_HAND);
        if (s.getDestroyProgress(b, level, plug) * p.digTicks >= 1.0f) {
            action(b, plug, p.digFace, ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK);
            gone(p, level, plug);
        }
        return true;
    }

    /** The block it dug is gone: counted, and the stroke over. */
    private static void gone(Bots.Bot p, Level level, BlockPos plug) {
        if (inTheWay(level, plug)) return;
        p.digging = null;
        Bots.dug(p);
    }

    /** A block half dug, let go of (the walk changed, or it stopped). */
    static void abort(Bots.Bot p) {
        if (p.digging == null) return;
        if (p.body.isAlive()) {
            action(p.body, p.digging, p.digFace, ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK);
        }
        p.digging = null;
    }

    /**
     * Whether a block stops the body, as the path finder counts it: a box taller than half a
     * block (a slab, a carpet, snow are walked on, not dug), and not an open trapdoor. Not a
     * door or a gate either: those are opened.
     */
    private static boolean inTheWay(Level level, BlockPos pos) {
        BlockState s = level.getBlockState(pos);
        if (s.isAir() || s.is(BlockTags.WOODEN_DOORS) || s.is(BlockTags.FENCE_GATES)) return false;
        if (s.is(BlockTags.TRAPDOORS) && s.getValue(TrapDoorBlock.OPEN)) return false;
        var box = s.getCollisionShape(level, pos);
        return !box.isEmpty() && box.max(Direction.Axis.Y) > 0.5;
    }

    /** What a client's packet would tell the server's game mode. */
    private static void action(BotPlayer b, BlockPos pos, Direction face, ServerboundPlayerActionPacket.Action what) {
        b.gameMode.handleBlockBreakAction(pos, what, face, b.level().getMaxBuildHeight(), 0);
    }

    /** A block's id as players say it: {@code stone}, a mod's with its name. */
    private static String name(BlockState s) {
        String id = BuiltInRegistries.BLOCK.getKey(s.getBlock()).toString();
        return id.startsWith("minecraft:") ? id.substring("minecraft:".length()) : id;
    }

    // --- the break list: /tachyon break, and the brain's look at it ---------------------------

    /**
     * {@code /tachyon break <who> [allow|forbid|default <block>]}: what it may break on its
     * own, listed or changed, by its owner or an operator. {@code default} undoes the bot's
     * own change for that block.
     */
    @Override
    public void commands(LiteralArgumentBuilder<CommandSourceStack> tachyon, CommandBuildContext context) {
        tachyon.then(Commands.literal("break")
                .then(Bots.who()
                        .executes(Tunnelling::list)
                        .then(change("allow"))
                        .then(change("forbid"))
                        .then(change("default"))));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> change(String how) {
        return Commands.literal(how).then(Commands.argument("block", ResourceLocationArgument.id())
                .suggests((c, sb) -> SharedSuggestionProvider.suggestResource(BuiltInRegistries.BLOCK.keySet(), sb))
                .executes(c -> change(c, how)));
    }

    private static int list(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        List<Bots.Bot> them = Bots.find(c);
        List<String> lines = new ArrayList<>();
        for (Bots.Bot p : them) {
            List<String> names = names(p);
            lines.add(p.name() + " may break on its own, to make its way" + (advancing(p) ? "" : " (with "
                    + BREAK + " on; it is off)") + ": " + (names.isEmpty() ? "nothing" : String.join(", ", names)));
        }
        return them.isEmpty() ? 0 : Bots.say(c.getSource(), String.join("\n", lines));
    }

    private static int change(CommandContext<CommandSourceStack> c, String how) throws CommandSyntaxException {
        List<Bots.Bot> them = Bots.find(c);
        if (them.isEmpty()) return 0;
        ResourceLocation key = ResourceLocationArgument.getId(c, "block");
        Block block = BuiltInRegistries.BLOCK.getOptional(key).orElse(null);
        if (block == null || block == Blocks.AIR) return Bots.fail(c.getSource(), "there is no block called " + key);
        String id = key.toString();
        for (Bots.Bot p : them) {
            switch (how) {
                case "allow" -> MAY_BREAK.add(p.data, id);
                case "forbid" -> MAY_BREAK.remove(p.data, id);
                default -> MAY_BREAK.reset(p.data, id);
            }
        }
        String shortId = shortId(id);
        String now = MAY_BREAK.has(them.get(0).data, id) ? "may break " + shortId + " on its own"
                : "does not break " + shortId + " on its own";
        return Bots.told(c, them, now);
    }

    private static List<String> names(Bots.Bot p) {
        List<String> names = new ArrayList<>();
        for (String id : MAY_BREAK.ids(p.data)) names.add(shortId(id));
        return names;
    }

    private static String shortId(String id) {
        return id.startsWith("minecraft:") ? id.substring("minecraft:".length()) : id;
    }

    @Override
    public void tools(Tools tools) {
        tools.add(new Tool("break_permissions", "The blocks you may break on your own to make your way, digging through"
                + " when there is no other way (only with your dig-through setting on). What you are told to break"
                + " (clear a box) never needs it. You cannot change it: your owner or an operator does, with"
                + " /tachyon break.", List.of(),
                call -> {
                    Bots.Bot p = call.bot();
                    List<String> names = names(p);
                    return (names.isEmpty() ? "I may break nothing on my own" : "I may break on my own, to make my way: "
                            + String.join(", ", names))
                            + (advancing(p) ? "" : "; but I do not dig through now: my " + BREAK + " setting is off")
                            + ". My owner or an operator changes the list with /tachyon break " + p.name()
                            + " allow|forbid <block>.";
                }));
    }
}
