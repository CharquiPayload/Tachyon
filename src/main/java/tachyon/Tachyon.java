package tachyon;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;

/**
 * Tachyon: bots that live on the server. Only a dedicated server constructs it; on a
 * player's own game it does nothing, and players need nothing to join a server that
 * runs it: the bots are players to them.
 */
@Mod(value = Tachyon.ID, dist = Dist.DEDICATED_SERVER)
public final class Tachyon {

    public static final String ID = "tachyon";

    public Tachyon() {
        NeoForge.EVENT_BUS.register(new Bots());
    }
}
