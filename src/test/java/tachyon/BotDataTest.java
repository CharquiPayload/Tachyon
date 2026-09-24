package tachyon;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * A bot's data on the disk: what comes back, what is written and when, and what is done
 * with a file that cannot be read or written. Plain files in a folder of the test's own:
 * no game. Every test leaves nothing waiting to be written, since what waits is shared.
 */
class BotDataTest {

    @TempDir
    Path dir;

    private static JsonObject read(Path file) throws IOException {
        return JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
    }

    private List<Path> besides(String prefix) throws IOException {
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(f -> f.getFileName().toString().startsWith(prefix)).toList();
        }
    }

    /** Something in the way of the file written beside {@code file}: no write of it can happen. */
    private static Path block(Path file) throws IOException {
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.createDirectory(tmp);
        Files.writeString(tmp.resolve("x"), "x", StandardCharsets.UTF_8);
        return tmp;
    }

    private static void unblock(Path tmp) throws IOException {
        Files.delete(tmp.resolve("x"));
        Files.delete(tmp);
    }

    @Test
    @DisplayName("what a bot keeps comes back as it was, from a file of its name in lower case")
    void roundTrip() throws IOException {
        BotData d = BotData.load(dir, "Ada");
        assertTrue(d.section("owner").entrySet().isEmpty(), "a bot never seen has nothing");
        d.section("owner").addProperty("uuid", "7d1a6f53-5b8e-4a8e-9d7e-0c1f2e3a4b5c");
        d.section("owner").addProperty("name", "Someone");
        d.section("settings").addProperty("sprint", false);
        d.changed();
        d.saveNow();
        assertTrue(Files.exists(dir.resolve("ada.json")));

        d.section("never-filled");
        d.changed();
        d.saveNow();
        assertFalse(read(dir.resolve("ada.json")).has("never-filled"), "an empty section is no section, and is not written");

        BotData back = BotData.load(dir, "ADA");
        assertEquals("Someone", back.section("owner").get("name").getAsString());
        assertEquals("7d1a6f53-5b8e-4a8e-9d7e-0c1f2e3a4b5c", back.section("owner").get("uuid").getAsString());
        assertFalse(back.section("settings").get("sprint").getAsBoolean());
        assertFalse(back.dirty(), "as read, nothing to write");
    }

    @Test
    @DisplayName("reading a section that is not there makes none, and what is put in it is not kept")
    void readingChangesNothing() throws IOException {
        BotData d = BotData.load(dir, "Abe");
        d.section("kept").addProperty("a", 1);
        assertEquals(1, d.read("kept").get("a").getAsInt(), "a section that is there is read as it is");
        JsonObject none = d.read("none");
        assertTrue(none.entrySet().isEmpty());
        none.addProperty("lost", true);
        assertTrue(d.read("none").entrySet().isEmpty(), "not kept: read again, it is still not there");
        d.changed();
        d.saveNow();
        assertFalse(read(dir.resolve("abe.json")).has("none"));
    }

    @Test
    @DisplayName("only what changed is written, a copy taken when it is asked for")
    void dirtyAndFlush() throws IOException {
        Path file = dir.resolve("bo.json");
        BotData d = BotData.load(dir, "Bo");
        assertFalse(d.dirty());
        d.section("notes");
        assertFalse(d.dirty(), "a section made is no change");
        d.saveLater();
        BotData.awaitWrites(5000);
        assertFalse(Files.exists(file), "nothing changed, nothing written");

        d.section("notes").addProperty("a", 1);
        d.changed();
        assertTrue(d.dirty());
        d.saveLater();
        assertFalse(d.dirty(), "the copy is on its way");
        // Changed after the copy was taken: that is for the next write.
        d.section("notes").addProperty("b", 2);
        BotData.awaitWrites(5000);
        assertEquals(1, read(file).getAsJsonObject("notes").get("a").getAsInt());
        assertFalse(read(file).getAsJsonObject("notes").has("b"));

        Files.delete(file);
        d.saveLater();
        BotData.awaitWrites(5000);
        assertFalse(Files.exists(file), "not said to have changed: not written");
        d.changed();
        d.saveLater();
        BotData.awaitWrites(5000);
        assertEquals(2, read(file).getAsJsonObject("notes").get("b").getAsInt());
    }

    @Test
    @DisplayName("a bot that comes back at once reads what it left with, written yet or not")
    void comesBackBeforeTheWrite() {
        BotData d = BotData.load(dir, "Cy");
        d.section("x").addProperty("n", 1);
        d.changed();
        d.saveLater();
        BotData again = BotData.load(dir, "Cy");
        assertEquals(1, again.section("x").get("n").getAsInt());
        BotData.awaitWrites(5000);
        assertEquals(1, BotData.load(dir, "Cy").section("x").get("n").getAsInt());
    }

    @Test
    @DisplayName("the newest copy lands last, whichever thread's write goes first")
    void newestLandsLast() throws IOException {
        Path file = dir.resolve("dot.json");
        BotData d = BotData.load(dir, "Dot");
        for (int i = 1; i <= 200; i++) {
            // One on the writer's thread, the next here: the two race for the file.
            d.section("n").addProperty("v", 2 * i - 1);
            d.changed();
            d.saveLater();
            d.section("n").addProperty("v", 2 * i);
            d.changed();
            d.saveNow();
            BotData.awaitWrites(5000);
            assertEquals(2 * i, read(file).getAsJsonObject("n").get("v").getAsInt(), "round " + i);
        }
        assertFalse(Files.exists(dir.resolve("dot.json.tmp")));
    }

    @Test
    @DisplayName("a file that is broken is moved aside, as it was, and the bot starts with nothing")
    void corruptIsMovedAside() throws IOException {
        Path file = dir.resolve("dee.json");
        String cut = "{\"owner\": {\"uuid\": ";
        Files.writeString(file, cut, StandardCharsets.UTF_8);
        BotData d = BotData.load(dir, "Dee");
        assertTrue(d.section("owner").entrySet().isEmpty());
        assertFalse(Files.exists(file));
        List<Path> aside = besides("dee.json.bad-");
        assertEquals(1, aside.size());
        assertTrue(aside.get(0).getFileName().toString().matches("dee\\.json\\.bad-\\d+"));
        assertEquals(cut, Files.readString(aside.get(0), StandardCharsets.UTF_8), "kept as it was");

        // Written again, it is a good file, and the bad one stays for whoever wants to look.
        d.section("owner").addProperty("name", "Someone");
        d.changed();
        d.saveNow();
        assertEquals("Someone", read(file).getAsJsonObject("owner").get("name").getAsString());
        assertEquals(1, besides("dee.json.bad-").size());
    }

    @Test
    @DisplayName("JSON that is no object, an empty file, or bytes that are no UTF-8 are no bot's data either")
    void notAnObject() throws IOException {
        Files.writeString(dir.resolve("eli.json"), "[1, 2]", StandardCharsets.UTF_8);
        assertTrue(BotData.load(dir, "Eli").section("owner").entrySet().isEmpty());
        assertEquals(1, besides("eli.json.bad-").size());

        Files.writeString(dir.resolve("flo.json"), "", StandardCharsets.UTF_8);
        assertTrue(BotData.load(dir, "Flo").section("owner").entrySet().isEmpty());
        assertEquals(1, besides("flo.json.bad-").size());

        Files.write(dir.resolve("fay.json"), new byte[]{'{', '"', (byte) 0xff, (byte) 0xfe, '"', ':', '1', '}'});
        assertTrue(BotData.load(dir, "Fay").section("owner").entrySet().isEmpty());
        assertEquals(1, besides("fay.json.bad-").size());
    }

    @Test
    @DisplayName("a file that is there but cannot be read is left where it is, and never written over")
    void unreadableIsLeftAlone() throws IOException {
        Path file = dir.resolve("gil.json");
        String good = "{\"owner\": {\"name\": \"Someone\"}}";
        Files.writeString(file, good, StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("---------"));
        try {
            // Whoever may read anything (root) reads it: nothing to try then.
            assumeFalse(Files.isReadable(file), "this user reads a file of no permissions");
            BotData d = BotData.load(dir, "Gil");
            assertTrue(d.read("owner").entrySet().isEmpty(), "it starts with nothing");
            assertTrue(Files.exists(file), "not moved aside");
            assertTrue(besides("gil.json.bad-").isEmpty());
            d.section("owner").addProperty("name", "Another");
            d.changed();
            d.saveNow();
            d.saveLater();
            BotData.awaitWrites(5000);
        } finally {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r--r--"));
        }
        assertEquals(good, Files.readString(file, StandardCharsets.UTF_8), "not written over");
        assertEquals("Someone", BotData.load(dir, "Gil").read("owner").get("name").getAsString(),
                "read again once it can be");
    }

    @Test
    @DisplayName("a section edited by hand into no object is started again, empty")
    void sectionThatIsNoObject() throws IOException {
        Files.writeString(dir.resolve("gus.json"), "{\"owner\": 5, \"keep\": {\"a\": 1}}", StandardCharsets.UTF_8);
        BotData d = BotData.load(dir, "Gus");
        assertTrue(d.section("owner").entrySet().isEmpty());
        assertEquals(1, d.section("keep").get("a").getAsInt(), "the rest is read");
    }

    @Test
    @DisplayName("a write is whole: through a file beside it, which is not left behind")
    void atomicWrite() throws IOException {
        Path file = dir.resolve("hal.json");
        Files.writeString(file, "{\"old\": {\"long\": \"" + "x".repeat(10_000) + "\"}}", StandardCharsets.UTF_8);
        // A crash halfway through an earlier write left a piece of one beside it.
        Files.writeString(dir.resolve("hal.json.tmp"), "{\"half", StandardCharsets.UTF_8);
        BotData d = BotData.load(dir, "Hal");
        assertTrue(d.section("old").has("long"), "the piece beside it is never read");

        JsonObject small = new JsonObject();
        small.add("new", new JsonObject());
        BotData.write(file, small);
        assertEquals(small, read(file), "replaced whole: nothing of the longer file under it");
        assertFalse(Files.exists(dir.resolve("hal.json.tmp")), "nothing left beside it");
    }

    @Test
    @DisplayName("a write that fails leaves the old file as it was, keeps its copy, and is tried again")
    void failedWrite() throws IOException {
        Path file = dir.resolve("ivy.json");
        BotData d = BotData.load(dir, "Ivy");
        d.section("n").addProperty("v", 1);
        d.changed();
        d.saveNow();

        Path tmp = block(file);
        assertThrows(IOException.class, () -> BotData.write(file, new JsonObject()));
        d.section("n").addProperty("v", 2);
        d.changed();
        d.saveLater();
        BotData.awaitWrites(5000);
        assertEquals(1, read(file).getAsJsonObject("n").get("v").getAsInt(), "the old file, as it was");
        assertEquals(2, BotData.load(dir, "Ivy").read("n").get("v").getAsInt(), "the copy, kept, is what is read");
        BotData.retry();
        BotData.awaitWrites(5000);
        assertEquals(1, read(file).getAsJsonObject("n").get("v").getAsInt(), "tried again, failed again");

        unblock(tmp);
        BotData.retry();
        BotData.awaitWrites(5000);
        assertEquals(2, read(file).getAsJsonObject("n").get("v").getAsInt());
    }

    @Test
    @DisplayName("what a bot left with is written once the disk takes it, though the bot is gone")
    void aBotGoneIsWrittenLater() throws IOException {
        Path file = dir.resolve("jo.json");
        Path tmp = block(file);
        BotData d = BotData.load(dir, "Jo");
        d.section("n").addProperty("v", 7);
        d.changed();
        d.saveLater();              // as it leaves: nobody holds d after this
        BotData.awaitWrites(5000);
        assertFalse(Files.exists(file));

        // Back before the disk is: it reads what it left with; not changed since, it
        // has nothing new to write, and what it left with is still tried.
        BotData back = BotData.load(dir, "Jo");
        assertEquals(7, back.read("n").get("v").getAsInt());
        assertFalse(back.dirty());
        unblock(tmp);
        BotData.retry();
        BotData.awaitWrites(5000);
        assertEquals(7, read(file).getAsJsonObject("n").get("v").getAsInt());
    }

    @Test
    @DisplayName("at the stop, what could not be written before is written there and then")
    void stopWritesWhatIsLeft() throws IOException {
        Path file = dir.resolve("kit.json");
        Path tmp = block(file);
        BotData d = BotData.load(dir, "Kit");
        d.section("n").addProperty("v", 3);
        d.changed();
        d.saveLater();
        BotData.awaitWrites(5000);
        assertFalse(Files.exists(file));
        unblock(tmp);
        BotData.stop(5000);
        assertEquals(3, read(file).getAsJsonObject("n").get("v").getAsInt());
    }

    @Test
    @DisplayName("a bot's data is the thread's that read it: used on another, it says so")
    void onItsThreadOnly() throws InterruptedException {
        BotData d = BotData.load(dir, "Lu");
        CompletableFuture<Object> elsewhere = CompletableFuture.supplyAsync(() -> d.read("settings"));
        ExecutionException e = assertThrows(ExecutionException.class, elsewhere::get);
        assertInstanceOf(IllegalStateException.class, e.getCause());
        CompletableFuture<Object> changing = CompletableFuture.supplyAsync(() -> {
            d.changed();
            return null;
        });
        assertInstanceOf(IllegalStateException.class, assertThrows(ExecutionException.class, changing::get).getCause());
        assertFalse(d.dirty());
    }

    @Test
    @DisplayName("a shared store is one per name, kept, written as a bot's is; a name that is no file name is refused")
    void sharedStores() throws IOException {
        BotData chests = BotData.shared(dir, "chests");
        assertSame(chests, BotData.shared(dir, "chests"), "one per name");
        chests.section("seen").addProperty("chest-1", 4);
        chests.changed();
        BotData.saveShared(true);
        assertEquals(4, read(dir.resolve("chests.json")).getAsJsonObject("seen").get("chest-1").getAsInt());
        BotData.forgetShared();
        assertEquals(4, BotData.shared(dir, "chests").read("seen").get("chest-1").getAsInt(), "read again from its file");
        BotData.forgetShared();
        for (String bad : new String[]{"../x", "A", "a/b", "", "a.b"}) {
            assertThrows(IllegalArgumentException.class, () -> BotData.shared(dir, bad), bad);
        }
    }
}
