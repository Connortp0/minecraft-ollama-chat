package com.ollamachat.chat;

/**
 * A change to the player's saved facts, requested by the model through a tool call.
 * Changes are collected on the AI thread and applied later on the server thread.
 */
public record FactChange(Type type, String oldText, String text) {

    public enum Type {
        ADD,
        UPDATE,
        REMOVE
    }

    public static FactChange add(String text) {
        return new FactChange(Type.ADD, null, text);
    }

    public static FactChange update(String oldText, String text) {
        return new FactChange(Type.UPDATE, oldText, text);
    }

    public static FactChange remove(String oldText) {
        return new FactChange(Type.REMOVE, oldText, null);
    }
}
