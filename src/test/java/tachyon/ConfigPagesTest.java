package tachyon;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The config menu's pages, without a game: which slot shows what, what each click does,
 * what is locked, and the pages when there is more than fits. Settings of the test's own,
 * a viewer who is an operator or not as the test says, and bots that are plain data.
 */
class ConfigPagesTest {

    @TempDir
    Path dir;

    private Settings settings;
    private boolean operator;
    private final List<ConfigPages.Choice> bots = new ArrayList<>();
    private ConfigPages pages;
    private ConfigPages.Choice ada;

    @BeforeEach
    void setUp() {
        settings = new Settings(key -> null);
        settings.bool("sprint", true, "whether it may sprint when walking", Settings.Who.OWNER)
                .label("Sprint").group("Walking").basic();
        settings.number("gap", 3, 1, 10, "how far it keeps", Settings.Who.OWNER)
                .label("Gap").group("Walking").basic();
        settings.bool("respawn", true, "whether it comes back", Settings.Who.OWNER)
                .label("Come back after dying").group("Life").basic();
        settings.bool("night", true, "whether the night goes on", Settings.Who.OPERATOR)
                .label("Left out of sleeping").group("Night").basic();
        settings.bool("lite", false, "whether its brain is small", Settings.Who.OWNER)
                .label("Lite brain").group("Brain").advanced();
        settings.check();
        ada = bot("Ada");
        pages = new ConfigPages(settings, () -> operator, () -> List.copyOf(bots));
    }

    @AfterEach
    void written() {
        BotData.awaitWrites(5000);
    }

    private ConfigPages.Choice bot(String name) {
        ConfigPages.Choice c = new ConfigPages.Choice(name, "standing", "Owner", BotData.load(dir, name));
        bots.add(c);
        return c;
    }

    private ConfigPages.Button at(int slot) {
        return pages.buttons()[slot];
    }

    private static List<String> lore(ConfigPages.Button b) {
        return b.lore().stream().map(ConfigPages.Line::text).toList();
    }

    private double value(String key) {
        return settings.value(ada.data(), settings.get(key));
    }

    private Double own(String key) {
        return settings.own(ada.data(), settings.get(key));
    }

    private ConfigPages.Kind click(int slot, ConfigPages.Click click) {
        return pages.click(slot, click).kind();
    }

    // --- a bot's page -------------------------------------------------------------------------

    @Test
    @DisplayName("a player's one bot opens at once: its basic settings, a row per group, the group's name first")
    void aBotsPage() {
        assertTrue(pages.start());
        assertSame(ada, pages.bot());
        assertEquals(Settings.Level.BASIC, pages.level());

        assertEquals(ConfigPages.Icon.GROUP, at(0).icon());
        assertEquals("Walking", at(0).name());
        assertEquals(ConfigPages.Icon.ON, at(1).icon());
        assertEquals("Sprint", at(1).name(), "its label is the item's name");
        assertEquals(ConfigPages.Icon.NUMBER, at(2).icon());
        assertEquals(3, at(2).count(), "a number shows its value as the stack's size");
        assertEquals(ConfigPages.Icon.NONE, at(3).icon());
        assertEquals("Life", at(9).name());
        assertEquals("Come back after dying", at(10).name());
        assertEquals("Night", at(18).name());
        assertEquals(ConfigPages.Icon.LOCKED, at(19).icon(), "an operators' setting, and the viewer is not one");
        for (int i = 27; i < 45; i++) assertEquals(ConfigPages.Icon.NONE, at(i).icon(), "slot " + i);

        // The bottom row: what the page is, and the way to the advanced ones. No way back:
        // there is nothing else to pick.
        assertEquals(ConfigPages.Icon.FILLER, at(ConfigPages.BACK).icon());
        assertEquals(ConfigPages.Icon.BOT, at(ConfigPages.ABOUT).icon());
        assertEquals("Ada", at(ConfigPages.ABOUT).bot(), "its head");
        assertEquals("Ada: basic settings", at(ConfigPages.ABOUT).name());
        assertEquals(ConfigPages.Icon.ADVANCED, at(ConfigPages.ADVANCED).icon());
        assertEquals(ConfigPages.Icon.FILLER, at(ConfigPages.PREVIOUS).icon());
        assertEquals(ConfigPages.Icon.FILLER, at(ConfigPages.NEXT).icon());
    }

