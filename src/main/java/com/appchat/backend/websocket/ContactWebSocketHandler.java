package com.appchat.backend.websocket;

import com.appchat.backend.entity.PendingConversation;
import com.appchat.backend.repository.PendingConversationRepository;
import com.appchat.backend.repository.UserRepository;
import com.appchat.backend.service.FriendshipService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class ContactWebSocketHandler {

    private final UserRepository userRepository;
    private final FriendshipService friendshipService;
    private final PendingConversationRepository pendingConversationRepository;

    public void handleRemoveContact(
            ChatWebSocketHandler handler,
            WebSocketSession session,
            Map<String, Object> data
    ) throws Exception {

        String currentUser = handler.getUsernameFromSession(session);
        String otherUser = handler.readString(data, "user", "username", "name", "to", "toUsername");

        if (currentUser == null) {
            handler.sendMessage(session, "error", "REMOVE_CONTACT", "Bạn cần đăng nhập trước", null);
            return;
        }

        if (otherUser == null || currentUser.equals(otherUser)) {
            handler.sendMessage(session, "error", "REMOVE_CONTACT", "Username không hợp lệ", null);
            return;
        }

        if (!friendshipService.areFriends(currentUser, otherUser)) {
            handler.sendMessage(session, "error", "REMOVE_CONTACT", "Không tìm thấy liên hệ", null);
            return;
        }

        friendshipService.removeFriendship(currentUser, otherUser);

        Map<String, Object> payload = handler.buildFriendshipPayload(currentUser, otherUser);

        handler.sendMessage(session, "success", "REMOVE_CONTACT", "Đã xóa liên hệ", payload);
        handler.sendRealtimeToUser(otherUser, "CONTACT_REMOVED", currentUser + " đã xóa liên hệ", payload);

        handler.accountWebSocketHandler.handleGetUserList(handler, session);

        WebSocketSession otherSession = handler.userSessions.get(otherUser);
        if (otherSession != null && otherSession.isOpen()) {
            handler.accountWebSocketHandler.handleGetUserList(handler, otherSession);
        }
    }

    public void handleSendContactRequest(
            ChatWebSocketHandler handler,
            WebSocketSession session,
            Map<String, Object> data
    ) throws Exception {

        String from = handler.getUsernameFromSession(session);
        String to = handler.readString(data, "to", "user", "username", "name", "receiver");

        if (from == null) {
            handler.sendMessage(session, "error", "SEND_CONTACT_REQUEST", "Bạn cần đăng nhập trước", null);
            return;
        }

        if (to == null) {
            handler.sendMessage(session, "error", "SEND_CONTACT_REQUEST", "Username cần liên hệ không hợp lệ", null);
            return;
        }

        if (from.equals(to)) {
            handler.sendMessage(session, "error", "SEND_CONTACT_REQUEST", "Bạn không thể tự gửi lời mời cho chính mình", null);
            return;
        }

        if (userRepository.findByUsername(to).isEmpty()) {
            handler.sendMessage(session, "error", "SEND_CONTACT_REQUEST", "Người dùng không tồn tại", null);
            return;
        }

        if (friendshipService.areFriends(from, to)) {
            handler.sendMessage(session, "success", "SEND_CONTACT_REQUEST", "Hai người đã có trong danh sách liên hệ", handler.buildFriendshipPayload(from, to));
            return;
        }

        Optional<PendingConversation> existingRequest = pendingConversationRepository.findBetweenUsers(from, to);

        if (existingRequest.isPresent()) {
            PendingConversation existing = existingRequest.get();
            Map<String, Object> payload = handler.toClientPendingConversation(existing);

            if (from.equals(existing.getFromUsername())) {
                handler.sendMessage(session, "success", "SEND_CONTACT_REQUEST", "Bạn đã gửi lời mời liên hệ trước đó", payload);
            } else {
                handler.sendMessage(session, "success", "SEND_CONTACT_REQUEST", "Người này đã gửi lời mời cho bạn. Hãy chấp nhận lời mời.", payload);
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

        Map<String, Object> payload = handler.toClientPendingConversation(pc);

        handler.sendMessage(session, "success", "SEND_CONTACT_REQUEST", "Đã gửi lời mời liên hệ", payload);
        handler.sendRealtimeToUser(to, "CONTACT_REQUEST_RECEIVED", "Bạn có lời mời liên hệ mới", payload);
    }

    public void handleGetContactRequests(ChatWebSocketHandler handler, WebSocketSession session) throws Exception {
        String username = handler.getUsernameFromSession(session);

        if (username == null) {
            handler.sendMessage(session, "error", "GET_CONTACT_REQUESTS", "Bạn cần đăng nhập trước", null);
            return;
        }

        Map<String, Object> payload = new LinkedHashMap<>();

        payload.put("incoming", pendingConversationRepository
                .findByToUsernameAndStatus(username, "PENDING")
                .stream()
                .map(handler::toClientPendingConversation)
                .toList());

        payload.put("outgoing", pendingConversationRepository
                .findByFromUsernameAndStatus(username, "PENDING")
                .stream()
                .map(handler::toClientPendingConversation)
                .toList());

        payload.put("accepted", friendshipService
                .findFriendships(username)
                .stream()
                .map(friendship -> handler.buildFriendshipPayload(username, friendshipService.otherUser(friendship, username)))
                .toList());

        handler.sendMessage(session, "success", "GET_CONTACT_REQUESTS", "Danh sách lời mời liên hệ", payload);
    }

    public void handleAcceptContactRequest(
            ChatWebSocketHandler handler,
            WebSocketSession session,
            Map<String, Object> data
    ) throws Exception {

        String currentUser = handler.getUsernameFromSession(session);
        String from = handler.readString(data, "from", "fromUsername", "user", "username", "name");

        if (currentUser == null) {
            handler.sendMessage(session, "error", "ACCEPT_CONTACT_REQUEST", "Bạn cần đăng nhập trước", null);
            return;
        }

        if (from == null) {
            handler.sendMessage(session, "error", "ACCEPT_CONTACT_REQUEST", "Người gửi lời mời không hợp lệ", null);
            return;
        }

        Optional<PendingConversation> pending =
                pendingConversationRepository.findByFromUsernameAndToUsername(from, currentUser);

        if (pending.isEmpty() || !"PENDING".equals(pending.get().getStatus())) {
            handler.sendMessage(session, "error", "ACCEPT_CONTACT_REQUEST", "Không tìm thấy lời mời đang chờ", null);
            return;
        }

        PendingConversation pc = pending.get();
        friendshipService.createFriendship(from, currentUser);
        pendingConversationRepository.delete(pc);

        Map<String, Object> payload = handler.buildFriendshipPayload(from, currentUser);

        handler.sendMessage(session, "success", "ACCEPT_CONTACT_REQUEST", "Đã chấp nhận lời mời liên hệ", payload);
        handler.sendRealtimeToUser(from, "CONTACT_REQUEST_ACCEPTED", currentUser + " đã chấp nhận lời mời liên hệ", payload);

        handler.accountWebSocketHandler.handleGetUserList(handler, session);

        WebSocketSession fromSession = handler.userSessions.get(from);
        if (fromSession != null && fromSession.isOpen()) {
            handler.accountWebSocketHandler.handleGetUserList(handler, fromSession);
        }
    }

    public void handleDeleteContactRequest(
            ChatWebSocketHandler handler,
            WebSocketSession session,
            Map<String, Object> data
    ) throws Exception {

        String currentUser = handler.getUsernameFromSession(session);
        String otherUser = handler.readString(data, "user", "username", "name", "from", "fromUsername", "to", "toUsername");

        if (currentUser == null) {
            handler.sendMessage(session, "error", "DELETE_CONTACT_REQUEST", "Bạn cần đăng nhập trước", null);
            return;
        }

        if (otherUser == null) {
            handler.sendMessage(session, "error", "DELETE_CONTACT_REQUEST", "Username không hợp lệ", null);
            return;
        }

        Optional<PendingConversation> pending =
                pendingConversationRepository.findBetweenUsers(currentUser, otherUser);

        if (pending.isEmpty()) {
            handler.sendMessage(session, "error", "DELETE_CONTACT_REQUEST", "Không tìm thấy lời mời liên hệ", null);
            return;
        }

        PendingConversation pc = pending.get();

        if (
                !currentUser.equals(pc.getFromUsername()) &&
                        !currentUser.equals(pc.getToUsername())
        ) {
            handler.sendMessage(session, "error", "DELETE_CONTACT_REQUEST", "Bạn không có quyền xóa lời mời này", null);
            return;
        }

        Map<String, Object> payload = handler.toClientPendingConversation(pc);

        pendingConversationRepository.delete(pc);

        handler.sendMessage(session, "success", "DELETE_CONTACT_REQUEST", "Đã xóa lời mời liên hệ", payload);
        handler.sendRealtimeToUser(otherUser, "CONTACT_REQUEST_DELETED", "Lời mời liên hệ đã bị xóa", payload);
    }
}
