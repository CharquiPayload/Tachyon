package tachyon;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * What a bot keeps across leaving and coming back: one JSON file per bot name, in the
 * world save ({@code <world>/tachyon/bots/<name in lower case>.json}), so that a copied
 * world keeps it and another world knows nothing of it. Inside, sections by key: its
 * owner, its settings, and whatever an ability keeps for it. The same kind of file holds
 * what bots share ({@link #shared}): a store by name, in {@code <world>/tachyon/shared/}.
 *
 * <p>It is read when the bot comes in, and written when it changed: every 30 seconds at
 * most, when the bot leaves, and when the server stops. It belongs to the server's
 * thread: only there is it read and changed (anywhere else is a mistake, and throws), and
 * there it is copied, the copy written on a thread of its own so that a slow disk is not
 * a slow tick. At the server's stop the files are written on the server's thread, there
 * and then: nothing waits for that thread once the server is gone.
 *
 * <p>Two writes of one file never cross, whatever threads they are on: each takes the
 * file's lock, and writes the newest copy there is of it. So the last to land is always
 * the newest, even when one thread's write of an older copy is still going when the
 * other's starts.
 *
 * <p>A file is written whole or not at all: into a file beside it, then moved over it.
 * A write that fails (the disk is full, the folder not writable) keeps its copy, which a
 * bot that comes back reads instead of the older file, and which is tried again every 30
 * seconds and at the stop, whether the bot is still in the game or not.
 *
 * <p>A file that is broken (cut short, edited by hand into something that is no JSON
 * object) is moved aside, as {@code <name>.json.bad-<time>}, with a warning, and the bot
 * starts with nothing: never a crash, and never the broken file written over. A file
 * that is there but cannot be read (a permission, the disk) is not broken: it is left
 * where it is and never written over while that bot is in the game, and the bot starts
 * with nothing.
 */
final class BotData {

    private static final Logger LOG = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    /** A shared store's name: it is a file name too, so nothing that could leave the folder. */
    private static final Pattern STORE = Pattern.compile("[a-z0-9_-]{1,64}");

    /** The thread the files are written on. */
    private static final ExecutorService WRITER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "tachyon-data");
        t.setDaemon(true);
        return t;
    });
    /**
     * The newest copy of each file that is not on the disk yet: on its way, or its write
     * failed and it waits to be tried again. A bot that comes back meanwhile reads it from
     * here, not from the older file.
     */
    private static final Map<Path, JsonObject> PENDING = new ConcurrentHashMap<>();
    /** The files whose last write failed: tried again every 30 s, and said once until one works. */
    private static final Set<Path> FAILING = ConcurrentHashMap.newKeySet();
    /** The files' locks, by their hash: a few, whatever the number of files. */
    private static final Object[] LOCKS = new Object[64];

    static {
        for (int i = 0; i < LOCKS.length; i++) LOCKS[i] = new Object();
    }

    /** The shared stores read so far, by file: kept while the server runs. The server's thread's. */
    private static final Map<Path, BotData> SHARED = new HashMap<>();

    final Path file;
    private final JsonObject json;
    private boolean dirty;
    /** Its file was there and could not be read: whatever is in it may be good, so it is never written over. */
    private final boolean keepOff;
    /** The thread it belongs to: the one it was read on, the server's. */
    private final Thread home = Thread.currentThread();

    private BotData(Path file, JsonObject json, boolean keepOff) {
        this.file = file;
        this.json = json;
        this.keepOff = keepOff;
    }

    /** Where the bots' files are: in the world save. */
    static Path folder(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT).resolve("tachyon").resolve("bots").normalize();
    }

    /**
     * A bot's data, from its file in {@code folder}: empty when it has none yet, or when
     * the file cannot be read (said in the log; a broken one is moved aside).
     */
    static BotData load(Path folder, String name) {
        return read(folder.resolve(name.toLowerCase(Locale.ROOT) + ".json"), name);
    }

    /** {@code whose}: for the log, whose data it is. */
    private static BotData read(Path file, String whose) {
        JsonObject pending = PENDING.get(file);
        if (pending != null) return new BotData(file, pending.deepCopy(), false);
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(file);
        } catch (NoSuchFileException e) {
            return new BotData(file, new JsonObject(), false);
        } catch (IOException e) {
            // Not broken, as far as anyone knows: a permission, a disk that failed to
            // answer. Moved aside, a good file would be lost to a slip of chmod.
            LOG.warn("[tachyon] {} is there but could not be read ({}): {} starts with nothing, and the file is"
                    + " left as it is; nothing is written over it until it is read again", file, e.toString(), whose);
            return new BotData(file, new JsonObject(), true);
        }
        try {
            return new BotData(file, parse(bytes), false);
        } catch (CharacterCodingException | RuntimeException e) {
            // Where it is, a broken file that could not be moved would be written over:
            // kept off it instead.
            boolean moved = setAside(file, e.getMessage(), whose + " starts with nothing");
            return new BotData(file, new JsonObject(), !moved);
        }
    }

    /**
     * A file's bytes as a JSON object. Bytes that are not one (not UTF-8, not JSON, JSON
     * but no object) throw: {@link CharacterCodingException}, or Gson's
     * {@link JsonParseException}, a RuntimeException like the rest of what broken text can
     * make the parser throw.
     */
    static JsonObject parse(byte[] bytes) throws CharacterCodingException {
        // Strict UTF-8: bytes that are not are a broken file, not text to guess at.
        String text = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
        JsonElement read = JsonParser.parseString(text);
        if (!read.isJsonObject()) throw new JsonParseException("not a JSON object");
        return read.getAsJsonObject();
    }

    /**
     * A broken file moved aside, as {@code <name>.bad-<time>} beside it, with a warning
     * that says {@code why} and what comes of it ({@code then}: "Ada starts with
     * nothing"): never a crash, and never the broken file written over, since someone may
     * want what is in it.
     *
     * @return false when it could not be moved (said too): it is where it was
     */
    static boolean setAside(Path file, String why, String then) {
        Path aside = file.resolveSibling(file.getFileName() + ".bad-" + System.currentTimeMillis());
        try {
            Files.move(file, aside);
            LOG.warn("[tachyon] {} is broken ({}): moved aside as {}; {}", file, why, aside.getFileName(), then);
            return true;
        } catch (IOException moving) {
            LOG.warn("[tachyon] {} is broken ({}) and could not be moved aside ({}); {}, and the file is left as it is",
                    file, why, moving.toString(), then);
            return false;
        }
    }

    /**
     * A store of what bots share (what every bot of one owner knows, what the server
     * keeps for all of them), by name: {@code <world>/tachyon/shared/<name>.json}, made
     * of sections and written as a bot's data is. Read the first time it is asked for,
     * kept while the server runs. On the server's thread.
     *
     * @param name lower case letters, digits, {@code _} and {@code -}: {@code chests},
     *             {@code farms-<owner's uuid>}
     */
    static BotData shared(MinecraftServer server, String name) {
        return shared(server.getWorldPath(LevelResource.ROOT).resolve("tachyon").resolve("shared").normalize(), name);
    }

    static BotData shared(Path folder, String name) {
        return shared(folder, name, "the shared store " + name);
    }

    /**
     * The same, in a folder of the caller's and said in the log as {@code whose} ("the
     * server's list of defaults set in game starts with nothing"): the mod's own stores that
     * are not in {@code shared/} ({@link Settings#gameStore}).
     */
    static BotData shared(Path folder, String name, String whose) {
        if (!STORE.matcher(name).matches()) {
            throw new IllegalArgumentException("a shared store's name is lower case letters, digits, _ and -: " + name);
        }
        return SHARED.computeIfAbsent(folder.resolve(name + ".json"), f -> read(f, whose));
    }

    /** The shared stores that changed, written: on the writer's thread, or {@code now}, here. */
    static void saveShared(boolean now) {
        for (BotData d : SHARED.values()) {
            if (now) d.saveNow();
            else d.saveLater();
        }
    }

    /** At the stop, once they are written: the next server reads them again. */
    static void forgetShared() {
        SHARED.clear();
    }

    /**
     * A section to change, by key: made (empty) when it is not there. Whoever changes it
     * says so with {@link #changed}, or the change is not written.
     */
    JsonObject section(String key) {
        home();
        JsonElement e = json.get(key);
        if (e != null && e.isJsonObject()) return e.getAsJsonObject();
        // Not there; or not an object, edited by hand, and then of no use to anyone.
        JsonObject made = new JsonObject();
        json.add(key, made);
        return made;
    }

    /**
     * A section to read, by key: when it is not there, an empty one that is not kept.
     * Reading never changes the data. What is read is the data itself: to change it, use
     * {@link #section}, and say so.
     */
    JsonObject read(String key) {
        home();
        JsonElement e = json.get(key);
        return e != null && e.isJsonObject() ? e.getAsJsonObject() : new JsonObject();
    }

    /** It changed: to be written. */
    void changed() {
        home();
        dirty = true;
    }

    /** It changed since it was last copied to be written. */
    boolean dirty() {
        return dirty;
    }

    /** Written, if it changed: a copy, taken here, written on the writer's thread. */
    void saveLater() {
        home();
        if (!dirty) return;
        dirty = false;
        if (keepOff) return;
        PENDING.put(file, copy());
        WRITER.execute(() -> flush(file));
    }

    /** Written, if it changed, here and now: at the server's stop. */
    void saveNow() {
        home();
        if (!dirty) return;
        dirty = false;
        if (keepOff) return;
        PENDING.put(file, copy());
        flush(file);
    }

    /**
     * What is written: all of it, but the sections that are empty. One emptied (its last
     * setting set back to the default) is no section.
     */
    private JsonObject copy() {
        JsonObject copy = new JsonObject();
        for (Map.Entry<String, JsonElement> e : json.entrySet()) {
            if (e.getValue().isJsonObject() && e.getValue().getAsJsonObject().isEmpty()) continue;
            copy.add(e.getKey(), e.getValue().deepCopy());
        }
        return copy;
    }

    /**
     * Used on another thread than its own, it is a mistake of ours: a brain's or a
     * search's thread reading it while the server's changes it breaks both, rarely, and
     * far from where the mistake is. Said at once instead.
     */
    private void home() {
        if (Thread.currentThread() != home) {
            throw new IllegalStateException(file.getFileName() + " is the server thread's, used on "
                    + Thread.currentThread().getName());
        }
    }

    // --- writing, on whichever thread -------------------------------------------------------

    private static Object lock(Path file) {
        return LOCKS[Math.floorMod(file.hashCode(), LOCKS.length)];
    }

    /**
     * The newest copy of {@code file} that is not on the disk, written. Under the file's
     * lock: a write that took an older copy is followed by one that takes the newer, and
     * the newest lands last. A write that fails keeps its copy for the next try.
     */
    private static void flush(Path file) {
        synchronized (lock(file)) {
            JsonObject copy = PENDING.get(file);
            if (copy == null) return;             // written already, by the flush before this one
            try {
                write(file, copy);
                PENDING.remove(file, copy);
                if (FAILING.remove(file)) LOG.info("[tachyon] {} written again", file);
            } catch (IOException | RuntimeException e) {
                // Said once, with why, until a write of it works: a full disk would fill
                // the log too, every 30 s for every bot.
                if (FAILING.add(file)) {
                    LOG.warn("[tachyon] could not write {}: kept, and tried again every 30 s and at the stop"
                            + " (said once until it works)", file, e);
                }
            }
        }
    }

    /** The files whose last write failed, tried again on the writer's thread: every 30 s, from the server's tick. */
    static void retry() {
        for (Path f : FAILING) WRITER.execute(() -> flush(f));
    }

    /** Waits for the writes on their way, {@code ms} at most. */
    static void awaitWrites(long ms) {
        try {
            WRITER.submit(() -> {
            }).get(ms, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            LOG.warn("[tachyon] the bots' data was still being written after {} ms", ms);
        }
    }

    /**
     * At the server's stop, once every bot left: the writes on their way waited for
     * ({@code ms} at most), then whatever is still not on the disk (the writes that
     * failed, of bots in the game or long gone) written here and now. What still cannot
     * be written is lost with the server, and said.
     */
    static void stop(long ms) {
        awaitWrites(ms);
        for (Path f : List.copyOf(PENDING.keySet())) flush(f);
        for (Path f : PENDING.keySet()) {
            LOG.warn("[tachyon] {} could not be written before the server stopped: what changed in it since"
                    + " its last write is lost", f);
        }
    }

    /**
     * {@code json} into {@code file}, whole or not at all: written into a file beside it,
     * forced to the disk, then moved over it. A crash halfway leaves the old file as it
     * was, and at worst a {@code .tmp} beside it, which the next write replaces. Only
     * under the file's lock (see {@link #flush}): the {@code .tmp} is one per file.
     */
    static void write(Path file, JsonObject json) throws IOException {
        Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        byte[] bytes = GSON.toJson(json).getBytes(StandardCharsets.UTF_8);
        try (FileChannel out = FileChannel.open(tmp, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING)) {
            ByteBuffer buf = ByteBuffer.wrap(bytes);
            while (buf.hasRemaining()) out.write(buf);
            // Without it, a power cut soon after the move can leave an empty file where
            // the old one was: the move reaches the disk before the bytes do.
            out.force(true);
        }
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