    @Test
    @DisplayName("each setting says, under its name: what it decides, its value, where that comes from, how to change it, and Q")
    void whatASettingSays() {
        pages.start();
        assertEquals(List.of("Whether it may sprint when walking.", "", "Value: true", "From: the mod's default", "",
                "Click: turn it off", "Q: back to the default (true)"), lore(at(1)));
        assertEquals(List.of("How far it keeps.", "", "Value: 3", "From: the mod's default", "",
                "Left click: +1, right click: -1", "Shift: 10 at a time (1 to 10)", "Q: back to the default (3)"),
                lore(at(2)));
        assertEquals(List.of("Whether the night goes on.", "", "Value: true", "From: the mod's default", "",
                "Locked: only operators change night"), lore(at(19)));

        settings.set(ada.data(), "sprint", "false");
        pages.draw();
        assertEquals(ConfigPages.Icon.OFF, at(1).icon());
        assertTrue(lore(at(1)).contains("From: its own"));
        assertTrue(lore(at(1)).contains("Click: turn it on"));
    }

    @Test
    @DisplayName("a switch turns over with any click; Q takes its own value away; each change is in the bot's data")
    void switchClicks() {
        pages.start();
        for (ConfigPages.Click c : new ConfigPages.Click[]{ConfigPages.Click.LEFT, ConfigPages.Click.RIGHT,
                ConfigPages.Click.SHIFT_LEFT, ConfigPages.Click.SHIFT_RIGHT}) {
            double before = value("sprint");
            assertEquals(ConfigPages.Kind.CHANGED, click(1, c), c.name());
            assertEquals(1 - before, value("sprint"), c.name());
            assertEquals(value("sprint") != 0 ? ConfigPages.Icon.ON : ConfigPages.Icon.OFF, at(1).icon(), "drawn again at once");
        }
        assertTrue(ada.data().dirty(), "in its data, to be written");
        assertEquals(1.0, own("sprint"));

        assertEquals(ConfigPages.Kind.CHANGED, click(1, ConfigPages.Click.DROP));
        assertNull(own("sprint"), "back to the default: no value of its own");
        assertEquals(ConfigPages.Kind.NOTHING, click(1, ConfigPages.Click.DROP), "nothing left to take back");
    }

    @Test
    @DisplayName("a number: left +1, right -1, shift 10 at a time, kept within its range; at an end, nothing")
    void numberClicks() {
        pages.start();
        assertEquals(ConfigPages.Kind.CHANGED, click(2, ConfigPages.Click.LEFT));
        assertEquals(4, value("gap"));
        assertEquals(4, at(2).count());
        assertEquals(ConfigPages.Kind.CHANGED, click(2, ConfigPages.Click.RIGHT));
        assertEquals(3, value("gap"));
        assertEquals(ConfigPages.Kind.CHANGED, click(2, ConfigPages.Click.SHIFT_LEFT));
        assertEquals(10, value("gap"), "13 is past its end: 10");
        assertEquals(ConfigPages.Kind.NOTHING, click(2, ConfigPages.Click.LEFT));
        assertEquals(ConfigPages.Kind.NOTHING, click(2, ConfigPages.Click.SHIFT_LEFT));
        assertEquals(10, value("gap"));
        assertEquals(ConfigPages.Kind.CHANGED, click(2, ConfigPages.Click.SHIFT_RIGHT));
        assertEquals(1, value("gap"), "0 is past its end: 1");
        assertEquals(ConfigPages.Kind.NOTHING, click(2, ConfigPages.Click.RIGHT));
        assertEquals(ConfigPages.Kind.CHANGED, click(2, ConfigPages.Click.DROP));
        assertEquals(3, value("gap"));
        assertNull(own("gap"));
    }

