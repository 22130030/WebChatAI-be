package com.appchat.backend.websocket;

import com.appchat.backend.dto.ApiResponse;
import com.appchat.backend.dto.SocketMessageDto;
import com.appchat.backend.entity.*;
import com.appchat.backend.repository.GroupThemeRepository;
import com.appchat.backend.repository.MessageRepository;
import com.appchat.backend.repository.PendingConversationRepository;
import com.appchat.backend.repository.RoomMemberRepository;
import com.appchat.backend.repository.RoomRepository;
import com.appchat.backend.repository.UserRepository;
import com.appchat.backend.security.JwtUtil;
import com.appchat.backend.service.AiModerationService;
import com.appchat.backend.service.FriendshipService;
import com.appchat.backend.service.OnlineStatusService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import com.appchat.backend.repository.MessageReactionRepository;

@Component
@RequiredArgsConstructor
public class ChatWebSocketHandler extends TextWebSocketHandler {

    // Tiêm các lớp handler chuyên biệt
    final AccountWebSocketHandler accountWebSocketHandler;
    final MessageWebSocketHandler messageWebSocketHandler;
    final RoomWebSocketHandler roomWebSocketHandler;
    final ContactWebSocketHandler contactWebSocketHandler;
    final CallWebSocketHandler callWebSocketHandler;

    // Các repository & service dùng chung (visibility package-private)
    final ObjectMapper objectMapper;
    final UserRepository userRepository;
    final MessageRepository messageRepository;
    final RoomRepository roomRepository;
    final RoomMemberRepository roomMemberRepository;
    final PendingConversationRepository pendingConversationRepository;
    final GroupThemeRepository groupThemeRepository;
    final JwtUtil jwtUtil;
    final PasswordEncoder passwordEncoder;
    final MessageReactionRepository messageReactionRepository;
    final OnlineStatusService onlineStatusService;
    final AiModerationService aiModerationService;
    final FriendshipService friendshipService;
    
    final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();
    final Map<String, WebSocketSession> userSessions = new ConcurrentHashMap<>();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        sessions.put(session.getId(), session);

        String token = extractTokenFromQuery(session);

