package com.ollamachat.ollama;

import java.io.IOException;

/** Ollama answered, but with an error (wrong model name, bad request, and so on). */
public class OllamaException extends IOException {

    private final int status;

    public OllamaException(int status, String message) {
        super(message);
        this.status = status;
    }

    public int getStatus() {
        return status;
    }
}
