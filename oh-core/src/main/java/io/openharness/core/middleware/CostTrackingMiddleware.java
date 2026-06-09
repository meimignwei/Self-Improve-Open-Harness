package io.openharness.core.middleware;

import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.MiddlewareContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CostTrackingMiddleware extends MiddlewareBase {

    private static final Logger log = LoggerFactory.getLogger(CostTrackingMiddleware.class);

    @Override
    public void onModelCall(MiddlewareContext ctx) {
        // Phase 2: 追踪 token 用量 → 计算成本 → 更新 Session.cost
        super.onModelCall(ctx);
    }
}
