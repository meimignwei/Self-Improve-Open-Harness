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
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * Generate or edit raster images using a configurable image generation provider
 * (OpenAI DALL-E compatible). Reads config from context.metadata["image_generation_config"].
 */
public class ImageGenerationTool extends BaseTool<ImageGenerationTool.Input> {

    private static final Logger LOG = Logger.getLogger(ImageGenerationTool.class.getName());
    private static final ObjectMapper MAPPER = OpenHarnessObjectMapper.get();
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private static final String DEFAULT_PROMPT =
            "Create a high-quality raster image that satisfies the user's request. " +
            "Avoid watermarks, unintended text, and unrelated logos.";
    private static final String DEFAULT_MODEL = "gpt-image-2";
    private static final String DEFAULT_OUTPUT_DIR = "generated_images";

    public ImageGenerationTool() {
        super("image_generation",
                "Generate or edit raster images using a configurable image generation provider. " +
                "Use this for bitmap assets such as photos, illustrations, sprites, mockups, " +
                "transparent cutouts, or edited local images.",
                Input.class);
    }

    @Override
    public ToolResult execute(Input arguments, ToolExecutionContext context) {
        // Read config from context metadata
        Map<String, Object> metadata = context.metadata();
        Object configObj = metadata != null ? metadata.get("image_generation_config") : null;
        @SuppressWarnings("unchecked")
        Map<String, Object> config = (configObj instanceof Map) ? (Map<String, Object>) configObj : Map.of();

        String model = (arguments.model() != null && !arguments.model().isBlank())
                ? arguments.model()
                : getString(config, "model", DEFAULT_MODEL);
        String apiKey = getString(config, "api_key", "");
        String baseUrl = getString(config, "base_url", "https://api.openai.com/v1");

        if (apiKey.isBlank()) {
            return ToolResult.error(
                    "OpenAI image generation API key is not configured. Set image_generation.api_key " +
                    "or OPENHARNESS_IMAGE_GENERATION_API_KEY.");
        }

        List<String> imagePaths = arguments.imagePaths() != null ? arguments.imagePaths() : List.of();

        try {
            List<Path> outputPaths = resolveOutputPaths(arguments, context.cwd());
            List<String> imagesB64;

            if (!imagePaths.isEmpty()) {
                // Edit mode
                imagesB64 = callImageEditApi(arguments, model, apiKey, baseUrl);
            } else {
                // Generate mode
                imagesB64 = callImageGenerateApi(arguments, model, apiKey, baseUrl);
            }

            List<Path> written = writeImages(imagesB64, outputPaths, arguments.overwrite());
            String mode = imagePaths.isEmpty() ? "generate" : "edit";
            StringBuilder sb = new StringBuilder();
            sb.append("[Image generation via ").append(model).append(" (").append(mode).append(", openai)]\n");
            for (Path p : written) {
                sb.append("Wrote ").append(p).append("\n");
            }
            return ToolResult.success(sb.toString().stripTrailing());
        } catch (FileExistsError e) {
            return ToolResult.error(e.getMessage());
        } catch (Exception e) {
            LOG.warning("image_generation failed: " + e.getMessage());
            return ToolResult.error("image_generation failed: " + e.getMessage());
        }
    }

    @Override
    public boolean isReadOnly(Input arguments) {
        return false;
    }

    // --- Output path resolution (matching Python) ---

