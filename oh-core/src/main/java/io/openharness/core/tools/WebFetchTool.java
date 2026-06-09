package io.openharness.core.tools;

import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

public class WebFetchTool {

    private static final Logger log = LoggerFactory.getLogger(WebFetchTool.class);
    private static final int MAX_CONTENT_LENGTH = 100_000;
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private final HttpClient httpClient;

    public WebFetchTool() {
        this.httpClient = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(TIMEOUT)
                .build();
    }

    public WebFetchTool(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    @Tool(name = "web_fetch", description = "Fetch a URL and process its content with a prompt")
    public ToolResultBlock webFetch(
            @ToolParam(name = "url", description = "URL to fetch") String url,
            @ToolParam(name = "prompt", description = "What information to extract from the page") String prompt) {

        if (url == null || !url.startsWith("http")) {
            return ToolResultBlock.error("Invalid URL: must start with http:// or https://");
        }

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("User-Agent", "OpenHarness/1.0")
                    .timeout(TIMEOUT)
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return processResponse(url, prompt, response);
        } catch (HttpTimeoutException e) {
            log.warn("Request timed out: {}", url);
            return ToolResultBlock.error("Request timed out: " + url);
        } catch (IOException e) {
            log.error("Failed to fetch {}: {}", url, e.getMessage());
            return ToolResultBlock.error("Failed to fetch URL: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ToolResultBlock.error("Fetch interrupted");
        }
    }

    private ToolResultBlock processResponse(String url, String prompt, HttpResponse<String> response) {
        int status = response.statusCode();
        if (status < 200 || status >= 300) {
            return ToolResultBlock.error("HTTP " + status + " when fetching: " + url);
        }

        String contentType = response.headers().firstValue("content-type").orElse("");
        String text;
        boolean truncated = false;

        if (contentType.contains("text/html")) {
            Document doc = Jsoup.parse(response.body());
            text = doc.text();
        } else {
            text = response.body();
        }

        if (text.length() > MAX_CONTENT_LENGTH) {
            text = text.substring(0, MAX_CONTENT_LENGTH) + "\n[...content truncated]";
            truncated = true;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("## Fetched: ").append(url).append("\n\n");
        sb.append("## Content:\n").append(text).append("\n\n");
        if (prompt != null && !prompt.isBlank()) {
            sb.append("## Extraction Prompt: ").append(prompt).append("\n");
        }

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("url", url);
        metadata.put("contentLength", text.length());
        metadata.put("truncated", truncated);

        return ToolResultBlock.of(TextBlock.builder().text(sb.toString()).build(), metadata);
    }
}
