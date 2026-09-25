package tachyon.mixin;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.SleepStatus;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import tachyon.Sleeping;

import java.util.List;

/**
 * The count of sleepers without the bots that do not count ({@link Sleeping}).
 *
 * <p>A level counts its players again ({@code SleepStatus.update}) whenever one comes in,
 * leaves, lies down or gets up: every one that is not a spectator is a player, and those
 * in a bed are sleepers. The night is skipped once the sleepers are the game rule's
 * percentage of the players ({@code playersSleepingPercentage}), and the "n/m players
 * sleeping" line says those two numbers. No NeoForge event lets a player out of that
 * count, so the list it is taken from is the one thing changed: the same players, without
 * the bots that do not count.
 *
 * <p>The night is skipped once enough sleepers have slept long enough
 * ({@code areEnoughDeepSleeping}, asked every tick while enough are asleep, over the
 * level's own list): without the same bots there too, a bot left out of the count that
 * lay in a bed would still be counted as a sleeper.
 */
@Mixin(SleepStatus.class)
public abstract class SleepStatusMixin {

    @ModifyVariable(method = "update", at = @At("HEAD"), argsOnly = true)
    private List<ServerPlayer> tachyon$withoutBots(List<ServerPlayer> players) {
        return Sleeping.counted(players);
    }

    @ModifyVariable(method = "areEnoughDeepSleeping", at = @At("HEAD"), argsOnly = true)
    private List<ServerPlayer> tachyon$deepWithoutBots(List<ServerPlayer> players) {
        return Sleeping.counted(players);
    }
}
