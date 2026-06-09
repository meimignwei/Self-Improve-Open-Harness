package io.openharness.unit;

import io.agentscope.core.tool.ToolResultBlock;
import io.openharness.core.tools.WebFetchTool;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WebFetchToolTest {

    @Test
    @SuppressWarnings("unchecked")
    void shouldFetchAndExtractContent() throws Exception {
        HttpClient httpClient = mock(HttpClient.class);
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.headers()).thenReturn(htmlHeaders());
        when(response.body()).thenReturn("""
                <html><body><p>Hello World</p><p>Second paragraph</p></body></html>""");
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(response);

        WebFetchTool tool = new WebFetchTool(httpClient);
        ToolResultBlock result = tool.webFetch("https://example.com", "extract text");

        assertThat(result.isError()).isFalse();
        assertThat(result.content()).contains("Hello World");
        assertThat(result.content()).contains("Second paragraph");
        assertThat(result.content()).contains("https://example.com");
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldTruncateLongContent() throws Exception {
        StringBuilder body = new StringBuilder();
        body.append("<html><body>");
        for (int i = 0; i < 200000; i++) {
            body.append('x');
        }
        body.append("</body></html>");

        HttpClient httpClient = mock(HttpClient.class);
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.headers()).thenReturn(htmlHeaders());
        when(response.body()).thenReturn(body.toString());
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(response);

        WebFetchTool tool = new WebFetchTool(httpClient);
        ToolResultBlock result = tool.webFetch("https://example.com", "extract");

        assertThat(result.isError()).isFalse();
        assertThat(result.content()).contains("truncated");
        assertThat(result.metadata().get("truncated")).isEqualTo(true);
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldHandleTimeout() throws Exception {
        HttpClient httpClient = mock(HttpClient.class);
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenThrow(new HttpTimeoutException("timeout"));

        WebFetchTool tool = new WebFetchTool(httpClient);
        ToolResultBlock result = tool.webFetch("https://example.com", "extract");

        assertThat(result.isError()).isTrue();
        assertThat(result.content()).contains("timed out");
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldHandleHttpError() throws Exception {
        HttpClient httpClient = mock(HttpClient.class);
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(404);
        when(response.headers()).thenReturn(htmlHeaders());
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(response);

        WebFetchTool tool = new WebFetchTool(httpClient);
        ToolResultBlock result = tool.webFetch("https://example.com/notfound", "extract");

        assertThat(result.isError()).isTrue();
        assertThat(result.content()).contains("404");
    }

    private HttpHeaders htmlHeaders() {
        return HttpHeaders.of(
                Map.of("content-type", List.of("text/html")),
                (k, v) -> true);
    }
}
