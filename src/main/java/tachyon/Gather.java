package tachyon;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.AbstractFurnaceBlock;
import net.minecraft.world.level.block.BaseTorchBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.CraftingTableBlock;
import net.minecraft.world.level.block.IronBarsBlock;
import net.minecraft.world.level.block.LanternBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import tachyon.path.Route;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Gathering: blocks of some kinds broken, nearest first, with the right tool, and what
 * they drop picked up, until it carries as many more of an item as it was asked for (or,
 * with no item named, has broken that many blocks). Masurium's gather, on the server: "get
 * me some dirt" in one order, never a box cleared around itself.
 *
 * <p>It takes only what a player in its place would: blocks <b>in the open</b>, with a face
 * to the air (buried rock is not seen through: that would be x-ray) and that face under the
 * sky, or in its sight from where it stands. The look is a sweep of the loaded blocks
 * around it, {@link #RADIUS} blocks each way, {@link #DOWN} down and {@link #UP} up, whole
 * chunk sections skipped when they hold none of the kinds; the nearest
 * {@link #KNOWN_MAX} it may take are kept, and it looks again when they are spent. Ores
 * are never gathered: finding them this way is the x-ray; they are for mining.
 *
 * <p>It never takes a piece of a build: a block a player placed ({@link PlacedBlocks}), one
 * that touches a building block (planks, stairs, slabs, doors, glass, bricks, torches...)
 * or a block a player placed, and, for logs, any but a tree's: the logs joined to it must
 * touch leaves that grew there and no building block, as Masurium's rule is (a log cabin
 * is logs too). Only a person's order clears a build ({@code /tachyon clear}). Nor a block
 * with nothing under it to catch what it drops ({@link #caught}): a bridge a bot built over
 * a gap is one, and taking it lost the drops to the gap and the way back with them. A tree
 * is not: its logs and leaves hang over the ground they grew from, which catches them.
 *
 * <p>With none near, it goes out looking ({@link Scouting}: legs of 48 blocks, 300 blocks or 3
 * minutes at most, twice an errand), looking around as it walks; a search that finds none
 * ends it, and so does a full backpack or no tool that makes the blocks drop anything. Every
 * way it ends says how many it got, with what it left alone and why. Several gatherers
 * share what is around: each claims the block it goes for.
 */
final class Gather extends Job {

    /** The look: this far each way, this far down and up (Masurium's numbers). */
    static final int RADIUS = 16, DOWN = 4, UP = 6;
    /** The blocks a look keeps, the nearest first: when they are spent, it looks again. */
    static final int KNOWN_MAX = 64;
    /** The sight tried on this many blocks at most in a look, for those not under the sky. */
    static final int SIGHTS_MAX = 24;
    /**
     * The blocks a look weighs at most, the nearest first: under a hill of stone a sweep finds
     * thousands, nearly all buried, and weighing each (six blocks read around it) cost the
     * tick 70,000 reads for one look.
     */
    static final int WEIGHED_MAX = 1024;
    /** Looks in one tick, for all the bots: the rest wait their turn, in the order they asked. */
    static final int LOOKS_PER_TICK = 4;
    /** How often it may look, on its own ticks: 0.5 s near, 2 s while out looking. */
    private static final int LOOK_EVERY = 10, LOOK_OUT_EVERY = 40;
    /** A block it walked to this many times and could not reach is given up. */
    private static final int TRIES_MAX = 3;
    /** Breaking one block for longer than this (30 s) is a refusal: it is given up. */
    private static final int BREAK_MAX = 20 * 30;
    /** The server not breaking a block this many ticks after its time was up is a refusal (a protection). */
    private static final int REFUSED_TICKS = 5;
    /** What a block dropped: looked for this far around it, for this long; an item it stands by this long is left. */
    private static final double LOOT_RADIUS = 2.5;
    private static final int LOOT_TICKS = 100, BY_ITEM_TICKS = 40;
    /** How close to the tile it saw a block from a walk to it ends (Masurium's Miner's): see {@link #seenFrom}. */
    private static final double ON_TILE = 0.4;
    /** This close to an item, with no route to walk, it walks straight at it. */
    private static final double CLOSE_IN = 4.0;
    /** How many logs of a tree it follows, at most, to tell a tree from a building. */
    private static final int TREE_MAX = 96;
    /**
     * What a block drops must come to rest on something this far under it at most (on top of
     * the third block down, 2 blocks lower: within {@link #LOOT_RADIUS} of it, where it is
     * looked for).
     */
    private static final int CATCH = 3;
    /**
     * Blocks broken in a row that do not bring the count up, at least, before it stops (and
     * four for each one asked, when that is more): gravel gives flint one time in ten, but
     * stone never gives stone, and a bot asked for 3 stone counted as stone broke 52 and went
     * on looking for more.
     */
    private static final int FRUITLESS_MIN = 16;

    /** The kinds it breaks. */
    private final Set<Block> kinds;
    /** The item it counts, and how many it had as it started; null: it counts the blocks it breaks. */
    private final Item item;
    private final int before;
    private final int wanted;
    /** Which way it goes looking first; null: the way it faces. */
    private final Direction toward;
    private final String what;

    private int broken;
    /** The blocks the last look kept, and those given up for this errand (no way, hidden, refused). */
    private final List<BlockPos> known = new ArrayList<>();
    private final Set<BlockPos> tried = new HashSet<>();
    private ServerLevel level;
    /** What the last look saw and left: for the words when it ends. */
    private int buried, built, unseen, hanging;
    /** The kinds it carries no tool for, that make nothing drop by hand. */
    private final Set<String> noTool = new TreeSet<>();
    private int noWay, refused;
    private long lookedAt = Long.MIN_VALUE;
    /** A look it asked for and was not given (others looked that tick): it asks again on the next. */
    private boolean wantLook;
    /** The count, and the blocks broken, when the count last went up: for {@link #FRUITLESS_MIN}. */
    private int gotAtRise, brokenAtRise;

    private BlockPos goal;
    private int tries;
    private BlockPos breaking;
    private Direction face = Direction.UP;
    private int breakTicks, doneAt;
    /**
     * What its hand held as the stroke started, and whether that was nothing: another thing in
     * it since (a bite), or the same stack worn out to nothing, starts the stroke again.
     */
    private ItemStack held = ItemStack.EMPTY;
    private boolean heldNothing;
    /** Whether the stroke under way is on leaves in front of what it goes for ({@link #inPassing}): not counted, nothing picked up. */
    private boolean passing;

    /** Picking up what the last block dropped: where, since when, for how long still, what it goes for. */
    private BlockPos lootAt;
    private long lootSince;
    private int loot;
    private ItemEntity lying;
    private int byItem;
    private boolean awaitingItem;
    private final Set<UUID> leftOnGround = new HashSet<>();

    private final Scouting scouting = new Scouting();

    /**
     * @param kinds  the blocks it breaks
     * @param item   the item it counts; null: the blocks it breaks
     * @param wanted how many more
     * @param toward which way it goes looking first when none is near; null: the way it faces
     * @param what   what it gathers, in words: "dirt", "oak_log"
     */
    Gather(Bots.Bot p, Set<Block> kinds, Item item, int wanted, Direction toward, String what) {
        this.kinds = Set.copyOf(kinds);
        this.item = item;
        this.before = item == null ? 0 : Gear.count(p.body.getInventory(), item);
        this.wanted = wanted;
        this.toward = toward;
        this.what = what;
    }

    /** Whether it is gathering, for a bot, what {@code stack} is: what it gathers is not its trash meanwhile ({@link Tossing}). */
    static boolean gathering(Bots.Bot p, ItemStack stack) {
        if (!(Bots.job(p) instanceof Gather g)) return false;
        if (g.item != null) return stack.is(g.item);
        for (Block k : g.kinds) {
            if (stack.is(k.asItem())) return true;
        }
        return false;
    }

    // --- how it goes ---------------------------------------------------------------------------

    /** How many it has got: of its item, or blocks broken. */
    private int got(BotPlayer b) {
        return item == null ? broken : Gear.count(b.getInventory(), item) - before;
    }

    private String counted(BotPlayer b) {
        int n = Math.max(0, got(b));
        return n + (n < wanted ? " of " + wanted : "");
    }

    /** "gathered 16 dirt (broke 17 blocks)", "broke 5 of 16 sand": what it did, for how it ended. */
    private String done(BotPlayer b) {
        return item == null ? "broke " + counted(b) + " " + what + (broken > 0 ? " and picked up what they dropped" : "")
                : "gathered " + counted(b) + " " + what + " (broke " + broken + " block" + (broken == 1 ? ")" : "s)");
    }

    /** What it left alone and why, for the end: "; left alone 12 that players built with". */
    private String left() {
        StringBuilder s = new StringBuilder();
        if (built > 0) s.append("; left alone ").append(built).append(" that players placed or built with");
        if (buried > 0) s.append("; ").append(buried).append(" buried, with no face to the air (that is mining)");
        if (unseen > 0) s.append("; ").append(unseen).append(" out of sight");
        if (hanging > 0) s.append("; ").append(hanging).append(" with nothing under them to catch what they drop");
        if (noWay > 0) s.append("; ").append(noWay).append(" I could not get at");
        if (refused > 0) s.append("; ").append(refused).append(" the server would not let me break");
        if (!noTool.isEmpty()) s.append("; I carry no tool that makes ").append(String.join(", ", noTool)).append(" drop anything");
        return s.toString();
    }

    @Override
    String status() {
        String s = "gathering " + what + ": " + broken + " broken";
        if (scouting.out()) s += ", looking for more " + scouting.where();
        else if (loot > 0 && lying != null) s += ", picking up what fell";
        else if (breaking != null) s += " [breaking " + breaking.toShortString() + "]";
        else if (goal != null) s += " [going for " + goal.toShortString() + ", try " + tries + "]";
        return s;
    }

    @Override
    boolean think(Bots.Bot p, long now) {
        BotPlayer b = p.body;
        if (b.serverLevel() != level) {           // the first think, or through a portal
            release(p, goal);
            release(p, breaking);
            level = b.serverLevel();
            known.clear();
            tried.clear();
            goal = null;
            breaking = null;
        }
        if (breaking != null) return true;          // the hands are at it (act)
        if (loot > 0) {
            loot--;
            if (pickUp(p, now)) return true;
            loot = 0;
        }
        int got = got(b);
        if (got >= wanted) {
            Bots.halt(p, done(b));
            return false;
        }
        if (got > gotAtRise) {
            gotAtRise = got;
            brokenAtRise = broken;
        } else if (broken - brokenAtRise >= Math.max(FRUITLESS_MIN, 4 * wanted)) {
            // What it breaks does not drop what it counts (stone gives cobblestone, without
            // silk touch): walking on to break more would not change that.
            Bots.halt(p, done(b) + "; the last " + (broken - brokenAtRise) + " I broke gave me no " + what
                    + ", so I stopped" + left());
            return false;
        }
        if (!room(b.getInventory())) {       // what it breaks would stay on the ground
            Bots.halt(p, done(b) + "; my backpack is full");
            return false;
        }
        if (goal != null && !takes(b, goal)) {
            release(p, goal);
            goal = null;
        }
        if (goal == null) {
            goal = next(p);
            if (goal == null) return lookFurther(p, now);
            tries = 0;
            if (scouting.out()) {
                scouting.found();
                Bots.halt(p, status());
            }
        }
        // What a click at it would hit from here, if it is in reach, grass and torches in front
        // no wall (Masurium's Miner's rule: a player breaks them in passing): another block of
        // the kinds in front of it is as good, and nearer; leaves that grew there are broken in
        // passing; anything else in front, it leaves.
        BlockHitResult hit = Clear.sight(p, level, goal, ClipContext.Block.COLLIDER);
        if (hit != null) {
            BlockPos seen = hit.getBlockPos().immutable();
            if (!seen.equals(goal) && !tried.contains(seen) && mayTake(b, seen) && claim(p, seen)) {
                release(p, goal);
                goal = seen;
            }
            if (seen.equals(goal)) {
                if (p.path != null || p.pending != null) Bots.halt(p, status());
                // The first stroke is the hands', and waits while a reflex holds them. What was
                // around it when it looked may have changed since: the whole rule, once more.
                if (!Bots.handsFree(p)) return true;
                if (!mayTake(b, goal)) {
                    built++;
                    giveUp(p);
                    return true;
                }
                start(p, goal, hit.getDirection(), false);
                return true;
            }
            if (inPassing(b, seen)) {
                // Leaves in front: broken in passing, and the block looked at again after.
                if (p.path != null || p.pending != null) Bots.halt(p, status());
                if (Bots.handsFree(p)) start(p, seen, hit.getDirection(), true);
                return true;
            }
            if (p.path == null && p.pending == null) {
                giveUp(p);                     // in reach, and hidden behind what it may not break
                return true;
            }
        }
        if (p.pending != null || p.path != null) return true;
        if (now - p.plannedAt < Bots.REPLAN_TICKS) return true;
        if (++tries > TRIES_MAX) {
            noWay++;
            giveUp(p);
            return true;
        }
        p.plannedAt = now;
        BlockPos to = goal;
        Bots.plan(p, to, world -> seenFrom(world, to), status());
        // On the tile it was seen from, not somewhere within the usual 1.4 of it: a step
        // aside, the leaves round a trunk hid the log again, and it was given up.
        Bots.arriveWithin(p, ON_TILE);
        return true;
    }

    /**
     * A tile within a player's reach of the block from which it is seen ({@link
     * SnapshotWorld#sees}): where a player stands to click it, Masurium's Miner's rule. Within
     * reach alone, a bot stood beside a tree whose trunk went on up inside the leaves, saw
     * leaves where the log was, and gave the log up.
     */
    private static Route.Meta seenFrom(SnapshotWorld world, BlockPos pos) {
        Route.Meta reach = Clear.reachOf(pos);
        return new Route.Meta() {
            public boolean isGoal(int x, int y, int z) {
                return reach.isGoal(x, y, z) && world.sees(x + 0.5, y + Clear.EYES, z + 0.5, pos);
            }

            public double heuristic(int x, int y, int z) {
                return reach.heuristic(x, y, z);
            }
        };
    }

    /**
     * None of what it knows of is left: it looks around again (on its own ticks, a few bots a
     * tick), and with none in sight goes out looking, looking around as it walks. @return
     * false once it is over, having said why
     */
    private boolean lookFurther(Bots.Bot p, long now) {
        BotPlayer b = p.body;
        if (wantLook || Hunt.due(p, now, scouting.out() ? LOOK_OUT_EVERY : LOOK_EVERY)) {
            int n = look(p, now);
            wantLook = n < 0;
            if (n != 0) return true;          // some in sight (the next tick goes for one), or its turn not come yet
            if (!scouting.out()) {
                if (!noTool.isEmpty() && built + buried + unseen + hanging == 0) {
                    // What is here it cannot make drop anything, and walking on finds the same.
                    Bots.halt(p, done(b) + left());
                    return false;
                }
                if (!scouting.begin(p, now, toward)) {
                    Bots.halt(p, done(b) + "; I see no more " + what + " in the open within " + RADIUS + " blocks" + left());
                    return false;
                }
                Bots.halt(p, status());
            }
        }
        if (!scouting.out() || !Hunt.due(p, now, LOOK_EVERY)) return true;      // the walk goes on meanwhile
        String end = scouting.step(p, now, status());
        if (end == null) return true;
        Bots.halt(p, done(b) + "; I saw no more " + what + " in the open: " + end + left());
        return false;
    }

    /** Room for what it gathers: a free slot, or a stack of its item with room left. */
    private boolean room(Inventory inv) {
        if (inv.getFreeSlot() >= 0) return true;
        if (item != null) return inv.getSlotWithRemainingSpace(new ItemStack(item)) >= 0;
        for (Block k : kinds) {
            if (k.asItem() != Items.AIR && inv.getSlotWithRemainingSpace(new ItemStack(k)) >= 0) return true;
        }
        return false;
    }

    // --- which blocks ----------------------------------------------------------------------------

    /**
     * The nearest block the last look kept that it may still take, claimed: not given up,
     * not another gatherer's, still of its kinds and nobody's build. Null: none left.
     */
    private BlockPos next(Bots.Bot p) {
        BotPlayer b = p.body;
        known.sort(Comparator.comparingDouble(pos -> pos.distToCenterSqr(b.position())));
        while (!known.isEmpty()) {
            BlockPos pos = known.remove(0);
            if (tried.contains(pos) || !takes(b, pos) || !claim(p, pos)) continue;
            return pos;
        }
        return null;
    }

    /**
     * Whether it takes the block there now: of its kinds, still not placed by a player, and
     * something it can make drop what it is (a pickaxe for stone). What else a look weighs
     * (in the open, part of a build) was weighed as it looked.
     */
    private boolean takes(BotPlayer b, BlockPos pos) {
        BlockState s = level.getBlockState(pos);
        return kinds.contains(s.getBlock()) && drops(b.getInventory(), s) && !PlacedBlocks.byPlayer(level, pos);
    }

    /**
     * The whole rule, for one block about to be broken: it takes it ({@link #takes}), it is no
     * piece of a build, and the server lets players break there (not the spawn's protection).
     */
    private boolean mayTake(BotPlayer b, BlockPos pos) {
        return takes(b, pos) && level.mayInteract(b, pos)
                && !partOfBuild(level, PlacedBlocks.of(level), pos, level.getBlockState(pos), new HashMap<>());
    }

    /** Whether it can make the block drop something: it needs no tool, or it carries one that does. */
    static boolean drops(Inventory inv, BlockState s) {
        if (!s.requiresCorrectToolForDrops()) return true;
        for (int i = 0; i < Gear.SLOTS; i++) {
            if (inv.getItem(i).isCorrectToolForDrops(s)) return true;
        }
        return false;
    }

    /**
     * A look around: the blocks of its kinds within the sweep, nearest first, kept while they
     * are what a player here would take (in the open, in sight, nobody's build), up to
     * {@link #KNOWN_MAX}; the nearest {@link #WEIGHED_MAX} weighed at most. Not more than
     * {@link #LOOKS_PER_TICK} bots look in a tick, in the order they asked ({@link #mayLook}).
     *
     * @return how many it kept; -1 when its turn has not come (others looked this tick)
     */
    private int look(Bots.Bot p, long now) {
        if (!mayLook(p, now)) return -1;
        lookedAt = now;
        BotPlayer b = p.body;
        LongArrayList found = sweep(level, b.blockPosition(), kinds);
        long[] nearest = byDistance(found, b.position());
        PlacedBlocks.Places places = PlacedBlocks.of(level);
        Inventory inv = b.getInventory();
        Map<BlockState, Boolean> tool = new HashMap<>();
        Map<BlockPos, Boolean> trees = new HashMap<>();
        known.clear();
        buried = built = unseen = hanging = 0;
        noTool.clear();
        int sights = 0;
        for (int k = 0; k < nearest.length && k < WEIGHED_MAX && known.size() < KNOWN_MAX; k++) {
            BlockPos pos = BlockPos.of(found.getLong((int) (nearest[k] & INDEX)));
            if (tried.contains(pos)) continue;
            BlockState s = level.getBlockState(pos);
            if (!tool.computeIfAbsent(s, st -> drops(inv, st))) {
                noTool.add(name(s.getBlock()));
                continue;
            }
            if (!exposed(level, pos)) {
                buried++;
                continue;
            }
            if (places.byPlayer(level, pos) || partOfBuild(level, places, pos, s, trees) || !level.mayInteract(b, pos)) {
                built++;
                continue;
            }
            boolean grew = grew(s, pos, trees);
            if (!grew && !caught(level, pos)) {
                hanging++;
                continue;
            }
            if (!inTheOpen(level, pos) && !(grew && crown(level, pos))) {
                if (sights++ >= SIGHTS_MAX || !inSight(b, pos)) {
                    unseen++;
                    continue;
                }
            }
            known.add(pos);
        }
        return known.size();
    }

    /** The low bits of a {@link #byDistance} key: the index of the block in the sweep's list. */
    private static final long INDEX = (1L << 20) - 1;

    /**
     * The sweep's blocks in order of distance from {@code at}, as keys: the squared distance
     * (in sixteenths) in the high bits, the index in {@code found} in the low 20 (a sweep holds
     * 12,000 blocks at most). One sort of plain numbers, where a sort of the positions made a
     * position and two distances for each comparison.
     */
    static long[] byDistance(LongArrayList found, Vec3 at) {
        long[] keys = new long[found.size()];
        for (int i = 0; i < keys.length; i++) {
            long pos = found.getLong(i);
            double dx = BlockPos.getX(pos) + 0.5 - at.x, dy = BlockPos.getY(pos) + 0.5 - at.y, dz = BlockPos.getZ(pos) + 0.5 - at.z;
            keys[i] = (long) ((dx * dx + dy * dy + dz * dz) * 16) << 20 | i;
        }
        Arrays.sort(keys);
        return keys;
    }

    /** The tick of the last look any bot made, and how many were made in it. The server's thread's. */
    private static long looksTick = -1;
    private static int looks;
    /**
     * The gatherers waiting their turn to look, in the order they first asked, each with the
     * last tick it asked: one that stops asking (its job over, or set aside) loses its place.
     * The server's thread's; as many as there are gatherers at most.
     */
    private static final LinkedHashMap<Bots.Bot, Long> WAITING = new LinkedHashMap<>();

    /**
     * Whether {@code p} may look this tick: {@link #LOOKS_PER_TICK} bots a tick, those that
     * waited longest first. With the looks given to whoever asked first in the tick, the bots
     * the server ticks first looked every time and the last never did: they walked past what
     * they were sent for and said they saw none.
     */
    static boolean mayLook(Bots.Bot p, long now) {
        if (now != looksTick) {
            looksTick = now;
            looks = 0;
            WAITING.values().removeIf(t -> t < now - 1);
        }
        int free = LOOKS_PER_TICK - looks;
        boolean turn = false;
        int i = 0;
        for (Bots.Bot q : WAITING.keySet()) {
            if (i++ >= free) break;
            if (q == p) {
                turn = true;
                break;
            }
        }
        if (!turn && WAITING.size() < free && !WAITING.containsKey(p)) turn = true;
        if (turn) {
            WAITING.remove(p);
            looks++;
            return true;
        }
        WAITING.put(p, now);
        return false;
    }

    /**
     * The blocks of those kinds around {@code at} ({@link #RADIUS} each way, {@link #DOWN}
     * down, {@link #UP} up), in the loaded chunks, as {@link BlockPos#asLong} numbers, read
     * from the chunk sections directly: a section with none of the kinds in its palette is
     * skipped whole. What is not loaded, it does not know of.
     */
    static LongArrayList sweep(ServerLevel level, BlockPos at, Set<Block> kinds) {
        LongArrayList out = new LongArrayList();
        int x0 = at.getX() - RADIUS, x1 = at.getX() + RADIUS, z0 = at.getZ() - RADIUS, z1 = at.getZ() + RADIUS;
        int y0 = Math.max(level.getMinBuildHeight(), at.getY() - DOWN), y1 = Math.min(level.getMaxBuildHeight() - 1, at.getY() + UP);
        for (int cx = x0 >> 4; cx <= x1 >> 4; cx++) {
            for (int cz = z0 >> 4; cz <= z1 >> 4; cz++) {
                LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
                if (chunk == null) continue;
                LevelChunkSection[] sections = chunk.getSections();
                for (int sy = y0 >> 4; sy <= y1 >> 4; sy++) {
                    int i = chunk.getSectionIndexFromSectionY(sy);
                    if (i < 0 || i >= sections.length) continue;
                    LevelChunkSection sec = sections[i];
                    if (sec.hasOnlyAir() || !sec.maybeHas(st -> kinds.contains(st.getBlock()))) continue;
                    int ax = Math.max(x0, cx << 4), bx = Math.min(x1, (cx << 4) + 15);
                    int az = Math.max(z0, cz << 4), bz = Math.min(z1, (cz << 4) + 15);
                    int ay = Math.max(y0, sy << 4), by = Math.min(y1, (sy << 4) + 15);
                    for (int y = ay; y <= by; y++) {
                        for (int x = ax; x <= bx; x++) {
                            for (int z = az; z <= bz; z++) {
                                if (kinds.contains(sec.getBlockState(x & 15, y & 15, z & 15).getBlock())) out.add(BlockPos.asLong(x, y, z));
                            }
                        }
                    }
                }
            }
        }
        return out;
    }

    /**
     * Whether the block grew there: a tree's log (its verdict in {@code trees}, from
     * {@link #partOfBuild}) or leaves nobody placed. What falls from a tree lands on the
     * ground it grew from, however high it hangs: chopped from the bottom up, a trunk hangs
     * over air, and "nothing under it" left all but its two lowest logs standing.
     */
    private static boolean grew(BlockState s, BlockPos pos, Map<BlockPos, Boolean> trees) {
        if (s.is(BlockTags.LOGS)) return Boolean.TRUE.equals(trees.get(pos));
        return grewLeaves(s);
    }

    /**
     * Whether what the block drops comes to rest where it is looked for: on something within
     * {@link #CATCH} blocks under it, or on water. Over a gap it falls out of reach: a player
     * does not break a block to watch what it drops fall into a canyon, and a bot that took
     * the bridge another bot had built over one (or it itself, to come) lost the drops and
     * the way back. Lava under it burns them.
     */
    static boolean caught(ServerLevel level, BlockPos pos) {
        for (int d = 1; d <= CATCH; d++) {
            BlockPos n = pos.below(d);
            BlockState s = level.getBlockState(n);
            if (!s.getFluidState().isEmpty()) return s.getFluidState().is(FluidTags.WATER);
            if (!s.getCollisionShape(level, n).isEmpty()) return true;
        }
        return false;
    }

    /** A face to the air (or to water, grass, a torch: nothing that fills the space): Masurium's "exposed". */
    static boolean exposed(ServerLevel level, BlockPos pos) {
        for (Direction d : Direction.values()) {
            BlockPos n = pos.relative(d);
            if (level.getBlockState(n).getCollisionShape(level, n).isEmpty()) return true;
        }
        return false;
    }

    /**
     * A face to the air under the open sky (the sky's full light, straight down, reaches that
     * space): out in the open, where a player walking by sees it. A cave's walls are not, nor
     * the floor of a house: some of the sky's light comes in by a door, a window, and "any sky
     * light" took a house's floor for open ground, so a gatherer outside opened the door and
     * dug it up. What is not in the open is taken only in its sight ({@link #inSight}).
     */
    private static boolean inTheOpen(ServerLevel level, BlockPos pos) {
        for (Direction d : Direction.values()) {
            BlockPos n = pos.relative(d);
            if (level.getBlockState(n).getCollisionShape(level, n).isEmpty()
                    && level.getBrightness(LightLayer.SKY, n) >= level.getMaxLightLevel()) {
                return true;
            }
        }
        return false;
    }

    /** How far up a tree's crown is followed, at most, to the open sky: the tallest trees are about 30 blocks. */
    private static final int CROWN_MAX = 32;

    /**
     * Whether a tree's log (or leaves) is seen by its crown: straight up from it, only logs
     * and leaves, to the open sky. A player sees a tree by its crown, and chops the trunk from
     * the bottom up, looking up it: a log inside the leaves, which no line from where the bot
     * stands reaches, is the tree it sees, and was left hanging. Not a tree under a roof, or
     * in a cave: that one it must see.
     */
    private static boolean crown(ServerLevel level, BlockPos pos) {
        BlockPos.MutableBlockPos at = pos.mutable();
        for (int i = 0; i < CROWN_MAX; i++) {
            at.move(Direction.UP);
            BlockState s = level.getBlockState(at);
            if (s.isAir()) return level.getBrightness(LightLayer.SKY, at) >= level.getMaxLightLevel();
            if (!s.is(BlockTags.LOGS) && !s.is(BlockTags.LEAVES)) return false;
        }
        return false;
    }

    /** Whether it sees the block from its eyes, where it stands ({@link #sees}). */
    private static boolean inSight(BotPlayer b, BlockPos pos) {
        return sees(b.level(), b.getEyePosition(), pos);
    }

    /**
     * Whether eyes at {@code eye} see a bit of the block at {@code pos}: its centre, or the
     * middle of a face turned to them with nothing in front of it; on the line, nothing one
     * collides with (a line through tall grass or a torch sees) but leaves that grew there,
     * which a player sees through and breaks in passing ({@link #inPassing}). Masurium's
     * whatIsInTheWay. Over the level from where the bot stands, or over a search's snapshot
     * from a tile it might stand on ({@link SnapshotWorld#sees}).
     */
    static boolean sees(BlockGetter world, Vec3 eye, BlockPos pos) {
        Vec3 centre = Vec3.atCenterOf(pos);
        if (line(world, eye, centre, pos)) return true;
        for (Direction d : Direction.values()) {
            BlockPos next = pos.relative(d);
            if (!world.getBlockState(next).getCollisionShape(world, next).isEmpty()) continue;
            Vec3 face = centre.add(d.getStepX() * 0.45, d.getStepY() * 0.45, d.getStepZ() * 0.45);
            if (face.subtract(centre).dot(eye.subtract(centre)) <= 0) continue;       // turned away
            if (line(world, eye, face, pos)) return true;
        }
        return false;
    }

    /** Whether the line from {@code from} to {@code to} reaches the block at {@code pos} first. */
    private static boolean line(BlockGetter world, Vec3 from, Vec3 to, BlockPos pos) {
        return BlockGetter.traverseBlocks(from, to, world, (w, at) -> {
            if (at.equals(pos)) return Boolean.TRUE;
            BlockState s = w.getBlockState(at);
            if (grewLeaves(s)) return null;
            VoxelShape shape = s.getCollisionShape(w, at);
            return shape.isEmpty() || shape.clip(from, to, at) == null ? null : Boolean.FALSE;
        }, w -> Boolean.TRUE);
    }

    /** Leaves that grew there (nobody placed them: they are not persistent). */
    static boolean grewLeaves(BlockState s) {
        return s.is(BlockTags.LEAVES) && s.hasProperty(LeavesBlock.PERSISTENT) && !s.getValue(LeavesBlock.PERSISTENT);
    }

    /**
     * Whether a block in front of what it goes for is one a player breaks in passing: leaves
     * that grew there, nobody's, where players may break. A spruce's leaves hang round its
     * trunk at head height, and the log behind them is chopped only once they are gone.
     */
    private boolean inPassing(BotPlayer b, BlockPos pos) {
        return grewLeaves(level.getBlockState(pos)) && !PlacedBlocks.byPlayer(level, pos) && level.mayInteract(b, pos);
    }

    /**
     * Whether the block is a piece of a build, though no player placed it that the server
     * knows of (a build older than the mod, a village): a log whose tree is none (see
     * {@link #tree}), or any other block that touches a building block or a block a player
     * placed. When in doubt, it is left: breaking a house is worse than walking on.
     */
    static boolean partOfBuild(ServerLevel level, PlacedBlocks.Places places, BlockPos pos, BlockState s,
                               Map<BlockPos, Boolean> trees) {
        if (s.is(BlockTags.LOGS)) {
            Boolean v = trees.get(pos);
            if (v == null) v = tree(level, places, pos, trees);
            return !v;
        }
        for (Direction d : Direction.values()) {
            BlockPos n = pos.relative(d);
            if (building(level.getBlockState(n)) || places.byPlayer(level, n)) return true;
        }
        return false;
    }

    /**
     * Whether a log is a tree's: the logs joined to it (up to {@link #TREE_MAX}) touch leaves
     * that grew there (not placed: a placed leaf is persistent) and no building block, and no
     * player placed one of them. The verdict holds for all of them, kept in {@code trees}.
     * Masurium's isTreePart, with the leaves' own word on whether they grew.
     */
    private static boolean tree(ServerLevel level, PlacedBlocks.Places places, BlockPos start, Map<BlockPos, Boolean> trees) {
        List<BlockPos> group = new ArrayList<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        Set<BlockPos> seen = new HashSet<>();
        queue.add(start);
        seen.add(start);
        boolean leaves = false, builtUp = false;
        while (!queue.isEmpty() && group.size() < TREE_MAX) {
            BlockPos pos = queue.poll();
            group.add(pos);
            if (places.byPlayer(level, pos)) builtUp = true;
            for (Direction d : Direction.values()) {
                BlockPos n = pos.relative(d);
                BlockState s = level.getBlockState(n);
                if (s.is(BlockTags.LOGS)) {
                    if (seen.add(n)) queue.add(n);
                } else if (s.is(BlockTags.LEAVES)) {
                    if (!s.hasProperty(LeavesBlock.PERSISTENT) || !s.getValue(LeavesBlock.PERSISTENT)) leaves = true;
                } else if (building(s) || places.byPlayer(level, n)) {
                    builtUp = true;
                }
            }
        }
        boolean tree = leaves && !builtUp;
        for (BlockPos pos : group) trees.put(pos, tree);
        return tree;
    }

    /**
     * A block players build with and nature does not make: planks, stairs, slabs, doors,
     * wool, carpets, fences, gates, walls, trapdoors, beds, signs, glass and panes, bricks,
     * torches, lanterns, chests, crafting tables, furnaces. Masurium's list, and a few more.
     */
    static boolean building(BlockState s) {
        Block k = s.getBlock();
        return s.is(BlockTags.PLANKS) || s.is(BlockTags.STAIRS) || s.is(BlockTags.SLABS) || s.is(BlockTags.DOORS)
                || s.is(BlockTags.WOOL) || s.is(BlockTags.WOOL_CARPETS) || s.is(BlockTags.FENCES)
                || s.is(BlockTags.FENCE_GATES) || s.is(BlockTags.WALLS) || s.is(BlockTags.TRAPDOORS) || s.is(BlockTags.BEDS)
                || s.is(BlockTags.ALL_SIGNS) || s.is(BlockTags.IMPERMEABLE) || k instanceof IronBarsBlock
                || k instanceof BaseTorchBlock || k instanceof LanternBlock || k instanceof ChestBlock
                || k instanceof CraftingTableBlock || k instanceof AbstractFurnaceBlock
                || k == Blocks.BRICKS || k == Blocks.STONE_BRICKS || k == Blocks.MOSSY_STONE_BRICKS
                || k == Blocks.CRACKED_STONE_BRICKS;
    }

    /** Ores, and what would be x-ray to look for from a list of blocks around (Masurium's rule): by id. */
    static boolean isOre(String id) {
        String path = id.substring(id.indexOf(':') + 1);
        return path.endsWith("_ore") || path.equals("ancient_debris") || path.equals("budding_amethyst")
                || path.equals("amethyst_cluster") || path.startsWith("raw_") && path.endsWith("_block");
    }

    /** A block's id as players say it: "dirt", and a mod's with its name. */
    static String name(Block k) {
        var key = BuiltInRegistries.BLOCK.getKey(k);
        return key.getNamespace().equals("minecraft") ? key.getPath() : key.toString();
    }

    // --- claims: several gatherers share what is around ---------------------------------------

    /** The block each gatherer goes for or breaks. The server's thread's; a claim is let go of as its job ends. */
    private static final Map<BlockPos, Bots.Bot> CLAIMED = new HashMap<>();

    /** Takes {@code pos} for {@code p}, unless another gatherer, still at it, has it. */
    private boolean claim(Bots.Bot p, BlockPos pos) {
        Bots.Bot other = CLAIMED.get(pos);
        if (other != null && other != p && other.job instanceof Gather g && pos.equals(g.goal) && g.level == level) return false;
        CLAIMED.put(pos, p);
        return true;
    }

    private static void release(Bots.Bot p, BlockPos pos) {
        if (pos != null) CLAIMED.remove(pos, p);
    }

    /** The block it goes for, given up for this errand: no way to it, hidden, or refused. */
    private void giveUp(Bots.Bot p) {
        tried.add(goal);
        release(p, goal);
        goal = null;
    }

    // --- the hands -------------------------------------------------------------------------------

    /**
     * The first stroke: the tool that makes it drop what it is, the fastest of those, in
     * hand; none that speeds it up (grass, a flower), a hand that wears nothing, since a tool
     * spends a use on every block whether it helps or not (Masurium's pickaxe spent 250 on
     * grass).
     */
    private void start(Bots.Bot p, BlockPos at, Direction side, boolean inPassing) {
        BotPlayer b = p.body;
        BlockState s = level.getBlockState(at);
        hold(p, stack -> score(stack, s));
        Bots.release(b);
        b.lookAt(EntityAnchorArgument.Anchor.EYES, Vec3.atCenterOf(at));
        breaking = at;
        passing = inPassing;
        face = side;
        breakTicks = 0;
        doneAt = -1;
        held = b.getMainHandItem();
        heldNothing = held.isEmpty();
        b.swing(InteractionHand.MAIN_HAND);
        action(b, breaking, ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK);
    }

    /** How good an item is for breaking {@code s}: one that makes it drop first, then the fastest, then one that does not wear. */
    static double score(ItemStack stack, BlockState s) {
        float speed = stack.getDestroySpeed(s);
        double v = (!s.requiresCorrectToolForDrops() || stack.isCorrectToolForDrops(s) ? 100 : 0) + speed;
        return speed <= 1.0f && stack.isDamageableItem() ? v - 0.5 : v;
    }

    @Override
    void act(Bots.Bot p) {
        BotPlayer b = p.body;
        if (breaking == null) {
            if (loot > 0 && lying != null && lying.isAlive() && p.path == null && p.pending == null) {
                // To the middle of the item's block, not the item: what dropped into the hole the
                // block left lies below the reach of a player's pickup (half a block under the
                // feet), and a body 0.6 wide at the hole's rim, aiming at an item off its middle,
                // never drops in.
                BlockPos at = lying.blockPosition();
                walkStraight(p, new Vec3(at.getX() + 0.5, lying.getY(), at.getZ() + 0.5));
            }
            return;
        }
        BlockState s = level.getBlockState(breaking);
        if (passing ? !grewLeaves(s) : !kinds.contains(s.getBlock())) {           // broken
            if (passing) {
                passing = false;
                breaking = null;
            } else {
                brokeIt(p);
            }
            return;
        }
        if (!b.canInteractWithBlock(breaking, 1.0) || ++breakTicks > BREAK_MAX) {
            // Pushed away, or it will not give way: the stroke is dropped.
            action(b, breaking, ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK);
            if (breakTicks > BREAK_MAX) {
                refused++;
                tried.add(breaking);
                release(p, breaking);
                if (passing && goal != null) giveUp(p);
                goal = null;
            }
            breaking = null;
            passing = false;
            return;
        }
        if (b.getMainHandItem() != held || held.isEmpty() != heldNothing) {
            // Something else in its hand since the stroke began (a reflex's bite, the tool worn
            // out): the server weighs the stroke with what is in the hand when it ends, so it
            // starts again, with the best tool, on its next think.
            action(b, breaking, ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK);
            breaking = null;
            passing = false;
            return;
        }
        Bots.release(b);
        b.lookAt(EntityAnchorArgument.Anchor.EYES, Vec3.atCenterOf(breaking));
        if (breakTicks % 4 == 0) b.swing(InteractionHand.MAIN_HAND);
        if (doneAt < 0 && s.getDestroyProgress(b, level, breaking) * breakTicks >= 1.0f) {
            action(b, breaking, ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK);
            doneAt = breakTicks;
        } else if (doneAt >= 0 && breakTicks - doneAt > REFUSED_TICKS) {
            // Its time was up and the server did not break it: a protection, an event
            // cancelled. It is left, and not tried again this errand.
            action(b, breaking, ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK);
            refused++;
            tried.add(breaking);
            release(p, breaking);
            if (passing && goal != null) giveUp(p);
            breaking = null;
            passing = false;
            goal = null;
        }
    }

    /** The block it was breaking is gone: counted, and what it dropped picked up next. */
    private void brokeIt(Bots.Bot p) {
        broken++;
        lootAt = breaking;
        lootSince = p.body.getServer().getTickCount();
        loot = LOOT_TICKS;
        lying = null;
        release(p, breaking);
        breaking = null;
        goal = null;
    }

    /**
     * It ends, or is set aside (a reflex took the body). A block its last stroke broke is
     * counted here, as Clear counts it; one half broken is let go of, and its claim too.
     */
    @Override
    void end(Bots.Bot p) {
        if (breaking != null && !passing && level != null && !kinds.contains(level.getBlockState(breaking).getBlock())) {
            brokeIt(p);
        } else if (breaking != null) {
            action(p.body, breaking, ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK);
        }
        release(p, breaking);
        release(p, goal);
        breaking = null;
        passing = false;
        goal = null;
        lying = null;
    }

    /** What a client's packet would tell the server's game mode. */
    private void action(BotPlayer b, BlockPos pos, ServerboundPlayerActionPacket.Action what) {
        b.gameMode.handleBlockBreakAction(pos, what, face, level.getMaxBuildHeight(), 0);
    }

    // --- picking up ----------------------------------------------------------------------------

    /**
     * What the last block dropped, walked to (a player picks up what it touches): the items
     * lying by it that came since it broke, that fit and that it did not toss. One it stands
     * by for 2 s without it going in, or has no way to, is left. @return whether it is still at it
     */
    private boolean pickUp(Bots.Bot p, long now) {
        BotPlayer b = p.body;
        if (lying != null && !lying.isAlive()) {
            lying = null;
            byItem = 0;
        }
        if (lying != null && b.distanceTo(lying) < 1.5 && ++byItem > BY_ITEM_TICKS) {
            leftOnGround.add(lying.getUUID());
            lying = null;
            byItem = 0;
        }
        if (awaitingItem && p.pending == null) {
            awaitingItem = false;
            if (lying != null && (p.searchFailed || p.path == null) && b.distanceTo(lying) > CLOSE_IN) {
                leftOnGround.add(lying.getUUID());
                lying = null;
            }
        }
        if (lying == null) {
            byItem = 0;
            long age = now - lootSince + 5;
            double best = Double.MAX_VALUE;
            for (ItemEntity e : level.getEntitiesOfClass(ItemEntity.class, new AABB(lootAt).inflate(LOOT_RADIUS),
                    e -> e.isAlive() && e.getAge() <= age && !leftOnGround.contains(e.getUUID()))) {
                Entity thrower = e.getOwner();
                if (!Gear.fits(b.getInventory(), e.getItem()) || thrower != null && thrower.getUUID().equals(b.getUUID())) continue;
                double d = e.distanceToSqr(b);
                if (d < best) {
                    best = d;
                    lying = e;
                }
            }
            if (lying == null) return false;
        }
        if (b.distanceTo(lying) > CLOSE_IN && p.pending == null && p.path == null && now - p.plannedAt >= Bots.REPLAN_TICKS) {
            p.plannedAt = now;
            awaitingItem = true;
            Bots.plan(p, lying.blockPosition(), 1.0, Hunt.chase(b), status());
            Bots.arriveWithin(p, Bots.ON_ITEM);
        }
        return true;
    }

    /** Kinds in words, in order: "dirt, grass_block". */
    static String names(Set<Block> kinds) {
        List<String> out = new ArrayList<>();
        for (Block k : kinds) out.add(name(k));
        out.sort(null);
        return String.join(", ", out);
    }
}
