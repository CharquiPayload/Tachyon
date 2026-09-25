package tachyon;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A bot's brain: what is said to it goes to a model, with the bot's state and its
 * tools (every ability's, see {@link Abilities}); the tools the model calls are run by
 * the server, as orders; its words are said in the chat.
 *
 * <p>The model is asked on a thread of its own, never on the server's: a slow model is a
 * slow answer, not a slow server. What it is told of the bot (its instructions, its
 * state, the tools it is offered) is read on the server's thread as each turn starts,
 * and the tools run there, where the world is. One thing at a time: what is said while
 * it thinks waits, the last of it. So does what is said while it lies dead, the 2 s
 * before it comes back ({@link #back}): a turn is about a bot that can do something.
 *
 * <p>An order given in the chat that is over by itself (done, or given up) is news: the
 * brain is told, with no tools, and says how it went to whoever gave it. What its body has
 * to tell its owner unasked (its things got back after a death, a player hitting it) is a
 * report ({@link #report}, through {@link Notices}): told the same way, with no tools, in
 * one call. What an ability notices (hunger, a reminder due) is a notice ({@link #notice}):
 * the brain is told, with its tools, and what it starts is told to its owner.
 */
final class Brain {

    private static final Logger LOG = LogUtils.getLogger();
    /** The turns kept to give the model the thread of the conversation. */
    private static final int HISTORY = 6;
    /** The notices that wait while it thinks, at most: the oldest are dropped. */
    private static final int NOTICES_MAX = 4;
    /** A line said in the chat is cut here, and at most this many lines. */
    private static final int LINE_MAX = 240, LINES_MAX = 3;

    private static final AtomicInteger THREAD_N = new AtomicInteger();
    private static final ExecutorService THINK = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "tachyon-brain-" + THREAD_N.incrementAndGet());
        t.setDaemon(true);
        return t;
    });

    private static volatile BrainConfig config;

    static BrainConfig config() {
        if (config == null) config = BrainConfig.load();
        return config;
    }

    static void reload() {
        config = BrainConfig.load();
        Abilities.settings().forget();
    }

    /** What a turn is for. */
    private enum Kind {
        /** Words said to it, by a player or the console. */
        WORDS,
        /** What came of an order {@code who} gave it: told, with no tools. */
        NEWS,
        /** Something an ability noticed: told, with its tools, on its owner's behalf. */
        NOTICE,
        /** What its body has to tell its owner unasked ({@link Notices}): told, with no tools, as news is. */
        REPORT
    }

    /** Said to it, or news, or a notice; {@code who} is the one it answers (null: the console, or nobody). */
    private record Said(UUID who, String name, String text, Kind kind) {
    }

    /**
     * What the model is told of the bot as a turn starts: read on the server's thread.
     * {@code offered}: the names of the tools it is sent, the only ones a call of this turn
     * may run.
     */
    private record Start(String prompt, String state, JsonArray tools, Set<String> offered) {
    }

    /**
     * A turn as it went: what it heard (without the state, which is old news by then),
     * then its answers, tool calls and their results. The calls stay: a history of words
     * alone teaches the model that answering "on it" is doing it.
     */
    private record Turn(List<JsonObject> messages) {
    }

    private final Bots.Bot p;
    private final MinecraftServer server;
    private final Deque<Turn> history = new ArrayDeque<>();
    private Future<?> thinking;
    private Said waiting;
    private final Deque<Said> notices = new ArrayDeque<>();
    /** Why its last turn failed (the model did not answer, say), or null when it went well. */
    private volatile String failure;

    Brain(Bots.Bot p) {
        this.p = p;
        this.server = p.body.getServer();
    }

    boolean busy() {
        return thinking != null && !thinking.isDone();
    }

    /** Whether a turn waits instead of starting now: one runs already, or its bot lies dead. On the server's thread. */
    private boolean waits() {
        return busy() || !p.body.isAlive();
    }

    /** Why its last turn failed, or null when it went well (or there was none). */
    String failure() {
        return failure;
    }

    /** Said to it, by a player (or by the console, {@code who} null). On the server's thread. */
    void hear(UUID who, String name, String text) {
        if (config().url(p.name()).isEmpty()) {
            tell(who, p.name() + " has no brain set up (tachyon.properties: url)");
            return;
        }
        Said said = new Said(who, name, text, Kind.WORDS);
        if (waits()) {
            waiting = said;         // the last thing said, after this one (or once it is back)
            return;
        }
        thinking = THINK.submit(() -> think(said));
    }

    /** An order {@code who} gave it, over by itself: {@code how} it went. On the server's thread. */
    void over(UUID who, String name, String how) {
        if (config().url(p.name()).isEmpty()) return;
        Said news = new Said(who, name, how, Kind.NEWS);
        if (waits()) {
            if (waiting == null) waiting = news;        // never instead of what a player said
            return;
        }
        thinking = THINK.submit(() -> think(news));
    }

    /**
     * Something an ability noticed (hungry, hurt, a reminder due), in words for the model:
     * what happened and what it may do about it ("You are hungry (food 5/20): eat if you
     * carry food; tell {@code owner} only if you cannot."). The brain is told with its
     * tools, on its owner's behalf: what it starts is told to its owner when it is over.
     * Every notice is a paid call to a model: an ability sends one when it matters, and
     * not again for the same thing for a while. While it thinks, a few wait, after what
     * was said to it. On the server's thread.
     */
    void notice(String text) {
        if (config().url(p.name()).isEmpty()) return;
        Said n = new Said(p.owner, p.ownerName == null ? "nobody" : p.ownerName, text, Kind.NOTICE);
        if (waits()) {
            if (notices.size() >= NOTICES_MAX) notices.removeFirst();
            notices.addLast(n);
            return;
        }
        thinking = THINK.submit(() -> think(n));
    }

    /**
     * What its body has to tell its owner unasked, in words for the model (see
     * {@link Notices#say}, which decides whether it is said, and how): its brain tells them
     * in its own words, in one call with no tools, as it tells news of an order. It waits
     * with the notices while it thinks. On the server's thread.
     */
    void report(String text) {
        if (config().url(p.name()).isEmpty()) return;
        Said r = new Said(p.owner, p.ownerName == null ? "nobody" : p.ownerName, text, Kind.REPORT);
        if (waits()) {
            if (notices.size() >= NOTICES_MAX) notices.removeFirst();
            notices.addLast(r);
            return;
        }
        thinking = THINK.submit(() -> think(r));
    }

    void stop() {
        if (thinking != null) thinking.cancel(true);
        waiting = null;
        notices.clear();
    }

    /**
     * Its bot is back from the dead, in a new body: what was said to it meanwhile (or news,
     * or a notice) is thought about now, unless a turn still runs (it follows that one). On
     * the server's thread, from Bots.respawn.
     */
    void back() {
        if (!busy()) next();
    }

    // --- a turn, on a brain thread -------------------------------------------------------

    private void think(Said said) {
        BrainConfig cfg = config();
        String name = p.name();
        Llm llm = new Llm(cfg.anthropic(), cfg.url(name), cfg.model(name), cfg.key(name), cfg.timeoutSeconds(name));
        try {
            // Everything read of the bot, read on the server's thread: its data and the
            // abilities' state are the server's, and change there.
            Start start = onServer(() -> start(said));
            List<JsonObject> msgs = new ArrayList<>();
            msgs.add(message("system", start.prompt()));
            for (Turn t : history) msgs.addAll(t.messages());
            String heard = switch (said.kind()) {
                case NEWS -> "(Nobody spoke: news. What " + said.name() + " asked you to do is over: " + said.text()
                        + ". Tell them how it went, in one short sentence, in the language they write to you in:"
                        + " only what this says happened, nothing more.)";
                case NOTICE -> "(Nobody spoke: a notice. " + said.text() + ")";
                case REPORT -> "(Nobody spoke: news from your own body, for " + said.name() + ", your owner: "
                        + said.text() + ". Tell " + said.name() + " in one or two short sentences, in the language"
                        + " they write to you in, in your own words: only what this says, nothing more.)";
                case WORDS -> said.name() + ": " + said.text();
            };
            msgs.add(message("user", "[" + start.state() + "]\n" + heard));
            int from = msgs.size();

            String answer = "";
            // News and reports are only told: no tools, so the first answer is words (one call).
            int steps = said.kind() == Kind.NEWS || said.kind() == Kind.REPORT ? 0 : cfg.steps();
            for (int step = 0; ; step++) {
                // The last step has no tools: whatever was done, it answers in words.
                Llm.Reply r = llm.ask(msgs, step < steps ? start.tools() : new JsonArray());
                msgs.add(r.message());
                if (r.calls().isEmpty() || step >= steps) {
                    // Calls asked for with no tools offered are not run: kept, they would
                    // be calls with no results in the history, which APIs refuse.
                    if (!r.calls().isEmpty()) msgs.set(msgs.size() - 1, message("assistant", r.text()));
                    answer = r.text().strip();
                    break;
                }
                for (Llm.ToolCall call : r.calls()) {
                    String result = result(onServer(() -> run(call, said, start.offered())), cfg.timeoutSeconds(name));
                    LOG.info("[tachyon] {} used {} {}: {}", name, call.name(), call.args(), result);
                    JsonObject tool = new JsonObject();
                    tool.addProperty("role", "tool");
                    tool.addProperty("tool_call_id", call.id());
                    tool.addProperty("content", result);
                    msgs.add(tool);
                }
            }
            String said2 = answer;
            server.execute(() -> say(said2));
            List<JsonObject> turn = new ArrayList<>();
            turn.add(message("user", heard));
            turn.addAll(msgs.subList(from, msgs.size()));
            history.addLast(new Turn(turn));
            while (history.size() > HISTORY) history.removeFirst();
            failure = null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            failure = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            LOG.warn("[tachyon] {} could not think", name, e);
            String why = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            // A report is still told, in its plain words: what the body had to say is not lost with the model.
            String line = said.kind() == Kind.REPORT ? name + ": " + said.text() + " (its brain could not say it: " + why + ")"
                    : name + " could not think: " + why;
            server.execute(() -> tell(said.who(), line));
        } finally {
            server.execute(this::next);
        }
    }

    /**
     * What was said while it thought, now: started here and not through hear, since the
     * turn that ran this may not count as done yet, and it would wait for nothing. While its
     * bot lies dead it keeps waiting, for {@link #back}; once the bot left, it is dropped.
     */
    private void next() {
        if (p.leaving() != null || !p.body.isAlive()) return;
        Said w = waiting != null ? waiting : notices.pollFirst();
        waiting = null;
        if (w != null && !config().url(p.name()).isEmpty()) {
            thinking = THINK.submit(() -> think(w));
        }
    }

    private <T> T onServer(java.util.function.Supplier<T> work) throws Exception {
        return server.submit(work).get(10, TimeUnit.SECONDS);
    }

    private static JsonObject message(String role, String text) {
        JsonObject m = new JsonObject();
        m.addProperty("role", role);
        m.addProperty("content", text);
        return m;
    }

    // --- what the model is told -------------------------------------------------------

    /**
     * What the model is told of the bot as a turn starts: its instructions (the tools'
     * and the abilities' lines after the mod's own), its state, and the tools it is
     * offered. On the server's thread.
     */
    private Start start(Said said) {
        List<Tool> offered = Abilities.tools().offered(p);
        List<String> rules = new ArrayList<>();
        for (Tool t : offered) {
            if (t.rule() != null) rules.add(t.rule());
        }
        Abilities.rules(p, rules);
        String prompt = rules.isEmpty() ? prompt() : prompt() + "\n" + String.join("\n", rules);
        Set<String> names = new HashSet<>();
        for (Tool t : offered) names.add(t.name);
        return new Start(prompt, state(said), Abilities.tools().json(offered), names);
    }

    private String prompt() {
        return "You are " + p.name() + ", a player in a Minecraft world. You live on the server: a mod runs "
                + "you, with a real player's body (you walk, jump, swim, fight and break blocks as a player "
                + "does). People talk to you in the game's chat.\n"
                + "You act only through your tools. When someone asks you to do something you can do, call "
                + "the tool that does it; then answer in one or two short sentences, in the language they "
                + "wrote in, as a friendly companion would in a game chat. Never show tool names, JSON or "
                + "coordinates they did not ask for. If your tools cannot do what they ask, say so plainly "
                + "and do not pretend. Your tools START things that take time (walking, following, hunting, "
                + "clearing): after calling one, say you are on it, never that it is done; to know how it "
                + "goes, use status. Never claim progress your state or a tool does not show. Each message starts with your state in brackets: use it, do not "
                + "repeat it.";
    }

    /** Where it is and how, and who speaks: at the head of what is said. On the server's thread. */
    private String state(Said said) {
        BotPlayer b = p.body;
        StringBuilder s = new StringBuilder();
        s.append("you are at ").append(pos(b.blockPosition()))
                .append(", health ").append(Math.round(b.getHealth())).append("/20")
                .append(", food ").append(b.getFoodData().getFoodLevel()).append("/20")
                .append("; doing: ").append(p.doing)
                .append("; in hand: ").append(item(b.getMainHandItem()))
                .append("; carrying: ").append(inventory(b));
        ServerPlayer speaker = said.who() == null ? null : server.getPlayerList().getPlayer(said.who());
        if (speaker != null && speaker != b) {
            s.append("; ").append(said.name()).append(" is ").append(Math.round(speaker.distanceTo(b)))
                    .append(" blocks ").append(direction(b.getX(), b.getZ(), speaker.getX(), speaker.getZ()));
        }
        List<String> parts = new ArrayList<>();
        Abilities.state(p, parts);
        for (String part : parts) s.append("; ").append(part);
        return s.toString();
    }

    // --- the tools ----------------------------------------------------------------------

    // The tools are the abilities' (see Abilities): what the model is sent, and what runs.

    /**
     * A tool the model called, started as an order; what comes of it, in words for the
     * model. Only one it was sent this turn ({@code offered}): a tool a lite brain is not
     * sent, or one not offered to this bot, is no tool to it, even when the model names it
     * (from an earlier turn in its history, or a guess). On the server's thread: most
     * answer here and now, a later one elsewhere.
     */
    private CompletableFuture<String> run(Llm.ToolCall call, Said said, Set<String> offered) {
        ServerPlayer speaker = said.who() == null ? null : server.getPlayerList().getPlayer(said.who());
        return Abilities.tools().start(call.name(), new Tool.Call(p, call.args(), speaker, said.name(), by(said)), offered);
    }

    /**
     * What a tool came to, waited for here, on the brain's thread: at once for most; a
     * later one (a search on a thread of its own) as long as a model's answer may take.
     */
    private static String result(CompletableFuture<String> started, int seconds) throws InterruptedException {
        try {
            return started.get(seconds, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            started.cancel(true);
            return "that could not be done: it took too long";
        } catch (ExecutionException e) {
            return Tools.failed(e);
        }
    }

    /**
     * An order from the chat, or from a notice: its brain tells how it went (from the
     * console, or for a bot nobody owns: nobody to tell).
     */
    private static Bots.Order by(Said said) {
        return said.who() == null ? null : new Bots.Order(said.who(), said.name(), true);
    }

    // --- words for the model, the tools' too --------------------------------------------

    /** What it carries, by name and count: 12 kinds at most. */
    static String inventory(BotPlayer b) {
        Map<String, Integer> count = new LinkedHashMap<>();
        for (ItemStack st : b.getInventory().items) {
            if (!st.isEmpty()) count.merge(st.getHoverName().getString(), st.getCount(), Integer::sum);
        }
        if (count.isEmpty()) return "nothing";
        List<String> out = new ArrayList<>();
        count.forEach((k, v) -> out.add(v + " " + k));
        return String.join(", ", out.size() > 12 ? out.subList(0, 12) : out) + (out.size() > 12 ? ", ..." : "");
    }

    static String item(ItemStack st) {
        return st.isEmpty() ? "nothing" : st.getHoverName().getString();
    }

    static String pos(BlockPos p) {
        return p.getX() + " " + p.getY() + " " + p.getZ();
    }

    /** Where (x2, z2) is from (x1, z1), in the eight winds: north is -Z. */
    static String direction(double x1, double z1, double x2, double z2) {
        double dx = x2 - x1, dz = z2 - z1;
        if (Math.abs(dx) < 1 && Math.abs(dz) < 1) return "here";
        String ns = dz < -Math.abs(dx) / 2.4 ? "north" : dz > Math.abs(dx) / 2.4 ? "south" : "";
        String ew = dx > Math.abs(dz) / 2.4 ? "east" : dx < -Math.abs(dz) / 2.4 ? "west" : "";
        return "to the " + ns + (ns.isEmpty() || ew.isEmpty() ? "" : "-") + ew;
    }

    // --- words ----------------------------------------------------------------------------

    /**
     * Its answer in the chat, as a player's line: {@code <Name> ...}. Not once its bot left
     * the game; but said while it lies dead (its corpse is out of the level after a second):
     * it comes back, and the answer was paid for.
     */
    private void say(String text) {
        if (text.isEmpty() || p.leaving() != null) return;
        int n = 0;
        for (String line : text.split("\\R")) {
            line = line.strip();
            if (line.isEmpty()) continue;
            if (line.length() > LINE_MAX) line = line.substring(0, LINE_MAX - 1) + "…";
            server.getPlayerList().broadcastSystemMessage(Component.literal("<" + p.name() + "> " + line), false);
            if (++n >= LINES_MAX) break;
        }
    }

    /** A word to the one who spoke only (or to the log, for the console). */
    private void tell(UUID who, String text) {
        ServerPlayer pl = who == null ? null : server.getPlayerList().getPlayer(who);
        if (pl != null) pl.sendSystemMessage(Component.literal("[tachyon] " + text));
        else LOG.info("[tachyon] {}", text);
    }
}
