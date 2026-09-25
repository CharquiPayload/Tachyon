package tachyon;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * The config menu's pages as plain data: what each of the chest's 54 slots shows, and what
 * a click on one does. Nothing of Minecraft's here (no items, no players): {@link ConfigMenu}
 * draws what this says with items, reads the viewer's clicks into {@link Click}s, and asks
 * whether the viewer is an operator and which bots are theirs; a test drives it as it is.
 *
 * <p>Three kinds of page, each five rows over a bottom row that is the way around (back,
 * the pages, what this page is, "Advanced"):
 * <ul>
 * <li>The pick: the bots the viewer may configure (their own; an operator's, every one), a
 *     head each, and for operators the server's defaults. With one thing only to pick, the
 *     menu opens on it ({@link #start}).</li>
 * <li>A bot's: its settings of one level, the basic ones first ({@link Settings.Level}), a
 *     row per group: the group's name, then up to {@link #PER_ROW} of its settings (a longer
 *     group goes on in the next row). More rows than a page holds make more pages.</li>
 * <li>The server's defaults (operators): the same, for the layer set in game.</li>
 * </ul>
 *
 * <p>A change goes through the checks {@code /tachyon set} and {@code /tachyon defaults}
 * make: the bot must still be one the viewer may order (among their choices, as
 * {@code Bots.find} asks), or the defaults theirs to change; then {@link Settings#change} or
 * {@link Settings#changeDefault}, and the bot's data written as theirs is. Whether the page
 * may stay open at all is the same question ({@link #stillValid}), which the menu asks
 * every tick.
 */
final class ConfigPages {

    /** Six rows of nine: the biggest chest a vanilla client draws. */
    static final int SIZE = 54;
    /** The rows above the bottom one, and how wide a row is. */
    static final int ROWS = 5, WIDE = 9;
    /** A group's settings in a row, after its name. */
    static final int PER_ROW = WIDE - 1;
    /** The heads a pick page holds. */
    static final int HEADS = ROWS * WIDE;
    /** The bottom row's buttons. */
    static final int BACK = 45, PREVIOUS = 48, ABOUT = 49, NEXT = 50, ADVANCED = 53;
    /** A description is cut into lines this long at most: a tooltip as wide as the screen is hard to read. */
    static final int LINE = 36;

    /** A click, as it means something here. Every other (a number key, a drag, a middle click) means nothing. */
    enum Click {
        LEFT, RIGHT, SHIFT_LEFT, SHIFT_RIGHT,
        /** Q over a slot: a setting back to its default. */
        DROP
    }

    /** What a slot shows; {@link ConfigMenu} picks the item. */
    enum Icon {
        /** Nothing: an empty slot. */
        NONE,
        /** The bottom row where it has no button. */
        FILLER,
        /** A bot's head. */
        BOT,
        /** The server's defaults. */
        DEFAULTS,
        /** A group's name, at the head of its row. */
        GROUP,
        /** A switch that is on, and one that is off. */
        ON, OFF,
        /** A number, shown as the stack's count when it is a whole one from 1 to 99. */
        NUMBER,
        /** A choice among named options: a click goes to the next one. */
        CHOICE,
        /** A setting the viewer may not change. */
        LOCKED,
        ADVANCED, BACK, PREVIOUS, NEXT
    }

    /** How a line under an item reads: a description, a value, what to do, or a warning. */
    enum Tone { TEXT, VALUE, HINT, WARNING }

    record Line(Tone tone, String text) {
        static final Line BLANK = new Line(Tone.TEXT, "");
    }

    /**
     * One slot as drawn: its icon, its name, the lines under it; {@code count}, the stack's
     * size (1, or a number's value); {@code bot}, whose head it is (null: none).
     */
    record Button(Icon icon, String name, List<Line> lore, int count, String bot) {
        static final Button NONE = new Button(Icon.NONE, "", List.of(), 0, null);
        static final Button FILLER = new Button(Icon.FILLER, "", List.of(), 1, null);
    }

    /** A bot the viewer may pick, as the menu saw it: its name, what it is doing, whose it is (null: nobody's), its data. */
    record Choice(String name, String doing, String owner, BotData data) {
    }

    /** What a click did. */
    enum Kind {
        /** Nothing at all: nothing there, or nothing to change (a default already, a number at its end). */
        NOTHING,
        /** Another page. */
        MOVED,
        /** A value changed. */
        CHANGED,
        /** Refused: {@code why} says why, for the viewer. */
        REFUSED
    }

    record Outcome(Kind kind, String why) {
        static final Outcome NOTHING = new Outcome(Kind.NOTHING, null);
        static final Outcome MOVED = new Outcome(Kind.MOVED, null);
        static final Outcome CHANGED = new Outcome(Kind.CHANGED, null);
    }

    /** A row of a settings page: a group's name, and up to {@link #PER_ROW} of its settings. */
    record Row(String group, List<Settings.Setting> settings) {
    }

    /** Where a button of the bottom row (or the pick page's defaults) goes. */
    private enum Go { BACK, PREVIOUS, NEXT, ADVANCED, DEFAULTS }

    private final Settings settings;
    /** Whether the viewer is an operator, asked anew at every drawing and every click. */
    private final BooleanSupplier operator;
    /** The bots the viewer may configure, now. */
    private final Supplier<List<Choice>> choices;

    /** The bot whose page it is; null on the pick page and on the server defaults'. */
    private Choice bot;
    /** On the server defaults' page. */
    private boolean defaults;
    private Settings.Level level = Settings.Level.BASIC;
    /** The page within it, from 0, and how many there were at the last drawing. */
    private int page, pages = 1;
    /** What the last drawing put in each slot, for a click on it: a Choice, a Setting, a Go, or null. */
    private final Object[] at = new Object[SIZE];
    private Button[] drawn;

    ConfigPages(Settings settings, BooleanSupplier operator, Supplier<List<Choice>> choices) {
        this.settings = settings;
        this.operator = operator;
        this.choices = choices;
    }

    // --- where it is -----------------------------------------------------------------------

    /**
     * The page the menu opens on: the pick; or, with one thing only to pick (a player's one
     * bot, an operator's defaults when no bot is in the game), that thing.
     *
     * @return false when there is nothing to show at all: no bot of theirs, and no operator
     */
    boolean start() {
        List<Choice> all = choices.get();
        boolean op = operator.getAsBoolean();
        int count = all.size() + (op ? 1 : 0);
        if (count == 0) return false;
        if (count > 1) pick();
        else if (op) openDefaults();
        else open(all.get(0));
        return true;
    }

    /** The pick page. */
    void pick() {
        show(null, false, Settings.Level.BASIC);
    }

    /** A bot's page, its basic settings. */
    void open(Choice c) {
        show(c, false, Settings.Level.BASIC);
    }

    /** The server defaults' page, the basic ones. */
    void openDefaults() {
        show(null, true, Settings.Level.BASIC);
    }

    /** Another page, its first; drawn when next asked for. */
    private void show(Choice c, boolean toDefaults, Settings.Level to) {
        bot = c;
        defaults = toDefaults;
        level = to;
        page = 0;
        drawn = null;
    }

    /** The bot whose page it is, or null. */
    Choice bot() {
        return bot;
    }

    boolean onDefaults() {
        return defaults;
    }

    boolean onPick() {
        return bot == null && !defaults;
    }

    /**
     * Whether the page may stay open: a bot's, while the bot is still among the viewer's
     * choices (the same bot, by its data: one that left and came back is another); the
     * defaults', while the viewer is an operator; the pick, always.
     */
    boolean stillValid() {
        return gone() == null;
    }

    /** Why the page may not stay open ({@link #stillValid}), in words for the viewer; null while it may. */
    private String gone() {
        if (bot != null && mine(bot) == null) return bot.name() + " is not one of yours in the game any more";
        if (defaults) return settings.mayChangeDefault(operator.getAsBoolean());
        return null;
    }

    /** The bot as the viewer's choices have it now, by its data; null when it is not among them any more. */
    private Choice mine(Choice c) {
        for (Choice now : choices.get()) {
            if (now.data() == c.data()) return now;
        }
        return null;
    }

    Settings.Level level() {
        return level;
    }

    /** The page, from 0, as drawn. */
    int page() {
        buttons();
        return page;
    }

    /** How many pages there are, as drawn. */
    int pages() {
        buttons();
        return pages;
    }

    // --- drawing -------------------------------------------------------------------------------

    /** The slots as the last drawing left them (drawn first if there was none). */
    Button[] buttons() {
        return drawn != null ? drawn : draw();
    }

    /**
     * Every slot, drawn again from what is so now: the values and where they come from, the
     * viewer's rights, the bots and what they are doing.
     */
    Button[] draw() {
        Button[] b = new Button[SIZE];
        Arrays.fill(b, 0, HEADS, Button.NONE);
        Arrays.fill(b, HEADS, SIZE, Button.FILLER);
        Arrays.fill(at, null);
        if (onPick()) drawPick(b);
        else drawSettings(b);
        drawn = b;
        return b;
    }

    private void drawPick(Button[] b) {
        List<Choice> all = choices.get();
        paged((all.size() + HEADS - 1) / HEADS);
        for (int i = 0; i < HEADS; i++) {
            int n = page * HEADS + i;
            if (n >= all.size()) break;
            put(b, i, head(all.get(n)), all.get(n));
        }
        if (operator.getAsBoolean()) {
            List<Line> lore = new ArrayList<>(text(Tone.TEXT, "What every bot has when it has no value of its own."));
            lore.add(Line.BLANK);
            lore.add(new Line(Tone.HINT, "Click: change them"));
            put(b, ABOUT, new Button(Icon.DEFAULTS, "Server defaults", lore, 1, null), Go.DEFAULTS);
        }
        paging(b);
    }

    private void drawSettings(Button[] b) {
        if (bot != null) bot = fresh(bot);
        List<Row> rows = rows(settings.groups(level));
        paged((rows.size() + ROWS - 1) / ROWS);
        for (int r = 0; r < ROWS; r++) {
            int n = page * ROWS + r;
            if (n >= rows.size()) break;
            Row row = rows.get(n);
            b[r * WIDE] = new Button(Icon.GROUP, row.group(), List.of(), 1, null);
            for (int j = 0; j < row.settings().size(); j++) {
                Settings.Setting s = row.settings().get(j);
                put(b, r * WIDE + 1 + j, button(s), s);
            }
        }
        b[ABOUT] = about();
        boolean basic = level == Settings.Level.BASIC;
        if (!basic || canPick()) {
            String to = !basic ? (defaults ? "To the basic defaults" : "To its basic settings") : "To the bots";
            put(b, BACK, new Button(Icon.BACK, "Back", List.of(new Line(Tone.TEXT, to)), 1, null), Go.BACK);
        }
        if (basic && !settings.groups(Settings.Level.ADVANCED).isEmpty()) {
            List<Line> lore = new ArrayList<>(text(Tone.TEXT, defaults ? "The other defaults: what few will change."
                    : "Its other settings: what few will change."));
            lore.add(Line.BLANK);
            lore.add(new Line(Tone.HINT, "Click: show them"));
            put(b, ADVANCED, new Button(Icon.ADVANCED, "Advanced", lore, 1, null), Go.ADVANCED);
        }
        paging(b);
    }

    /** {@code n} pages there are now (at least one): the page it is on, kept within them. */
    private void paged(int n) {
        pages = Math.max(1, n);
        page = Math.min(page, pages - 1);
    }

    private void paging(Button[] b) {
        if (page > 0) {
            put(b, PREVIOUS, new Button(Icon.PREVIOUS, "Previous page",
                    List.of(new Line(Tone.TEXT, "Page " + page + " of " + pages)), 1, null), Go.PREVIOUS);
        }
        if (page < pages - 1) {
            put(b, NEXT, new Button(Icon.NEXT, "Next page",
                    List.of(new Line(Tone.TEXT, "Page " + (page + 2) + " of " + pages)), 1, null), Go.NEXT);
        }
    }

    private void put(Button[] b, int slot, Button button, Object what) {
        b[slot] = button;
        at[slot] = what;
    }

    /** Whether there is a pick page to go back to: more than one thing to pick. */
    private boolean canPick() {
        return choices.get().size() + (operator.getAsBoolean() ? 1 : 0) > 1;
    }

    /** The bot as it is now (what it is doing, whose it is), by its data; as it was when it is not among them any more. */
    private Choice fresh(Choice c) {
        Choice now = mine(c);
        return now != null ? now : c;
    }

    /**
     * The rows a settings page shows, in order: each group's name with up to
     * {@link #PER_ROW} of its settings; a longer group goes on in the next row, its name
     * again at its head, so that no row is without one.
     */
    static List<Row> rows(Map<String, List<Settings.Setting>> groups) {
        List<Row> out = new ArrayList<>();
        groups.forEach((group, list) -> {
            for (int i = 0; i < list.size(); i += PER_ROW) {
                out.add(new Row(group, List.copyOf(list.subList(i, Math.min(list.size(), i + PER_ROW)))));
            }
        });
        return out;
    }

    private static Button head(Choice c) {
        List<Line> lore = new ArrayList<>(text(Tone.VALUE, "Doing: " + c.doing()));
        lore.add(new Line(Tone.TEXT, c.owner() == null ? "Nobody's" : c.owner() + "'s"));
        lore.add(Line.BLANK);
        lore.add(new Line(Tone.HINT, "Click: its settings"));
        return new Button(Icon.BOT, c.name(), lore, 1, c.name());
    }

    /** What the page is: the bot's head, or the defaults'; which level, which page. */
    private Button about() {
        String what = level == Settings.Level.BASIC ? "basic" : "advanced";
        List<Line> lore = new ArrayList<>();
        if (defaults) {
            lore.addAll(text(Tone.TEXT, "For every bot without a value of its own: a bot's own value comes first."));
        } else {
            lore.addAll(text(Tone.VALUE, "Doing: " + bot.doing()));
            lore.add(new Line(Tone.TEXT, bot.owner() == null ? "Nobody's" : bot.owner() + "'s"));
        }
        if (pages > 1) lore.add(new Line(Tone.TEXT, "Page " + (page + 1) + " of " + pages));
        return defaults ? new Button(Icon.DEFAULTS, "Server defaults: " + what, lore, 1, null)
                : new Button(Icon.BOT, bot.name() + ": " + what + " settings", lore, 1, bot.name());
    }

    /**
     * A setting as the page shows it: its label as the name; under it, what it decides, its
     * value and where that comes from, and how to change it (or why the viewer may not).
     */
    private Button button(Settings.Setting s) {
        double v = value(s);
        Settings.From from = defaults ? settings.defaultFrom(s) : settings.from(bot.data(), s);
        List<Line> lore = new ArrayList<>(text(Tone.TEXT, s.description));
        lore.add(Line.BLANK);
        lore.add(new Line(Tone.VALUE, "Value: " + s.words(v)));
        if (s.isChoice()) lore.addAll(text(Tone.TEXT, "Options: " + String.join(", ", s.options)));
        lore.add(new Line(Tone.VALUE, "From: " + from.words));
        lore.add(Line.BLANK);
        String refused = refusal(s);
        if (refused != null) {
            lore.addAll(text(Tone.WARNING, "Locked: " + refused));
            return new Button(Icon.LOCKED, s.label, lore, 1, null);
        }
        if (s.isSwitch) {
            lore.add(new Line(Tone.HINT, "Click: turn it " + (v != 0 ? "off" : "on")));
        } else if (s.isChoice()) {
            lore.add(new Line(Tone.HINT, "Left click: " + s.words(cycle(s, v, 1)) + ", right click: " + s.words(cycle(s, v, -1))));
        } else {
            lore.add(new Line(Tone.HINT, "Left click: +1, right click: -1"));
            lore.add(new Line(Tone.HINT, "Shift: 10 at a time (" + s.words(s.min) + " to " + s.words(s.max) + ")"));
        }
        lore.addAll(text(Tone.HINT, "Q: back to " + backTo(s)));
        if (s.isSwitch) return new Button(v != 0 ? Icon.ON : Icon.OFF, s.label, lore, 1, null);
        if (s.isChoice()) return new Button(Icon.CHOICE, s.label, lore, 1, null);
        boolean shown = v == Math.rint(v) && v >= 1 && v <= 99;
        return new Button(Icon.NUMBER, s.label, lore, shown ? (int) v : 1, null);
    }

    /**
     * What Q puts a setting back to, saying which default that is and its value: on a bot's
     * page, the server's default (set in game, in tachyon.properties, or the mod's); on the
     * Server defaults page, what is there without the one set in game (tachyon.properties',
     * or the mod's).
     */
    private String backTo(Settings.Setting s) {
        double back = defaults ? settings.withoutGame(s) : settings.serverDefault(s);
        Settings.From from = defaults ? settings.fileOrMod(s) : settings.defaultFrom(s);
        String which = switch (from) {
            case GAME -> "the server default set in game";
            case FILE -> "the server default in tachyon.properties";
            default -> "the mod's default";
        };
        return which + " (" + s.words(back) + ")";
    }

    /** The value this page shows for s: the bot's, or the server's default. */
    private double value(Settings.Setting s) {
        return defaults ? settings.serverDefault(s) : settings.value(bot.data(), s);
    }

    /** The value this page sets for s: the bot's own, or the default set in game; null when there is none. */
    private Double own(Settings.Setting s) {
        return defaults ? settings.inGame(s) : settings.own(bot.data(), s);
    }

    /** Why the viewer may not change s here, or null: the same rule the commands follow. */
    private String refusal(Settings.Setting s) {
        boolean op = operator.getAsBoolean();
        return defaults ? settings.mayChangeDefault(op) : settings.mayChange(s, op);
    }

    // --- clicks --------------------------------------------------------------------------------

    /**
     * A click on a slot, as the viewer saw it at the last drawing: a setting changed, another
     * page, or nothing. Whatever it did, the page is drawn again at once. On a page that may
     * not stay open any more (its bot gone, or no longer the viewer's; the defaults, and the
     * viewer no longer an operator) every click is refused, whatever it was on: the menu
     * closes on it within a tick, and nothing is changed meanwhile.
     */
    Outcome click(int slot, Click click) {
        if (slot < 0 || slot >= SIZE) return Outcome.NOTHING;
        String gone = gone();
        if (gone != null) {
            draw();
            return new Outcome(Kind.REFUSED, gone);
        }
        if (drawn == null) draw();
        Object there = at[slot];
        Outcome done;
        if (there instanceof Settings.Setting s) done = change(s, click);
        else if (click == Click.DROP) done = Outcome.NOTHING;         // Q on a button: nothing to take back
        else if (there instanceof Choice c) done = pickBot(c);
        else if (there instanceof Go go) done = press(go);
        else done = Outcome.NOTHING;
        if (done.kind() != Kind.NOTHING) draw();
        return done;
    }

    private Outcome pickBot(Choice c) {
        Choice now = mine(c);
        // Gone since it was drawn, or no longer the viewer's: the pick is drawn again.
        if (now == null) return new Outcome(Kind.REFUSED, c.name() + " is not one of yours in the game any more");
        open(now);
        return Outcome.MOVED;
    }

    private Outcome press(Go button) {
        switch (button) {
            case BACK -> {
                if (level == Settings.Level.ADVANCED) show(bot, defaults, Settings.Level.BASIC);
                else pick();
            }
            case ADVANCED -> show(bot, defaults, Settings.Level.ADVANCED);
            case PREVIOUS -> page = Math.max(0, page - 1);
            case NEXT -> page = Math.min(pages - 1, page + 1);
            case DEFAULTS -> {
                String refused = settings.mayChangeDefault(operator.getAsBoolean());
                if (refused != null) return new Outcome(Kind.REFUSED, refused);
                openDefaults();
            }
        }
        return Outcome.MOVED;
    }

    /**
     * A setting changed by a click: a switch turned over by any click; a choice to its next
     * option (left) or the one before (right), going round; a number one up (left) or down
     * (right), ten with shift, and kept within its range; Q, back to the default. Through
     * the same checks as the commands.
     */
    private Outcome change(Settings.Setting s, Click click) {
        boolean op = operator.getAsBoolean();
        String refused = refusal(s);
        if (refused != null) return new Outcome(Kind.REFUSED, refused);
        double now = value(s);
        String text;
        if (click == Click.DROP) {
            text = "default";
        } else if (s.isSwitch) {
            text = now != 0 ? "off" : "on";
        } else if (s.isChoice()) {
            text = s.words(cycle(s, now, click == Click.LEFT || click == Click.SHIFT_LEFT ? 1 : -1));
        } else {
            int step = click == Click.SHIFT_LEFT || click == Click.SHIFT_RIGHT ? 10 : 1;
            double next = s.clamp(click == Click.LEFT || click == Click.SHIFT_LEFT ? now + step : now - step);
            if (next == now) return Outcome.NOTHING;      // at its end already
            text = s.words(next);
        }
        Double before = own(s);
        refused = defaults ? settings.changeDefault(s.key, text, op) : settings.change(bot.data(), s.key, text, op);
        if (refused != null) return new Outcome(Kind.REFUSED, refused);
        return Objects.equals(before, own(s)) ? Outcome.NOTHING : Outcome.CHANGED;
    }

    /**
     * A choice's option {@code step} places from {@code v}, going round: after the last, the
     * first; before the first, the last. A few options, all of them a click or two away.
     */
    static double cycle(Settings.Setting s, double v, int step) {
        int n = s.options.size();
        return Math.floorMod((int) Math.round(v) + step, n);
    }

    // --- words -------------------------------------------------------------------------------

    /** Words, with those between quotes joined back into one ("Left out of sleeping"). */
    private static List<String> quoted(String[] words) {
        List<String> out = new ArrayList<>();
        StringBuilder in = null;
        for (String w : words) {
            if (in != null) {
                in.append(' ').append(w);
                if (w.endsWith("\"")) {
                    out.add(in.toString());
                    in = null;
                }
            } else if (w.startsWith("\"") && !(w.length() > 1 && w.endsWith("\""))) {
                in = new StringBuilder(w);
            } else {
                out.add(w);
            }
        }
        if (in != null) out.add(in.toString());
        return out;
    }

    /** Words as lines of a tone, cut at {@link #LINE}. */
    private static List<Line> text(Tone tone, String words) {
        List<Line> out = new ArrayList<>();
        for (String l : wrap(words, LINE)) out.add(new Line(tone, l));
        return out;
    }

    /**
     * Words cut into lines of {@code width} characters at most, at spaces; a longer word is a
     * line of its own. Words in quotes (a setting's label, in a refusal) stay on one line.
     */
    static List<String> wrap(String words, int width) {
        List<String> out = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : quoted(words.trim().split("\\s+"))) {
            if (word.isEmpty()) continue;
            if (!line.isEmpty() && line.length() + 1 + word.length() > width) {
                out.add(line.toString());
                line.setLength(0);
            }
            if (!line.isEmpty()) line.append(' ');
            line.append(word);
        }
        if (!line.isEmpty()) out.add(line.toString());
        return out;
    }
}