        if (token != null && jwtUtil.isTokenValid(token)) {
            String username = jwtUtil.getUsernameFromToken(token);
            markUserOnline(username, session);
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        sessions.remove(session.getId());

        String username = getUsernameFromSession(session);

        if (username != null) {
            WebSocketSession currentSession = userSessions.get(username);

            if (currentSession != null && currentSession.getId().equals(session.getId())) {
                userSessions.remove(username);
                markUserOffline(username);
            }
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        try {
            SocketMessageDto msg = objectMapper.readValue(
                    message.getPayload(),
                    SocketMessageDto.class
            );

            if ("onchat".equals(msg.getAction()) && msg.getData() != null) {
                handleEvent(
                        session,
                        msg.getData().getEvent(),
                        msg.getData().getData()
                );
            }
        } catch (Exception e) {
            System.err.println("Error parsing WebSocket message: " + e.getMessage());

            if (session.isOpen()) {
                sendMessage(
                        session,
                        "error",
                        "SYSTEM",
                        "Server xử lý dữ liệu thất bại",
                        null
                );
            }
        }
    }

    private void handleEvent(
            WebSocketSession session,
            String event,
            Map<String, Object> data
    ) throws Exception {

        if (!isPublicEvent(event) && getUsernameFromSession(session) == null) {
            sendMessage(
                    session,
                    "error",
                    "AUTH",
                    "Bạn cần đăng nhập trước khi thực hiện thao tác này",
                    null
            );
            return;
        }

        switch (event) {
            // ==========================================
            // TÀI KHOẢN & XÁC THỰC
            // ==========================================
            case "LOGIN":
                accountWebSocketHandler.handleLogin(this, session, data);
                break;

            case "REGISTER":
                accountWebSocketHandler.handleRegister(this, session, data);
                break;

            case "RE_LOGIN":
                accountWebSocketHandler.handleReLogin(this, session, data);
                break;

            case "LOGOUT":
                accountWebSocketHandler.handleLogout(this, session);
                break;

            case "GET_USER_LIST":
                accountWebSocketHandler.handleGetUserList(this, session);
                break;

            case "CHECK_USER_ONLINE":
                accountWebSocketHandler.handleCheckUserOnline(this, session, data);
                break;

            case "CHECK_USER_EXIST":
                accountWebSocketHandler.handleCheckUserExist(this, session, data);
                break;

            case "GET_PROFILE":
                accountWebSocketHandler.handleGetProfile(this, session, data);
                break;

            case "UPDATE_PROFILE":
                accountWebSocketHandler.handleUpdateProfile(this, session, data);
                break;

            // ==========================================
            // CUỘC GỌI & SIGNALING
            // ==========================================
            case "CALL_INVITE":
                callWebSocketHandler.handleCallInvite(this, session, data);
                break;

            case "CALL_ACCEPT":
                callWebSocketHandler.handleCallControl(this, session, data, "CALL_ACCEPTED", "CALL_ACCEPT", "Đã chấp nhận cuộc gọi");
                break;

            case "CALL_REJECT":
                callWebSocketHandler.handleCallControl(this, session, data, "CALL_REJECTED", "CALL_REJECT", "Đã từ chối cuộc gọi");
                break;

            case "CALL_CANCEL":
                callWebSocketHandler.handleCallControl(this, session, data, "CALL_CANCELED", "CALL_CANCEL", "Đã hủy cuộc gọi");
                break;

            case "CALL_END":
                callWebSocketHandler.handleCallControl(this, session, data, "CALL_ENDED", "CALL_END", "Cuộc gọi đã kết thúc");
                break;

            case "WEBRTC_OFFER":
            case "WEBRTC_ANSWER":
            case "WEBRTC_ICE_CANDIDATE":
                callWebSocketHandler.handleWebRtcSignal(this, session, event, data);
                break;

            // ==========================================
            // TIN NHẮN & CẢM XÚC
            // ==========================================
            case "SEND_CHAT":
                messageWebSocketHandler.handleSendChat(this, session, data);
                break;

            case "MARK_READ":
                messageWebSocketHandler.handleMarkRead(this, session, data);
                break;

            case "TYPING":
            case "STOP_TYPING":
                messageWebSocketHandler.handleTyping(this, session, event, data);
                break;

            case "RECALL_MESSAGE":
                messageWebSocketHandler.handleRecallMessage(this, session, data);
                break;

            case "EDIT_MESSAGE":
                messageWebSocketHandler.handleEditMessage(this, session, data);
                break;

            case "GET_PEOPLE_CHAT_MES":
                messageWebSocketHandler.handleGetPeopleChatMes(this, session, data);
                break;

            case "GET_ROOM_CHAT_MES":
                messageWebSocketHandler.handleGetRoomChatMes(this, session, data);
                break;

            case "REACT_MESSAGE":
                messageWebSocketHandler.handleReactMessage(this, session, data);
                break;

            // ==========================================
            // PHÒNG & THÀNH VIÊN
            // ==========================================
            case "CREATE_ROOM":
                roomWebSocketHandler.handleCreateRoom(this, session, data);
                break;

            case "JOIN_ROOM":
                roomWebSocketHandler.handleJoinRoom(this, session, data);
                break;

            case "ADD_USER_TO_ROOM":
            case "ADD_MEMBER":
                roomWebSocketHandler.handleAddUserToRoom(this, session, data);
                break;

            case "GET_ROOM_MEMBERS":
                roomWebSocketHandler.handleGetRoomMembers(this, session, data);
                break;

            case "SET_ROOM_DEPUTY":
            case "PROMOTE_ROOM_DEPUTY":
                roomWebSocketHandler.handleSetRoomDeputy(this, session, data);
                break;

            case "REMOVE_ROOM_DEPUTY":
            case "DEMOTE_ROOM_DEPUTY":
                roomWebSocketHandler.handleRemoveRoomDeputy(this, session, data);
                break;

            case "REMOVE_ROOM_MEMBER":
            case "KICK_ROOM_MEMBER":
                roomWebSocketHandler.handleRemoveRoomMember(this, session, data);
                break;

            case "RENAME_ROOM":
                roomWebSocketHandler.handleRenameRoom(this, session, data);
                break;

            case "LEAVE_ROOM":
                roomWebSocketHandler.handleLeaveRoom(this, session, data);
                break;

            // ==========================================
            // LIÊN HỆ & BẠN BÈ
            // ==========================================
            case "SEND_CONTACT_REQUEST":
            case "CREATE_PENDING_CONVERSATION":
            case "ADD_CONTACT":
                contactWebSocketHandler.handleSendContactRequest(this, session, data);
                break;

            case "GET_CONTACT_REQUESTS":
            case "GET_PENDING_CONVERSATIONS":
                contactWebSocketHandler.handleGetContactRequests(this, session);
                break;

            case "ACCEPT_CONTACT_REQUEST":
            case "ACCEPT_PENDING_CONVERSATION":
                contactWebSocketHandler.handleAcceptContactRequest(this, session, data);
                break;

            case "DELETE_CONTACT_REQUEST":
            case "REJECT_CONTACT_REQUEST":
            case "DELETE_PENDING_CONVERSATION":
                contactWebSocketHandler.handleDeleteContactRequest(this, session, data);
                break;

            case "REMOVE_CONTACT":
                contactWebSocketHandler.handleRemoveContact(this, session, data);
                break;

            default:
                sendMessage(session, "error", event, "Event không được hỗ trợ", null);
                break;
        }
    }

    // =========================================================
    // CÁC HÀM HỖ TRỢ TRUY CẬP TRONG CÙNG PACKAGE (PACKAGE-PRIVATE)
    // =========================================================

    boolean isPublicEvent(String event) {
        return "LOGIN".equals(event)
                || "REGISTER".equals(event)
                || "RE_LOGIN".equals(event);
    }

    String getUsernameFromSession(WebSocketSession session) {
        return userSessions.entrySet()
                .stream()
                .filter(entry ->
                        entry.getValue() != null
                                && entry.getValue().getId().equals(session.getId())
                )
                .map(Map.Entry::getKey)
                .findFirst()
                .orElse(null);
    }

    String extractTokenFromQuery(WebSocketSession session) {
        if (session.getUri() == null || session.getUri().getQuery() == null) {
            return null;
        }

        for (String param : session.getUri().getQuery().split("&")) {
            String[] pair = param.split("=", 2);

            if (pair.length == 2 && "token".equals(pair[0])) {
                return URLDecoder.decode(pair[1], StandardCharsets.UTF_8);
            }
        }

        return null;
    }

    String readString(Map<String, Object> data, String... keys) {
        if (data == null) {
            return null;
        }

        for (String key : keys) {
            Object value = data.get(key);

            if (value != null) {
                String result = String.valueOf(value).trim();

                if (!result.isEmpty()) {
                    return result;
                }
            }
        }

        return null;
    }

    Long readLong(Map<String, Object> data, String... keys) {
        if (data == null) {
            return null;
        }

        for (String key : keys) {
            Object value = data.get(key);

            if (value instanceof Number number) {
                return number.longValue();
            }

            if (value != null) {
                try {
                    return Long.parseLong(String.valueOf(value));
                } catch (NumberFormatException ignored) {
                }
            }
        }

        return null;
    }

    String normalizeChatType(Object rawType) {
        if (rawType == null) {
            return "people";
        }

        String type = String.valueOf(rawType).trim().toLowerCase();

        if ("room".equals(type) || "group".equals(type) || "1".equals(type)) {
            return "room";
        }

        return "people";
    }

    int extractPage(Map<String, Object> data) {
        Object pageObject = data != null ? data.get("page") : null;
        int page = 1;

        if (pageObject instanceof Number number) {
            page = number.intValue();
        } else if (pageObject instanceof String text) {
            try {
                page = Integer.parseInt(text);
            } catch (NumberFormatException ignored) {
                page = 1;
            }
        }

        return Math.max(page, 1);
    }

    int extractPageSize(Map<String, Object> data) {
        Object sizeObject = data != null ? data.get("size") : null;
        int size = 30;

        if (sizeObject instanceof Number number) {
            size = number.intValue();
        } else if (sizeObject instanceof String text) {
            try {
                size = Integer.parseInt(text);
            } catch (NumberFormatException ignored) {
                size = 30;
            }
        }

        return Math.min(Math.max(size, 1), 100);
    }

    Map<String, Object> buildPagedMessages(
            List<Map<String, Object>> messages,
            int page,
            int size,
            boolean hasMore
    ) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("messages", messages);
        response.put("page", page);
        response.put("size", size);
        response.put("hasMore", hasMore);
        return response;
    }

