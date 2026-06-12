package com.openharness.tools;

import com.openharness.common.ToolResult;
import com.openharness.engine.tool.BaseTool;
import com.openharness.engine.tool.ToolExecutionContext;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Content search tool using ripgrep.
 * Java equivalent of Python's GrepTool.
 */
public class GrepTool extends BaseTool<GrepTool.Input> {

    private static final int DEFAULT_MAX_RESULTS = 250;

    public GrepTool() {
        super("grep", "Searches file contents using regex patterns (ripgrep).", Input.class);
    }

    @Override
    public ToolResult execute(Input arguments, ToolExecutionContext context) {
        Path searchPath = arguments.path() != null
                ? context.cwd().resolve(arguments.path()).normalize()
                : context.cwd();

        List<String> cmd = new ArrayList<>();
        cmd.add("rg");

        if (arguments.caseInsensitive()) cmd.add("-i");
        if (arguments.fixedStrings()) cmd.add("-F");

        cmd.add("--line-number");
        cmd.add("--max-count");
        cmd.add(String.valueOf(arguments.maxResults() > 0 ? arguments.maxResults() : DEFAULT_MAX_RESULTS));
        cmd.add("--");

        cmd.add(arguments.pattern());
        cmd.add(searchPath.toString());

        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(context.cwd().toFile());
            pb.redirectErrorStream(true);

            Process process = pb.start();
            boolean finished = process.waitFor(30, TimeUnit.SECONDS);

            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                int lineCount = 0;
                while ((line = reader.readLine()) != null && lineCount < arguments.maxResults()) {
                    output.append(line).append("\n");
                    lineCount++;
                }
            }

            if (!finished) {
                process.destroyForcibly();
            }

            String result = output.toString();
            if (result.isEmpty()) {
                return ToolResult.success("No matches found for pattern: " + arguments.pattern());
            }
            return ToolResult.success(result);

        } catch (Exception e) {
            return ToolResult.error("grep failed: " + e.getMessage());
        }
    }

    @Override
    public boolean isReadOnly(Input arguments) {
        return true;
    }

    public record Input(
            String pattern,
            String path,
            boolean caseInsensitive,
            boolean fixedStrings,
            int maxResults) {

        public Input {
            if (pattern == null) throw new IllegalArgumentException("pattern is required");
            if (maxResults <= 0) maxResults = DEFAULT_MAX_RESULTS;
        }
    }
}
