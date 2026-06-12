package com.openharness.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.openharness.common.OpenHarnessObjectMapper;
import com.openharness.common.ToolResult;
import com.openharness.engine.tool.BaseTool;
import com.openharness.engine.tool.ToolExecutionContext;
import okhttp3.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * Generate images using a configurable image generation provider (OpenAI DALL-E compatible).
 */
public class ImageGenerationTool extends BaseTool<ImageGenerationTool.Input> {

    private static final Logger LOG = Logger.getLogger(ImageGenerationTool.class.getName());
    private static final ObjectMapper MAPPER = OpenHarnessObjectMapper.get();
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    public ImageGenerationTool() {
        super("image_generation", "Generate images from a text prompt using an AI image model (e.g. DALL-E).", Input.class);
    }

    @Override
    public ToolResult execute(Input arguments, ToolExecutionContext context) {
        String model = arguments.model() != null ? arguments.model() : "dall-e-3";
        String apiKey = arguments.apiKey() != null ? arguments.apiKey() : "";
        String baseUrl = arguments.baseUrl() != null ? arguments.baseUrl() : "https://api.openai.com/v1";

        if (apiKey.isBlank()) {
            return ToolResult.error("Image generation API key is not configured. Pass api_key or set it in settings.");
        }

        Path outputPath = resolveOutputPath(arguments, context);

        try {
            String b64 = callImageApi(arguments.prompt(), model, apiKey, baseUrl, arguments.size(), arguments.quality(), arguments.n());
            byte[] imageBytes = Base64.getDecoder().decode(b64);
            Files.createDirectories(outputPath.getParent());
            Files.write(outputPath, imageBytes);
            return ToolResult.success("Generated image saved to: " + outputPath);
        } catch (Exception e) {
            LOG.warning("image_generation failed: " + e.getMessage());
            return ToolResult.error("Image generation failed: " + e.getMessage());
        }
    }

    private Path resolveOutputPath(Input arguments, ToolExecutionContext context) {
        if (arguments.outputPath() != null) {
            return context.cwd().resolve(arguments.outputPath()).normalize();
        }
        Path dir = context.cwd().resolve(arguments.outputDir() != null ? arguments.outputDir() : "generated_images");
        String ext = arguments.outputFormat() != null ? arguments.outputFormat() : "png";
        return dir.resolve("generated_" + System.currentTimeMillis() + "." + ext);
    }

    private String callImageApi(String prompt, String model, String apiKey, String baseUrl,
                                 String size, String quality, int n) throws IOException {
        OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(2, TimeUnit.MINUTES)
                .build();

        String url = baseUrl.replaceAll("/+$", "") + "/images/generations";

        ObjectNode body = MAPPER.createObjectNode();
        body.put("model", model);
        body.put("prompt", prompt);
        body.put("n", Math.clamp(n, 1, 4));
        body.put("size", size != null ? size : "1024x1024");
        body.put("quality", quality != null ? quality : "standard");
        body.put("response_format", "b64_json");

        Request request = new Request.Builder()
                .url(url)
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .post(RequestBody.create(body.toString(), JSON))
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("HTTP " + response.code() + ": " + response.body().string());
            }
            JsonNode root = MAPPER.readTree(response.body().string());
            JsonNode data = root.get("data");
            if (data != null && data.isArray() && !data.isEmpty()) {
                return data.get(0).get("b64_json").asText();
            }
            throw new IOException("No image data in response");
        }
    }

    public record Input(String prompt, String model, String apiKey, String baseUrl,
                        String outputPath, String outputDir, String size, String quality,
                        int n, String outputFormat) {
        public Input {
            if (prompt == null || prompt.isBlank()) {
                throw new IllegalArgumentException("prompt is required");
            }
            if (n <= 0) n = 1;
            if (n > 4) n = 4;
        }
    }
}
