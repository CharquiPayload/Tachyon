package tachyon;

import com.google.gson.JsonArray;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

/**
 * The brain's tools, in the order they were added: what the model is sent, and the one a
 * call names, run. Filled once, by the abilities (see {@link Abilities}), and only read
 * after that, from the brain's threads (what is sent) and the server's (what is run).
 */
final class Tools {

    private final Map<String, Tool> byName = new LinkedHashMap<>();
    private final JsonArray json = new JsonArray();

    /** A tool more. Two with one name is a mistake of ours, said as they are gathered. */
    void add(Tool tool) {
        if (byName.putIfAbsent(tool.name, tool) != null) {
            throw new IllegalStateException("two tools called " + tool.name);
        }
        json.add(tool.json());
    }

    Tool get(String name) {
        return byName.get(name);
    }

    /**
     * What the model is sent, every tool in order. The same array every time, as the
     * requests are built from it: nobody changes it.
     */
    JsonArray json() {
        return json;
    }

    /** The tools offered to a bot, in order (see {@link Tool#offeredWhen}). On the server's thread. */
    List<Tool> offered(Bots.Bot p) {
        List<Tool> out = new ArrayList<>(byName.size());
        for (Tool t : byName.values()) {
            if (t.offeredTo(p)) out.add(t);
        }
        return out;
    }

    /** What the model is sent of {@code tools}: the array of them all when they are all there. */
    JsonArray json(List<Tool> tools) {
        if (tools.size() == byName.size()) return json;
        JsonArray some = new JsonArray();
        for (Tool t : tools) some.add(t.json());
        return some;
    }

    /**
     * The tool a call names, started; what comes of it, in words for the model. A tool
     * that throws is a failure told to the model, never to the server. On the server's
     * thread; a {@link Tool#later} tool's answer completes elsewhere, and is waited for
     * off the server's thread.
     */
    CompletableFuture<String> start(String name, Tool.Call call) {
        Tool tool = byName.get(name);
        if (tool == null) return CompletableFuture.completedFuture("there is no tool " + name);
        try {
            if (tool.handler != null) return CompletableFuture.completedFuture(tool.handler.run(call));
            return tool.later.start(call).exceptionally(Tools::failed);
        } catch (RuntimeException e) {
            return CompletableFuture.completedFuture(failed(e));
        }
    }

    /** {@link #start}, for a tool that answers at once: what it said. */
    String run(String name, Tool.Call call) {
        return start(name, call).join();
    }

    /** A failure, as the model is told it: the exception's message, which is rarely good words. */
    static String failed(Throwable e) {
        while ((e instanceof CompletionException || e instanceof ExecutionException) && e.getCause() != null) {
            e = e.getCause();
        }
        return "that could not be done: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
    }
}
