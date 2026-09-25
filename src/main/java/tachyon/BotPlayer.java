package tachyon;

import com.mojang.authlib.GameProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.food.FoodData;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.portal.DimensionTransition;
import net.minecraft.world.phys.Vec3;

/**
 * A player nobody plays, living on the server.
 *
 * <p>A real player's body ticks twice over: the level ticks the player
 * ({@link ServerPlayer#tick}), and its connection ticks its body ({@code doTick}: the
 * physics, the food, the attacks) when its client's packets are handled. A bot's
 * connection is never ticked, since nobody sends it anything, so the body is ticked
 * here, as Carpet's fake players do. Its physics are then a player's own, run by the
 * server: gravity, collisions, the step up, water, falls. What moves it is what moves a
 * player, keys: {@link #pilot} presses them (forward, jump, sprint) before each tick.
 */
final class BotPlayer extends ServerPlayer {

    /** Presses the keys for the tick about to run; null = none pressed. */
    Runnable pilot;
    /**
     * The bot this is the body of, while it is (null once it left, or once a respawn gave
     * the bot a new body): see {@link Bots#of}.
     */
    Bots.Bot bot;

    BotPlayer(MinecraftServer server, ServerLevel level, GameProfile profile) {
        super(server, level, profile, ClientInformation.createDefault());
    }

    @Override
    public void tick() {
        long started = System.nanoTime();
        // Every half second, what a moving client would have made the server do: its
        // position taken as good, and the chunks around it loaded where it now is.
        if (getServer() != null && getServer().getTickCount() % 10 == 0) {
            connection.resetPosition();
            serverLevel().getChunkSource().move(this);
        }
        if (pilot != null) pilot.run();
        // Using an item (a bite, a bow drawn), a player walks at a fifth of its pace and
        // cannot sprint: its own client slows its keys (LocalPlayer.aiStep), and the server,
        // which trusts the client's movement, never does. A bot has no client: the same, here.
        if (isUsingItem() && !isPassenger()) {
            zza *= 0.2f;
            xxa *= 0.2f;
            setSprinting(false);
        }
        super.tick();
        doTick();
        Bots.ticked(System.nanoTime() - started);
    }

    /**
     * A real player's falls are judged from what its client reports; a server-side
     * player's leave that empty, so without this it would never take fall damage.
     */
    @Override
    protected void checkFallDamage(double y, boolean onGround, BlockState state, BlockPos pos) {
        doCheckFallDamage(0.0, y, 0.0, onGround);
    }

    /**
     * Its player save, read as it comes in, before it is in the level. A name that died and
     * left (respawn false, too many deaths, a crash while it lay dead) saved a dead body, and
     * a bot has no death screen to press "respawn" on: it comes back as a respawn brings a
     * player, in the state a respawn's new body starts in. Health, food and air full; no
     * effects, fire, frost, fall or speed; its levels gone, dropped as orbs where it died,
     * unless keepInventory kept them (as {@code ServerPlayer.restoreFrom} decides). Here and
     * not once it is in: the level tells every client of a player as it goes in, and one
     * told of it at health 0 counts its {@code deathTime} up and draws it lying down for as
     * long as it is in sight, whatever health it is told of after.
     *
     * <p>The effects are taken off by hand, not with {@code removeAllEffects}: that tells the
     * player's client of each, through a connection it does not have yet. An effect's
     * attribute bonus (Speed's, Health Boost's) is saved with the attributes and read back
     * with them, so it is taken off too, before the health is counted.
     */
    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        if (isDeadOrDying()) {
            for (MobEffectInstance e : getActiveEffectsMap().values()) {
                e.getEffect().value().removeAttributeModifiers(getAttributes());
            }
            getActiveEffectsMap().clear();
            setAbsorptionAmount(0);
            setHealth(getMaxHealth());
            deathTime = 0;
            clearFire();
            setTicksFrozen(0);
            foodData = new FoodData();
            setAirSupply(getMaxAirSupply());
            resetFallDistance();
            setDeltaMovement(Vec3.ZERO);
            if (!level().getGameRules().getBoolean(GameRules.RULE_KEEPINVENTORY)) {
                experienceLevel = 0;
                totalExperience = 0;
                experienceProgress = 0;
                setScore(0);
            }
        }
    }

    /**
     * Its body into another dimension (a portal, a teleport there, a spawn in another one
     * than its save's), then told it arrived. The server takes a player that changes
     * dimension for invulnerable to everything until its client acknowledges the teleport
     * ({@code isChangingDimension}, cleared only by that packet's handler), and a bot's
     * client never will: without this, a bot that ever changed dimension could not die,
     * nor go through a portal again. Carpet's fake players do the same.
     */
    @Override
    public Entity changeDimension(DimensionTransition to) {
        Entity arrived = super.changeDimension(to);
        if (isChangingDimension()) hasChangedDimension();
        return arrived;
    }

    /**
     * Its death: the player's own first (the message in the chat, the items dropped), then
     * Bots' (it comes back, or leaves). The message is taken before: the player's death
     * clears what its combat tracker knew, and after it every death reads "died".
     */
    @Override
    public void die(DamageSource cause) {
        Component message = getCombatTracker().getDeathMessage();
        super.die(cause);
        // A mod that cancels a death (NeoForge's LivingDeathEvent) leaves it alive.
        if (isDeadOrDying()) Bots.died(this, cause, message);
    }
}
