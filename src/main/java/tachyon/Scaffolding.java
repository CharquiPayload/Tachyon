package tachyon;

import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import tachyon.path.Route;

import java.util.Set;

/**
 * Building to get somewhere: a block placed under the next tile to cross a gap (a bridge),
 * or under its own feet at the top of a jump to climb (a tower), when there is no way on
 * foot. Masurium's Builder and the build steps of its Walker.
 *
 * <p>The path finder plans them only when the walk may build (its {@code build_to_move}
 * setting for a trip, {@code build_while_following} to follow) and the bot carries blocks it
 * may spend on it: planning a bridge with nothing to build it with ended, on Masurium's
 * client, in "I could not bridge" in front of the gap every few seconds for minutes. What
 * keeps a bot from redecorating is the cost table: a bridge block costs the path finder 23
 * ticks and a tower block 28, against 4.6 for a step, so it builds only where walking,
 * swimming and going round do not get it there.
 *
 * <p>It spends only cheap blocks ({@link #isScaffold}: dirt, stone, cobblestone, sand,
 * gravel, planks, netherrack...), taken from its hotbar or brought up from its backpack, and
 * places them as a player does: sneaking (a chest it builds against is not opened), with
 * the block in its hand, clicking the face of a block it reaches, the game's own placing
 * (protections, claims and the spawn's protection see a player placing a block). The bot
 * does not pick them up again, as Masurium's did not.
 */
final class Scaffolding implements Ability {

    /** Whether a trip (go to, come here) may build bridges and towers. */
    static final String BUILD = "build_to_move";
    /** Whether following someone may build them. */
    static final String WHILE_FOLLOWING = "build_while_following";

    /** A tower that has not got off the ground in this many ticks is given up. */
    private static final int TOWER_TICKS = 40;
    /** The block goes under the feet once they are this far over its cell: at the top of the jump. */
    private static final double TOWER_CLEAR = 1.1;

    /**
     * What may be spent on a bridge or a tower, Masurium's list: nothing valuable. The game's
     * dirt, planks and base stone tags add the rest of those families (and what mods tag so).
     */
    private static final Set<String> SCAFFOLD = Set.of(
            "minecraft:dirt", "minecraft:coarse_dirt", "minecraft:rooted_dirt", "minecraft:grass_block",
            "minecraft:cobblestone", "minecraft:cobbled_deepslate", "minecraft:stone", "minecraft:deepslate",
            "minecraft:andesite", "minecraft:diorite", "minecraft:granite", "minecraft:tuff", "minecraft:calcite",
            "minecraft:netherrack", "minecraft:blackstone", "minecraft:basalt", "minecraft:sand", "minecraft:gravel",
            "minecraft:oak_planks", "minecraft:spruce_planks", "minecraft:birch_planks", "minecraft:cherry_planks");

    @Override
    public void settings(Settings settings) {
        settings.bool(BUILD, true, "whether it may place blocks it carries (dirt, stone, planks...) to get where it"
                        + " goes when there is no way on foot: a bridge over a gap, a tower to climb", Settings.Who.OWNER)
                .label("Build to get there").group("Walking").basic();
        settings.bool(WHILE_FOLLOWING, true, "whether it may also place them to keep up with whom it follows",
                        Settings.Who.OWNER)
                .label("Build when following").group("Walking").advanced();
    }

    /** Whether it is a block a bot may spend on a bridge or a tower. */
    static boolean isScaffold(ItemStack stack) {
        if (stack.isEmpty() || !(stack.getItem() instanceof BlockItem item)) return false;
        if (SCAFFOLD.contains(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString())) return true;
        if (stack.is(ItemTags.PLANKS) || stack.is(ItemTags.DIRT)) return true;
        var block = item.getBlock().defaultBlockState();
        return block.is(BlockTags.BASE_STONE_OVERWORLD) || block.is(BlockTags.BASE_STONE_NETHER);
    }

    /** Whether it carries any, hotbar or backpack. */
    static boolean carries(BotPlayer b) {
        Inventory inv = b.getInventory();
        for (int i = 0; i < inv.items.size(); i++) {
            if (isScaffold(inv.items.get(i))) return true;
        }
        return false;
    }

    /** Whether a follower's search may plan building. */
    static boolean mayBuildFollowing(Bots.Bot p) {
        return Settings.bool(p, WHILE_FOLLOWING) && carries(p.body);
    }

    // --- the walk's build step --------------------------------------------------------------

