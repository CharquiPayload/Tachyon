package tachyon;

import com.mojang.logging.LogUtils;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Coming back after a restart. The bots in the game when the server stops are recorded
 * in its {@link Roster} (name, dimension, position, rotation); once it has started again,
 * each one whose {@code come_back} is true comes back where it was, with its owner from
 * its data, as a spawn from the console brings a bot in. A bot removed before the stop is
 * not in the game then, and is not recorded. One whose dimension is gone (a mod's,
 * removed) comes back at the world's spawn.
 *
 * <p>The roster is written once the server has stopped, from what was recorded as each
 * bot left: at a stop, and after a crash too, when the bots leave as the server has stopped
 * (Bots.onStopped, which runs before {@link #record}). It is read once the server has
 * started, and left as it is, so that a server killed outright, where nothing more runs,
 * brings back the bots of its last stop.
 */
final class Returning implements Ability {

    private static final Logger LOG = LogUtils.getLogger();

    /** Whether it comes back by itself when the server starts again. */
    static final String COME_BACK = "come_back";

    /** The bots that left as the server stopped, as they stood. The server's thread's. */
    private final List<Roster.Entry> stopping = new ArrayList<>();
    /**
     * The server started, and read its roster. A server that fails as it starts stops
     * too, with nobody in it: that is no roster to write over the one it did not read.
     */
    private boolean started;

    @Override
    public void events(IEventBus bus) {
        bus.addListener(ServerStartedEvent.class, e -> bringBack(e.getServer()));
        bus.addListener(ServerStoppedEvent.class, e -> record(e.getServer()));
    }

    @Override
    public void settings(Settings settings) {
        settings.bool(COME_BACK, true, "When the server starts again, it comes back by itself, where it was.",
                Settings.Who.OWNER).label("Come back after a restart").group("Life").basic();
    }

    /**
     * A bot leaving as the server stops, or once it has crashed: where it stands, for the
     * roster. (At a stop a dead one came back first: see Bots.onStopping. After a crash it
     * is recorded where its body lies, and comes back whole: see BotPlayer.)
     */
    @Override
    public void left(Bots.Bot p) {
        if (p.leaving() != Bots.Leaving.STOPPING) return;
        BotPlayer b = p.body;
        stopping.add(new Roster.Entry(p.name(), b.level().dimension().location().toString(),
                b.getX(), b.getY(), b.getZ(), b.getYRot(), b.getXRot()));
    }

    /**
     * Once the server has stopped, every bot gone (after a crash too: Bots.onStopped runs
     * first): the roster written, here and now, since nothing runs after.
     */
    private void record(MinecraftServer server) {
        if (!started) return;
        started = false;
        Path file = Roster.file(server);
        try {
            Roster.write(file, stopping);
            LOG.info("[tachyon] {} bot(s) recorded in {}, to come back when the server starts", stopping.size(), file);
        } catch (IOException | RuntimeException e) {
            LOG.warn("[tachyon] could not write {}: the bots that were in the game do not come back", file, e);
        }
        stopping.clear();
    }

    /** Once the server has started: the bots of the roster whose come_back is true, back in the game. */
    private void bringBack(MinecraftServer server) {
        started = true;
        for (Roster.Entry e : Roster.read(Roster.file(server))) {
            // One that cannot come back is said, and the rest come: never a server that
            // does not start over a bot.
            try {
                bringBack(server, e);
            } catch (RuntimeException failed) {
                LOG.error("[tachyon] bot {} of the roster could not come back", e.name(), failed);
            }
        }
    }

    private static void bringBack(MinecraftServer server, Roster.Entry e) {
        String refused = Bots.refusal(server, e.name());
        if (refused != null) {
            LOG.warn("[tachyon] bot {} of the roster does not come back: {}", e.name(), refused);
            return;
        }
        // Its setting, from its data, before it is in (read again as it comes in: a few
        // hundred bytes).
        BotData data = BotData.load(BotData.folder(server), e.name());
        if (Abilities.settings().value(data, COME_BACK) == 0) {
            LOG.info("[tachyon] bot {} stays out: its come_back is false", e.name());
            return;
        }
        ResourceLocation id = ResourceLocation.tryParse(e.dimension());
        ServerLevel level = id == null ? null : server.getLevel(ResourceKey.create(Registries.DIMENSION, id));
        if (level != null) {
            Bots.Bot p = Bots.bringIn(server, level, e.name(), new Vec3(e.x(), e.y(), e.z()), e.yaw(), null, "came back");
            p.body.setXRot(e.pitch());
        } else {
            ServerLevel overworld = server.overworld();
            Bots.bringIn(server, overworld, e.name(), Vec3.atBottomCenterOf(overworld.getSharedSpawnPos()),
                    overworld.getSharedSpawnAngle(), null,
                    "came back to the world's spawn (its dimension, " + e.dimension() + ", is gone)");
        }
    }
}
