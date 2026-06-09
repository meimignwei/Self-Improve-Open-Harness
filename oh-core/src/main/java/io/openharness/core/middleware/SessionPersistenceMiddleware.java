package io.openharness.core.middleware;

import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.MiddlewareContext;
import io.openharness.core.persistence.AsyncPersistenceWriter;
import io.openharness.core.persistence.mapper.MessageMapper;
import io.openharness.core.persistence.model.Message;
import org.apache.ibatis.session.SqlSessionFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class SessionPersistenceMiddleware extends MiddlewareBase {

    private static final Logger log = LoggerFactory.getLogger(SessionPersistenceMiddleware.class);
    private final AsyncPersistenceWriter writer;
    private final SqlSessionFactory sessionFactory;

    public SessionPersistenceMiddleware(AsyncPersistenceWriter writer, SqlSessionFactory sessionFactory) {
        this.writer = writer;
        this.sessionFactory = sessionFactory;
    }

    @Override
    @SuppressWarnings("unchecked")
    public void onTurnComplete(MiddlewareContext ctx) {
        Object raw = ctx.getAttribute("turnMessages");
        if (raw == null) {
            log.debug("SessionPersistenceMiddleware: no messages for turn {}, skipping", ctx.getTurnNumber());
            super.onTurnComplete(ctx);
            return;
        }

        List<Map<String, Object>> messageData;
        if (raw instanceof List<?> list) {
            messageData = (List<Map<String, Object>>) list;
        } else {
            log.warn("SessionPersistenceMiddleware: unexpected turnMessages type: {}", raw.getClass());
            super.onTurnComplete(ctx);
            return;
        }

        String sessionId = ctx.getSessionId();
        int turnNumber = ctx.getTurnNumber();
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

        log.debug("SessionPersistenceMiddleware: enqueuing {} messages for turn {}", messages.size(), turnNumber);
        writer.enqueue(() -> {
            try (var session = sessionFactory.openSession()) {
                MessageMapper mapper = session.getMapper(MessageMapper.class);
                mapper.batchInsert(messages);
                session.commit();
            } catch (Exception e) {
                log.error("Failed to persist messages for session {} turn {}", sessionId, turnNumber, e);
            }
        });

        super.onTurnComplete(ctx);
    }

    private static String safeString(Object value) {
        return value != null ? value.toString() : null;
    }
}
