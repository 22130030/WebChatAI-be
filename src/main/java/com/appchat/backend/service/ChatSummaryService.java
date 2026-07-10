package com.appchat.backend.service;

import com.appchat.backend.dto.ChatSummaryDto;
import com.appchat.backend.entity.Message;
import com.appchat.backend.repository.MessageRepository;
import com.appchat.backend.repository.RoomMemberRepository;
import com.appchat.backend.repository.RoomRepository;
import com.appchat.backend.repository.UserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class ChatSummaryService {

    private final MessageRepository messageRepository;
    private final RoomMemberRepository roomMemberRepository;
    private final RoomRepository roomRepository;
    private final UserRepository userRepository;
    private final ObjectMapper objectMapper;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .build();

    @Value("${app.ai.gemini.api-key:}")
    private String geminiApiKey;

    @Value("${app.ai.gemini.model:gemini-2.5-flash-lite}")
    private String geminiModel;

    @Value("${app.ai.gemini.url:https://generativelanguage.googleapis.com/v1beta/models}")
    private String geminiBaseUrl;

    /**
     * Múi giờ dùng để xác định phạm vi "Hôm nay".
     * Có thể đổi bằng biến môi trường CHAT_SUMMARY_TIME_ZONE.
     */
    @Value("${app.chat-summary.time-zone:Asia/Ho_Chi_Minh}")
    private String summaryTimeZone;

    public ChatSummaryDto summarizeChat(
            String currentUsername,
            String rawType,
            String target,
            String rawPeriod,
            String rawMode,
            int limit,
            String from,
            String to,
            boolean force
    ) throws Exception {
        String type = normalizeType(rawType);
        String period = normalizePeriod(rawPeriod);
        String mode = normalizeMode(rawMode);
        String normalizedTarget = target == null ? "" : target.trim();
        int safeLimit = normalizeLimit(limit);
        ZoneId zoneId = resolveSummaryZoneId();
        TimeWindow timeWindow = resolveTimeWindow(period, from, to, zoneId);

        if (normalizedTarget.isBlank()) {
            throw new IllegalArgumentException("Thiếu tên cuộc trò chuyện cần tóm tắt.");
        }

        List<Message> messages = loadMessages(
                currentUsername,
                type,
                normalizedTarget,
                safeLimit,
                timeWindow
        );

        LocalDateTime responseTime = LocalDateTime.now(zoneId);
        String lastMessageId = messages.isEmpty()
                ? null
                : messages.get(messages.size() - 1).getId();

        if (messages.isEmpty()) {
            return ChatSummaryDto.builder()
                    .type(type)
                    .target(normalizedTarget)
                    .period(period)
                    .mode(mode)
                    .fromTime(timeWindow == null ? null : timeWindow.fromTime())
                    .toTime(timeWindow == null ? null : timeWindow.toTime())
                    .limit(safeLimit)
                    .messageCount(0)
                    .lastMessageId(null)
                    .summary(buildEmptySummaryMessage(period))
                    .cached(false)
                    .aiProvider("gemini")
                    .createdAt(responseTime)
                    .updatedAt(responseTime)
                    .build();
        }

        String transcript = buildTranscript(messages);
        String prompt = buildPrompt(type, normalizedTarget, period, messages.size(), transcript);
        String summary = callGemini(prompt);

        return ChatSummaryDto.builder()
                .type(type)
                .target(normalizedTarget)
                .period(period)
                .mode(mode)
                .fromTime(timeWindow == null ? null : timeWindow.fromTime())
                .toTime(timeWindow == null ? null : timeWindow.toTime())
                .limit(safeLimit)
                .messageCount(messages.size())
                .lastMessageId(lastMessageId)
                .summary(summary)
                .cached(false)
                .aiProvider("gemini")
                .createdAt(responseTime)
                .updatedAt(responseTime)
                .build();
    }

    private List<Message> loadMessages(
            String currentUsername,
            String type,
            String target,
            int limit,
            TimeWindow timeWindow
    ) {
        List<Message> latestMessages;
        PageRequest pageRequest = PageRequest.of(0, limit);

        if ("people".equals(type)) {
            if (!currentUsername.equals(target) && userRepository.findByUsername(target).isEmpty()) {
                throw new IllegalArgumentException("Người dùng không tồn tại.");
            }

            if (timeWindow == null) {
                latestMessages = messageRepository.findPeopleMessages(
                        currentUsername,
                        target,
                        pageRequest
                );
            } else {
                latestMessages = messageRepository.findPeopleMessagesBetween(
                        currentUsername,
                        target,
                        timeWindow.fromInstant(),
                        timeWindow.toInstant(),
                        pageRequest
                );
            }
        } else {
            if (roomRepository.findByName(target).isEmpty()) {
                throw new IllegalArgumentException("Nhóm chat không tồn tại.");
            }

            if (!roomMemberRepository.existsByRoomNameAndUsername(target, currentUsername)) {
                throw new AccessDeniedException("Bạn không thuộc nhóm chat này.");
            }

            if (timeWindow == null) {
                latestMessages = messageRepository.findRoomMessages(
                        target,
                        pageRequest
                );
            } else {
                latestMessages = messageRepository.findRoomMessagesBetween(
                        target,
                        timeWindow.fromInstant(),
                        timeWindow.toInstant(),
                        pageRequest
                );
            }
        }

        List<Message> chronologicalMessages = new ArrayList<>(latestMessages);
        Collections.reverse(chronologicalMessages);
        return chronologicalMessages;
    }

    private TimeWindow resolveTimeWindow(
            String period,
            String from,
            String to,
            ZoneId zoneId
    ) {
        if ("latest".equals(period)) {
            return null;
        }

        if ("today".equals(period)) {
            LocalDate today = LocalDate.now(zoneId);
            ZonedDateTime start = today.atStartOfDay(zoneId);
            ZonedDateTime end = today.plusDays(1).atStartOfDay(zoneId);
            return toTimeWindow(start, end);
        }

        if (from == null || from.isBlank() || to == null || to.isBlank()) {
            throw new IllegalArgumentException("Vui lòng cung cấp đầy đủ ngày bắt đầu và ngày kết thúc.");
        }

        ZonedDateTime start = parseDateBoundary(from.trim(), zoneId, false);
        ZonedDateTime end = parseDateBoundary(to.trim(), zoneId, true);

        if (!start.isBefore(end)) {
            throw new IllegalArgumentException("Thời gian bắt đầu phải nhỏ hơn thời gian kết thúc.");
        }

        return toTimeWindow(start, end);
    }

    private ZonedDateTime parseDateBoundary(String value, ZoneId zoneId, boolean endBoundary) {
        try {
            LocalDate date = LocalDate.parse(value);
            LocalDate boundaryDate = endBoundary ? date.plusDays(1) : date;
            return boundaryDate.atStartOfDay(zoneId);
        } catch (DateTimeParseException ignored) {
            // Không phải định dạng yyyy-MM-dd, thử các dạng ISO có thời gian bên dưới.
        }

        try {
            return OffsetDateTime.parse(value).atZoneSameInstant(zoneId);
        } catch (DateTimeParseException ignored) {
            // Không có offset, thử LocalDateTime.
        }

        try {
            return LocalDateTime.parse(value).atZone(zoneId);
        } catch (DateTimeParseException ex) {
            throw new IllegalArgumentException(
                    "Ngày giờ không hợp lệ. Hãy dùng yyyy-MM-dd hoặc ISO-8601, ví dụ 2026-07-10T08:30:00."
            );
        }
    }

    private TimeWindow toTimeWindow(ZonedDateTime start, ZonedDateTime end) {
        return new TimeWindow(
                start.toLocalDateTime(),
                end.toLocalDateTime(),
                start.toInstant(),
                end.toInstant()
        );
    }

    private String buildEmptySummaryMessage(String period) {
        if ("today".equals(period)) {
            return "Hôm nay cuộc trò chuyện này chưa có tin nhắn để tóm tắt.";
        }

        if ("range".equals(period)) {
            return "Không có tin nhắn trong khoảng thời gian đã chọn để tóm tắt.";
        }

        return "Cuộc trò chuyện này chưa có tin nhắn để tóm tắt.";
    }

    private String buildTranscript(List<Message> messages) {
        StringBuilder builder = new StringBuilder();

        for (Message message : messages) {
            String sender = message.getSender() == null ? "Không rõ" : message.getSender();
            String content = normalizeMessageContent(message);

            if (content.isBlank()) {
                continue;
            }

            builder.append(sender)
                    .append(": ")
                    .append(truncate(content, 700))
                    .append('\n');

            if (builder.length() > 18000) {
                builder.append("...\n");
                break;
            }
        }

        return builder.toString().trim();
    }

    private String normalizeMessageContent(Message message) {
        if (Boolean.TRUE.equals(message.getRecalled())) {
            return "[Tin nhắn đã được thu hồi]";
        }

        String content = message.getContent() == null ? "" : message.getContent().trim();

        if (content.startsWith("[IMAGE]")) {
            return "[Đã gửi một hình ảnh]";
        }

        if (content.startsWith("[VIDEO]")) {
            return "[Đã gửi một video]";
        }

        if (content.startsWith("[FILE]")) {
            String fileContent = content.replaceFirst("^\\[FILE]", "");
            String[] parts = fileContent.split("\\|");
            String fileName = parts.length >= 2 ? parts[1] : "tệp đính kèm";
            return "[Đã gửi file: " + fileName + "]";
        }

        if (content.startsWith("[STICKER:")) {
            return "[Đã gửi sticker]";
        }

        return content;
    }

    private String buildPrompt(
            String type,
            String target,
            String period,
            int messageCount,
            String transcript
    ) {
        String conversationType = "room".equals(type) ? "chat nhóm" : "chat 1-1";
        String periodDescription = switch (period) {
            case "today" -> "các tin nhắn trong hôm nay";
            case "range" -> "các tin nhắn trong khoảng thời gian đã chọn";
            default -> "các tin nhắn gần nhất";
        };

        return """
                Bạn là trợ lý AI trong ứng dụng web chat.
                Hãy tóm tắt cuộc trò chuyện bằng tiếng Việt, ngắn gọn, dễ hiểu.

                Yêu cầu:
                - Tóm tắt nội dung chính của cuộc trò chuyện.
                - Nếu có việc cần làm, hãy ghi rõ ai cần làm gì.
                - Nếu có quyết định/thống nhất, hãy liệt kê rõ.
                - Không bịa thêm thông tin ngoài tin nhắn.
                - Nếu nội dung chỉ là trò chuyện xã giao, hãy nói ngắn gọn là chưa có nội dung quan trọng.

                Thông tin cuộc trò chuyện:
                - Loại: %s
                - Tên/người nhận: %s
                - Phạm vi: %s
                - Số tin nhắn dùng để tóm tắt: %d

                Tin nhắn:
                %s
                """.formatted(conversationType, target, periodDescription, messageCount, transcript);
    }

    private String callGemini(String prompt) throws Exception {
        if (geminiApiKey == null || geminiApiKey.isBlank()) {
            throw new IllegalStateException("Chưa cấu hình GEMINI_API_KEY cho backend.");
        }

        Map<String, Object> textPart = new LinkedHashMap<>();
        textPart.put("text", prompt);

        Map<String, Object> content = new LinkedHashMap<>();
        content.put("role", "user");
        content.put("parts", List.of(textPart));

        Map<String, Object> generationConfig = new LinkedHashMap<>();
        generationConfig.put("temperature", 0.3);
        generationConfig.put("maxOutputTokens", 500);

        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("contents", List.of(content));
        requestBody.put("generationConfig", generationConfig);

        String jsonBody = objectMapper.writeValueAsString(requestBody);
        String endpoint = buildGeminiEndpoint();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .timeout(Duration.ofSeconds(45))
                .header("Content-Type", "application/json")
                .header("X-goog-api-key", geminiApiKey)
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException(
                    "Gemini API lỗi: HTTP " + response.statusCode() + " - " + extractGeminiError(response.body())
            );
        }

        return extractGeminiSummaryText(response.body());
    }

    private String buildGeminiEndpoint() {
        String baseUrl = geminiBaseUrl == null || geminiBaseUrl.isBlank()
                ? "https://generativelanguage.googleapis.com/v1beta/models"
                : geminiBaseUrl.trim();

        if (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }

        return baseUrl + "/" + geminiModel + ":generateContent";
    }

    private String extractGeminiSummaryText(String responseBody) throws Exception {
        JsonNode root = objectMapper.readTree(responseBody);
        JsonNode candidates = root.path("candidates");

        if (candidates.isArray()) {
            StringBuilder builder = new StringBuilder();

            for (JsonNode candidate : candidates) {
                JsonNode parts = candidate.path("content").path("parts");
                if (!parts.isArray()) {
                    continue;
                }

                for (JsonNode part : parts) {
                    JsonNode textNode = part.path("text");
                    if (textNode.isTextual() && !textNode.asText().isBlank()) {
                        builder.append(textNode.asText()).append('\n');
                    }
                }
            }

            String text = builder.toString().trim();
            if (!text.isBlank()) {
                return text;
            }
        }

        JsonNode blockReason = root.path("promptFeedback").path("blockReason");
        if (blockReason.isTextual() && !blockReason.asText().isBlank()) {
            throw new IllegalStateException("Gemini không tạo tóm tắt vì nội dung bị chặn: " + blockReason.asText());
        }

        throw new IllegalStateException("Không đọc được nội dung tóm tắt từ Gemini API.");
    }

    private String extractGeminiError(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) {
            return "Không có nội dung lỗi từ Gemini.";
        }

        try {
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode message = root.path("error").path("message");
            if (message.isTextual() && !message.asText().isBlank()) {
                return message.asText();
            }
        } catch (Exception ignored) {
        }

        return truncate(responseBody, 500);
    }

    private String normalizeType(String rawType) {
        String type = rawType == null ? "" : rawType.trim().toLowerCase(Locale.ROOT);

        if ("0".equals(type) || "people".equals(type) || "private".equals(type)) {
            return "people";
        }

        if ("1".equals(type) || "room".equals(type) || "group".equals(type)) {
            return "room";
        }

        throw new IllegalArgumentException("Loại cuộc trò chuyện không hợp lệ.");
    }

    private String normalizePeriod(String rawPeriod) {
        String period = rawPeriod == null
                ? "latest"
                : rawPeriod.trim().toLowerCase(Locale.ROOT);

        if (period.isBlank() || "latest".equals(period)) {
            return "latest";
        }

        if ("today".equals(period)) {
            return "today";
        }

        if ("range".equals(period)) {
            return "range";
        }

        throw new IllegalArgumentException("Phạm vi tóm tắt không hợp lệ.");
    }

    private String normalizeMode(String rawMode) {
        String mode = rawMode == null ? "general" : rawMode.trim().toLowerCase(Locale.ROOT);
        return mode.isBlank() ? "general" : mode;
    }

    private int normalizeLimit(int limit) {
        if (limit <= 0) {
            return 50;
        }
        return Math.min(limit, 100);
    }

    private ZoneId resolveSummaryZoneId() {
        String configuredZone = summaryTimeZone == null ? "" : summaryTimeZone.trim();

        try {
            return configuredZone.isBlank()
                    ? ZoneId.of("Asia/Ho_Chi_Minh")
                    : ZoneId.of(configuredZone);
        } catch (DateTimeException ex) {
            throw new IllegalStateException(
                    "Múi giờ tóm tắt không hợp lệ: " + configuredZone,
                    ex
            );
        }
    }

    private String truncate(String text, int maxLength) {
        if (text == null) {
            return "";
        }
        if (text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength) + "...";
    }

    private record TimeWindow(
            LocalDateTime fromTime,
            LocalDateTime toTime,
            Instant fromInstant,
            Instant toInstant
    ) {
    }
}