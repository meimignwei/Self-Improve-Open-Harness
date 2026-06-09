package io.openharness.core.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStreamReader;

/**
 * API Key 安全解析。优先级:
 *   1. 环境变量 ANTHROPIC_API_KEY
 *   2. 系统 Keychain（macOS security 命令 / Linux secret-tool）
 *   3. settings.yaml 明文（最低优先级，打印 WARN）
 */
public class ApiKeyResolver {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyResolver.class);
    private static final String ENV_KEY = "ANTHROPIC_API_KEY";
    private static final String KEYCHAIN_SERVICE = "openharness";
    private static final String KEYCHAIN_ACCOUNT = "anthropic-api-key";

    private final Settings settings;

    public ApiKeyResolver(Settings settings) {
        this.settings = settings;
    }

    /**
     * 按优先级解析 API key。返回非空字符串或 null。
     */
    public String resolve() {
        // 1. 环境变量
        String key = System.getenv(ENV_KEY);
        if (key != null && !key.isBlank()) {
            log.debug("API key resolved from env var {}", ENV_KEY);
            return key;
        }

        // 2. 系统 Keychain
        key = readFromKeychain();
        if (key != null && !key.isBlank()) {
            log.debug("API key resolved from system keychain");
            return key;
        }

        // 3. settings 明文
        key = settings.getApiKey();
        if (key != null && !key.isBlank()) {
            log.warn("API key loaded from settings.yaml — consider using env var or keychain instead");
            return key;
        }

        log.error("No API key found. Set ANTHROPIC_API_KEY env var or configure keychain.");
        return null;
    }

    private String readFromKeychain() {
        String os = System.getProperty("os.name").toLowerCase();
        try {
            if (os.contains("mac")) {
                return runCmd("security", "find-generic-password",
                    "-s", KEYCHAIN_SERVICE,
                    "-a", KEYCHAIN_ACCOUNT,
                    "-w");
            }
            if (os.contains("linux")) {
                return runCmd("secret-tool", "lookup",
                    "service", KEYCHAIN_SERVICE,
                    "account", KEYCHAIN_ACCOUNT);
            }
        } catch (Exception e) {
            log.debug("Keychain lookup failed: {}", e.getMessage());
        }
        return null;
    }

    private String runCmd(String... cmd) throws Exception {
        Process proc = new ProcessBuilder(cmd)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
            String line = reader.readLine();
            proc.waitFor();
            return (line != null && !line.isBlank()) ? line.trim() : null;
        }
    }

    /**
     * 检查 API key 是否已配置（不暴露 key 内容）
     */
    public boolean isConfigured() {
        return resolve() != null;
    }

    /** 生成安全的 key 显示掩码: sk-ant-*** */
    public static String mask(String key) {
        if (key == null || key.length() <= 10) return "***";
        return key.substring(0, 7) + "***" + key.substring(key.length() - 4);
    }
}