    @Test
    @DisplayName("an operators' setting is locked for anyone else: every click refused, in the command's words, and nothing changed")
    void locked() {
        pages.start();
        for (ConfigPages.Click c : ConfigPages.Click.values()) {
            ConfigPages.Outcome done = pages.click(19, c);
            assertEquals(ConfigPages.Kind.REFUSED, done.kind(), c.name());
            assertEquals("only operators change night", done.why());
        }
        assertNull(own("night"));
        assertFalse(ada.data().dirty());

        operator = true;
        pages.draw();
        assertEquals(ConfigPages.Icon.ON, at(19).icon(), "an operator sees it as a switch");
        assertEquals(ConfigPages.Kind.CHANGED, click(19, ConfigPages.Click.LEFT));
        assertEquals(0, value("night"));

        operator = false;          // an operator no more: asked anew at every click
        assertEquals(ConfigPages.Kind.REFUSED, click(19, ConfigPages.Click.LEFT));
        assertEquals(0, value("night"));
    }

    @Test
    @DisplayName("clicks on nothing, on a group's name, outside the chest or with Q on a button do nothing")
    void clicksThatDoNothing() {
        pages.start();
        for (int slot : new int[]{0, 3, 30, 44, ConfigPages.ABOUT, ConfigPages.BACK, -999, -1, 54, 89}) {
            for (ConfigPages.Click c : ConfigPages.Click.values()) {
                assertEquals(ConfigPages.Kind.NOTHING, click(slot, c), slot + " " + c);
            }
        }
        assertEquals(ConfigPages.Kind.NOTHING, click(ConfigPages.ADVANCED, ConfigPages.Click.DROP));
        assertEquals(Settings.Level.BASIC, pages.level());
        assertFalse(ada.data().dirty());
    }

    @Test
    @DisplayName("Advanced opens the rest of the same bot's settings; Back returns to the basic ones")
    void advancedAndBack() {
        pages.start();
        assertEquals(ConfigPages.Kind.MOVED, click(ConfigPages.ADVANCED, ConfigPages.Click.LEFT));
        assertEquals(Settings.Level.ADVANCED, pages.level());
        assertSame(ada, pages.bot());
        assertEquals("Brain", at(0).name());
        assertEquals("Lite brain", at(1).name());
        assertEquals(ConfigPages.Icon.OFF, at(1).icon());
        assertEquals(ConfigPages.Icon.NONE, at(9).icon());
        assertEquals("Ada: advanced settings", at(ConfigPages.ABOUT).name());
        assertEquals(ConfigPages.Icon.BACK, at(ConfigPages.BACK).icon());
        assertEquals(ConfigPages.Icon.FILLER, at(ConfigPages.ADVANCED).icon());

        assertEquals(ConfigPages.Kind.CHANGED, click(1, ConfigPages.Click.LEFT));
        assertEquals(1, value("lite"));

        assertEquals(ConfigPages.Kind.MOVED, click(ConfigPages.BACK, ConfigPages.Click.RIGHT));
        assertEquals(Settings.Level.BASIC, pages.level());
        assertEquals("Walking", at(0).name());
    }

    @Test
    @DisplayName("no Advanced button when a level has nothing more to show")
    void noAdvancedButton() {
        Settings few = new Settings(key -> null);
        few.bool("sprint", true, "whether it sprints", Settings.Who.OWNER).label("Sprint").group("Walking").basic();
        pages = new ConfigPages(few, () -> operator, () -> List.copyOf(bots));
        pages.start();
        assertEquals(ConfigPages.Icon.FILLER, at(ConfigPages.ADVANCED).icon());
        assertEquals(ConfigPages.Kind.NOTHING, click(ConfigPages.ADVANCED, ConfigPages.Click.LEFT));
    }

    /**
     * The rule the menu asks every tick (and the server before every click): a bot's page
     * stays open while that very bot is among the viewer's choices. The model holds to it on
     * its own too: a click that comes anyway is refused, whatever it is on.
     */
    @Test
    @DisplayName("a bot's page with the bot gone, or no longer the viewer's, may not stay open, and refuses every click")
    void botGoneWhileOpen() {
        pages.start();
        assertTrue(pages.stillValid());
        bots.remove(ada);           // removed, or given to someone else: not among the viewer's
        assertFalse(pages.stillValid());
        for (int slot = 0; slot < ConfigPages.SIZE; slot++) {
            for (ConfigPages.Click c : ConfigPages.Click.values()) {
                ConfigPages.Outcome done = pages.click(slot, c);
                assertEquals(ConfigPages.Kind.REFUSED, done.kind(), slot + " " + c);
                assertEquals("Ada is not one of yours in the game any more", done.why());
            }
        }
        assertFalse(ada.data().dirty(), "nothing written");
        assertNull(own("sprint"));
        assertEquals(Settings.Level.BASIC, pages.level(), "no page moved either");

        // One of the same name back in the game is another bot, with data of its own.
        bots.add(new ConfigPages.Choice("Ada", "standing", "Owner", BotData.load(dir.resolve("again"), "Ada")));
        assertFalse(pages.stillValid());
        bots.add(ada);              // the same one, the viewer's again
        assertTrue(pages.stillValid());
        assertEquals(ConfigPages.Kind.CHANGED, click(1, ConfigPages.Click.LEFT));
    }