    private List<Path> resolveOutputPaths(Input arguments, Path cwd) {
        String format = arguments.outputFormat() != null ? arguments.outputFormat() : "png";
        String suffix = "." + format;
        int n = arguments.n();

        Path base;
        if (arguments.outputPath() != null && !arguments.outputPath().isBlank()) {
            base = Path.of(arguments.outputPath());
            if (!base.isAbsolute()) {
                base = cwd.resolve(base);
            }
            base = base.normalize();
        } else {
            String outDir = arguments.outputDir() != null ? arguments.outputDir() : DEFAULT_OUTPUT_DIR;
            Path dir = Path.of(outDir);
            if (!dir.isAbsolute()) {
                dir = cwd.resolve(dir);
            }
            dir = dir.normalize();
            base = dir.resolve("image" + suffix);
        }

        if (!base.getFileName().toString().toLowerCase().endsWith(suffix)) {
            String stem = base.getFileName().toString();
            int dotIdx = stem.lastIndexOf('.');
            if (dotIdx >= 0) {
                stem = stem.substring(0, dotIdx);
            }
            base = base.resolveSibling(stem + suffix);
        }

        if (n == 1) {
            return List.of(base);
        }

        List<Path> paths = new ArrayList<>();
        String stem = base.getFileName().toString();
        int dotIdx = stem.lastIndexOf('.');
        String namePart = dotIdx >= 0 ? stem.substring(0, dotIdx) : stem;
        for (int i = 1; i <= n; i++) {
            paths.add(base.resolveSibling(namePart + "-" + i + suffix));
        }
        return paths;
    }

    // --- Write images with overwrite check ---

    private List<Path> writeImages(List<String> imagesB64, List<Path> outputPaths, boolean overwrite)
            throws IOException {
        List<Path> written = new ArrayList<>();
        int count = Math.min(imagesB64.size(), outputPaths.size());
        for (int i = 0; i < count; i++) {
            Path outputPath = outputPaths.get(i);
            if (Files.exists(outputPath) && !overwrite) {
                throw new FileExistsError("output already exists: " + outputPath + " (set overwrite=true)");
            }
            Files.createDirectories(outputPath.getParent());
            byte[] bytes = Base64.getDecoder().decode(imagesB64.get(i));
            Files.write(outputPath, bytes);
            written.add(outputPath);
        }
        if (written.isEmpty()) {
            throw new IOException("provider returned no image data");
        }
        return written;
    }

    // --- OpenAI generation API ---

