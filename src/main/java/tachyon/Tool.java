package tachyon;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;

/**
 * One of the brain's tools: its name, what the model is told it does and what it takes,
 * and what runs when the model calls it.
 *
 * <p>What the handler answers is words for the model, and the model believes them: a
 * tool that starts something that takes time (a walk, a hunt) says it started and is not
 * done yet, and a tool that cannot do what it was asked says why, in words, instead of
 * throwing. What it starts is told back when it is over through the {@link Call#order}.
 *
 * <p>Most answer at once, on the server's thread ({@link Handler}). One whose answer
 * takes long to find (a search over many blocks) answers {@link #later}: it takes what it
 * needs from the world on the server's thread, looks on a thread of its own, and the
 * brain waits for it on its own thread, never the server's.
 */
final class Tool {

    /**
     * What the model is told a tool takes. {@code type} is a JSON schema type: string,
     * integer, number or boolean. An optional one may be left out of a call.
     */
    record Param(String name, String type, String description, boolean optional) {
    }

    /** One that a call must carry. */
    static Param param(String name, String type, String description) {
        return new Param(name, type, description, false);
    }

    /** One that a call may leave out. */
    static Param optional(String name, String type, String description) {
        return new Param(name, type, description, true);
    }

    /**
     * A call of the tool, as its handler gets it, on the server's thread.
     *
     * @param bot         whose brain called it
     * @param args        what the model gave it, as it gave it: a key it was told is
     *                    required may still be missing, or of another type
     * @param speaker     who spoke to the bot, if they are in the game (null: the console,
     *                    or they left)
     * @param speakerName their name, the console's included
     * @param order       for whatever the tool starts: whoever gave it is told, in the
     *                    bot's words, when it is over (null: nobody to tell, as from the
     *                    console)
     */
    record Call(Bots.Bot bot, JsonObject args, ServerPlayer speaker, String speakerName, Bots.Order order) {
    }

    /** A tool's work, answered at once, on the server's thread. */
    interface Handler {
        /** What came of it, in words for the model. */
        String run(Call call);
    }

    /**
     * A tool's work whose answer comes later. It starts on the server's thread, where it
     * reads what it needs of the world (a {@link SnapshotWorld}, say); the rest runs on a
     * thread of its own, and the answer completes the future there.
     */
    interface Later {
        CompletableFuture<String> start(Call call);
    }

    final String name;
    final String description;
    final List<Param> params;
    /** One of the two is set: the work, answered at once or later. */
    final Handler handler;
    final Later later;
    /** A line for the brain's instructions while it has this tool (when to use it, how), or null. */
    private String rule;
    /** Whether a bot is offered it (a setting, a permission); null: every bot, always. */
    private Predicate<Bots.Bot> offered;
    /** One of the few a lite brain is sent too (see {@link #core()}). */
    private boolean core;

    Tool(String name, String description, List<Param> params, Handler handler) {
        this(name, description, params, handler, null);
    }

    private Tool(String name, String description, List<Param> params, Handler handler, Later later) {
        this.name = name;
        this.description = description;
        this.params = List.copyOf(params);
        this.handler = handler;
        this.later = later;
    }

    /** A tool whose answer comes later: see {@link Later}. */
    static Tool later(String name, String description, List<Param> params, Later later) {
        return new Tool(name, description, params, null, later);
    }

    /**
     * A line for the brain's instructions, sent while it has the tool: when it is the one
     * to call, and how. Instructions are behaviour for every bot: a line is short, and
     * only what the description cannot say. Set as the tool is made, before it is added.
     */
    Tool rule(String line) {
        this.rule = line;
        return this;
    }

    String rule() {
        return rule;
    }

    /**
     * Offered to a bot only when {@code when} says so (a setting, a permission): asked as
     * each of its brain's turns starts, on the server's thread. A tool not offered is not
     * sent, and a call of it that turn is refused as a call of no tool at all (see
     * {@link Tools#start(String, Call, java.util.Collection)}): the model neither sees nor
     * runs it. (A lite brain is {@link #core}'s.) Set as it is made.
     */
    Tool offeredWhen(Predicate<Bots.Bot> when) {
        this.offered = when;
        return this;
    }

    boolean offeredTo(Bots.Bot p) {
        return offered == null || offered.test(p);
    }

    /**
     * One of the core tools: those a bot with a lite brain ({@code brain_lite}, for a small
     * local model) is sent, the rest being left out. Every tool is sent to a full brain.
     * What a small model is sent is what it can choose well from: a tool is core when a
     * bot is of little use without it. Set as it is made; never part of what is sent.
     */
    Tool core() {
        this.core = true;
        return this;
    }

    boolean isCore() {
        return core;
    }

    /**
     * As the model is sent it: an OpenAI function tool (Llm turns it into Anthropic's
     * shape when that is the API). The order of the keys is part of what the model has
     * always been sent, and a test holds it to the byte.
     */
    JsonObject json() {
        JsonObject props = new JsonObject();
        JsonArray required = new JsonArray();
        for (Param param : params) {
            JsonObject prop = new JsonObject();
            prop.addProperty("type", param.type());
            prop.addProperty("description", param.description());
            props.add(param.name(), prop);
            if (!param.optional()) required.add(param.name());
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
        return t;
    }
}
