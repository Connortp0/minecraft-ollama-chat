package com.ollamachat.chat;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/** Writes a chat transcript to saves/&lt;world&gt;/ai_exports/. */
public final class ChatExporter {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private ChatExporter() {
    }

    /** Returns the path of the file that was written. */
    public static Path export(MinecraftServer server, String playerName, ChatSession chat) throws IOException {
        Path dir = server.getWorldPath(LevelResource.ROOT).resolve("ai_exports");
        Files.createDirectories(dir);

        String fileName = safe(playerName) + "-" + safe(chat.getName()) + "-"
                + LocalDateTime.now().format(STAMP) + ".txt";
        Path file = dir.resolve(fileName);

        StringBuilder sb = new StringBuilder();
        sb.append("Chat: ").append(chat.getName()).append('\n');
        sb.append("Player: ").append(playerName).append('\n');
        sb.append("Persona: ").append(chat.getPersonaName()).append('\n');
        sb.append("Exported: ").append(LocalDateTime.now()).append("\n\n");

        String summary = chat.getSummary();
        if (summary != null && !summary.isBlank()) {
            sb.append("--- Summary of earlier messages ---\n").append(summary).append("\n\n");
        }

        sb.append("--- Conversation ---\n");
        List<ChatMessage> messages = chat.snapshot();
        for (ChatMessage m : messages) {
            sb.append("user".equals(m.role()) ? "You: " : "AI: ")
                    .append(m.content()).append("\n\n");
        }

        Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);
        return file;
    }

    private static String safe(String text) {
        return text.replaceAll("[^A-Za-z0-9._-]", "_");
    }
}
