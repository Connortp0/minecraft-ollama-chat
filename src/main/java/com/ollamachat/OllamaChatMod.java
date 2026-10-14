package com.ollamachat;

import com.ollamachat.chat.ChatStorage;
import com.ollamachat.config.AiConfig;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class OllamaChatMod implements ModInitializer {

    public static final String MOD_ID = "ollama-chat";

    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        LOGGER.info("Ollama Chat loaded");

        AiConfig.load();
        AiService.init();
        AiCommand.register();

        // Registered before ChatStorage so running requests are cancelled before the final save.
        ServerLifecycleEvents.SERVER_STARTED.register(AiService::onServerStarted);
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> AiService.shutdown());

        ChatStorage.register(); // load on start, save on stop
    }
}
