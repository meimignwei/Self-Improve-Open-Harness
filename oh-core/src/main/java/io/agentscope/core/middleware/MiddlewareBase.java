package io.agentscope.core.middleware;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * [STUB] AgentScope MiddlewareBase 本地桩。
 * AgentScope 2.0.0-SNAPSHOT 可用后删除，直接引入 agentscope-harness。
 */
public abstract class MiddlewareBase {

    protected final Logger log = LoggerFactory.getLogger(getClass());

    public void onStart(MiddlewareContext ctx) {
        log.trace("MiddlewareBase.onStart");
    }

    public void onModelCall(MiddlewareContext ctx) {
        log.trace("MiddlewareBase.onModelCall");
    }

    public void onActing(MiddlewareContext ctx) {
        log.trace("MiddlewareBase.onActing");
    }

    public void onTurnComplete(MiddlewareContext ctx) {
        log.trace("MiddlewareBase.onTurnComplete");
    }
}
