package com.storynpcs.api.event;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public class EventPublisher {
    private final List<Consumer<StoryNpcsEvent>> listeners = new ArrayList<>();

    public void register(Consumer<StoryNpcsEvent> listener) {
        listeners.add(listener);
    }

    public void publish(StoryNpcsEvent event) {
        for (Consumer<StoryNpcsEvent> listener : listeners) {
            try {
                listener.accept(event);
            } catch (Exception e) {
                System.err.println("Error publishing event: " + e.getMessage());
            }
        }
    }
}