    @Test
    @DisplayName("the defaults' page may stay open while the viewer is an operator; the pick, always")
    void defaultsAndPickStillValid() {
        settings.game(BotData.shared(dir, "defaults", "the test's defaults"));
        operator = true;
        pages.openDefaults();
        assertTrue(pages.stillValid());
        operator = false;
        assertFalse(pages.stillValid());
        assertEquals(ConfigPages.Kind.REFUSED, click(ConfigPages.ADVANCED, ConfigPages.Click.LEFT), "not even another page");
        assertEquals(Settings.Level.BASIC, pages.level());
        pages.pick();
        assertTrue(pages.stillValid());
        bots.clear();
        assertTrue(pages.stillValid(), "an empty pick is still a pick");
    }

    /** A number is changed through its words, as a command would change it: its words must read back. */
    @Test
    @DisplayName("a number with a fractional range changes by clicks down to its lowest value, shown in plain digits")
    void fractionalNumber() {
        Settings fine = new Settings(key -> null);
        fine.number("tiny", 0.5, 0.0001, 1, "how little", Settings.Who.OWNER).label("Tiny").group("Walking").basic();
        pages = new ConfigPages(fine, () -> operator, () -> List.copyOf(bots));
        pages.start();
        assertEquals(ConfigPages.Kind.CHANGED, click(1, ConfigPages.Click.RIGHT));
        assertEquals(0.0001, fine.value(ada.data(), fine.get("tiny")));
        assertTrue(lore(at(1)).contains("Value: 0.0001"), lore(at(1)).toString());
        assertTrue(lore(at(1)).contains("Shift: 10 at a time (0.0001 to 1)"), lore(at(1)).toString());
        assertEquals(1, at(1).count(), "a value that is no whole number from 1 to 99 is a stack of one");
        assertEquals(ConfigPages.Kind.NOTHING, click(1, ConfigPages.Click.RIGHT), "at its end");
        assertEquals(ConfigPages.Kind.CHANGED, click(1, ConfigPages.Click.LEFT));
        assertEquals(1, fine.value(ada.data(), fine.get("tiny")));
    }

    // --- the pick -------------------------------------------------------------------------------

    @Test
    @DisplayName("the pick: the viewer's bots as heads, their name and what they do; operators have the server's defaults too")
    void pick() {
        ConfigPages.Choice bea = bot("Bea");
        assertTrue(pages.start());
        assertTrue(pages.onPick());
        assertEquals(ConfigPages.Icon.BOT, at(0).icon());
        assertEquals("Ada", at(0).name());
        assertEquals("Ada", at(0).bot());
        assertEquals(List.of("Doing: standing", "Owner's", "", "Click: its settings"), lore(at(0)));
        assertEquals("Bea", at(1).name());
        assertEquals(ConfigPages.Icon.NONE, at(2).icon());
        assertEquals(ConfigPages.Icon.FILLER, at(ConfigPages.ABOUT).icon(), "no defaults for a player");

        assertEquals(ConfigPages.Kind.NOTHING, click(1, ConfigPages.Click.DROP), "Q on a head: nothing");
        assertEquals(ConfigPages.Kind.MOVED, click(1, ConfigPages.Click.LEFT));
        assertSame(bea, pages.bot());
        assertEquals(ConfigPages.Icon.BACK, at(ConfigPages.BACK).icon(), "back to the pick: there is one");
        assertEquals(ConfigPages.Kind.MOVED, click(ConfigPages.BACK, ConfigPages.Click.LEFT));
        assertTrue(pages.onPick());

        operator = true;
        pages.draw();
        assertEquals(ConfigPages.Icon.DEFAULTS, at(ConfigPages.ABOUT).icon());
        assertEquals("Server defaults", at(ConfigPages.ABOUT).name());
        assertEquals(ConfigPages.Kind.MOVED, click(ConfigPages.ABOUT, ConfigPages.Click.LEFT));
        assertTrue(pages.onDefaults());
    }