    private List<String> callImageGenerateApi(Input arguments, String model,
                                              String apiKey, String baseUrl) throws IOException {
        OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(2, TimeUnit.MINUTES)
                .build();

        String url = baseUrl.replaceAll("/+$", "") + "/images/generations";

        ObjectNode body = buildImagePayload(arguments, model);
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
            return extractB64Images(root);
        }
    }

    // --- OpenAI edit API (multipart form data) ---

    private List<String> callImageEditApi(Input arguments, String model,
                                           String apiKey, String baseUrl) throws IOException {
        OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(2, TimeUnit.MINUTES)
                .build();

        String url = baseUrl.replaceAll("/+$", "") + "/images/edits";

        MultipartBody.Builder multipart = new MultipartBody.Builder()
                .setType(MultipartBody.FORM);

        // Add image files
        for (String imagePath : arguments.imagePaths()) {
            Path p = resolveFilePath(imagePath);
            byte[] bytes = Files.readAllBytes(p);
            String mediaType = guessMediaType(p);
            multipart.addFormDataPart("image", p.getFileName().toString(),
                    RequestBody.create(bytes, MediaType.get(mediaType)));
        }

        // Add optional mask
        if (arguments.maskPath() != null && !arguments.maskPath().isBlank()) {
            Path maskP = resolveFilePath(arguments.maskPath());
            byte[] maskBytes = Files.readAllBytes(maskP);
            multipart.addFormDataPart("mask", maskP.getFileName().toString(),
                    RequestBody.create(maskBytes, MediaType.get("image/png")));
        }

        // Add other parameters as form parts
        multipart.addFormDataPart("prompt", arguments.prompt());
        multipart.addFormDataPart("model", model);
        multipart.addFormDataPart("n", String.valueOf(Math.clamp(arguments.n(), 1, 10)));
        multipart.addFormDataPart("size", arguments.size() != null ? arguments.size() : "auto");
        multipart.addFormDataPart("response_format", "b64_json");

        String quality = arguments.quality() != null ? arguments.quality() : "medium";
        if (!quality.isBlank()) multipart.addFormDataPart("quality", quality);

        Request request = new Request.Builder()
                .url(url)
                .header("Authorization", "Bearer " + apiKey)
                .post(multipart.build())
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("HTTP " + response.code() + ": " + response.body().string());
            }
            JsonNode root = MAPPER.readTree(response.body().string());
            return extractB64Images(root);
        }
    }

    // --- Shared helpers ---

    private ObjectNode buildImagePayload(Input arguments, String model) {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("model", model);
        body.put("prompt", arguments.prompt() != null ? arguments.prompt() : DEFAULT_PROMPT);
        body.put("n", Math.clamp(arguments.n(), 1, 10));
        body.put("size", arguments.size() != null ? arguments.size() : "auto");

        String quality = arguments.quality() != null ? arguments.quality() : "medium";
        if (!quality.isBlank()) body.put("quality", quality);

        String outFmt = arguments.outputFormat() != null ? arguments.outputFormat() : "png";
        if (!outFmt.isBlank()) body.put("output_format", outFmt);

        if (arguments.background() != null && !arguments.background().isBlank()) {
            body.put("background", arguments.background());
        }
        if (arguments.outputCompression() != null) {
            body.put("output_compression", arguments.outputCompression());
        }
        if (arguments.inputFidelity() != null && !arguments.inputFidelity().isBlank()) {
            body.put("input_fidelity", arguments.inputFidelity());
        }
        if (arguments.moderation() != null && !arguments.moderation().isBlank()) {
            body.put("moderation", arguments.moderation());
        }
        return body;
    }

    private List<String> extractB64Images(JsonNode root) throws IOException {
        List<String> images = new ArrayList<>();
        JsonNode data = root.get("data");
        if (data != null && data.isArray()) {
            for (JsonNode item : data) {
                // Try b64_json first
                JsonNode b64 = item.get("b64_json");
                if (b64 != null && b64.isTextual() && !b64.asText().isEmpty()) {
                    images.add(b64.asText());
                    continue;
                }
                // Try data:image URL
                JsonNode urlNode = item.get("url");
                if (urlNode != null && urlNode.isTextual()) {
                    String url = urlNode.asText();
                    if (url.startsWith("data:image/") && url.contains(";base64,")) {
                        images.add(url.split(";base64,", 2)[1]);
                    }
                }
            }
        }
        if (images.isEmpty()) {
            throw new IOException("No image data in response");
        }
        return images;
    }

    private Path resolveFilePath(String pathStr) {
        Path p = Path.of(pathStr);
        return p.toAbsolutePath().normalize();
    }

    private String guessMediaType(Path path) {
        String name = path.getFileName().toString().toLowerCase();
        if (name.endsWith(".png")) return "image/png";
        if (name.endsWith(".jpg") || name.endsWith(".jpeg")) return "image/jpeg";
        if (name.endsWith(".webp")) return "image/webp";
        if (name.endsWith(".gif")) return "image/gif";
        return "image/png";
    }

    private static String getString(Map<String, Object> map, String key, String defaultValue) {
        Object val = map.get(key);
        if (val instanceof String s && !s.isBlank()) return s;
        return defaultValue;
    }

    /** Custom exception for file-exists with overwrite=false. */
    private static class FileExistsError extends RuntimeException {
        FileExistsError(String msg) { super(msg); }
    }

    // --- Input record (matching Python ImageGenerationToolInput) ---

    public record Input(
            String prompt,
            String provider,
            List<String> imagePaths,
            String maskPath,
            String outputPath,
            String outputDir,
            String model,
            int n,
            String size,
            String quality,
            String background,
            String outputFormat,
            Integer outputCompression,
            String inputFidelity,
            String moderation,
            boolean overwrite
    ) {
        public Input {
            if (prompt == null || prompt.isBlank()) prompt = DEFAULT_PROMPT;
            if (imagePaths == null) imagePaths = List.of();
            if (n <= 0) n = 1;
            if (n > 10) n = 10;
            if (size == null || size.isBlank()) size = "auto";
            if (quality == null || quality.isBlank()) quality = "medium";
            if (outputFormat == null || outputFormat.isBlank()) outputFormat = "png";
            if (outputDir == null || outputDir.isBlank()) outputDir = DEFAULT_OUTPUT_DIR;
        }
    }
}
