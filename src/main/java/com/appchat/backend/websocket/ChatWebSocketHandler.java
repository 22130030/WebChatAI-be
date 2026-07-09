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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import com.appchat.backend.repository.MessageReactionRepository;
@Component
@RequiredArgsConstructor
public class ChatWebSocketHandler extends TextWebSocketHandler {

    private final ObjectMapper objectMapper;
    private final UserRepository userRepository;
    private final MessageRepository messageRepository;
    private final RoomRepository roomRepository;
    private final RoomMemberRepository roomMemberRepository;
    private final PendingConversationRepository pendingConversationRepository;
    private final GroupThemeRepository groupThemeRepository;
    private final JwtUtil jwtUtil;
    private final PasswordEncoder passwordEncoder;
    private final MessageReactionRepository messageReactionRepository;
    private final OnlineStatusService onlineStatusService;
    private final AiModerationService aiModerationService;
    private final FriendshipService friendshipService;
    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();
    private final Map<String, WebSocketSession> userSessions = new ConcurrentHashMap<>();

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
            case "LOGIN":
                handleLogin(session, data);
                break;

            case "REGISTER":
                handleRegister(session, data);
                break;

            case "RE_LOGIN":
                handleReLogin(session, data);
                break;

            case "LOGOUT":
                handleLogout(session);
                break;

            case "GET_USER_LIST":
                handleGetUserList(session);
                break;

            case "CHECK_USER_ONLINE":
                handleCheckUserOnline(session, data);
                break;

            case "CHECK_USER_EXIST":
                handleCheckUserExist(session, data);
                break;

            case "GET_PROFILE":
                handleGetProfile(session, data);
                break;

            case "UPDATE_PROFILE":
                handleUpdateProfile(session, data);
                break;

            case "CALL_INVITE":
                handleCallInvite(session, data);
                break;

            case "CALL_ACCEPT":
                handleCallControl(session, data, "CALL_ACCEPTED", "CALL_ACCEPT", "Đã chấp nhận cuộc gọi");
                break;

            case "CALL_REJECT":
                handleCallControl(session, data, "CALL_REJECTED", "CALL_REJECT", "Đã từ chối cuộc gọi");
                break;

            case "CALL_CANCEL":
                handleCallControl(session, data, "CALL_CANCELED", "CALL_CANCEL", "Đã hủy cuộc gọi");
                break;

            case "CALL_END":
                handleCallControl(session, data, "CALL_ENDED", "CALL_END", "Cuộc gọi đã kết thúc");
                break;

            case "WEBRTC_OFFER":
            case "WEBRTC_ANSWER":
            case "WEBRTC_ICE_CANDIDATE":
                handleWebRtcSignal(session, event, data);
                break;

            case "SEND_CHAT":
                handleSendChat(session, data);
                break;

            case "MARK_READ":
                handleMarkRead(session, data);
                break;

            case "TYPING":
            case "STOP_TYPING":
                handleTyping(session, event, data);
                break;

            case "RECALL_MESSAGE":
                handleRecallMessage(session, data);
                break;

            case "EDIT_MESSAGE":
                handleEditMessage(session, data);
                break;

            case "GET_PEOPLE_CHAT_MES":
                handleGetPeopleChatMes(session, data);
                break;

            case "CREATE_ROOM":
                handleCreateRoom(session, data);
                break;

            case "JOIN_ROOM":
                handleJoinRoom(session, data);
                break;

            case "ADD_USER_TO_ROOM":
            case "ADD_MEMBER":
                handleAddUserToRoom(session, data);
                break;

            case "GET_ROOM_MEMBERS":
                handleGetRoomMembers(session, data);
                break;
            case "SET_ROOM_DEPUTY":
            case "PROMOTE_ROOM_DEPUTY":
                handleSetRoomDeputy(session, data);
                break;

            case "REMOVE_ROOM_DEPUTY":
            case "DEMOTE_ROOM_DEPUTY":
                handleRemoveRoomDeputy(session, data);
                break;

            case "REMOVE_ROOM_MEMBER":
            case "KICK_ROOM_MEMBER":
                handleRemoveRoomMember(session, data);
                break;

            case "GET_ROOM_CHAT_MES":
                handleGetRoomChatMes(session, data);
                break;

            case "RENAME_ROOM":
                handleRenameRoom(session, data);
                break;

            case "LEAVE_ROOM":
                handleLeaveRoom(session, data);
                break;
            case "SEND_CONTACT_REQUEST":
            case "CREATE_PENDING_CONVERSATION":
            case "ADD_CONTACT":
                handleSendContactRequest(session, data);
                break;

            case "GET_CONTACT_REQUESTS":
            case "GET_PENDING_CONVERSATIONS":
                handleGetContactRequests(session);
                break;

            case "ACCEPT_CONTACT_REQUEST":
            case "ACCEPT_PENDING_CONVERSATION":
                handleAcceptContactRequest(session, data);
                break;

            case "DELETE_CONTACT_REQUEST":
            case "REJECT_CONTACT_REQUEST":
            case "DELETE_PENDING_CONVERSATION":
                handleDeleteContactRequest(session, data);
                break;
            case "REACT_MESSAGE":
                handleReactMessage(session, data);
                break;
            case "REMOVE_CONTACT":
                handleRemoveContact(session, data);
                break;

