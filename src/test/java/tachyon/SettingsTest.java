package tachyon;

import com.google.gson.JsonElement;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Settings: the declared default, the server's over it, the bot's own over both; and the
 * words a value is set with. A switch and a number of the test's own, over properties
 * of its own, as tachyon.properties would give them.
 */
class SettingsTest {

    @TempDir
    Path dir;

    private final Properties server = new Properties();
    private Settings settings;
    private BotData data;

    @BeforeEach
    void setUp() {
        settings = new Settings(key -> server.getProperty("default." + key));
        settings.bool("sprint", true, "whether it may sprint when walking", Settings.Who.OWNER);
        settings.number("gap", 3, 1, 10, "how far it keeps", Settings.Who.OPERATOR);
        data = BotData.load(dir, "Ada");
    }

    private double value(String key) {
        return settings.value(data, key);
    }

    private JsonElement kept(String key) {
        return data.section(Settings.SECTION).get(key);
    }

    @Test
    @DisplayName("with nothing set anywhere, a setting is what it was declared")
    void declaredDefaults() {
        assertEquals(1, value("sprint"));
        assertEquals(3, value("gap"));
        assertNull(settings.own(data, settings.get("sprint")));
        assertFalse(data.dirty(), "reading changes nothing");
    }

    @Test
    @DisplayName("the server's default, in tachyon.properties, is over the declared one")
    void serverDefault() {
        server.setProperty("default.sprint", "false");
        server.setProperty("default.gap", " 7 ");
        assertEquals(0, value("sprint"));
        assertEquals(7, value("gap"));
        assertNull(settings.own(data, settings.get("gap")), "still none of its own");
    }

    @Test
    @DisplayName("a server default out of range is brought to its end; one that is no value is not used")
    void serverDefaultOutOfRangeOrUnreadable() {
        server.setProperty("default.gap", "50");
        assertEquals(10, value("gap"));
        server.setProperty("default.gap", "-4");
        settings.forget();
        assertEquals(1, value("gap"));
        server.setProperty("default.gap", "far");
        settings.forget();
        assertEquals(3, value("gap"));
        server.setProperty("default.sprint", "maybe");
        assertEquals(1, value("sprint"));
    }

    @Test
    @DisplayName("the server's defaults are read once, and again after the file is read again (brain reload)")
    void serverDefaultsKeptUntilReload() {
        server.setProperty("default.gap", "7");
        assertEquals(7, value("gap"));
        server.setProperty("default.gap", "5");
        assertEquals(7, value("gap"), "kept: tachyon.properties is not read on every read");
        settings.forget();
        assertEquals(5, value("gap"));
    }

    @Test
    @DisplayName("the bot's own value is over the server's, kept in its data; default clears it")
    void ownOverServer() {
        server.setProperty("default.sprint", "false");
        assertNull(settings.set(data, "sprint", "true"));
        assertEquals(1, value("sprint"));
        assertNotNull(settings.own(data, settings.get("sprint")));
        assertTrue(data.dirty(), "to be written");
        assertTrue(kept("sprint").getAsJsonPrimitive().isBoolean(), "a switch is kept as true or false");

        data.saveNow();
        assertNull(settings.set(data, "sprint", "default"));
        assertNull(kept("sprint"));
        assertEquals(0, value("sprint"), "the server's again");
        assertTrue(data.dirty());

        data.saveNow();
        assertNull(settings.set(data, "sprint", "default"));
        assertFalse(data.dirty(), "clearing what is not there changes nothing");
    }

    @Test
    @DisplayName("a switch takes true or false, on or off, yes or no, in any case")
    void switchWords() {
        for (String on : new String[]{"true", "TRUE", "on", "Yes"}) {
            assertNull(settings.set(data, "sprint", on), on);
            assertEquals(1, value("sprint"), on);
        }
        for (String off : new String[]{"false", "Off", "no"}) {
            assertNull(settings.set(data, "sprint", off), off);
            assertEquals(0, value("sprint"), off);
        }
        for (String bad : new String[]{"maybe", "1", "", "tru"}) {
            assertEquals("sprint is true or false (or default)", settings.set(data, "sprint", bad), bad);
        }
        assertEquals(0, value("sprint"), "refused: unchanged");
    }

    @Test
    @DisplayName("a number takes plain digits, within its range, ends included; the rest is refused with the range")
    void numberWordsAndRange() {
        assertNull(settings.set(data, "gap", "2.5"));
        assertEquals(2.5, value("gap"));
        assertNull(settings.set(data, "gap", "7"));
        assertEquals(7, value("gap"));
        assertEquals("7", kept("gap").toString(), "a whole number is kept without its .0");
        assertNull(settings.set(data, "gap", "1"));
        assertNull(settings.set(data, "gap", "10"));
        assertEquals(10, value("gap"));

        String takes = "gap is a number from 1 to 10 (or default)";
        for (String bad : new String[]{"11", "0.5", "-3", "abc", "1e3", "NaN", "Infinity", "0x5", "", "5."}) {
            assertEquals(takes, settings.set(data, "gap", bad), bad);
        }
        assertEquals(10, value("gap"), "refused: unchanged");
    }

    @Test
    @DisplayName("a key nobody declared is refused, naming the ones there are")
    void unknownKey() {
        assertEquals("no setting fly; there are: sprint, gap", settings.set(data, "fly", "true"));
        assertFalse(data.dirty());
    }

    @Test
    @DisplayName("an own value edited by hand into something else is not used; a number out of range is brought in")
    void handEdited() {
        data.section(Settings.SECTION).addProperty("sprint", "no");
        assertEquals(1, value("sprint"));
        data.section(Settings.SECTION).addProperty("gap", 99);
        assertEquals(10, value("gap"));
    }

    @Test
    @DisplayName("values in words: true or false, a whole number without its .0")
    void words() {
        assertEquals("true", settings.get("sprint").words(1));
        assertEquals("false", settings.get("sprint").words(0));
        assertEquals("2", settings.get("gap").words(2));
        assertEquals("2.5", settings.get("gap").words(2.5));
    }

    @Test
    @DisplayName("declaring mistakes are said at once, and so is reading what nobody declared")
    void mistakes() {
        assertThrows(IllegalStateException.class,
                () -> settings.bool("sprint", false, "again", Settings.Who.OWNER));
        assertThrows(IllegalArgumentException.class,
                () -> settings.bool("Sprint", false, "a capital", Settings.Who.OWNER));
        assertThrows(IllegalArgumentException.class,
                () -> settings.number("far", 20, 1, 10, "a default out of range", Settings.Who.OWNER));
        assertThrows(IllegalArgumentException.class, () -> settings.value(data, "fly"));
        // default.url is also how a bot named Default would get a brain of its own.
        for (String brains : new String[]{"url", "model", "key", "timeout"}) {
            assertThrows(IllegalArgumentException.class,
                    () -> settings.bool(brains, true, "a brain's key", Settings.Who.OWNER), brains);
        }
    }
}
