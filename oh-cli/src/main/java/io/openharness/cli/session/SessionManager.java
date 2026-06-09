package io.openharness.cli.session;

import io.openharness.core.config.Settings;
import io.openharness.core.persistence.AsyncPersistenceWriter;
import io.openharness.core.persistence.mapper.SessionMapper;
import io.openharness.core.persistence.model.Session;
import io.openharness.core.session.SessionContext;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public class SessionManager {

    private static final Logger log = LoggerFactory.getLogger(SessionManager.class);

    private final SqlSessionFactory sessionFactory;
    private final AsyncPersistenceWriter writer;
    private final Settings settings;

    public SessionManager(SqlSessionFactory sessionFactory, Settings settings) {
        this.sessionFactory = sessionFactory;
        this.writer = new AsyncPersistenceWriter(sessionFactory);
        this.settings = settings;
    }

    public AsyncPersistenceWriter getWriter() {
        return writer;
    }

    public SessionContext createSession(Path cwd) {
        String sessionId = UUID.randomUUID().toString();
        Instant now = Instant.now();

        Session session = new Session(
                sessionId, "active", settings.getModel(),
                settings.getMaxTurns(), 0.0, now, now);

        try (SqlSession sqlSession = sessionFactory.openSession()) {
            sqlSession.getMapper(SessionMapper.class).insert(session);
            sqlSession.commit();
        }

        log.info("Session created: id={}, model={}", sessionId, settings.getModel());

        return SessionContext.builder()
                .sessionId(sessionId)
                .workspaceDir(cwd)
                .settings(settings)
                .model(settings.getModel())
                .createdAt(now)
                .build();
    }

    public Optional<SessionContext> restoreSession(String sessionId) {
        try (SqlSession sqlSession = sessionFactory.openSession()) {
            Session session = sqlSession.getMapper(SessionMapper.class).findById(sessionId);
            if (session == null) {
                log.warn("Session not found: {}", sessionId);
                return Optional.empty();
            }

            return Optional.of(SessionContext.builder()
                    .sessionId(session.id())
                    .workspaceDir(Path.of(settings.getWorkspaceDir()))
                    .settings(settings)
                    .model(session.model())
                    .createdAt(session.createdAt())
                    .build());
        }
    }

    public Optional<SessionContext> restoreLastSession() {
        try (SqlSession sqlSession = sessionFactory.openSession()) {
            List<Session> sessions = sqlSession.getMapper(SessionMapper.class).findAll();
            if (sessions.isEmpty()) {
                return Optional.empty();
            }

            Session latest = sessions.stream()
                    .filter(s -> "active".equals(s.status()))
                    .max((a, b) -> a.createdAt().compareTo(b.createdAt()))
                    .orElse(null);

            if (latest == null) {
                return Optional.empty();
            }

            return restoreSession(latest.id());
        }
    }

    public List<Session> listSessions() {
        try (SqlSession sqlSession = sessionFactory.openSession()) {
            return sqlSession.getMapper(SessionMapper.class).findAll().stream()
                    .filter(s -> "active".equals(s.status()))
                    .toList();
        }
    }

    public void saveSession(SessionContext ctx) {
        double cost = 0.0;
        try (SqlSession sqlSession = sessionFactory.openSession()) {
            Session existing = sqlSession.getMapper(SessionMapper.class).findById(ctx.getSessionId());
            if (existing == null) {
                return;
            }

            Session updated = new Session(
                    existing.id(),
                    existing.status(),
                    ctx.getModel() != null ? ctx.getModel() : existing.model(),
                    existing.maxTurns(),
                    cost,
                    existing.createdAt(),
                    Instant.now()
            );
            sqlSession.getMapper(SessionMapper.class).update(updated);
            sqlSession.commit();
        }
    }

    public void releaseLocks() {
        try (SqlSession sqlSession = sessionFactory.openSession()) {
            List<Session> active = sqlSession.getMapper(SessionMapper.class).findAll().stream()
                    .filter(s -> "active".equals(s.status()))
                    .toList();

            for (Session s : active) {
                Session closed = new Session(
                        s.id(), "closed", s.model(), s.maxTurns(), s.cost(),
                        s.createdAt(), Instant.now());
                sqlSession.getMapper(SessionMapper.class).update(closed);
            }
            sqlSession.commit();
        }
    }

    public void flushAll() {
        writer.shutdown();
    }
}
