package io.openharness.unit;

import io.openharness.core.config.Settings;
import io.openharness.core.config.SettingsLoader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class SettingsLoaderTest {

    @TempDir
    Path userHome;

    @TempDir
    Path projectDir;

    private SettingsLoader loader;

    @BeforeEach
    void setUp() {
        loader = new SettingsLoader(userHome, projectDir);
    }

    @Test
    void loadDefaultsWhenNoConfigFilesExist() {
        Settings settings = loader.load();
        assertThat(settings.getModel()).isEqualTo("claude-sonnet-4-6");
        assertThat(settings.getMaxTurns()).isEqualTo(100);
        assertThat(settings.getLogMode()).isEqualTo("local");
    }

    @Test
    void userConfigOverridesDefaults() throws Exception {
        Path userConfig = userHome.resolve(".oh/settings.yaml");
        Files.createDirectories(userConfig.getParent());
        Files.writeString(userConfig, """
            model: claude-opus-4-7
            maxTurns: 50
            logLevel: DEBUG
            """);

        Settings settings = loader.load();
        assertThat(settings.getModel()).isEqualTo("claude-opus-4-7");
        assertThat(settings.getMaxTurns()).isEqualTo(50);
        assertThat(settings.getLogLevel()).isEqualTo("DEBUG");
        // 未覆盖的保持默认
        assertThat(settings.getLogMode()).isEqualTo("local");
    }

    @Test
    void projectConfigOverridesUserConfig() throws Exception {
        Path userConfig = userHome.resolve(".oh/settings.yaml");
        Files.createDirectories(userConfig.getParent());
        Files.writeString(userConfig, """
            model: claude-opus-4-7
            maxTurns: 50
            """);

        Path projectConfig = projectDir.resolve(".oh/settings.yaml");
        Files.createDirectories(projectConfig.getParent());
        Files.writeString(projectConfig, """
            model: claude-haiku-4-5
            """);

        Settings settings = loader.load();
        // project overrides model
        assertThat(settings.getModel()).isEqualTo("claude-haiku-4-5");
        // user still provides maxTurns (project didn't override)
        assertThat(settings.getMaxTurns()).isEqualTo(50);
    }

    @Test
    void emptyConfigFilesAreGracefullySkipped() throws Exception {
        Path userConfig = userHome.resolve(".oh/settings.yaml");
        Files.createDirectories(userConfig.getParent());
        Files.writeString(userConfig, "");

        assertDoesNotThrow(() -> {
            Settings s = loader.load();
            assertThat(s.getModel()).isEqualTo("claude-sonnet-4-6");
        });
    }

    @Test
    void malformedConfigFilesAreGracefullySkipped() throws Exception {
        Path userConfig = userHome.resolve(".oh/settings.yaml");
        Files.createDirectories(userConfig.getParent());
        Files.writeString(userConfig, "!!! not valid yaml: {{[");

        assertDoesNotThrow(() -> {
            Settings s = loader.load();
            assertThat(s.getModel()).isEqualTo("claude-sonnet-4-6"); // stays default
        });
    }

    @Test
    void loadAndMergeFromExplicitPath() throws Exception {
        Settings base = Settings.defaults();
        Path overrideFile = projectDir.resolve("custom.yaml");
        Files.writeString(overrideFile, "model: custom-model\nmaxTurns: 10\n");

        loader.loadAndMerge(base, overrideFile);
        assertThat(base.getModel()).isEqualTo("custom-model");
        assertThat(base.getMaxTurns()).isEqualTo(10);
    }
}
