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

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

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

    // session_id -> session
    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();
    // username -> session
    private final Map<String, WebSocketSession> userSessions = new ConcurrentHashMap<>();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        sessions.put(session.getId(), session);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        sessions.remove(session.getId());
        userSessions.values().removeIf(s -> s.getId().equals(session.getId()));
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
        switch (event) {
            case "LOGIN":
                handleLogin(session, data);
                break;
            case "REGISTER":
                handleRegister(session, data);
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
            case "RE_LOGIN":
                handleReLogin(session, data);
                break;
            // Additional events can be handled here
            default:
                System.out.println("Unhandled event: " + event);
        }
    }

    private String getUsernameFromSession(WebSocketSession session) {
        return userSessions.entrySet().stream()
                .filter(e -> e.getValue().getId().equals(session.getId()))
                .map(Map.Entry::getKey)
                .findFirst()
                .orElse(null);
    }

    private void handleGetUserList(WebSocketSession session) throws Exception {
        String username = getUsernameFromSession(session);
        if (username == null) return;

        java.util.List<Map<String, Object>> responseList = new java.util.ArrayList<>();

        // 1. Get all friends (other users)
        java.util.List<User> users = userRepository.findAll();
        for (User u : users) {
            if (!u.getUsername().equals(username)) {
                // type: 0 for people
                responseList.add(Map.of(
                    "name", u.getUsername(),
                    "type", 0,
                    "actionTime", u.getCreatedAt().toString()
                ));
            }
        }

        // 2. Get all rooms this user is part of
        java.util.List<RoomMember> userRooms = roomMemberRepository.findByUsername(username);
        for (RoomMember rm : userRooms) {
            // type: 1 for room
            responseList.add(Map.of(
                "name", rm.getRoomName(),
                "type", 1,
                "actionTime", java.time.LocalDateTime.now().toString()
            ));
        }

        sendMessage(session, "success", "GET_USER_LIST", "User list retrieved", responseList);
    }

    private void handleLogin(WebSocketSession session, Map<String, Object> data) throws Exception {
        String username = (String) data.get("user");
        String password = (String) data.get("pass");
        
        var optUser = userRepository.findByUsername(username);
        if (optUser.isPresent() && optUser.get().getPassword().equals(password)) {
            userSessions.put(username, session);
            optUser.get().setStatus("ONLINE");
            userRepository.save(optUser.get());
            Map<String, String> payload = Map.of(
                "RE_LOGIN_CODE", "code_" + username,
                "user", username
            );
            sendMessage(session, "success", "LOGIN", "Login successful", payload);
        } else {
            sendMessage(session, "error", "LOGIN", "Invalid credentials", null);
        }
    }

    private void handleRegister(WebSocketSession session, Map<String, Object> data) throws Exception {
        String username = (String) data.get("user");
        String password = (String) data.get("pass");

        if (userRepository.findByUsername(username).isPresent()) {
            sendMessage(session, "error", "REGISTER", "User already exists", null);
        } else {
            User newUser = User.builder().username(username).password(password).status("OFFLINE").build();
            userRepository.save(newUser);
            sendMessage(session, "success", "REGISTER", "Registration successful", null);
        }
    }

    private void handleLogout(WebSocketSession session) throws Exception {
        userSessions.entrySet().removeIf(entry -> {
            if (entry.getValue().getId().equals(session.getId())) {
                userRepository.findByUsername(entry.getKey()).ifPresent(user -> {
                    user.setStatus("OFFLINE");
                    userRepository.save(user);
                });
                return true;
            }
            return false;
        });
    }

    private void handleSendChat(WebSocketSession session, Map<String, Object> data) throws Exception {
        String type = (String) data.get("type");
        String to = (String) data.get("to");
        String mes = (String) data.get("mes");

        String sender = getUsernameFromSession(session);

        if (sender != null) {
            Message message = Message.builder()
                    .type(type)
                    .sender(sender)
                    .receiver(to)
                    .content(mes)
                    .build();
            messageRepository.save(message);

            if ("people".equals(type)) {
                WebSocketSession recipientSession = userSessions.get(to);
                if (recipientSession != null && recipientSession.isOpen()) {
                    sendMessage(recipientSession, "success", "SEND_CHAT", "New message", message);
                }
            } else if ("room".equals(type)) {
                // Broadcast to room members (simplified to broadcast all for now)
                for (WebSocketSession s : userSessions.values()) {
                    if (s.isOpen()) {
                        sendMessage(s, "success", "SEND_CHAT", "New message", message);
                    }
                }
            }

            // Send confirmation back to sender
            sendMessage(session, "success", "SEND_CHAT", "Message sent", message);
        }
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

        Room newRoom = Room.builder().name(roomName).build();
        roomRepository.save(newRoom);
        sendMessage(session, "success", "CREATE_ROOM", "Room created", null);
    }

    private void handleJoinRoom(WebSocketSession session, Map<String, Object> data) throws Exception {
        String roomName = (String) data.get("name");
        String username = getUsernameFromSession(session);
        
        if (username == null || roomName == null) return;

        RoomMember rm = RoomMember.builder().roomName(roomName).username(username).build();
        roomMemberRepository.save(rm);

        java.util.List<RoomMember> members = roomMemberRepository.findByRoomName(roomName);
        java.util.List<String> userList = members.stream().map(RoomMember::getUsername).toList();

        Map<String, Object> responseData = Map.of(
            "name", roomName,
            "own", userList.isEmpty() ? username : userList.get(0),
            "userList", userList
        );

        sendMessage(session, "success", "JOIN_ROOM", "Joined room successfully", responseData);
    }

    private void handleCheckUserOnline(WebSocketSession session, Map<String, Object> data) throws Exception {
        String usernameToCheck = (String) data.get("user");
        boolean isOnline = userSessions.containsKey(usernameToCheck) && userSessions.get(usernameToCheck).isOpen();
        // The frontend uses checkOnline but the response is CHECK_USER_ONLINE and expects a boolean directly? No, it handles data differently.
        // Actually, frontend uses response.data? Let's send it in payload.
        // The frontend expects the response to have the same format but usually checkOnline just sends if it's true or false.
        sendMessage(session, "success", "CHECK_USER_ONLINE", "Status checked", Map.of("user", usernameToCheck, "status", isOnline));
    }

    private void handleCheckUserExist(WebSocketSession session, Map<String, Object> data) throws Exception {
        String usernameToCheck = (String) data.get("user");
        boolean exists = userRepository.findByUsername(usernameToCheck).isPresent();
        sendMessage(session, exists ? "success" : "error", "CHECK_USER_EXIST", exists ? "User exists" : "User not found", Map.of("user", usernameToCheck, "status", exists));
    }

    private void handleGetPeopleChatMes(WebSocketSession session, Map<String, Object> data) throws Exception {
        String username = getUsernameFromSession(session);
        String to = (String) data.get("name");
        // Integer page = (Integer) data.get("page"); // Ignore pagination for now, just return all
        
        if (username == null || to == null) return;

        java.util.List<Message> allMessages = messageRepository.findAll();
        java.util.List<Message> chatMessages = allMessages.stream()
            .filter(m -> "people".equals(m.getType()) && 
                    ((m.getSender().equals(username) && m.getReceiver().equals(to)) || 
                     (m.getSender().equals(to) && m.getReceiver().equals(username))))
            .toList();

        sendMessage(session, "success", "GET_PEOPLE_CHAT_MES", "Messages retrieved", chatMessages);
    }

    private void handleGetRoomChatMes(WebSocketSession session, Map<String, Object> data) throws Exception {
        String roomName = (String) data.get("name");
        if (roomName == null) return;

        java.util.List<Message> allMessages = messageRepository.findAll();
        java.util.List<Message> chatMessages = allMessages.stream()
            .filter(m -> "room".equals(m.getType()) && roomName.equals(m.getReceiver()))
            .toList();

        sendMessage(session, "success", "GET_ROOM_CHAT_MES", "Messages retrieved", chatMessages);
    }

    private void handleReLogin(WebSocketSession session, Map<String, Object> data) throws Exception {
        String username = (String) data.get("user");
        String code = (String) data.get("code"); // The code we sent earlier

        if (username != null && code != null && code.equals("code_" + username)) {
            userSessions.put(username, session);
            userRepository.findByUsername(username).ifPresent(user -> {
                user.setStatus("ONLINE");
                userRepository.save(user);
            });
            Map<String, String> payload = Map.of(
                "RE_LOGIN_CODE", code,
                "user", username
            );
            sendMessage(session, "success", "RE_LOGIN", "Re-login successful", payload);
        } else {
            sendMessage(session, "error", "RE_LOGIN", "Invalid re-login credentials", null);
        }
    }

    private void sendMessage(WebSocketSession session, String event, Object payload) throws Exception {
        ApiResponse<?> response = new ApiResponse<>(event, payload);
        session.sendMessage(new TextMessage(objectMapper.writeValueAsString(response)));
    }

    private void sendMessage(WebSocketSession session, String status, String event, String mes, Object payload) throws Exception {
        ApiResponse<?> response = new ApiResponse<>(status, event, mes, payload);
        session.sendMessage(new TextMessage(objectMapper.writeValueAsString(response)));
    }
}
