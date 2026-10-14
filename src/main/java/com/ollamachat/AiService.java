package com.ollamachat;

import com.ollamachat.chat.ChatManager;
import com.ollamachat.chat.ChatMessage;
import com.ollamachat.chat.ChatSession;
import com.ollamachat.chat.ChatStorage;
import com.ollamachat.chat.PlayerData;
import com.ollamachat.config.AiConfig;
import com.ollamachat.ollama.OllamaClient;
import com.ollamachat.ollama.OllamaException;
import com.ollamachat.ollama.RequestHandle;
import com.ollamachat.util.Msg;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;

import java.net.ConnectException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * The brain of the mod: sends questions to Ollama on background threads and brings the
 * answers back to the server thread. Commands call into this class.
 */
public final class AiService {

    private static ExecutorService worker;

    private static final AtomicInteger PENDING = new AtomicInteger();
    private static final Set<RequestHandle> ACTIVE = ConcurrentHashMap.newKeySet();

    // Models we've already warned about lacking tool support (once per game session).
    private static final Set<String> NO_TOOLS_WARNED = ConcurrentHashMap.newKeySet();

    private static volatile List<String> modelCache = List.of();

    // Statistics for /ai status
    private static final AtomicLong REPLIES = new AtomicLong();
    private static final AtomicLong ERRORS = new AtomicLong();
    private static final AtomicLong PROMPT_TOKENS = new AtomicLong();
    private static final AtomicLong OUTPUT_TOKENS = new AtomicLong();
    private static final AtomicLong TOTAL_MILLIS = new AtomicLong();

    private record Probe(String version, Boolean tools) {
    }

    private AiService() {
    }

    // =====================================================================
    // Lifecycle
    // =====================================================================

    public static synchronized void init() {
        if (worker == null) {
            worker = Executors.newFixedThreadPool(AiConfig.get().workerThreads, r -> {
                Thread t = new Thread(r, "ai-worker");
                t.setDaemon(true);
                return t;
            });
        }
    }

    /** Logs whether Ollama can be reached, so problems show up at startup. */
    public static void onServerStarted(MinecraftServer server) {
        async(OllamaClient::version).whenComplete((version, error) -> {
            if (error != null) {
                OllamaChatMod.LOGGER.warn("Ollama is not reachable at {} ({}). /ai will not work until it is running.",
                        AiConfig.get().ollamaUrl, describe(unwrap(error)));
            } else {
                OllamaChatMod.LOGGER.info("Connected to Ollama {} at {}", version, AiConfig.get().ollamaUrl);
            }
        });
    }

    /** Cancels every running request. Called when the server stops. */
    public static void shutdown() {
        for (RequestHandle handle : ACTIVE) {
            handle.cancel();
        }
    }

    public static List<String> cachedModels() {
        return modelCache;
    }

    // =====================================================================
    // Asking
    // =====================================================================

    public static void ask(CommandSourceStack source, String prompt) {
        dispatch(source, prompt.trim(), false);
    }

    /** Asks the last question again and replaces the previous answer. */
    public static void retry(CommandSourceStack source) {
        ChatSession chat = ChatManager.get(source).getCurrent();

        if (chat.isBusy()) {
            Msg.gray(source, "[" + chat.getName() + "] Still answering, please wait.");
            return;
        }

        String last = chat.lastUserMessage();
        if (last == null) {
            Msg.gray(source, "Nothing to retry in '" + chat.getName() + "'.");
            return;
        }
        dispatch(source, last, true);
    }

    /** Cancels the answer that is being written in the current chat. */
    public static void stop(CommandSourceStack source) {
        ChatSession chat = ChatManager.get(source).getCurrent();

        if (chat.cancelCurrent()) {
            Msg.gray(source, "[" + chat.getName() + "] Stopping...");
        } else {
            Msg.gray(source, "Nothing is running in '" + chat.getName() + "'.");
        }
    }

