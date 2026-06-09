package io.openharness.unit;

import io.openharness.core.config.ProfileManager;
import io.openharness.core.config.Settings;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProfileManagerTest {

    @TempDir
    Path profilesDir;

    private ProfileManager manager;

    @BeforeEach
    void setUp() {
        manager = new ProfileManager(profilesDir);
    }

    @Test
    void saveAndLoadRoundtrip() {
        Settings settings = Settings.defaults();
        settings.setModel("claude-sonnet-4-6");
        settings.setMaxTurns(200);

        manager.save("work", settings);
        Settings loaded = manager.load("work");

        assertThat(loaded).isNotNull();
        assertThat(loaded.getModel()).isEqualTo("claude-sonnet-4-6");
        assertThat(loaded.getMaxTurns()).isEqualTo(200);
    }

    @Test
    void listProfiles() {
        manager.save("java", Settings.defaults());
        manager.save("python", Settings.defaults());
        manager.save("go", Settings.defaults());

        List<String> names = manager.list();
        assertThat(names).containsExactly("go", "java", "python"); // sorted
    }

    @Test
    void deleteProfile() {
        manager.save("temp", Settings.defaults());
        assertThat(manager.load("temp")).isNotNull();

        boolean deleted = manager.delete("temp");
        assertThat(deleted).isTrue();
        assertThat(manager.load("temp")).isNull();
    }

    @Test
    void loadNonExistentReturnsNull() {
        assertThat(manager.load("nonexistent")).isNull();
    }

    @Test
    void copyProfile() {
        Settings settings = Settings.defaults();
        settings.setModel("claude-opus-4-7");
        manager.save("source", settings);

        manager.copy("source", "target");

        Settings loaded = manager.load("target");
        assertThat(loaded).isNotNull();
        assertThat(loaded.getModel()).isEqualTo("claude-opus-4-7");
    }

    @Test
    void copyNonExistentThrows() {
        assertThatThrownBy(() -> manager.copy("nope", "target"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void listEmptyProfiles() {
        List<String> names = manager.list();
        assertThat(names).isEmpty();
    }
}
