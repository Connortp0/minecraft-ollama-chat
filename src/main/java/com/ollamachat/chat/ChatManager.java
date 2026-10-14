package com.ollamachat.chat;

import net.minecraft.commands.CommandSourceStack;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Holds the data of every player, keyed by UUID. */
public final class ChatManager {

    // Key used when a command comes from the server console or a command block.
    private static final String CONSOLE_KEY = "console";

    // Keyed by player UUID (as a string), or CONSOLE_KEY.
    private static final Map<String, PlayerData> PLAYERS = new ConcurrentHashMap<>();

    private ChatManager() {
    }

    public static String keyOf(CommandSourceStack source) {
        var player = source.getPlayer();
        return player != null ? player.getUUID().toString() : CONSOLE_KEY;
    }

    /** Returns the data for whoever ran the command, creating it on first use. */
    public static PlayerData get(CommandSourceStack source) {
        return PLAYERS.computeIfAbsent(keyOf(source), k -> new PlayerData());
    }

    /** Used by ChatStorage. */
    public static Map<String, PlayerData> all() {
        return PLAYERS;
    }

    /** Used by ChatStorage. Replaces everything and repairs the loaded data. */
    public static void replaceAll(Map<String, PlayerData> loaded) {
        PLAYERS.clear();
        for (Map.Entry<String, PlayerData> entry : loaded.entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null) {
                entry.getValue().sanitize();
                PLAYERS.put(entry.getKey(), entry.getValue());
            }
        }
    }
}
