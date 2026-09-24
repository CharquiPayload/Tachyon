package tachyon;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The brain's tools: what the model is sent, and how a call is run. The abilities' tools
 * are gathered as the mod gathers them, without a game: nothing of theirs is run here.
 */
class ToolsTest {

    private static String name(JsonElement tool) {
        return tool.getAsJsonObject().getAsJsonObject("function").get("name").getAsString();
    }

    /**
     * {@code tools-0.1.0.json} is what 0.1.0 sent the model, printed from it before its
     * tools became the abilities'. What the model is told is behaviour: every word of it
     * shapes what it calls. New tools may come among them; these stay as they were.
     */
    @Test
    @DisplayName("the tools of 0.1.0 are sent to the model as they were, to the byte and in their order")
    void theToolsOf010AreSentAsTheyWere() throws IOException {
        String before;
        try (InputStream in = ToolsTest.class.getResourceAsStream("tools-0.1.0.json")) {
            assertNotNull(in, "the resource is there");
            before = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        Set<String> old = new HashSet<>();
        for (JsonElement t : JsonParser.parseString(before).getAsJsonArray()) old.add(name(t));
        assertEquals(8, old.size());

        JsonArray theirs = new JsonArray();
        for (JsonElement t : Abilities.tools().json()) {
            if (old.contains(name(t))) theirs.add(t);
        }
        assertEquals(before, theirs.toString());
    }

    private static Tool.Call call(JsonObject args) {
        return new Tool.Call(null, args, null, "Someone", null);
    }

    @Test
    @DisplayName("a call of a tool nobody has is told so")
    void noSuchTool() {
        assertEquals("there is no tool fly", new Tools().run("fly", call(new JsonObject())));
    }

    @Test
    @DisplayName("a tool that throws is a failure told to the model, in words")
    void aToolThatThrows() {
        Tools tools = new Tools();
        tools.add(new Tool("broken", "Breaks.", List.of(), c -> {
            throw new IllegalStateException("it broke");
        }));
        tools.add(new Tool("silent", "Breaks with no words.", List.of(), c -> {
            throw new NullPointerException();
        }));
        assertEquals("that could not be done: it broke", tools.run("broken", call(new JsonObject())));
        assertEquals("that could not be done: NullPointerException", tools.run("silent", call(new JsonObject())));
    }

    @Test
    @DisplayName("a call reaches its tool with what the model gave, and its answer comes back")
    void aCallIsRun() {
        Tools tools = new Tools();
        tools.add(new Tool("echo", "Says it back.", List.of(Tool.param("what", "string", "What to say")),
                c -> c.speakerName() + " said " + c.args().get("what").getAsString()));
        JsonObject args = new JsonObject();
        args.addProperty("what", "hello");
        assertEquals("Someone said hello", tools.run("echo", call(args)));
    }

    @Test
    @DisplayName("what the model is sent of a tool: its parameters in order, the ones not optional required")
    void json() {
        Tools tools = new Tools();
        tools.add(new Tool("t", "Does.", List.of(Tool.param("a", "integer", "A"), Tool.optional("b", "string", "B"),
                Tool.param("c", "boolean", "C")), c -> ""));
        assertEquals("[{\"type\":\"function\",\"function\":{\"name\":\"t\",\"description\":\"Does.\",\"parameters\":"
                        + "{\"type\":\"object\",\"properties\":{\"a\":{\"type\":\"integer\",\"description\":\"A\"},"
                        + "\"b\":{\"type\":\"string\",\"description\":\"B\"},\"c\":{\"type\":\"boolean\",\"description\":\"C\"}},"
                        + "\"required\":[\"a\",\"c\"]}}}]",
                tools.json().toString());
    }

    @Test
    @DisplayName("a tool may be offered to some bots only; with all offered, the array is the one of them all")
    void offered() {
        Tools tools = new Tools();
        tools.add(new Tool("always", "Always.", List.of(), c -> ""));
        tools.add(new Tool("never", "Never.", List.of(), c -> "").offeredWhen(bot -> false));
        tools.add(new Tool("also", "Also.", List.of(), c -> ""));
        List<Tool> offered = tools.offered(null);
        assertEquals(List.of("always", "also"), offered.stream().map(t -> t.name).toList());
        JsonArray sent = tools.json(offered);
        assertEquals(2, sent.size());
        assertEquals("also", name(sent.get(1)));

        Tools all = new Tools();
        all.add(new Tool("a", "A.", List.of(), c -> ""));
        assertSame(all.json(), all.json(all.offered(null)), "nobody left out: the same array, not a copy");
    }

    @Test
    @DisplayName("a tool's rule is for the instructions, never in what is sent of the tool")
    void ruleIsNotSentWithTheTool() {
        Tool plain = new Tool("t", "Does.", List.of(), c -> "");
        Tool ruled = new Tool("t", "Does.", List.of(), c -> "").rule("Use t only when asked.");
        assertEquals(plain.json(), ruled.json());
        assertEquals("Use t only when asked.", ruled.rule());
    }

    @Test
    @DisplayName("a tool that answers later is waited for; one that fails later is a failure in words")
    void later() throws Exception {
        Tools tools = new Tools();
        tools.add(Tool.later("search", "Looks far.", List.of(),
                c -> CompletableFuture.supplyAsync(() -> "found it on " + Thread.currentThread().getName())));
        tools.add(Tool.later("lost", "Looks and fails.", List.of(),
                c -> CompletableFuture.supplyAsync(() -> {
                    throw new IllegalStateException("nothing there");
                })));
        tools.add(Tool.later("broken", "Fails to start.", List.of(), c -> {
            throw new IllegalArgumentException("no such block");
        }));
        String here = Thread.currentThread().getName();
        String found = tools.start("search", call(new JsonObject())).get(5, TimeUnit.SECONDS);
        assertTrue(found.startsWith("found it on "));
        assertNotEquals("found it on " + here, found, "on a thread of its own");
        assertEquals("that could not be done: nothing there", tools.start("lost", call(new JsonObject())).get(5, TimeUnit.SECONDS));
        assertEquals("that could not be done: no such block", tools.start("broken", call(new JsonObject())).get(5, TimeUnit.SECONDS));
        assertFalse(tools.start("fly", call(new JsonObject())).isCompletedExceptionally());
    }

    @Test
    @DisplayName("two tools of one name are a mistake, said when they are gathered")
    void twoOfOneName() {
        Tools tools = new Tools();
        tools.add(new Tool("t", "One.", List.of(), c -> ""));
        assertThrows(IllegalStateException.class, () -> tools.add(new Tool("t", "Two.", List.of(), c -> "")));
    }
}
