package com.ollamachat.ollama;

import java.util.concurrent.CompletableFuture;

/**
 * Lets a running Ollama request be cancelled from another thread
 * (for /ai stop, and when the server shuts down).
 */
public final class RequestHandle {

    /** Thrown when a request was cancelled on purpose. */
    public static class CancelledException extends Exception {
        public CancelledException() {
            super("Request cancelled");
        }
    }

    private volatile boolean cancelled;
    private volatile CompletableFuture<?> current;

    public void cancel() {
        cancelled = true;
        CompletableFuture<?> future = current;
        if (future != null) {
            future.cancel(true);
        }
    }

    public boolean isCancelled() {
        return cancelled;
    }

    public void throwIfCancelled() throws CancelledException {
        if (cancelled) {
            throw new CancelledException();
        }
    }

    /** Called by the client for each HTTP request it starts. */
    void attach(CompletableFuture<?> future) {
        current = future;
        if (cancelled) {
            future.cancel(true);
        }
    }
}
