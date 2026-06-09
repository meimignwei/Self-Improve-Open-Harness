package io.openharness.core.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.core.tool.ToolResultBlock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public class WebSearchTool {

    private static final Logger log = LoggerFactory.getLogger(WebSearchTool.class);
    private static final String TAVILY_URL = "https://api.tavily.com/search";
    private static final int MAX_RETRIES = 3;
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private final HttpClient httpClient;
    private final ObjectMapper mapper;
    private final String apiKey;

    public WebSearchTool() {
        this.httpClient = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
        this.mapper = new ObjectMapper();
        this.apiKey = resolveApiKey();
    }

    public WebSearchTool(HttpClient httpClient, ObjectMapper mapper, String apiKey) {
        this.httpClient = httpClient;
        this.mapper = mapper;
        this.apiKey = apiKey;
    }

    @Tool(name = "web_search", description = "Search the web using Tavily Search API")
    public ToolResultBlock webSearch(
            @ToolParam(name = "query", description = "Search query") String query,
            @ToolParam(name = "allowed_domains", required = false) List<String> allowedDomains) {

        if (apiKey == null || apiKey.isBlank()) {
            return ToolResultBlock.error("TAVILY_API_KEY not configured");
        }

        Map<String, Object> body = buildRequestBody(query, allowedDomains);

        try {
            String json = mapper.writeValueAsString(body);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(TAVILY_URL))
                    .header("Content-Type", "application/json")
                    .timeout(TIMEOUT)
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();

            HttpResponse<String> response = executeWithRetry(request);
            return formatResponse(query, response);
        } catch (IOException e) {
            log.error("WebSearch failed: {}", e.getMessage());
            return ToolResultBlock.error("Search failed: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ToolResultBlock.error("Search interrupted");
        }
    }

    private Map<String, Object> buildRequestBody(String query, List<String> allowedDomains) {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("query", query);
        body.put("api_key", apiKey);
        body.put("search_depth", "basic");
        body.put("max_results", 5);
        if (allowedDomains != null && !allowedDomains.isEmpty()) {
            body.put("include_domains", allowedDomains);
        }
        return body;
    }

    private HttpResponse<String> executeWithRetry(HttpRequest request) throws IOException, InterruptedException {
        long backoff = 1000;
        for (int attempt = 0; attempt < MAX_RETRIES; attempt++) {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();

            if (status == 200) {
                return response;
            }

            if (status == 429) {
                log.warn("WebSearch rate limited (429), attempt {}/{}", attempt + 1, MAX_RETRIES);
                Thread.sleep(backoff);
                backoff *= 2;
                continue;
            }

            if (status >= 500) {
                log.warn("WebSearch server error ({}), attempt {}/{}", status, attempt + 1, MAX_RETRIES);
                if (attempt < MAX_RETRIES - 1) {
                    Thread.sleep(backoff);
                    backoff *= 2;
                }
                continue;
            }

            return response;
        }

        throw new IOException("Search failed after " + MAX_RETRIES + " retries");
    }

    private ToolResultBlock formatResponse(String query, HttpResponse<String> response) {
        int status = response.statusCode();
        if (status != 200) {
            return ToolResultBlock.error("HTTP " + status + ": " + response.body());
        }

        try {
            JsonNode root = mapper.readTree(response.body());
            JsonNode results = root.get("results");
            if (results == null || !results.isArray() || results.isEmpty()) {
                return ToolResultBlock.success("No results found for: \"" + query + "\"");
            }

            StringBuilder sb = new StringBuilder("## Web Search Results for: \"")
                    .append(query).append("\"\n\n");

            int idx = 1;
            for (JsonNode r : results) {
                String title = r.has("title") ? r.get("title").asText() : "";
                String url = r.has("url") ? r.get("url").asText() : "";
                String content = r.has("content") ? r.get("content").asText() : "";
                sb.append(idx++).append(". [").append(title).append("](").append(url).append(")\n");
                sb.append("   ").append(content).append("\n\n");
            }

            Map<String, Object> metadata = Collections.singletonMap("resultCount", results.size());
            return ToolResultBlock.success(sb.toString(), metadata);
        } catch (Exception e) {
            log.error("Failed to parse search response: {}", e.getMessage());
            return ToolResultBlock.error("Failed to parse search response");
        }
    }

    private String resolveApiKey() {
        String key = System.getenv("TAVILY_API_KEY");
        if (key == null || key.isBlank()) {
            log.warn("TAVILY_API_KEY not set; web search will fail at runtime");
        }
        return key;
    }
}
