package tachyon;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.mojang.logging.LogUtils;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * The switches, numbers and choices the abilities declare, which say how a bot goes about
 * what it does (whether it sprints, say, or how it tells its owner things), each bot with
 * values of its own.
 *
 * <p>Four layers; a bot's value is the first of them that has one:
 * <ol>
 * <li>the bot's own, set with {@code /tachyon set} or the menu ({@link ConfigMenu}), kept
 *     in its data (section {@code settings}), so it stays across leaving and coming back;</li>
 * <li>the server's default set in game by operators ({@code /tachyon defaults}, or the
 *     menu), kept with the world in {@code <world>/tachyon/defaults.json} (the same
 *     section): see {@link #gameStore};</li>
 * <li>the server's default in {@code tachyon.properties}, {@code default.<key>=...};</li>
 * <li>the one the ability declared.</li>
 * </ol>
 * {@link From} says which one a value comes from. Who may change a setting is declared
 * with it: its owner (and operators), or operators only. So are the words the menu shows
 * it with: a label, a group, and a level (basic, shown first; advanced, behind a button).
 *
 * <p>The code reads them with {@link #bool(Bots.Bot, String)},
 * {@link #number(Bots.Bot, String)} and {@link #choice(Bots.Bot, String)}, on the server's
 * thread, as a bot's data is. A read is
 * a lookup in the bot's data, then, when it has no value of its own, one in the defaults
 * set in game, then one in tachyon.properties' defaults, parsed once and kept until
 * {@code /tachyon brain reload}. A number read is always within its range: one out of it (a
 * server default, a file edited by hand) is brought to the nearest end; the commands and
 * the menu refuse it, saying the range.
 */
final class Settings {

    private static final Logger LOG = LogUtils.getLogger();
    /** The section of a bot's data its own values are kept in; and of the defaults set in game, those. */
    static final String SECTION = "settings";
    private static final Pattern KEY = Pattern.compile("[a-z][a-z0-9_]*");
    private static final Pattern NUMBER = Pattern.compile("-?\\d+(\\.\\d+)?");
    /** A label is a few words: the menu shows it as an item's name. */
    static final int LABEL_MAX = 32;
    /**
     * Keys no setting may have: a server default is {@code default.<key>} in
     * tachyon.properties, and {@code default.url} (model, key, timeout) is also how the
     * brain of a bot named Default would be set there.
     */
    private static final Set<String> BRAIN_KEYS = Set.of("url", "model", "key", "timeout");

    /** Who may change a setting of a bot. */
    enum Who {
        /** Its owner, or an operator. */
        OWNER,
        /** Operators only. */
        OPERATOR
    }

    /**
     * Where the menu shows a setting: among the first ones, or behind its "Advanced" button.
     * The plan is that a player finds what they want without wading through the rest.
     */
    enum Level {
        /** What most owners will want to change: shown first. */
        BASIC,
        /** For whoever knows what they are after: behind the "Advanced" button. */
        ADVANCED
    }

    /** Which of the four layers a value comes from: the first that has one. */
    enum From {
        OWN("its own"),
        GAME("server default, set in game"),
        FILE("server default, in tachyon.properties"),
        MOD("the mod's default");

        /** As {@code /tachyon settings} and the menu say it. */
        final String words;

        From(String words) {
            this.words = words;
        }
    }

    /**
     * One setting, as declared: a switch (on or off), a number within its range, or a
     * choice among a few named options ("brain", "plain", "off"). Values are held as numbers
     * all the same: a switch's are 1 and 0, a choice's the place of its option in the list
     * (from 0), so that the layers, the ranges and the menu work alike for the three. What
     * is said is words: on or off, the number, the option's name; what is kept in a bot's
     * data, true or false for a switch (JSON's own), the number, the option's name.
     *
     * <p>Its words for the menu are given as it is declared, one after another:
     * {@code settings.bool(...).label("Sprint when walking").group("Walking").basic()}. They
     * are set there and never after; a setting without them is a mistake, said as the server
     * starts ({@link #check}).
     */
    static final class Setting {
        final String key;
        final boolean isSwitch;
        /** A choice's options, in order: its values are their places in it. Empty for a switch or a number. */
        final List<String> options;
        final double byDefault, min, max;
        /**
         * What it does, in full sentences for players, as the menu shows it under its label
         * and {@code /tachyon settings} after its value: "It sprints when it walks, on flat
         * ground." A switch's says what it does when it is on, and, if that is not plain, what
         * happens when it is off.
         */
        final String description;
        final Who who;
        /** Its short name in the menu, a few words: "Come back after dying". */
        String label;
        /** The group the menu shows it in, with the others of that name: "Walking", "Life". */
        String group;
        /** Whether the menu shows it first, or behind the "Advanced" button. */
        Level level;

        private Setting(String key, boolean isSwitch, List<String> options, double byDefault, double min, double max,
                        String description, Who who) {
            this.key = key;
            this.isSwitch = isSwitch;
            this.options = List.copyOf(options);
            this.byDefault = byDefault;
            this.min = min;
            this.max = max;
            this.description = description;
            this.who = who;
        }

        /** Its short name in the menu: a few words, as a player would say it ("Sprint when walking"). */
        Setting label(String label) {
            this.label = label;
            return this;
        }

        /**
         * The group the menu shows it in: a word or two, the same for every setting of one
         * kind ("Walking", "Life", "Night", "Brain"), and spelled the same, or it is another group.
         */
        Setting group(String group) {
            this.group = group;
            return this;
        }

        /** Shown first: what most owners will want to change. */
        Setting basic() {
            this.level = Level.BASIC;
            return this;
        }

        /** Behind the menu's "Advanced" button: what few will change, or only knowing why. */
        Setting advanced() {
            this.level = Level.ADVANCED;
            return this;
        }

        /** Whether it is a choice among named options (then {@link #options} has them). */
        boolean isChoice() {
            return !options.isEmpty();
        }

        /**
         * A value in words, as players read it: on or off, a choice's option, or the number,
         * in plain digits (a whole one without its ".0"). Never Java's "1.0E-4": the menu
         * turns a number it changed into words and back through {@link #parse}, which takes
         * plain digits only, as a player types them.
         */
        String words(double v) {
            if (isSwitch) return v != 0 ? "on" : "off";
            if (isChoice()) return options.get((int) Math.round(clamp(v)));
            if (v == Math.rint(v) && Math.abs(v) < 1e15) return String.valueOf((long) v);
            return BigDecimal.valueOf(v).stripTrailingZeros().toPlainString();
        }

        /**
         * Its label as a refusal names it: in quotes, since it is a few words among others
         * (its key only while it has none yet, as it is being declared).
         */
        String named() {
            return label == null ? key : "\"" + label + "\"";
        }

        /** What it takes, in words, for a refusal: by its label, as the menu shows it. */
        String takes() {
            if (isSwitch) return named() + " is on or off (or default)";
            if (isChoice()) return named() + " is one of " + String.join(", ", options) + " (or default)";
            return named() + " is a number from " + words(min) + " to " + words(max) + " (or default)";
        }

        /**
         * Words as a value (a number in or out of its range, a choice's option by its name,
         * in any case), or null when they are none.
         */
        Double parse(String text) {
            String t = text.trim().toLowerCase(Locale.ROOT);
            if (isSwitch) {
                return switch (t) {
                    case "true", "on", "yes" -> 1.0;
                    case "false", "off", "no" -> 0.0;
                    default -> null;
                };
            }
            if (isChoice()) {
                int i = options.indexOf(t);
                return i < 0 ? null : (double) i;
            }
            return NUMBER.matcher(t).matches() ? Double.valueOf(t) : null;
        }

        boolean within(double v) {
            return v >= min && v <= max;
        }

        double clamp(double v) {
            return Math.max(min, Math.min(max, v));
        }

        /** A value as kept in a bot's data, or null when it is none (edited by hand into something else). */
        Double read(JsonElement e) {
            if (!e.isJsonPrimitive()) return null;
            JsonPrimitive v = e.getAsJsonPrimitive();
            if (isSwitch) return v.isBoolean() ? (v.getAsBoolean() ? 1.0 : 0.0) : null;
            // A choice is kept by its option's name: an option added later, or the list
            // reordered, does not change what a bot had chosen.
            if (isChoice()) return v.isString() ? parse(v.getAsString()) : null;
            return v.isNumber() ? clamp(v.getAsDouble()) : null;
        }

        /** A value as it is kept in a bot's data (or in defaults.json): true or false, the option's name, the number. */
        JsonPrimitive kept(double v) {
            if (isSwitch) return new JsonPrimitive(v != 0);
            if (isChoice()) return new JsonPrimitive(words(v));
            if (v == Math.rint(v) && Math.abs(v) < 1e15) return new JsonPrimitive((long) v);
            return new JsonPrimitive(v);
        }
    }

    private final Map<String, Setting> declared = new LinkedHashMap<>();
    /** A key's server default as written in tachyon.properties, {@code default.<key>} (null: none). */
    private final Function<String, String> server;
    /** The server defaults that could not be read, said once each. */
    private final Set<String> warned = ConcurrentHashMap.newKeySet();
    /**
     * The server defaults as read from tachyon.properties, parsed (empty: none there that is
     * a value): read once, until {@link #forget}.
     */
    private final Map<String, Optional<Double>> fileDefaults = new ConcurrentHashMap<>();
    /**
     * The server's defaults set in game, while a server runs (null before, and in a test that
     * gives none): {@code <world>/tachyon/defaults.json}, the server thread's as a bot's data is.
     */
    private BotData game;

    Settings(Function<String, String> server) {
        this.server = server;
    }

    // --- declaring: from Ability.settings ------------------------------------------------

    /** A switch. */
    Setting bool(String key, boolean byDefault, String description, Who who) {
        return declare(new Setting(key, true, List.of(), byDefault ? 1 : 0, 0, 1, description, who));
    }

    /** A number, from {@code min} to {@code max}. */
    Setting number(String key, double byDefault, double min, double max, String description, Who who) {
        if (!(min <= byDefault && byDefault <= max)) {
            throw new IllegalArgumentException("setting " + key + ": its default " + byDefault + " is not within " + min + " to " + max);
        }
        return declare(new Setting(key, false, List.of(), byDefault, min, max, description, who));
    }

    /**
     * A choice among a few named options, {@code byDefault} one of them: the way something
     * is done when there are more than two ("brain", "plain", "off"). Each option is a
     * lower case word, as a player types it; the menu goes through them in this order. Keep
     * them few: each is a word a player has to understand.
     */
    Setting choice(String key, String byDefault, List<String> options, String description, Who who) {
        if (options.size() < 2) throw new IllegalArgumentException("setting " + key + ": a choice has two options at least");
        for (String o : options) {
            if (!KEY.matcher(o).matches() || o.equals("default")) {
                throw new IllegalArgumentException("setting " + key + ": its option " + o
                        + " is not a lower case word (or is default, which means the server's)");
            }
        }
        if (Set.copyOf(options).size() != options.size()) throw new IllegalArgumentException("setting " + key + ": an option twice");
        int at = options.indexOf(byDefault);
        if (at < 0) throw new IllegalArgumentException("setting " + key + ": its default " + byDefault + " is none of " + options);
        return declare(new Setting(key, false, options, at, 0, options.size() - 1, description, who));
    }

    // Mistakes of ours, said as the server starts.
    private Setting declare(Setting s) {
        if (!KEY.matcher(s.key).matches()) {
            throw new IllegalArgumentException("setting " + s.key + ": a key is lower case letters, digits and _");
        }
        if (BRAIN_KEYS.contains(s.key)) {
            throw new IllegalArgumentException("setting " + s.key + ": that key is the brain's, in tachyon.properties");
        }
        if (declared.putIfAbsent(s.key, s) != null) throw new IllegalStateException("two settings called " + s.key);
        return s;
    }

    /**
     * Every setting has its words for the menu: a label of a few words, a group, a level,
     * and a description in full sentences.
     * Asked once every ability has declared its own (see Abilities): one without them is a
     * mistake of ours, said as the server starts, where it is seen at once.
     */
    void check() {
        for (Setting s : declared.values()) {
            if (s.label == null || s.label.isBlank()) {
                throw new IllegalStateException("setting " + s.key + " has no label: declare it with .label(\"A few words\")");
            }
            if (s.label.length() > LABEL_MAX) {
                throw new IllegalStateException("setting " + s.key + ": its label is " + s.label.length()
                        + " characters, " + LABEL_MAX + " at most (the description says the rest)");
            }
            if (s.group == null || s.group.isBlank()) {
                throw new IllegalStateException("setting " + s.key + " has no group: declare it with .group(\"Walking\") or the like");
            }
            if (s.level == null) {
                throw new IllegalStateException("setting " + s.key + " has no level: declare it with .basic() or .advanced()");
            }
            if (s.description.isEmpty() || !Character.isUpperCase(s.description.charAt(0)) || !s.description.endsWith(".")) {
                throw new IllegalStateException("setting " + s.key + ": its description is full sentences for players,"
                        + " a capital first and a period at the end (\"It sprints when it walks.\")");
            }
        }
    }

    /** The one called so, or null. */
    Setting get(String key) {
        return declared.get(key);
    }

    Collection<Setting> all() {
        return Collections.unmodifiableCollection(declared.values());
    }

    /**
     * The settings of one level, by group: the groups in the order their first setting of
     * that level was declared (the abilities' order), each with its settings in theirs.
     * What a page of the menu shows.
     */
    Map<String, List<Setting>> groups(Level level) {
        Map<String, List<Setting>> out = new LinkedHashMap<>();
        for (Setting s : declared.values()) {
            if (s.level == level) out.computeIfAbsent(s.group, g -> new ArrayList<>()).add(s);
        }
        return out;
    }

    // --- reading ---------------------------------------------------------------------------

    /** Whether a switch is on for the bot. */
    static boolean bool(Bots.Bot p, String key) {
        Settings all = Abilities.settings();
        Setting s = all.declared(key);
        if (!s.isSwitch) throw new IllegalArgumentException("setting " + key + " is not a switch");
        return all.value(p.data, s) != 0;
    }

    /** A number's value for the bot, within its range. */
    static double number(Bots.Bot p, String key) {
        Settings all = Abilities.settings();
        Setting s = all.declared(key);
        if (s.isSwitch || s.isChoice()) throw new IllegalArgumentException("setting " + key + " is not a number");
        return all.value(p.data, s);
    }

    /** A choice's option for the bot, by its name, as declared: compare it with the names the ability declared. */
    static String choice(Bots.Bot p, String key) {
        Settings all = Abilities.settings();
        Setting s = all.declared(key);
        if (!s.isChoice()) throw new IllegalArgumentException("setting " + key + " is not a choice");
        return s.words(all.value(p.data, s));
    }

    private Setting declared(String key) {
        Setting s = declared.get(key);
        if (s == null) throw new IllegalArgumentException("no setting " + key + " was declared");
        return s;
    }

    /** Its value in {@code data}: the bot's own, else the server's default. A switch's is 1 or 0. */
    double value(BotData data, String key) {
        return value(data, declared(key));
    }

    double value(BotData data, Setting s) {
        Double own = own(data, s);
        return own != null ? own : serverDefault(s);
    }

    /** Which layer the bot's value comes from. */
    From from(BotData data, Setting s) {
        return own(data, s) != null ? From.OWN : defaultFrom(s);
    }

    /** The bot's own value, or null when it has none. Reading makes no section. */
    Double own(BotData data, Setting s) {
        JsonElement e = data.read(SECTION).get(s.key);
        return e == null ? null : s.read(e);
    }

    /**
     * The server's default, for every bot without a value of its own: the one set in game,
     * else tachyon.properties' {@code default.<key>}, else the declared one.
     */
    double serverDefault(Setting s) {
        Double set = inGame(s);
        return set != null ? set : withoutGame(s);
    }

    /** Which layer the server's default comes from: set in game, tachyon.properties, or the mod. */
    From defaultFrom(Setting s) {
        if (inGame(s) != null) return From.GAME;
        return fileDefault(s) != null ? From.FILE : From.MOD;
    }

    /**
     * The server's default as it is without the one set in game: tachyon.properties', else
     * the declared one. What clearing the one set in game goes back to.
     */
    double withoutGame(Setting s) {
        Double file = fileDefault(s);
        return file != null ? file : s.byDefault;
    }

    /** Which layer {@link #withoutGame} comes from: tachyon.properties, or the mod. */
    From fileOrMod(Setting s) {
        return fileDefault(s) != null ? From.FILE : From.MOD;
    }

    /** The default set in game, or null when there is none (or no server runs). */
    Double inGame(Setting s) {
        return game == null ? null : own(game, s);
    }

    /** tachyon.properties' {@code default.<key>}, parsed and within range; null when it has none that is a value. */
    private Double fileDefault(Setting s) {
        Optional<Double> known = fileDefaults.get(s.key);
        if (known == null) {
            known = Optional.ofNullable(readFileDefault(s));
            fileDefaults.put(s.key, known);
        }
        return known.orElse(null);
    }

    /** tachyon.properties was read again: the server's defaults are read again from it, as they are next asked for. */
    void forget() {
        fileDefaults.clear();
    }

    private Double readFileDefault(Setting s) {
        String text = server.apply(s.key);
        if (text == null || text.isBlank()) return null;
        Double v = s.parse(text);
        if (v == null) {
            if (warned.add(s.key + "=" + text)) {
                LOG.warn("[tachyon] tachyon.properties: default.{}={} is no value for it ({}): it is not used",
                        s.key, text, s.takes());
            }
            return null;
        }
        return s.clamp(v);
    }

    // --- the defaults set in game ------------------------------------------------------------

    /**
     * Where the server's defaults set in game are kept: {@code <world>/tachyon/defaults.json},
     * with the world, so that a copied world keeps them. A store kept as the shared ones are
     * ({@link BotData#shared}): read once as the server starts, written whole or not at all,
     * moved aside when it is broken (and then there are none). On the server's thread.
     */
    static BotData gameStore(MinecraftServer server) {
        return BotData.shared(server.getWorldPath(LevelResource.ROOT).resolve("tachyon").normalize(), "defaults",
                "the server's list of defaults set in game");
    }

    /** The defaults set in game from now on: the world's store as the server starts, null once it stops. */
    void game(BotData store) {
        this.game = store;
    }

    // --- changing ---------------------------------------------------------------------------

    /**
     * The bot's own value, from words; {@code default} clears it, and the server's default
     * is its value again.
     *
     * @return why not (no such setting, or words that are no value for it, said with
     *         what it takes), or null once done
     */
    String set(BotData data, String key, String text) {
        Setting s = declared.get(key);
        if (s == null) return "no setting " + key + "; there are: " + String.join(", ", declared.keySet());
        JsonObject own = data.section(SECTION);
        if (text.trim().equalsIgnoreCase("default")) {
            if (own.remove(s.key) != null) data.changed();
            return null;
        }
        Double v = s.parse(text);
        if (v == null || !s.within(v)) return s.takes();
        own.add(s.key, s.kept(v));
        data.changed();
        return null;
    }

    /**
     * A bot's own value changed by someone: the checks {@code /tachyon set} makes, and the
     * menu with it. A setting of operators' only by an operator; then words that are a value
     * within its range, or {@code default} ({@link #set}).
     *
     * @param operator whether whoever changes it is an operator
     * @return why not, in words for them, or null once done
     */
    String change(BotData data, String key, String text, boolean operator) {
        Setting s = declared.get(key);
        String refused = s == null ? null : mayChange(s, operator);
        return refused != null ? refused : set(data, key, text);
    }

    /**
     * Why whoever it is may not change a bot's {@code s}, or null when they may: a setting of
     * operators' is theirs only. Said by its label, as the menu shows it.
     */
    String mayChange(Setting s, boolean operator) {
        return s.who == Who.OPERATOR && !operator ? "only operators change " + s.named() : null;
    }

    /** Why whoever it is may not change the server's defaults, or null when they may: operators only. */
    String mayChangeDefault(boolean operator) {
        return operator ? null : "only operators change the server's defaults";
    }

    /**
     * The server's default set in game, from words, for every bot without a value of its
     * own: the checks {@code /tachyon defaults} makes, and the menu with it. Operators only;
     * then words that are a value within its range, or {@code default}, which clears it
     * (tachyon.properties' or the mod's is the default again). Written at once, on the
     * writer's thread.
     *
     * @return why not, in words for them, or null once done
     */
    String changeDefault(String key, String text, boolean operator) {
        String refused = mayChangeDefault(operator);
        if (refused != null) return refused;
        if (game == null) return "the server's defaults are not open: the world is not running";
        refused = set(game, key, text);
        if (refused == null) game.saveLater();
        return refused;
    }

    // --- the commands ------------------------------------------------------------------------

    /**
     * {@code /tachyon settings <who>}: each setting, its value and where it comes from.
     * {@code /tachyon set <who> <key> <value|default>}: one changed. Anyone may use them on
     * the bots they may order (see Bots.find); a setting of operators', only operators.
     * {@code /tachyon defaults [<key> <value|default>]}: the server's defaults, listed, or
     * one set in game; operators only.
     */
    static void commands(LiteralArgumentBuilder<CommandSourceStack> tachyon) {
        tachyon.then(Commands.literal("settings")
                        .then(Bots.who().executes(Settings::list)))
                .then(Commands.literal("set")
                        .then(Bots.who()
                                .then(Commands.argument("key", StringArgumentType.word())
                                        .suggests(Settings::keys)
                                        .then(Commands.argument("value", StringArgumentType.word())
                                                .suggests(Settings::values)
                                                .executes(Settings::set)))))
                .then(Commands.literal("defaults")
                        .requires(Bots::operator)
                        .executes(Settings::defaults)
                        .then(Commands.argument("key", StringArgumentType.word())
                                .suggests(Settings::keys)
                                .then(Commands.argument("value", StringArgumentType.word())
                                        .suggests(Settings::values)
                                        .executes(Settings::setDefault))));
    }

    private static int list(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        List<Bots.Bot> them = Bots.find(c);
        if (them.isEmpty()) return 0;
        Settings all = Abilities.settings();
        List<String> lines = new ArrayList<>();
        for (Bots.Bot p : them) {
            lines.add(p.name() + "'s settings (the key in brackets, for /tachyon set):");
            for (Setting s : all.all()) lines.add(line(s, all.value(p.data, s), all.from(p.data, s)));
        }
        Bots.say(c.getSource(), String.join("\n", lines));
        return them.size();
    }

    /**
     * A setting in a line, as the commands list it: its label and key, its value and where
     * that comes from, who changes it when that is operators only, and what it does.
     */
    private static String line(Setting s, double value, From from) {
        return "  " + s.label + " [" + s.key + "]: " + s.words(value) + " (" + from.words + ")"
                + (s.who == Who.OPERATOR ? ", operators only" : "") + ". " + s.description;
    }

    private static int set(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        List<Bots.Bot> them = Bots.find(c);
        if (them.isEmpty()) return 0;
        Settings all = Abilities.settings();
        String key = StringArgumentType.getString(c, "key"), value = StringArgumentType.getString(c, "value");
        boolean operator = Bots.operator(c.getSource());
        // The words are the same for every bot: refused for the first, refused for all,
        // and nothing changed.
        for (Bots.Bot p : them) {
            String refused = all.change(p.data, key, value, operator);
            if (refused != null) return Bots.fail(c.getSource(), refused);
        }
        Setting s = all.get(key);
        if (value.equalsIgnoreCase("default")) {
            return Bots.told(c, them, s.label + ": back to the server default, " + s.words(all.serverDefault(s))
                    + " (" + all.defaultFrom(s).words + ")");
        }
        return Bots.told(c, them, s.label + ": " + s.words(all.value(them.get(0).data, s)) + " (its own)");
    }

    /** {@code /tachyon defaults}: every setting's server default, and where it comes from. */
    private static int defaults(CommandContext<CommandSourceStack> c) {
        Settings all = Abilities.settings();
        List<String> lines = new ArrayList<>();
        lines.add("the server's defaults, for every bot without a value of its own (the key in brackets, for"
                + " /tachyon defaults):");
        for (Setting s : all.all()) lines.add(line(s, all.serverDefault(s), all.defaultFrom(s)));
        return Bots.say(c.getSource(), String.join("\n", lines));
    }

    /** {@code /tachyon defaults <key> <value|default>}: one server default set in game, or cleared. */
    private static int setDefault(CommandContext<CommandSourceStack> c) {
        Settings all = Abilities.settings();
        String key = StringArgumentType.getString(c, "key"), value = StringArgumentType.getString(c, "value");
        String refused = all.changeDefault(key, value, Bots.operator(c.getSource()));
        if (refused != null) return Bots.fail(c.getSource(), refused);
        Setting s = all.get(key);
        return Bots.say(c.getSource(), s.label + ", the server's default: " + s.words(all.serverDefault(s)) + " ("
                + all.defaultFrom(s).words + "), for every bot without a value of its own");
    }

    /** The keys, those of operators' only for operators. */
    private static CompletableFuture<Suggestions> keys(CommandContext<CommandSourceStack> c, SuggestionsBuilder b) {
        boolean operator = Bots.operator(c.getSource());
        return SharedSuggestionProvider.suggest(Abilities.settings().all().stream()
                .filter(s -> operator || s.who == Who.OWNER).map(s -> s.key), b);
    }

    /** A switch's on and off; a choice's options; a number's ends and default; and default. */
    private static CompletableFuture<Suggestions> values(CommandContext<CommandSourceStack> c, SuggestionsBuilder b) {
        Setting s = Abilities.settings().get(StringArgumentType.getString(c, "key"));
        List<String> words = new ArrayList<>();
        if (s != null && s.isSwitch) {
            words.add("on");
            words.add("off");
        } else if (s != null && s.isChoice()) {
            words.addAll(s.options);
        } else if (s != null) {
            words.add(s.words(s.min));
            words.add(s.words(s.byDefault));
            words.add(s.words(s.max));
        }
        words.add("default");
        return SharedSuggestionProvider.suggest(words.stream().distinct(), b);
    }
}
