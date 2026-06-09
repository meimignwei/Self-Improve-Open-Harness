package io.openharness.core.events;

import reactor.core.Disposable;

public interface EventBus {
    void publish(OhEvent event);
    <T extends OhEvent> Disposable subscribe(Class<T> eventType, EventHandler<T> handler);
}
