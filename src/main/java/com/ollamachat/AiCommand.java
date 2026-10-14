package com.ollamachat;

import com.ollamachat.chat.ChatExporter;
import com.ollamachat.chat.ChatManager;
import com.ollamachat.chat.ChatMessage;
import com.ollamachat.chat.ChatSession;
import com.ollamachat.chat.ChatStorage;
import com.ollamachat.chat.Personas;
import com.ollamachat.chat.PlayerData;
import com.ollamachat.config.AiConfig;
import com.ollamachat.util.Msg;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;

import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Function;

/** Builds the /ai command tree. The real work happens in AiService and the chat classes. */
public final class AiCommand {

    private static final int MAX_PERSONA_LENGTH = 400;

    private static final SuggestionProvider<CommandSourceStack> CHAT_NAMES =
            suggestions(source -> ChatManager.get(source).chatNames());

    private static final SuggestionProvider<CommandSourceStack> PERSONA_NAMES =
            suggestions(source -> Personas.names());

    private static final SuggestionProvider<CommandSourceStack> MODEL_NAMES =
            suggestions(source -> AiService.cachedModels());

    private AiCommand() {
    }

    public static void register() {
        CommandRegistrationCallback.EVENT.register(
                (dispatcher, buildContext, selection) -> dispatcher.register(build()));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> build() {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("ai");

        // /ai
        root.executes(ctx -> help(ctx.getSource()));

        // /ai help | status | stop | retry | undo | reload
        root.then(Commands.literal("help").executes(ctx -> help(ctx.getSource())));
        root.then(Commands.literal("status").executes(ctx -> run(AiService::status, ctx)));
        root.then(Commands.literal("stop").executes(ctx -> run(AiService::stop, ctx)));
        root.then(Commands.literal("retry").executes(ctx -> run(AiService::retry, ctx)));
        root.then(Commands.literal("undo").executes(ctx -> undo(ctx.getSource())));
        root.then(Commands.literal("reload").executes(ctx -> reload(ctx.getSource())));

        // /ai ask <message>   (for messages that start with a command word)
        root.then(Commands.literal("ask")
                .then(Commands.argument("question", StringArgumentType.greedyString())
                        .executes(ctx -> ask(ctx))));

        // /ai model [list | use <name>]
        root.then(Commands.literal("model")
                .executes(ctx -> showModel(ctx.getSource()))
                .then(Commands.literal("list").executes(ctx -> run(AiService::listModels, ctx)))
                // greedyString because model names contain ':' (e.g. qwen3:0.6b)
                .then(Commands.literal("use")
                        .then(Commands.argument("name", StringArgumentType.greedyString())
                                .suggests(MODEL_NAMES)
                                .executes(ctx -> {
                                    AiService.useModel(ctx.getSource(), StringArgumentType.getString(ctx, "name"));
                                    return Command.SINGLE_SUCCESS;
                                }))));

        // /ai forget all   (wipes everything the AI has learned about you)
        root.then(Commands.literal("forget")
                .executes(ctx -> forgetUsage(ctx.getSource()))
                .then(Commands.literal("all").executes(ctx -> forgetAll(ctx.getSource()))));

        // /ai persona [name | set <text> | reset]
        root.then(Commands.literal("persona")
                .executes(ctx -> showPersona(ctx.getSource()))
                .then(Commands.literal("set")
                        .then(Commands.argument("text", StringArgumentType.greedyString())
                                .executes(ctx -> setPersona(ctx.getSource(), StringArgumentType.getString(ctx, "text")))))
                .then(Commands.literal("reset").executes(ctx -> usePersona(ctx.getSource(), "default")))
                .then(Commands.argument("name", StringArgumentType.word())
                        .suggests(PERSONA_NAMES)
                        .executes(ctx -> usePersona(ctx.getSource(), StringArgumentType.getString(ctx, "name")))));

        // /ai settings [learn|notes|context] [on|off]
        root.then(Commands.literal("settings")
                .executes(ctx -> showSettings(ctx.getSource()))
                .then(toggle("learn", AiCommand::setLearn))
                .then(toggle("notes", AiCommand::setNotes)));

        // /ai chat ...
        root.then(Commands.literal("chat")
                .executes(ctx -> listChats(ctx.getSource()))

                .then(Commands.literal("new")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .executes(ctx -> newChat(ctx.getSource(), StringArgumentType.getString(ctx, "name")))))

                .then(Commands.literal("list").executes(ctx -> listChats(ctx.getSource())))

                .then(Commands.literal("switch")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .suggests(CHAT_NAMES)
                                .executes(ctx -> switchChat(ctx.getSource(), StringArgumentType.getString(ctx, "name")))))

                .then(Commands.literal("delete")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .suggests(CHAT_NAMES)
                                .executes(ctx -> deleteChat(ctx.getSource(), StringArgumentType.getString(ctx, "name")))))

