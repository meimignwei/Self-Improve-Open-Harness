package com.openharness.tools;

import com.openharness.common.ToolResult;
import com.openharness.engine.tool.BaseTool;
import com.openharness.engine.tool.ToolExecutionContext;

import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Searches the web using Brave Search API (when API key configured)
 * or DuckDuckGo HTML search as a free fallback.
 * Java equivalent of Python's WebSearchTool.
 */
public class WebSearchTool extends BaseTool<WebSearchTool.Input> {

    private static final int DEFAULT_MAX_RESULTS = 5;
    private static final String DEFAULT_DUCKDUCKGO_URL = "https://html.duckduckgo.com/html/";
    private static final String DUCKDUCKGO_USER_AGENT = "OpenHarness/0.1";

    private final HttpClient httpClient;

    public WebSearchTool() {
        super("web_search",
                "Search the web and return compact top results with titles, URLs, and snippets.",
                Input.class);
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    @Override
    public ToolResult execute(Input arguments, ToolExecutionContext context) {
        try {
            String apiKey = getApiKey();
            if (apiKey != null && !apiKey.isBlank()) {
                return executeBraveSearch(arguments, apiKey);
            } else {
                return executeDuckDuckGoSearch(arguments);
            }
        } catch (Exception e) {
            return ToolResult.error("web_search failed: " + e.getMessage());
        }
    }

    // --- Brave Search API ---

    private ToolResult executeBraveSearch(Input arguments, String apiKey) throws Exception {
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

        if (response.statusCode() != 200) {
            return ToolResult.error("web_search failed: HTTP " + response.statusCode());
        }

        List<SearchResult> results = parseBraveResults(response.body(), arguments.maxResults());
        if (results.isEmpty()) {
            return ToolResult.error("No search results found.");
        }
        return ToolResult.success(formatResults(arguments.query(), results));
    }

    private List<SearchResult> parseBraveResults(String json, int maxResults) {
        List<SearchResult> results = new ArrayList<>();
        try {
            var mapper = com.openharness.common.OpenHarnessObjectMapper.get();
            var root = mapper.readTree(json);
            var web = root.get("web");
            if (web != null) {
                var items = web.get("results");
                if (items != null && items.isArray()) {
                    for (var item : items) {
                        if (results.size() >= maxResults) break;
                        String title = item.has("title") ? item.get("title").asText() : "";
                        String url = item.has("url") ? item.get("url").asText() : "";
                        String snippet = item.has("description") ? item.get("description").asText() : "";
                        if (!title.isEmpty() && !url.isEmpty()) {
                            results.add(new SearchResult(title, url, snippet));
                        }
                    }
                }
            }
        } catch (Exception e) {
            // Fall back to raw JSON if parsing fails
        }
        return results;
    }

    // --- DuckDuckGo HTML search ---

    private ToolResult executeDuckDuckGoSearch(Input arguments) throws Exception {
        // Resolve endpoint: argument > env var > default
        String endpoint = arguments.searchUrl();
        if (endpoint == null || endpoint.isBlank()) {
            endpoint = System.getenv("OPENHARNESS_WEB_SEARCH_URL");
        }
        if (endpoint == null || endpoint.isBlank()) {
            endpoint = DEFAULT_DUCKDUCKGO_URL;
        }

        // Validate the endpoint URL (SSRF protection)
        String validationError = WebFetchTool.validateUrl(endpoint);
        if (validationError != null) {
            return ToolResult.error("web_search failed: " + validationError);
        }

        String encodedQuery = URLEncoder.encode(arguments.query(), StandardCharsets.UTF_8);
        String fullUrl = endpoint;
        if (!fullUrl.contains("?")) {
            fullUrl += "?q=" + encodedQuery;
        } else {
            fullUrl += "&q=" + encodedQuery;
        }

        URI uri = URI.create(fullUrl);
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(20))
                .header("User-Agent", DUCKDUCKGO_USER_AGENT)
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request,
                HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            return ToolResult.error("web_search failed: HTTP " + response.statusCode());
        }

