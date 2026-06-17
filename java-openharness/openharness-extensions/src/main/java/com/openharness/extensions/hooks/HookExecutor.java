package com.openharness.extensions.hooks;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openharness.common.AgentRuntime;
import com.openharness.common.ConversationMessage;
import com.openharness.common.OpenHarnessObjectMapper;
import com.openharness.common.QueryOptions;
import com.openharness.common.StreamEvent;

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
import java.util.concurrent.Flow;
import java.util.logging.Logger;

/**
 * Main hook execution engine. Dispatches hooks by type: command, HTTP, prompt, agent.
 * Java equivalent of Python's HookExecutor.
 */
public class HookExecutor {

    private static final Logger LOG = Logger.getLogger(HookExecutor.class.getName());

    private HookRegistry registry;
    private HookExecutionContext context;
    private final HttpClient httpClient;
    private final ObjectMapper mapper;

    public HookExecutor(HookRegistry registry, Path cwd) {
        this.registry = registry;
        this.context = new HookExecutionContext(cwd, null, "claude-sonnet-4-6");
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.mapper = OpenHarnessObjectMapper.get();
    }

    public HookExecutor(HookRegistry registry, HookExecutionContext context) {
        this.registry = registry;
        this.context = context;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.mapper = OpenHarnessObjectMapper.get();
    }

    public void updateRegistry(HookRegistry registry) {
        this.registry = registry;
    }

    public void updateContext(HookExecutionContext context) {
        this.context = context;
    }

    public void updateApiClient(AgentRuntime apiClient) {
        this.context.setApiClient(apiClient);
    }

    public void updateDefaultModel(String defaultModel) {
        this.context.setDefaultModel(defaultModel);
    }

    /**
     * Execute all hooks registered for the given event.
     */
    public AggregatedHookResult execute(HookEvent event, Map<String, Object> payload) {
        List<HookDefinition> hooks = registry.get(event);
        List<HookResult> results = new ArrayList<>();

        for (HookDefinition hook : hooks) {
            if (!matchesHook(hook, event, payload)) continue;

            HookResult result = switch (hook) {
                case HookDefinition.CommandHook ch -> runCommandHook(ch, event, payload);
                case HookDefinition.HttpHook hh -> runHttpHook(hh, event, payload);
                case HookDefinition.PromptHook ph -> runPromptLike(ph, event, payload);
                case HookDefinition.AgentHook ah -> runAgentHook(ah, event, payload);
            };

            results.add(result);
            LOG.fine(() -> "Hook [" + hook.getClass().getSimpleName() + "] result: " + result);

            if (!result.success() && hook.blockOnFailure()) break;
        }

        return new AggregatedHookResult(results);
    }

    public void executeOrThrow(HookEvent event, Map<String, Object> payload) {
        AggregatedHookResult result = execute(event, payload);
        if (result.blocked()) {
            throw new HookBlockedException(result.reason());
        }
    }

