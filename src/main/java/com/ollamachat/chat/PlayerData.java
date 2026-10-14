package com.ollamachat.chat;

import com.ollamachat.config.AiConfig;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Everything saved for one player: their chats, chosen model, facts and settings.
 * Only changed on the server thread, which keeps saving to disk safe.
 * Public fields so Gson can read and write them.
 */
public class PlayerData {

    public static final String DEFAULT_CHAT = "default";
    public static final int MAX_NAME_LENGTH = 24;

    public Map<String, ChatSession> chats = new LinkedHashMap<>();
    public String current = DEFAULT_CHAT;

    /** The Ollama model this player picked. Null until they choose one. */
    public String model;

    /** Facts learned about this player. Shared across all their chats. */
    public List<String> facts = new ArrayList<>();

    /** Let the AI save facts on its own. */
    public boolean learnFacts = true;
    /** Show a grey "Memory: ..." line when something is saved. */
    public boolean showMemoryNotes = true;

    // Not saved.
    public transient String pendingQuestion;
    public transient long lastRequestMillis;

    public enum Result {
        OK,
        EXISTS,
        LIMIT,
        INVALID,
        MISSING
    }

    public PlayerData() {
        chats.put(DEFAULT_CHAT, new ChatSession(DEFAULT_CHAT));
    }

    /** Repairs missing fields after loading an older or damaged file. */
    public void sanitize() {
        if (chats == null) {
            chats = new LinkedHashMap<>();
        }
        chats.values().forEach(ChatSession::sanitize);
        if (chats.isEmpty()) {
            chats.put(DEFAULT_CHAT, new ChatSession(DEFAULT_CHAT));
        }
        if (current == null || !chats.containsKey(current)) {
            current = chats.keySet().iterator().next();
        }
        if (facts == null) {
            facts = new ArrayList<>();
        }
    }

    // =====================================================================
    // Chats
    // =====================================================================

    public static boolean isValidName(String name) {
        return name != null
                && !name.isEmpty()
                && name.length() <= MAX_NAME_LENGTH
                && name.matches("[A-Za-z0-9_.+-]+");
    }

    public ChatSession getCurrent() {
        return chats.get(current);
    }

    public String getCurrentName() {
        return current;
    }

    public ChatSession getChat(String name) {
        return chats.get(name);
    }

    public List<String> chatNames() {
        return new ArrayList<>(chats.keySet());
    }

    public Result create(String name) {
        if (!isValidName(name)) {
            return Result.INVALID;
        }
        if (chats.containsKey(name)) {
            return Result.EXISTS;
        }
        if (chats.size() >= AiConfig.get().maxChatsPerPlayer) {
            return Result.LIMIT;
        }
        chats.put(name, new ChatSession(name));
        current = name;
        return Result.OK;
    }

    public boolean switchTo(String name) {
        if (!chats.containsKey(name)) {
            return false;
        }
        current = name;
        return true;
    }

    public Result rename(String oldName, String newName) {
        ChatSession session = chats.get(oldName);
        if (session == null) {
            return Result.MISSING;
        }
        if (!isValidName(newName)) {
            return Result.INVALID;
        }
        if (chats.containsKey(newName)) {
            return Result.EXISTS;
        }

        // Rebuild the map so the order of the chats stays the same.
        Map<String, ChatSession> rebuilt = new LinkedHashMap<>();
        for (Map.Entry<String, ChatSession> entry : chats.entrySet()) {
            if (entry.getKey().equals(oldName)) {
                session.setName(newName);
                rebuilt.put(newName, session);
            } else {
                rebuilt.put(entry.getKey(), entry.getValue());
            }
        }
        chats = rebuilt;
        if (current.equals(oldName)) {
            current = newName;
        }
        return Result.OK;
    }

    /**
     * Removes a chat. If it was the current chat, switches to another one.
     * If no chats are left, creates an empty default chat.
     */
    public boolean delete(String name) {
        if (chats.remove(name) == null) {
            return false;
        }
        if (chats.isEmpty()) {
            chats.put(DEFAULT_CHAT, new ChatSession(DEFAULT_CHAT));
        }
        if (!chats.containsKey(current)) {
            current = chats.keySet().iterator().next();
        }
        return true;
    }

    // =====================================================================
    // Facts
    // =====================================================================

    public List<String> factsCopy() {
        return new ArrayList<>(facts);
    }

    /** Tidies a fact and rejects ones that are empty, too long, or phrased as a question. */
    public static String cleanFact(String raw) {
        if (raw == null) {
            return null;
        }
        String fact = raw.replaceAll("\\s+", " ").trim();
        fact = fact.replaceAll("^[\"']+|[\"']+$", "").trim();

        if (fact.isEmpty() || fact.length() > AiConfig.get().maxFactLength || fact.endsWith("?")) {
            return null;
        }
        return fact;
    }

    /**
     * Applies fact changes requested by the model. Matching is by text, so it still
     * works if the list changed while the model was thinking.
     * Returns a short description of each change that was actually made.
     */
    public List<String> applyChanges(List<FactChange> changes) {
        int max = AiConfig.get().maxFacts;
        List<String> notes = new ArrayList<>();

        for (FactChange change : changes) {
            switch (change.type()) {
                case ADD -> {
                    if (indexOf(change.text()) < 0 && facts.size() < max) {
                        facts.add(change.text());
                        notes.add("Remembered: " + change.text());
                    }
                }
                case UPDATE -> {
                    int i = indexOf(change.oldText());
                    if (i >= 0) {
                        facts.set(i, change.text());
                        notes.add("Updated: " + change.text());
                    } else if (indexOf(change.text()) < 0 && facts.size() < max) {
                        facts.add(change.text());
                        notes.add("Remembered: " + change.text());
                    }
                }
                case REMOVE -> {
                    int i = indexOf(change.oldText());
                    if (i >= 0) {
                        facts.remove(i);
                        notes.add("Forgot: " + change.oldText());
                    }
                }
            }
        }
        return notes;
    }

    /** Removes fact number n (1-based). Returns the removed text, or null if out of range. */
    public String removeFact(int number) {
        if (number < 1 || number > facts.size()) {
            return null;
        }
        return facts.remove(number - 1);
    }

    public void clearFacts() {
        facts.clear();
    }

    private int indexOf(String text) {
        if (text == null) {
            return -1;
        }
        for (int i = 0; i < facts.size(); i++) {
            if (facts.get(i).equalsIgnoreCase(text)) {
                return i;
            }
        }
        return -1;
    }
}
