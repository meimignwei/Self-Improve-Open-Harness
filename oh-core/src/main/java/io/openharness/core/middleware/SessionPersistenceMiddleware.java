package io.openharness.core.middleware;

import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.MiddlewareContext;
import io.openharness.core.persistence.AsyncPersistenceWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SessionPersistenceMiddleware extends MiddlewareBase {

    private static final Logger log = LoggerFactory.getLogger(SessionPersistenceMiddleware.class);
    private final AsyncPersistenceWriter writer;

    public SessionPersistenceMiddleware(AsyncPersistenceWriter writer) {
        this.writer = writer;
    }

    @Override
    public void onTurnComplete(MiddlewareContext ctx) {
        // Phase 2: 每轮 turn 完成后异步写入 messages + replay_events
        log.debug("SessionPersistenceMiddleware: enqueue turn {} messages", ctx.getTurnNumber());
        super.onTurnComplete(ctx);
    }
}
