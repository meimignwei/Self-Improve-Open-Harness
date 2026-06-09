package io.openharness.unit;

import io.agentscope.core.middleware.MiddlewareContext;
import io.openharness.core.middleware.SessionPersistenceMiddleware;
import io.openharness.core.persistence.AsyncPersistenceWriter;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SessionPersistenceMiddlewareTest {

    @Mock
    private AsyncPersistenceWriter writer;

    @Mock
    private SqlSessionFactory sessionFactory;

    @Test
    void shouldEnqueueMessagesOnTurnComplete() {
        List<Map<String, Object>> messages = List.of(
                Map.of("role", "user", "content", "Hello"),
                Map.of("role", "assistant", "content", "Hi there!")
        );

        MiddlewareContext ctx = new MiddlewareContext();
        ctx.setAttribute("sessionId", "test-session");
        ctx.setAttribute("turnNumber", 1);
        ctx.setAttribute("turnMessages", messages);

        when(writer.enqueue(any(Runnable.class))).thenReturn(true);

        SessionPersistenceMiddleware middleware = new SessionPersistenceMiddleware(writer, sessionFactory);
        middleware.onTurnComplete(ctx);

        verify(writer).enqueue(any(Runnable.class));
    }

    @Test
    void shouldNotEnqueueWhenNoMessages() {
        MiddlewareContext ctx = new MiddlewareContext();
        ctx.setAttribute("sessionId", "test-session");
        ctx.setAttribute("turnNumber", 1);

        SessionPersistenceMiddleware middleware = new SessionPersistenceMiddleware(writer, sessionFactory);
        middleware.onTurnComplete(ctx);

        verify(writer, never()).enqueue(any());
    }

    @Test
    void shouldHandleEnqueueReturningFalse() {
        List<Map<String, Object>> messages = List.of(
                Map.of("role", "user", "content", "Hello")
        );

        MiddlewareContext ctx = new MiddlewareContext();
        ctx.setAttribute("sessionId", "test-session");
        ctx.setAttribute("turnNumber", 1);
        ctx.setAttribute("turnMessages", messages);

        when(writer.enqueue(any(Runnable.class))).thenReturn(false);

        SessionPersistenceMiddleware middleware = new SessionPersistenceMiddleware(writer, sessionFactory);
        middleware.onTurnComplete(ctx);

        verify(writer).enqueue(any(Runnable.class));
    }
}
