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

    @Value("${app.ai.gemini.api-key:}")
    private String geminiApiKey;

    @Value("${app.ai.gemini.model:gemini-2.5-flash-lite}")
    private String geminiModel;

    @Value("${app.ai.gemini.url:https://generativelanguage.googleapis.com/v1beta/models}")
    private String geminiBaseUrl;

    public ModerationResult moderate(String text) {
        if (!enabled || text == null || text.isBlank() || isNonTextMessage(text)) {
            return ModerationResult.allowedResult();
        }

        if ("gemini".equalsIgnoreCase(modelType)) {
            try {
                return moderateWithGemini(text);
            } catch (Exception ex) {
                System.err.println("[AI Moderation Gemini] " + ex.getMessage());
                if (failOpen) {
                    return ModerationResult.allowedResult();
                }
                return ModerationResult.blocked(
                        List.of("moderation_unavailable"),
                        Map.of("error", ex.getMessage())
                );
            }
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
        if ("gemini".equalsIgnoreCase(value)) {
            return "gemini";
        }

        return "traditional";
    }

    private ModerationResult moderateWithGemini(String text) throws Exception {
        if (geminiApiKey == null || geminiApiKey.isBlank()) {
            throw new IllegalStateException("Chưa cấu hình GEMINI_API_KEY cho backend.");
        }

        String systemInstruction = "You are an AI Content Moderator. Analyze the user's text and classify it into these labels:\n" +
                "- spam: unsolicited messages, advertisements, promotions, links to external sites promising rewards, etc.\n" +
                "- toxic: general rude, disrespectful, or unreasonable comment.\n" +
                "- severe_toxic: extremely offensive, aggressive, or hateful comment.\n" +
                "- obscene: vulgar, profane, or sexually explicit language.\n" +
                "- threat: expressions of intention to harm, kill, or violence.\n" +
                "- insult: targeted personal attacks, name-calling, or demeaning language.\n" +
                "- identity_hate: hate speech targeting race, religion, gender, sexual orientation, etc.\n" +
                "\n" +
                "Respond ONLY with a JSON object containing boolean values (true/false) for each label.";

        Map<String, Object> textPart = new LinkedHashMap<>();
        textPart.put("text", text);

        Map<String, Object> contentPart = new LinkedHashMap<>();
        contentPart.put("parts", List.of(textPart));

        Map<String, Object> sysPart = new LinkedHashMap<>();
        sysPart.put("text", systemInstruction);

        Map<String, Object> sysInstruction = new LinkedHashMap<>();
        sysInstruction.put("parts", List.of(sysPart));

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "OBJECT");

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("spam", Map.of("type", "BOOLEAN"));
        properties.put("toxic", Map.of("type", "BOOLEAN"));
        properties.put("severe_toxic", Map.of("type", "BOOLEAN"));
        properties.put("obscene", Map.of("type", "BOOLEAN"));
        properties.put("threat", Map.of("type", "BOOLEAN"));
        properties.put("insult", Map.of("type", "BOOLEAN"));
        properties.put("identity_hate", Map.of("type", "BOOLEAN"));

        schema.put("properties", properties);
        schema.put("required", List.of("spam", "toxic", "severe_toxic", "obscene", "threat", "insult", "identity_hate"));

        Map<String, Object> generationConfig = new LinkedHashMap<>();
        generationConfig.put("responseMimeType", "application/json");
        generationConfig.put("responseSchema", schema);

        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("systemInstruction", sysInstruction);
        requestBody.put("contents", List.of(contentPart));
        requestBody.put("generationConfig", generationConfig);

        String jsonBody = objectMapper.writeValueAsString(requestBody);

        String baseUrl = geminiBaseUrl == null || geminiBaseUrl.isBlank()
                ? "https://generativelanguage.googleapis.com/v1beta/models"
                : geminiBaseUrl.trim();
        if (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        String endpoint = baseUrl + "/" + geminiModel + ":generateContent";

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .header("X-goog-api-key", geminiApiKey)
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("Gemini API HTTP " + response.statusCode() + ": " + response.body());
        }

        JsonNode root = objectMapper.readTree(response.body());
        JsonNode candidates = root.path("candidates");
        if (candidates.isArray() && candidates.size() > 0) {
            String ansText = candidates.get(0).path("content").path("parts").get(0).path("text").asText();
            JsonNode resultJson = objectMapper.readTree(ansText.trim());

            boolean spam = resultJson.path("spam").asBoolean(false);
            boolean toxic = resultJson.path("toxic").asBoolean(false);
            boolean severeToxic = resultJson.path("severe_toxic").asBoolean(false);
            boolean obscene = resultJson.path("obscene").asBoolean(false);
            boolean threat = resultJson.path("threat").asBoolean(false);
            boolean insult = resultJson.path("insult").asBoolean(false);
            boolean identityHate = resultJson.path("identity_hate").asBoolean(false);

            boolean allowed = !spam && !toxic && !severeToxic && !obscene && !threat && !insult && !identityHate;
            List<String> flags = new java.util.ArrayList<>();
            if (spam) flags.add("spam");
            if (toxic) flags.add("toxic");
            if (severeToxic) flags.add("severe_toxic");
            if (obscene) flags.add("obscene");
            if (threat) flags.add("threat");
            if (insult) flags.add("insult");
            if (identityHate) flags.add("identity_hate");

            Map<String, Object> report = new LinkedHashMap<>();
            report.put("is_allowed", allowed);
            report.put("flagged_as", flags);

            Map<String, Object> scores = new LinkedHashMap<>();
            scores.put("spam", spam ? 1.0 : 0.0);

            Map<String, Object> toxicScores = new LinkedHashMap<>();
            toxicScores.put("toxic", toxic ? 1.0 : 0.0);
            toxicScores.put("severe_toxic", severeToxic ? 1.0 : 0.0);
            toxicScores.put("obscene", obscene ? 1.0 : 0.0);
            toxicScores.put("threat", threat ? 1.0 : 0.0);
            toxicScores.put("insult", insult ? 1.0 : 0.0);
            toxicScores.put("identity_hate", identityHate ? 1.0 : 0.0);

            scores.put("toxic", toxicScores);
            report.put("scores", scores);

            return new ModerationResult(allowed, flags, report);
        }

        throw new IllegalStateException("Không đọc được phản hồi từ Gemini API.");
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
