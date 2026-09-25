package tachyon.mixin;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The player list's own lists, for a bot's respawn (Bots.respawn), which does what
 * {@code PlayerList.respawn} does with a body of its own: the old body out of them, the
 * new one in. NeoForge's {@code getPlayers()} is a view that cannot be changed, and the
 * players by UUID are private.
 */
@Mixin(PlayerList.class)
public interface PlayerListAccess {

    /** Every player in the game, in the order they came in: the live list. */
    @Accessor("players")
    List<ServerPlayer> tachyon$players();

    /** The same players by UUID: {@code getPlayer(uuid)} reads it. */
    @Accessor("playersByUUID")
    Map<UUID, ServerPlayer> tachyon$playersByUUID();
}
