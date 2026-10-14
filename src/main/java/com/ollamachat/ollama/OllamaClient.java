package com.ollamachat.ollama;

import com.ollamachat.chat.ChatMessage;
import com.ollamachat.chat.FactChange;
import com.ollamachat.chat.PlayerData;
import com.ollamachat.config.AiConfig;
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
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.regex.Pattern;

/**
 * Talks to Ollama. Every method here blocks, so call them off the server thread.
 */
public final class OllamaClient {

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    /** How many times the model may call tools before it has to answer. */
    private static final int MAX_TOOL_ROUNDS = 3;

    /** Longest summary we keep, in characters. */
    private static final int MAX_SUMMARY_LENGTH = 1200;

    private static final Pattern THINK_BLOCK = Pattern.compile("(?s)<think>.*?</think>");

    /** Whether each model supports tools. Missing means "assume yes until proven otherwise". */
    private static final Map<String, Boolean> TOOL_SUPPORT = new ConcurrentHashMap<>();

    private static final JsonArray TOOLS = buildTools();

    private OllamaClient() {
    }

    public record ModelInfo(String name, boolean supportsTools) {
    }

    /** Everything needed to answer one question. */
    public record ChatRequest(String model, String player, String chatName, String persona,
                              List<String> facts, String summary,
                              List<ChatMessage> history, String userMessage,
                              boolean allowMemoryTools) {
    }

    /**
     * The result of a chat request.
     *
     * @param reply            the answer to show the player
     * @param changes          fact changes the model made through tools
     * @param toolsUnsupported true if the model turned out not to support tools
     */
    public record ChatResult(String reply, List<FactChange> changes, boolean toolsUnsupported,
                             int promptTokens, int outputTokens, long millis) {
    }

    private static class ToolsNotSupportedException extends OllamaException {
        ToolsNotSupportedException() {
            super(400, "Model does not support tools");
        }
    }

    private static String url(String path) {
        return AiConfig.get().ollamaUrl + path;
    }

    // =====================================================================
    // Server info and models
    // =====================================================================