        List<SearchResult> results = parseDuckDuckGoResults(response.body(), arguments.maxResults());
        if (results.isEmpty()) {
            return ToolResult.error("No search results found.");
        }
        return ToolResult.success(formatResults(arguments.query(), results));
    }

    /**
     * Parse DuckDuckGo HTML results page.
     * Mirrors Python's _parse_search_results function.
     */
    static List<SearchResult> parseDuckDuckGoResults(String html, int limit) {
        // 1. Extract all snippets (class result__snippet or result-snippet)
        Pattern snippetPattern = Pattern.compile(
                "<(?:a|div|span)[^>]+class=\"[^\"]*(?:result__snippet|result-snippet)[^\"]*\"[^>]*>" +
                "(?<snippet>.*?)</(?:a|div|span)>",
                Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
        List<String> snippets = new ArrayList<>();
        Matcher snippetMatcher = snippetPattern.matcher(html);
        while (snippetMatcher.find()) {
            snippets.add(cleanHtml(snippetMatcher.group("snippet")));
        }

        // 2. Parse all <a> tags, filter for result links
        // Uses anchor index (counting ALL anchors) to pair snippets, matching Python's enumerate behavior
        List<SearchResult> results = new ArrayList<>();
        Pattern anchorPattern = Pattern.compile(
                "<a(?<attrs>[^>]+)>(?<title>.*?)</a>",
                Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
        Matcher anchorMatcher = anchorPattern.matcher(html);
        int anchorIndex = 0;

        while (anchorMatcher.find() && results.size() < limit) {
            String attrs = anchorMatcher.group("attrs");

            // Check for result link class (result__a or result-link)
            Matcher classMatcher = Pattern.compile("class=\"(?<class>[^\"]+)\"",
                    Pattern.CASE_INSENSITIVE).matcher(attrs);
            boolean isResultLink = classMatcher.find()
                    && (classMatcher.group("class").contains("result__a")
                        || classMatcher.group("class").contains("result-link"));

            if (isResultLink) {
                // Extract href
                Matcher hrefMatcher = Pattern.compile("href=\"(?<href>[^\"]+)\"",
                        Pattern.CASE_INSENSITIVE).matcher(attrs);
                if (hrefMatcher.find()) {
                    String title = cleanHtml(anchorMatcher.group("title"));
                    String url = normalizeResultUrl(hrefMatcher.group("href"));
                    String snippet = anchorIndex < snippets.size() ? snippets.get(anchorIndex) : "";

                    if (!title.isEmpty() && !url.isEmpty()) {
                        results.add(new SearchResult(title, url, snippet));
                    }
                }
            }
            anchorIndex++;
        }
        return results;
    }

    /**
     * Normalize DuckDuckGo result URLs (extract real URL from /l/ redirect).
     * Mirrors Python's _normalize_result_url.
     */
    static String normalizeResultUrl(String rawUrl) {
        try {
            URI parsed = URI.create(rawUrl);
            String host = parsed.getHost();
            if (host != null && host.endsWith("duckduckgo.com")
                    && parsed.getPath() != null && parsed.getPath().startsWith("/l/")) {
                String query = parsed.getRawQuery();
                if (query != null) {
                    for (String param : query.split("&")) {
                        if (param.startsWith("uddg=")) {
                            return URLDecoder.decode(param.substring(5), StandardCharsets.UTF_8);
                        }
                    }
                }
            }
        } catch (Exception e) {
            // Fall through to return raw URL
        }
        return rawUrl;
    }

    /**
     * Strip HTML tags and decode entities from a fragment.
     * Mirrors Python's _clean_html.
     */
    static String cleanHtml(String fragment) {
        String text = Pattern.compile("<[^>]+>", Pattern.DOTALL)
                .matcher(fragment).replaceAll(" ");
        text = decodeHtmlEntities(text);
        text = text.replaceAll("\\s+", " ").strip();
        return text;
    }

    private static String decodeHtmlEntities(String text) {
        return text
                .replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&#39;", "'")
                .replace("&quot;", "\"")
                .replace("&apos;", "'");
    }

    // --- Shared helpers ---

    @Override
    public boolean isReadOnly(Input arguments) {
        return true;
    }

    private static String getApiKey() {
        // Check order: SEARCH_API_KEY -> BRAVE_API_KEY
        String key = System.getenv("SEARCH_API_KEY");
        if (key != null && !key.isBlank()) return key;
        key = System.getenv("BRAVE_API_KEY");
        if (key != null && !key.isBlank()) return key;
        return System.getProperty("search.api.key");
    }

    /**
     * Format results matching Python's output format:
     *   Search results for: {query}
     *   1. Title
     *      URL: {url}
     *      {snippet}
     */
    private static String formatResults(String query, List<SearchResult> results) {
        StringBuilder sb = new StringBuilder();
        sb.append("Search results for: ").append(query);
        for (int i = 0; i < results.size(); i++) {
            SearchResult r = results.get(i);
            sb.append("\n").append(i + 1).append(". ").append(r.title);
            sb.append("\n   URL: ").append(r.url);
            if (!r.snippet.isEmpty()) {
                sb.append("\n   ").append(r.snippet);
            }
        }
        return sb.toString();
    }

    // --- Types ---

    private record SearchResult(String title, String url, String snippet) {}

    public record Input(String query, int maxResults, String searchUrl) {
        public Input {
            if (query == null || query.isBlank())
                throw new IllegalArgumentException("query is required");
            if (maxResults <= 0) maxResults = DEFAULT_MAX_RESULTS;
            if (maxResults > 10) maxResults = 10;
            // searchUrl is nullable (defaults to null for DuckDuckGo fallback detection)
        }

        /** Backward-compatible constructor without searchUrl. */
        public Input(String query, int maxResults) {
            this(query, maxResults, null);
        }

        /** Backward-compatible constructor with defaults. */
        public Input(String query) {
            this(query, DEFAULT_MAX_RESULTS, null);
        }
    }
}
