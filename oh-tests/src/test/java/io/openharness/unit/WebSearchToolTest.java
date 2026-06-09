package io.openharness.unit;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.openharness.core.tools.WebSearchTool;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WebSearchToolTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private static String textOf(ToolResultBlock r) {
        return ((TextBlock) r.getOutput().get(0)).getText();
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldReturnResultsForValidQuery() throws Exception {
        HttpClient httpClient = mock(HttpClient.class);
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("""
                {"results":[{"title":"Test","url":"https://example.com","content":"Content here"}]}""");
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(response);

        WebSearchTool tool = new WebSearchTool(httpClient, mapper, "test-key");
        ToolResultBlock result = tool.webSearch("test", null);

        assertThat(textOf(result)).contains("Test");
        assertThat(textOf(result)).contains("https://example.com");
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldRetryOn429() throws Exception {
        HttpClient httpClient = mock(HttpClient.class);
        HttpResponse<String> rateLimited = mock(HttpResponse.class);
        when(rateLimited.statusCode()).thenReturn(429);

        HttpResponse<String> success = mock(HttpResponse.class);
        when(success.statusCode()).thenReturn(200);
        when(success.body()).thenReturn("""
                {"results":[{"title":"OK","url":"https://ok.com","content":"it works"}]}""");

        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(rateLimited).thenReturn(success);

        WebSearchTool tool = new WebSearchTool(httpClient, mapper, "test-key");
        ToolResultBlock result = tool.webSearch("retry test", null);

        assertThat(textOf(result)).contains("OK");
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldReturnErrorOnAllRetriesExhausted() throws Exception {
        HttpClient httpClient = mock(HttpClient.class);
        HttpResponse<String> error = mock(HttpResponse.class);
        when(error.statusCode()).thenReturn(503);
        when(error.body()).thenReturn("Service Unavailable");
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(error);

        WebSearchTool tool = new WebSearchTool(httpClient, mapper, "test-key");
        ToolResultBlock result = tool.webSearch("fail test", null);

        assertThat(textOf(result)).contains("failed");
    }

    @Test
    void shouldFailGracefullyWhenNoApiKey() {
        HttpClient httpClient = mock(HttpClient.class);
        WebSearchTool tool = new WebSearchTool(httpClient, mapper, null);
        ToolResultBlock result = tool.webSearch("test", null);

        assertThat(textOf(result)).contains("TAVILY_API_KEY");
    }
}
