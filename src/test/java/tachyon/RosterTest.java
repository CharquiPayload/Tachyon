package tachyon;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The roster of the bots that come back after a restart, on the disk: what is written
 * comes back, and a file that is broken is moved aside. Plain files in a folder of the
 * test's own: no game.
 */
class RosterTest {

    @TempDir
    Path dir;

    private Path file() {
        return dir.resolve("tachyon").resolve("roster.json");
    }

    private List<String> files() throws IOException {
        try (Stream<Path> all = Files.list(file().getParent())) {
            return all.map(f -> f.getFileName().toString()).sorted().toList();
        }
    }

    private static final Roster.Entry ADA = new Roster.Entry("Ada", "minecraft:overworld", -99.5, 100.0, -29.5, 91.25f, -12.5f);
    private static final Roster.Entry BEA = new Roster.Entry("Bea", "minecraft:the_nether", 8.3, 64.0, 17.7, -180f, 0f);

    @Test
    @DisplayName("what is written comes back as it was, in its order, the folder made; no temp file is left")
    void roundTrip() throws IOException {
        Roster.write(file(), List.of(ADA, BEA));
        assertEquals(List.of(ADA, BEA), Roster.read(file()));
        assertEquals(List.of("roster.json"), files());
        assertTrue(JsonParser.parseString(Files.readString(file())).getAsJsonObject().get("bots").isJsonArray());
    }

    @Test
    @DisplayName("no file is nobody; an empty roster is written too, and is nobody")
    void none() throws IOException {
        assertEquals(List.of(), Roster.read(file()));
        Roster.write(file(), List.of(ADA));
        Roster.write(file(), List.of());
        assertEquals(List.of(), Roster.read(file()), "written over: the bots removed before the stop do not come back");
    }

    @Test
    @DisplayName("a file that is broken is moved aside, as it was, and nobody comes back")
    void broken() throws IOException {
        for (String bad : new String[]{"{\"bots\": [ {\"name\"", "not json", "[1, 2]", "{\"bots\": 3}", "{}"}) {
            Files.createDirectories(file().getParent());
            Files.writeString(file(), bad, StandardCharsets.UTF_8);
            assertEquals(List.of(), Roster.read(file()), bad);
            assertFalse(Files.exists(file()), "moved: " + bad);
            List<String> aside = files().stream().filter(n -> n.startsWith("roster.json.bad-")).toList();
            assertEquals(1, aside.size(), bad);
            assertEquals(bad, Files.readString(file().resolveSibling(aside.get(0))), "as it was");
            Files.delete(file().resolveSibling(aside.get(0)));
        }
        Files.write(file(), new byte[]{'{', (byte) 0xff, '}'});
        assertEquals(List.of(), Roster.read(file()), "bytes that are no UTF-8");
        assertFalse(Files.exists(file()));
    }

    @Test
    @DisplayName("an entry that is broken is left out; the others come back, and the file stays")
    void brokenEntry() throws IOException {
        Files.createDirectories(file().getParent());
        Files.writeString(file(), """
                {"bots": [
                  {"name": "Ada", "dimension": "minecraft:overworld", "x": -99.5, "y": 100.0, "z": -29.5, "yaw": 91.25, "pitch": -12.5},
                  {"name": "Cy", "dimension": "minecraft:overworld", "x": "far", "y": 1, "z": 2, "yaw": 0, "pitch": 0},
                  {"dimension": "minecraft:overworld", "x": 0, "y": 1, "z": 2, "yaw": 0, "pitch": 0},
                  7,
                  {"name": "Bea", "dimension": "minecraft:the_nether", "x": 8.3, "y": 64.0, "z": 17.7, "yaw": -180, "pitch": 0}
                ]}
                """, StandardCharsets.UTF_8);
        assertEquals(List.of(ADA, BEA), Roster.read(file()));
        assertEquals(List.of("roster.json"), files(), "not moved aside: most of it is good");
    }
}
