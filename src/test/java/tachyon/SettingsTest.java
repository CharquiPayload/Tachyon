package tachyon;

import com.google.gson.JsonElement;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Settings: the four layers (the declared default, tachyon.properties' over it, the one set
 * in game over that, the bot's own over all), the words a value is set with, who may set
 * it, and the words the menu shows it with. A switch and a number of the test's own, over
 * properties of its own, as tachyon.properties would give them, and a defaults file of its
 * own, as the world would keep it.
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
        settings.bool("sprint", true, "It sprints when it walks.", Settings.Who.OWNER)
                .label("Sprint when walking").group("Walking").basic();
        settings.number("gap", 3, 1, 10, "How far it keeps.", Settings.Who.OPERATOR)
                .label("Gap").group("Walking").advanced();
        data = BotData.load(dir, "Ada");
    }

    /** Nothing left waiting to be written: what waits is shared by every test. */
    @AfterEach
    void written() {
        BotData.awaitWrites(5000);
    }

    /** The defaults set in game, from a file of the test's own (none there: empty). */
    private BotData game() {
        BotData store = BotData.shared(dir, "defaults", "the test's defaults");
        settings.game(store);
        return store;
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
            assertEquals("\"Sprint when walking\" is on or off (or default)", settings.set(data, "sprint", bad), bad);
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

        String takes = "\"Gap\" is a number from 1 to 10 (or default)";
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
    @DisplayName("values in words: on or off (players read a switch so), a whole number without its .0")
    void words() {
        assertEquals("on", settings.get("sprint").words(1));
        assertEquals("off", settings.get("sprint").words(0));
        assertEquals("2", settings.get("gap").words(2));
        assertEquals("2.5", settings.get("gap").words(2.5));
    }

    /**
     * The menu changes a number by turning the new value into words and setting those, as
     * a command would: so a value's words must read back as that value. Java prints very
     * small and very big numbers as "1.0E-4", which a player never types and parse refuses.
     */
    @Test
    @DisplayName("a number's words are plain digits, never 1.0E-4, and read back as the same value")
    void wordsReadBack() {
        Settings.Setting tiny = settings.number("tiny", 0.5, 0.0001, 1, "how little", Settings.Who.OWNER);
        assertEquals("0.0001", tiny.words(0.0001));
        assertEquals("-0.25", tiny.words(-0.25));
        assertEquals("100000000000000000000", tiny.words(1e20));
        for (double v : new double[]{0.0001, 0.00001234, 0.5, 2.5, -0.25, 3, 1e7, 12345678.9, 1e20}) {
            assertEquals(v, tiny.parse(tiny.words(v)), tiny.words(v));
        }
        assertNull(settings.set(data, "tiny", tiny.words(tiny.clamp(0.5 - 1))), "the lowest value, set through its words");
        assertEquals(0.0001, settings.value(data, "tiny"));
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

    // --- a choice ----------------------------------------------------------------------------

    private Settings.Setting notices() {
        return settings.choice("notices", "brain", List.of("brain", "plain", "off"), "How it tells its owner.",
                Settings.Who.OWNER).label("Notices").group("Brain").basic();
    }

    @Test
    @DisplayName("a choice takes one of its options by name, in any case; the rest is refused with the options")
    void choiceWords() {
        Settings.Setting n = notices();
        assertEquals(0, value("notices"), "its default, brain, is the first option");
        assertEquals("brain", n.words(value("notices")));
        assertNull(settings.set(data, "notices", "Plain"));
        assertEquals(1, value("notices"));
        assertEquals("plain", n.words(value("notices")));
        assertEquals("\"plain\"", kept("notices").toString(), "kept by its name, not its place in the list");
        assertNull(settings.set(data, "notices", " off "));
        assertEquals("off", n.words(value("notices")));
        String takes = "\"Notices\" is one of brain, plain, off (or default)";
        for (String bad : new String[]{"loud", "1", "0", "", "true", "brains"}) {
            assertEquals(takes, settings.set(data, "notices", bad), bad);
        }
        assertEquals(2, value("notices"), "refused: unchanged");
        assertNull(settings.set(data, "notices", "default"));
        assertEquals(0, value("notices"));
    }

    @Test
    @DisplayName("a choice kept by hand as something else, or as a number, is not used; a server default by name")
    void choiceKeptAndServerDefault() {
        notices();
        data.section(Settings.SECTION).addProperty("notices", 2);
        assertEquals(0, value("notices"), "a number is no option");
        data.section(Settings.SECTION).addProperty("notices", "shout");
        assertEquals(0, value("notices"));
        data.section(Settings.SECTION).addProperty("notices", "OFF");
        assertEquals(2, value("notices"), "an option's name in another case is still it");
        data.section(Settings.SECTION).remove("notices");
        server.setProperty("default.notices", "plain");
        settings.forget();          // tachyon.properties read again, as brain reload does
        assertEquals(1, value("notices"));
        assertEquals(Settings.From.FILE, settings.from(data, settings.get("notices")));
        server.setProperty("default.notices", "whisper");
        settings.forget();
        assertEquals(0, value("notices"), "a server default that is no option is not used");
    }

    @Test
    @DisplayName("a choice's default set in game is kept by its name in defaults.json")
    void choiceDefaultInGame() throws IOException {
        notices();
        game();
        assertNull(settings.changeDefault("notices", "off", true));
        assertEquals(2, value("notices"));
        BotData.awaitWrites(5000);
        JsonObject kept = JsonParser.parseString(Files.readString(dir.resolve("defaults.json"), StandardCharsets.UTF_8))
                .getAsJsonObject();
        assertEquals("off", kept.getAsJsonObject("settings").get("notices").getAsString());
        assertEquals("\"Notices\" is one of brain, plain, off (or default)", settings.changeDefault("notices", "on", true));
    }

    @Test
    @DisplayName("declaring a choice: two options at least, lower case words, none twice, its default among them")
    void choiceMistakes() {
        assertThrows(IllegalArgumentException.class, () -> settings.choice("a", "x", List.of("x"), "one option", Settings.Who.OWNER));
        assertThrows(IllegalArgumentException.class, () -> settings.choice("b", "x", List.of("x", "Y"), "a capital", Settings.Who.OWNER));
        assertThrows(IllegalArgumentException.class, () -> settings.choice("c", "x", List.of("x", "x"), "twice", Settings.Who.OWNER));
        assertThrows(IllegalArgumentException.class, () -> settings.choice("d", "z", List.of("x", "y"), "no such default", Settings.Who.OWNER));
        assertThrows(IllegalArgumentException.class, () -> settings.choice("e", "x", List.of("x", "default"), "default", Settings.Who.OWNER));
        assertThrows(IllegalArgumentException.class, () -> settings.choice("f", "x", List.of("x", "two words"), "a space", Settings.Who.OWNER));
        assertNull(settings.get("a"));
        assertTrue(notices().isChoice());
        assertFalse(settings.get("sprint").isChoice());
        assertFalse(settings.get("gap").isChoice());
    }

    // --- the four layers ---------------------------------------------------------------------

    @Test
    @DisplayName("a bot's own value, else the default set in game, else tachyon.properties', else the declared one; and which")
    void fourLayers() {
        Settings.Setting gap = settings.get("gap");
        assertEquals(3, value("gap"));
        assertEquals(Settings.From.MOD, settings.from(data, gap));

        server.setProperty("default.gap", "5");
        settings.forget();          // tachyon.properties read again, as brain reload does
        assertEquals(5, value("gap"));
        assertEquals(Settings.From.FILE, settings.from(data, gap));

        game();
        assertNull(settings.changeDefault("gap", "7", true));
        assertEquals(7, value("gap"));
        assertEquals(Settings.From.GAME, settings.from(data, gap));
        assertEquals(Settings.From.GAME, settings.defaultFrom(gap));
        assertEquals(5, settings.withoutGame(gap), "what clearing it goes back to");

        assertNull(settings.set(data, "gap", "9"));
        assertEquals(9, value("gap"));
        assertEquals(Settings.From.OWN, settings.from(data, gap));
        assertEquals(7, settings.serverDefault(gap), "the server's default under it is unchanged");

        // Each taken away, the next one under it shows.
        assertNull(settings.set(data, "gap", "default"));
        assertEquals(7, value("gap"));
        assertNull(settings.changeDefault("gap", "default", true));
        assertEquals(5, value("gap"));
        assertEquals(Settings.From.FILE, settings.from(data, gap));
        server.remove("default.gap");
        settings.forget();
        assertEquals(3, value("gap"));
        assertEquals(Settings.From.MOD, settings.from(data, gap));
    }

    @Test
    @DisplayName("no store open (no world running): no default set in game, and none can be set")
    void noStore() {
        assertNull(settings.inGame(settings.get("sprint")));
        assertEquals("the server's defaults are not open: the world is not running",
                settings.changeDefault("sprint", "false", true));
        assertEquals(1, value("sprint"));
    }

    @Test
    @DisplayName("a default set in game is refused as the command refuses it: operators only, a value within range")
    void changeDefaultChecks() {
        BotData store = game();
        assertEquals("only operators change the server's defaults", settings.changeDefault("sprint", "false", false));
        assertEquals("\"Gap\" is a number from 1 to 10 (or default)", settings.changeDefault("gap", "11", true));
        assertEquals("\"Sprint when walking\" is on or off (or default)", settings.changeDefault("sprint", "maybe", true));
        assertTrue(settings.changeDefault("fly", "true", true).startsWith("no setting fly"));
        assertFalse(store.dirty(), "refused: nothing to write");
        assertNull(settings.inGame(settings.get("sprint")));
    }

    @Test
    @DisplayName("the defaults set in game are written at once, whole, to defaults.json, and read back from it")
    void defaultsFile() throws IOException {
        game();
        assertNull(settings.changeDefault("sprint", "off", true));
        assertNull(settings.changeDefault("gap", "4.5", true));
        BotData.awaitWrites(5000);
        Path file = dir.resolve("defaults.json");
        JsonObject kept = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
        assertEquals(JsonParser.parseString("{\"settings\": {\"sprint\": false, \"gap\": 4.5}}"), kept);
        assertFalse(Files.exists(dir.resolve("defaults.json.tmp")), "written beside it, then moved over it");

        // Another server reading the same world: the same defaults. (Read afresh: the
        // shared stores are kept by file while a server runs.)
        Settings again = new Settings(key -> null);
        again.bool("sprint", true, "It sprints when it walks.", Settings.Who.OWNER);
        again.number("gap", 3, 1, 10, "How far it keeps.", Settings.Who.OPERATOR);
        again.game(BotData.load(dir, "defaults"));
        assertEquals(0, again.serverDefault(again.get("sprint")));
        assertEquals(4.5, again.serverDefault(again.get("gap")));
    }

    @Test
    @DisplayName("a broken defaults.json is moved aside, and there are no defaults set in game")
    void brokenDefaultsFile() throws IOException {
        Files.writeString(dir.resolve("defaults.json"), "{\"settings\": {\"sprint\": fal", StandardCharsets.UTF_8);
        server.setProperty("default.sprint", "false");
        game();
        assertEquals(0, value("sprint"), "tachyon.properties' shows");
        assertEquals(Settings.From.FILE, settings.from(data, settings.get("sprint")));
        try (Stream<Path> files = Files.list(dir)) {
            List<String> names = files.map(f -> f.getFileName().toString()).toList();
            assertFalse(names.contains("defaults.json"), names.toString());
            assertTrue(names.stream().anyMatch(n -> n.startsWith("defaults.json.bad-")), names.toString());
        }
    }

    @Test
    @DisplayName("who may change a bot's setting: its owner, or operators only when it is theirs; said by its label")
    void whoMayChange() {
        assertEquals("only operators change \"Gap\"", settings.change(data, "gap", "5", false));
        assertNull(settings.own(data, settings.get("gap")), "refused: unchanged");
        assertFalse(data.dirty());
        assertNull(settings.change(data, "gap", "5", true));
        assertEquals(5, value("gap"));
        assertNull(settings.change(data, "sprint", "false", false), "an owner's setting: anyone who may order the bot");
        assertEquals("only operators change \"Gap\"", settings.mayChange(settings.get("gap"), false));
        assertNull(settings.mayChange(settings.get("sprint"), false));
    }

    // --- the words for the menu ------------------------------------------------------------------

    @Test
    @DisplayName("a setting is declared with a label, a group and a level; without them it is a mistake said at once")
    void metadata() {
        Settings.Setting sprint = settings.get("sprint");
        assertEquals("Sprint when walking", sprint.label);
        assertEquals("Walking", sprint.group);
        assertEquals(Settings.Level.BASIC, sprint.level);
        assertEquals(Settings.Level.ADVANCED, settings.get("gap").level);
        settings.check();

        Settings bare = new Settings(key -> null);
        bare.bool("a", true, "A.", Settings.Who.OWNER).group("G").basic();
        assertTrue(assertThrows(IllegalStateException.class, bare::check).getMessage().contains("has no label"));
        bare = new Settings(key -> null);
        bare.bool("a", true, "A.", Settings.Who.OWNER).label("A").basic();
        assertTrue(assertThrows(IllegalStateException.class, bare::check).getMessage().contains("has no group"));
        bare = new Settings(key -> null);
        bare.bool("a", true, "A.", Settings.Who.OWNER).label("A").group("G");
        assertTrue(assertThrows(IllegalStateException.class, bare::check).getMessage().contains("has no level"));
        bare = new Settings(key -> null);
        bare.bool("a", true, "A.", Settings.Who.OWNER).label("A label that goes on and on and on").group("G").basic();
        assertTrue(assertThrows(IllegalStateException.class, bare::check).getMessage().contains("32 at most"));
        // A description is sentences for players: "whether it sprints" is not one.
        for (String fragment : new String[]{"whether it sprints", "It sprints", "", "it sprints."}) {
            bare = new Settings(key -> null);
            bare.bool("a", true, fragment, Settings.Who.OWNER).label("A").group("G").basic();
            assertTrue(assertThrows(IllegalStateException.class, bare::check).getMessage().contains("full sentences"), fragment);
        }
    }

    @Test
    @DisplayName("the settings of a level by group, the groups in the order they were first declared")
    void groups() {
        Settings s = new Settings(key -> null);
        s.bool("a", true, "a", Settings.Who.OWNER).label("A").group("Life").basic();
        s.bool("b", true, "b", Settings.Who.OWNER).label("B").group("Walking").basic();
        s.bool("c", true, "c", Settings.Who.OWNER).label("C").group("Life").basic();
        s.bool("d", true, "d", Settings.Who.OWNER).label("D").group("Brain").advanced();
        Map<String, List<Settings.Setting>> basic = s.groups(Settings.Level.BASIC);
        assertEquals(List.of("Life", "Walking"), List.copyOf(basic.keySet()));
        assertEquals(List.of("a", "c"), basic.get("Life").stream().map(x -> x.key).toList());
        assertEquals(List.of("Brain"), List.copyOf(s.groups(Settings.Level.ADVANCED).keySet()));
    }

    /**
     * The README's settings table, row for row, against the settings the mod declares: key,
     * label, group, level, who changes it, its default, and what it does (the description,
     * with maybe a link after it). A setting added without its row, a row left after its
     * setting went, or one that drifted from the code, fails here. The tests run in the
     * project's folder, where the README is.
     */
    @Test
    @DisplayName("the mod's own settings are the README's settings table, row for row")
    void theModsOwnAreTheReadmesTable() throws IOException {
        Path readme = Path.of("README.md");
        assertTrue(Files.exists(readme), "no README at " + readme.toAbsolutePath());
        List<String> rows = new ArrayList<>();
        boolean in = false;
        for (String line : Files.readAllLines(readme, StandardCharsets.UTF_8)) {
            if (line.startsWith("| key | label")) {
                in = true;
            } else if (in && !line.startsWith("|")) {
                break;
            } else if (in && !line.startsWith("|---")) {
                rows.add(line);
            }
        }
        Settings mod = Abilities.settings();
        mod.check();
        Set<String> inReadme = new HashSet<>();
        for (String row : rows) {
            String[] cell = row.substring(1, row.length() - 1).split("\\|");
            assertEquals(7, cell.length, row);
            for (int i = 0; i < cell.length; i++) cell[i] = cell[i].trim();
            String key = cell[0].replace("`", "");
            Settings.Setting s = mod.get(key);
            assertNotNull(s, "the README has " + key + ", which the mod does not declare");
            assertTrue(inReadme.add(key), key + " twice in the README");
            assertEquals(s.label, cell[1], key + "'s label");
            assertEquals(s.group, cell[2], key + "'s group");
            assertEquals(s.level.name().toLowerCase(Locale.ROOT), cell[3], key + "'s level");
            assertEquals(s.who == Settings.Who.OWNER ? "owner, operators" : "operators", cell[4], key + ": who changes it");
            assertEquals("`" + s.words(s.byDefault) + "`", cell[5], key + "'s default");
            assertEquals(s.description, cell[6].replaceAll("\\s*\\(\\[[^]]*]\\([^)]*\\)\\)$", ""), key + ": what it does");
        }
        Set<String> declared = new HashSet<>();
        for (Settings.Setting s : mod.all()) declared.add(s.key);
        assertEquals(declared, inReadme, "every setting the mod declares has its row in the README, and no other");
        assertEquals(List.of("Walking", "Life", "Night", "Gear", "Brain"), List.copyOf(mod.groups(Settings.Level.BASIC).keySet()));
        assertEquals(List.of("Brain", "Fighting", "Gear", "Walking"), List.copyOf(mod.groups(Settings.Level.ADVANCED).keySet()));
        for (Settings.Setting s : mod.all()) {
            assertFalse(s.description.startsWith("Whether") || s.description.contains("false:") || s.description.contains("true:"),
                    s.key + ": a description reads as sentences for players, not a command line's");
        }
    }
}
