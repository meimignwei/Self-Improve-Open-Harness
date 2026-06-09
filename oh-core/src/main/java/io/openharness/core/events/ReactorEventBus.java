package io.openharness.core.events;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class ReactorEventBus implements EventBus {

    private static final Logger log = LoggerFactory.getLogger(ReactorEventBus.class);

    private final Sinks.Many<OhEvent> sink;
    private final Flux<OhEvent> sharedFlux;
    private final Map<Class<?>, Disposable> subscriptions = new ConcurrentHashMap<>();

    public ReactorEventBus() {
        this.sink = Sinks.many().multicast().onBackpressureBuffer();
        this.sharedFlux = sink.asFlux().share();
    }

    @Override
    public void publish(OhEvent event) {
        Sinks.EmitResult result = sink.tryEmitNext(event);
        if (result.isFailure()) {
            log.warn("EventBus publish failed: {}, event={}", result, event.getClass().getSimpleName());
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends OhEvent> Disposable subscribe(Class<T> eventType, EventHandler<T> handler) {
        Disposable disposable = sharedFlux
                .filter(eventType::isInstance)
                .map(e -> (T) e)
                .subscribe(handler::handle,
                        error -> log.error("EventBus subscriber error for {}", eventType.getSimpleName(), error));

        subscriptions.put(eventType, disposable);
        log.debug("EventBus: subscribed to {}", eventType.getSimpleName());
        return disposable;
    }
}
