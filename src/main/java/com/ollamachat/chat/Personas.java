package com.ollamachat.chat;

import com.ollamachat.config.AiConfig;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Built-in personas, plus any custom ones from the config file. */
public final class Personas {

    private static final Map<String, String> BUILTIN = new LinkedHashMap<>();

    static {
        BUILTIN.put("default", "");
        BUILTIN.put("concise", "Answer in as few words as possible. No filler.");
        BUILTIN.put("teacher", "Explain things step by step in simple language, like a patient teacher. Use a tiny example when it helps.");
        BUILTIN.put("coder", "You are an expert programmer. Give correct, practical answers and keep code snippets short.");
        BUILTIN.put("storyteller", "You are a creative storyteller. Reply with vivid but brief writing.");
        BUILTIN.put("pirate", "Talk like a friendly pirate, but stay accurate and helpful.");
    }

    private Personas() {
    }

    private static String key(String name) {
        return name == null ? "" : name.toLowerCase(Locale.ROOT);
    }

    /** Returns the instructions for a persona, or an empty string if it has none or doesn't exist. */
    public static String text(String name) {
        String k = key(name);
        for (Map.Entry<String, String> e : AiConfig.get().customPersonas.entrySet()) {
            if (key(e.getKey()).equals(k) && e.getValue() != null) {
                return e.getValue();
            }
        }
        return BUILTIN.getOrDefault(k, "");
    }

    public static boolean exists(String name) {
        String k = key(name);
        if (BUILTIN.containsKey(k)) {
            return true;
        }
        for (String custom : AiConfig.get().customPersonas.keySet()) {
            if (key(custom).equals(k)) {
                return true;
            }
        }
        return false;
    }

    public static List<String> names() {
        List<String> names = new ArrayList<>(BUILTIN.keySet());
        for (String custom : AiConfig.get().customPersonas.keySet()) {
            String k = key(custom);
            if (!names.contains(k)) {
                names.add(k);
            }
        }
        return names;
    }

    /** Normalises a name the way it is stored. */
    public static String normalize(String name) {
        return key(name);
    }
}
