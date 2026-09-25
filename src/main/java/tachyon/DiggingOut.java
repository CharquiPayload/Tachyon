package tachyon;

import com.mojang.logging.LogUtils;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.slf4j.Logger;

import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Digging itself out when buried: sand or gravel fell on it, and its head is inside a
 * block, where the game takes a point of health every half second. It breaks what covers
 * its head, then what fills the space of its feet, before anything else (even a creeper:
 * {@link #URGENCY}), as a player would: with its hands, the best tool it carries for the
 * block, the time the block takes with it, from where it stands. Sand falling again into
 * the space it opened is broken again, as it comes. Masurium's, which it had after a strip
 * mine ended in "I cannot see it: there is gravel in the way" while the bot suffocated.
 *
 * <p>It starts when the game hurts it for being inside a block (that damage is the only
 * sign it waits for, so it costs nothing until then) and ends when neither its head nor
 * its feet are in a block that suffocates; then what it was doing goes on. A block it
 * cannot break (bedrock, a protected spawn) or cannot break in 30 s is given up, and its
 * owner told: there is nothing left it can do.
 */
final class DiggingOut implements Ability {

    private static final Logger LOG = LogUtils.getLogger();

    /** How urgent its hold on the body is: over everything, coming up for air too. */
    static final int URGENCY = 60;
    /** A block that takes longer than this to break (30 s) is given up. */
    static final int BREAK_MAX = 20 * 30;
    /** After giving up, being inside a block starts nothing for this long: 30 s. */
    static final int GIVEN_UP_TICKS = 20 * 30;

    /** A bot digging itself out: what it breaks, for how long, and how many it broke. */
    private static final class Dig {
        final long since;
        BlockPos lid, breaking;
        Direction face = Direction.UP;
        int ticks, broken;

        Dig(long since) {
            this.since = since;
        }
    }

    /** The bots digging themselves out; and those that gave up, until when. Empty nearly always. */
    private static final Map<Bots.Bot, Dig> DIGS = new IdentityHashMap<>();
    private static final Map<Bots.Bot, Long> GAVE_UP = new IdentityHashMap<>();

    /** Hurt for being inside a block: it digs itself out. */
    @Override
    public void hurt(Bots.Bot p, DamageSource source, float amount) {
        if (!source.is(DamageTypes.IN_WALL) || DIGS.containsKey(p)) return;
        long now = p.body.getServer().getTickCount();
        Long until = GAVE_UP.get(p);
        if (until != null && now < until) return;
        GAVE_UP.remove(p);
        DIGS.put(p, new Dig(now));
        BlockPos lid = lid(p.body);
        LOG.info("[tachyon] {} is buried{} at {}: it digs itself out", p.name(),
                lid == null ? "" : " in " + block(p.body.level().getBlockState(lid)), Brain.pos(p.body.blockPosition()));
    }

    @Override
    public void tick(Bots.Bot p, long now) {
        if (DIGS.isEmpty()) return;
        Dig d = DIGS.get(p);
        if (d == null) return;
        BotPlayer b = p.body;
        broken(b, d);
        d.lid = lid(b);
        if (d.lid == null) {
            DIGS.remove(p);
            abort(p, d);
            Bots.freeHands(p, this);
            if (d.broken == 0) {
                // A block at its head only: the body crawls under it (the game's own pose), and breathes.
                LOG.info("[tachyon] {} is not buried any more: nothing broken", p.name());
            } else {
                LOG.info("[tachyon] {} dug itself out: {} block{} broken in {} s", p.name(), d.broken, d.broken == 1 ? "" : "s",
                        Math.round((now - d.since) / 20.0));
            }
            Bots.giveBack(p, this);
            return;
        }
        String doing = "digging itself out of " + block(b.level().getBlockState(d.lid));
        if (!Bots.takeOver(p, this, URGENCY, doing)) return;
        p.doing = doing;
        if (d.breaking != null && !d.breaking.equals(d.lid)) abort(p, d);      // another block first now
    }

    /** The hands on the block: the best tool it carries for it, the stroke started, and finished when its time is up. */
    @Override
    public void act(Bots.Bot p, long now) {
        if (DIGS.isEmpty()) return;
        Dig d = DIGS.get(p);
        if (d == null || d.lid == null || Bots.holding(p) != this) return;
        BotPlayer b = p.body;
        Bots.release(b);
        if (!Bots.holdHands(p, this, 5)) return;           // a bite being swallowed: a moment
        ServerLevel level = b.serverLevel();
        BlockState s = level.getBlockState(d.lid);
        if (d.breaking == null) {
            if (s.getDestroySpeed(level, d.lid) < 0) {
                giveUp(p, d, block(s) + " cannot be broken");
                return;
            }
            if (!level.mayInteract(b, d.lid)) {
                giveUp(p, d, "it may not break blocks there (the spawn's protection, or the world's border)");
                return;
            }
            Job.wield(p, stack -> stack.getDestroySpeed(s) + (stack.isCorrectToolForDrops(s) ? 0.5 : 0));
            d.breaking = d.lid;
            d.ticks = 0;
            d.face = Direction.getNearest(b.getEyePosition().subtract(Vec3.atCenterOf(d.lid)));
            b.lookAt(EntityAnchorArgument.Anchor.EYES, Vec3.atCenterOf(d.lid));
            b.swing(InteractionHand.MAIN_HAND);
            action(b, d, ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK);
            return;
        }
        if (broken(b, d)) return;
        if (++d.ticks > BREAK_MAX) {
            giveUp(p, d, "breaking " + block(s) + " took more than " + BREAK_MAX / 20 + " s");
            return;
        }
        b.lookAt(EntityAnchorArgument.Anchor.EYES, Vec3.atCenterOf(d.breaking));
        if (d.ticks % 4 == 0) b.swing(InteractionHand.MAIN_HAND);
        if (s.getDestroyProgress(b, level, d.breaking) * d.ticks >= 1.0f) {
            action(b, d, ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK);
        }
    }

    /**
     * Whether the block it was breaking is gone (counted, and the stroke over): a new block
     * fallen into the same space is another stroke from the start, with its own time.
     */
    private static boolean broken(BotPlayer b, Dig d) {
        if (d.breaking == null) return false;
        BlockState s = b.level().getBlockState(d.breaking);
        if (!s.isAir() && s.isSuffocating(b.level(), d.breaking)) return false;
        d.broken++;
        d.breaking = null;
        return true;
    }

    /** Nothing left it can do: its owner hears of it, and it is left as it is for 30 s. */
    private void giveUp(Bots.Bot p, Dig d, String why) {
        BotPlayer b = p.body;
        abort(p, d);
        DIGS.remove(p);
        GAVE_UP.put(p, (long) b.getServer().getTickCount() + GIVEN_UP_TICKS);
        LOG.info("[tachyon] {} cannot dig itself out at {}: {}", p.name(), Brain.pos(b.blockPosition()), why);
        Notices.say(p, "buried", "is buried at " + Brain.pos(b.blockPosition()) + " and cannot dig itself out: " + why);
        Bots.freeHands(p, this);
        Bots.giveBack(p, this);
    }

    private static void abort(Bots.Bot p, Dig d) {
        if (d.breaking != null) action(p.body, d, ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK);
        d.breaking = null;
    }

    /** What a client's packet would tell the server's game mode. */
    private static void action(BotPlayer b, Dig d, ServerboundPlayerActionPacket.Action what) {
        b.gameMode.handleBlockBreakAction(d.breaking, what, d.face, b.level().getMaxBuildHeight(), 0);
    }

    @Override
    public void died(Bots.Bot p, DamageSource cause) {
        DIGS.remove(p);
        GAVE_UP.remove(p);
    }

    @Override
    public void left(Bots.Bot p) {
        DIGS.remove(p);
        GAVE_UP.remove(p);
    }

    /**
     * The block to break: of those around its eyes that suffocate (what the game hurts it
     * for, as {@code Entity.isInWall} tells), the nearest; else one that suffocates in its
     * body, where its feet are (standing on soul sand is not in it). Null: it is out.
     */
    static BlockPos lid(BotPlayer b) {
        float w = b.getBbWidth() * 0.8f;
        BlockPos head = inside(b, AABB.ofSize(b.getEyePosition(), w, 1.0E-6, w));
        return head != null ? head : inside(b, b.getBoundingBox().deflate(1.0E-3));
    }

    private static BlockPos inside(BotPlayer b, AABB box) {
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        VoxelShape in = Shapes.create(box);
        Vec3 eyes = b.getEyePosition();
        for (BlockPos pos : BlockPos.betweenClosed(BlockPos.containing(box.minX, box.minY, box.minZ),
                BlockPos.containing(box.maxX, box.maxY, box.maxZ))) {
            BlockState s = b.level().getBlockState(pos);
            if (s.isAir() || !s.isSuffocating(b.level(), pos)) continue;
            VoxelShape shape = s.getCollisionShape(b.level(), pos).move(pos.getX(), pos.getY(), pos.getZ());
            if (!Shapes.joinIsNotEmpty(shape, in, BooleanOp.AND)) continue;
            double d = pos.distToCenterSqr(eyes);
            if (d < bestD) {
                bestD = d;
                best = pos.immutable();
            }
        }
        return best;
    }

    /** A block's id as players say it: "sand", "gravel". */
    private static String block(BlockState s) {
        return BuiltInRegistries.BLOCK.getKey(s.getBlock()).getPath();
    }
}
