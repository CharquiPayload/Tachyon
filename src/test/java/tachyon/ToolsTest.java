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
import java.util.ArrayList;
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
     * {@code core-tools.json} is what the model is sent of the eight tools of 0.1.0, every
     * bot's core. What the model is told is behaviour: every word of it shapes what it
     * calls. New tools may come among them; these stay as they are, unless changed on
     * purpose, and the file with them.
     *
     * <p>Changed once, on purpose: 0.1.0 sent hunt's {@code mob} as optional, and its
     * description cut at a colon ("The mob, as Minecraft names it"), since its tools were
     * written as "name:type:description" and the colon split the description. It is sent
     * now required, and whole. The rest is what 0.1.0 sent, to the byte.
     */
    @Test
    @DisplayName("the core tools are sent to the model as the file has them, to the byte and in their order")
    void theCoreToolsAreSentAsTheFileHasThem() throws IOException {
        String expected;
        try (InputStream in = ToolsTest.class.getResourceAsStream("core-tools.json")) {
            assertNotNull(in, "the resource is there");
            expected = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        Set<String> core = new HashSet<>();
        for (JsonElement t : JsonParser.parseString(expected).getAsJsonArray()) core.add(name(t));
        assertEquals(8, core.size());

        JsonArray theirs = new JsonArray();
        for (JsonElement t : Abilities.tools().json()) {
            if (core.contains(name(t))) theirs.add(t);
        }
        assertEquals(expected, theirs.toString());
        for (String n : core) assertTrue(Abilities.tools().get(n).isCore(), n + " is core: a lite brain has it");
    }

    @Test
    @DisplayName("a lite brain is offered the core tools only; a full one every tool, the same array as ever")
    void liteBrain() {
        Tools tools = new Tools();
        tools.add(new Tool("walk", "Walk.", List.of(), c -> "").core());
        tools.add(new Tool("farm", "Farm.", List.of(), c -> ""));
        tools.add(new Tool("stop", "Stop.", List.of(), c -> "").core());
        assertEquals(List.of("walk", "stop"), tools.offered(null, true).stream().map(t -> t.name).toList());
        assertEquals(2, tools.json(tools.offered(null, true)).size());
        assertSame(tools.json(), tools.json(tools.offered(null, false)), "the full catalogue, untouched");
        assertEquals(new Tool("t", "T.", List.of(), c -> "").json(), new Tool("t", "T.", List.of(), c -> "").core().json(),
                "being core is never part of what is sent");
    }

    private static Tool.Call call(JsonObject args) {
        return new Tool.Call(null, args, null, "Someone", null);
    }

    /**
     * A tool left out of a turn (a lite brain's non-core tools, one offeredWhen does not
     * give the bot) is sent to nobody, and a call of it is refused as one of no tool: the
     * model may still name it, from its history or by a guess, and must not run it.
     */
    @Test
    @DisplayName("a call of a tool this turn was not sent is refused as one of no tool, and never runs")
    void onlyWhatWasSentRuns() throws Exception {
        Tools tools = new Tools();
        List<String> ran = new ArrayList<>();
        tools.add(new Tool("walk", "Walk.", List.of(), c -> {
            ran.add("walk");
            return "walking";
        }).core());
        tools.add(new Tool("farm", "Farm.", List.of(), c -> {
            ran.add("farm");
            return "farming";
        }));
        List<String> lite = tools.offered(null, true).stream().map(t -> t.name).toList();
        assertEquals("there is no tool farm", tools.start("farm", call(new JsonObject()), lite).get(5, TimeUnit.SECONDS));
        assertEquals("walking", tools.start("walk", call(new JsonObject()), lite).get(5, TimeUnit.SECONDS));
        assertEquals(List.of("walk"), ran, "the tool left out never ran");
        List<String> full = tools.offered(null, false).stream().map(t -> t.name).toList();
        assertEquals("farming", tools.start("farm", call(new JsonObject()), full).get(5, TimeUnit.SECONDS));
        assertEquals("there is no tool fly", tools.start("fly", call(new JsonObject()), List.of("fly")).get(5, TimeUnit.SECONDS),
                "a name offered but no tool is still no tool");
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
        List<Tool> offered = tools.offered(null, false);
        assertEquals(List.of("always", "also"), offered.stream().map(t -> t.name).toList());
        JsonArray sent = tools.json(offered);
        assertEquals(2, sent.size());
        assertEquals("also", name(sent.get(1)));

        Tools all = new Tools();
        all.add(new Tool("a", "A.", List.of(), c -> ""));
        assertSame(all.json(), all.json(all.offered(null, false)), "nobody left out: the same array, not a copy");
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
