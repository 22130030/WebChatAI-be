package com.appchat.backend.websocket;

import com.appchat.backend.dto.ApiResponse;
import com.appchat.backend.dto.SocketMessageDto;
import com.appchat.backend.entity.Message;
import com.appchat.backend.entity.User;
import com.appchat.backend.repository.MessageRepository;
import com.appchat.backend.repository.UserRepository;
import com.appchat.backend.repository.RoomRepository;
import com.appchat.backend.repository.RoomMemberRepository;
import com.appchat.backend.entity.Room;
import com.appchat.backend.entity.RoomMember;
import com.appchat.backend.security.JwtUtil;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
@RequiredArgsConstructor
public class ChatWebSocketHandler extends TextWebSocketHandler {

    private final ObjectMapper objectMapper;
    private final UserRepository userRepository;
    private final MessageRepository messageRepository;
    private final RoomRepository roomRepository;
    private final RoomMemberRepository roomMemberRepository;
    private final JwtUtil jwtUtil;
    private final PasswordEncoder passwordEncoder;

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
        String payload = message.getPayload();

        try {
            SocketMessageDto msg = objectMapper.readValue(payload, SocketMessageDto.class);

            if ("onchat".equals(msg.getAction()) && msg.getData() != null) {
                handleEvent(session, msg.getData().getEvent(), msg.getData().getData());
            }
        } catch (Exception e) {
            System.err.println("Error parsing WebSocket message: " + e.getMessage());
        }
    }

    private void handleEvent(WebSocketSession session, String event, Map<String, Object> data) throws Exception {
        if (!isPublicEvent(event) && getUsernameFromSession(session) == null) {
            sendMessage(session, "error", "AUTH", "Bạn cần đăng nhập trước khi thực hiện thao tác này", null);
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

            case "SEND_CHAT":
                handleSendChat(session, data);
                break;

            case "GET_USER_LIST":
                handleGetUserList(session);
                break;

            case "CREATE_ROOM":
                handleCreateRoom(session, data);
                break;

            case "JOIN_ROOM":
                handleJoinRoom(session, data);
                break;

            case "CHECK_USER_ONLINE":
                handleCheckUserOnline(session, data);
                break;

            case "CHECK_USER_EXIST":
                handleCheckUserExist(session, data);
                break;

            case "GET_PEOPLE_CHAT_MES":
                handleGetPeopleChatMes(session, data);
                break;

            case "GET_ROOM_CHAT_MES":
                handleGetRoomChatMes(session, data);
                break;

            default:
                System.out.println("Unhandled event: " + event);
                break;
        }
    }

    private boolean isPublicEvent(String event) {
        return "LOGIN".equals(event)
                || "REGISTER".equals(event)
                || "RE_LOGIN".equals(event);
    }

    private String getUsernameFromSession(WebSocketSession session) {
        return userSessions.entrySet()
                .stream()
                .filter(e -> e.getValue().getId().equals(session.getId()))
                .map(Map.Entry::getKey)
                .findFirst()
                .orElse(null);
    }

    private String extractTokenFromQuery(WebSocketSession session) {
        if (session.getUri() == null || session.getUri().getQuery() == null) {
            return null;
        }

        String query = session.getUri().getQuery();

        for (String param : query.split("&")) {
            String[] pair = param.split("=", 2);

            if (pair.length == 2 && "token".equals(pair[0])) {
                return URLDecoder.decode(pair[1], StandardCharsets.UTF_8);
            }
        }

        return null;
    }

    private void handleLogin(WebSocketSession session, Map<String, Object> data) throws Exception {
        String username = (String) data.get("user");
        String password = (String) data.get("pass");

        var optUser = userRepository.findByUsername(username);

        if (optUser.isPresent() && isPasswordMatched(password, optUser.get())) {
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

    private void handleRegister(WebSocketSession session, Map<String, Object> data) throws Exception {
        String username = (String) data.get("user");
        String password = (String) data.get("pass");

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

    private void handleReLogin(WebSocketSession session, Map<String, Object> data) throws Exception {
        String username = (String) data.get("user");
        String token = (String) data.get("code");

        if (username != null && token != null && jwtUtil.isTokenValid(token)) {
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
        } else {
            sendMessage(session, "error", "RE_LOGIN", "Invalid re-login credentials", null);
        }
    }

    private void handleLogout(WebSocketSession session) throws Exception {
        String username = getUsernameFromSession(session);

        if (username == null) {
            sendMessage(session, "success", "LOGOUT", "Logout successful", null);
            return;
        }

        userSessions.remove(username);
        markUserOffline(username);

        sendMessage(session, "success", "LOGOUT", "Logout successful", Map.of("user", username));
    }

    private void handleSendChat(WebSocketSession session, Map<String, Object> data) throws Exception {
        String type = String.valueOf(data.getOrDefault("type", "people"));
        String to = (String) data.get("to");
        String mes = (String) data.get("mes");

        String sender = getUsernameFromSession(session);

        if (sender == null) {
            sendMessage(session, "error", "SEND_CHAT", "Bạn cần đăng nhập trước khi gửi tin nhắn", null);
            return;
        }

        if (to == null || to.trim().isEmpty()) {
            sendMessage(session, "error", "SEND_CHAT", "Người nhận không hợp lệ", null);
            return;
        }

        if (mes == null || mes.trim().isEmpty()) {
            sendMessage(session, "error", "SEND_CHAT", "Nội dung tin nhắn không được rỗng", null);
            return;
        }

        Message message = Message.builder()
                .type(type)
                .sender(sender)
                .receiver(to)
                .content(mes)
                .build();

        message = messageRepository.save(message);

        Map<String, Object> payload = toClientMessage(message);

        if ("people".equals(type)) {
            WebSocketSession recipientSession = userSessions.get(to);

            System.out.println("===== SEND_CHAT DEBUG =====");
            System.out.println("sender = " + sender);
            System.out.println("to = " + to);
            System.out.println("online users = " + userSessions.keySet());
            System.out.println("recipientSession = " + recipientSession);
            System.out.println("===========================");

            if (recipientSession != null && recipientSession.isOpen()) {
                sendMessage(recipientSession, "success", "SEND_CHAT", "New message", payload);
            } else {
                System.out.println("User " + to + " không online hoặc WebSocket session bị null");
            }
        }else if ("room".equals(type)) {
            java.util.List<RoomMember> members = roomMemberRepository.findByRoomName(to);

            for (RoomMember member : members) {
                if (sender.equals(member.getUsername())) {
                    continue;
                }

                WebSocketSession memberSession = userSessions.get(member.getUsername());

                if (memberSession != null && memberSession.isOpen()) {
                    sendMessage(memberSession, "success", "SEND_CHAT", "New message", payload);
                }
            }
        }

        sendMessage(session, "success", "SEND_CHAT", "Message sent", payload);
    }

    private void handleGetUserList(WebSocketSession session) throws Exception {
        String username = getUsernameFromSession(session);

        if (username == null) {
            return;
        }

        java.util.List<Map<String, Object>> responseList = new java.util.ArrayList<>();

        java.util.List<User> users = userRepository.findAll();

        for (User u : users) {
            if (!u.getUsername().equals(username)) {
                responseList.add(Map.of(
                        "name", u.getUsername(),
                        "type", 0,
                        "actionTime", u.getCreatedAt() != null ? u.getCreatedAt().toString() : LocalDateTime.now().toString()
                ));
            }
        }

        java.util.List<RoomMember> userRooms = roomMemberRepository.findByUsername(username);

        for (RoomMember rm : userRooms) {
            responseList.add(Map.of(
                    "name", rm.getRoomName(),
                    "type", 1,
                    "actionTime", LocalDateTime.now().toString()
            ));
        }

        sendMessage(session, "success", "GET_USER_LIST", "User list retrieved", responseList);
    }

    private void handleCreateRoom(WebSocketSession session, Map<String, Object> data) throws Exception {
        String roomName = (String) data.get("name");

        if (roomName == null || roomName.trim().isEmpty()) {
            sendMessage(session, "error", "CREATE_ROOM", "Room name cannot be empty", null);
            return;
        }

        if (roomRepository.findByName(roomName).isPresent()) {
            sendMessage(session, "error", "CREATE_ROOM", "Room already exists", null);
            return;
        }

        Room newRoom = Room.builder()
                .name(roomName)
                .build();

        roomRepository.save(newRoom);

        sendMessage(session, "success", "CREATE_ROOM", "Room created", null);
    }

    private void handleJoinRoom(WebSocketSession session, Map<String, Object> data) throws Exception {
        String roomName = (String) data.get("name");
        String username = getUsernameFromSession(session);

        if (username == null || roomName == null) {
            return;
        }

        RoomMember rm = RoomMember.builder()
                .roomName(roomName)
                .username(username)
                .build();

        roomMemberRepository.save(rm);

        java.util.List<RoomMember> members = roomMemberRepository.findByRoomName(roomName);
        java.util.List<String> userList = members.stream()
                .map(RoomMember::getUsername)
                .toList();

        Map<String, Object> responseData = Map.of(
                "name", roomName,
                "own", userList.isEmpty() ? username : userList.get(0),
                "userList", userList
        );

        sendMessage(session, "success", "JOIN_ROOM", "Joined room successfully", responseData);
    }

    private void handleCheckUserOnline(WebSocketSession session, Map<String, Object> data) throws Exception {
        String usernameToCheck = (String) data.get("user");

        boolean isOnline = userSessions.containsKey(usernameToCheck)
                && userSessions.get(usernameToCheck).isOpen();

        sendMessage(session, "success", "CHECK_USER_ONLINE", "Status checked", Map.of(
                "user", usernameToCheck,
                "status", isOnline
        ));
    }

    private void handleCheckUserExist(WebSocketSession session, Map<String, Object> data) throws Exception {
        String usernameToCheck = (String) data.get("user");

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

    private void handleGetPeopleChatMes(WebSocketSession session, Map<String, Object> data) throws Exception {
        String username = getUsernameFromSession(session);
        String to = (String) data.get("name");

        if (username == null || to == null) {
            return;
        }

        int page = extractPage(data);

        java.util.List<Map<String, Object>> chatMessages = messageRepository
                .findPeopleMessages(username, to, org.springframework.data.domain.PageRequest.of(page - 1, 30))
                .stream()
                .map(this::toClientMessage)
                .toList();

        sendMessage(session, "success", "GET_PEOPLE_CHAT_MES", "Messages retrieved", chatMessages);
    }

    private void handleGetRoomChatMes(WebSocketSession session, Map<String, Object> data) throws Exception {
        String roomName = (String) data.get("name");

        if (roomName == null) {
            return;
        }

        int page = extractPage(data);

        java.util.List<Map<String, Object>> chatMessages = messageRepository
                .findRoomMessages(roomName, org.springframework.data.domain.PageRequest.of(page - 1, 30))
                .stream()
                .map(this::toClientMessage)
                .toList();

        sendMessage(session, "success", "GET_ROOM_CHAT_MES", "Messages retrieved", chatMessages);
    }

    private int extractPage(Map<String, Object> data) {
        Object pageObj = data != null ? data.get("page") : null;
        int page = 1;

        if (pageObj instanceof Number number) {
            page = number.intValue();
        } else if (pageObj instanceof String str) {
            try {
                page = Integer.parseInt(str);
            } catch (NumberFormatException ignored) {
                page = 1;
            }
        }

        return Math.max(page, 1);
    }

    private Map<String, Object> toClientMessage(Message message) {
        Map<String, Object> dto = new LinkedHashMap<>();

        String createdAt = message.getCreatedAt() != null
                ? message.getCreatedAt().toString()
                : LocalDateTime.now().toString();

        dto.put("id", message.getId());
        dto.put("type", message.getType());

        dto.put("name", message.getSender());
        dto.put("mes", message.getContent());
        dto.put("to", message.getReceiver());
        dto.put("createAt", createdAt);

        dto.put("sender", message.getSender());
        dto.put("receiver", message.getReceiver());
        dto.put("content", message.getContent());
        dto.put("createdAt", createdAt);
        dto.put("status", "sent");

        return dto;
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

        for (WebSocketSession s : userSessions.values()) {
            if (s != null && s.isOpen()) {
                try {
                    sendMessage(s, "success", "CHECK_USER_ONLINE", "Status updated", payload);
                } catch (Exception ignored) {
                }
            }
        }
    }

    private void sendMessage(WebSocketSession session, String status, String event, String mes, Object payload) throws Exception {
        ApiResponse<?> response = new ApiResponse<>(status, event, mes, payload);
        session.sendMessage(new TextMessage(objectMapper.writeValueAsString(response)));
    }
}