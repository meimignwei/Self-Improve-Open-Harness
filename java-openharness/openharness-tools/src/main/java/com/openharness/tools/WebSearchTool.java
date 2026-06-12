package com.openharness.tools;

import com.openharness.common.ToolResult;
import com.openharness.engine.tool.BaseTool;
import com.openharness.engine.tool.ToolExecutionContext;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Searches the web using the Brave Search API (free tier).
 * Java equivalent of Python's WebSearchTool.
 */
public class WebSearchTool extends BaseTool<WebSearchTool.Input> {

    private final HttpClient httpClient;

    public WebSearchTool() {
        super("web_search", "Searches the web and returns results.", Input.class);
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    @Override
    public ToolResult execute(Input arguments, ToolExecutionContext context) {
        try {
            String apiKey = getApiKey();
            if (apiKey == null || apiKey.isBlank()) {
                return ToolResult.error("No search API key configured. "
                        + "Set the BRAVE_API_KEY or SEARCH_API_KEY environment variable.");
            }

            String encodedQuery = URLEncoder.encode(arguments.query(), StandardCharsets.UTF_8);
            URI uri = URI.create("https://api.search.brave.com/res/v1/web/search?q="
                    + encodedQuery + "&count=" + Math.min(arguments.maxResults(), 20));

            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(15))
                    .header("Accept", "application/json")
                    .header("X-Subscription-Token", apiKey)
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                return ToolResult.success(formatResults(response.body(), arguments.maxResults()));
            } else {
                return ToolResult.error("Search failed: HTTP " + response.statusCode());
            }
        } catch (Exception e) {
            return ToolResult.error("Search failed: " + e.getMessage());
        }
    }

    @Override
    public boolean isReadOnly(Input arguments) {
        return true;
    }

    private static String getApiKey() {
        String key = System.getenv("BRAVE_API_KEY");
        if (key != null && !key.isBlank()) return key;
        key = System.getenv("SEARCH_API_KEY");
        if (key != null && !key.isBlank()) return key;
        return System.getProperty("search.api.key");
    }

    private static String formatResults(String json, int maxResults) {
        // Simple extraction of title, url, description from Brave Search response.
        // For a full implementation, use Jackson parsing.
        StringBuilder sb = new StringBuilder();
        try {
            var mapper = com.openharness.common.OpenHarnessObjectMapper.get();
            var root = mapper.readTree(json);
            var web = root.get("web");
            if (web != null) {
                var results = web.get("results");
                if (results != null && results.isArray()) {
                    int count = 0;
                    for (var item : results) {
                        if (count >= maxResults) break;
                        String title = item.has("title") ? item.get("title").asText() : "";
                        String url = item.has("url") ? item.get("url").asText() : "";
                        String desc = item.has("description") ? item.get("description").asText() : "";
                        sb.append("[").append(count + 1).append("] ").append(title).append("\n");
                        sb.append("    ").append(url).append("\n");
                        sb.append("    ").append(desc).append("\n\n");
                        count++;
                    }
                }
            }
        } catch (Exception e) {
            return json; // fallback: return raw JSON
        }
        return sb.isEmpty() ? "No results found." : sb.toString().stripTrailing();
    }

    public record Input(String query, int maxResults) {
        public Input {
            if (query == null || query.isBlank())
                throw new IllegalArgumentException("query is required");
            if (maxResults <= 0) maxResults = 10;
        }
    }
}
