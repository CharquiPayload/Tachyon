package tachyon;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * One question to a model, with tools, over HTTP.
 *
 * <p>The conversation is kept in the OpenAI chat/completions shape (system, user,
 * assistant with {@code tool_calls}, tool), which is what Ollama, OpenAI, OpenRouter and
 * most others speak; for Anthropic's Messages API it is translated on the way out and
 * the answer on the way back. Called off the server's thread only: it waits for the
 * network.
 */
final class Llm {

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private static final int MAX_TOKENS = 600;

    /** What the model asked to run. */
    record ToolCall(String id, String name, JsonObject args) {
    }

    /**
     * The answer: its words (may be empty), the tools it asked for, and itself as an
     * assistant message to add to the conversation.
     */
    record Reply(String text, List<ToolCall> calls, JsonObject message) {
    }

    private final boolean anthropic;
    private final String url, model, key;
    private final Duration timeout;

    Llm(boolean anthropic, String url, String model, String key, int timeoutSeconds) {
        this.anthropic = anthropic;
        this.url = url;
        this.model = model;
        this.key = key;
        this.timeout = Duration.ofSeconds(timeoutSeconds);
    }

    /** @param messages the conversation, the system message first; @param tools OpenAI function tools */
    Reply ask(List<JsonObject> messages, JsonArray tools) throws IOException, InterruptedException {
        return anthropic ? askAnthropic(messages, tools) : askOpenAi(messages, tools);
    }

    // --- OpenAI chat/completions ------------------------------------------------------

