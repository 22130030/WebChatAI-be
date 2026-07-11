package com.appchat.backend.websocket;

import com.appchat.backend.entity.Message;
import com.appchat.backend.entity.MessageReaction;
import com.appchat.backend.entity.RoomMember;
import com.appchat.backend.repository.MessageReactionRepository;
import com.appchat.backend.repository.MessageRepository;
import com.appchat.backend.repository.RoomMemberRepository;
import com.appchat.backend.repository.RoomRepository;
import com.appchat.backend.repository.UserRepository;
import com.appchat.backend.service.AiModerationService;
import com.appchat.backend.service.FriendshipService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class MessageWebSocketHandler {

    private final MessageRepository messageRepository;
    private final UserRepository userRepository;
    private final RoomRepository roomRepository;
    private final RoomMemberRepository roomMemberRepository;
    private final FriendshipService friendshipService;
    private final MessageReactionRepository messageReactionRepository;
    private final AiModerationService aiModerationService;

    public void handleSendChat(
            ChatWebSocketHandler handler,
            WebSocketSession session,
            Map<String, Object> data
    ) throws Exception {

        String sender = handler.getUsernameFromSession(session);
        String type = handler.normalizeChatType(data != null ? data.get("type") : null);
        String to = handler.readString(data, "to", "receiver", "name");
        String messageContent = handler.readString(data, "mes", "content", "message");

        if (sender == null) {
            handler.sendMessage(
                    session,
                    "error",
                    "SEND_CHAT",
                    "Bạn cần đăng nhập trước khi gửi tin nhắn",
                    null
            );
            return;
        }

        if (to == null) {
            handler.sendMessage(
                    session,
                    "error",
                    "SEND_CHAT",
                    "Người nhận hoặc nhóm không hợp lệ",
                    null
            );
            return;
        }

        if (messageContent == null) {
            handler.sendMessage(
                    session,
                    "error",
                    "SEND_CHAT",
                    "Nội dung tin nhắn không được rỗng",
                    null
            );
            return;
        }

        AiModerationService.ModerationResult moderationResult =
                aiModerationService.moderate(messageContent);

        if (!moderationResult.allowed()) {
            Map<String, Object> moderationPayload = new LinkedHashMap<>();
            String failureMessage = buildModerationFailureMessage(moderationResult.flags());

            moderationPayload.put("rejected", true);
            moderationPayload.put("reason", "AI_MODERATION");
            moderationPayload.put("flags", moderationResult.flags());
            moderationPayload.put("report", moderationResult.report());

            handler.sendMessage(
                    session,
                    "error",
                    "SEND_CHAT",
                    failureMessage,
                    moderationPayload
            );
            return;
        }

        if ("room".equals(type)) {
            if (roomRepository.findByName(to).isEmpty()) {
                handler.sendMessage(session, "error", "SEND_CHAT", "Nhóm không tồn tại", null);
                return;
            }

            if (!roomMemberRepository.existsByRoomNameAndUsername(to, sender)) {
                handler.sendMessage(session, "error", "SEND_CHAT", "Bạn không thuộc nhóm này", null);
                return;
            }
        } else if (userRepository.findByUsername(to).isEmpty()) {
            handler.sendMessage(session, "error", "SEND_CHAT", "Người nhận không tồn tại", null);
            return;
        } else if (!friendshipService.areFriends(sender, to)) {
            handler.sendMessage(
                    session,
                    "error",
                    "SEND_CHAT",
                    "Bạn cần kết bạn trước khi gửi tin nhắn",
                    Map.of(
                            "rejected", true,
                            "reason", "CONTACT_REQUIRED"
                    )
            );
            return;
        }

        Message newMessage = Message.builder()
                .type(type)
                .sender(sender)
                .receiver(to)
                .content(messageContent)
                .recalled(false)
                .edited(false)
                .status("SENT")
                .build();

        Message savedMessage = messageRepository.save(newMessage);
        savedMessage = markDeliveredIfNeeded(handler, savedMessage);
        Map<String, Object> payload = handler.toClientMessage(savedMessage);

        if ("people".equals(type)) {
            handler.sendRealtimeToUser(to, "SEND_CHAT", "New message", payload);
        } else {
            for (RoomMember member : roomMemberRepository.findByRoomName(to)) {
                if (!sender.equals(member.getUsername())) {
                    handler.sendRealtimeToUser(
                            member.getUsername(),
                            "SEND_CHAT",
                            "New message",
                            payload
                    );
                }
            }
        }

        handler.sendMessage(session, "success", "SEND_CHAT", "Message sent", payload);
    }

    private Message markDeliveredIfNeeded(ChatWebSocketHandler handler, Message message) {
        boolean delivered = false;

        if ("people".equals(message.getType())) {
            delivered = handler.isUserOnline(message.getReceiver());
        } else if ("room".equals(message.getType())) {
            delivered = roomMemberRepository.findByRoomName(message.getReceiver())
                    .stream()
                    .anyMatch(member ->
                            !message.getSender().equals(member.getUsername())
                                    && handler.isUserOnline(member.getUsername())
                    );
        }

        if (delivered) {
            message.setStatus("DELIVERED");
            message.setDeliveredAt(LocalDateTime.now());
            return messageRepository.save(message);
        }

        return message;
    }

    private String buildModerationFailureMessage(List<String> flags) {
        if (flags == null || flags.isEmpty()) {
            return "Tin nhắn không hợp lệ nên không thể gửi.";
        }

        boolean spam = flags.stream().anyMatch(flag -> "spam".equalsIgnoreCase(flag));
        boolean toxic = flags.stream().anyMatch(flag ->
                "toxic".equalsIgnoreCase(flag)
                        || "severe_toxic".equalsIgnoreCase(flag)
                        || "obscene".equalsIgnoreCase(flag)
                        || "insult".equalsIgnoreCase(flag)
                        || "identity_hate".equalsIgnoreCase(flag)
        );
        boolean threat = flags.stream().anyMatch(flag -> "threat".equalsIgnoreCase(flag));

        if (threat) {
            return "Tin nhắn không hợp lệ vì có nội dung đe dọa hoặc bạo lực.";
        }

        if (toxic) {
            return "Tin nhắn không hợp lệ vì có nội dung thô tục hoặc xúc phạm.";
        }

        if (spam) {
            return "Tin nhắn không hợp lệ vì bị phát hiện là spam hoặc quảng cáo.";
        }

        return "Tin nhắn không hợp lệ nên không thể gửi.";
    }

    public void handleMarkRead(ChatWebSocketHandler handler, WebSocketSession session, Map<String, Object> data) throws Exception {
        String reader = handler.getUsernameFromSession(session);
        String messageId = handler.readString(data, "id", "messageId");

        if (reader == null || messageId == null) {
            handler.sendMessage(session, "error", "MARK_READ", "Invalid read receipt request", null);
            return;
        }

        Optional<Message> messageOptional = messageRepository.findById(messageId);

        if (messageOptional.isEmpty()) {
            handler.sendMessage(session, "error", "MARK_READ", "Message not found", null);
            return;
        }

        Message chatMessage = messageOptional.get();

        if (!handler.canAccessMessage(chatMessage, reader) || reader.equals(chatMessage.getSender())) {
            handler.sendMessage(session, "error", "MARK_READ", "Cannot mark this message as read", null);
            return;
        }

        chatMessage.setStatus("READ");
        chatMessage.setReadAt(LocalDateTime.now());

        if (chatMessage.getDeliveredAt() == null) {
            chatMessage.setDeliveredAt(chatMessage.getReadAt());
        }

        Message savedMessage = messageRepository.save(chatMessage);
        Map<String, Object> payload = handler.toClientMessage(savedMessage);

        handler.sendRealtimeToUser(savedMessage.getSender(), "MESSAGE_STATUS", "Message read", payload);
        handler.sendMessage(session, "success", "MARK_READ", "Message read", payload);
    }

    public void handleTyping(
            ChatWebSocketHandler handler,
            WebSocketSession session,
            String event,
            Map<String, Object> data
    ) throws Exception {
        String sender = handler.getUsernameFromSession(session);
        String type = handler.normalizeChatType(data != null ? data.get("type") : null);
        String to = handler.readString(data, "to", "receiver", "name");

        if (sender == null || to == null) {
            handler.sendMessage(session, "error", event, "Invalid typing event", null);
            return;
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", type);
        payload.put("from", sender);
        payload.put("name", sender);
        payload.put("to", to);
        payload.put("typing", "TYPING".equals(event));

        if ("room".equals(type)) {
            if (!roomMemberRepository.existsByRoomNameAndUsername(to, sender)) {
                handler.sendMessage(session, "error", event, "You are not a member of this room", null);
                return;
            }

            for (RoomMember member : roomMemberRepository.findByRoomName(to)) {
                if (!sender.equals(member.getUsername())) {
                    handler.sendRealtimeToUser(member.getUsername(), event, "Typing updated", payload);
                }
            }
        } else {
            handler.sendRealtimeToUser(to, event, "Typing updated", payload);
        }
    }

    public void handleRecallMessage(
            ChatWebSocketHandler handler,
            WebSocketSession session,
            Map<String, Object> data
    ) throws Exception {

        String requester = handler.getUsernameFromSession(session);
        String messageId = handler.readString(data, "id", "messageId");

        if (requester == null) {
            handler.sendMessage(session, "error", "RECALL_MESSAGE", "Bạn cần đăng nhập trước", null);
            return;
        }

        if (messageId == null) {
            handler.sendMessage(
                    session,
                    "error",
                    "RECALL_MESSAGE",
                    "Không xác định được tin nhắn cần thu hồi",
                    null
            );
            return;
        }

        var messageOptional = messageRepository.findById(messageId);

        if (messageOptional.isEmpty()) {
            handler.sendMessage(session, "error", "RECALL_MESSAGE", "Tin nhắn không tồn tại", null);
            return;
        }

        Message chatMessage = messageOptional.get();

        if (!requester.equals(chatMessage.getSender())) {
            handler.sendMessage(
                    session,
                    "error",
                    "RECALL_MESSAGE",
                    "Bạn chỉ có thể thu hồi tin nhắn của mình",
                    null
            );
            return;
        }

        if (Boolean.TRUE.equals(chatMessage.getRecalled())) {
            handler.sendMessage(
                    session,
                    "error",
                    "RECALL_MESSAGE",
                    "Tin nhắn này đã được thu hồi",
                    null
            );
            return;
        }

        chatMessage.setRecalled(true);
        chatMessage.setContent("Tin nhắn đã được thu hồi");

        Message savedMessage = messageRepository.save(chatMessage);
        Map<String, Object> payload = handler.toClientMessage(savedMessage);

        handler.sendMessageToParticipantsExceptRequester(
                savedMessage,
                requester,
                "RECALL_MESSAGE",
                "Tin nhắn đã được thu hồi",
                payload
        );

        handler.sendMessage(
                session,
                "success",
                "RECALL_MESSAGE",
                "Thu hồi tin nhắn thành công",
                payload
        );
    }

    public void handleEditMessage(
            ChatWebSocketHandler handler,
            WebSocketSession session,
            Map<String, Object> data
    ) throws Exception {

        String requester = handler.getUsernameFromSession(session);
        String messageId = handler.readString(data, "id", "messageId");
        String newContent = handler.readString(data, "content", "mes", "message", "newContent");

        if (requester == null) {
            handler.sendMessage(session, "error", "EDIT_MESSAGE", "Bạn cần đăng nhập trước", null);
            return;
        }

        if (messageId == null) {
            handler.sendMessage(
                    session,
                    "error",
                    "EDIT_MESSAGE",
                    "Không xác định được tin nhắn cần chỉnh sửa",
                    null
            );
            return;
        }

        if (newContent == null) {
            handler.sendMessage(
                    session,
                    "error",
                    "EDIT_MESSAGE",
                    "Nội dung mới không được để trống",
                    null
            );
            return;
        }

        var messageOptional = messageRepository.findById(messageId);

        if (messageOptional.isEmpty()) {
            handler.sendMessage(session, "error", "EDIT_MESSAGE", "Tin nhắn không tồn tại", null);
            return;
        }

        Message chatMessage = messageOptional.get();

        if (!requester.equals(chatMessage.getSender())) {
            handler.sendMessage(
                    session,
                    "error",
                    "EDIT_MESSAGE",
                    "Bạn chỉ có thể chỉnh sửa tin nhắn của mình",
                    null
            );
            return;
        }

        if (Boolean.TRUE.equals(chatMessage.getRecalled())) {
            handler.sendMessage(
                    session,
                    "error",
                    "EDIT_MESSAGE",
                    "Tin nhắn đã thu hồi thì không thể chỉnh sửa",
                    null
            );
            return;
        }

        if (!canEditContent(chatMessage.getContent())) {
            handler.sendMessage(
                    session,
                    "error",
                    "EDIT_MESSAGE",
                    "Chỉ hỗ trợ chỉnh sửa tin nhắn chữ hoặc emoji",
                    null
            );
            return;
        }

        if (!canEditContent(newContent)) {
            handler.sendMessage(
                    session,
                    "error",
                    "EDIT_MESSAGE",
                    "Nội dung chỉnh sửa chỉ được là chữ hoặc emoji",
                    null
            );
            return;
        }

        if ("room".equals(chatMessage.getType())
                && !roomMemberRepository.existsByRoomNameAndUsername(chatMessage.getReceiver(), requester)) {
            handler.sendMessage(session, "error", "EDIT_MESSAGE", "Bạn không còn thuộc nhóm này", null);
            return;
        }

        if (newContent.equals(chatMessage.getContent())) {
            handler.sendMessage(
                    session,
                    "error",
                    "EDIT_MESSAGE",
                    "Nội dung mới phải khác nội dung hiện tại",
                    null
            );
            return;
        }

        AiModerationService.ModerationResult moderationResult =
                aiModerationService.moderate(newContent);

        if (!moderationResult.allowed()) {
            Map<String, Object> moderationPayload = new LinkedHashMap<>();
            String failureMessage = buildModerationFailureMessage(moderationResult.flags());

            moderationPayload.put("rejected", true);
            moderationPayload.put("reason", "AI_MODERATION");
            moderationPayload.put("flags", moderationResult.flags());
            moderationPayload.put("report", moderationResult.report());

            handler.sendMessage(
                    session,
                    "error",
                    "EDIT_MESSAGE",
                    failureMessage,
                    moderationPayload
            );
            return;
        }

        chatMessage.setContent(newContent);
        chatMessage.setEdited(true);

        Message savedMessage = messageRepository.save(chatMessage);
        Map<String, Object> payload = handler.toClientMessage(savedMessage);

        handler.sendMessageToParticipantsExceptRequester(
                savedMessage,
                requester,
                "EDIT_MESSAGE",
                "Tin nhắn đã được chỉnh sửa",
                payload
        );

        handler.sendMessage(
                session,
                "success",
                "EDIT_MESSAGE",
                "Chỉnh sửa tin nhắn thành công",
                payload
        );
    }

    private boolean canEditContent(String content) {
        if (content == null || content.isBlank()) {
            return false;
        }

        String normalized = content.trim();

        return !normalized.startsWith("[IMAGE]")
                && !normalized.startsWith("[VIDEO]")
                && !normalized.startsWith("[FILE]")
                && !normalized.startsWith("[STICKER]")
                && !normalized.startsWith("STICKER:")
                && !normalized.startsWith("sticker:");
    }

    public void handleGetPeopleChatMes(
            ChatWebSocketHandler handler,
            WebSocketSession session,
            Map<String, Object> data
    ) throws Exception {

        String username = handler.getUsernameFromSession(session);
        String to = handler.readString(data, "name", "to", "user");

        if (username == null || to == null) {
            handler.sendMessage(
                    session,
                    "error",
                    "GET_PEOPLE_CHAT_MES",
                    "Thông tin người dùng không hợp lệ",
                    null
            );
            return;
        }

        int page = handler.extractPage(data);
        int size = handler.extractPageSize(data);

        List<Map<String, Object>> rawMessages = messageRepository
                .findPeopleMessages(
                        username,
                        to,
                        org.springframework.data.domain.PageRequest.of(page - 1, size + 1)
                )
                .stream()
                .map(handler::toClientMessage)
                .toList();
        boolean hasMore = rawMessages.size() > size;
        List<Map<String, Object>> chatMessages = hasMore
                ? rawMessages.subList(0, size)
                : rawMessages;
        Map<String, Object> responseData = handler.buildPagedMessages(chatMessages, page, size, hasMore);

        handler.sendMessage(
                session,
                "success",
                "GET_PEOPLE_CHAT_MES",
                "Messages retrieved",
                responseData
        );
    }

    public void handleGetRoomChatMes(
            ChatWebSocketHandler handler,
            WebSocketSession session,
            Map<String, Object> data
    ) throws Exception {

        String requester = handler.getUsernameFromSession(session);
        String roomName = handler.readString(data, "name", "roomName", "room");

        if (requester == null || roomName == null) {
            handler.sendMessage(
                    session,
                    "error",
                    "GET_ROOM_CHAT_MES",
                    "Thông tin nhóm không hợp lệ",
                    null
            );
            return;
        }

        if (roomRepository.findByName(roomName).isEmpty()) {
            handler.sendMessage(session, "error", "GET_ROOM_CHAT_MES", "Nhóm không tồn tại", null);
            return;
        }

        if (!roomMemberRepository.existsByRoomNameAndUsername(roomName, requester)) {
            handler.sendMessage(session, "error", "GET_ROOM_CHAT_MES", "Bạn không thuộc nhóm này", null);
            return;
        }

        int page = handler.extractPage(data);
        int size = handler.extractPageSize(data);

        List<Map<String, Object>> rawMessages = messageRepository
                .findRoomMessages(
                        roomName,
                        org.springframework.data.domain.PageRequest.of(page - 1, size + 1)
                )
                .stream()
                .map(handler::toClientMessage)
                .toList();
        boolean hasMore = rawMessages.size() > size;
        List<Map<String, Object>> chatMessages = hasMore
                ? rawMessages.subList(0, size)
                : rawMessages;

        Map<String, Object> responseData = handler.buildRoomData(roomName);
        responseData.put("chatData", chatMessages);
        responseData.putAll(handler.buildPagedMessages(chatMessages, page, size, hasMore));

        handler.sendMessage(
                session,
                "success",
                "GET_ROOM_CHAT_MES",
                "Messages retrieved",
                responseData
        );
    }

    public void handleReactMessage(
            ChatWebSocketHandler handler,
            WebSocketSession session,
            Map<String, Object> data
    ) throws Exception {

        String username = handler.getUsernameFromSession(session);
        String messageId = handler.readString(data, "messageId", "id");
        String reaction = handler.readString(data, "reaction", "emoji");

        if ("REMOVE".equalsIgnoreCase(reaction)) {
            reaction = null;
        }

        if (username == null) {
            handler.sendMessage(session, "error", "REACT_MESSAGE", "Bạn cần đăng nhập trước", null);
            return;
        }

        if (messageId == null) {
            handler.sendMessage(session, "error", "REACT_MESSAGE", "Không xác định được tin nhắn", null);
            return;
        }

        Optional<Message> messageOptional = messageRepository.findById(messageId);

        if (messageOptional.isEmpty()) {
            handler.sendMessage(session, "error", "REACT_MESSAGE", "Tin nhắn không tồn tại", null);
            return;
        }

        Message chatMessage = messageOptional.get();

        if (!handler.canAccessMessage(chatMessage, username)) {
            handler.sendMessage(session, "error", "REACT_MESSAGE", "Bạn không có quyền thả cảm xúc tin nhắn này", null);
            return;
        }

        if (reaction == null || reaction.isBlank() || "REMOVE".equalsIgnoreCase(reaction)) {
            messageReactionRepository
                    .findByMessageIdAndUsername(messageId, username)
                    .ifPresent(messageReactionRepository::delete);
        } else {
            MessageReaction messageReaction = messageReactionRepository
                    .findByMessageIdAndUsername(messageId, username)
                    .orElseGet(() -> MessageReaction.builder()
                            .messageId(messageId)
                            .username(username)
                            .build());

            messageReaction.setReaction(reaction);
            messageReactionRepository.save(messageReaction);
        }

        Map<String, Object> payload = handler.toClientMessage(chatMessage);

        handler.sendMessageToParticipantsExceptRequester(
                chatMessage,
                username,
                "REACT_MESSAGE",
                "Đã cập nhật cảm xúc tin nhắn",
                payload
        );

        handler.sendMessage(
                session,
                "success",
                "REACT_MESSAGE",
                "Đã cập nhật cảm xúc tin nhắn",
                payload
        );
    }
}