    Map<String, Object> buildAuthPayload(User user, String token) {
        Map<String, Object> payload = buildUserPayload(user);

        payload.put("token", token);
        payload.put("RE_LOGIN_CODE", token);
        payload.put("user", user.getUsername());

        return payload;
    }

    Map<String, Object> buildUserPayload(User user) {
        Map<String, Object> payload = new LinkedHashMap<>();

        payload.put("id", user.getId());
        payload.put("user", user.getUsername());
        payload.put("username", user.getUsername());
        payload.put("name", user.getUsername());
        payload.put("displayName", getEffectiveDisplayName(user));
        payload.put("avatar", user.getAvatar());
        payload.put("bio", user.getBio());
        payload.put("role", user.getRole());
        payload.put("status", isUserOnline(user.getUsername()) ? "ONLINE" : "OFFLINE");
        payload.put("createdAt", user.getCreatedAt() != null ? user.getCreatedAt().toString() : null);

        return payload;
    }

    String getEffectiveDisplayName(User user) {
        String displayName = user.getDisplayName();

        if (displayName == null || displayName.isBlank()) {
            return user.getUsername();
        }

        return displayName;
    }

    String cleanText(Object rawValue, int maxLength) {
        if (rawValue == null) {
            return null;
        }

        String value = String.valueOf(rawValue).trim();

        if (value.isEmpty()) {
            return null;
        }

        if (value.length() > maxLength) {
            return value.substring(0, maxLength);
        }

        return value;
    }

