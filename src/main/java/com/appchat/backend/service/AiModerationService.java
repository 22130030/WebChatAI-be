package com.appchat.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class AiModerationService {

    private final ObjectMapper objectMapper;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();

    @Value("${app.ai.moderation.enabled:false}")
    private boolean enabled;

    @Value("${app.ai.moderation.fail-open:true}")
    private boolean failOpen;

    @Value("${app.ai.moderation.url:http://localhost:8000/api}")
    private String moderationBaseUrl;

    @Value("${app.ai.moderation.model-type:traditional}")
    private String modelType;

    @Value("${app.ai.moderation.spam-threshold:}")
    private String spamThreshold;

    public ModerationResult moderate(String text) {
        if (!enabled || text == null || text.isBlank() || isNonTextMessage(text)) {
            return ModerationResult.allowedResult();
        }

        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("text", text);
            body.put("model_type", normalizeModelType(modelType));

            Double threshold = parseOptionalDouble(spamThreshold);
            if (threshold != null) {
                body.put("spam_threshold", threshold);
            }

            String jsonBody = objectMapper.writeValueAsString(body);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(buildModerationEndpoint()))
                    .timeout(Duration.ofSeconds(8))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("AI moderation HTTP " + response.statusCode() + ": " + response.body());
            }

            return parseModerationResponse(response.body());
        } catch (Exception ex) {
            System.err.println("[AI Moderation] " + ex.getMessage());

            if (failOpen) {
                return ModerationResult.allowedResult();
            }

            return ModerationResult.blocked(
                    List.of("moderation_unavailable"),
                    Map.of("error", ex.getMessage())
            );
        }
    }

    private ModerationResult parseModerationResponse(String responseBody) throws Exception {
        JsonNode root = objectMapper.readTree(responseBody);
        boolean allowed = root.path("is_allowed").asBoolean(true);
        List<String> flags = objectMapper.convertValue(
                root.path("flagged_as"),
                objectMapper.getTypeFactory().constructCollectionType(List.class, String.class)
        );
        Map<String, Object> report = objectMapper.convertValue(
                root,
                objectMapper.getTypeFactory().constructMapType(Map.class, String.class, Object.class)
        );

        return new ModerationResult(allowed, flags == null ? List.of() : flags, report);
    }

    private String buildModerationEndpoint() {
        String baseUrl = moderationBaseUrl == null || moderationBaseUrl.isBlank()
                ? "http://localhost:8000/api"
                : moderationBaseUrl.trim();

        if (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }

        return baseUrl + "/moderate";
    }

    private String normalizeModelType(String value) {
        if ("deep".equalsIgnoreCase(value)) {
            return "deep";
        }

        return "traditional";
    }

    private Double parseOptionalDouble(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }

        try {
            return Double.parseDouble(value.trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private boolean isNonTextMessage(String text) {
        String value = text.trim();
        return value.startsWith("[IMAGE]")
                || value.startsWith("[VIDEO]")
                || value.startsWith("[FILE]")
                || value.startsWith("[STICKER:");
    }

    public record ModerationResult(
            boolean allowed,
            List<String> flags,
            Map<String, Object> report
    ) {
        public static ModerationResult allowedResult() {
            return new ModerationResult(true, List.of(), Map.of());
        }

        public static ModerationResult blocked(List<String> flags, Map<String, Object> report) {
            return new ModerationResult(false, flags, report);
        }
    }
}
