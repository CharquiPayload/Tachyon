package tachyon;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Properties;

/**
 * How the bots think: {@code tachyon.properties} in the server folder, written with
 * the defaults the first time.
 *
 * <p>Any key but {@code api}, {@code per_minute} and {@code steps} can be given for one
 * bot as {@code <name>.<key>} ({@code Alice.model=...}): a bot of its own model.
 * The key is read from here and nowhere else; it is never said in the game.
 *
 * <p>The same file holds the server's defaults for the bots' settings, as
 * {@code default.<setting>=...}, under those operators set in game (see {@link Settings}).
 */
final class BrainConfig {

    private static final Logger LOG = LogUtils.getLogger();
    static final Path FILE = Path.of("tachyon.properties");

    private static final String DEFAULTS = """
            # How the bots think.
            #
            # api=openai speaks the OpenAI chat/completions API, with tools: Ollama, OpenAI,
            # OpenRouter, Groq, LM Studio, vLLM and most others take it. url is the base, up to
            # /v1 (the default is an Ollama on this machine). api=anthropic speaks Anthropic's
            # Messages API (url https://api.anthropic.com/v1).
            #
            # Leave url empty to keep the bots silent.
            api=openai
            url=http://localhost:11434/v1
            model=qwen2.5:3b
            key=
            # How long a model may take to answer, in seconds (a small model on a CPU is slow).
            timeout=120
            # How many tool calls a bot may make for one thing said to it.
            steps=6
            # How many times a minute one player may speak to bots (every answer costs).
            per_minute=6
            #
            # Any of url, model, key or timeout for one bot only: <name>.<key>=...
            #
            # A setting's value for every bot that has none of its own (/tachyon settings
            # lists them): default.<setting>=..., as default.sprint=false. One an operator
            # set in game (/tachyon defaults, or /tachyon config) comes before these.
            """;

    private final Properties p;

    private BrainConfig(Properties p) {
        this.p = p;
    }

    static BrainConfig load() {
        Properties p = new Properties();
        try {
            if (!Files.exists(FILE)) {
                try (Writer w = Files.newBufferedWriter(FILE, StandardCharsets.UTF_8)) {
                    w.write(DEFAULTS);
                }
                LOG.info("[tachyon] wrote {} with defaults", FILE.toAbsolutePath());
            }
            try (Reader r = Files.newBufferedReader(FILE, StandardCharsets.UTF_8)) {
                p.load(r);
            }
        } catch (IOException e) {
            LOG.warn("[tachyon] could not read {}: the bots stay silent", FILE, e);
            p.setProperty("url", "");
        }
        return new BrainConfig(p);
    }

    private String get(String bot, String key, String fallback) {
        if (bot != null) {
            String own = p.getProperty(bot + "." + key);
            if (own == null) own = p.getProperty(bot.toLowerCase(Locale.ROOT) + "." + key);
            if (own != null) return own.trim();
        }
        String v = p.getProperty(key);
        return v == null ? fallback : v.trim();
    }

    private int number(String bot, String key, int fallback) {
        try {
            return Integer.parseInt(get(bot, key, String.valueOf(fallback)));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    boolean anthropic() {
        return get(null, "api", "openai").equalsIgnoreCase("anthropic");
    }

    String url(String bot) {
        String u = get(bot, "url", "");
        return u.endsWith("/") ? u.substring(0, u.length() - 1) : u;
    }

    String model(String bot) {
        return get(bot, "model", "");
    }

    String key(String bot) {
        return get(bot, "key", "");
    }

    int timeoutSeconds(String bot) {
        return Math.max(5, number(bot, "timeout", 120));
    }

    int steps() {
        return Math.max(1, number(null, "steps", 6));
    }

    int perMinute() {
        return Math.max(1, number(null, "per_minute", 6));
    }

    /** A setting's server default, {@code default.<setting>}, as written (null: none). */
    String serverDefault(String setting) {
        String v = p.getProperty("default." + setting);
        return v == null ? null : v.trim();
    }

    /** What {@code brain} shows: everything but the key, which is only said to be there or not. */
    String describe(String bot) {
        return (anthropic() ? "anthropic" : "openai") + " at " + (url(bot).isEmpty() ? "(none: silent)" : url(bot))
                + ", model " + model(bot) + ", key " + (key(bot).isEmpty() ? "none" : "set")
                + ", timeout " + timeoutSeconds(bot) + " s, " + steps() + " steps, "
                + perMinute() + " a minute a player";
    }
}
