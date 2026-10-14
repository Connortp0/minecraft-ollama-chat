package com.ollamachat.chat;

import com.ollamachat.OllamaChatMod;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Saves all player data to saves/&lt;world&gt;/ai_chats.json.
 * Reads both the current format and the older plain-map format.
 */
public final class ChatStorage {

    private static final String FILE_NAME = "ai_chats.json";
    private static final int FORMAT_VERSION = 2;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static final Type PLAYERS_TYPE =
            new TypeToken<Map<String, PlayerData>>() {}.getType();

    /** The file layout. The version lets future changes migrate old files. */
    private static class SaveFile {
        int version = FORMAT_VERSION;
        Map<String, PlayerData> players;
    }

    private record PendingWrite(Path path, String json) {
    }

    // One background thread for disk writes, so the server thread never waits on the disk
    // and writes always happen in the order they were requested.
    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "ai-chat-io");
        t.setDaemon(true);
        return t;
    });

    // Only the newest requested save is written. Older ones that were never written are skipped.
    private static final AtomicReference<PendingWrite> LATEST = new AtomicReference<>();

    private ChatStorage() {
    }

    public static void register() {
        ServerLifecycleEvents.SERVER_STARTED.register(ChatStorage::load);
        ServerLifecycleEvents.SERVER_STOPPING.register(ChatStorage::saveNow);

        // Don't let one world's chats leak into the next world in the same session.
        ServerLifecycleEvents.SERVER_STOPPED.register(
                server -> ChatManager.replaceAll(new HashMap<>()));
    }

    public static Path file(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT).resolve(FILE_NAME);
    }

    // =====================================================================
    // Loading
    // =====================================================================

    public static void load(MinecraftServer server) {
        // Start clean, so a world without a save file doesn't inherit another world's data.
        ChatManager.replaceAll(new HashMap<>());

        Path path = file(server);
        if (!Files.exists(path)) {
            return;
        }

        try {
            String json = Files.readString(path, StandardCharsets.UTF_8);
            JsonElement root = JsonParser.parseString(json);

            Map<String, PlayerData> data;
            JsonObject obj = root.isJsonObject() ? root.getAsJsonObject() : null;

            if (obj != null && obj.has("version") && obj.has("players")) {
                data = GSON.fromJson(obj.get("players"), PLAYERS_TYPE);
            } else {
                data = GSON.fromJson(root, PLAYERS_TYPE); // older format: just the map
            }

            if (data != null) {
                ChatManager.replaceAll(data);
            }
            OllamaChatMod.LOGGER.info("Loaded AI chats for {} players from {}",
                    ChatManager.all().size(), path);

            backup(path);
        } catch (Exception e) {
            OllamaChatMod.LOGGER.error("Failed to load AI chats from {}", path, e);
            quarantine(path);
        }
    }

    /** Keeps a copy of the file as it was when the world opened. */
    private static void backup(Path path) {
        try {
            Files.copy(path, path.resolveSibling(FILE_NAME + ".bak"),
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            OllamaChatMod.LOGGER.warn("Could not back up {}", path, e);
        }
    }

    /** Moves an unreadable file aside so it isn't overwritten by the next save. */
    private static void quarantine(Path path) {
        try {
            Path moved = path.resolveSibling(FILE_NAME + ".corrupt-" + System.currentTimeMillis());
            Files.move(path, moved, StandardCopyOption.REPLACE_EXISTING);
            OllamaChatMod.LOGGER.warn("Moved the unreadable chat file to {}", moved);
        } catch (IOException e) {
            OllamaChatMod.LOGGER.error("Could not move the unreadable chat file", e);
        }
    }

    // =====================================================================
    // Saving
    // =====================================================================

    private static String serialize() {
        SaveFile file = new SaveFile();
        file.players = ChatManager.all();
        return GSON.toJson(file);
    }

    /**
     * Turns the data into JSON on the calling (server) thread, so it can't change
     * mid-write, then writes it on the IO thread.
     */
    public static void saveAsync(MinecraftServer server) {
        LATEST.set(new PendingWrite(file(server), serialize()));
        IO.execute(ChatStorage::flush);
    }

    /** Saves and waits for the write to finish. Used when the server is shutting down. */
    public static void saveNow(MinecraftServer server) {
        LATEST.set(new PendingWrite(file(server), serialize()));

        try {
            // Goes through the same thread as async saves, so an older save can't
            // finish after this one and overwrite it.
            IO.submit(ChatStorage::flush).get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            OllamaChatMod.LOGGER.error("Failed to save AI chats on shutdown", e);
        }
    }

    private static void flush() {
        PendingWrite pending = LATEST.getAndSet(null);
        if (pending != null) {
            write(pending.path(), pending.json());
        }
    }

    private static void write(Path path, String json) {
        try {
            // Write to a temp file first, then swap it in, so a crash mid-write
            // doesn't corrupt the save.
            Path tmp = path.resolveSibling(FILE_NAME + ".tmp");
            Files.writeString(tmp, json, StandardCharsets.UTF_8);

            try {
                Files.move(tmp, path,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            OllamaChatMod.LOGGER.error("Failed to save AI chats to {}", path, e);
        }
    }
}