    @Test
    @DisplayName("with one thing to pick the menu opens on it; with nothing it does not open")
    void oneOrNone() {
        bots.clear();
        assertFalse(pages.start(), "a player with no bot: nothing to show");
        operator = true;
        assertTrue(pages.start());
        assertTrue(pages.onDefaults(), "an operator with no bot in the game: the defaults");
        assertEquals(ConfigPages.Icon.FILLER, at(ConfigPages.BACK).icon(), "nothing to go back to");
        bots.add(ada);
        assertTrue(pages.start());
        assertTrue(pages.onPick(), "a bot and the defaults: two things");
    }

    @Test
    @DisplayName("a bot gone (or no longer the viewer's) since the pick was drawn is refused, and the pick drawn again without it")
    void goneSinceDrawn() {
        bot("Bea");
        pages.start();
        assertEquals("Bea", at(1).name());
        bots.remove(1);
        ConfigPages.Outcome done = pages.click(1, ConfigPages.Click.LEFT);
        assertEquals(ConfigPages.Kind.REFUSED, done.kind());
        assertEquals("Bea is not one of yours in the game any more", done.why());
        assertTrue(pages.onPick());
        assertEquals(ConfigPages.Icon.NONE, at(1).icon());
    }

    // --- the server's defaults ----------------------------------------------------------------

    @Test
    @DisplayName("the server defaults' page changes the layer set in game, for every bot without its own; Q clears it")
    void defaultsPage() {
        BotData game = BotData.shared(dir, "defaults", "the test's defaults");
        settings.game(game);
        operator = true;
        bots.clear();
        pages.start();
        assertTrue(pages.onDefaults());
        assertEquals("Server defaults: basic", at(ConfigPages.ABOUT).name());
        assertEquals(ConfigPages.Icon.ON, at(19).icon(), "no lock on the defaults' page: it is the operators'");

        assertEquals(ConfigPages.Kind.CHANGED, click(1, ConfigPages.Click.LEFT));
        assertEquals(0.0, settings.inGame(settings.get("sprint")));
        assertEquals(0, value("sprint"), "a bot without a value of its own has it");
        assertTrue(lore(at(1)).contains("From: server default, set in game"));
        assertTrue(lore(at(1)).contains("Q: back to the default (true)"), "what clearing it goes back to");
        assertEquals(ConfigPages.Kind.CHANGED, click(2, ConfigPages.Click.SHIFT_LEFT));
        assertEquals(10.0, settings.inGame(settings.get("gap")));

        settings.set(ada.data(), "sprint", "true");
        assertEquals(1, value("sprint"), "a bot's own value comes first");

        assertEquals(ConfigPages.Kind.CHANGED, click(1, ConfigPages.Click.DROP));
        assertNull(settings.inGame(settings.get("sprint")));
        assertEquals(ConfigPages.Kind.NOTHING, click(1, ConfigPages.Click.DROP));

        operator = false;           // no longer an operator: refused, as the command refuses it
        ConfigPages.Outcome done = pages.click(2, ConfigPages.Click.RIGHT);
        assertEquals(ConfigPages.Kind.REFUSED, done.kind());
        assertEquals("only operators change the server's defaults", done.why());
        assertEquals(10.0, settings.inGame(settings.get("gap")));
    }

    // --- pages ----------------------------------------------------------------------------------

