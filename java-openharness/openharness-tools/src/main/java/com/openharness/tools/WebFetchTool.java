package com.openharness.tools;

import com.openharness.common.ToolResult;
import com.openharness.engine.tool.BaseTool;
import com.openharness.engine.tool.ToolExecutionContext;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Fetches content from a URL. Uses the Java 11 HttpClient.
 * Java equivalent of Python's WebFetchTool.
 */
public class WebFetchTool extends BaseTool<WebFetchTool.Input> {

    private final HttpClient httpClient;

    public WebFetchTool() {
        super("web_fetch", "Fetches content from a URL and processes it.", Input.class);
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    @Override
    public ToolResult execute(Input arguments, ToolExecutionContext context) {
        try {
            URI uri = URI.create(arguments.url());
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(30))
                    .header("User-Agent", "OpenHarness/0.1")
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                String body = response.body();
                // Truncate large responses
                if (body.length() > 100_000) {
                    body = body.substring(0, 100_000) + "\n... (truncated)";
                }
                return ToolResult.success(body);
            } else {
                return ToolResult.error("HTTP " + response.statusCode() + ": " + response.body());
            }
        } catch (Exception e) {
            return ToolResult.error("Failed to fetch URL: " + e.getMessage());
        }
    }

    @Override
    public boolean isReadOnly(Input arguments) {
        return true;
    }

    public record Input(String url) {
        public Input {
            if (url == null || url.isBlank())
                throw new IllegalArgumentException("url is required");
        }
    }
}