    /** Returns Ollama's version, which also proves that it is running. */
    public static String version() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url("/api/version")))
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build();

        HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new OllamaException(response.statusCode(), "Ollama returned HTTP " + response.statusCode());
        }

        JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
        return root.has("version") ? root.get("version").getAsString() : "unknown";
    }

    public static boolean supportsTools(String model) {
        return TOOL_SUPPORT.getOrDefault(model, true);
    }

    /** Looks up whether a model supports tools, using the cached answer if there is one. */
    public static boolean checkTools(String model) {
        Boolean known = TOOL_SUPPORT.get(model);
        return known != null ? known : detectToolSupport(model);
    }

    /**
     * Returns the installed models and whether each supports tools.
     */
    public static List<ModelInfo> listModels() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url("/api/tags")))
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build();

        HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new OllamaException(response.statusCode(), "Ollama returned HTTP " + response.statusCode());
        }

        JsonArray models = JsonParser.parseString(response.body())
                .getAsJsonObject()
                .getAsJsonArray("models");

        List<ModelInfo> result = new ArrayList<>();
        if (models != null) {
            for (JsonElement el : models) {
                String name = el.getAsJsonObject().get("name").getAsString();
                result.add(new ModelInfo(name, checkTools(name)));
            }
        }
        return result;
    }

    /** Asks Ollama what the model can do. Assumes "yes" if Ollama doesn't say. */
    private static boolean detectToolSupport(String model) {
        try {
            JsonObject body = new JsonObject();
            body.addProperty("model", model);

            HttpRequest request = HttpRequest.newBuilder(URI.create(url("/api/show")))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(5))
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                    .build();

            HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
                if (root.has("capabilities") && root.get("capabilities").isJsonArray()) {
                    boolean tools = false;
                    for (JsonElement cap : root.getAsJsonArray("capabilities")) {
                        if ("tools".equals(cap.getAsString())) {
                            tools = true;
                            break;
                        }
                    }
                    TOOL_SUPPORT.put(model, tools);
                    return tools;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception ignored) {
            // Older Ollama versions don't report capabilities. Assume the best.
        }
        return supportsTools(model);
    }

    // =====================================================================
    // Chat
    // =====================================================================

    /**
     * Answers a question. The model can also call memory tools while answering. Those are
     * applied to a private copy of the facts and returned as a list of changes.
     */
    public static ChatResult chat(ChatRequest req, RequestHandle handle) throws Exception {
        AiConfig cfg = AiConfig.get();

        List<String> workingFacts = new ArrayList<>(req.facts());
        List<FactChange> changes = new ArrayList<>();

        boolean useTools = req.allowMemoryTools() && supportsTools(req.model());
        boolean unsupportedNow = false;

        long started = System.currentTimeMillis();
        int promptTokens = 0;
        int outputTokens = 0;

        JsonArray convo = buildConversation(req, workingFacts, useTools);

        String reply = null;
        int rounds = 0;

        while (reply == null) {
            handle.throwIfCancelled();

            boolean offerTools = useTools && rounds < MAX_TOOL_ROUNDS;

            JsonObject root;
            try {
                root = postChat(buildBody(req.model(), convo, offerTools, cfg.temperature), handle);
            } catch (ToolsNotSupportedException e) {
                // Remember this, and rebuild the conversation without the tool instructions.
                TOOL_SUPPORT.put(req.model(), false);
                useTools = false;
                unsupportedNow = true;
                convo = buildConversation(req, workingFacts, false);
                continue;
            }

            promptTokens += intOf(root, "prompt_eval_count");
            outputTokens += intOf(root, "eval_count");

            JsonObject message = root.getAsJsonObject("message");

            JsonArray calls = null;
            if (offerTools && message.has("tool_calls") && message.get("tool_calls").isJsonArray()) {
                calls = message.getAsJsonArray("tool_calls");
            }

            if (calls != null && calls.size() > 0) {
                convo.add(message);

                for (JsonElement el : calls) {
                    JsonObject call = el.getAsJsonObject();
                    JsonObject fn = call.has("function") && call.get("function").isJsonObject()
                            ? call.getAsJsonObject("function")
                            : call;

                    String name = fn.has("name") ? fn.get("name").getAsString() : "";
                    String result = runTool(name, argumentsOf(fn), workingFacts, changes);

                    JsonObject toolMessage = new JsonObject();
                    toolMessage.addProperty("role", "tool");
                    toolMessage.addProperty("tool_name", name);
                    toolMessage.addProperty("content", result);
                    convo.add(toolMessage);
                }

                rounds++;
                continue;
            }

            reply = clean(contentOf(message));
        }

        if (reply.isBlank()) {
            reply = changes.isEmpty() ? "(No answer)" : "Got it.";
        }

        return new ChatResult(reply, changes, unsupportedNow, promptTokens, outputTokens,
                System.currentTimeMillis() - started);
    }

    /** Folds older messages into a short running summary. */
    public static String summarize(String model, String oldSummary, List<ChatMessage> older,
                                   RequestHandle handle) throws Exception {
        StringBuilder transcript = new StringBuilder();
        for (ChatMessage m : older) {
            String who = m.role().equals("user") ? "Player" : "Assistant";
            transcript.append(who).append(": ").append(truncate(m.content(), 500)).append("\n");
        }

        String instructions = """
                You keep a short running summary of a conversation between a player and an AI assistant.
                Merge the existing summary with the new messages into one updated summary.
                Keep names, decisions, ongoing tasks, and important answers. Drop small talk.
                Write plain text, under 120 words, with no introduction.
                """;

        String input = "Existing summary:\n"
                + (oldSummary == null || oldSummary.isBlank() ? "(none)" : oldSummary)
                + "\n\nNew messages:\n" + transcript
                + "\nUpdated summary:";

        JsonArray messages = new JsonArray();
        messages.add(message("system", instructions));
        messages.add(message("user", input));

        JsonObject root = postChat(buildBody(model, messages, false, 0.2), handle);
        return truncate(clean(contentOf(root.getAsJsonObject("message"))), MAX_SUMMARY_LENGTH);
    }

    // =====================================================================
    // Building requests
    // =====================================================================

    private static JsonArray buildConversation(ChatRequest req, List<String> facts, boolean tools) {
        JsonArray messages = new JsonArray();
        messages.add(message("system", systemPrompt(req, facts, tools)));

        for (ChatMessage m : fitHistory(req.history())) {
            messages.add(message(m.role(), m.content()));
        }

        // Repeat the facts next to the question, since small models pay most attention to it.
        messages.add(message("user", withFacts(req.userMessage(), facts)));
        return messages;
    }

    private static JsonObject buildBody(String model, JsonArray messages, boolean withTools, double temperature) {
        AiConfig cfg = AiConfig.get();

        JsonObject options = new JsonObject();
        options.addProperty("temperature", temperature);
        options.addProperty("num_ctx", cfg.numCtx);
        if (cfg.maxReplyTokens > 0) {
            options.addProperty("num_predict", cfg.maxReplyTokens);
        }

        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        body.add("messages", messages);
        body.add("options", options);
        body.addProperty("stream", false);
        body.addProperty("think", false); // skip reasoning output on models that support it
        body.addProperty("keep_alive", cfg.keepAlive);

        if (withTools) {
            body.add("tools", TOOLS);
        }
        return body;
    }

    /** Sends one request to /api/chat and returns the whole reply object. */
    private static JsonObject postChat(JsonObject body, RequestHandle handle) throws Exception {
        for (int attempt = 0; attempt < 2; attempt++) {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url("/api/chat")))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(AiConfig.get().requestTimeoutSeconds))
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                    .build();

            HttpResponse<String> response = send(request, handle);

            if (response.statusCode() == 200) {
                JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
                if (!root.has("message") || !root.get("message").isJsonObject()) {
                    throw new IOException("Ollama sent a reply without a message");
                }
                return root;
            }

            String text = errorText(response.body());
            String lower = text.toLowerCase(Locale.ROOT);

            if (lower.contains("support tools")) {
                throw new ToolsNotSupportedException();
            }

            // Some models reject the "think" option. Retry once without it.
            if (attempt == 0 && body.has("think") && lower.contains("think")) {
                body.remove("think");
                continue;
            }

            throw new OllamaException(response.statusCode(), text);
        }
        throw new IOException("Ollama request failed");
    }

    /** Sends a request in a way that can be cancelled through the handle. */
    private static HttpResponse<String> send(HttpRequest request, RequestHandle handle) throws Exception {
        CompletableFuture<HttpResponse<String>> future =
                CLIENT.sendAsync(request, HttpResponse.BodyHandlers.ofString());
        handle.attach(future);

        try {
            return future.get();
        } catch (CancellationException e) {
            throw new RequestHandle.CancelledException();
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof CancellationException) {
                throw new RequestHandle.CancelledException();
            }
            if (cause instanceof Exception ex) {
                throw ex;
            }
            throw e;
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw e;
        }
    }

    private static String errorText(String body) {
        try {
            JsonObject obj = JsonParser.parseString(body).getAsJsonObject();
            if (obj.has("error")) {
                return obj.get("error").getAsString();
            }
        } catch (RuntimeException ignored) {
            // Not JSON, use the raw text.
        }
        return body;
    }

    // =====================================================================
    // Prompt pieces
    // =====================================================================

    private static String systemPrompt(ChatRequest req, List<String> facts, boolean tools) {
        AiConfig cfg = AiConfig.get();
        StringBuilder sb = new StringBuilder();

        sb.append("You are a helpful AI assistant chatting with the user \"").append(req.player())
                .append("\" (conversation name: \"").append(req.chatName()).append("\").\n");
        sb.append("Keep answers as short as possible, because replies appear in a small chat window. ")
                .append("Use plain text only: no markdown, no tables, no headings. ")
                .append("If you are unsure about something, say so instead of guessing.\n");

        if (req.persona() != null && !req.persona().isBlank()) {
            sb.append("\nYour role and style in this conversation: ").append(req.persona().trim()).append("\n");
        }
        if (!cfg.serverRules.isBlank()) {
            sb.append("\nServer rules you must follow: ").append(cfg.serverRules.trim()).append("\n");
        }

        sb.append("\nWhat you know about this player (they told you these, so treat them as true):\n");
        if (facts.isEmpty()) {
            sb.append("(nothing yet)\n");
        } else {
            for (int i = 0; i < facts.size(); i++) {
                sb.append(i + 1).append(". ").append(facts.get(i)).append("\n");
            }
        }

        if (req.summary() != null && !req.summary().isBlank()) {
            sb.append("\nSummary of the earlier part of this conversation (older messages no longer shown):\n")
                    .append(req.summary()).append("\n");
        }

        sb.append("""

                Memory:
                - The previous messages in this conversation are your memory. Use them for follow-up questions.
                - If asked what you know about the player, answer from the facts above.
                - Do not repeat earlier messages back to the player.
                """);

        if (tools) {
            sb.append("""

                    Memory tools:
                    - Call remember_fact when the player's latest message states something lasting about themselves: their name, preferences, possessions, projects, or goals.
                    - Call update_fact (with the fact's number) when new information replaces an existing fact, for example a changed preference.
                    - Call forget_fact (with the fact's number) when the player asks you to forget something.
                    - Never save questions, requests, jokes, general knowledge, or anything you said yourself. Most messages need no tool.
                    - Never mention the tools. After using one, answer the player normally in a short reply.
                    """);
        }

        return sb.toString();
    }

    /** Puts the saved facts in front of the question, for this request only. */
    private static String withFacts(String userMessage, List<String> facts) {
        if (facts.isEmpty()) {
            return userMessage;
        }
        return "[Saved facts about me: " + String.join("; ", facts) + "]\n" + userMessage;
    }

    /**
     * Keeps the newest messages that fit in the character budget, so a few very long
     * answers can't push the context window over its limit.
     */
    private static List<ChatMessage> fitHistory(List<ChatMessage> history) {
        int budget = AiConfig.get().historyCharBudget;
        int total = 0;
        int start = history.size();

        for (int i = history.size() - 1; i >= 0; i--) {
            int length = history.get(i).content().length();
            if (start < history.size() && total + length > budget) {
                break;
            }
            total += length;
            start = i;
        }

        // The history should always begin with a user message.
        while (start < history.size() && !history.get(start).role().equals("user")) {
            start++;
        }

        return history.subList(start, history.size());
    }

    // =====================================================================
    // Tools
    // =====================================================================

    private static JsonArray buildTools() {
        JsonArray tools = new JsonArray();

        JsonObject remember = new JsonObject();
        remember.add("fact", property("string", "A short fact, for example: Name is Alex"));
        tools.add(tool("remember_fact",
                "Save a new lasting fact about the player.",
                remember, "fact"));

        JsonObject update = new JsonObject();
        update.add("number", property("integer", "The number of the existing fact to replace"));
        update.add("fact", property("string", "The new short fact"));
        tools.add(tool("update_fact",
                "Replace an existing fact with new information.",
                update, "number", "fact"));

        JsonObject forget = new JsonObject();
        forget.add("number", property("integer", "The number of the fact to remove"));
        tools.add(tool("forget_fact",
                "Remove a saved fact the player wants forgotten or says is wrong.",
                forget, "number"));

        return tools;
    }

    private static JsonObject property(String type, String description) {
        JsonObject p = new JsonObject();
        p.addProperty("type", type);
        p.addProperty("description", description);
        return p;
    }

    private static JsonObject tool(String name, String description, JsonObject properties, String... required) {
        JsonArray req = new JsonArray();
        for (String r : required) {
            req.add(r);
        }

        JsonObject parameters = new JsonObject();
        parameters.addProperty("type", "object");
        parameters.add("properties", properties);
        parameters.add("required", req);

        JsonObject function = new JsonObject();
        function.addProperty("name", name);
        function.addProperty("description", description);
        function.add("parameters", parameters);

        JsonObject tool = new JsonObject();
        tool.addProperty("type", "function");
        tool.add("function", function);
        return tool;
    }

    /**
     * Runs one tool call against the working copy of the facts, records the change,
     * and returns the result text that is shown to the model.
     */
    private static String runTool(String name, JsonObject args, List<String> facts, List<FactChange> changes) {
        switch (name) {
            case "remember_fact": {
                String fact = PlayerData.cleanFact(stringArg(args, "fact"));
                if (fact == null) {
                    return "Error: that is not a valid fact. Facts must be short statements, not questions.";
                }
                for (String known : facts) {
                    if (known.equalsIgnoreCase(fact)) {
                        return "Already known.";
                    }
                }
                if (facts.size() >= AiConfig.get().maxFacts) {
                    return "Error: memory is full.";
                }
                facts.add(fact);
                changes.add(FactChange.add(fact));
                return "Saved.";
            }

            case "update_fact": {
                int number = intArg(args, "number");
                String fact = PlayerData.cleanFact(stringArg(args, "fact"));
                if (number < 1 || number > facts.size()) {
                    return "Error: there is no fact with that number.";
                }
                if (fact == null) {
                    return "Error: that is not a valid fact.";
                }
                String old = facts.get(number - 1);
                facts.set(number - 1, fact);
                changes.add(FactChange.update(old, fact));
                return "Updated.";
            }

            case "forget_fact": {
                int number = intArg(args, "number");
                if (number < 1 || number > facts.size()) {
                    return "Error: there is no fact with that number.";
                }
                String old = facts.remove(number - 1);
                changes.add(FactChange.remove(old));
                return "Forgotten.";
            }

            default:
                return "Error: unknown tool.";
        }
    }

    /** Tool arguments normally arrive as an object, but some models send a JSON string. */
    private static JsonObject argumentsOf(JsonObject fn) {
        JsonElement args = fn.get("arguments");

        if (args == null || args.isJsonNull()) {
            return new JsonObject();
        }
        if (args.isJsonObject()) {
            return args.getAsJsonObject();
        }
        if (args.isJsonPrimitive()) {
            try {
                JsonElement parsed = JsonParser.parseString(args.getAsString());
                if (parsed.isJsonObject()) {
                    return parsed.getAsJsonObject();
                }
            } catch (RuntimeException ignored) {
                // Fall through to an empty object.
            }
        }
        return new JsonObject();
    }

    private static String stringArg(JsonObject args, String key) {
        JsonElement e = args.get(key);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : null;
    }

    private static int intArg(JsonObject args, String key) {
        JsonElement e = args.get(key);
        if (e == null || !e.isJsonPrimitive()) {
            return -1;
        }
        try {
            return (int) Math.round(e.getAsDouble());
        } catch (RuntimeException ex) {
            return -1;
        }
    }

    // =====================================================================
    // Small helpers
    // =====================================================================

    private static int intOf(JsonObject obj, String key) {
        JsonElement e = obj.get(key);
        try {
            return e != null && e.isJsonPrimitive() ? e.getAsInt() : 0;
        } catch (RuntimeException ex) {
            return 0;
        }
    }

    private static String contentOf(JsonObject message) {
        if (message.has("content") && !message.get("content").isJsonNull()) {
            return message.get("content").getAsString();
        }
        return "";
    }

    /** Removes any leaked reasoning block and surrounding whitespace. */
    private static String clean(String text) {
        return THINK_BLOCK.matcher(text).replaceAll("").trim();
    }

    private static String truncate(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max) + "...";
    }

    private static JsonObject message(String role, String content) {
        JsonObject obj = new JsonObject();
        obj.addProperty("role", role);
        obj.addProperty("content", content);
        return obj;
    }
}