    Map<String, Object> buildRoomData(String roomName) {
        List<RoomMember> roomMembers = roomMemberRepository.findByRoomName(roomName)
                .stream()
                .sorted((left, right) -> {
                    int roleCompare = Integer.compare(
                            roleOrder(left.getRole()),
                            roleOrder(right.getRole())
                    );

                    if (roleCompare != 0) {
                        return roleCompare;
                    }

                    return String.CASE_INSENSITIVE_ORDER.compare(
                            left.getUsername(),
                            right.getUsername()
                    );
                })
                .toList();

        Optional<Room> roomOptional = roomRepository.findByName(roomName);

        String ownerUsername = roomMembers
                .stream()
                .filter(this::isRoomOwner)
                .map(RoomMember::getUsername)
                .findFirst()
                .orElseGet(() -> roomOptional
                        .map(Room::getOwnerUsername)
                        .filter(owner -> owner != null && !owner.isBlank())
                        .orElse(roomMembers.isEmpty() ? "" : roomMembers.get(0).getUsername())
                );

        roomOptional.ifPresent(room -> {
            if (ownerUsername != null
                    && !ownerUsername.isBlank()
                    && !ownerUsername.equals(room.getOwnerUsername())) {
                room.setOwnerUsername(ownerUsername);
                roomRepository.save(room);
            }
        });

        final String finalOwnerUsername = ownerUsername;

        List<Map<String, Object>> memberPayloads = roomMembers
                .stream()
                .map(member -> {
                    String memberUsername = member.getUsername();
                    String role = normalizeRoomRole(member.getRole());

                    if (memberUsername.equals(finalOwnerUsername)) {
                        role = RoomMember.ROLE_OWNER;
                    } else if (RoomMember.ROLE_OWNER.equals(role)) {
                        role = RoomMember.ROLE_MEMBER;
                    }

                    Map<String, Object> memberData = new LinkedHashMap<>();

                    memberData.put("name", memberUsername);
                    memberData.put("username", memberUsername);
                    memberData.put("role", role);
                    memberData.put("own", RoomMember.ROLE_OWNER.equals(role));
                    memberData.put("isOwner", RoomMember.ROLE_OWNER.equals(role));
                    memberData.put("isDeputy", RoomMember.ROLE_DEPUTY.equals(role));

                    userRepository.findByUsername(memberUsername).ifPresent(user -> {
                        memberData.put("displayName", getEffectiveDisplayName(user));
                        memberData.put("avatar", user.getAvatar());
                        memberData.put("bio", user.getBio());
                        memberData.put("status", isUserOnline(user.getUsername()) ? "ONLINE" : "OFFLINE");
                    });

                    return memberData;
                })
                .toList();

        Map<String, Object> roomData = new LinkedHashMap<>();

        roomData.put("name", roomName);
        roomData.put("displayName", roomName);
        roomData.put("avatar", null);
        roomData.put("type", 1);
        roomData.put("own", ownerUsername);
        roomData.put("ownerUsername", ownerUsername);
        roomData.put("userList", memberPayloads);
        roomData.put("members", memberPayloads);

        return roomData;
    }

