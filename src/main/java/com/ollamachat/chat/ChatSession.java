package com.ollamachat.chat;

import com.ollamachat.config.AiConfig;
import com.ollamachat.ollama.RequestHandle;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One conversation. Keeps the recent messages word for word, plus a rolling
 * summary of older messages that were folded away, and its own persona.
 */
public class ChatSession {

    private String name;
    private List<ChatMessage> messages = new ArrayList<>();
    private String summary = "";
    private String personaName = "default";
    private String personaText = "";
    private long createdAt = System.currentTimeMillis();

    // Transient so Gson doesn't save them.
    private final transient AtomicBoolean busy = new AtomicBoolean(false);
    private final transient AtomicBoolean summarizing = new AtomicBoolean(false);
    private transient volatile RequestHandle handle;

    private ChatSession() {
        // Needed by Gson
    }

    public ChatSession(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    /** Fixes up fields that are missing in older or damaged save files. */
    public synchronized void sanitize() {
        if (messages == null) {
            messages = new ArrayList<>();
        }
        if (summary == null) {
            summary = "";
        }
        if (personaName == null || personaName.isBlank()) {
            personaName = "default";
        }
        if (personaText == null) {
            personaText = "";
        }
    }

    // ---------- Messages ----------

    public synchronized List<ChatMessage> snapshot() {
        return new ArrayList<>(messages);
    }

    public synchronized int messageCount() {
        return messages.size();
    }

    public synchronized String getSummary() {
        return summary;
    }

    public synchronized void addExchange(String userMessage, String aiMessage) {
        messages.add(new ChatMessage("user", userMessage));
        messages.add(new ChatMessage("assistant", aiMessage));

        int hardMax = AiConfig.get().hardMaxMessages;
        while (messages.size() > hardMax) {
            messages.remove(0);
        }
    }

    /** The last thing the player said, or null if the chat has no complete exchange. */
    public synchronized String lastUserMessage() {
        if (messages.size() < 2) {
            return null;
        }
        ChatMessage m = messages.get(messages.size() - 2);
        return "user".equals(m.role()) ? m.content() : null;
    }

    /** The history without the last question and answer, used when regenerating an answer. */
    public synchronized List<ChatMessage> snapshotExcludingLast() {
        int end = Math.max(0, messages.size() - 2);
        return new ArrayList<>(messages.subList(0, end));
    }

    public synchronized boolean removeLastExchange() {
        if (messages.size() < 2) {
            return false;
        }
        messages.remove(messages.size() - 1);
        messages.remove(messages.size() - 1);
        return true;
    }

    public synchronized void replaceLastExchange(String userMessage, String aiMessage) {
        removeLastExchange();
        addExchange(userMessage, aiMessage);
    }

    /** Wipes this chat's messages and summary. The persona is kept. */
    public synchronized void clear() {
        messages.clear();
        summary = "";
    }

    // ---------- Persona ----------

    public synchronized String getPersonaName() {
        return personaName;
    }

    /** The instructions the model should follow, from custom text or the named preset. */
    public synchronized String getPersonaText() {
        if (personaText != null && !personaText.isBlank()) {
            return personaText;
        }
        return Personas.text(personaName);
    }

    public synchronized void setPersona(String name, String text) {
        this.personaName = name;
        this.personaText = text == null ? "" : text;
    }

    // ---------- Summarising ----------

    /**
     * If the chat is long enough, returns the oldest messages to summarise and marks
     * a summary as in progress. Returns null if nothing needs doing.
     */
    public synchronized List<ChatMessage> beginSummary() {
        AiConfig cfg = AiConfig.get();

        if (messages.size() < cfg.summarizeAtMessages) {
            return null;
        }
        if (!summarizing.compareAndSet(false, true)) {
            return null;
        }

        int count = messages.size() - cfg.keepRecentMessages;
        count -= count % 2; // keep question/answer pairs together
        if (count <= 0) {
            summarizing.set(false);
            return null;
        }
        return new ArrayList<>(messages.subList(0, count));
    }

    /** Stores the new summary and removes the messages it replaced. */
    public synchronized void finishSummary(int count, String newSummary) {
        int n = Math.min(count, messages.size());
        messages.subList(0, n).clear();
        summary = newSummary;
        summarizing.set(false);
    }

    /** Called when summarising failed. The messages stay and it is retried later. */
    public void abortSummary() {
        summarizing.set(false);
    }

    public boolean isSummarizing() {
        return summarizing.get();
    }

    // ---------- Busy flag ----------

    public boolean isBusy() {
        return busy.get();
    }

    /** Returns false if a request is already running in this chat. */
    public boolean tryBegin(RequestHandle requestHandle) {
        if (!busy.compareAndSet(false, true)) {
            return false;
        }
        this.handle = requestHandle;
        return true;
    }

    public void end() {
        handle = null;
        busy.set(false);
    }

    /** Cancels the running request, if any. Returns true if there was one. */
    public boolean cancelCurrent() {
        RequestHandle h = handle;
        if (busy.get() && h != null) {
            h.cancel();
            return true;
        }
        return false;
    }
}
