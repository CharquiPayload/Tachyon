package tachyon;

import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import java.util.function.ToDoubleFunction;

/**
 * What a bot does beyond walking, a tick at a time.
 *
 * <p>Before the walk, {@link #think} decides where to walk (a search, through
 * {@link Bots#plan}); after the walk has pressed its keys, {@link #act} does what the
 * hands do, and may take the keys over for the last steps, straight at what is near.
 *
 * <p>A job may be set aside for a while and taken up again: while a reflex holds the
 * body ({@link Bots#takeOver}), or while a detour is walked from a standing order
 * ({@link Bots#orderStanding}). Set aside, it is told {@link #end}, and lets go of what
 * its hands did; taken up again, it thinks again from its fields, as they were.
 */
abstract class Job {

    /** Before the walk. False when the job is over; it has said why, in {@code doing}. */
    abstract boolean think(Bots.Bot p, long now);

    /** What it is at, for {@code list}: shown while the job lasts, over the walk's own words. */
    abstract String status();

    /** After the walk's keys: the hands, and the last steps. */
    void act(Bots.Bot p) {
    }

    /**
     * It ends, however, or it is set aside for a while: what the hands were doing is let
     * go (a block half broken, a claim on a shared area, a target). What it counted stays:
     * a job taken up again goes on from there, and finds its target again.
     */
    void end(Bots.Bot p) {
    }

    /**
     * The best in the inventory by {@code score} into the hand, as {@link #wield} puts it
     * there; but not while a reflex holds the hands ({@link Bots#holdHands}): a bite or a
     * bow drawn is not swapped for a pickaxe.
     */
    static void hold(Bots.Bot p, ToDoubleFunction<ItemStack> score) {
        if (!Bots.handsFree(p)) return;
        wield(p, score);
    }

    /**
     * {@link #hold}, saying whether what is in the hand changed. A hit waits for the next
     * tick then, as a player's must: the game starts the new item's charge again, and gives
     * the hand its damage, in the body's own tick, after this one's hands; hit now, it would
     * land with the old item's damage and charge.
     */
    static boolean swapped(Bots.Bot p, ToDoubleFunction<ItemStack> score) {
        ItemStack before = p.body.getMainHandItem();
        hold(p, score);
        return p.body.getMainHandItem() != before;
    }

    /**
     * The best in the inventory by {@code score} into the hand: selected if it is in the
     * hotbar, brought up from the backpack if it is not, into the hotbar slot Masurium's
     * rule gives up ({@link Gear#toHand}). Nothing better than what is held, nothing
     * changes. For whoever has the hands: a job through {@link #hold}, a reflex that holds
     * them directly.
     */
    static void wield(Bots.Bot p, ToDoubleFunction<ItemStack> score) {
        Inventory inv = p.body.getInventory();
        int best = inv.selected;
        double bestScore = score.applyAsDouble(inv.getItem(best));
        for (int i = 0; i < inv.items.size(); i++) {
            double s = score.applyAsDouble(inv.getItem(i));
            if (s > bestScore) {
                bestScore = s;
                best = i;
            }
        }
        Gear.toHand(p, best);
    }

    /** A step straight ahead falls this far at most: past it, a player takes damage. */
    private static final int SAFE_DROP = 3;

    /**
     * The last steps, straight at {@code to}: forward, and a jump when something is in the
     * way. Not over an edge with a longer drop than {@link #SAFE_DROP}, nor into lava:
     * then it stands, and says so with false (a route knows the way round).
     */
    static boolean walkStraight(Bots.Bot p, Vec3 to) {
        BotPlayer b = p.body;
        double dx = to.x - b.getX(), dz = to.z - b.getZ();
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len > 0.01 && !safeStep(b, b.getX() + dx / len * 0.8, b.getZ() + dz / len * 0.8)) {
            Bots.release(b);
            return false;
        }
        float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        b.setYRot(yaw);
        b.setYHeadRot(yaw);
        b.zza = 1.0f;
        b.xxa = 0;
        b.setSprinting(false);
        b.setJumping(b.horizontalCollision && b.onGround() || b.isInWater());
        return true;
    }

    /** Ground within {@link #SAFE_DROP} under (x, z), or water, and no lava down there. */
    private static boolean safeStep(BotPlayer b, double x, double z) {
        var level = b.level();
        int feet = (int) Math.floor(b.getY());
        for (int dy = 1; dy >= -SAFE_DROP - 1; dy--) {
            BlockPos at = BlockPos.containing(x, feet + dy, z);
            var state = level.getBlockState(at);
            if (state.getFluidState().is(FluidTags.LAVA)) return false;
            if (state.getFluidState().is(FluidTags.WATER)) return true;
            if (dy <= 0 && !state.getCollisionShape(level, at).isEmpty()) return true;
        }
        return false;
    }
}
