package com.appchat.backend.websocket;

import com.appchat.backend.entity.User;
import com.appchat.backend.repository.UserRepository;
import com.appchat.backend.security.JwtUtil;
import com.appchat.backend.service.FriendshipService;
import com.appchat.backend.service.OnlineStatusService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

import java.util.Map;

@Component
@RequiredArgsConstructor
public class AccountWebSocketHandler {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;
    private final FriendshipService friendshipService;
    private final OnlineStatusService onlineStatusService;

    public void handleLogin(
            ChatWebSocketHandler handler,
            WebSocketSession session,
            Map<String, Object> data
    ) throws Exception {

        String username = handler.readString(data, "user", "username");
        String password = handler.readString(data, "pass", "password");

        if (username == null || password == null) {
            handler.sendMessage(
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
            handler.markUserOnline(username, session);

            String token = jwtUtil.generateToken(username);

            Map<String, Object> payload = handler.buildAuthPayload(optionalUser.get(), token);

            handler.sendMessage(session, "success", "LOGIN", "Login successful", payload);
        } else {
            handler.sendMessage(session, "error", "LOGIN", "Invalid credentials", null);
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

    public void handleRegister(
            ChatWebSocketHandler handler,
            WebSocketSession session,
            Map<String, Object> data
    ) throws Exception {

        String username = handler.readString(data, "user", "username");
        String password = handler.readString(data, "pass", "password");

        if (username == null || password == null) {
            handler.sendMessage(
                    session,
                    "error",
                    "REGISTER",
                    "Username và password không được để trống",
                    null
            );
            return;
        }

        if (userRepository.findByUsername(username).isPresent()) {
            handler.sendMessage(session, "error", "REGISTER", "User already exists", null);
            return;
        }

        User newUser = User.builder()
                .username(username)
                .password(passwordEncoder.encode(password))
                .displayName(username)
                .status("OFFLINE")
                .build();

        userRepository.save(newUser);

        handler.sendMessage(session, "success", "REGISTER", "Registration successful", null);
    }

    public void handleReLogin(
            ChatWebSocketHandler handler,
            WebSocketSession session,
            Map<String, Object> data
    ) throws Exception {

        String username = handler.readString(data, "user", "username");
        String token = handler.readString(data, "code", "token");

        if (username == null || token == null || !jwtUtil.isTokenValid(token)) {
            handler.sendMessage(session, "error", "RE_LOGIN", "Invalid re-login credentials", null);
            return;
        }

        String usernameFromToken = jwtUtil.getUsernameFromToken(token);

        if (!username.equals(usernameFromToken)) {
            handler.sendMessage(session, "error", "RE_LOGIN", "Invalid re-login credentials", null);
            return;
        }

        var optionalUser = userRepository.findByUsername(username);

        if (optionalUser.isEmpty()) {
            handler.sendMessage(session, "error", "RE_LOGIN", "Invalid re-login credentials", null);
            return;
        }

        User user = optionalUser.get();

        handler.markUserOnline(username, session);

        Map<String, Object> payload = handler.buildAuthPayload(user, token);

        handler.sendMessage(session, "success", "RE_LOGIN", "Re-login successful", payload);
    }

    public void handleLogout(ChatWebSocketHandler handler, WebSocketSession session) throws Exception {
        String username = handler.getUsernameFromSession(session);

        if (username != null) {
            WebSocketSession currentSession = handler.userSessions.get(username);

            if (currentSession != null && currentSession.getId().equals(session.getId())) {
                handler.userSessions.remove(username);
                handler.markUserOffline(username);
            }
        }

        handler.sendMessage(
                session,
                "success",
                "LOGOUT",
                "Logout successful",
                username == null ? null : Map.of("user", username)
        );
    }

    public void handleGetUserList(ChatWebSocketHandler handler, WebSocketSession session) throws Exception {
        String username = handler.getUsernameFromSession(session);

        if (username == null) {
            return;
        }

        java.util.List<Map<String, Object>> responseList = new java.util.ArrayList<>();

        for (var friendship : friendshipService.findFriendships(username)) {
            String friendUsername = friendshipService.otherUser(friendship, username);

            userRepository.findByUsername(friendUsername).ifPresent(user -> {
                Map<String, Object> userData = new java.util.LinkedHashMap<>();

                userData.put("name", user.getUsername());
                userData.put("username", user.getUsername());
                userData.put("displayName", handler.getEffectiveDisplayName(user));
                userData.put("avatar", user.getAvatar());
                userData.put("bio", user.getBio());
                userData.put("type", 0);

                boolean online = handler.isUserOnline(user.getUsername());
                userData.put("status", online ? "ONLINE" : "OFFLINE");
                userData.put("online", online);

                java.util.Optional<com.appchat.backend.entity.Message> lastMessage =
                        handler.messageRepository.findTopByTypeAndSenderAndReceiverOrTypeAndSenderAndReceiverOrderByCreatedAtDesc(
                                "people", username, friendUsername,
                                "people", friendUsername, username
                        );

                userData.put(
                        "actionTime",
                        lastMessage
                                .map(message -> message.getCreatedAt().toString())
                                .orElse(friendship.getCreatedAt() != null
                                        ? friendship.getCreatedAt().toString()
                                        : java.time.LocalDateTime.MIN.toString())
                );

                lastMessage.ifPresent(message -> {
                    userData.put("lastMessage", message.getContent());
                    userData.put("lastSender", message.getSender());
                });

                responseList.add(userData);
            });
        }

        for (com.appchat.backend.entity.RoomMember roomMember : handler.roomMemberRepository.findByUsername(username)) {
            String roomName = roomMember.getRoomName();

            if (handler.roomRepository.findByName(roomName).isEmpty()) {
                continue;
            }

            Map<String, Object> roomData = handler.buildRoomData(roomName);
            roomData.put("currentUserRole", handler.normalizeRoomRole(roomMember.getRole()));

            java.util.Optional<com.appchat.backend.entity.Message> lastRoomMessage =
                    handler.messageRepository.findTopByTypeAndReceiverOrderByCreatedAtDesc(
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
                            .orElse(java.time.LocalDateTime.MIN.toString())
            );

            lastRoomMessage.ifPresent(message -> {
                roomData.put("lastMessage", message.getContent());
                roomData.put("lastSender", message.getSender());
            });

            responseList.add(roomData);
        }

        handler.sendMessage(
                session,
                "success",
                "GET_USER_LIST",
                "User list retrieved",
                responseList
        );
    }

    public void handleCheckUserOnline(
            ChatWebSocketHandler handler,
            WebSocketSession session,
            Map<String, Object> data
    ) throws Exception {

        String usernameToCheck = handler.readString(data, "user", "username");

        if (usernameToCheck == null) {
            handler.sendMessage(
                    session,
                    "error",
                    "CHECK_USER_ONLINE",
                    "Username không hợp lệ",
                    null
            );
            return;
        }

        boolean online = handler.isUserOnline(usernameToCheck);

        handler.sendMessage(
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

    public void handleCheckUserExist(
            ChatWebSocketHandler handler,
            WebSocketSession session,
            Map<String, Object> data
    ) throws Exception {

        String usernameToCheck = handler.readString(data, "user", "username");

        if (usernameToCheck == null) {
            handler.sendMessage(
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

        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("user", usernameToCheck);
        payload.put("username", usernameToCheck);
        optionalUser.ifPresent(user -> payload.putAll(handler.buildUserPayload(user)));
        payload.put("status", exists);
        payload.put("exists", exists);

        handler.sendMessage(
                session,
                exists ? "success" : "error",
                "CHECK_USER_EXIST",
                exists ? "User exists" : "User not found",
                payload
        );
    }

    public void handleGetProfile(
            ChatWebSocketHandler handler,
            WebSocketSession session,
            Map<String, Object> data
    ) throws Exception {

        String requester = handler.getUsernameFromSession(session);
        String usernameToView = handler.readString(data, "user", "username", "name");

        if (requester == null) {
            handler.sendMessage(session, "error", "GET_PROFILE", "Bạn cần đăng nhập trước", null);
            return;
        }

        if (usernameToView == null) {
            usernameToView = requester;
        }

        var optionalUser = userRepository.findByUsername(usernameToView);

        if (optionalUser.isEmpty()) {
            handler.sendMessage(session, "error", "GET_PROFILE", "Không tìm thấy người dùng", null);
            return;
        }

        handler.sendMessage(
                session,
                "success",
                "GET_PROFILE",
                "Thông tin cá nhân",
                handler.buildUserPayload(optionalUser.get())
        );
    }

    public void handleUpdateProfile(
            ChatWebSocketHandler handler,
            WebSocketSession session,
            Map<String, Object> data
    ) throws Exception {

        String username = handler.getUsernameFromSession(session);

        if (username == null) {
            handler.sendMessage(session, "error", "UPDATE_PROFILE", "Bạn cần đăng nhập trước", null);
            return;
        }

        var optionalUser = userRepository.findByUsername(username);

        if (optionalUser.isEmpty()) {
            handler.sendMessage(session, "error", "UPDATE_PROFILE", "Không tìm thấy người dùng", null);
            return;
        }

        User user = optionalUser.get();

        if (data != null && data.containsKey("displayName")) {
            user.setDisplayName(handler.cleanText(data.get("displayName"), 100));
        }

        if (data != null && (data.containsKey("avatar") || data.containsKey("avatarUrl"))) {
            Object rawAvatar = data.containsKey("avatar") ? data.get("avatar") : data.get("avatarUrl");
            user.setAvatar(handler.cleanText(rawAvatar, 1000));
        }

        if (data != null && data.containsKey("bio")) {
            user.setBio(handler.cleanText(data.get("bio"), 500));
        }

        User savedUser = userRepository.save(user);
        Map<String, Object> payload = handler.buildUserPayload(savedUser);

        handler.sendMessage(
                session,
                "success",
                "UPDATE_PROFILE",
                "Cập nhật thông tin cá nhân thành công",
                payload
        );

        for (var friendship : friendshipService.findFriendships(username)) {
            String friendUsername = friendshipService.otherUser(friendship, username);

            handler.sendRealtimeToUser(
                    friendUsername,
                    "PROFILE_UPDATED",
                    username + " đã cập nhật thông tin cá nhân",
                    payload
            );

            handler.refreshUserListForOnlineUser(friendUsername);
        }

        handler.refreshUserListForOnlineUser(username);
    }
}
