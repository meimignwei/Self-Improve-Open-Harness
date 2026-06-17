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
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * Convert an image to a text description using a vision-capable model.
 * Reads vision model config from context.metadata["vision_model_config"].
 */
public class ImageToTextTool extends BaseTool<ImageToTextTool.Input> {

    private static final Logger LOG = Logger.getLogger(ImageToTextTool.class.getName());
    private static final ObjectMapper MAPPER = OpenHarnessObjectMapper.get();
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private static final String DEFAULT_PROMPT =
            "You are an image description assistant. " +
            "Describe the image in detail, including any text, objects, people, " +
            "colors, layout, and context. If the image contains code, UI screenshots, " +
            "diagrams, or data visualizations, describe them precisely so that a " +
            "text-only AI model can understand the content.";

    public ImageToTextTool() {
        super("image_to_text",
                "Convert an image to a detailed text description using a vision-capable model. " +
                "Use this when you need to understand the content of an image but your current " +
                "model does not support image input.",
                Input.class);
    }

    @Override
    public ToolResult execute(Input arguments, ToolExecutionContext context) {
        String imageData;
        String mediaType;
        try {
            var resolved = resolveImage(arguments, context);
            if (resolved == null) {
                return ToolResult.error("image_to_text failed: provide either image_data (base64) or image_path");
            }
            imageData = resolved[0];
            mediaType = resolved[1];
        } catch (IOException e) {
            return ToolResult.error("image_to_text failed: " + e.getMessage());
        }

        // Get vision model config from context metadata
        Map<String, Object> metadata = context.metadata();
        Object visionObj = metadata != null ? metadata.get("vision_model_config") : null;
        @SuppressWarnings("unchecked")
        Map<String, Object> visionConfig = (visionObj instanceof Map) ? (Map<String, Object>) visionObj : Map.of();

        String model = getString(visionConfig, "model", "");
        String apiKey = getString(visionConfig, "api_key", "");
        String baseUrl = getString(visionConfig, "base_url", "");

        if (model.isBlank() || apiKey.isBlank()) {
            LOG.warning("image_to_text: vision model not configured. Set vision.model and vision.api_key in settings.");
            return ToolResult.error(
                    "image_to_text failed: vision model is not configured. " +
                    "Please set vision.model and vision.api_key in your settings, " +
                    "or configure the OPENHARNESS_VISION_MODEL and " +
                    "OPENHARNESS_VISION_API_KEY environment variables.");
        }

        try {
            String description = callVisionModel(imageData, mediaType, arguments.prompt(),
                    model, apiKey, baseUrl, arguments.maxTokens());
            return ToolResult.success("[Image description via " + model + "]\n\n" + description);
        } catch (Exception e) {
            LOG.warning("image_to_text: vision model call failed: " + e.getMessage());
            return ToolResult.error("image_to_text failed: vision model error: " + e.getMessage());
        }
    }

    @Override
    public boolean isReadOnly(Input arguments) {
        return true;
    }

    private String[] resolveImage(Input arguments, ToolExecutionContext context) throws IOException {
        if (arguments.imageData() != null && !arguments.imageData().isBlank()) {
            return new String[]{arguments.imageData(), arguments.mediaType()};
        }
        if (arguments.imagePath() != null && !arguments.imagePath().isBlank()) {
            Path path = Path.of(arguments.imagePath());
            if (!path.isAbsolute()) {
                path = context.cwd().resolve(path);
            }
            path = path.normalize();

            if (!Files.exists(path)) {
                LOG.warning("image_to_text: image not found at " + path);
                return null;
            }

            try {
                byte[] raw = Files.readAllBytes(path);
                String data = Base64.getEncoder().encodeToString(raw);
                String mt = guessMediaType(path);
                return new String[]{data, mt};
            } catch (IOException e) {
                LOG.warning("image_to_text: failed to read " + path + ": " + e.getMessage());
                return null;
            }
        }
        return null;
    }

    private String guessMediaType(Path path) {
        String name = path.getFileName().toString().toLowerCase();
        if (name.endsWith(".png")) return "image/png";
        if (name.endsWith(".jpg") || name.endsWith(".jpeg")) return "image/jpeg";
        if (name.endsWith(".gif")) return "image/gif";
        if (name.endsWith(".webp")) return "image/webp";
        if (name.endsWith(".bmp")) return "image/bmp";
        if (name.endsWith(".svg")) return "image/svg+xml";
        return "image/png";
    }

    private String callVisionModel(String imageData, String mediaType, String prompt,
                                    String model, String apiKey, String baseUrl, int maxTokens) throws IOException {
        OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(2, TimeUnit.MINUTES)
                .build();

        String url = baseUrl.replaceAll("/+$", "") + "/chat/completions";

        ObjectNode body = MAPPER.createObjectNode();
        body.put("model", model);
        body.put("max_tokens", maxTokens);

        ArrayNode messages = MAPPER.createArrayNode();
        ObjectNode userMsg = MAPPER.createObjectNode();
        userMsg.put("role", "user");

        ArrayNode content = MAPPER.createArrayNode();
        ObjectNode textBlock = MAPPER.createObjectNode();
        textBlock.put("type", "text");
        textBlock.put("text", prompt);
        content.add(textBlock);

        ObjectNode imageBlock = MAPPER.createObjectNode();
        imageBlock.put("type", "image_url");
        ObjectNode imageUrl = MAPPER.createObjectNode();
        imageUrl.put("url", "data:" + mediaType + ";base64," + imageData);
        imageBlock.set("image_url", imageUrl);
        content.add(imageBlock);

        userMsg.set("content", content);
        messages.add(userMsg);
        body.set("messages", messages);

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
            JsonNode choices = root.get("choices");
            if (choices != null && choices.isArray() && !choices.isEmpty()) {
                JsonNode message = choices.get(0).get("message");
                if (message != null && message.has("content")) {
                    return message.get("content").asText("(no description)");
                }
            }
            return "(no description returned)";
        }
    }

    private static String getString(Map<String, Object> map, String key, String defaultValue) {
        Object val = map.get(key);
        if (val instanceof String s && !s.isBlank()) return s;
        return defaultValue;
    }

    public record Input(String imageData, String imagePath, String prompt,
                        String mediaType, int maxTokens) {
        public Input {
            if (prompt == null || prompt.isBlank()) prompt = DEFAULT_PROMPT;
            if (mediaType == null || mediaType.isBlank()) mediaType = "image/png";
            if (maxTokens <= 0) maxTokens = 2048;
            if (maxTokens > 16384) maxTokens = 16384;
        }
    }
}
