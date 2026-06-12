package com.openharness.tools;

import com.openharness.common.ToolResult;
import com.openharness.engine.tool.BaseTool;
import com.openharness.engine.tool.ToolExecutionContext;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * File pattern matching tool using glob patterns.
 * Java equivalent of Python's GlobTool.
 */
public class GlobTool extends BaseTool<GlobTool.Input> {

    private static final int DEFAULT_MAX_RESULTS = 500;

    public GlobTool() {
        super("glob", "Finds files matching a glob pattern.", Input.class);
    }

    @Override
    public ToolResult execute(Input arguments, ToolExecutionContext context) {
        Path searchPath = arguments.path() != null
                ? context.cwd().resolve(arguments.path()).normalize()
                : context.cwd();

        String pattern = "glob:" + arguments.pattern();
        PathMatcher matcher = FileSystems.getDefault().getPathMatcher(pattern);

        try (Stream<Path> fileStream = Files.walk(searchPath, arguments.maxDepth())) {
            String result = fileStream
                    .filter(Files::isRegularFile)
                    .filter(p -> matcher.matches(p.getFileName()) || matcher.matches(p))
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .limit(arguments.maxResults() > 0 ? arguments.maxResults() : DEFAULT_MAX_RESULTS)
                    .map(p -> searchPath.relativize(p).toString())
                    .collect(StringBuilder::new,
                            (sb, s) -> sb.append(s).append("\n"),
                            StringBuilder::append)
                    .toString();

            return ToolResult.success(result.isEmpty()
                    ? "No files matched pattern: " + arguments.pattern()
                    : result);

        } catch (IOException e) {
            return ToolResult.error("glob failed: " + e.getMessage());
        }
    }

    @Override
    public boolean isReadOnly(Input arguments) {
        return true;
    }

    public record Input(String pattern, String path, int maxDepth, int maxResults) {
        public Input {
            if (pattern == null) throw new IllegalArgumentException("pattern is required");
            if (maxDepth <= 0) maxDepth = 10;
            if (maxResults <= 0) maxResults = DEFAULT_MAX_RESULTS;
        }
    }
}
