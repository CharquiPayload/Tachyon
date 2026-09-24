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
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * The switches and numbers the abilities declare, which say how a bot goes about what it
 * does (whether it sprints, say), each bot with values of its own.
 *
 * <p>Two layers. Under, the server's default: {@code default.<key>=...} in
 * {@code tachyon.properties}, or else the one the ability declared. Over it, the bot's
 * own value, set with {@code /tachyon set} and kept in its data (section
 * {@code settings}), so it stays across leaving and coming back. Who may change a
 * setting is declared with it: its owner (and operators), or operators only.
 *
 * <p>The code reads them with {@link #bool(Bots.Bot, String)} and
 * {@link #number(Bots.Bot, String)}, on the server's thread, as a bot's data is. A read is
 * a lookup in the bot's data, then, when it has no value of its own, in the server's
 * defaults, parsed once and kept until {@code /tachyon brain reload}. A number read is
 * always within its range: one out of it (a server default, a file edited by hand) is
 * brought to the nearest end; the command refuses it, saying the range.
 */
final class Settings {

    private static final Logger LOG = LogUtils.getLogger();
    /** The section of a bot's data its own values are kept in. */
    static final String SECTION = "settings";
    private static final Pattern KEY = Pattern.compile("[a-z][a-z0-9_]*");
    private static final Pattern NUMBER = Pattern.compile("-?\\d+(\\.\\d+)?");
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
     * One setting, as declared: a switch (true or false), or a number within its range.
     * Values are held as numbers either way: a switch's are 1 and 0.
     */
    static final class Setting {
        final String key;
        final boolean isSwitch;
        final double byDefault, min, max;
        /** What it decides, as "whether it may sprint when walking". */
        final String description;
        final Who who;

        private Setting(String key, boolean isSwitch, double byDefault, double min, double max, String description, Who who) {
            this.key = key;
            this.isSwitch = isSwitch;
            this.byDefault = byDefault;
            this.min = min;
            this.max = max;
            this.description = description;
            this.who = who;
        }

        /** A value in words: true or false, or the number (a whole one without its ".0"). */
        String words(double v) {
            if (isSwitch) return v != 0 ? "true" : "false";
            return v == Math.rint(v) && Math.abs(v) < 1e15 ? String.valueOf((long) v) : String.valueOf(v);
        }

        /** What it takes, in words, for a refusal. */
        String takes() {
            return isSwitch ? key + " is true or false (or default)"
                    : key + " is a number from " + words(min) + " to " + words(max) + " (or default)";
        }

        /** Words as a value (a number in or out of its range), or null when they are none. */
        Double parse(String text) {
            String t = text.trim().toLowerCase(Locale.ROOT);
            if (isSwitch) {
                return switch (t) {
                    case "true", "on", "yes" -> 1.0;
                    case "false", "off", "no" -> 0.0;
                    default -> null;
                };
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
            return v.isNumber() ? clamp(v.getAsDouble()) : null;
        }
    }

    private final Map<String, Setting> declared = new LinkedHashMap<>();
    /** A key's server default as written in tachyon.properties, {@code default.<key>} (null: none). */
    private final Function<String, String> server;
    /** The server defaults that could not be read, said once each. */
    private final Set<String> warned = ConcurrentHashMap.newKeySet();
    /** The server defaults as read from tachyon.properties, parsed: read once, until {@link #forget}. */
    private final Map<String, Double> defaults = new ConcurrentHashMap<>();

    Settings(Function<String, String> server) {
        this.server = server;
    }

    // --- declaring: from Ability.settings ------------------------------------------------

    /** A switch. */
    Setting bool(String key, boolean byDefault, String description, Who who) {
        return declare(new Setting(key, true, byDefault ? 1 : 0, 0, 1, description, who));
    }

    /** A number, from {@code min} to {@code max}. */
    Setting number(String key, double byDefault, double min, double max, String description, Who who) {
        if (!(min <= byDefault && byDefault <= max)) {
            throw new IllegalArgumentException("setting " + key + ": its default " + byDefault + " is not within " + min + " to " + max);
        }
        return declare(new Setting(key, false, byDefault, min, max, description, who));
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

    /** The one called so, or null. */
    Setting get(String key) {
        return declared.get(key);
    }

    Collection<Setting> all() {
        return Collections.unmodifiableCollection(declared.values());
    }

    // --- reading ---------------------------------------------------------------------------

    /** Whether a switch is on for the bot. */
    static boolean bool(Bots.Bot p, String key) {
        Settings all = Abilities.settings();
        Setting s = all.declared(key);
        if (!s.isSwitch) throw new IllegalArgumentException("setting " + key + " is a number, not a switch");
        return all.value(p.data, s) != 0;
    }

    /** A number's value for the bot, within its range. */
    static double number(Bots.Bot p, String key) {
        Settings all = Abilities.settings();
        Setting s = all.declared(key);
        if (s.isSwitch) throw new IllegalArgumentException("setting " + key + " is a switch, not a number");
        return all.value(p.data, s);
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

    /** The bot's own value, or null when it has none. Reading makes no section. */
    Double own(BotData data, Setting s) {
        JsonElement e = data.read(SECTION).get(s.key);
        return e == null ? null : s.read(e);
    }

    /** The server's default: tachyon.properties' {@code default.<key>}, else the declared one. */
    double serverDefault(Setting s) {
        Double known = defaults.get(s.key);
        if (known != null) return known;
        double v = readServerDefault(s);
        defaults.put(s.key, v);
        return v;
    }

    /** tachyon.properties was read again: the server's defaults are read again from it, as they are next asked for. */
    void forget() {
        defaults.clear();
    }

    private double readServerDefault(Setting s) {
        String text = server.apply(s.key);
        if (text == null || text.isBlank()) return s.byDefault;
        Double v = s.parse(text);
        if (v == null) {
            if (warned.add(s.key + "=" + text)) {
                LOG.warn("[tachyon] tachyon.properties: default.{}={} is no value for it ({}): {} is used",
                        s.key, text, s.takes(), s.words(s.byDefault));
            }
            return s.byDefault;
        }
        return s.clamp(v);
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
        if (s.isSwitch) own.addProperty(s.key, v != 0);
        else if (v == Math.rint(v) && Math.abs(v) < 1e15) own.addProperty(s.key, (long) (double) v);
        else own.addProperty(s.key, v);
        data.changed();
        return null;
    }

    // --- the commands ------------------------------------------------------------------------

    /**
     * {@code /tachyon settings <who>}: each setting, its value and where it comes from.
     * {@code /tachyon set <who> <key> <value|default>}: one changed. Anyone may use them on
     * the bots they may order (see Bots.find); a setting of operators', only operators.
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
                                                .executes(Settings::set)))));
    }

    private static int list(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        List<Bots.Bot> them = Bots.find(c);
        if (them.isEmpty()) return 0;
        Settings all = Abilities.settings();
        List<String> lines = new ArrayList<>();
        for (Bots.Bot p : them) {
            lines.add(p.name() + "'s settings:");
            for (Setting s : all.all()) {
                lines.add("  " + s.key + " = " + s.words(all.value(p.data, s))
                        + (all.own(p.data, s) != null ? " (its own)" : " (server default)")
                        + ": " + s.description + (s.who == Who.OPERATOR ? " [operators only]" : ""));
            }
        }
        Bots.say(c.getSource(), String.join("\n", lines));
        return them.size();
    }

    private static int set(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        List<Bots.Bot> them = Bots.find(c);
        if (them.isEmpty()) return 0;
        Settings all = Abilities.settings();
        String key = StringArgumentType.getString(c, "key"), value = StringArgumentType.getString(c, "value");
        Setting s = all.get(key);
        if (s != null && s.who == Who.OPERATOR && !Bots.operator(c.getSource())) {
            return Bots.fail(c.getSource(), "only operators change " + key);
        }
        // The words are the same for every bot: refused for the first, refused for all,
        // and nothing changed.
        for (Bots.Bot p : them) {
            String refused = all.set(p.data, key, value);
            if (refused != null) return Bots.fail(c.getSource(), refused);
        }
        if (value.equalsIgnoreCase("default")) {
            return Bots.told(c, them, key + " is the server default again: " + s.words(all.serverDefault(s)));
        }
        return Bots.told(c, them, key + " = " + s.words(all.value(them.get(0).data, s)) + " (its own)");
    }

    /** The keys, those of operators' only for operators. */
    private static CompletableFuture<Suggestions> keys(CommandContext<CommandSourceStack> c, SuggestionsBuilder b) {
        boolean operator = Bots.operator(c.getSource());
        return SharedSuggestionProvider.suggest(Abilities.settings().all().stream()
                .filter(s -> operator || s.who == Who.OWNER).map(s -> s.key), b);
    }

    /** A switch's true and false; a number's ends and default; and default. */
    private static CompletableFuture<Suggestions> values(CommandContext<CommandSourceStack> c, SuggestionsBuilder b) {
        Setting s = Abilities.settings().get(StringArgumentType.getString(c, "key"));
        List<String> words = new ArrayList<>();
        if (s != null && s.isSwitch) {
            words.add("true");
            words.add("false");
        } else if (s != null) {
            words.add(s.words(s.min));
            words.add(s.words(s.byDefault));
            words.add(s.words(s.max));
        }
        words.add("default");
        return SharedSuggestionProvider.suggest(words.stream().distinct(), b);
    }
}