    Map<String, Object> toClientMessage(Message message) {
        Map<String, Object> dto = new LinkedHashMap<>();

        String createdAt = message.getCreatedAt() != null
                ? message.getCreatedAt().toString()
                : LocalDateTime.now().toString();

        boolean recalled = Boolean.TRUE.equals(message.getRecalled());
        boolean edited = Boolean.TRUE.equals(message.getEdited());

        String displayContent = recalled
                ? "Tin nhắn đã được thu hồi"
                : message.getContent();

        dto.put("id", message.getId());
        dto.put("type", message.getType());
        dto.put("name", message.getSender());
        dto.put("mes", displayContent);
        dto.put("to", message.getReceiver());
        dto.put("createAt", createdAt);
        dto.put("sender", message.getSender());
        dto.put("receiver", message.getReceiver());
        dto.put("content", displayContent);
        dto.put("createdAt", createdAt);
        dto.put("recalled", recalled);
        dto.put("edited", edited);
        dto.put("status", recalled ? "recalled" : normalizeMessageStatus(message.getStatus()));
        dto.put("deliveredAt", message.getDeliveredAt() != null ? message.getDeliveredAt().toString() : null);
        dto.put("readAt", message.getReadAt() != null ? message.getReadAt().toString() : null);

        if (!recalled && displayContent != null && displayContent.startsWith("[CALL]")) {
            String callLogJson = displayContent.substring("[CALL]".length()).trim();

            if (!callLogJson.isEmpty()) {
                try {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> callLog = objectMapper.readValue(callLogJson, Map.class);
                    dto.put("callLog", callLog);
                } catch (Exception ignored) {
                    Map<String, Object> fallbackCallLog = new LinkedHashMap<>();
                    fallbackCallLog.put("callType", "audio");
                    fallbackCallLog.put("callStatus", "missed");
                    fallbackCallLog.put("durationSeconds", 0);
                    dto.put("callLog", fallbackCallLog);
                }
            }
        }

        List<Map<String, Object>> reactions = messageReactionRepository
                .findByMessageId(message.getId())
                .stream()
                .map(reaction -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("username", reaction.getUsername());
                    item.put("reaction", reaction.getReaction());
                    return item;
                })
                .toList();