    private static void dispatch(CommandSourceStack source, String prompt, boolean retry) {
        AiConfig cfg = AiConfig.get();
        MinecraftServer server = source.getServer();
        PlayerData pd = ChatManager.get(source);

        init(); // does nothing if already started

        if (prompt.isBlank()) {
            Msg.error(source, "Say something after /ai. Try /ai help.");
            return;
        }
        if (prompt.length() > cfg.maxQuestionLength) {
            Msg.error(source, "That message is too long (" + prompt.length()
                    + " characters, the limit is " + cfg.maxQuestionLength + ").");
            return;
        }

        // First time: the player must pick a model before any question is answered.
        String model = pd.model;
        if (model == null) {
            firstTimeSetup(source, pd, prompt);
            return;
        }

        ChatSession chat = pd.getCurrent();
        String chatName = chat.getName();
        String tag = "[" + chatName + "] ";

        long now = System.currentTimeMillis();
        long remaining = pd.lastRequestMillis + cfg.cooldownSeconds * 1000L - now;
        if (remaining > 0) {
            Msg.error(source, "Please wait " + ((remaining + 999) / 1000) + "s before asking again.");
            return;
        }

        if (PENDING.get() >= cfg.maxQueuedRequests) {
            Msg.error(source, "The AI is busy with other requests. Try again in a moment.");
            return;
        }

        // One request at a time per chat, so history is never missing a pending answer.
        RequestHandle handle = new RequestHandle();
        if (!chat.tryBegin(handle)) {
            Msg.gray(source, tag + "Still answering your last question in this chat, please wait.");
            return;
        }

        pd.lastRequestMillis = now;
        PENDING.incrementAndGet();
        ACTIVE.add(handle);

        Msg.gray(source, tag + "Thinking...");

        // Read everything the worker needs while still on the server thread.
        List<ChatMessage> history = retry ? chat.snapshotExcludingLast() : chat.snapshot();

        OllamaClient.ChatRequest request = new OllamaClient.ChatRequest(
                model,
                source.getTextName(),
                chatName,
                chat.getPersonaText(),
                pd.factsCopy(),
                chat.getSummary(),
                history,
                prompt,
                pd.learnFacts);

        async(() -> OllamaClient.chat(request, handle))
                .whenComplete((result, error) -> server.execute(
                        () -> finish(source, server, pd, chat, model, prompt, retry, handle, result, error)));
    }

    /** Runs on the server thread when Ollama has answered (or failed). */
    private static void finish(CommandSourceStack source, MinecraftServer server, PlayerData pd,
                               ChatSession chat, String model, String prompt, boolean retry,
                               RequestHandle handle, OllamaClient.ChatResult result, Throwable error) {
        PENDING.decrementAndGet();
        ACTIVE.remove(handle);
        chat.end();

        String chatName = chat.getName();
        String tag = "[" + chatName + "] ";

        if (error != null) {
            Throwable cause = unwrap(error);

            if (cause instanceof RequestHandle.CancelledException) {
                Msg.gray(source, tag + "Cancelled.");
                return;
            }

            ERRORS.incrementAndGet();
            OllamaChatMod.LOGGER.error("Failed to get an answer from Ollama", cause);
            Msg.error(source, tag + "AI Error: " + describe(cause));
            return;
        }

        if (retry) {
            chat.replaceLastExchange(prompt, result.reply());
        } else {
            chat.addExchange(prompt, result.reply());
        }
        List<String> notes = pd.applyChanges(result.changes());

        recordStats(result);
        ChatStorage.saveAsync(server);

        if (AiConfig.get().logConversations) {
            OllamaChatMod.LOGGER.info("[{}] Model: {} | Question: {} | Response: {} | Memory changes: {}",
                    chatName, model, prompt, result.reply(), notes);
        }

        Msg.reply(source, chatName, result.reply());

        if (pd.showMemoryNotes) {
            for (String note : notes) {
                Msg.gray(source, "Memory: " + note);
            }
        }

        if (result.toolsUnsupported() && NO_TOOLS_WARNED.add(model)) {
            Msg.gray(source, "Note: '" + model + "' doesn't support tools, so it can't learn facts on its own. "
                    + "Pick another model with /ai model list if you want it to remember things about you.");
        }

        summarizeIfNeeded(server, chat, model);
    }

    private static void recordStats(OllamaClient.ChatResult result) {
        REPLIES.incrementAndGet();
        PROMPT_TOKENS.addAndGet(result.promptTokens());
        OUTPUT_TOKENS.addAndGet(result.outputTokens());
        TOTAL_MILLIS.addAndGet(result.millis());
    }

    // =====================================================================
    // Summaries
    // =====================================================================

