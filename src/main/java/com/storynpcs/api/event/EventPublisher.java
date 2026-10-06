package com.storynpcs.api.event;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

public class EventPublisher {
    private final List<Consumer<StoryNpcsEvent>> listeners = new CopyOnWriteArrayList<>();

    public void register(Consumer<StoryNpcsEvent> listener) {
        listeners.add(listener);
    }

    /** Remove a listener — subscription teardown for API clients. */
    public boolean unregister(Consumer<StoryNpcsEvent> listener) {
        return listeners.remove(listener);
    }

    public void publish(StoryNpcsEvent event) {
        for (Consumer<StoryNpcsEvent> listener : listeners) {
            try {
                listener.accept(event);
            } catch (Exception e) {
                logListenerFailure(e);
            } catch (AssertionError e) {
                // Assertions from an extension listener must not strand later listeners or queued events.
                logListenerFailure(e);
            }
        }
    }

    private static void logListenerFailure(Throwable failure) {
        System.err.println("Error publishing event: " + failure.getMessage());
    }
}