            default:
                sendMessage(session, "error", event, "Event không được hỗ trợ", null);
                break;
        }
    }


    private void handleRemoveContact(
            WebSocketSession session,
            Map<String, Object> data
    ) throws Exception {

        String currentUser = getUsernameFromSession(session);
        String otherUser = readString(data, "user", "username", "name", "to", "toUsername");

        if (currentUser == null) {
            sendMessage(session, "error", "REMOVE_CONTACT", "Bạn cần đăng nhập trước", null);
            return;
        }

        if (otherUser == null || currentUser.equals(otherUser)) {
            sendMessage(session, "error", "REMOVE_CONTACT", "Username không hợp lệ", null);
            return;
        }

        if (!friendshipService.areFriends(currentUser, otherUser)) {
            sendMessage(session, "error", "REMOVE_CONTACT", "Không tìm thấy liên hệ", null);
            return;
        }

        friendshipService.removeFriendship(currentUser, otherUser);

        Map<String, Object> payload = buildFriendshipPayload(currentUser, otherUser);

        sendMessage(session, "success", "REMOVE_CONTACT", "Đã xóa liên hệ", payload);
        sendRealtimeToUser(otherUser, "CONTACT_REMOVED", currentUser + " đã xóa liên hệ", payload);

        handleGetUserList(session);

        WebSocketSession otherSession = userSessions.get(otherUser);
        if (otherSession != null && otherSession.isOpen()) {
            handleGetUserList(otherSession);
        }
    }

    private void handleReactMessage(
            WebSocketSession session,
            Map<String, Object> data
    ) throws Exception {

        String username = getUsernameFromSession(session);
        String messageId = readString(data, "messageId", "id");
        String reaction = readString(data, "reaction", "emoji");

        if ("REMOVE".equalsIgnoreCase(reaction)) {
            reaction = null;
        }

        if (username == null) {
            sendMessage(session, "error", "REACT_MESSAGE", "Bạn cần đăng nhập trước", null);
            return;
        }

        if (messageId == null) {
            sendMessage(session, "error", "REACT_MESSAGE", "Không xác định được tin nhắn", null);
            return;
        }

        Optional<Message> messageOptional = messageRepository.findById(messageId);

        if (messageOptional.isEmpty()) {
            sendMessage(session, "error", "REACT_MESSAGE", "Tin nhắn không tồn tại", null);
            return;
        }

        Message chatMessage = messageOptional.get();

        if (!canAccessMessage(chatMessage, username)) {
            sendMessage(session, "error", "REACT_MESSAGE", "Bạn không có quyền thả cảm xúc tin nhắn này", null);
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

        Map<String, Object> payload = toClientMessage(chatMessage);

        sendMessageToParticipantsExceptRequester(
                chatMessage,
                username,
                "REACT_MESSAGE",
                "Đã cập nhật cảm xúc tin nhắn",
                payload
        );

        sendMessage(
                session,
                "success",
                "REACT_MESSAGE",
                "Đã cập nhật cảm xúc tin nhắn",
                payload
        );
    }

    private void handleSendContactRequest(
            WebSocketSession session,
            Map<String, Object> data
    ) throws Exception {

        String from = getUsernameFromSession(session);
        String to = readString(data, "to", "user", "username", "name", "receiver");

        if (from == null) {
            sendMessage(session, "error", "SEND_CONTACT_REQUEST", "Bạn cần đăng nhập trước", null);
            return;
        }

        if (to == null) {
            sendMessage(session, "error", "SEND_CONTACT_REQUEST", "Username cần liên hệ không hợp lệ", null);
            return;
        }

        if (from.equals(to)) {
            sendMessage(session, "error", "SEND_CONTACT_REQUEST", "Bạn không thể tự gửi lời mời cho chính mình", null);
            return;
        }

        if (userRepository.findByUsername(to).isEmpty()) {
            sendMessage(session, "error", "SEND_CONTACT_REQUEST", "Người dùng không tồn tại", null);
            return;
        }

        if (friendshipService.areFriends(from, to)) {
            sendMessage(session, "success", "SEND_CONTACT_REQUEST", "Hai người đã có trong danh sách liên hệ", buildFriendshipPayload(from, to));
            return;
        }

        Optional<PendingConversation> existingRequest = pendingConversationRepository.findBetweenUsers(from, to);

        if (existingRequest.isPresent()) {
            PendingConversation existing = existingRequest.get();
            Map<String, Object> payload = toClientPendingConversation(existing);

            if (from.equals(existing.getFromUsername())) {
                sendMessage(session, "success", "SEND_CONTACT_REQUEST", "Bạn đã gửi lời mời liên hệ trước đó", payload);
            } else {
                sendMessage(session, "success", "SEND_CONTACT_REQUEST", "Người này đã gửi lời mời cho bạn. Hãy chấp nhận lời mời.", payload);
            }

            return;
        }

        PendingConversation pc = pendingConversationRepository.save(
                PendingConversation.builder()
                        .fromUsername(from)
                        .toUsername(to)
                        .status("PENDING")
                        .build()
        );

        Map<String, Object> payload = toClientPendingConversation(pc);

        sendMessage(session, "success", "SEND_CONTACT_REQUEST", "Đã gửi lời mời liên hệ", payload);
        sendRealtimeToUser(to, "CONTACT_REQUEST_RECEIVED", "Bạn có lời mời liên hệ mới", payload);
    }

    private void handleGetContactRequests(WebSocketSession session) throws Exception {
        String username = getUsernameFromSession(session);

        if (username == null) {
            sendMessage(session, "error", "GET_CONTACT_REQUESTS", "Bạn cần đăng nhập trước", null);
            return;
        }

        Map<String, Object> payload = new LinkedHashMap<>();

        payload.put("incoming", pendingConversationRepository
                .findByToUsernameAndStatus(username, "PENDING")
                .stream()
                .map(this::toClientPendingConversation)
                .toList());

        payload.put("outgoing", pendingConversationRepository
                .findByFromUsernameAndStatus(username, "PENDING")
                .stream()
                .map(this::toClientPendingConversation)
                .toList());

        payload.put("accepted", friendshipService
                .findFriendships(username)
                .stream()
                .map(friendship -> buildFriendshipPayload(username, friendshipService.otherUser(friendship, username)))
                .toList());

        sendMessage(session, "success", "GET_CONTACT_REQUESTS", "Danh sách lời mời liên hệ", payload);
    }

    private void handleAcceptContactRequest(
            WebSocketSession session,
            Map<String, Object> data
    ) throws Exception {

        String currentUser = getUsernameFromSession(session);
        String from = readString(data, "from", "fromUsername", "user", "username", "name");

        if (currentUser == null) {
            sendMessage(session, "error", "ACCEPT_CONTACT_REQUEST", "Bạn cần đăng nhập trước", null);
            return;
        }

        if (from == null) {
            sendMessage(session, "error", "ACCEPT_CONTACT_REQUEST", "Người gửi lời mời không hợp lệ", null);
            return;
        }

        Optional<PendingConversation> pending =
                pendingConversationRepository.findByFromUsernameAndToUsername(from, currentUser);

        if (pending.isEmpty() || !"PENDING".equals(pending.get().getStatus())) {
            sendMessage(session, "error", "ACCEPT_CONTACT_REQUEST", "Không tìm thấy lời mời đang chờ", null);
            return;
        }

        PendingConversation pc = pending.get();
        friendshipService.createFriendship(from, currentUser);
        pendingConversationRepository.delete(pc);

        Map<String, Object> payload = buildFriendshipPayload(from, currentUser);

        sendMessage(session, "success", "ACCEPT_CONTACT_REQUEST", "Đã chấp nhận lời mời liên hệ", payload);
        sendRealtimeToUser(from, "CONTACT_REQUEST_ACCEPTED", currentUser + " đã chấp nhận lời mời liên hệ", payload);

        handleGetUserList(session);

        WebSocketSession fromSession = userSessions.get(from);
        if (fromSession != null && fromSession.isOpen()) {
            handleGetUserList(fromSession);
        }
    }

    private void handleDeleteContactRequest(
            WebSocketSession session,
            Map<String, Object> data
    ) throws Exception {

        String currentUser = getUsernameFromSession(session);
        String otherUser = readString(data, "user", "username", "name", "from", "fromUsername", "to", "toUsername");

        if (currentUser == null) {
            sendMessage(session, "error", "DELETE_CONTACT_REQUEST", "Bạn cần đăng nhập trước", null);
            return;
        }

        if (otherUser == null) {
            sendMessage(session, "error", "DELETE_CONTACT_REQUEST", "Username không hợp lệ", null);
            return;
        }

        Optional<PendingConversation> pending =
                pendingConversationRepository.findBetweenUsers(currentUser, otherUser);

        if (pending.isEmpty()) {
            sendMessage(session, "error", "DELETE_CONTACT_REQUEST", "Không tìm thấy lời mời liên hệ", null);
            return;
        }

        PendingConversation pc = pending.get();

        if (
                !currentUser.equals(pc.getFromUsername()) &&
                        !currentUser.equals(pc.getToUsername())
        ) {
            sendMessage(session, "error", "DELETE_CONTACT_REQUEST", "Bạn không có quyền xóa lời mời này", null);
            return;
        }

        Map<String, Object> payload = toClientPendingConversation(pc);

        pendingConversationRepository.delete(pc);

        sendMessage(session, "success", "DELETE_CONTACT_REQUEST", "Đã xóa lời mời liên hệ", payload);
        sendRealtimeToUser(otherUser, "CONTACT_REQUEST_DELETED", "Lời mời liên hệ đã bị xóa", payload);
    }

    private Map<String, Object> toClientPendingConversation(PendingConversation pc) {
        Map<String, Object> data = new LinkedHashMap<>();

        data.put("id", pc.getId());
        data.put("from", pc.getFromUsername());
        data.put("fromUsername", pc.getFromUsername());
        data.put("to", pc.getToUsername());
        data.put("toUsername", pc.getToUsername());
        data.put("status", pc.getStatus());
        data.put("createdAt", pc.getCreatedAt() == null ? null : pc.getCreatedAt().toString());
        data.put("updatedAt", pc.getUpdatedAt() == null ? null : pc.getUpdatedAt().toString());

        return data;
    }

    private Map<String, Object> buildFriendshipPayload(String userA, String userB) {
        Map<String, Object> data = new LinkedHashMap<>();

        data.put("from", userA);
        data.put("fromUsername", userA);
        data.put("to", userB);
        data.put("toUsername", userB);
        data.put("status", "ACCEPTED");
        data.put("friendship", true);

        return data;
    }

    private boolean isPublicEvent(String event) {
        return "LOGIN".equals(event)
                || "REGISTER".equals(event)
                || "RE_LOGIN".equals(event);
    }

    private String getUsernameFromSession(WebSocketSession session) {
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

    private String extractTokenFromQuery(WebSocketSession session) {
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

    private String readString(Map<String, Object> data, String... keys) {
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

    private Long readLong(Map<String, Object> data, String... keys) {
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
                    // Tiếp tục kiểm tra key khác.
                }
            }
        }

        return null;
    }

    private String normalizeChatType(Object rawType) {
        if (rawType == null) {
            return "people";
        }

        String type = String.valueOf(rawType).trim().toLowerCase();

        if ("room".equals(type) || "group".equals(type) || "1".equals(type)) {
            return "room";
        }

        return "people";
    }

    // =========================================================
    // ĐĂNG NHẬP / ĐĂNG KÝ / ĐĂNG XUẤT
    // =========================================================

    private void handleLogin(
            WebSocketSession session,
            Map<String, Object> data
    ) throws Exception {

        String username = readString(data, "user", "username");
        String password = readString(data, "pass", "password");

        if (username == null || password == null) {
            sendMessage(
                    session,
                    "error",
                    "LOGIN",
                    "Username và password không được để trống",
                    null
            );
            return;
        }

        var optionalUser = userRepository.findByUsername(username);

        if (optionalUser.isPresent() && isPasswordMatched(password, optionalUser.get())) {
            markUserOnline(username, session);

            String token = jwtUtil.generateToken(username);

            Map<String, Object> payload = buildAuthPayload(optionalUser.get(), token);

            sendMessage(session, "success", "LOGIN", "Login successful", payload);
        } else {
            sendMessage(session, "error", "LOGIN", "Invalid credentials", null);
        }
    }

    private boolean isPasswordMatched(String rawPassword, User user) {
        String savedPassword = user.getPassword();

        if (savedPassword != null
                && savedPassword.startsWith("$2")
                && passwordEncoder.matches(rawPassword, savedPassword)) {
            return true;
        }

        if (savedPassword != null && savedPassword.equals(rawPassword)) {
            user.setPassword(passwordEncoder.encode(rawPassword));
            userRepository.save(user);
            return true;
        }

        return false;
    }

    private void handleRegister(
            WebSocketSession session,
            Map<String, Object> data
    ) throws Exception {

        String username = readString(data, "user", "username");
        String password = readString(data, "pass", "password");

        if (username == null || password == null) {
            sendMessage(
                    session,
                    "error",
                    "REGISTER",
                    "Username và password không được để trống",
                    null
            );
            return;
        }

        if (userRepository.findByUsername(username).isPresent()) {
            sendMessage(session, "error", "REGISTER", "User already exists", null);
            return;
        }

        User newUser = User.builder()
                .username(username)
                .password(passwordEncoder.encode(password))
                .displayName(username)
                .status("OFFLINE")
                .build();

        userRepository.save(newUser);

        sendMessage(session, "success", "REGISTER", "Registration successful", null);
    }

    private void handleReLogin(
            WebSocketSession session,
            Map<String, Object> data
    ) throws Exception {

        String username = readString(data, "user", "username");
        String token = readString(data, "code", "token");

        if (username == null || token == null || !jwtUtil.isTokenValid(token)) {
            sendMessage(session, "error", "RE_LOGIN", "Invalid re-login credentials", null);
            return;
        }

        String usernameFromToken = jwtUtil.getUsernameFromToken(token);

        if (!username.equals(usernameFromToken)) {
            sendMessage(session, "error", "RE_LOGIN", "Invalid re-login credentials", null);
            return;
        }

        var optionalUser = userRepository.findByUsername(username);

        if (optionalUser.isEmpty()) {
            sendMessage(session, "error", "RE_LOGIN", "Invalid re-login credentials", null);
            return;
        }

        User user = optionalUser.get();

        markUserOnline(username, session);

        Map<String, Object> payload = buildAuthPayload(user, token);

        sendMessage(session, "success", "RE_LOGIN", "Re-login successful", payload);
    }

    private void handleLogout(WebSocketSession session) throws Exception {
        String username = getUsernameFromSession(session);

        if (username != null) {
            WebSocketSession currentSession = userSessions.get(username);

            if (currentSession != null && currentSession.getId().equals(session.getId())) {
                userSessions.remove(username);
                markUserOffline(username);
            }
        }

        sendMessage(
                session,
                "success",
                "LOGOUT",
                "Logout successful",
                username == null ? null : Map.of("user", username)
        );
    }

    // =========================================================
    // DANH SÁCH USER / ONLINE STATUS
    // =========================================================

    private void handleGetUserList(WebSocketSession session) throws Exception {
        String username = getUsernameFromSession(session);

        if (username == null) {
            return;
        }

        List<Map<String, Object>> responseList = new ArrayList<>();

        for (var friendship : friendshipService.findFriendships(username)) {
            String friendUsername = friendshipService.otherUser(friendship, username);

            userRepository.findByUsername(friendUsername).ifPresent(user -> {
                Map<String, Object> userData = new LinkedHashMap<>();

                userData.put("name", user.getUsername());
                userData.put("username", user.getUsername());
                userData.put("displayName", getEffectiveDisplayName(user));
                userData.put("avatar", user.getAvatar());
                userData.put("bio", user.getBio());
                userData.put("type", 0);

                boolean online = isUserOnline(user.getUsername());
                userData.put("status", online ? "ONLINE" : "OFFLINE");
                userData.put("online", online);

                Optional<Message> lastMessage =
                        messageRepository.findTopByTypeAndSenderAndReceiverOrTypeAndSenderAndReceiverOrderByCreatedAtDesc(
                                "people", username, friendUsername,
                                "people", friendUsername, username
                        );

                userData.put(
                        "actionTime",
                        lastMessage
                                .map(message -> message.getCreatedAt().toString())
                                .orElse(friendship.getCreatedAt() != null
                                        ? friendship.getCreatedAt().toString()
                                        : LocalDateTime.MIN.toString())
                );

                lastMessage.ifPresent(message -> {
                    userData.put("lastMessage", message.getContent());
                    userData.put("lastSender", message.getSender());
                });

                responseList.add(userData);
            });
        }

        for (RoomMember roomMember : roomMemberRepository.findByUsername(username)) {
            String roomName = roomMember.getRoomName();

            if (roomRepository.findByName(roomName).isEmpty()) {
                continue;
            }

            Map<String, Object> roomData = buildRoomData(roomName);
            roomData.put("currentUserRole", normalizeRoomRole(roomMember.getRole()));

            Optional<Message> lastRoomMessage =
                    messageRepository.findTopByTypeAndReceiverOrderByCreatedAtDesc(
                            "room",
                            roomName
                    );

            roomData.put("name", roomName);
            roomData.put("displayName", roomName);
            roomData.put("avatar", null);
            roomData.put("type", 1);

            roomData.put(
                    "actionTime",
                    lastRoomMessage
                            .map(message -> message.getCreatedAt().toString())
                            .orElse(LocalDateTime.MIN.toString())
            );

            lastRoomMessage.ifPresent(message -> {
                roomData.put("lastMessage", message.getContent());
                roomData.put("lastSender", message.getSender());
            });

            responseList.add(roomData);
        }


        sendMessage(
                session,
                "success",
                "GET_USER_LIST",
                "User list retrieved",
                responseList
        );
    }

    private void handleCheckUserOnline(
            WebSocketSession session,
            Map<String, Object> data
    ) throws Exception {

        String usernameToCheck = readString(data, "user", "username");

        if (usernameToCheck == null) {
            sendMessage(
                    session,
                    "error",
                    "CHECK_USER_ONLINE",
                    "Username không hợp lệ",
                    null
            );
            return;
        }

        boolean online = isUserOnline(usernameToCheck);

        sendMessage(
                session,
                "success",
                "CHECK_USER_ONLINE",
                "Status checked",
                Map.of(
                        "user", usernameToCheck,
                        "status", online
                )
        );
    }

    private void handleCheckUserExist(
            WebSocketSession session,
            Map<String, Object> data
    ) throws Exception {

        String usernameToCheck = readString(data, "user", "username");

        if (usernameToCheck == null) {
            sendMessage(
                    session,
                    "error",
                    "CHECK_USER_EXIST",
                    "Username không hợp lệ",
                    null
            );
            return;
        }

        var optionalUser = userRepository.findByUsername(usernameToCheck);
        boolean exists = optionalUser.isPresent();

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("user", usernameToCheck);
        payload.put("username", usernameToCheck);
        optionalUser.ifPresent(user -> payload.putAll(buildUserPayload(user)));
        payload.put("status", exists);
        payload.put("exists", exists);

        sendMessage(
                session,
                exists ? "success" : "error",
                "CHECK_USER_EXIST",
                exists ? "User exists" : "User not found",
                payload
        );
    }

    private void handleGetProfile(
            WebSocketSession session,
            Map<String, Object> data
    ) throws Exception {

        String requester = getUsernameFromSession(session);
        String usernameToView = readString(data, "user", "username", "name");

        if (requester == null) {
            sendMessage(session, "error", "GET_PROFILE", "Bạn cần đăng nhập trước", null);
            return;
        }

        if (usernameToView == null) {
            usernameToView = requester;
        }

        var optionalUser = userRepository.findByUsername(usernameToView);

        if (optionalUser.isEmpty()) {
            sendMessage(session, "error", "GET_PROFILE", "Không tìm thấy người dùng", null);
            return;
        }

        sendMessage(
                session,
                "success",
                "GET_PROFILE",
                "Thông tin cá nhân",
                buildUserPayload(optionalUser.get())
        );
    }

    private void handleUpdateProfile(
            WebSocketSession session,
            Map<String, Object> data
    ) throws Exception {

        String username = getUsernameFromSession(session);

        if (username == null) {
            sendMessage(session, "error", "UPDATE_PROFILE", "Bạn cần đăng nhập trước", null);
            return;
        }

        var optionalUser = userRepository.findByUsername(username);

        if (optionalUser.isEmpty()) {
            sendMessage(session, "error", "UPDATE_PROFILE", "Không tìm thấy người dùng", null);
            return;
        }

        User user = optionalUser.get();

        if (data != null && data.containsKey("displayName")) {
            user.setDisplayName(cleanText(data.get("displayName"), 100));
        }

        if (data != null && (data.containsKey("avatar") || data.containsKey("avatarUrl"))) {
            Object rawAvatar = data.containsKey("avatar") ? data.get("avatar") : data.get("avatarUrl");
            user.setAvatar(cleanText(rawAvatar, 1000));
        }

        if (data != null && data.containsKey("bio")) {
            user.setBio(cleanText(data.get("bio"), 500));
        }

        User savedUser = userRepository.save(user);
        Map<String, Object> payload = buildUserPayload(savedUser);

        sendMessage(
                session,
                "success",
                "UPDATE_PROFILE",
                "Cập nhật thông tin cá nhân thành công",
                payload
        );

        for (var friendship : friendshipService.findFriendships(username)) {
            String friendUsername = friendshipService.otherUser(friendship, username);

            sendRealtimeToUser(
                    friendUsername,
                    "PROFILE_UPDATED",
                    username + " đã cập nhật thông tin cá nhân",
                    payload
            );

            refreshUserListForOnlineUser(friendUsername);
        }

        refreshUserListForOnlineUser(username);
    }


    // =========================================================
    // WEBRTC VOICE / VIDEO CALL SIGNALING
    // =========================================================

    private void handleCallInvite(
            WebSocketSession session,
            Map<String, Object> data
    ) throws Exception {

        String from = getUsernameFromSession(session);
        String to = readString(data, "to", "receiver", "user", "username", "name");
        String roomName = readString(data, "roomName", "room", "groupName");
        String callType = normalizeCallType(readString(data, "callType", "type"));
        String callId = readString(data, "callId", "id");
        boolean isGroupCall = isGroupCallPayload(data, roomName);

        if (from == null) {
            sendMessage(session, "error", "CALL_INVITE", "Bạn cần đăng nhập trước khi gọi", null);
            return;
        }

        if (callId == null) {
            callId = from + "_" + (isGroupCall ? roomName : to) + "_" + System.currentTimeMillis();
        }

        if (isGroupCall) {
            if (roomName == null) {
                roomName = to;
            }

            if (roomName == null || roomRepository.findByName(roomName).isEmpty()) {
                sendMessage(session, "error", "CALL_INVITE", "Nhóm không tồn tại", null);
                return;
            }

            if (!roomMemberRepository.existsByRoomNameAndUsername(roomName, from)) {
                sendMessage(session, "error", "CALL_INVITE", "Bạn không thuộc nhóm này", null);
                return;
            }

            List<String> participants = roomMemberRepository.findByRoomName(roomName)
                    .stream()
                    .map(RoomMember::getUsername)
                    .toList();

            Map<String, Object> payload = buildCallPayload(from, roomName, callId, callType, data);
            payload.put("isGroupCall", true);
            payload.put("roomName", roomName);
            payload.put("chatType", "room");
            payload.put("participants", participants);

            boolean hasOnlineReceiver = false;

            for (String memberUsername : participants) {
                if (from.equals(memberUsername)) {
                    continue;
                }

                WebSocketSession targetSession = userSessions.get(memberUsername);
                if (targetSession != null && targetSession.isOpen()) {
                    hasOnlineReceiver = true;
                    sendRealtimeToUser(memberUsername, "CALL_INVITE", "Có cuộc gọi nhóm đến", payload);
                }
            }

            if (!hasOnlineReceiver) {
                sendMessage(session, "error", "CALL_INVITE", "Không có thành viên nào trong nhóm đang online", payload);
                return;
            }

            sendMessage(session, "success", "CALL_INVITE_SENT", "Đã gửi lời mời gọi nhóm", payload);
            return;
        }

        if (to == null || to.equals(from)) {
            sendMessage(session, "error", "CALL_INVITE", "Người nhận cuộc gọi không hợp lệ", null);
            return;
        }

        if (userRepository.findByUsername(to).isEmpty()) {
            sendMessage(session, "error", "CALL_INVITE", "Người dùng không tồn tại", null);
            return;
        }

        Map<String, Object> payload = buildCallPayload(from, to, callId, callType, data);
        payload.put("isGroupCall", false);
        payload.put("chatType", "people");

        WebSocketSession targetSession = userSessions.get(to);

        if (targetSession == null || !targetSession.isOpen()) {
            sendMessage(session, "error", "CALL_INVITE", "Người dùng hiện đang offline", payload);
            return;
        }

        sendRealtimeToUser(to, "CALL_INVITE", "Có cuộc gọi đến", payload);
        sendMessage(session, "success", "CALL_INVITE_SENT", "Đã gửi lời mời gọi", payload);
    }

    private void handleCallControl(
            WebSocketSession session,
            Map<String, Object> data,
            String forwardEvent,
            String ackEvent,
            String defaultMessage
    ) throws Exception {

        String from = getUsernameFromSession(session);
        String to = readString(data, "to", "receiver", "user", "username", "name");
        String roomName = readString(data, "roomName", "room", "groupName");
        String callType = normalizeCallType(readString(data, "callType", "type"));
        String callId = readString(data, "callId", "id");
        boolean isGroupCall = isGroupCallPayload(data, roomName);

        if (from == null) {
            sendMessage(session, "error", ackEvent, "Bạn cần đăng nhập trước", null);
            return;
        }

        if (callId == null) {
            sendMessage(session, "error", ackEvent, "Dữ liệu cuộc gọi không hợp lệ", null);
            return;
        }

        if (isGroupCall) {
            if (roomName == null) {
                roomName = to;
            }

            if (roomName == null || roomRepository.findByName(roomName).isEmpty()) {
                sendMessage(session, "error", ackEvent, "Nhóm không tồn tại", null);
                return;
            }

            if (!roomMemberRepository.existsByRoomNameAndUsername(roomName, from)) {
                sendMessage(session, "error", ackEvent, "Bạn không thuộc nhóm này", null);
                return;
            }

            Map<String, Object> payload = buildCallPayload(from, roomName, callId, callType, data);
            payload.put("isGroupCall", true);
            payload.put("roomName", roomName);
            payload.put("chatType", "room");

            Object reason = data == null ? null : data.get("reason");
            if (reason != null) {
                payload.put("reason", String.valueOf(reason));
            }

            broadcastRoomData(roomName, from, forwardEvent, defaultMessage, payload);
            sendMessage(session, "success", ackEvent, defaultMessage, payload);

            if (shouldCreateCallLog(forwardEvent)) {
                createAndBroadcastCallLog(session, from, roomName, callId, callType, forwardEvent, data, true);
            }
            return;
        }

        if (to == null) {
            sendMessage(session, "error", ackEvent, "Dữ liệu cuộc gọi không hợp lệ", null);
            return;
        }

        Map<String, Object> payload = buildCallPayload(from, to, callId, callType, data);
        payload.put("isGroupCall", false);
        payload.put("chatType", "people");

        Object reason = data == null ? null : data.get("reason");
        if (reason != null) {
            payload.put("reason", String.valueOf(reason));
        }

        sendRealtimeToUser(to, forwardEvent, defaultMessage, payload);
        sendMessage(session, "success", ackEvent, defaultMessage, payload);

        if (shouldCreateCallLog(forwardEvent)) {
            createAndBroadcastCallLog(session, from, to, callId, callType, forwardEvent, data, false);
        }
    }

    private boolean shouldCreateCallLog(String event) {
        return "CALL_REJECTED".equals(event)
                || "CALL_CANCELED".equals(event)
                || "CALL_ENDED".equals(event);
    }

    private void createAndBroadcastCallLog(
            WebSocketSession requesterSession,
            String from,
            String to,
            String callId,
            String callType,
            String event,
            Map<String, Object> data,
            boolean isGroupCall
    ) throws Exception {

        if (from == null || to == null || callId == null) {
            return;
        }

        String normalizedCallType = normalizeCallType(callType);
        String reason = readString(data, "reason");
        Long durationSeconds = readLong(data, "durationSeconds", "duration", "durationInSeconds");

        if (durationSeconds == null || durationSeconds < 0) {
            durationSeconds = 0L;
        }

        String callStatus = resolveCallStatus(event, reason, durationSeconds);
        String caller = readString(data, "caller", "callerUsername");
        String receiver = readString(data, "receiver", "receiverUsername");

        if (caller == null || receiver == null) {
            if (isGroupCall) {
                caller = readString(data, "caller", "callerUsername");
                if (caller == null) {
                    caller = from;
                }
                receiver = to;
            } else if ("CALL_REJECTED".equals(event)) {
                caller = to;
                receiver = from;
            } else {
                caller = from;
                receiver = to;
            }
        }

        Map<String, Object> callLog = new LinkedHashMap<>();
        callLog.put("callId", callId);
        callLog.put("callType", normalizedCallType);
        callLog.put("type", normalizedCallType);
        callLog.put("callStatus", callStatus);
        callLog.put("status", callStatus);
        callLog.put("durationSeconds", durationSeconds);
        callLog.put("caller", caller);
        callLog.put("receiver", receiver);
        callLog.put("endedBy", from);
        callLog.put("reason", reason == null ? "" : reason);
        callLog.put("isGroupCall", isGroupCall);
        if (isGroupCall) {
            callLog.put("roomName", to);
        }

        String content = "[CALL]" + objectMapper.writeValueAsString(callLog);

        Message savedMessage = messageRepository.save(
                Message.builder()
                        .type(isGroupCall ? "room" : "people")
                        .sender(from)
                        .receiver(to)
                        .content(content)
                        .status("SENT")
                        .build()
        );

        Map<String, Object> messagePayload = toClientMessage(savedMessage);

        sendMessage(requesterSession, "success", "SEND_CHAT", "New message", messagePayload);

        if (isGroupCall) {
            broadcastRoomData(to, from, "SEND_CHAT", "New message", messagePayload);
        } else {
            sendRealtimeToUser(to, "SEND_CHAT", "New message", messagePayload);
        }
    }

    private String resolveCallStatus(String event, String reason, Long durationSeconds) {
        if ("CALL_ENDED".equals(event)) {
            return durationSeconds != null && durationSeconds > 0 ? "completed" : "missed";
        }

        if ("CALL_CANCELED".equals(event)) {
            return "missed";
        }

        String normalizedReason = reason == null ? "" : reason.toLowerCase();

        if (normalizedReason.contains("bận") || normalizedReason.contains("busy")) {
            return "busy";
        }

        if (normalizedReason.contains("không nghe")
                || normalizedReason.contains("khong nghe")
                || normalizedReason.contains("miss")) {
            return "missed";
        }

        return "rejected";
    }

    private void handleWebRtcSignal(
            WebSocketSession session,
            String event,
            Map<String, Object> data
    ) throws Exception {

        String from = getUsernameFromSession(session);
        String to = readString(data, "to", "receiver", "user", "username", "name");
        String roomName = readString(data, "roomName", "room", "groupName");
        String callType = normalizeCallType(readString(data, "callType", "type"));
        String callId = readString(data, "callId", "id");
        boolean isGroupCall = isGroupCallPayload(data, roomName);

        if (from == null) {
            sendMessage(session, "error", event, "Bạn cần đăng nhập trước", null);
            return;
        }

        if (to == null || callId == null) {
            sendMessage(session, "error", event, "Dữ liệu WebRTC không hợp lệ", null);
            return;
        }

        if (isGroupCall) {
            if (roomName == null || roomRepository.findByName(roomName).isEmpty()) {
                sendMessage(session, "error", event, "Nhóm không tồn tại", null);
                return;
            }

            if (!roomMemberRepository.existsByRoomNameAndUsername(roomName, from)
                    || !roomMemberRepository.existsByRoomNameAndUsername(roomName, to)) {
                sendMessage(session, "error", event, "Thành viên không thuộc nhóm này", null);
                return;
            }
        }

        Map<String, Object> payload = buildCallPayload(from, to, callId, callType, data);
        payload.put("isGroupCall", isGroupCall);
        payload.put("chatType", isGroupCall ? "room" : "people");
        if (roomName != null) {
            payload.put("roomName", roomName);
        }

        if (data != null) {
            if (data.containsKey("offer")) {
                payload.put("offer", data.get("offer"));
            }

            if (data.containsKey("answer")) {
                payload.put("answer", data.get("answer"));
            }

            if (data.containsKey("candidate")) {
                payload.put("candidate", data.get("candidate"));
            }
        }

        WebSocketSession targetSession = userSessions.get(to);

        if (targetSession == null || !targetSession.isOpen()) {
            sendMessage(session, "error", event, "Người dùng hiện đang offline", payload);
            return;
        }

        sendRealtimeToUser(to, event, "WebRTC signaling", payload);
    }

    private Map<String, Object> buildCallPayload(
            String from,
            String to,
            String callId,
            String callType,
            Map<String, Object> rawData
    ) {
        Map<String, Object> payload = new LinkedHashMap<>();
        String normalizedCallType = normalizeCallType(callType);

        payload.put("from", from);
        payload.put("fromUsername", from);
        payload.put("to", to);
        payload.put("toUsername", to);
        payload.put("callId", callId);
        payload.put("id", callId);
        payload.put("callType", normalizedCallType);
        payload.put("type", normalizedCallType);
        payload.put("createdAt", LocalDateTime.now().toString());

        if (rawData != null) {
            if (rawData.containsKey("roomName")) {
                payload.put("roomName", rawData.get("roomName"));
            }
            if (rawData.containsKey("chatType")) {
                payload.put("chatType", rawData.get("chatType"));
            }
            if (rawData.containsKey("isGroupCall")) {
                payload.put("isGroupCall", rawData.get("isGroupCall"));
            }
        }

        return payload;
    }

    private String normalizeCallType(String callType) {
        return "video".equalsIgnoreCase(callType) ? "video" : "audio";
    }

    private boolean isGroupCallPayload(Map<String, Object> data, String roomName) {
        if (roomName != null && !roomName.isBlank()) {
            return true;
        }

        if (data == null) {
            return false;
        }

        Object isGroupCall = data.get("isGroupCall");
        if (isGroupCall instanceof Boolean bool) {
            return bool;
        }
        if (isGroupCall != null && "true".equalsIgnoreCase(String.valueOf(isGroupCall))) {
            return true;
        }

        String chatType = readString(data, "chatType");
        return "room".equalsIgnoreCase(chatType) || "group".equalsIgnoreCase(chatType);
    }

    // =========================================================
    // CHAT 1-1 / CHAT NHÓM
    // =========================================================

    private void handleSendChat(
            WebSocketSession session,
            Map<String, Object> data
    ) throws Exception {

        String sender = getUsernameFromSession(session);
        String type = normalizeChatType(data != null ? data.get("type") : null);
        String to = readString(data, "to", "receiver", "name");
        String messageContent = readString(data, "mes", "content", "message");

        if (sender == null) {
            sendMessage(
                    session,
                    "error",
                    "SEND_CHAT",
                    "Bạn cần đăng nhập trước khi gửi tin nhắn",
                    null
            );
            return;
        }

        if (to == null) {
            sendMessage(
                    session,
                    "error",
                    "SEND_CHAT",
                    "Người nhận hoặc nhóm không hợp lệ",
                    null
            );
            return;
        }

        if (messageContent == null) {
            sendMessage(
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

            sendMessage(
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
                sendMessage(session, "error", "SEND_CHAT", "Nhóm không tồn tại", null);
                return;
            }

            if (!roomMemberRepository.existsByRoomNameAndUsername(to, sender)) {
                sendMessage(session, "error", "SEND_CHAT", "Bạn không thuộc nhóm này", null);
                return;
            }
        } else if (userRepository.findByUsername(to).isEmpty()) {
            sendMessage(session, "error", "SEND_CHAT", "Người nhận không tồn tại", null);
            return;
        } else if (!friendshipService.areFriends(sender, to)) {
            sendMessage(
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
        savedMessage = markDeliveredIfNeeded(savedMessage);
        Map<String, Object> payload = toClientMessage(savedMessage);

        if ("people".equals(type)) {
            sendRealtimeToUser(to, "SEND_CHAT", "New message", payload);
        } else {
            for (RoomMember member : roomMemberRepository.findByRoomName(to)) {
                if (!sender.equals(member.getUsername())) {
                    sendRealtimeToUser(
                            member.getUsername(),
                            "SEND_CHAT",
                            "New message",
                            payload
                    );
                }
            }
        }

        sendMessage(session, "success", "SEND_CHAT", "Message sent", payload);
    }

    private Message markDeliveredIfNeeded(Message message) {
        boolean delivered = false;

        if ("people".equals(message.getType())) {
            delivered = isUserOnline(message.getReceiver());
        } else if ("room".equals(message.getType())) {
            delivered = roomMemberRepository.findByRoomName(message.getReceiver())
                    .stream()
                    .anyMatch(member ->
                            !message.getSender().equals(member.getUsername())
                                    && isUserOnline(member.getUsername())
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

    private boolean isUserOnline(String username) {
        WebSocketSession session = userSessions.get(username);
        return (session != null && session.isOpen()) || onlineStatusService.isOnline(username);
    }

    private void handleMarkRead(WebSocketSession session, Map<String, Object> data) throws Exception {
        String reader = getUsernameFromSession(session);
        String messageId = readString(data, "id", "messageId");

        if (reader == null || messageId == null) {
            sendMessage(session, "error", "MARK_READ", "Invalid read receipt request", null);
            return;
        }

        Optional<Message> messageOptional = messageRepository.findById(messageId);

        if (messageOptional.isEmpty()) {
            sendMessage(session, "error", "MARK_READ", "Message not found", null);
            return;
        }

        Message chatMessage = messageOptional.get();

        if (!canAccessMessage(chatMessage, reader) || reader.equals(chatMessage.getSender())) {
            sendMessage(session, "error", "MARK_READ", "Cannot mark this message as read", null);
            return;
        }

        chatMessage.setStatus("READ");
        chatMessage.setReadAt(LocalDateTime.now());

        if (chatMessage.getDeliveredAt() == null) {
            chatMessage.setDeliveredAt(chatMessage.getReadAt());
        }

        Message savedMessage = messageRepository.save(chatMessage);
        Map<String, Object> payload = toClientMessage(savedMessage);

        sendRealtimeToUser(savedMessage.getSender(), "MESSAGE_STATUS", "Message read", payload);
        sendMessage(session, "success", "MARK_READ", "Message read", payload);
    }

    private void handleTyping(
            WebSocketSession session,
            String event,
            Map<String, Object> data
    ) throws Exception {
        String sender = getUsernameFromSession(session);
        String type = normalizeChatType(data != null ? data.get("type") : null);
        String to = readString(data, "to", "receiver", "name");

        if (sender == null || to == null) {
            sendMessage(session, "error", event, "Invalid typing event", null);
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
                sendMessage(session, "error", event, "You are not a member of this room", null);
                return;
            }

            for (RoomMember member : roomMemberRepository.findByRoomName(to)) {
                if (!sender.equals(member.getUsername())) {
                    sendRealtimeToUser(member.getUsername(), event, "Typing updated", payload);
                }
            }
        } else {
            sendRealtimeToUser(to, event, "Typing updated", payload);
        }
    }

    private boolean canAccessMessage(Message message, String username) {
        if ("people".equals(message.getType())) {
            return username.equals(message.getSender()) || username.equals(message.getReceiver());
        }

        if ("room".equals(message.getType())) {
            return roomMemberRepository.existsByRoomNameAndUsername(message.getReceiver(), username);
        }

        return false;
    }

    // =========================================================
    // THU HỒI TIN NHẮN
    // =========================================================

    private void handleRecallMessage(
            WebSocketSession session,
            Map<String, Object> data
    ) throws Exception {

        String requester = getUsernameFromSession(session);
        String messageId = readString(data, "id", "messageId");

        if (requester == null) {
            sendMessage(session, "error", "RECALL_MESSAGE", "Bạn cần đăng nhập trước", null);
            return;
        }

        if (messageId == null) {
            sendMessage(
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
            sendMessage(session, "error", "RECALL_MESSAGE", "Tin nhắn không tồn tại", null);
            return;
        }

        Message chatMessage = messageOptional.get();

        if (!requester.equals(chatMessage.getSender())) {
            sendMessage(
                    session,
                    "error",
                    "RECALL_MESSAGE",
                    "Bạn chỉ có thể thu hồi tin nhắn của mình",
                    null
            );
            return;
        }

        if (Boolean.TRUE.equals(chatMessage.getRecalled())) {
            sendMessage(
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
        Map<String, Object> payload = toClientMessage(savedMessage);

        sendMessageToParticipantsExceptRequester(
                savedMessage,
                requester,
                "RECALL_MESSAGE",
                "Tin nhắn đã được thu hồi",
                payload
        );

        sendMessage(
                session,
                "success",
                "RECALL_MESSAGE",
                "Thu hồi tin nhắn thành công",
                payload
        );
    }

    // =========================================================
    // CHỈNH SỬA TIN NHẮN
    // =========================================================

    private void handleEditMessage(
            WebSocketSession session,
            Map<String, Object> data
    ) throws Exception {

        String requester = getUsernameFromSession(session);
        String messageId = readString(data, "id", "messageId");
        String newContent = readString(data, "content", "mes", "message", "newContent");

        if (requester == null) {
            sendMessage(session, "error", "EDIT_MESSAGE", "Bạn cần đăng nhập trước", null);
            return;
        }

        if (messageId == null) {
            sendMessage(
                    session,
                    "error",
                    "EDIT_MESSAGE",
                    "Không xác định được tin nhắn cần chỉnh sửa",
                    null
            );
            return;
        }

        if (newContent == null) {
            sendMessage(
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
            sendMessage(session, "error", "EDIT_MESSAGE", "Tin nhắn không tồn tại", null);
            return;
        }

        Message chatMessage = messageOptional.get();

        if (!requester.equals(chatMessage.getSender())) {
            sendMessage(
                    session,
                    "error",
                    "EDIT_MESSAGE",
                    "Bạn chỉ có thể chỉnh sửa tin nhắn của mình",
                    null
            );
            return;
        }

        if (Boolean.TRUE.equals(chatMessage.getRecalled())) {
            sendMessage(
                    session,
                    "error",
                    "EDIT_MESSAGE",
                    "Tin nhắn đã thu hồi thì không thể chỉnh sửa",
                    null
            );
            return;
        }

        if (!canEditContent(chatMessage.getContent())) {
            sendMessage(
                    session,
                    "error",
                    "EDIT_MESSAGE",
                    "Chỉ hỗ trợ chỉnh sửa tin nhắn chữ hoặc emoji",
                    null
            );
            return;
        }

        if (!canEditContent(newContent)) {
            sendMessage(
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
            sendMessage(session, "error", "EDIT_MESSAGE", "Bạn không còn thuộc nhóm này", null);
            return;
        }

        if (newContent.equals(chatMessage.getContent())) {
            sendMessage(
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

            sendMessage(
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
        Map<String, Object> payload = toClientMessage(savedMessage);

        sendMessageToParticipantsExceptRequester(
                savedMessage,
                requester,
                "EDIT_MESSAGE",
                "Tin nhắn đã được chỉnh sửa",
                payload
        );

        sendMessage(
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

    private void sendMessageToParticipantsExceptRequester(
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

    private void handleGetPeopleChatMes(
            WebSocketSession session,
            Map<String, Object> data
    ) throws Exception {

        String username = getUsernameFromSession(session);
        String to = readString(data, "name", "to", "user");

        if (username == null || to == null) {
            sendMessage(
                    session,
                    "error",
                    "GET_PEOPLE_CHAT_MES",
                    "Thông tin người dùng không hợp lệ",
                    null
            );
            return;
        }

        int page = extractPage(data);
        int size = extractPageSize(data);

        List<Map<String, Object>> rawMessages = messageRepository
                .findPeopleMessages(
                        username,
                        to,
                        org.springframework.data.domain.PageRequest.of(page - 1, size + 1)
                )
                .stream()
                .map(this::toClientMessage)
                .toList();
        boolean hasMore = rawMessages.size() > size;
        List<Map<String, Object>> chatMessages = hasMore
                ? rawMessages.subList(0, size)
                : rawMessages;
        Map<String, Object> responseData = buildPagedMessages(chatMessages, page, size, hasMore);

        sendMessage(
                session,
                "success",
                "GET_PEOPLE_CHAT_MES",
                "Messages retrieved",
                responseData
        );
    }

    // =========================================================
    // TẠO NHÓM / THÊM THÀNH VIÊN / CHAT NHÓM
    // =========================================================

    private void handleCreateRoom(
            WebSocketSession session,
            Map<String, Object> data
    ) throws Exception {

        String creator = getUsernameFromSession(session);
        String roomName = readString(data, "name", "roomName", "room");

        if (creator == null) {
            sendMessage(session, "error", "CREATE_ROOM", "Bạn cần đăng nhập trước", null);
            return;
        }

        if (roomName == null || roomName.isBlank()) {
            sendMessage(session, "error", "CREATE_ROOM", "Tên nhóm không được để trống", null);
            return;
        }

        roomName = roomName.trim();

        if (roomRepository.findByName(roomName).isPresent()) {
            sendMessage(session, "error", "CREATE_ROOM", "Tên nhóm đã tồn tại", null);
            return;
        }

        Room room = Room.builder()
                .name(roomName)
                .type("GROUP")
                .ownerUsername(creator)
                .build();

        roomRepository.save(room);

        RoomMember creatorMember = RoomMember.builder()
                .roomName(roomName)
                .username(creator)
                .role(RoomMember.ROLE_OWNER)
                .build();

        roomMemberRepository.save(creatorMember);

        Map<String, Object> roomData = buildRoomData(roomName);
        roomData.put("currentUserRole", RoomMember.ROLE_OWNER);

        sendMessage(session, "success", "CREATE_ROOM", "Tạo nhóm thành công", roomData);
        handleGetUserList(session);
    }

    private void handleJoinRoom(
            WebSocketSession session,
            Map<String, Object> data
    ) throws Exception {

        String username = getUsernameFromSession(session);
        String roomName = readString(data, "name", "roomName", "room");

        if (username == null || roomName == null) {
            sendMessage(session, "error", "JOIN_ROOM", "Thông tin nhóm không hợp lệ", null);
            return;
        }

        if (roomRepository.findByName(roomName).isEmpty()) {
            sendMessage(session, "error", "JOIN_ROOM", "Nhóm không tồn tại", null);
            return;
        }

        Optional<RoomMember> currentMember =
                roomMemberRepository.findByRoomNameAndUsername(roomName, username);

        if (currentMember.isEmpty()) {
            sendMessage(session, "error", "JOIN_ROOM", "Bạn chưa được thêm vào nhóm này", null);
            return;
        }

        Map<String, Object> roomData = buildRoomData(roomName);
        roomData.put("currentUserRole", normalizeRoomRole(currentMember.get().getRole()));

        sendMessage(
                session,
                "success",
                "JOIN_ROOM",
                "Joined room successfully",
                roomData
        );
    }

    private void handleAddUserToRoom(
            WebSocketSession session,
            Map<String, Object> data
    ) throws Exception {

        String requester = getUsernameFromSession(session);
        String roomName = readString(data, "name", "roomName", "room");
        String newUsername = readString(data, "user", "username", "member", "targetUsername");

        if (requester == null) {
            sendMessage(session, "error", "ADD_USER_TO_ROOM", "Bạn cần đăng nhập trước", null);
            return;
        }

        if (roomName == null || newUsername == null) {
            sendMessage(session, "error", "ADD_USER_TO_ROOM", "Tên nhóm hoặc username không hợp lệ", null);
            return;
        }

        if (roomRepository.findByName(roomName).isEmpty()) {
            sendMessage(session, "error", "ADD_USER_TO_ROOM", "Nhóm không tồn tại", null);
            return;
        }

        Optional<RoomMember> requesterMember =
                roomMemberRepository.findByRoomNameAndUsername(roomName, requester);

        if (requesterMember.isEmpty()) {
            sendMessage(session, "error", "ADD_USER_TO_ROOM", "Bạn không thuộc nhóm này", null);
            return;
        }

        if (requester.equals(newUsername)) {
            sendMessage(session, "error", "ADD_USER_TO_ROOM", "Bạn đã có trong nhóm", null);
            return;
        }

        if (userRepository.findByUsername(newUsername).isEmpty()) {
            sendMessage(session, "error", "ADD_USER_TO_ROOM", "User cần thêm không tồn tại", null);
            return;
        }

        if (roomMemberRepository.existsByRoomNameAndUsername(roomName, newUsername)) {
            sendMessage(session, "error", "ADD_USER_TO_ROOM", "User đã có trong nhóm", null);
            return;
        }

        RoomMember newMember = RoomMember.builder()
                .roomName(roomName)
                .username(newUsername)
                .role(RoomMember.ROLE_MEMBER)
                .build();

        roomMemberRepository.save(newMember);

        Map<String, Object> roomData = buildRoomData(roomName);
        roomData.put("addedUser", newUsername);
        roomData.put("actor", requester);

        sendMessage(session, "success", "ADD_USER_TO_ROOM", "Thêm thành viên thành công", roomData);

        for (RoomMember member : roomMemberRepository.findByRoomName(roomName)) {
            if (member.getUsername().equals(requester)) {
                refreshUserListForOnlineUser(member.getUsername());
                continue;
            }

            sendRealtimeToUser(
                    member.getUsername(),
                    member.getUsername().equals(newUsername) ? "ADDED_TO_ROOM" : "ROOM_MEMBER_ADDED",
                    member.getUsername().equals(newUsername)
                            ? "Bạn đã được thêm vào nhóm " + roomName
                            : newUsername + " đã được thêm vào nhóm",
                    roomData
            );

            refreshUserListForOnlineUser(member.getUsername());
        }
    }

    private void handleGetRoomMembers(
            WebSocketSession session,
            Map<String, Object> data
    ) throws Exception {

        String requester = getUsernameFromSession(session);
        String roomName = readString(data, "name", "roomName", "room");

        if (requester == null || roomName == null) {
            sendMessage(session, "error", "GET_ROOM_MEMBERS", "Thông tin nhóm không hợp lệ", null);
            return;
        }

        Optional<RoomMember> requesterMember =
                roomMemberRepository.findByRoomNameAndUsername(roomName, requester);

        if (requesterMember.isEmpty()) {
            sendMessage(session, "error", "GET_ROOM_MEMBERS", "Bạn không thuộc nhóm này", null);
            return;
        }

        Map<String, Object> roomData = buildRoomData(roomName);
        roomData.put("currentUserRole", normalizeRoomRole(requesterMember.get().getRole()));

        sendMessage(
                session,
                "success",
                "GET_ROOM_MEMBERS",
                "Lấy danh sách thành viên thành công",
                roomData
        );
    }

    private void handleSetRoomDeputy(
            WebSocketSession session,
            Map<String, Object> data
    ) throws Exception {

        String requester = getUsernameFromSession(session);
        String roomName = readString(data, "name", "roomName", "room");
        String targetUsername = readString(data, "user", "username", "member", "targetUsername");

        if (requester == null) {
            sendMessage(session, "error", "SET_ROOM_DEPUTY", "Bạn cần đăng nhập trước", null);
            return;
        }

        if (roomName == null || targetUsername == null) {
            sendMessage(session, "error", "SET_ROOM_DEPUTY", "Thông tin nhóm hoặc thành viên không hợp lệ", null);
            return;
        }

        Optional<RoomMember> requesterMember =
                roomMemberRepository.findByRoomNameAndUsername(roomName, requester);
        Optional<RoomMember> targetMember =
                roomMemberRepository.findByRoomNameAndUsername(roomName, targetUsername);

        if (requesterMember.isEmpty() || targetMember.isEmpty()) {
            sendMessage(session, "error", "SET_ROOM_DEPUTY", "Thành viên không tồn tại trong nhóm", null);
            return;
        }

        if (!isRoomOwner(requesterMember.get())) {
            sendMessage(session, "error", "SET_ROOM_DEPUTY", "Chỉ trưởng nhóm mới được cấp phó nhóm", null);
            return;
        }

        if (isRoomOwner(targetMember.get())) {
            sendMessage(session, "error", "SET_ROOM_DEPUTY", "Không thể cấp phó cho trưởng nhóm", null);
            return;
        }

        roomMemberRepository.updateRole(roomName, targetUsername, RoomMember.ROLE_DEPUTY);

        Map<String, Object> roomData = buildRoomData(roomName);
        roomData.put("targetUser", targetUsername);
        roomData.put("targetRole", RoomMember.ROLE_DEPUTY);
        roomData.put("actor", requester);

        sendMessage(session, "success", "SET_ROOM_DEPUTY", "Đã cấp phó nhóm", roomData);
        broadcastRoomData(roomName, requester, "ROOM_ROLE_UPDATED", "Vai trò thành viên đã được cập nhật", roomData);
        refreshUserListsInRoom(roomName);
    }

    private void handleRemoveRoomDeputy(
            WebSocketSession session,
            Map<String, Object> data
    ) throws Exception {

        String requester = getUsernameFromSession(session);
        String roomName = readString(data, "name", "roomName", "room");
        String targetUsername = readString(data, "user", "username", "member", "targetUsername");

        if (requester == null) {
            sendMessage(session, "error", "REMOVE_ROOM_DEPUTY", "Bạn cần đăng nhập trước", null);
            return;
        }

        if (roomName == null || targetUsername == null) {
            sendMessage(session, "error", "REMOVE_ROOM_DEPUTY", "Thông tin nhóm hoặc thành viên không hợp lệ", null);
            return;
        }

        Optional<RoomMember> requesterMember =
                roomMemberRepository.findByRoomNameAndUsername(roomName, requester);
        Optional<RoomMember> targetMember =
                roomMemberRepository.findByRoomNameAndUsername(roomName, targetUsername);

        if (requesterMember.isEmpty() || targetMember.isEmpty()) {
            sendMessage(session, "error", "REMOVE_ROOM_DEPUTY", "Thành viên không tồn tại trong nhóm", null);
            return;
        }

        if (!isRoomOwner(requesterMember.get())) {
            sendMessage(session, "error", "REMOVE_ROOM_DEPUTY", "Chỉ trưởng nhóm mới được hủy phó nhóm", null);
            return;
        }

        if (isRoomOwner(targetMember.get())) {
            sendMessage(session, "error", "REMOVE_ROOM_DEPUTY", "Không thể hủy vai trò trưởng nhóm", null);
            return;
        }

        roomMemberRepository.updateRole(roomName, targetUsername, RoomMember.ROLE_MEMBER);

        Map<String, Object> roomData = buildRoomData(roomName);
        roomData.put("targetUser", targetUsername);
        roomData.put("targetRole", RoomMember.ROLE_MEMBER);
        roomData.put("actor", requester);

        sendMessage(session, "success", "REMOVE_ROOM_DEPUTY", "Đã hủy phó nhóm", roomData);
        broadcastRoomData(roomName, requester, "ROOM_ROLE_UPDATED", "Vai trò thành viên đã được cập nhật", roomData);
        refreshUserListsInRoom(roomName);
    }

    private void handleRemoveRoomMember(
            WebSocketSession session,
            Map<String, Object> data
    ) throws Exception {

        String requester = getUsernameFromSession(session);
        String roomName = readString(data, "name", "roomName", "room");
        String targetUsername = readString(data, "user", "username", "member", "targetUsername");

        if (requester == null) {
            sendMessage(session, "error", "REMOVE_ROOM_MEMBER", "Bạn cần đăng nhập trước", null);
            return;
        }

        if (roomName == null || targetUsername == null) {
            sendMessage(session, "error", "REMOVE_ROOM_MEMBER", "Thông tin nhóm hoặc thành viên không hợp lệ", null);
            return;
        }

        Optional<RoomMember> requesterMember =
                roomMemberRepository.findByRoomNameAndUsername(roomName, requester);
        Optional<RoomMember> targetMember =
                roomMemberRepository.findByRoomNameAndUsername(roomName, targetUsername);

        if (requesterMember.isEmpty()) {
            sendMessage(session, "error", "REMOVE_ROOM_MEMBER", "Bạn không thuộc nhóm này", null);
            return;
        }

        if (targetMember.isEmpty()) {
            sendMessage(session, "error", "REMOVE_ROOM_MEMBER", "Thành viên cần xóa không tồn tại trong nhóm", null);
            return;
        }

        if (!isRoomManager(requesterMember.get())) {
            sendMessage(session, "error", "REMOVE_ROOM_MEMBER", "Chỉ trưởng nhóm hoặc phó nhóm mới được xóa thành viên", null);
            return;
        }

        if (requester.equals(targetUsername)) {
            sendMessage(session, "error", "REMOVE_ROOM_MEMBER", "Muốn tự rời nhóm hãy dùng chức năng Rời khỏi phòng chat", null);
            return;
        }

        if (isRoomOwner(targetMember.get())) {
            sendMessage(session, "error", "REMOVE_ROOM_MEMBER", "Không thể xóa trưởng nhóm", null);
            return;
        }

        if (isRoomDeputy(targetMember.get()) && !isRoomOwner(requesterMember.get())) {
            sendMessage(session, "error", "REMOVE_ROOM_MEMBER", "Phó nhóm không thể xóa phó nhóm khác", null);
            return;
        }

        roomMemberRepository.deleteMemberFromRoom(roomName, targetUsername);

        Map<String, Object> roomData = buildRoomData(roomName);
        roomData.put("removedUser", targetUsername);
        roomData.put("actor", requester);

        sendMessage(session, "success", "REMOVE_ROOM_MEMBER", "Đã xóa thành viên khỏi nhóm", roomData);

        sendRealtimeToUser(
                targetUsername,
                "ROOM_MEMBER_REMOVED_FROM_ROOM",
                "Bạn đã bị xóa khỏi nhóm " + roomName,
                roomData
        );
        refreshUserListForOnlineUser(targetUsername);

        broadcastRoomData(roomName, requester, "ROOM_MEMBER_REMOVED", targetUsername + " đã bị xóa khỏi nhóm", roomData);
        refreshUserListsInRoom(roomName);
    }

    private void handleGetRoomChatMes(
            WebSocketSession session,
            Map<String, Object> data
    ) throws Exception {

        String requester = getUsernameFromSession(session);
        String roomName = readString(data, "name", "roomName", "room");

        if (requester == null || roomName == null) {
            sendMessage(
                    session,
                    "error",
                    "GET_ROOM_CHAT_MES",
                    "Thông tin nhóm không hợp lệ",
                    null
            );
            return;
        }

        if (roomRepository.findByName(roomName).isEmpty()) {
            sendMessage(session, "error", "GET_ROOM_CHAT_MES", "Nhóm không tồn tại", null);
            return;
        }

        if (!roomMemberRepository.existsByRoomNameAndUsername(roomName, requester)) {
            sendMessage(session, "error", "GET_ROOM_CHAT_MES", "Bạn không thuộc nhóm này", null);
            return;
        }

        int page = extractPage(data);
        int size = extractPageSize(data);

        List<Map<String, Object>> rawMessages = messageRepository
                .findRoomMessages(
                        roomName,
                        org.springframework.data.domain.PageRequest.of(page - 1, size + 1)
                )
                .stream()
                .map(this::toClientMessage)
                .toList();
        boolean hasMore = rawMessages.size() > size;
        List<Map<String, Object>> chatMessages = hasMore
                ? rawMessages.subList(0, size)
                : rawMessages;

        Map<String, Object> responseData = buildRoomData(roomName);
        responseData.put("chatData", chatMessages);
        responseData.putAll(buildPagedMessages(chatMessages, page, size, hasMore));

        sendMessage(
                session,
                "success",
                "GET_ROOM_CHAT_MES",
                "Messages retrieved",
                responseData
        );
    }

    // =========================================================
    // ĐỔI TÊN PHÒNG CHAT
    // =========================================================

    private void handleRenameRoom(
            WebSocketSession session,
            Map<String, Object> data
    ) throws Exception {

        String requester = getUsernameFromSession(session);
        String oldName = readString(data, "oldName", "name", "roomName");
        String newName = readString(data, "newName", "newRoomName");

        if (requester == null) {
            sendMessage(session, "error", "RENAME_ROOM", "Bạn cần đăng nhập trước", null);
            return;
        }

        if (oldName == null || newName == null) {
            sendMessage(
                    session,
                    "error",
                    "RENAME_ROOM",
                    "Tên nhóm cũ hoặc tên nhóm mới không hợp lệ",
                    null
            );
            return;
        }

        if (oldName.equalsIgnoreCase(newName)) {
            sendMessage(
                    session,
                    "error",
                    "RENAME_ROOM",
                    "Tên nhóm mới phải khác tên hiện tại",
                    null
            );
            return;
        }

        var roomOptional = roomRepository.findByName(oldName);

        if (roomOptional.isEmpty()) {
            sendMessage(session, "error", "RENAME_ROOM", "Nhóm không tồn tại", null);
            return;
        }

        if (!roomMemberRepository.existsByRoomNameAndUsername(oldName, requester)) {
            sendMessage(session, "error", "RENAME_ROOM", "Bạn không thuộc nhóm này", null);
            return;
        }

        if (roomRepository.findByName(newName).isPresent()) {
            sendMessage(session, "error", "RENAME_ROOM", "Tên nhóm này đã tồn tại", null);
            return;
        }

        List<String> members = roomMemberRepository.findByRoomName(oldName)
                .stream()
                .map(RoomMember::getUsername)
                .toList();

        Room room = roomOptional.get();
        room.setName(newName);

        roomRepository.saveAndFlush(room);
        roomMemberRepository.renameRoomName(oldName, newName);
        messageRepository.findByTypeAndReceiver("room", oldName).forEach(message -> {
            message.setReceiver(newName);
            messageRepository.save(message);
        });
        groupThemeRepository.renameGroupTheme(oldName, newName);

        Map<String, Object> roomData = buildRoomData(newName);
        roomData.put("oldName", oldName);
        roomData.put("newName", newName);

        sendMessage(session, "success", "RENAME_ROOM", "Đổi tên nhóm thành công", roomData);

        for (String member : members) {
            if (!requester.equals(member)) {
                sendRealtimeToUser(
                        member,
                        "ROOM_RENAMED",
                        "Tên nhóm đã được thay đổi",
                        roomData
                );
            }

            refreshUserListForOnlineUser(member);
        }
    }

    // =========================================================
    // RỜI KHỎI PHÒNG CHAT
    // =========================================================

    private void handleLeaveRoom(
            WebSocketSession session,
            Map<String, Object> data
    ) throws Exception {

        String requester = getUsernameFromSession(session);
        String roomName = readString(data, "name", "roomName", "room");
        String newOwnerUsername = readString(data, "newOwner", "newOwnerUsername", "ownerUsername");

        if (requester == null) {
            sendMessage(session, "error", "LEAVE_ROOM", "Bạn cần đăng nhập trước", null);
            return;
        }

        if (roomName == null || roomName.isBlank()) {
            sendMessage(session, "error", "LEAVE_ROOM", "Tên nhóm không hợp lệ", null);
            return;
        }

        roomName = roomName.trim();

        var roomOptional = roomRepository.findByName(roomName);

        if (roomOptional.isEmpty()) {
            sendMessage(session, "error", "LEAVE_ROOM", "Nhóm không tồn tại", null);
            return;
        }

        Optional<RoomMember> requesterMemberOptional =
                roomMemberRepository.findByRoomNameAndUsername(roomName, requester);

        if (requesterMemberOptional.isEmpty()) {
            sendMessage(session, "error", "LEAVE_ROOM", "Bạn không thuộc nhóm này", null);
            return;
        }

        Room room = roomOptional.get();
        RoomMember requesterMember = requesterMemberOptional.get();

        boolean requesterIsOwner = isRoomOwner(requesterMember)
                || requester.equals(room.getOwnerUsername());

        List<RoomMember> membersBeforeLeave = roomMemberRepository.findByRoomName(roomName);

        List<RoomMember> remainingBeforeLeave = membersBeforeLeave
                .stream()
                .filter(member -> !requester.equals(member.getUsername()))
                .toList();

        Map<String, Object> leaveData = new LinkedHashMap<>();
        leaveData.put("name", roomName);
        leaveData.put("leftUser", requester);

        if (remainingBeforeLeave.isEmpty()) {
            roomMemberRepository.deleteMemberFromRoom(roomName, requester);
            groupThemeRepository.deleteByGroupName(roomName);
            messageRepository.deleteByTypeAndReceiver("room", roomName);
            roomRepository.delete(room);

            leaveData.put("deleted", true);

            sendMessage(
                    session,
                    "success",
                    "LEAVE_ROOM",
                    "Bạn đã rời nhóm. Nhóm trống nên đã được xóa.",
                    leaveData
            );

            handleGetUserList(session);
            return;
        }

        if (requesterIsOwner) {
            if (newOwnerUsername == null || newOwnerUsername.isBlank()) {
                sendMessage(session, "error", "LEAVE_ROOM", "Trưởng nhóm phải chọn người nhận quyền trước khi rời nhóm", null);
                return;
            }

            newOwnerUsername = newOwnerUsername.trim();

            if (requester.equals(newOwnerUsername)) {
                sendMessage(session, "error", "LEAVE_ROOM", "Người nhận quyền phải là thành viên khác", null);
                return;
            }

            Optional<RoomMember> newOwnerMember =
                    roomMemberRepository.findByRoomNameAndUsername(roomName, newOwnerUsername);

            if (newOwnerMember.isEmpty()) {
                sendMessage(session, "error", "LEAVE_ROOM", "Người nhận quyền không thuộc nhóm này", null);
                return;
            }

            room.setOwnerUsername(newOwnerUsername);
            roomRepository.saveAndFlush(room);
            roomMemberRepository.updateRole(roomName, newOwnerUsername, RoomMember.ROLE_OWNER);

            leaveData.put("newOwner", newOwnerUsername);
            leaveData.put("newOwnerUsername", newOwnerUsername);
        }

        roomMemberRepository.deleteMemberFromRoom(roomName, requester);

        List<RoomMember> remainingMembers = roomMemberRepository.findByRoomName(roomName);
        leaveData.put("deleted", false);

        sendMessage(
                session,
                "success",
                "LEAVE_ROOM",
                requesterIsOwner
                        ? "Bạn đã rời nhóm và nhường quyền trưởng nhóm thành công"
                        : "Rời khỏi phòng chat thành công",
                leaveData
        );

        Map<String, Object> roomData = buildRoomData(roomName);
        roomData.put("leftUser", requester);

        if (requesterIsOwner) {
            roomData.put("newOwner", newOwnerUsername);
            roomData.put("newOwnerUsername", newOwnerUsername);
        }

        for (RoomMember member : remainingMembers) {
            sendRealtimeToUser(
                    member.getUsername(),
                    requesterIsOwner ? "ROOM_OWNER_CHANGED" : "ROOM_MEMBER_LEFT",
                    requesterIsOwner
                            ? requester + " đã rời nhóm và nhường quyền trưởng nhóm cho " + newOwnerUsername
                            : requester + " đã rời khỏi nhóm",
                    roomData
            );
            refreshUserListForOnlineUser(member.getUsername());
        }

        handleGetUserList(session);
    }

    // =========================================================
    // HÀM HỖ TRỢ
    // =========================================================

    private int extractPage(Map<String, Object> data) {
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

    private int extractPageSize(Map<String, Object> data) {
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

    private Map<String, Object> buildPagedMessages(
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

    private Map<String, Object> buildAuthPayload(User user, String token) {
        Map<String, Object> payload = buildUserPayload(user);

        payload.put("token", token);
        payload.put("RE_LOGIN_CODE", token);
        payload.put("user", user.getUsername());

        return payload;
    }

    private Map<String, Object> buildUserPayload(User user) {
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

    private String getEffectiveDisplayName(User user) {
        String displayName = user.getDisplayName();

        if (displayName == null || displayName.isBlank()) {
            return user.getUsername();
        }

        return displayName;
    }

    private String cleanText(Object rawValue, int maxLength) {
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

    private Map<String, Object> buildRoomData(String roomName) {
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

        // Lấy trưởng nhóm theo room_members.role trước.
        // Nếu rooms.owner_username bị lệch thì vẫn hiển thị đúng theo DB room_members.
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

        // Đồng bộ lại rooms.owner_username nếu đang bị lệch
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

    private Map<String, Object> toClientMessage(Message message) {
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



    private String normalizeMessageStatus(String status) {
        if (status == null || status.isBlank()) {
            return "sent";
        }

        return status.toLowerCase();
    }

    private void sendRealtimeToUser(
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

    private void refreshUserListForOnlineUser(String username) throws Exception {
        WebSocketSession targetSession = userSessions.get(username);

        if (targetSession != null && targetSession.isOpen()) {
            handleGetUserList(targetSession);
        }
    }

    private String normalizeRoomRole(String role) {
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

    private boolean isRoomOwner(RoomMember member) {
        return member != null
                && RoomMember.ROLE_OWNER.equals(normalizeRoomRole(member.getRole()));
    }

    private boolean isRoomDeputy(RoomMember member) {
        return member != null
                && RoomMember.ROLE_DEPUTY.equals(normalizeRoomRole(member.getRole()));
    }

    private boolean isRoomManager(RoomMember member) {
        return isRoomOwner(member) || isRoomDeputy(member);
    }

    private int roleOrder(String role) {
        String normalized = normalizeRoomRole(role);

        if (RoomMember.ROLE_OWNER.equals(normalized)) {
            return 0;
        }

        if (RoomMember.ROLE_DEPUTY.equals(normalized)) {
            return 1;
        }

        return 2;
    }

    private void broadcastRoomData(
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

    private void refreshUserListsInRoom(String roomName) throws Exception {
        for (RoomMember member : roomMemberRepository.findByRoomName(roomName)) {
            refreshUserListForOnlineUser(member.getUsername());
        }
    }


    private void markUserOnline(String username, WebSocketSession session) {
        userSessions.put(username, session);
        onlineStatusService.markOnline(username, session.getId());

        broadcastUserStatus(username, true);
    }

    private void markUserOffline(String username) {
        onlineStatusService.markOffline(username);

        broadcastUserStatus(username, false);
    }

    private void broadcastUserStatus(String username, boolean online) {
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
                    // Bỏ qua session đã đóng.
                }
            }
        }
    }

    private void sendMessage(
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

        /*
         * Mỗi session chỉ được gửi một message tại một thời điểm.
         * Tránh lỗi TEXT_PARTIAL_WRITING khi nhiều event realtime gửi cùng lúc.
         */
        synchronized (session) {
            if (session.isOpen()) {
                session.sendMessage(new TextMessage(jsonMessage));
            }
        }
    }
}
