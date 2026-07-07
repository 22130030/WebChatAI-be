package com.appchat.backend.service;

import com.appchat.backend.dto.ChatSummaryDto;
import com.appchat.backend.entity.ChatSummary;
import com.appchat.backend.entity.Message;
import com.appchat.backend.repository.ChatSummaryRepository;
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
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class ChatSummaryService {

    private static final ZoneId VIETNAM_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    private final MessageRepository messageRepository;
    private final RoomMemberRepository roomMemberRepository;
    private final RoomRepository roomRepository;
    private final UserRepository userRepository;
    private final ChatSummaryRepository chatSummaryRepository;
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

        if (normalizedTarget.isBlank()) {
            throw new IllegalArgumentException("Thiếu tên cuộc trò chuyện cần tóm tắt.");
        }

        DateRange dateRange = resolveDateRange(period, from, to);

        List<Message> messages = loadMessages(
                currentUsername,
                type,
                normalizedTarget,
                period,
                dateRange,
                safeLimit
        );

        if (messages.isEmpty()) {
            return ChatSummaryDto.builder()
                    .type(type)
                    .target(normalizedTarget)
                    .period(period)
                    .mode(mode)
                    .fromTime(dateRange.fromTime())
                    .toTime(dateRange.toTime())
                    .limit(safeLimit)
                    .messageCount(0)
                    .lastMessageId(null)
                    .summary("Cuộc trò chuyện này chưa có tin nhắn phù hợp để tóm tắt.")
                    .cached(false)
                    .aiProvider("none")
                    .build();
        }

        String lastMessageId = getLastMessageId(messages);
        int messageCount = messages.size();

        if (!force) {
            ChatSummary cachedSummary = findCachedSummary(
                    type,
                    normalizedTarget,
                    mode,
                    period,
                    currentUsername,
                    safeLimit,
                    messageCount,
                    lastMessageId,
                    dateRange
            );

            if (cachedSummary != null) {
                return toDto(cachedSummary, true);
            }
        }

        String transcript = buildTranscript(messages);
        String prompt = buildPrompt(
                type,
                normalizedTarget,
                period,
                mode,
                messageCount,
                dateRange,
                transcript
        );

        String summaryText = callGemini(prompt);
        String aiProvider = "gemini";

        ChatSummary savedSummary = ChatSummary.builder()
                .conversationType(type)
                .target(normalizedTarget)
                .summaryMode(mode)
                .periodType(period)
                .fromTime(dateRange.fromTime())
                .toTime(dateRange.toTime())
                .messageLimit(safeLimit)
                .messageCount(messageCount)
                .lastMessageId(lastMessageId)
                .summaryText(summaryText)
                .createdBy(currentUsername)
                .aiProvider(aiProvider)
                .build();

        savedSummary = chatSummaryRepository.save(savedSummary);

        return toDto(savedSummary, false);
    }

    private List<Message> loadMessages(
            String currentUsername,
            String type,
            String target,
            String period,
            DateRange dateRange,
            int limit
    ) {
        validateConversationAccess(currentUsername, type, target);

        List<Message> latestMessages;

        if ("latest".equals(period)) {
            if ("people".equals(type)) {
                latestMessages = messageRepository.findPeopleMessages(
                        currentUsername,
                        target,
                        PageRequest.of(0, limit)
                );
            } else {
                latestMessages = messageRepository.findRoomMessages(
                        target,
                        PageRequest.of(0, limit)
                );
            }
        } else {
            if ("people".equals(type)) {
                latestMessages = messageRepository.findPeopleMessagesBetween(
                        currentUsername,
                        target,
                        dateRange.fromTime(),
                        dateRange.toTime(),
                        PageRequest.of(0, limit)
                );
            } else {
                latestMessages = messageRepository.findRoomMessagesBetween(
                        target,
                        dateRange.fromTime(),
                        dateRange.toTime(),
                        PageRequest.of(0, limit)
                );
            }
        }

        List<Message> chronologicalMessages = new ArrayList<>(latestMessages);
        Collections.reverse(chronologicalMessages);
        return chronologicalMessages;
    }

    private void validateConversationAccess(String currentUsername, String type, String target) {
        if ("people".equals(type)) {
            if (!currentUsername.equals(target) && userRepository.findByUsername(target).isEmpty()) {
                throw new IllegalArgumentException("Người dùng không tồn tại.");
            }

            return;
        }

        if (roomRepository.findByName(target).isEmpty()) {
            throw new IllegalArgumentException("Nhóm chat không tồn tại.");
        }

        if (!roomMemberRepository.existsByRoomNameAndUsername(target, currentUsername)) {
            throw new AccessDeniedException("Bạn không thuộc nhóm chat này.");
        }
    }

    private ChatSummary findCachedSummary(
            String type,
            String target,
            String mode,
            String period,
            String currentUsername,
            int limit,
            int messageCount,
            String lastMessageId,
            DateRange dateRange
    ) {
        List<ChatSummary> summaries = chatSummaryRepository.findReusableSummary(
                type,
                target,
                mode,
                period,
                currentUsername,
                limit,
                messageCount,
                lastMessageId,
                dateRange.fromTime(),
                dateRange.toTime(),
                PageRequest.of(0, 10)
        );

        if (summaries == null || summaries.isEmpty()) {
            return null;
        }

        for (ChatSummary summary : summaries) {
            if ("gemini".equalsIgnoreCase(summary.getAiProvider())) {
                return summary;
            }
        }

        return null;
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

            if (builder.length() > 22000) {
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
            String mode,
            int messageCount,
            DateRange dateRange,
            String transcript
    ) {
        String conversationType = "room".equals(type) ? "chat nhóm" : "chat 1-1";
        String periodDescription = buildPeriodDescription(period, dateRange);
        String modeInstruction = buildModeInstruction(mode);

        return """
                Bạn là trợ lý AI trong ứng dụng web chat.
                Hãy trả lời hoàn toàn bằng tiếng Việt.
                Không bịa thêm thông tin ngoài tin nhắn.
                Không nhắc lại toàn bộ tin nhắn.
                Trình bày gọn gàng, dễ đọc.

                Kiểu tóm tắt yêu cầu:
                %s

                Thông tin cuộc trò chuyện:
                - Loại: %s
                - Tên/người nhận: %s
                - Phạm vi: %s
                - Số tin nhắn dùng để tóm tắt: %d

                Tin nhắn:
                %s
                """.formatted(
                modeInstruction,
                conversationType,
                target,
                periodDescription,
                messageCount,
                transcript
        );
    }

    private String buildModeInstruction(String mode) {
        return switch (mode) {
            case "tasks" -> """
                    Chỉ tập trung vào VIỆC CẦN LÀM.
                    Format bắt buộc:
                    Việc cần làm:
                    - Ai cần làm gì, nếu có.
                    - Nếu không có việc cần làm rõ ràng, ghi: Chưa thấy việc cần làm rõ ràng.
                    """;
            case "decisions" -> """
                    Chỉ tập trung vào QUYẾT ĐỊNH hoặc THỐNG NHẤT.
                    Format bắt buộc:
                    Quyết định/Thống nhất:
                    - Những điều nhóm đã chốt, nếu có.
                    - Nếu chưa có quyết định rõ ràng, ghi: Chưa thấy quyết định hoặc thống nhất rõ ràng.
                    """;
            default -> """
                    Tóm tắt tổng quan cuộc trò chuyện.
                    Format bắt buộc:
                    Nội dung chính:
                    - ...

                    Việc cần làm:
                    - ...

                    Quyết định/Thống nhất:
                    - ...
                    """;
        };
    }

    private String buildPeriodDescription(String period, DateRange dateRange) {
        if ("latest".equals(period)) {
            return "100 tin nhắn gần nhất hoặc theo limit được truyền vào.";
        }

        if ("today".equals(period)) {
            return "Tin nhắn trong hôm nay.";
        }

        return "Tin nhắn từ " + dateRange.fromTime() + " đến trước " + dateRange.toTime() + ".";
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
        generationConfig.put("temperature", 0.25);
        generationConfig.put("maxOutputTokens", 700);

        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("contents", List.of(content));
        requestBody.put("generationConfig", generationConfig);

        String jsonBody = objectMapper.writeValueAsString(requestBody);
        String endpoint = buildGeminiEndpoint();

        int maxAttempts = 3;
        Exception lastException = null;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(endpoint))
                        .timeout(Duration.ofSeconds(45))
                        .header("Content-Type", "application/json")
                        .header("X-goog-api-key", geminiApiKey)
                        .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                        .build();

                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() >= 200 && response.statusCode() < 300) {
                    return extractGeminiSummaryText(response.body());
                }

                String errorMessage = extractGeminiError(response.body());

                if (response.statusCode() == 503 || response.statusCode() == 429) {
                    lastException = new IllegalStateException(
                            "Gemini API lỗi: HTTP " + response.statusCode() + " - " + errorMessage
                    );

                    Thread.sleep(1200L * attempt);
                    continue;
                }

                throw new IllegalStateException(
                        "Gemini API lỗi: HTTP " + response.statusCode() + " - " + errorMessage
                );
            } catch (Exception ex) {
                lastException = ex;

                if (attempt < maxAttempts) {
                    Thread.sleep(1200L * attempt);
                    continue;
                }

                throw ex;
            }
        }

        throw lastException == null
                ? new IllegalStateException("Gemini API đang quá tải. Vui lòng thử lại sau.")
                : lastException;
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

    private String buildFallbackSummary(List<Message> messages, String mode, String errorMessage) {
        int count = messages == null ? 0 : messages.size();

        Map<String, Integer> senderCount = new LinkedHashMap<>();
        List<String> importantMessages = new ArrayList<>();

        if (messages != null) {
            for (Message message : messages) {
                String sender = message.getSender() == null ? "Không rõ" : message.getSender();
                String content = normalizeMessageContent(message);

                if (content == null || content.isBlank()) {
                    continue;
                }

                senderCount.put(sender, senderCount.getOrDefault(sender, 0) + 1);

                String lowerContent = content.toLowerCase(Locale.ROOT).trim();

                boolean isShortNoise =
                        lowerContent.equals("ok") ||
                                lowerContent.equals("oke") ||
                                lowerContent.equals("ừ") ||
                                lowerContent.equals("uh") ||
                                lowerContent.equals("haha") ||
                                lowerContent.equals("hihi");

                boolean looksImportant = content.length() >= 8 && !isShortNoise;

                if (looksImportant && importantMessages.size() < 6) {
                    importantMessages.add(sender + ": " + truncate(content, 140));
                }
            }
        }

        StringBuilder builder = new StringBuilder();

        if ("tasks".equals(mode)) {
            builder.append("Việc cần làm:\n");
        } else if ("decisions".equals(mode)) {
            builder.append("Quyết định/Thống nhất:\n");
        } else {
            builder.append("Nội dung chính:\n");
        }

        builder.append("- Hệ thống đã phân tích ").append(count).append(" tin nhắn.\n");

        if (!senderCount.isEmpty()) {
            builder.append("- Người tham gia: ");
            builder.append(String.join(", ", senderCount.keySet()));
            builder.append(".\n");
        }

        if (!importantMessages.isEmpty()) {
            builder.append("- Một số nội dung đáng chú ý:\n");

            for (String item : importantMessages) {
                builder.append("  + ").append(item).append("\n");
            }
        } else {
            builder.append("- Chưa có nội dung quan trọng rõ ràng.\n");
        }

        builder.append("\nGhi chú:\n");
        builder.append("- Đây là bản tóm tắt tạm thời vì Gemini API đang lỗi.");

        if (errorMessage != null && !errorMessage.isBlank()) {
            builder.append("\n- Lỗi Gemini: ").append(truncate(errorMessage, 200));
        }

        return builder.toString();
    }

    private ChatSummaryDto toDto(ChatSummary summary, boolean cached) {
        return ChatSummaryDto.builder()
                .type(summary.getConversationType())
                .target(summary.getTarget())
                .period(summary.getPeriodType())
                .mode(summary.getSummaryMode())
                .fromTime(summary.getFromTime())
                .toTime(summary.getToTime())
                .limit(summary.getMessageLimit())
                .messageCount(summary.getMessageCount())
                .lastMessageId(summary.getLastMessageId())
                .summary(summary.getSummaryText())
                .cached(cached)
                .aiProvider(summary.getAiProvider())
                .createdAt(summary.getCreatedAt())
                .updatedAt(summary.getUpdatedAt())
                .build();
    }

    private String getLastMessageId(List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return null;
        }

        Message lastMessage = messages.get(messages.size() - 1);
        return lastMessage.getId();
    }

    private DateRange resolveDateRange(String period, String from, String to) {
        if ("latest".equals(period)) {
            return new DateRange(null, null);
        }

        if ("today".equals(period)) {
            LocalDate today = LocalDate.now(VIETNAM_ZONE);
            return new DateRange(
                    today.atStartOfDay(),
                    today.plusDays(1).atStartOfDay()
            );
        }

        LocalDate fromDate = parseDate(from, "Thiếu ngày bắt đầu.");
        LocalDate toDate = parseDate(to, "Thiếu ngày kết thúc.");

        if (toDate.isBefore(fromDate)) {
            throw new IllegalArgumentException("Ngày kết thúc không được nhỏ hơn ngày bắt đầu.");
        }

        return new DateRange(
                fromDate.atStartOfDay(),
                toDate.plusDays(1).atStartOfDay()
        );
    }

    private LocalDate parseDate(String value, String errorMessage) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(errorMessage);
        }

        try {
            return LocalDate.parse(value.trim());
        } catch (Exception ex) {
            throw new IllegalArgumentException("Ngày không hợp lệ. Định dạng đúng là yyyy-MM-dd.");
        }
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
        String period = rawPeriod == null ? "latest" : rawPeriod.trim().toLowerCase(Locale.ROOT);

        if ("latest".equals(period) || "recent".equals(period)) {
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

        if ("general".equals(mode) || "summary".equals(mode)) {
            return "general";
        }

        if ("tasks".equals(mode) || "task".equals(mode) || "todo".equals(mode)) {
            return "tasks";
        }

        if ("decisions".equals(mode) || "decision".equals(mode)) {
            return "decisions";
        }

        throw new IllegalArgumentException("Kiểu tóm tắt không hợp lệ.");
    }

    private int normalizeLimit(int limit) {
        if (limit <= 0) {
            return 100;
        }

        return Math.min(limit, 300);
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

    private record DateRange(LocalDateTime fromTime, LocalDateTime toTime) {
    }
}
