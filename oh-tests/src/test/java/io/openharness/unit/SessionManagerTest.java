package io.openharness.unit;

import io.openharness.cli.session.SessionManager;
import io.openharness.core.config.DataSourceConfig;
import io.openharness.core.config.Settings;
import io.openharness.core.persistence.model.Session;
import io.openharness.core.session.SessionContext;
import org.apache.ibatis.jdbc.ScriptRunner;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.sql.DataSource;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class SessionManagerTest {

    private SqlSessionFactory sessionFactory;
    private SessionManager manager;
    private Settings settings;

    @BeforeEach
    void setUp(@TempDir Path tempDir) throws Exception {
        settings = Settings.defaults();
        settings.setModel("claude-sonnet-4-6");
        settings.setWorkspaceDir(tempDir.toString());

        String jdbcUrl = "jdbc:h2:mem:testdb-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1";
        DataSource ds = DataSourceConfig.createHikariDataSource(jdbcUrl, "sa", "");
        sessionFactory = DataSourceConfig.createSqlSessionFactory(ds);

        try (var conn = ds.getConnection();
             var reader = new InputStreamReader(
                     getClass().getClassLoader().getResourceAsStream("db/migration/V001__init_schema.sql"))) {
            ScriptRunner runner = new ScriptRunner(conn);
            runner.setLogWriter(null);
            runner.runScript(reader);
        }

        manager = new SessionManager(sessionFactory, settings);
    }

    @AfterEach
    void tearDown() {
        if (manager != null) {
            manager.flushAll();
        }
    }

    @Test
    void shouldCreateAndPersistSession() {
        SessionContext ctx = manager.createSession(Path.of("/tmp/workspace"));

        assertThat(ctx).isNotNull();
        assertThat(ctx.getSessionId()).isNotBlank();
        assertThat(ctx.getModel()).isEqualTo("claude-sonnet-4-6");
    }

    @Test
    void shouldRestoreExistingSession() {
        SessionContext created = manager.createSession(Path.of("/tmp/workspace"));

        Optional<SessionContext> restored = manager.restoreSession(created.getSessionId());

        assertThat(restored).isPresent();
        assertThat(restored.get().getSessionId()).isEqualTo(created.getSessionId());
        assertThat(restored.get().getModel()).isEqualTo("claude-sonnet-4-6");
    }

    @Test
    void shouldReturnEmptyForNonExistentSession() {
        Optional<SessionContext> restored = manager.restoreSession("nonexistent-id");
        assertThat(restored).isEmpty();
    }

    @Test
    void shouldListActiveSessions() {
        manager.createSession(Path.of("/tmp/ws1"));
        manager.createSession(Path.of("/tmp/ws2"));

        List<Session> sessions = manager.listSessions();
        assertThat(sessions).hasSize(2);
    }

    @Test
    void shouldRestoreLastSession() {
        manager.createSession(Path.of("/tmp/ws-first"));

        Optional<SessionContext> last = manager.restoreLastSession();
        assertThat(last).isPresent();
    }

    @Test
    void shouldReleaseLocksAndCloseSessions() {
        SessionContext ctx = manager.createSession(Path.of("/tmp/ws"));
        assertThat(manager.listSessions()).hasSize(1);

        manager.releaseLocks();

        List<Session> after = manager.listSessions();
        assertThat(after).isEmpty();
    }
}