    private Reply askOpenAi(List<JsonObject> messages, JsonArray tools) throws IOException, InterruptedException {
        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        JsonArray msgs = new JsonArray();
        messages.forEach(msgs::add);
        body.add("messages", msgs);
        if (!tools.isEmpty()) {
            body.add("tools", tools);
            body.addProperty("tool_choice", "auto");
        }
        body.addProperty("max_tokens", MAX_TOKENS);
        HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(url + "/chat/completions"))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()));
        if (!key.isEmpty()) req.header("Authorization", "Bearer " + key);
        JsonObject answer = send(req.build());

        JsonObject msg = answer.getAsJsonArray("choices").get(0).getAsJsonObject().getAsJsonObject("message");
        String text = msg.has("content") && !msg.get("content").isJsonNull() ? msg.get("content").getAsString() : "";
        List<ToolCall> calls = new ArrayList<>();
        JsonArray tcs = msg.has("tool_calls") && msg.get("tool_calls").isJsonArray() ? msg.getAsJsonArray("tool_calls") : new JsonArray();
        for (JsonElement e : tcs) {
            JsonObject tc = e.getAsJsonObject();
            JsonObject fn = tc.getAsJsonObject("function");
            calls.add(new ToolCall(tc.has("id") ? tc.get("id").getAsString() : "call_" + calls.size(),
                    fn.get("name").getAsString(), arguments(fn.get("arguments"))));
        }
        // As it came, to go back into the conversation (with an id on every call: some
        // servers leave it out and then refuse the tool results that point at none).
        JsonObject back = new JsonObject();
        back.addProperty("role", "assistant");
        back.addProperty("content", text);
        if (!calls.isEmpty()) back.add("tool_calls", openAiCalls(calls));
        return new Reply(text, calls, back);
    }

    /** Arguments come as a JSON string (OpenAI) or as an object (some servers). */
    private static JsonObject arguments(JsonElement a) {
        if (a == null || a.isJsonNull()) return new JsonObject();
        if (a.isJsonObject()) return a.getAsJsonObject();
        try {
            JsonElement parsed = JsonParser.parseString(a.getAsString());
            return parsed.isJsonObject() ? parsed.getAsJsonObject() : new JsonObject();
        } catch (RuntimeException e) {
            return new JsonObject();
        }
    }

    private static JsonArray openAiCalls(List<ToolCall> calls) {
        JsonArray out = new JsonArray();
        for (ToolCall c : calls) {
            JsonObject fn = new JsonObject();
            fn.addProperty("name", c.name());
            fn.addProperty("arguments", c.args().toString());
            JsonObject tc = new JsonObject();
            tc.addProperty("id", c.id());
            tc.addProperty("type", "function");
            tc.add("function", fn);
            out.add(tc);
        }
        return out;
    }

    // --- Anthropic Messages ---------------------------------------------------------------

    private Reply askAnthropic(List<JsonObject> messages, JsonArray tools) throws IOException, InterruptedException {
        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        body.addProperty("max_tokens", MAX_TOKENS);
        JsonArray msgs = new JsonArray();
        for (JsonObject m : messages) {
            String role = m.get("role").getAsString();
            switch (role) {
                case "system" -> body.addProperty("system", m.get("content").getAsString());
                case "user" -> msgs.add(textMessage("user", m.get("content").getAsString()));
                case "assistant" -> {
                    JsonArray content = new JsonArray();
                    String text = m.has("content") && !m.get("content").isJsonNull() ? m.get("content").getAsString() : "";
                    if (!text.isEmpty()) content.add(textBlock(text));
                    if (m.has("tool_calls")) {
                        for (JsonElement e : m.getAsJsonArray("tool_calls")) {
                            JsonObject tc = e.getAsJsonObject();
                            JsonObject use = new JsonObject();
                            use.addProperty("type", "tool_use");
                            use.addProperty("id", tc.get("id").getAsString());
                            use.addProperty("name", tc.getAsJsonObject("function").get("name").getAsString());
                            use.add("input", arguments(tc.getAsJsonObject("function").get("arguments")));
                            content.add(use);
                        }
                    }
                    if (content.isEmpty()) content.add(textBlock("..."));
                    JsonObject a = new JsonObject();
                    a.addProperty("role", "assistant");
                    a.add("content", content);
                    msgs.add(a);
                }
                case "tool" -> {
                    // Tool results go back in a user message; several in a row share one.
                    JsonObject result = new JsonObject();
                    result.addProperty("type", "tool_result");
                    result.addProperty("tool_use_id", m.get("tool_call_id").getAsString());
                    result.addProperty("content", m.get("content").getAsString());
                    JsonObject last = msgs.isEmpty() ? null : msgs.get(msgs.size() - 1).getAsJsonObject();
                    if (last != null && last.get("role").getAsString().equals("user")
                            && last.getAsJsonArray("content").get(0).getAsJsonObject().get("type").getAsString().equals("tool_result")) {
                        last.getAsJsonArray("content").add(result);
                    } else {
                        JsonArray content = new JsonArray();
                        content.add(result);
                        JsonObject u = new JsonObject();
                        u.addProperty("role", "user");
                        u.add("content", content);
                        msgs.add(u);
                    }
                }
                default -> {
                }
            }
        }
        body.add("messages", msgs);
        if (!tools.isEmpty()) {
            JsonArray ts = new JsonArray();
            for (JsonElement e : tools) {
                JsonObject fn = e.getAsJsonObject().getAsJsonObject("function");
                JsonObject t = new JsonObject();
                t.addProperty("name", fn.get("name").getAsString());
                t.addProperty("description", fn.get("description").getAsString());
                t.add("input_schema", fn.get("parameters"));
                ts.add(t);
            }
            body.add("tools", ts);
        }
        HttpRequest req = HttpRequest.newBuilder(URI.create(url + "/messages"))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .header("x-api-key", key)
                .header("anthropic-version", "2023-06-01")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();
        JsonObject answer = send(req);

        StringBuilder text = new StringBuilder();
        List<ToolCall> calls = new ArrayList<>();
        for (JsonElement e : answer.getAsJsonArray("content")) {
            JsonObject block = e.getAsJsonObject();
            switch (block.get("type").getAsString()) {
                case "text" -> text.append(block.get("text").getAsString());
                case "tool_use" -> calls.add(new ToolCall(block.get("id").getAsString(), block.get("name").getAsString(),
                        block.has("input") && block.get("input").isJsonObject() ? block.getAsJsonObject("input") : new JsonObject()));
                default -> {
                }
            }
        }
        JsonObject back = new JsonObject();
        back.addProperty("role", "assistant");
        back.addProperty("content", text.toString());
        if (!calls.isEmpty()) back.add("tool_calls", openAiCalls(calls));
        return new Reply(text.toString(), calls, back);
    }

    private static JsonObject textBlock(String text) {
        JsonObject b = new JsonObject();
        b.addProperty("type", "text");
        b.addProperty("text", text);
        return b;
    }

    private static JsonObject textMessage(String role, String text) {
        JsonArray content = new JsonArray();
        content.add(textBlock(text));
        JsonObject m = new JsonObject();
        m.addProperty("role", role);
        m.add("content", content);
        return m;
    }

    // --- the wire -------------------------------------------------------------------------

    private static JsonObject send(HttpRequest req) throws IOException, InterruptedException {
        HttpResponse<String> res = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() / 100 != 2) {
            String b = res.body() == null ? "" : res.body();
            throw new IOException("HTTP " + res.statusCode() + ": " + (b.length() > 300 ? b.substring(0, 300) + "..." : b));
        }
        JsonElement parsed = JsonParser.parseString(res.body());
        if (!parsed.isJsonObject()) throw new IOException("not a JSON object: " + res.body());
        return parsed.getAsJsonObject();
    }
}