    @Test
    @DisplayName("more rows than fit make pages: previous and next, a long group going on in the next row with its name again")
    void settingsPages() {
        Settings many = new Settings(key -> null);
        for (int g = 0; g < 7; g++) {
            many.bool("g" + g, true, "a switch", Settings.Who.OWNER).label("G" + g).group("Group " + g).basic();
        }
        for (int i = 0; i < 10; i++) {
            many.bool("big" + i, i % 2 == 0, "a switch", Settings.Who.OWNER).label("Big " + i).group("Big").basic();
        }
        pages = new ConfigPages(many, () -> operator, () -> List.copyOf(bots));
        assertEquals(9, ConfigPages.rows(many.groups(Settings.Level.BASIC)).size(), "7 groups, and Big in two rows");
        pages.start();
        assertEquals(2, pages.pages());
        for (int r = 0; r < 5; r++) assertEquals("Group " + r, at(r * 9).name());
        assertEquals(ConfigPages.Icon.FILLER, at(ConfigPages.PREVIOUS).icon());
        assertEquals(ConfigPages.Icon.NEXT, at(ConfigPages.NEXT).icon());
        assertEquals(List.of("Doing: standing", "Owner's", "Page 1 of 2"), lore(at(ConfigPages.ABOUT)));

        assertEquals(ConfigPages.Kind.MOVED, click(ConfigPages.NEXT, ConfigPages.Click.LEFT));
        assertEquals(1, pages.page());
        assertEquals("Group 5", at(0).name());
        assertEquals("Group 6", at(9).name());
        assertEquals("Big", at(18).name());
        assertEquals("Big 7", at(18 + 8).name(), "8 to a row");
        assertEquals("Big", at(27).name(), "the rest of Big, its name again");
        assertEquals("Big 8", at(28).name());
        assertEquals("Big 9", at(29).name());
        assertEquals(ConfigPages.Icon.NONE, at(30).icon());
        assertEquals(ConfigPages.Icon.NONE, at(36).icon());
        assertEquals(ConfigPages.Icon.PREVIOUS, at(ConfigPages.PREVIOUS).icon());
        assertEquals(ConfigPages.Icon.FILLER, at(ConfigPages.NEXT).icon());

        assertEquals(ConfigPages.Kind.CHANGED, click(29, ConfigPages.Click.LEFT), "a click on a later page's setting");
        assertEquals(1, many.value(ada.data(), many.get("big9")));
        assertEquals(ConfigPages.Kind.MOVED, click(ConfigPages.PREVIOUS, ConfigPages.Click.LEFT));
        assertEquals(0, pages.page());
        assertEquals(ConfigPages.Kind.NOTHING, click(ConfigPages.PREVIOUS, ConfigPages.Click.LEFT), "no page before the first");
    }

    @Test
    @DisplayName("the pick holds 45 heads a page")
    void pickPages() {
        for (int i = 0; i < 49; i++) bot("Bot" + i);      // 50 with Ada
        pages.start();
        assertEquals(2, pages.pages());
        assertEquals("Ada", at(0).name());
        assertEquals("Bot43", at(44).name());
        assertEquals(ConfigPages.Kind.MOVED, click(ConfigPages.NEXT, ConfigPages.Click.LEFT));
        assertEquals("Bot44", at(0).name());
        assertEquals("Bot48", at(4).name());
        assertEquals(ConfigPages.Icon.NONE, at(5).icon());
        assertEquals(ConfigPages.Kind.MOVED, click(4, ConfigPages.Click.LEFT));
        assertEquals("Bot48", pages.bot().name());

        // Fewer bots than the page it was on: the last page there is.
        pages.pick();
        pages.click(ConfigPages.NEXT, ConfigPages.Click.LEFT);
        bots.subList(10, bots.size()).clear();
        pages.draw();
        assertEquals(0, pages.page());
        assertEquals(1, pages.pages());
    }

    @Test
    @DisplayName("a description is cut into lines at spaces, none longer than the width but a word that is")
    void wrap() {
        assertEquals(List.of("whether the players skip the night", "without it"),
                ConfigPages.wrap("whether the players skip the night without it", 36));
        assertEquals(List.of("a", "longerthanthewidth", "b"), ConfigPages.wrap("a longerthanthewidth b", 5));
        assertEquals(List.of(), ConfigPages.wrap("  ", 10));
        for (String line : ConfigPages.wrap("whether it comes back by itself when it dies (5 times in 5 minutes at most);"
                + " false: it leaves the game", ConfigPages.LINE)) {
            assertTrue(line.length() <= ConfigPages.LINE, line);
        }
    }
}
