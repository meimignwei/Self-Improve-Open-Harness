package com.openharness.extensions.state;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Observable application state store with lightweight pub/sub.
 * Java equivalent of Python's AppStateStore.
 */
public class AppStateStore {

    private volatile AppState current;
    private final List<Consumer<AppState>> subscribers = new CopyOnWriteArrayList<>();

    public AppStateStore(AppState initialState) {
        this.current = initialState;
    }

    public AppState get() { return current; }

    public void set(AppState newState) {
        this.current = newState;
        for (Consumer<AppState> sub : subscribers) {
            sub.accept(newState);
        }
    }

    /**
     * Subscribe to state changes. Returns an unsubscription runnable.
     */
    public Runnable subscribe(Consumer<AppState> listener) {
        subscribers.add(listener);
        return () -> subscribers.remove(listener);
    }
}
