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
            userSessions.remove(username);
            markUserOffline(username);
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

            default:
                sendMessage(session, "error", event, "Event không được hỗ trợ", null);
                break;
        }
    }


    private void handleReactMessage(
            WebSocketSession session,
            Map<String, Object> data
    ) throws Exception {

        String username = getUsernameFromSession(session);
        Long messageId = readLong(data, "messageId", "id");
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

        PendingConversation pc = pendingConversationRepository.findBetweenUsers(from, to)
                .map(existing -> {
                    if (!"ACCEPTED".equals(existing.getStatus())) {
                        existing.setFromUsername(from);
                        existing.setToUsername(to);
                        existing.setStatus("PENDING");
                    }

                    return pendingConversationRepository.save(existing);
                })
                .orElseGet(() -> pendingConversationRepository.save(
                        PendingConversation.builder()
                                .fromUsername(from)
                                .toUsername(to)
                                .status("PENDING")
                                .build()
                ));

        Map<String, Object> payload = toClientPendingConversation(pc);

        if ("ACCEPTED".equals(pc.getStatus())) {
            sendMessage(session, "success", "SEND_CONTACT_REQUEST", "Hai người đã có trong danh sách liên hệ", payload);
            return;
        }

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

        payload.put("accepted", pendingConversationRepository
                .findAcceptedConversations(username)
                .stream()
                .map(this::toClientPendingConversation)
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
        pc.setStatus("ACCEPTED");
        pc = pendingConversationRepository.save(pc);

        Map<String, Object> payload = toClientPendingConversation(pc);

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

            Map<String, String> payload = Map.of(
                    "token", token,
                    "RE_LOGIN_CODE", token,
                    "user", username
            );

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

        markUserOnline(username, session);

        Map<String, String> payload = Map.of(
                "token", token,
                "RE_LOGIN_CODE", token,
                "user", username
        );

        sendMessage(session, "success", "RE_LOGIN", "Re-login successful", payload);
    }

    private void handleLogout(WebSocketSession session) throws Exception {
        String username = getUsernameFromSession(session);

        if (username != null) {
            userSessions.remove(username);
            markUserOffline(username);
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

        for (var conversation : pendingConversationRepository.findAcceptedConversations(username)) {
            String friendUsername = username.equals(conversation.getFromUsername())
                    ? conversation.getToUsername()
                    : conversation.getFromUsername();

            userRepository.findByUsername(friendUsername).ifPresent(user -> {
                Map<String, Object> userData = new LinkedHashMap<>();

                userData.put("name", user.getUsername());
                userData.put("type", 0);
                userData.put(
                        "actionTime",
                        conversation.getUpdatedAt() != null
                                ? conversation.getUpdatedAt().toString()
                                : LocalDateTime.now().toString()
                );

                responseList.add(userData);
            });
        }

        for (RoomMember roomMember : roomMemberRepository.findByUsername(username)) {
            Map<String, Object> roomData = new LinkedHashMap<>();

            roomData.put("name", roomMember.getRoomName());
            roomData.put("type", 1);
            roomData.put("actionTime", LocalDateTime.now().toString());

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

        boolean online = userSessions.containsKey(usernameToCheck)
                && userSessions.get(usernameToCheck) != null
                && userSessions.get(usernameToCheck).isOpen();

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

        boolean exists = userRepository.findByUsername(usernameToCheck).isPresent();

        sendMessage(
                session,
                exists ? "success" : "error",
                "CHECK_USER_EXIST",
                exists ? "User exists" : "User not found",
                Map.of(
                        "user", usernameToCheck,
                        "status", exists
                )
        );
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

    private boolean isUserOnline(String username) {
        WebSocketSession session = userSessions.get(username);
        return session != null && session.isOpen();
    }

    private void handleMarkRead(WebSocketSession session, Map<String, Object> data) throws Exception {
        String reader = getUsernameFromSession(session);
        Long messageId = readLong(data, "id", "messageId");

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
        Long messageId = readLong(data, "id", "messageId");

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
        Long messageId = readLong(data, "id", "messageId");
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
            sendRealtimeToUser(chatMessage.getReceiver(), event, message, payload);
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

        if (roomName == null) {
            sendMessage(session, "error", "CREATE_ROOM", "Tên nhóm không được để trống", null);
            return;
        }

        if (roomRepository.findByName(roomName).isPresent()) {
            sendMessage(session, "error", "CREATE_ROOM", "Tên nhóm đã tồn tại", null);
            return;
        }

        Room room = Room.builder()
                .name(roomName)
                .type("GROUP")
                .build();

        roomRepository.save(room);

        RoomMember creatorMember = RoomMember.builder()
                .roomName(roomName)
                .username(creator)
                .build();

        roomMemberRepository.save(creatorMember);

        Map<String, Object> roomData = buildRoomData(roomName);

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

        if (!roomMemberRepository.existsByRoomNameAndUsername(roomName, username)) {
            sendMessage(session, "error", "JOIN_ROOM", "Bạn chưa được thêm vào nhóm này", null);
            return;
        }

        sendMessage(
                session,
                "success",
                "JOIN_ROOM",
                "Joined room successfully",
                buildRoomData(roomName)
        );
    }

    private void handleAddUserToRoom(
            WebSocketSession session,
            Map<String, Object> data
    ) throws Exception {

        String requester = getUsernameFromSession(session);
        String roomName = readString(data, "name", "roomName", "room");
        String newUsername = readString(data, "user", "username", "member");

        if (requester == null) {
            sendMessage(session, "error", "ADD_USER_TO_ROOM", "Bạn cần đăng nhập trước", null);
            return;
        }

        if (roomName == null || newUsername == null) {
            sendMessage(
                    session,
                    "error",
                    "ADD_USER_TO_ROOM",
                    "Tên nhóm hoặc username không hợp lệ",
                    null
            );
            return;
        }

        if (roomRepository.findByName(roomName).isEmpty()) {
            sendMessage(session, "error", "ADD_USER_TO_ROOM", "Nhóm không tồn tại", null);
            return;
        }

        if (!roomMemberRepository.existsByRoomNameAndUsername(roomName, requester)) {
            sendMessage(session, "error", "ADD_USER_TO_ROOM", "Bạn không thuộc nhóm này", null);
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
                .build();

        roomMemberRepository.save(newMember);

        Map<String, Object> roomData = buildRoomData(roomName);

        sendMessage(
                session,
                "success",
                "ADD_USER_TO_ROOM",
                "Thêm thành viên thành công",
                roomData
        );

        handleGetUserList(session);

        WebSocketSession addedUserSession = userSessions.get(newUsername);

        if (addedUserSession != null && addedUserSession.isOpen()) {
            sendMessage(
                    addedUserSession,
                    "success",
                    "ADDED_TO_ROOM",
                    "Bạn đã được thêm vào nhóm " + roomName,
                    roomData
            );

            handleGetUserList(addedUserSession);
        }
    }

    private void handleGetRoomMembers(
            WebSocketSession session,
            Map<String, Object> data
    ) throws Exception {

        String requester = getUsernameFromSession(session);
        String roomName = readString(data, "name", "roomName", "room");

        if (requester == null || roomName == null) {
            sendMessage(
                    session,
                    "error",
                    "GET_ROOM_MEMBERS",
                    "Thông tin nhóm không hợp lệ",
                    null
            );
            return;
        }

        if (!roomMemberRepository.existsByRoomNameAndUsername(roomName, requester)) {
            sendMessage(session, "error", "GET_ROOM_MEMBERS", "Bạn không thuộc nhóm này", null);
            return;
        }

        sendMessage(
                session,
                "success",
                "GET_ROOM_MEMBERS",
                "Lấy danh sách thành viên thành công",
                buildRoomData(roomName)
        );
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
        messageRepository.renameRoomMessages(oldName, newName);
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

        if (requester == null) {
            sendMessage(session, "error", "LEAVE_ROOM", "Bạn cần đăng nhập trước", null);
            return;
        }

        if (roomName == null) {
            sendMessage(session, "error", "LEAVE_ROOM", "Tên nhóm không hợp lệ", null);
            return;
        }

        var roomOptional = roomRepository.findByName(roomName);

        if (roomOptional.isEmpty()) {
            sendMessage(session, "error", "LEAVE_ROOM", "Nhóm không tồn tại", null);
            return;
        }

        if (!roomMemberRepository.existsByRoomNameAndUsername(roomName, requester)) {
            sendMessage(session, "error", "LEAVE_ROOM", "Bạn không thuộc nhóm này", null);
            return;
        }

        roomMemberRepository.deleteMemberFromRoom(roomName, requester);

        List<RoomMember> remainingMembers = roomMemberRepository.findByRoomName(roomName);

        Map<String, Object> leaveData = new LinkedHashMap<>();
        leaveData.put("name", roomName);
        leaveData.put("leftUser", requester);

        if (remainingMembers.isEmpty()) {
            groupThemeRepository.deleteByGroupName(roomName);
            messageRepository.deleteRoomMessages(roomName);
            roomRepository.delete(roomOptional.get());

            leaveData.put("deleted", true);

            sendMessage(
                    session,
                    "success",
                    "LEAVE_ROOM",
                    "Bạn đã rời nhóm. Nhóm trống nên đã được xóa.",
                    leaveData
            );
        } else {
            leaveData.put("deleted", false);

            sendMessage(
                    session,
                    "success",
                    "LEAVE_ROOM",
                    "Rời khỏi phòng chat thành công",
                    leaveData
            );

            Map<String, Object> roomData = buildRoomData(roomName);
            roomData.put("leftUser", requester);

            for (RoomMember member : remainingMembers) {
                sendRealtimeToUser(
                        member.getUsername(),
                        "ROOM_MEMBER_LEFT",
                        requester + " đã rời khỏi nhóm",
                        roomData
                );
            }
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

    private Map<String, Object> buildRoomData(String roomName) {
        List<String> userList = roomMemberRepository.findByRoomName(roomName)
                .stream()
                .map(RoomMember::getUsername)
                .toList();

        Map<String, Object> roomData = new LinkedHashMap<>();

        roomData.put("name", roomName);
        roomData.put("type", 1);
        roomData.put("own", userList.isEmpty() ? "" : userList.get(0));
        roomData.put("userList", userList);

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

    private void markUserOnline(String username, WebSocketSession session) {
        userSessions.put(username, session);

        userRepository.findByUsername(username).ifPresent(user -> {
            user.setStatus("ONLINE");
            userRepository.save(user);
        });

        broadcastUserStatus(username, true);
    }

    private void markUserOffline(String username) {
        userRepository.findByUsername(username).ifPresent(user -> {
            user.setStatus("OFFLINE");
            userRepository.save(user);
        });

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
