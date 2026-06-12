package com.openharness.extensions.hooks;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openharness.common.OpenHarnessObjectMapper;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * Main hook execution engine. Dispatches hooks by type: command, HTTP, prompt, agent.
 * Java equivalent of Python's HookExecutor.
 */
public class HookExecutor {

    private static final Logger LOG = Logger.getLogger(HookExecutor.class.getName());

    private final HookRegistry registry;
    private final Path cwd;
    private final HttpClient httpClient;
    private final ObjectMapper mapper;

    public HookExecutor(HookRegistry registry, Path cwd) {
        this.registry = registry;
        this.cwd = cwd;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.mapper = OpenHarnessObjectMapper.get();
    }

    /**
     * Execute all hooks registered for the given event.
     * Returns the first blocking result, or aggregates all results.
     */
    public AggregatedHookResult execute(HookEvent event, Map<String, Object> payload) {
        List<HookDefinition> hooks = registry.get(event);
        List<HookResult> results = new ArrayList<>();

        for (HookDefinition hook : hooks) {
            if (!matchesHook(hook, event, payload)) continue;

            HookResult result = switch (hook) {
                case HookDefinition.CommandHook ch -> runCommandHook(ch, payload);
                case HookDefinition.HttpHook hh -> runHttpHook(hh, payload);
                case HookDefinition.PromptHook ph -> runPromptLike(ph, payload);
                case HookDefinition.AgentHook ah -> runPromptLike(ah, payload);
            };

            results.add(result);
            LOG.fine(() -> "Hook [" + hook.getClass().getSimpleName() + "] result: " + result);

            if (!result.success() && hook.blockOnFailure()) break;
        }

        return new AggregatedHookResult(results);
    }

    /**
     * Execute hooks synchronously and throw if any hook blocks.
     */
    public void executeOrThrow(HookEvent event, Map<String, Object> payload) {
        AggregatedHookResult result = execute(event, payload);
        if (result.blocked()) {
            throw new HookBlockedException(result.reason());
        }
    }

    private HookResult runCommandHook(HookDefinition.CommandHook hook, Map<String, Object> payload) {
        try {
            ProcessBuilder pb = new ProcessBuilder(hook.command().split("\\s+"));
            pb.directory(cwd.toFile());
            pb.environment().put("OPENHARNESS_HOOK_EVENT", hook.matcher());
            pb.environment().put("OPENHARNESS_HOOK_PAYLOAD",
                    mapper.writeValueAsString(payload));
            pb.redirectErrorStream(true);

            Process process = pb.start();
            boolean finished = process.waitFor(hook.timeoutSeconds(), java.util.concurrent.TimeUnit.SECONDS);

            if (!finished) {
                process.destroyForcibly();
                return HookResult.failed("Command timed out after " + hook.timeoutSeconds() + "s");
            }

            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            }

            int exitCode = process.exitValue();
            if (exitCode == 2) {
                return HookResult.blocked(output.toString().stripTrailing());
            } else if (exitCode != 0) {
                return HookResult.failed("Exit code " + exitCode + ": " + output);
            }

            return HookResult.ok(output.toString().stripTrailing());

        } catch (Exception e) {
            return HookResult.failed("Command hook failed: " + e.getMessage());
        }
    }

    private HookResult runHttpHook(HookDefinition.HttpHook hook, Map<String, Object> payload) {
        try {
            String json = mapper.writeValueAsString(payload);

            var builder = HttpRequest.newBuilder()
                    .uri(URI.create(hook.url()))
                    .timeout(Duration.ofSeconds(hook.timeoutSeconds()))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json));

            if (hook.headers() != null) {
                hook.headers().forEach(builder::header);
            }

            HttpRequest request = builder.build();
            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                return HookResult.ok(response.body());
            } else if (response.statusCode() == 403) {
                return HookResult.blocked("HTTP hook blocked: " + response.body());
            } else {
                return HookResult.failed("HTTP " + response.statusCode() + ": " + response.body());
            }

        } catch (Exception e) {
            return HookResult.failed("HTTP hook failed: " + e.getMessage());
        }
    }

    private HookResult runPromptLike(HookDefinition hook, Map<String, Object> payload) {
        // Prompt/Agent hooks require LLM evaluation.
        // For now, record the hook triggered and return ok.
        LOG.info("Prompt-like hook triggered: " + hook.matcher());
        return HookResult.ok("Prompt hook acknowledged");
    }

    private boolean matchesHook(HookDefinition hook, HookEvent event, Map<String, Object> payload) {
        String matcher = hook.matcher();
        if (matcher == null || matcher.isBlank() || "*".equals(matcher)) return true;

        String toolName = (String) payload.getOrDefault("tool_name", "");
        return matchesGlob(matcher, toolName) || matchesGlob(matcher, event.name().toLowerCase());
    }

    private static boolean matchesGlob(String pattern, String text) {
        if (text == null) return false;
        try {
            var matcher = java.nio.file.FileSystems.getDefault()
                    .getPathMatcher("glob:" + pattern);
            return matcher.matches(java.nio.file.Path.of(text));
        } catch (Exception e) {
            return pattern.equals(text);
        }
    }

    public record AggregatedHookResult(List<HookResult> results) {
        public boolean blocked() {
            return results.stream().anyMatch(HookResult::blocked);
        }

        public String reason() {
            return results.stream()
                    .filter(HookResult::blocked)
                    .map(HookResult::message)
                    .collect(Collectors.joining("; "));
        }
    }

    public static class HookBlockedException extends RuntimeException {
        public HookBlockedException(String reason) {
            super("Hook blocked: " + reason);
        }
    }
}
