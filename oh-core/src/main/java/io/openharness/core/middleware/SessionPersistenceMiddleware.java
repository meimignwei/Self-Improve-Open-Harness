package io.openharness.core.middleware;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.openharness.core.persistence.AsyncPersistenceWriter;
import io.openharness.core.persistence.mapper.MessageMapper;
import io.openharness.core.persistence.model.Message;
import org.apache.ibatis.session.SqlSessionFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

public class SessionPersistenceMiddleware implements MiddlewareBase {

    private static final Logger log = LoggerFactory.getLogger(SessionPersistenceMiddleware.class);
    private final AsyncPersistenceWriter writer;
    private final SqlSessionFactory sessionFactory;

    public SessionPersistenceMiddleware(AsyncPersistenceWriter writer, SqlSessionFactory sessionFactory) {
        this.writer = writer;
        this.sessionFactory = sessionFactory;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Flux<AgentEvent> onAgent(Agent agent, RuntimeContext ctx, AgentInput input,
                                     Function<AgentInput, Flux<AgentEvent>> next) {
        return next.apply(input).doOnComplete(() -> {
            String sessionId = ctx.getSessionId();
            Object raw = ctx.get("turnMessages");
            if (raw == null) {
                log.debug("SessionPersistenceMiddleware: no messages for session {}", sessionId);
                return;
            }

            List<Map<String, Object>> messageData;
            if (raw instanceof List<?> list) {
                messageData = (List<Map<String, Object>>) list;
            } else {
                log.warn("SessionPersistenceMiddleware: unexpected turnMessages type: {}", raw.getClass());
                return;
            }

            int turnNumber = safeInt(ctx.get("turnNumber"));
            List<Message> messages = new ArrayList<>();

            for (Map<String, Object> data : messageData) {
                String role = safeString(data.get("role"));
                String content = safeString(data.get("content"));
                String toolName = safeString(data.get("toolName"));
                String toolUseId = safeString(data.get("toolUseId"));

                messages.add(new Message(
                        UUID.randomUUID().toString(),
                        sessionId,
                        turnNumber,
                        role != null ? role : "unknown",
                        content != null ? content : "",
                        toolName,
                        toolUseId,
                        Instant.now()
                ));
            }

            log.debug("SessionPersistenceMiddleware: enqueuing {} messages for session {} turn {}",
                    messages.size(), sessionId, turnNumber);
            writer.enqueue(() -> {
                try (var session = sessionFactory.openSession()) {
                    MessageMapper mapper = session.getMapper(MessageMapper.class);
                    mapper.batchInsert(messages);
                    session.commit();
                } catch (Exception e) {
                    log.error("Failed to persist messages for session {} turn {}", sessionId, turnNumber, e);
                }
            });
        });
    }

    private static String safeString(Object value) {
        return value != null ? value.toString() : null;
    }

    private static int safeInt(Object value) {
        if (value instanceof Number n) return n.intValue();
        return 0;
    }
}
