package tachyon;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A bot's brain: what is said to it goes to a model, with the bot's state and its
 * tools; the tools the model calls are run by the server, as orders; its words are said
 * in the chat.
 *
 * <p>The model is asked on a thread of its own, never on the server's: a slow model is a
 * slow answer, not a slow server. The tools run on the server's thread, where the world
 * is. One thing at a time: what is said while it thinks waits, the last of it.
 *
 * <p>An order given in the chat that is over by itself (done, or given up) is news: the
 * brain is told, with no tools, and says how it went to whoever gave it.
 */
final class Brain {

    private static final Logger LOG = LogUtils.getLogger();
    /** The turns kept to give the model the thread of the conversation. */
    private static final int HISTORY = 6;
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
    }

    /** Said to it; or, {@code news}, what came of an order {@code who} gave it. */
    private record Said(UUID who, String name, String text, boolean news) {
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

    Brain(Bots.Bot p) {
        this.p = p;
        this.server = p.body.getServer();
    }

    boolean busy() {
        return thinking != null && !thinking.isDone();
    }

    /** Said to it, by a player (or by the console, {@code who} null). On the server's thread. */
    void hear(UUID who, String name, String text) {
        if (config().url(p.name()).isEmpty()) {
            tell(who, p.name() + " has no brain set up (tachyon.properties: url)");
            return;
        }
        Said said = new Said(who, name, text, false);
        if (busy()) {
            waiting = said;         // the last thing said, after this one
            return;
        }
        thinking = THINK.submit(() -> think(said));
    }

    /** An order {@code who} gave it, over by itself: {@code how} it went. On the server's thread. */
    void over(UUID who, String name, String how) {
        if (config().url(p.name()).isEmpty()) return;
        Said news = new Said(who, name, how, true);
        if (busy()) {
            if (waiting == null) waiting = news;        // never instead of what a player said
            return;
        }
        thinking = THINK.submit(() -> think(news));
    }

    void stop() {
        if (thinking != null) thinking.cancel(true);
        waiting = null;
    }

    // --- a turn, on a brain thread -------------------------------------------------------

    private void think(Said said) {
        BrainConfig cfg = config();
        String name = p.name();
        Llm llm = new Llm(cfg.anthropic(), cfg.url(name), cfg.model(name), cfg.key(name), cfg.timeoutSeconds(name));
        try {
            List<JsonObject> msgs = new ArrayList<>();
            msgs.add(message("system", prompt()));
            for (Turn t : history) msgs.addAll(t.messages());
            String state = onServer(() -> state(said));
            String heard = said.news()
                    ? "(Nobody spoke: news. What " + said.name() + " asked you to do is over: " + said.text()
                            + ". Tell them how it went, in one short sentence, in the language they write to you in:"
                            + " only what this says happened, nothing more.)"
                    : said.name() + ": " + said.text();
            msgs.add(message("user", "[" + state + "]\n" + heard));
            int from = msgs.size();

            String answer = "";
            // News is only told: no tools, so the first answer is words.
            int steps = said.news() ? 0 : cfg.steps();
            for (int step = 0; ; step++) {
                // The last step has no tools: whatever was done, it answers in words.
                Llm.Reply r = llm.ask(msgs, step < steps ? TOOLS : new JsonArray());
                msgs.add(r.message());
                if (r.calls().isEmpty() || step >= steps) {
                    // Calls asked for with no tools offered are not run: kept, they would
                    // be calls with no results in the history, which APIs refuse.
                    if (!r.calls().isEmpty()) msgs.set(msgs.size() - 1, message("assistant", r.text()));
                    answer = r.text().strip();
                    break;
                }
                for (Llm.ToolCall call : r.calls()) {
                    String result = onServer(() -> run(call, said));
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
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            LOG.warn("[tachyon] {} could not think", name, e);
            String why = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            server.execute(() -> tell(said.who(), name + " could not think: " + why));
        } finally {
            server.execute(this::next);
        }
    }

    /**
     * What was said while it thought, now: started here and not through hear, since the
     * turn that ran this may not count as done yet, and it would wait for nothing.
     */
    private void next() {
        Said w = waiting;
        waiting = null;
        if (w != null && p.body.isAlive() && !p.body.isRemoved() && !config().url(p.name()).isEmpty()) {
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
        return s.toString();
    }

    // --- the tools ----------------------------------------------------------------------

    private static final JsonArray TOOLS = new JsonArray();

    static {
        tool("come_here", "Walk to the player who is speaking to you, once.");
        tool("follow", "Keep walking after a player until told to stop.",
                "player:string:Their name; leave it out for the one speaking to you:optional");
        tool("go_to", "Walk to a place.", "x:integer:X", "y:integer:Y", "z:integer:Z");
        tool("stop", "Stop what you are doing and stand still.");
        tool("hunt", "Hunt a kind of mob with your best weapon and pick up what it drops.",
                "mob:string:The mob, as Minecraft names it: cow, pig, sheep, chicken, zombie...",
                "count:integer:How many to kill; 0 for every one around:optional");
        tool("clear", "Break every block in a box, top layer first, with your best tools.",
                "x1:integer:A corner's X", "y1:integer:A corner's Y", "z1:integer:A corner's Z",
                "x2:integer:The other corner's X", "y2:integer:The other corner's Y", "z2:integer:The other corner's Z");
        tool("status", "Your health, food, what you are doing and what you carry.");
        tool("look_around", "Who and what is near you: players, mobs, things lying on the ground.");
    }

    /** @param params "name:type:description" or "name:type:description:optional" */
    private static void tool(String name, String description, String... params) {
        JsonObject props = new JsonObject();
        JsonArray required = new JsonArray();
        for (String param : params) {
            String[] f = param.split(":", 4);
            JsonObject prop = new JsonObject();
            prop.addProperty("type", f[1]);
            prop.addProperty("description", f[2]);
            props.add(f[0], prop);
            if (f.length < 4) required.add(f[0]);
        }
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", props);
        schema.add("required", required);
        JsonObject fn = new JsonObject();
        fn.addProperty("name", name);
        fn.addProperty("description", description);
        fn.add("parameters", schema);
        JsonObject t = new JsonObject();
        t.addProperty("type", "function");
        t.add("function", fn);
        TOOLS.add(t);
    }

    /** A tool the model called, run as an order; what came of it, in words for the model. On the server's thread. */
    private String run(Llm.ToolCall call, Said said) {
        JsonObject a = call.args();
        BotPlayer b = p.body;
        ServerPlayer speaker = said.who() == null ? null : server.getPlayerList().getPlayer(said.who());
        try {
            switch (call.name()) {
                case "come_here" -> {
                    if (speaker == null) return "nobody to go to: the one speaking is not in the game";
                    Bots.orderGoto(p, speaker.blockPosition(), by(said));
                    return "started walking to " + said.name() + ", " + Math.round(speaker.distanceTo(b))
                            + " blocks away; not there yet";
                }
                case "follow" -> {
                    String who = a.has("player") ? a.get("player").getAsString() : said.name();
                    ServerPlayer leader = server.getPlayerList().getPlayerByName(who);
                    if (leader == null) return "no player " + who + " in the game";
                    if (leader == b) return "you cannot follow yourself";
                    Bots.orderFollow(p, leader, by(said));
                    return "following " + who;
                }
                case "go_to" -> {
                    BlockPos to = new BlockPos(a.get("x").getAsInt(), a.get("y").getAsInt(), a.get("z").getAsInt());
                    Bots.orderGoto(p, to, by(said));
                    return "started walking to " + pos(to) + ", " + Math.round(Math.sqrt(to.distToCenterSqr(b.position())))
                            + " blocks away; not there yet";
                }
                case "stop" -> {
                    Bots.orderStop(p);
                    return "standing still";
                }
                case "hunt" -> {
                    String mob = a.get("mob").getAsString().toLowerCase(Locale.ROOT).trim().replace(' ', '_');
                    ResourceLocation id = ResourceLocation.tryParse(mob.contains(":") ? mob : "minecraft:" + mob);
                    EntityType<?> type = id == null ? null : BuiltInRegistries.ENTITY_TYPE.getOptional(id).orElse(null);
                    if (type == null) return "there is no mob called " + mob;
                    if (type == EntityType.PLAYER) return "you never hunt players";
                    int count = a.has("count") ? Math.max(0, a.get("count").getAsInt()) : 0;
                    Bots.orderHunt(p, type, id.getPath(), count, by(said));
                    return "started hunting " + id.getPath() + (count > 0 ? ", " + count : ", every one around") + ", "
                            + (Hunt.damage(b.getMainHandItem()) > 0 ? "with " + item(b.getMainHandItem()) : "bare-handed (no weapon)")
                            + "; none killed yet, it takes a while";
                }
                case "clear" -> {
                    BlockPos c1 = new BlockPos(a.get("x1").getAsInt(), a.get("y1").getAsInt(), a.get("z1").getAsInt());
                    BlockPos c2 = new BlockPos(a.get("x2").getAsInt(), a.get("y2").getAsInt(), a.get("z2").getAsInt());
                    String refused = Bots.orderClear(p, c1, c2, by(said));
                    return refused != null ? refused : "started clearing " + pos(c1) + " to " + pos(c2)
                            + "; nothing broken yet, it takes a while";
                }
                case "status" -> {
                    return "health " + Math.round(b.getHealth()) + "/20, food " + b.getFoodData().getFoodLevel()
                            + "/20, doing: " + p.doing + "; carrying: " + inventory(b);
                }
                case "look_around" -> {
                    return around(b);
                }
                default -> {
                    return "there is no tool " + call.name();
                }
            }
        } catch (RuntimeException e) {
            return "that could not be done: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    /** An order from the chat: its brain tells how it went (from the console: nobody to tell). */
    private static Bots.Order by(Said said) {
        return said.who() == null ? null : new Bots.Order(said.who(), said.name(), true);
    }

    private static String around(BotPlayer b) {
        StringBuilder s = new StringBuilder();
        List<String> players = new ArrayList<>();
        for (Player pl : b.level().players()) {
            if (pl == b || pl.distanceTo(b) > 64) continue;
            players.add(pl.getGameProfile().getName() + " " + Math.round(pl.distanceTo(b)) + " blocks "
                    + direction(b.getX(), b.getZ(), pl.getX(), pl.getZ()));
            if (players.size() >= 8) break;
        }
        s.append("players: ").append(players.isEmpty() ? "none within 64" : String.join(", ", players));
        Map<String, int[]> mobs = new LinkedHashMap<>();
        for (LivingEntity e : b.level().getEntitiesOfClass(LivingEntity.class, b.getBoundingBox().inflate(24),
                e -> !(e instanceof Player) && e.isAlive())) {
            String name = BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).getPath();
            int[] c = mobs.computeIfAbsent(name, k -> new int[]{0, Integer.MAX_VALUE});
            c[0]++;
            c[1] = Math.min(c[1], Math.round(e.distanceTo(b)));
        }
        List<String> m = new ArrayList<>();
        mobs.forEach((k, v) -> m.add(k + " x" + v[0] + " (nearest " + v[1] + ")"));
        s.append("; mobs within 24: ").append(m.isEmpty() ? "none" : String.join(", ", m));
        int items = b.level().getEntitiesOfClass(ItemEntity.class, b.getBoundingBox().inflate(16)).size();
        s.append("; things lying on the ground within 16: ").append(items);
        return s.toString();
    }

    private static String inventory(BotPlayer b) {
        Map<String, Integer> count = new LinkedHashMap<>();
        for (ItemStack st : b.getInventory().items) {
            if (!st.isEmpty()) count.merge(st.getHoverName().getString(), st.getCount(), Integer::sum);
        }
        if (count.isEmpty()) return "nothing";
        List<String> out = new ArrayList<>();
        count.forEach((k, v) -> out.add(v + " " + k));
        return String.join(", ", out.size() > 12 ? out.subList(0, 12) : out) + (out.size() > 12 ? ", ..." : "");
    }

    private static String item(ItemStack st) {
        return st.isEmpty() ? "nothing" : st.getHoverName().getString();
    }

    private static String pos(BlockPos p) {
        return p.getX() + " " + p.getY() + " " + p.getZ();
    }

    /** Where (x2, z2) is from (x1, z1), in the eight winds: north is -Z. */
    private static String direction(double x1, double z1, double x2, double z2) {
        double dx = x2 - x1, dz = z2 - z1;
        if (Math.abs(dx) < 1 && Math.abs(dz) < 1) return "here";
        String ns = dz < -Math.abs(dx) / 2.4 ? "north" : dz > Math.abs(dx) / 2.4 ? "south" : "";
        String ew = dx > Math.abs(dz) / 2.4 ? "east" : dx < -Math.abs(dz) / 2.4 ? "west" : "";
        return "to the " + ns + (ns.isEmpty() || ew.isEmpty() ? "" : "-") + ew;
    }

    // --- words ----------------------------------------------------------------------------

    /** Its answer in the chat, as a player's line: {@code <Name> ...}. */
    private void say(String text) {
        if (text.isEmpty() || p.body.isRemoved()) return;
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