    /**
     * This tick of the walk, if the route's next point needs a block first: a tower going
     * up, one to start (the point is straight over the one it stands on), or a bridge (the
     * point is beside it, at its level, over nothing). It presses the keys itself then.
     * Called by the legs for a route searched with building allowed.
     *
     * @return whether this tick went to building (the walk presses nothing else)
     */
    static boolean step(Bots.Bot p, Route.Point goal) {
        BotPlayer b = p.body;
        ServerLevel level = b.serverLevel();
        int px = (int) Math.floor(b.getX()), pz = (int) Math.floor(b.getZ()), py = Bots.floorY(p);

        // A tower started is a state, not a condition looked at again: in the jump the body
        // rises and "the point is right over me" stops being true halfway; looking again
        // gave the tower up in the air, and the bot jumped in place for ever.
        if (p.towerAt != null) {
            b.zza = 0;
            b.xxa = 0;
            b.setSprinting(false);
            b.setJumping(true);
            // Not at take-off: the body is in the cell where the block goes, and the game
            // refuses a block placed into it. At the top of the jump.
            if (b.getY() > p.towerAt.getY() + TOWER_CLEAR && Bots.handsFree(p)) {
                BlockPos at = p.towerAt;
                p.towerAt = null;
                String failed = place(p, at, Direction.DOWN);
                if (failed != null) Bots.blocked(p, "I could not build a tower: " + failed);
                else Bots.placed(p);
            } else if (++p.towerTicks > TOWER_TICKS) {
                p.towerAt = null;
                Bots.blocked(p, "I tried to build a tower and could not get off the ground");
            }
            return true;
        }
        // The point is over the one it stands on (one up, or more: pushed about, it may have
        // come down from a block of its tower): a block under its feet brings it closer.
        if (goal.x() == px && goal.z() == pz && goal.y() > py && b.onGround()) {
            p.towerAt = new BlockPos(px, py, pz);
            p.towerTicks = 0;
            b.zza = 0;
            b.xxa = 0;
            b.setSprinting(false);
            b.setJumping(true);
            return true;
        }
        // A bridge: the next point beside it, at its level, over nothing. Over water it swims
        // instead: water has no collision box, and a route that swims across a lake (which
        // the path finder picks over bridging it) became a bridge of 34 cobblestone on
        // Masurium's client. Over lava it does bridge: that is how lava is crossed. From the
        // ground only: in a jump, or on another's shoulders in a crowd, the tile under the
        // feet is not the one it stands on.
        if (goal.y() != py || !b.onGround()) return false;
        BlockPos under = new BlockPos(goal.x(), goal.y() - 1, goal.z());
        BlockPos at = new BlockPos(goal.x(), goal.y(), goal.z());
        if (level.getFluidState(at).is(FluidTags.WATER) || level.getFluidState(under).is(FluidTags.WATER)) return false;
        if (!level.getBlockState(under).getCollisionShape(level, under).isEmpty()) return false;
        Direction toward = toward(goal.x() - px, goal.z() - pz);
        if (toward == null) return false;              // not beside it yet: walk on
        Bots.release(b);
        if (!Bots.handsFree(p)) return true;           // a bite being swallowed: a moment
        String failed = place(p, under, toward.getOpposite());
        if (failed != null) Bots.blocked(p, "I could not bridge: " + failed);
        else Bots.placed(p);
        return true;
    }

    /**
     * A block at {@code where}, resting on its neighbour toward {@code fromFace}: blocks are
     * not placed in the air, but against the face of one that is there, which the hand
     * clicks, from within a player's reach. Sneaking, as a player places against a chest or
     * a door without opening it.
     *
     * @return null once the block is there; else why not
     */
    static String place(Bots.Bot p, BlockPos where, Direction fromFace) {
        BotPlayer b = p.body;
        ServerLevel level = b.serverLevel();
        BlockPos support = where.relative(fromFace);
        if (level.getBlockState(support).getCollisionShape(level, support).isEmpty()) {
            return "there is nothing to rest the block on at " + Brain.pos(support);
        }
        if (!b.canInteractWithBlock(support, 1.0)) return "it is out of my reach";
        if (!level.mayInteract(b, support)) return "I may not build there (the spawn's protection, or the world's border)";
        if (!isScaffold(b.getMainHandItem())) Job.wield(p, s -> isScaffold(s) ? 1 : 0);
        ItemStack stack = b.getMainHandItem();
        if (!isScaffold(stack)) return "I carry no blocks to build with (dirt, stone, cobblestone, planks...)";
        Direction face = fromFace.getOpposite();
        Vec3 hit = Vec3.atCenterOf(support).add(face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
        b.lookAt(EntityAnchorArgument.Anchor.EYES, hit);
        boolean sneaking = b.isShiftKeyDown();
        b.setShiftKeyDown(true);
        try {
            b.gameMode.useItemOn(b, level, stack, InteractionHand.MAIN_HAND, new BlockHitResult(hit, face, support, false));
        } finally {
            b.setShiftKeyDown(sneaking);
        }
        b.swing(InteractionHand.MAIN_HAND);
        if (level.getBlockState(where).getCollisionShape(level, where).isEmpty()) {
            return "the block did not stay at " + Brain.pos(where) + " (something in the way, or a protected place)";
        }
        return null;
    }

    /** The side a one-tile step goes toward; null when it is not one (a diagonal, or farther). */
    static Direction toward(int dx, int dz) {
        if (dx == 1 && dz == 0) return Direction.EAST;
        if (dx == -1 && dz == 0) return Direction.WEST;
        if (dx == 0 && dz == 1) return Direction.SOUTH;
        if (dx == 0 && dz == -1) return Direction.NORTH;
        return null;
    }
}
