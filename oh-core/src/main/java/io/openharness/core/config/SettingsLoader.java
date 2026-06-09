package io.openharness.core.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.Constructor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 三层配置加载与合并:
 *   1. Settings.defaults() — 硬编码默认值
 *   2. ~/.oh/settings.yaml — 用户级覆盖
 *   3. ./.oh/settings.yaml — 项目级覆盖（最高优先级）
 *
 * 支持 .yaml / .yml / .json 格式（SnakeYAML 兼容 JSON）。
 */
public class SettingsLoader {

    private static final Logger log = LoggerFactory.getLogger(SettingsLoader.class);
    private static final String USER_CONFIG = ".oh/settings.yaml";
    private static final String PROJECT_CONFIG = ".oh/settings.yaml";

    private final Path userHome;
    private final Path projectDir;

    public SettingsLoader() {
        this(Path.of(System.getProperty("user.home")), Path.of(System.getProperty("user.dir")));
    }

    public SettingsLoader(Path userHome, Path projectDir) {
        this.userHome = userHome;
        this.projectDir = projectDir;
    }

    /** 完整三层加载 */
    public Settings load() {
        Settings settings = Settings.defaults();

        Path userConfig = userHome.resolve(USER_CONFIG);
        if (Files.exists(userConfig)) {
            Settings user = readFile(userConfig);
            if (user != null) {
                settings.merge(user);
                log.debug("Loaded user config: {}", userConfig);
            }
        }

        Path projectConfig = projectDir.resolve(PROJECT_CONFIG);
        if (Files.exists(projectConfig)) {
            Settings project = readFile(projectConfig);
            if (project != null) {
                settings.merge(project);
                log.debug("Loaded project config: {}", projectConfig);
            }
        }

        return settings;
    }

    /** 仅加载默认值，跳过文件 */
    public Settings loadDefaults() {
        return Settings.defaults();
    }

    /** 加载指定路径并合并到当前 Settings */
    public Settings loadAndMerge(Settings base, Path configPath) {
        Settings override = readFile(configPath);
        if (override != null) {
            base.merge(override);
        }
        return base;
    }

    private Settings readFile(Path path) {
        try {
            String content = Files.readString(path);
            if (content.isBlank()) return null;
            Yaml yaml = new Yaml(new Constructor(Settings.class, new LoaderOptions()));
            return yaml.load(content);
        } catch (IOException e) {
            log.warn("Failed to read config file: {} — {}", path, e.getMessage());
            return null;
        } catch (Exception e) {
            log.warn("Failed to parse config: {} — {}", path, e.getMessage());
            return null;
        }
    }
}
