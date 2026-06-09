package io.openharness.core.middleware;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.middleware.MiddlewareBase;
import io.openharness.core.config.Settings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

public class SystemPromptAssembler implements MiddlewareBase {

    private static final Logger log = LoggerFactory.getLogger(SystemPromptAssembler.class);

    private final Path workspaceRoot;
    private final Settings settings;

    public SystemPromptAssembler(Path workspaceRoot, Settings settings) {
        this.workspaceRoot = workspaceRoot;
        this.settings = settings;
    }

    @Override
    public Mono<String> onSystemPrompt(Agent agent, RuntimeContext ctx, String sysPrompt) {
        StringBuilder sb = new StringBuilder();
        if (sysPrompt != null && !sysPrompt.isBlank()) {
            sb.append(sysPrompt).append('\n');
        }

        appendAgentsMd(sb);
        appendMemoryMd(sb);
        appendSkills(sb);
        appendPermissions(sb);

        String assembled = sb.toString();
        log.debug("SystemPromptAssembler: assembled prompt of {} bytes, sessionId={}",
                assembled.length(), ctx.getSessionId());

        return Mono.just(assembled);
    }

    private void appendAgentsMd(StringBuilder sb) {
        Path path = workspaceRoot.resolve("AGENTS.md");
        String content = readFileIfExists(path);
        if (content != null) {
            sb.append("## Workspace Instructions (AGENTS.md)\n\n");
            sb.append(content).append('\n');
        } else {
            log.debug("AGENTS.md not found at {}, skipping", path);
        }
    }

    private void appendMemoryMd(StringBuilder sb) {
        Path path = workspaceRoot.resolve("MEMORY.md");
        String content = readFileIfExists(path);
        if (content == null) {
            log.debug("MEMORY.md not found at {}, skipping", path);
            return;
        }

        int maxTokens = settings.getMaxContextTokens() > 0
                ? settings.getMaxContextTokens()
                : Settings.defaults().getMaxContextTokens();
        int byteBudget = maxTokens * 2;

        if (content.getBytes(StandardCharsets.UTF_8).length > byteBudget) {
            String truncated = truncateToByteBudget(content, byteBudget);
            log.info("MEMORY.md truncated from {} to {} bytes (token budget: {})",
                    content.length(), truncated.length(), maxTokens);
            sb.append("## Memory Context (MEMORY.md)\n\n");
            sb.append(truncated).append('\n');
            sb.append("[...truncated to fit token budget]\n\n");
        } else {
            sb.append("## Memory Context (MEMORY.md)\n\n");
            sb.append(content).append('\n');
        }
    }

    private void appendSkills(StringBuilder sb) {
        Path skillsDir = workspaceRoot.resolve(".claude/skills");
        if (!Files.isDirectory(skillsDir)) {
            log.debug("Skills directory not found at {}, skipping", skillsDir);
            return;
        }

        List<String> skillEntries = new ArrayList<>();
        try (Stream<Path> dirs = Files.list(skillsDir)) {
            dirs.filter(Files::isDirectory).forEach(dir -> {
                Path skillMd = dir.resolve("SKILL.md");
                if (Files.isRegularFile(skillMd)) {
                    try {
                        String firstLine = Files.lines(skillMd).findFirst().orElse(dir.getFileName().toString());
                        skillEntries.add("- " + dir.getFileName() + ": " + firstLine);
                    } catch (IOException e) {
                        skillEntries.add("- " + dir.getFileName());
                    }
                }
            });
        } catch (IOException e) {
            log.warn("Failed to list skills directory: {}", e.getMessage());
            return;
        }

        if (!skillEntries.isEmpty()) {
            sb.append("## Available Skills\n\n");
            skillEntries.forEach(s -> sb.append(s).append('\n'));
            sb.append('\n');
        }
    }

    private void appendPermissions(StringBuilder sb) {
        List<String> allowedPaths = settings.getAllowedPaths();
        if (allowedPaths != null && !allowedPaths.isEmpty()) {
            sb.append("## Permission Policy\n\nAllowed paths:\n");
            allowedPaths.forEach(p -> sb.append("- ").append(p).append('\n'));
            sb.append('\n');
        }
    }

    private String readFileIfExists(Path path) {
        try {
            if (Files.isRegularFile(path)) {
                return Files.readString(path);
            }
        } catch (IOException e) {
            log.warn("Failed to read {}: {}", path, e.getMessage());
        }
        return null;
    }

    static String truncateToByteBudget(String text, int byteBudget) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= byteBudget) {
            return text;
        }
        int cutPoint = byteBudget;
        while (cutPoint > 0 && (bytes[cutPoint] & 0xC0) == 0x80) {
            cutPoint--;
        }
        return new String(bytes, 0, cutPoint, StandardCharsets.UTF_8);
    }
}