    private HookResult runCommandHook(HookDefinition.CommandHook hook, HookEvent event, Map<String, Object> payload) {
        try {
            String jsonPayload = mapper.writeValueAsString(payload);
            String command = injectArguments(hook.command(), jsonPayload);

            // Sandbox-aware execution (matches Python's create_shell_subprocess)
            if (context.sandboxManager() != null && context.sandboxManager().isAvailable()) {
                var sandboxSettings = context.sandboxManager().settingsFor("hook");
                var srtResult = context.sandboxManager().srt()
                        .execute(command, sandboxSettings, context.cwd(),
                                hook.timeoutSeconds() * 1000L);
                String output = srtResult.combinedOutput();
                boolean success = srtResult.success();
                return new HookResult("command", success,
                        hook.blockOnFailure() && !success,
                        output, output,
                        Map.of("returncode", srtResult.exitCode(), "sandbox", true));
            }

            ProcessBuilder pb = new ProcessBuilder(command.split("\\s+"));
            pb.directory(context.cwd().toFile());
            pb.environment().put("OPENHARNESS_HOOK_EVENT", event.name().toLowerCase());
            pb.environment().put("OPENHARNESS_HOOK_PAYLOAD", jsonPayload);
            pb.redirectErrorStream(true);

            Process process = pb.start();
            boolean finished = process.waitFor(hook.timeoutSeconds(), java.util.concurrent.TimeUnit.SECONDS);

            if (!finished) {
                process.destroyForcibly();
                return new HookResult("command", false, hook.blockOnFailure(),
                        "Command timed out after " + hook.timeoutSeconds() + "s",
                        "Command timed out after " + hook.timeoutSeconds() + "s",
                        Map.of());
            }

            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            }

            int exitCode = process.exitValue();
            boolean success = exitCode == 0;
            String out = output.toString().stripTrailing();
            return new HookResult("command", success,
                    hook.blockOnFailure() && !success,
                    out, out, Map.of("returncode", exitCode));

        } catch (Exception e) {
            return new HookResult("command", false, hook.blockOnFailure(),
                    e.getMessage(), e.getMessage(), Map.of());
        }
    }

    private HookResult runHttpHook(HookDefinition.HttpHook hook, HookEvent event, Map<String, Object> payload) {
        try {
            Map<String, Object> wrapped = Map.of("event", event.name().toLowerCase(), "payload", payload);
            String json = mapper.writeValueAsString(wrapped);

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

            int status = response.statusCode();
            String body = response.body();
            if (status >= 200 && status < 300) {
                return new HookResult("http", true, false, body, body, Map.of("status_code", status));
            } else {
                return new HookResult("http", false, hook.blockOnFailure(),
                        body, "HTTP " + status + ": " + body, Map.of("status_code", status));
            }

        } catch (Exception e) {
            return new HookResult("http", false, hook.blockOnFailure(),
                    e.getMessage(), e.getMessage(), Map.of());
        }
    }

    private HookResult runPromptLike(HookDefinition.PromptHook hook, HookEvent event, Map<String, Object> payload) {
        return runLlmHook("prompt", hook.prompt(), hook.model(), hook.matcher(),
                hook.blockOnFailure(), event, payload);
    }

    private HookResult runAgentHook(HookDefinition.AgentHook hook, HookEvent event, Map<String, Object> payload) {
        return runLlmHook("agent", hook.prompt(), hook.model(), hook.matcher(),
                hook.blockOnFailure(), event, payload);
    }

    /**
     * Execute an LLM-based hook (prompt or agent).
     * Falls back to a stub acknowledgement if no API client is available.
     */
    private HookResult runLlmHook(String hookType, String promptTemplate, String model,
                                   String matcher, boolean blockOnFailure,
                                   HookEvent event, Map<String, Object> payload) {
        try {
            String jsonPayload = mapper.writeValueAsString(payload);
            String prompt = injectArguments(promptTemplate, jsonPayload);

            if (context.apiClient() == null) {
                LOG.warning("No API client available for " + hookType
                        + " hook; returning stub. matcher=" + matcher);
                return new HookResult(hookType, true, false,
                        "Hook acknowledged (no API client)", "", Map.of());
            }

            String systemPrompt = "You are validating whether a hook condition passes in OpenHarness. "
                    + "Return strict JSON: {\"ok\": true} or {\"ok\": false, \"reason\": \"...\"}.";
            if ("agent".equals(hookType)) {
                systemPrompt += " Be more thorough and reason over the payload before deciding.";
            }

            String effectiveModel = model != null ? model : context.defaultModel();
            QueryOptions options = QueryOptions.defaults()
                    .withModel(effectiveModel)
                    .withSystemPrompt(systemPrompt)
                    .withMaxTurns(1);

            List<ConversationMessage> messages = List.of(ConversationMessage.fromUserText(prompt));
            Flow.Publisher<StreamEvent> publisher = context.apiClient().runQuery(messages, options);

            StringBuilder text = new StringBuilder();
            CompletableSubscriber subscriber = new CompletableSubscriber();
            publisher.subscribe(subscriber);
            List<StreamEvent> events = subscriber.await();

            for (StreamEvent eventItem : events) {
                if (eventItem instanceof StreamEvent.AssistantTextDelta delta) {
                    text.append(delta.text());
                }
            }

            Map<String, Object> parsed = parseHookJson(text.toString());
            boolean ok = Boolean.TRUE.equals(parsed.get("ok"));
            String reason = (String) parsed.getOrDefault("reason", "");
            String output = !text.isEmpty() ? text.toString() : "Hook returned empty response";

            return new HookResult(hookType, ok, !ok && blockOnFailure,
                    output, !ok ? (!reason.isEmpty() ? reason : output) : "" , Map.of());

        } catch (Exception e) {
            return new HookResult(hookType, false, blockOnFailure,
                    "Hook failed: " + e.getMessage(),
                    "Hook failed: " + e.getMessage(), Map.of());
        }
    }

    private boolean matchesHook(HookDefinition hook, HookEvent event, Map<String, Object> payload) {
        String matcher = hook.matcher();
        if (matcher == null || matcher.isBlank() || "*".equals(matcher)) return true;

        String subject = (String) payload.getOrDefault("tool_name",
                (String) payload.getOrDefault("prompt",
                        (String) payload.getOrDefault("event", "")));
        return matchesGlob(matcher, subject) || matchesGlob(matcher, event.name().toLowerCase());
    }

    /**
     * Python fnmatch-compatible glob matching.
     */
    private static boolean matchesGlob(String pattern, String text) {
        if (text == null) return false;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            switch (c) {
                case '*': sb.append(".*"); break;
                case '?': sb.append("[^/]"); break;
                case '.': case '+': case '(': case ')': case '{': case '}':
                case '^': case '$': case '|': case '\\':
                    sb.append('\\').append(c); break;
                default: sb.append(c);
            }
        }
        try {
            return text.matches(sb.toString());
        } catch (Exception e) {
            return pattern.equals(text);
        }
    }

    static String injectArguments(String template, String serializedPayload) {
        return template.replace("$ARGUMENTS", serializedPayload);
    }

    static Map<String, Object> parseHookJson(String text) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = new ObjectMapper().readValue(text, Map.class);
            if (parsed != null && parsed.get("ok") instanceof Boolean) {
                return parsed;
            }
        } catch (Exception e) {
            // fall through
        }
        String lowered = text.strip().toLowerCase();
        if (lowered.equals("ok") || lowered.equals("true") || lowered.equals("yes")) {
            return Map.of("ok", true);
        }
        return Map.of("ok", false, "reason", !text.strip().isEmpty() ? text.strip() : "hook returned invalid JSON");
    }

    /**
     * Simple subscriber that collects all StreamEvents.
     */
    static class CompletableSubscriber implements Flow.Subscriber<StreamEvent> {
        private final List<StreamEvent> events = new ArrayList<>();
        private volatile boolean done;
        private Flow.Subscription subscription;

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            subscription.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(StreamEvent item) {
            events.add(item);
        }

        @Override
        public void onError(Throwable throwable) {
            done = true;
        }

        @Override
        public void onComplete() {
            done = true;
        }

        List<StreamEvent> await() {
            while (!done) {
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            return events;
        }
    }

    public static class HookBlockedException extends RuntimeException {
        public HookBlockedException(String reason) {
            super("Hook blocked: " + reason);
        }
    }
}
