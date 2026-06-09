package io.openharness.unit;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.middleware.AgentInput;
import io.openharness.core.middleware.SessionPersistenceMiddleware;
import io.openharness.core.persistence.AsyncPersistenceWriter;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

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

    @Mock
    private Agent agent;

    @Test
    void shouldEnqueueMessagesOnAgentComplete() {
        List<Map<String, Object>> messages = List.of(
                Map.of("role", "user", "content", "Hello"),
                Map.of("role", "assistant", "content", "Hi there!")
        );

        RuntimeContext ctx = RuntimeContext.builder()
                .sessionId("test-session")
                .build();
        ctx.put("turnNumber", 1);
        ctx.put("turnMessages", messages);

        when(writer.enqueue(any(Runnable.class))).thenReturn(true);

        SessionPersistenceMiddleware middleware = new SessionPersistenceMiddleware(writer, sessionFactory);
        AgentInput input = new AgentInput(List.of());
        Function<AgentInput, Flux<AgentEvent>> next = in -> Flux.empty();

        middleware.onAgent(agent, ctx, input, next).blockLast();

        verify(writer).enqueue(any(Runnable.class));
    }

    @Test
    void shouldNotEnqueueWhenNoMessages() {
        RuntimeContext ctx = RuntimeContext.builder()
                .sessionId("test-session")
                .build();
        ctx.put("turnNumber", 1);

        SessionPersistenceMiddleware middleware = new SessionPersistenceMiddleware(writer, sessionFactory);
        AgentInput input = new AgentInput(List.of());
        Function<AgentInput, Flux<AgentEvent>> next = in -> Flux.empty();

        middleware.onAgent(agent, ctx, input, next).blockLast();

        verify(writer, never()).enqueue(any());
    }

    @Test
    void shouldHandleEnqueueReturningFalse() {
        List<Map<String, Object>> messages = List.of(
                Map.of("role", "user", "content", "Hello")
        );

        RuntimeContext ctx = RuntimeContext.builder()
                .sessionId("test-session")
                .build();
        ctx.put("turnNumber", 1);
        ctx.put("turnMessages", messages);

        when(writer.enqueue(any(Runnable.class))).thenReturn(false);

        SessionPersistenceMiddleware middleware = new SessionPersistenceMiddleware(writer, sessionFactory);
        AgentInput input = new AgentInput(List.of());
        Function<AgentInput, Flux<AgentEvent>> next = in -> Flux.empty();

        middleware.onAgent(agent, ctx, input, next).blockLast();

        verify(writer).enqueue(any(Runnable.class));
    }
}
