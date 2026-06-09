package io.openharness.core.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.Constructor;

import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

/**
 * Profile CRUD。每个 profile 是一组命名预设，存储在 ~/.oh/profiles/ 目录下。
 */
public class ProfileManager {

    private static final Logger log = LoggerFactory.getLogger(ProfileManager.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String PROFILE_EXT = ".json";

    private final Path profilesDir;

    public ProfileManager() {
        this(Path.of(System.getProperty("user.home"), ".oh", "profiles"));
    }

    public ProfileManager(Path profilesDir) {
        this.profilesDir = profilesDir;
    }

    private void ensureDir() {
        try {
            Files.createDirectories(profilesDir);
        } catch (IOException e) {
            throw new RuntimeException("Cannot create profiles directory: " + profilesDir, e);
        }
    }

    /** 保存 profile */
    public void save(String name, Settings settings) {
        ensureDir();
        Path file = profileFile(name);
        try {
            JSON.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), settings);
            log.info("Profile saved: {}", name);
        } catch (IOException e) {
            throw new RuntimeException("Failed to save profile: " + name, e);
        }
    }

    /** 加载指定 profile */
    public Settings load(String name) {
        Path file = profileFile(name);
        if (!Files.exists(file)) {
            log.warn("Profile not found: {}", name);
            return null;
        }
        try {
            return JSON.readValue(file.toFile(), Settings.class);
        } catch (IOException e) {
            throw new RuntimeException("Failed to load profile: " + name, e);
        }
    }

    /** 列出所有 profile 名称 */
    public List<String> list() {
        ensureDir();
        try (Stream<Path> files = Files.list(profilesDir)) {
            return files
                .filter(p -> p.getFileName().toString().endsWith(PROFILE_EXT))
                .map(p -> p.getFileName().toString())
                .map(n -> n.substring(0, n.length() - PROFILE_EXT.length()))
                .sorted()
                .toList();
        } catch (IOException e) {
            return Collections.emptyList();
        }
    }

    /** 删除 profile */
    public boolean delete(String name) {
        Path file = profileFile(name);
        try {
            boolean deleted = Files.deleteIfExists(file);
            if (deleted) log.info("Profile deleted: {}", name);
            return deleted;
        } catch (IOException e) {
            log.error("Failed to delete profile: {}", name, e);
            return false;
        }
    }

    /** 复制 profile */
    public void copy(String source, String target) {
        Settings s = load(source);
        if (s == null) throw new IllegalArgumentException("Source profile not found: " + source);
        save(target, s);
        log.info("Profile copied: {} -> {}", source, target);
    }

    private Path profileFile(String name) {
        return profilesDir.resolve(name + PROFILE_EXT);
    }
}