                .then(Commands.literal("rename")
                        .then(Commands.argument("new_name", StringArgumentType.word())
                                .executes(ctx -> renameChat(ctx.getSource(), StringArgumentType.getString(ctx, "new_name")))))

                .then(Commands.literal("clear").executes(ctx -> clearChat(ctx.getSource())))

                .then(Commands.literal("summary").executes(ctx -> showSummary(ctx.getSource())))

                .then(Commands.literal("history")
                        .executes(ctx -> showHistory(ctx.getSource(), 6))
                        .then(Commands.argument("count", IntegerArgumentType.integer(1, 50))
                                .executes(ctx -> showHistory(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "count")))))

                .then(Commands.literal("export").executes(ctx -> exportChat(ctx.getSource()))));

        // /ai <question>
        root.then(Commands.argument("message", StringArgumentType.greedyString())
                .executes(ctx -> ask(ctx)));

        return root;
    }

    // =====================================================================
    // Small helpers for building the tree
    // =====================================================================

    private static int run(Consumer<CommandSourceStack> action, CommandContext<CommandSourceStack> ctx) {
        action.accept(ctx.getSource());
        return Command.SINGLE_SUCCESS;
    }

    private static int ask(CommandContext<CommandSourceStack> ctx) {
        AiService.ask(ctx.getSource(), StringArgumentType.getString(ctx, "question"));
        return Command.SINGLE_SUCCESS;
    }

    private static SuggestionProvider<CommandSourceStack> suggestions(
            Function<CommandSourceStack, Collection<String>> values) {
        return (ctx, builder) -> {
            String typed = builder.getRemainingLowerCase();
            for (String value : values.apply(ctx.getSource())) {
                if (value.toLowerCase(Locale.ROOT).startsWith(typed)) {
                    builder.suggest(value);
                }
            }
            return builder.buildFuture();
        };
    }

    @FunctionalInterface
    private interface Toggle {
        /** value is null when the player only wants to see the current setting. */
        int apply(CommandSourceStack source, Boolean value);
    }

    private static LiteralArgumentBuilder<CommandSourceStack> toggle(String name, Toggle handler) {
        return Commands.literal(name)
                .executes(ctx -> handler.apply(ctx.getSource(), null))
                .then(Commands.literal("on").executes(ctx -> handler.apply(ctx.getSource(), true)))
                .then(Commands.literal("off").executes(ctx -> handler.apply(ctx.getSource(), false)));
    }

    private static String onOff(boolean value) {
        return value ? "on" : "off";
    }

    // =====================================================================
    // Help and general
    // =====================================================================

    private static int help(CommandSourceStack source) {
        Msg.heading(source, "AI commands");
        Msg.info(source, "  /ai <message>   talk to the AI (or /ai ask <message>)");
        Msg.info(source, "  /ai retry | undo | stop   redo, remove or cancel the last answer");
        Msg.info(source, "  /ai chat new|list|switch|delete|rename|clear   manage conversations");
        Msg.info(source, "  /ai chat history|summary|export   look back at a conversation");
        Msg.info(source, "  /ai persona [name | set <text> | reset]   change how this chat talks");
        Msg.info(source, "  /ai model [list | use <name>]   choose the Ollama model");
        Msg.info(source, "  /ai forget all   wipe what the AI has learned about you");
        Msg.info(source, "  /ai settings [learn|notes] [on|off]   your preferences");
        Msg.info(source, "  /ai status | reload");
        Msg.gray(source, "Just chat. Tell the AI about yourself and it will remember it.");
        return Command.SINGLE_SUCCESS;
    }

    private static int reload(CommandSourceStack source) {
        if (AiConfig.load()) {
            Msg.ok(source, "Reloaded config/ai-chat.json. (Worker thread count only changes after a restart.)");
        } else {
            Msg.error(source, "Could not read config/ai-chat.json. Keeping the old settings. See the log.");
        }
        return Command.SINGLE_SUCCESS;
    }

    private static int undo(CommandSourceStack source) {
        ChatSession chat = ChatManager.get(source).getCurrent();

        if (chat.isBusy()) {
            Msg.gray(source, "'" + chat.getName() + "' is busy right now. Try again shortly.");
        } else if (chat.removeLastExchange()) {
            Msg.ok(source, "Removed the last question and answer from '" + chat.getName() + "'.");
            ChatStorage.saveAsync(source.getServer());
        } else {
            Msg.gray(source, "Nothing to undo in '" + chat.getName() + "'.");
        }
        return Command.SINGLE_SUCCESS;
    }

    // =====================================================================
    // Model
    // =====================================================================

    private static int showModel(CommandSourceStack source) {
        String model = ChatManager.get(source).model;

        if (model == null) {
            Msg.info(source, "No model chosen yet. Use /ai model list to see installed models.");
        } else {
            Msg.info(source, "Current model: " + model);
        }
        return Command.SINGLE_SUCCESS;
    }

    // =====================================================================
    // Facts
    // =====================================================================

    private static int forgetUsage(CommandSourceStack source) {
        Msg.info(source, "Use /ai forget all to wipe everything I have learned about you. "
                + "To forget one thing, just tell me, for example: forget my name.");
        return Command.SINGLE_SUCCESS;
    }

    private static int forgetAll(CommandSourceStack source) {
        ChatManager.get(source).clearFacts();
        Msg.ok(source, "Forgot everything I knew about you.");
        ChatStorage.saveAsync(source.getServer());
        return Command.SINGLE_SUCCESS;
    }

    // =====================================================================
    // Persona
    // =====================================================================

    private static int showPersona(CommandSourceStack source) {
        ChatSession chat = ChatManager.get(source).getCurrent();

        Msg.info(source, "Persona in '" + chat.getName() + "': " + chat.getPersonaName());
        String text = chat.getPersonaText();
        if (!text.isBlank()) {
            Msg.gray(source, "  " + Msg.truncate(text, 150));
        }

        Msg.heading(source, "Click one to switch:");
        for (String name : Personas.names()) {
            Msg.clickable(source, "  " + name, "/ai persona " + name, ChatFormatting.YELLOW);
        }
        Msg.gray(source, "Or write your own: /ai persona set <instructions>");
        return Command.SINGLE_SUCCESS;
    }

    private static int usePersona(CommandSourceStack source, String name) {
        String key = Personas.normalize(name);

        if (!Personas.exists(key)) {
            Msg.error(source, "No persona called '" + name + "'. Use /ai persona to see them.");
            return Command.SINGLE_SUCCESS;
        }

        ChatSession chat = ChatManager.get(source).getCurrent();
        chat.setPersona(key, "");
        Msg.ok(source, "'" + chat.getName() + "' now uses the " + key + " persona.");
        ChatStorage.saveAsync(source.getServer());
        return Command.SINGLE_SUCCESS;
    }

    private static int setPersona(CommandSourceStack source, String text) {
        String trimmed = text.trim();

        if (trimmed.isEmpty() || trimmed.length() > MAX_PERSONA_LENGTH) {
            Msg.error(source, "Write between 1 and " + MAX_PERSONA_LENGTH + " characters of instructions.");
            return Command.SINGLE_SUCCESS;
        }

        ChatSession chat = ChatManager.get(source).getCurrent();
        chat.setPersona("custom", trimmed);
        Msg.ok(source, "'" + chat.getName() + "' now follows your custom instructions.");
        ChatStorage.saveAsync(source.getServer());
        return Command.SINGLE_SUCCESS;
    }

    // =====================================================================
    // Settings
    // =====================================================================

    private static int showSettings(CommandSourceStack source) {
        PlayerData pd = ChatManager.get(source);

        Msg.heading(source, "Your AI settings");
        Msg.info(source, "  learn: " + onOff(pd.learnFacts) + "   (the AI saves facts about you on its own)");
        Msg.info(source, "  notes: " + onOff(pd.showMemoryNotes) + "   (show a line when something is saved)");
        Msg.gray(source, "Change one with, for example: /ai settings learn off");
        return Command.SINGLE_SUCCESS;
    }

    private static int setLearn(CommandSourceStack source, Boolean value) {
        PlayerData pd = ChatManager.get(source);
        if (value == null) {
            Msg.info(source, "learn is " + onOff(pd.learnFacts));
        } else {
            pd.learnFacts = value;
            Msg.ok(source, "learn is now " + onOff(value));
            ChatStorage.saveAsync(source.getServer());
        }
        return Command.SINGLE_SUCCESS;
    }

    private static int setNotes(CommandSourceStack source, Boolean value) {
        PlayerData pd = ChatManager.get(source);
        if (value == null) {
            Msg.info(source, "notes is " + onOff(pd.showMemoryNotes));
        } else {
            pd.showMemoryNotes = value;
            Msg.ok(source, "notes is now " + onOff(value));
            ChatStorage.saveAsync(source.getServer());
        }
        return Command.SINGLE_SUCCESS;
    }

    // =====================================================================
    // Chats
    // =====================================================================

    private static int newChat(CommandSourceStack source, String name) {
        switch (ChatManager.get(source).create(name)) {
            case OK -> {
                Msg.ok(source, "Created and switched to chat '" + name + "'.");
                ChatStorage.saveAsync(source.getServer());
            }
            case EXISTS -> Msg.error(source, "A chat named '" + name + "' already exists.");
            case LIMIT -> Msg.error(source, "You can have at most " + AiConfig.get().maxChatsPerPlayer
                    + " chats. Delete one with /ai chat delete <name>.");
            default -> Msg.error(source, "Chat names can use letters, numbers and _ . + - (up to "
                    + PlayerData.MAX_NAME_LENGTH + " characters).");
        }
        return Command.SINGLE_SUCCESS;
    }

    private static int listChats(CommandSourceStack source) {
        PlayerData pd = ChatManager.get(source);

        Msg.heading(source, "Your chats (click one to switch):");
        for (String name : pd.chatNames()) {
            ChatSession chat = pd.getChat(name);

            String line = "  " + name
                    + (name.equals(pd.current) ? " (current)" : "")
                    + " - " + chat.messageCount() + " messages"
                    + (chat.getPersonaName().equals("default") ? "" : ", " + chat.getPersonaName());

            Msg.clickable(source, line, "/ai chat switch " + name,
                    name.equals(pd.current) ? ChatFormatting.GREEN : ChatFormatting.YELLOW);
        }
        return Command.SINGLE_SUCCESS;
    }

    private static int switchChat(CommandSourceStack source, String name) {
        if (ChatManager.get(source).switchTo(name)) {
            Msg.ok(source, "Switched to chat '" + name + "'.");
            ChatStorage.saveAsync(source.getServer());
        } else {
            Msg.error(source, "No chat named '" + name + "'. Use /ai chat list to see chats.");
        }
        return Command.SINGLE_SUCCESS;
    }

    private static int deleteChat(CommandSourceStack source, String name) {
        PlayerData pd = ChatManager.get(source);
        ChatSession chat = pd.getChat(name);

        if (chat == null) {
            Msg.error(source, "No chat named '" + name + "'. Use /ai chat list to see chats.");
            return Command.SINGLE_SUCCESS;
        }

        // Can't delete while an answer is pending, or the reply would go to a deleted chat.
        if (chat.isBusy() || chat.isSummarizing()) {
            Msg.gray(source, "Can't delete '" + name + "' while it's busy. Try again shortly.");
            return Command.SINGLE_SUCCESS;
        }

        pd.delete(name);
        Msg.ok(source, "Deleted chat '" + name + "'. Current chat is now '" + pd.current + "'.");
        ChatStorage.saveAsync(source.getServer());
        return Command.SINGLE_SUCCESS;
    }

    /** Renames the current chat. */
    private static int renameChat(CommandSourceStack source, String newName) {
        PlayerData pd = ChatManager.get(source);
        ChatSession chat = pd.getCurrent();
        String oldName = chat.getName();

        if (chat.isBusy() || chat.isSummarizing()) {
            Msg.gray(source, "'" + oldName + "' is busy right now. Try again shortly.");
            return Command.SINGLE_SUCCESS;
        }

        switch (pd.rename(oldName, newName)) {
            case OK -> {
                Msg.ok(source, "Renamed '" + oldName + "' to '" + newName + "'.");
                ChatStorage.saveAsync(source.getServer());
            }
            case EXISTS -> Msg.error(source, "A chat named '" + newName + "' already exists.");
            default -> Msg.error(source, "Chat names can use letters, numbers and _ . + - (up to "
                    + PlayerData.MAX_NAME_LENGTH + " characters).");
        }
        return Command.SINGLE_SUCCESS;
    }

    /** Wipes the current chat's messages and summary. Facts about you and the persona are kept. */
    private static int clearChat(CommandSourceStack source) {
        ChatSession chat = ChatManager.get(source).getCurrent();

        if (chat.isBusy() || chat.isSummarizing()) {
            Msg.gray(source, "'" + chat.getName() + "' is busy right now. Try again shortly.");
            return Command.SINGLE_SUCCESS;
        }

        chat.clear();
        Msg.ok(source, "Cleared the conversation in '" + chat.getName()
                + "'. I still remember facts about you.");
        ChatStorage.saveAsync(source.getServer());
        return Command.SINGLE_SUCCESS;
    }

    private static int showSummary(CommandSourceStack source) {
        ChatSession chat = ChatManager.get(source).getCurrent();
        String summary = chat.getSummary();

        if (summary.isBlank()) {
            Msg.gray(source, "No summary yet. Chats are summarised once they get long ("
                    + AiConfig.get().summarizeAtMessages + " messages).");
        } else {
            Msg.heading(source, "Summary of earlier messages in '" + chat.getName() + "':");
            Msg.info(source, summary);
        }
        return Command.SINGLE_SUCCESS;
    }

    private static int showHistory(CommandSourceStack source, int count) {
        ChatSession chat = ChatManager.get(source).getCurrent();
        List<ChatMessage> messages = chat.snapshot();

        if (messages.isEmpty()) {
            Msg.gray(source, "'" + chat.getName() + "' has no messages yet.");
            return Command.SINGLE_SUCCESS;
        }

        int start = Math.max(0, messages.size() - count);
        Msg.heading(source, "Last " + (messages.size() - start) + " messages in '" + chat.getName() + "':");

        for (int i = start; i < messages.size(); i++) {
            ChatMessage m = messages.get(i);
            boolean user = "user".equals(m.role());
            Msg.color(source, (user ? "You: " : "AI: ") + Msg.truncate(m.content(), 120),
                    user ? ChatFormatting.YELLOW : ChatFormatting.GREEN);
        }
        return Command.SINGLE_SUCCESS;
    }

    private static int exportChat(CommandSourceStack source) {
        ChatSession chat = ChatManager.get(source).getCurrent();

        try {
            Path file = ChatExporter.export(source.getServer(), source.getTextName(), chat);
            Msg.ok(source, "Saved this chat to " + file);
        } catch (IOException e) {
            OllamaChatMod.LOGGER.error("Failed to export chat", e);
            Msg.error(source, "Could not export the chat: " + e.getMessage());
        }
        return Command.SINGLE_SUCCESS;
    }
}
