package io.openharness.core.events;

@FunctionalInterface
public interface EventHandler<T extends OhEvent> {
    void handle(T event);
}
