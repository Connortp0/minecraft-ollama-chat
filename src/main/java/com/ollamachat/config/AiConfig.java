package com.ollamachat.config;

import com.ollamachat.OllamaChatMod;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Settings loaded from config/ai-chat.json. Missing options fall back to the defaults
 * below, and the file is rewritten on load so new options always show up in it.
 */
public class AiConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static volatile AiConfig instance = new AiConfig();

    // ---------- Ollama ----------
    public String ollamaUrl = "http://localhost:11434";
    /** Recommended model. Used automatically for new players if autoUseDefaultModel is true. */
    public String defaultModel = "qwen2.5:1.5b";
    public boolean autoUseDefaultModel = false;
    public double temperature = 0.4;
    public int numCtx = 6144;
    /** Cap on the length of one reply, in tokens. 0 means no cap. */
    public int maxReplyTokens = 400;
    public int requestTimeoutSeconds = 120;
    /** How long Ollama keeps the model loaded after a request, e.g. "10m". */
    public String keepAlive = "10m";

    // ---------- Memory ----------
    /** Most characters of recent chat history sent with each question. */
    public int historyCharBudget = 6000;
    /** When a chat holds this many messages, the oldest ones are summarised. */
    public int summarizeAtMessages = 24;
    /** How many of the newest messages stay word for word after summarising. */
    public int keepRecentMessages = 12;
    /** Safety cap in case summarising keeps failing. */
    public int hardMaxMessages = 60;
    public int maxFacts = 40;
    public int maxFactLength = 120;

    // ---------- Limits ----------
    public int maxChatsPerPlayer = 20;
    public int maxQuestionLength = 500;
    public int cooldownSeconds = 2;
    public int workerThreads = 2;
    public int maxQueuedRequests = 6;
    public int maxReplyLines = 12;

    // ---------- Behaviour ----------
    public boolean logConversations = true;
    /** Extra rules added to every system prompt, e.g. "Never discuss politics." */
    public String serverRules = "";
    /** Your own personas: name -> instructions. They appear next to the built-in ones. */
    public Map<String, String> customPersonas = new LinkedHashMap<>();

    public static AiConfig get() {
        return instance;
    }

    public static Path path() {
        return FabricLoader.getInstance().getConfigDir().resolve("ai-chat.json");
    }

    /**
     * Loads the config file, creating it if it doesn't exist.
     * Returns false if the file couldn't be read, in which case the old settings stay active.
     */
    public static boolean load() {
        Path path = path();
        AiConfig loaded = null;

        if (Files.exists(path)) {
            try {
                loaded = GSON.fromJson(Files.readString(path, StandardCharsets.UTF_8), AiConfig.class);
            } catch (Exception e) {
                OllamaChatMod.LOGGER.error("Could not read {}. Keeping the previous settings.", path, e);
                return false;
            }
        }

        if (loaded == null) {
            loaded = new AiConfig();
        }
        loaded.validate();
        instance = loaded;

        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, GSON.toJson(loaded), StandardCharsets.UTF_8);
        } catch (IOException e) {
            OllamaChatMod.LOGGER.warn("Could not write {}", path, e);
        }
        return true;
    }

    /** Clamps values so a typo in the file can't break the mod. */
    private void validate() {
        if (ollamaUrl == null || ollamaUrl.isBlank()) {
            ollamaUrl = "http://localhost:11434";
        }
        ollamaUrl = ollamaUrl.trim();
        while (ollamaUrl.endsWith("/")) {
            ollamaUrl = ollamaUrl.substring(0, ollamaUrl.length() - 1);
        }

        if (defaultModel == null || defaultModel.isBlank()) {
            defaultModel = "qwen2.5:1.5b";
        }
        if (keepAlive == null || keepAlive.isBlank()) {
            keepAlive = "10m";
        }
        if (serverRules == null) {
            serverRules = "";
        }
        if (customPersonas == null) {
            customPersonas = new LinkedHashMap<>();
        }

        temperature = clamp(temperature, 0.0, 2.0);
        numCtx = clamp(numCtx, 1024, 131072);
        maxReplyTokens = clamp(maxReplyTokens, 0, 8192);
        requestTimeoutSeconds = clamp(requestTimeoutSeconds, 5, 3600);

        historyCharBudget = clamp(historyCharBudget, 500, 100000);
        summarizeAtMessages = clamp(summarizeAtMessages, 6, 200);
        keepRecentMessages = clamp(keepRecentMessages, 2, 100);
        if (keepRecentMessages >= summarizeAtMessages) {
            keepRecentMessages = summarizeAtMessages / 2;
        }
        hardMaxMessages = Math.max(clamp(hardMaxMessages, 10, 500), summarizeAtMessages + 2);
        maxFacts = clamp(maxFacts, 1, 500);
        maxFactLength = clamp(maxFactLength, 20, 500);

        maxChatsPerPlayer = clamp(maxChatsPerPlayer, 1, 200);
        maxQuestionLength = clamp(maxQuestionLength, 20, 5000);
        cooldownSeconds = clamp(cooldownSeconds, 0, 600);
        workerThreads = clamp(workerThreads, 1, 16);
        maxQueuedRequests = clamp(maxQueuedRequests, 1, 100);
        maxReplyLines = clamp(maxReplyLines, 1, 100);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
