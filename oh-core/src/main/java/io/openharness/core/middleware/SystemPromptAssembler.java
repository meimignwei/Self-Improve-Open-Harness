package io.openharness.core.middleware;

import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.MiddlewareContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SystemPromptAssembler extends MiddlewareBase {

    private static final Logger log = LoggerFactory.getLogger(SystemPromptAssembler.class);

    @Override
    public void onStart(MiddlewareContext ctx) {
        // Phase 2: 注入 AGENTS.md + MEMORY.md + skills + permission rules
        log.debug("SystemPromptAssembler.onStart: sessionId={}", ctx.getSessionId());
        super.onStart(ctx);
    }
}