    /**
     * If the chat has grown long, folds its oldest messages into the summary in the
     * background. Failures are only logged, and it is retried after the next answer.
     */
    private static void summarizeIfNeeded(MinecraftServer server, ChatSession chat, String model) {
        List<ChatMessage> older = chat.beginSummary();
        if (older == null) {
            return;
        }
        String oldSummary = chat.getSummary();

        RequestHandle handle = new RequestHandle();
        ACTIVE.add(handle);

        async(() -> OllamaClient.summarize(model, oldSummary, older, handle))
                .whenComplete((newSummary, error) -> server.execute(() -> {
                    ACTIVE.remove(handle);

                    if (error != null || newSummary == null || newSummary.isBlank()) {
                        chat.abortSummary();
                        if (error != null && !(unwrap(error) instanceof RequestHandle.CancelledException)) {
                            OllamaChatMod.LOGGER.warn("Summarising failed: {}", describe(unwrap(error)));
                        }
                        return;
                    }

                    chat.finishSummary(older.size(), newSummary);
                    ChatStorage.saveAsync(server);
                    OllamaChatMod.LOGGER.info("[{}] Summary updated: {}", chat.getName(), newSummary);
                }));
    }

    // =====================================================================
    // Models
    // =====================================================================

    /** Runs the model list request on a worker and calls back on the server thread. */
    public static void fetchModels(CommandSourceStack source, Consumer<List<OllamaClient.ModelInfo>> onSuccess) {
        MinecraftServer server = source.getServer();
        init();

        async(OllamaClient::listModels).whenComplete((models, error) -> server.execute(() -> {
            if (error != null) {
                Throwable cause = unwrap(error);
                OllamaChatMod.LOGGER.warn("Failed to list Ollama models: {}", describe(cause));
                Msg.error(source, describe(cause));
                return;
            }

            modelCache = models.stream().map(OllamaClient.ModelInfo::name).toList();
            onSuccess.accept(models);
        }));
    }

    public static void listModels(CommandSourceStack source) {
        fetchModels(source, installed -> {
            if (installed.isEmpty()) {
                sendNoModelsHelp(source);
                return;
            }
            sendModelPicker(source, installed, ChatManager.get(source).model);
        });
    }

    public static void useModel(CommandSourceStack source, String requested) {
        fetchModels(source, installed -> {
            List<OllamaClient.ModelInfo> matches = match(installed, requested);

            if (matches.isEmpty()) {
                Msg.error(source, "'" + requested.trim() + "' isn't installed in Ollama. Use /ai model list.");
                return;
            }
            if (matches.size() > 1) {
                Msg.error(source, "That matches several models. Be more specific:");
                for (OllamaClient.ModelInfo m : matches) {
                    Msg.clickable(source, "  " + m.name(), "/ai model use " + m.name(), ChatFormatting.YELLOW);
                }
                return;
            }

            OllamaClient.ModelInfo chosen = matches.get(0);
            PlayerData pd = ChatManager.get(source);
            pd.model = chosen.name();
            ChatStorage.saveAsync(source.getServer());

            Msg.ok(source, "Now using model '" + chosen.name() + "'.");
            if (!chosen.supportsTools()) {
                Msg.gray(source, "This model can't learn facts on its own, so it only remembers the current conversation.");
            }

            // If the player asked something before choosing a model, answer it now.
            String pending = pd.pendingQuestion;
            pd.pendingQuestion = null;
            if (pending != null) {
                Msg.gray(source, "Sending your question: " + Msg.truncate(pending, 80));
                dispatch(source, pending, false);
            }
        });
    }

    /** Exact name first, then names that start with what was typed (so "qwen2.5" finds "qwen2.5:1.5b"). */
    private static List<OllamaClient.ModelInfo> match(List<OllamaClient.ModelInfo> installed, String query) {
        String q = query.trim().toLowerCase(Locale.ROOT);

        for (OllamaClient.ModelInfo m : installed) {
            if (m.name().equalsIgnoreCase(q)) {
                return List.of(m);
            }
        }

        List<OllamaClient.ModelInfo> hits = new ArrayList<>();
        for (OllamaClient.ModelInfo m : installed) {
            if (m.name().toLowerCase(Locale.ROOT).startsWith(q)) {
                hits.add(m);
            }
        }
        return hits;
    }

    /** Shows the installed models as clickable choices. */
    private static void sendModelPicker(CommandSourceStack source, List<OllamaClient.ModelInfo> installed,
                                        String current) {
        String recommended = AiConfig.get().defaultModel;
        Msg.heading(source, "Installed models (click one to use it):");

        for (OllamaClient.ModelInfo model : installed) {
            StringBuilder label = new StringBuilder("  ").append(model.name());

            if (model.name().equals(current)) {
                label.append(" (current)");
            }
            if (model.name().equals(recommended)) {
                label.append(" (recommended)");
            }
            label.append(model.supportsTools() ? " (learns facts)" : " (no fact learning)");

            Msg.clickable(source, label.toString(), "/ai model use " + model.name(), ChatFormatting.YELLOW);
        }
    }

