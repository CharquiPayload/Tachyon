package tachyon;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The bots that were in the game when the server last stopped, and where each stood:
 * {@code <world>/tachyon/roster.json}, for {@link Returning} to bring them back as it
 * starts again. In the world save, so that a copied world keeps it and another world
 * knows nothing of it.
 *
 * <pre>
 * { "bots": [ { "name": "Ada", "dimension": "minecraft:overworld",
 *               "x": -99.5, "y": 100.0, "z": -29.5, "yaw": 90.0, "pitch": 0.0 } ] }
 * </pre>
 *
 * <p>Written whole or not at all, as a bot's data is ({@link BotData#write}). A file that
 * is broken is moved aside, as {@code roster.json.bad-<time>}, and nobody comes back; an
 * entry that is broken (edited by hand: no name, a number that is none) is left out, said,
 * and the rest come back.
 */
final class Roster {

    private static final Logger LOG = LogUtils.getLogger();

    /** One bot as it stood when the server stopped: where, and which way it looked. */
    record Entry(String name, String dimension, double x, double y, double z, float yaw, float pitch) {
    }

    private Roster() {
    }

    static Path file(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT).resolve("tachyon").resolve("roster.json").normalize();
    }

    /**
     * The bots the file holds, in the order they were written: none when there is no file,
     * or it cannot be read or is broken (both said in the log; a broken one moved aside).
     */
    static List<Entry> read(Path file) {
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(file);
        } catch (NoSuchFileException e) {
            return List.of();
        } catch (IOException e) {
            LOG.warn("[tachyon] {} is there but could not be read ({}): no bot comes back", file, e.toString());
            return List.of();
        }
        JsonElement bots;
        try {
            bots = BotData.parse(bytes).get("bots");
            if (bots == null || !bots.isJsonArray()) throw new IllegalStateException("no list of bots in it");
        } catch (CharacterCodingException | RuntimeException e) {
            BotData.setAside(file, e.getMessage(), "no bot comes back");
            return List.of();
        }
        List<Entry> out = new ArrayList<>();
        for (JsonElement e : bots.getAsJsonArray()) {
            try {
                JsonObject o = e.getAsJsonObject();
                out.add(new Entry(o.get("name").getAsString(), o.get("dimension").getAsString(),
                        o.get("x").getAsDouble(), o.get("y").getAsDouble(), o.get("z").getAsDouble(),
                        o.get("yaw").getAsFloat(), o.get("pitch").getAsFloat()));
            } catch (RuntimeException broken) {
                // A field missing is a NullPointerException here, one of another kind an
                // IllegalStateException or a NumberFormatException: all "not a bot".
                LOG.warn("[tachyon] {}: {} is no bot (a name, a dimension, x y z, yaw and pitch): it does not come back",
                        file, e);
            }
        }
        return out;
    }

    /**
     * {@code bots} into the file, whole or not at all. An empty list is written too: a
     * roster with nobody on it, so that nobody who left before the stop comes back.
     */
    static void write(Path file, List<Entry> bots) throws IOException {
        JsonArray list = new JsonArray();
        for (Entry b : bots) {
            JsonObject o = new JsonObject();
            o.addProperty("name", b.name());
            o.addProperty("dimension", b.dimension());
            o.addProperty("x", b.x());
            o.addProperty("y", b.y());
            o.addProperty("z", b.z());
            o.addProperty("yaw", b.yaw());
            o.addProperty("pitch", b.pitch());
            list.add(o);
        }
        JsonObject json = new JsonObject();
        json.add("bots", list);
        BotData.write(file, json);
    }
}
