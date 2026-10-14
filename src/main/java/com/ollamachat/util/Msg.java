package com.ollamachat.util;

import com.ollamachat.config.AiConfig;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;

import java.util.ArrayList;
import java.util.List;

/** Helpers for sending coloured chat messages. */
public final class Msg {

    private Msg() {
    }

    public static void info(CommandSourceStack source, String text) {
        source.sendSystemMessage(Component.literal(text));
    }

    public static void color(CommandSourceStack source, String text, ChatFormatting format) {
        source.sendSystemMessage(Component.literal(text).withStyle(format));
    }

    public static void gray(CommandSourceStack source, String text) {
        color(source, text, ChatFormatting.GRAY);
    }

    public static void error(CommandSourceStack source, String text) {
        color(source, text, ChatFormatting.RED);
    }

    public static void ok(CommandSourceStack source, String text) {
        color(source, text, ChatFormatting.GREEN);
    }

    public static void heading(CommandSourceStack source, String text) {
        color(source, text, ChatFormatting.GOLD);
    }

    /** A line that runs a command when clicked. */
    public static void clickable(CommandSourceStack source, String text, String command, ChatFormatting format) {
        MutableComponent line = Component.literal(text);
        line.setStyle(Style.EMPTY
                .withColor(format)
                .withClickEvent(new ClickEvent.RunCommand(command)));
        source.sendSystemMessage(line);
    }

    /**
     * Sends an AI answer. The first line gets the chat name and "AI:" in front,
     * and long answers are cut off after the configured number of lines.
     */
    public static void reply(CommandSourceStack source, String chatName, String text) {
        int maxLines = AiConfig.get().maxReplyLines;

        List<String> lines = new ArrayList<>();
        for (String line : clean(text).split("\n")) {
            if (!line.isBlank()) {
                lines.add(line.stripTrailing());
            }
        }
        if (lines.isEmpty()) {
            lines.add("(empty reply)");
        }

        boolean shortened = lines.size() > maxLines;
        if (shortened) {
            lines = lines.subList(0, maxLines);
        }

        MutableComponent first = Component.literal("[" + chatName + "] ").withStyle(ChatFormatting.AQUA)
                .append(Component.literal("AI: ").withStyle(ChatFormatting.GREEN))
                .append(Component.literal(lines.get(0)));
        source.sendSystemMessage(first);

        for (int i = 1; i < lines.size(); i++) {
            source.sendSystemMessage(Component.literal("  " + lines.get(i)));
        }

        if (shortened) {
            gray(source, "  ... (reply shortened)");
        }
    }

    /** Minecraft chat can't show markdown, so remove the symbols instead of showing them. */
    public static String clean(String text) {
        String t = text.replace("\r", "");
        t = t.replaceAll("(?m)^\\s{0,3}#{1,6}\\s*", "");
        t = t.replaceAll("\\*\\*(.+?)\\*\\*", "$1");
        t = t.replaceAll("__(.+?)__", "$1");
        t = t.replace("`", "");
        t = t.replaceAll("(?m)^\\s*\\*\\s+", "- ");
        t = t.replaceAll("\\n{3,}", "\n\n");
        return t.trim();
    }

    public static String truncate(String text, int max) {
        String oneLine = text.replace('\n', ' ');
        return oneLine.length() <= max ? oneLine : oneLine.substring(0, max) + "...";
    }
}