    private static void sendNoModelsHelp(CommandSourceStack source) {
        Msg.error(source, "No models are installed in Ollama. On the server, run: ollama pull "
                + AiConfig.get().defaultModel);
    }

    private static void firstTimeSetup(CommandSourceStack source, PlayerData pd, String prompt) {
        AiConfig cfg = AiConfig.get();
        pd.pendingQuestion = prompt;

        fetchModels(source, installed -> {
            if (installed.isEmpty()) {
                sendNoModelsHelp(source);
                return;
            }

            if (cfg.autoUseDefaultModel
                    && installed.stream().anyMatch(m -> m.name().equals(cfg.defaultModel))) {
                pd.model = cfg.defaultModel;
                pd.pendingQuestion = null;
                ChatStorage.saveAsync(source.getServer());
                Msg.gray(source, "Using the default model '" + cfg.defaultModel
                        + "'. Change it any time with /ai model list.");
                dispatch(source, prompt, false);
                return;
            }

            Msg.info(source, "Pick a model before asking questions. You only need to do this once.");
            sendModelPicker(source, installed, null);
            Msg.gray(source, "Your question is saved and will be sent as soon as you pick one.");
        });
    }

    // =====================================================================
    // Status
    // =====================================================================

    public static void status(CommandSourceStack source) {
        MinecraftServer server = source.getServer();
        PlayerData pd = ChatManager.get(source);
        String model = pd.model;
        init();

        async(() -> new Probe(OllamaClient.version(), model == null ? null : OllamaClient.checkTools(model)))
                .whenComplete((probe, error) -> server.execute(() -> sendStatus(source, pd, probe, error)));
    }

    private static void sendStatus(CommandSourceStack source, PlayerData pd, Probe probe, Throwable error) {
        AiConfig cfg = AiConfig.get();
        ChatSession chat = pd.getCurrent();

        Msg.heading(source, "AI status");

        if (error != null) {
            Msg.error(source, "Ollama: offline (" + describe(unwrap(error)) + ")");
        } else {
            Msg.info(source, "Ollama: online, version " + probe.version() + " at " + cfg.ollamaUrl);
        }

        if (pd.model == null) {
            Msg.info(source, "Model: none chosen yet (use /ai model list)");
        } else {
            String tools = probe == null || probe.tools() == null ? ""
                    : probe.tools() ? ", can learn facts" : ", cannot learn facts";
            Msg.info(source, "Model: " + pd.model + tools);
        }

        boolean hasSummary = !chat.getSummary().isBlank();
        Msg.info(source, "Chat: " + chat.getName() + " (" + chat.messageCount() + " messages"
                + (hasSummary ? ", with summary" : "") + ", persona " + chat.getPersonaName() + ")");
        Msg.info(source, "Facts: " + pd.facts.size() + "/" + cfg.maxFacts
                + ", learning " + (pd.learnFacts ? "on" : "off"));

        long replies = REPLIES.get();
        if (replies > 0) {
            long avgSeconds = Math.round(TOTAL_MILLIS.get() / (double) replies / 1000.0);
            Msg.gray(source, "Since the server started: " + replies + " replies, about " + avgSeconds
                    + "s each, " + (PROMPT_TOKENS.get() + OUTPUT_TOKENS.get()) + " tokens, "
                    + ERRORS.get() + " errors.");
        }
        if (PENDING.get() > 0) {
            Msg.gray(source, PENDING.get() + " request(s) running right now.");
        }
    }

    // =====================================================================
    // Helpers
    // =====================================================================

    private static <T> CompletableFuture<T> async(Callable<T> task) {
        init();
        return CompletableFuture.supplyAsync(() -> {
            try {
                return task.call();
            } catch (Exception e) {
                throw new CompletionException(e);
            }
        }, worker);
    }

    private static Throwable unwrap(Throwable error) {
        if (error instanceof CompletionException && error.getCause() != null) {
            return error.getCause();
        }
        return error;
    }

    /** Turns an exception into a message a player can understand. */
    public static String describe(Throwable t) {
        if (t instanceof RequestHandle.CancelledException) {
            return "Cancelled.";
        }
        if (t instanceof HttpConnectTimeoutException || t instanceof ConnectException) {
            return "Could not reach Ollama at " + AiConfig.get().ollamaUrl + ". Is it running?";
        }
        if (t instanceof HttpTimeoutException) {
            return "Ollama took too long to answer.";
        }
        if (t instanceof OllamaException oe && oe.getStatus() == 404) {
            return "That model isn't installed in Ollama. Use /ai model list.";
        }
        String message = t.getMessage();
        return message != null ? message : t.getClass().getSimpleName();
    }
}
