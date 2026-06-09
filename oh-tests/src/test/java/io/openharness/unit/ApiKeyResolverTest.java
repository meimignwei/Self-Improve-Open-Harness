package io.openharness.unit;

import io.openharness.core.config.ApiKeyResolver;
import io.openharness.core.config.Settings;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ApiKeyResolverTest {

    @Test
    void resolveFromSettingsWhenNoEnvVar() {
        Settings settings = Settings.defaults();
        settings.setApiKey("sk-ant-test-key-12345");

        ApiKeyResolver resolver = new ApiKeyResolver(settings);
        String key = resolver.resolve();

        // env var not set + no keychain in CI → falls back to settings
        assertThat(key).isEqualTo("sk-ant-test-key-12345");
    }

    @Test
    void maskShortKey() {
        assertThat(ApiKeyResolver.mask("short")).isEqualTo("***");
    }

    @Test
    void maskLongKey() {
        String masked = ApiKeyResolver.mask("sk-ant-api03-abcdefghijklmnopqrstuvwxyz");
        assertThat(masked).startsWith("sk-ant-").endsWith("wxyz");
        assertThat(masked).contains("***");
        assertThat(masked).doesNotContain("abcdefghijklmnopqrstuv");
    }

    @Test
    void maskNullKey() {
        assertThat(ApiKeyResolver.mask(null)).isEqualTo("***");
    }

    @Test
    void isConfiguredReturnsFalseWhenNoKey() {
        Settings settings = Settings.defaults();
        settings.setApiKey(null);

        ApiKeyResolver resolver = new ApiKeyResolver(settings);
        // No env var, no keychain in test → not configured
        assertThat(resolver.isConfigured()).isFalse();
    }

    @Test
    void isConfiguredReturnsTrueWhenKeyInSettings() {
        Settings settings = Settings.defaults();
        settings.setApiKey("sk-ant-key");

        ApiKeyResolver resolver = new ApiKeyResolver(settings);
        assertThat(resolver.isConfigured()).isTrue();
    }
}