        dto.put("reactions", reactions);
        return dto;
    }

    String normalizeMessageStatus(String status) {
        if (status == null || status.isBlank()) {
            return "sent";
        }

        return status.toLowerCase();
    }

    void sendRealtimeToUser(
            String username,
            String event,
            String message,
            Object payload
    ) throws Exception {

        WebSocketSession targetSession = userSessions.get(username);

        if (targetSession != null && targetSession.isOpen()) {
            sendMessage(targetSession, "success", event, message, payload);
        }
    }

    void refreshUserListForOnlineUser(String username) throws Exception {
        WebSocketSession targetSession = userSessions.get(username);

        if (targetSession != null && targetSession.isOpen()) {
            accountWebSocketHandler.handleGetUserList(this, targetSession);
        }
    }

    String normalizeRoomRole(String role) {
        if (role == null || role.isBlank()) {
            return RoomMember.ROLE_MEMBER;
        }

        String normalized = role.trim().toUpperCase();

        if (RoomMember.ROLE_OWNER.equals(normalized)
                || RoomMember.ROLE_DEPUTY.equals(normalized)
                || RoomMember.ROLE_MEMBER.equals(normalized)) {
            return normalized;
        }

        return RoomMember.ROLE_MEMBER;
    }

    boolean isRoomOwner(RoomMember member) {
        return member != null
                && RoomMember.ROLE_OWNER.equals(normalizeRoomRole(member.getRole()));
    }

    boolean isRoomDeputy(RoomMember member) {
        return member != null
                && RoomMember.ROLE_DEPUTY.equals(normalizeRoomRole(member.getRole()));
    }

    boolean isRoomManager(RoomMember member) {
        return isRoomOwner(member) || isRoomDeputy(member);
    }

    int roleOrder(String role) {
        String normalized = normalizeRoomRole(role);

        if (RoomMember.ROLE_OWNER.equals(normalized)) {
            return 0;
        }

        if (RoomMember.ROLE_DEPUTY.equals(normalized)) {
            return 1;
        }

        return 2;
    }

    void broadcastRoomData(
            String roomName,
            String exceptUsername,
            String event,
            String message,
            Object payload
    ) throws Exception {
        for (RoomMember member : roomMemberRepository.findByRoomName(roomName)) {
            if (exceptUsername != null && exceptUsername.equals(member.getUsername())) {
                continue;
            }

            sendRealtimeToUser(member.getUsername(), event, message, payload);
        }
    }

    void refreshUserListsInRoom(String roomName) throws Exception {
        for (RoomMember member : roomMemberRepository.findByRoomName(roomName)) {
            refreshUserListForOnlineUser(member.getUsername());
        }
    }

    void markUserOnline(String username, WebSocketSession session) {
        userSessions.put(username, session);
        onlineStatusService.markOnline(username, session.getId());

        broadcastUserStatus(username, true);
    }

    void markUserOffline(String username) {
        onlineStatusService.markOffline(username);

        broadcastUserStatus(username, false);
    }

    void broadcastUserStatus(String username, boolean online) {
        Map<String, Object> payload = Map.of(
                "user", username,
                "status", online
        );

        for (WebSocketSession session : userSessions.values()) {
            if (session != null && session.isOpen()) {
                try {
                    sendMessage(
                            session,
                            "success",
                            "CHECK_USER_ONLINE",
                            "Status updated",
                            payload
                    );
                } catch (Exception ignored) {
                }
            }
        }
    }

    void sendMessage(
            WebSocketSession session,
            String status,
            String event,
            String message,
            Object payload
    ) throws Exception {

        if (session == null || !session.isOpen()) {
            return;
        }

        ApiResponse<?> response = new ApiResponse<>(
                status,
                event,
                message,
                payload
        );

        String jsonMessage = objectMapper.writeValueAsString(response);

        synchronized (session) {
            if (session.isOpen()) {
                session.sendMessage(new TextMessage(jsonMessage));
            }
        }
    }

    boolean isUserOnline(String username) {
        WebSocketSession session = userSessions.get(username);
        return (session != null && session.isOpen()) || onlineStatusService.isOnline(username);
    }

    boolean canAccessMessage(Message message, String username) {
        if ("people".equals(message.getType())) {
            return username.equals(message.getSender()) || username.equals(message.getReceiver());
        }

        if ("room".equals(message.getType())) {
            return roomMemberRepository.existsByRoomNameAndUsername(message.getReceiver(), username);
        }

        return false;
    }

    void sendMessageToParticipantsExceptRequester(
            Message chatMessage,
            String requester,
            String event,
            String message,
            Object payload
    ) throws Exception {

        if ("people".equals(chatMessage.getType())) {
            String targetUser = requester.equals(chatMessage.getSender())
                    ? chatMessage.getReceiver()
                    : chatMessage.getSender();

            sendRealtimeToUser(targetUser, event, message, payload);
            return;
        }

        if ("room".equals(chatMessage.getType())) {
            for (RoomMember member : roomMemberRepository.findByRoomName(chatMessage.getReceiver())) {
                if (!requester.equals(member.getUsername())) {
                    sendRealtimeToUser(member.getUsername(), event, message, payload);
                }
            }
        }
    }

    Map<String, Object> toClientPendingConversation(PendingConversation pc) {
        Map<String, Object> data = new LinkedHashMap<>();

        data.put("id", pc.getId());
        data.put("from", pc.getFromUsername());
        data.put("fromUsername", pc.getFromUsername());
        data.put("to", pc.getToUsername());
        data.put("toUsername", pc.getToUsername());
        data.put("status", pc.getStatus());
        data.put("createdAt", pc.getCreatedAt() == null ? null : pc.getCreatedAt().toString());
        data.put("updatedAt", pc.getUpdatedAt() == null ? null : pc.getUpdatedAt().toString());

        userRepository.findByUsername(pc.getFromUsername()).ifPresent(u -> {
            data.put("fromAvatar", u.getAvatar());
            data.put("fromDisplayName", getEffectiveDisplayName(u));
        });

        userRepository.findByUsername(pc.getToUsername()).ifPresent(u -> {
            data.put("toAvatar", u.getAvatar());
            data.put("toDisplayName", getEffectiveDisplayName(u));
        });

        return data;
    }

    Map<String, Object> buildFriendshipPayload(String userA, String userB) {
        Map<String, Object> data = new LinkedHashMap<>();

        data.put("from", userA);
        data.put("fromUsername", userA);
        data.put("to", userB);
        data.put("toUsername", userB);
        data.put("status", "ACCEPTED");
        data.put("friendship", true);

        return data;
    }
}
