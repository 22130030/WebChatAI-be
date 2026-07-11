package com.appchat.backend.websocket;

import com.appchat.backend.entity.Message;
import com.appchat.backend.entity.RoomMember;
import com.appchat.backend.repository.MessageRepository;
import com.appchat.backend.repository.RoomMemberRepository;
import com.appchat.backend.repository.RoomRepository;
import com.appchat.backend.repository.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class CallWebSocketHandler {

    private final RoomRepository roomRepository;
    private final RoomMemberRepository roomMemberRepository;
    private final UserRepository userRepository;
    private final MessageRepository messageRepository;
    private final ObjectMapper objectMapper;

    public void handleCallInvite(
            ChatWebSocketHandler handler,
            WebSocketSession session,
            Map<String, Object> data
    ) throws Exception {

        String from = handler.getUsernameFromSession(session);
        String to = handler.readString(data, "to", "receiver", "user", "username", "name");
        String roomName = handler.readString(data, "roomName", "room", "groupName");
        String callType = normalizeCallType(handler.readString(data, "callType", "type"));
        String callId = handler.readString(data, "callId", "id");
        boolean isGroupCall = isGroupCallPayload(handler, data, roomName);

        if (from == null) {
            handler.sendMessage(session, "error", "CALL_INVITE", "Bạn cần đăng nhập trước khi gọi", null);
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
                handler.sendMessage(session, "error", "CALL_INVITE", "Nhóm không tồn tại", null);
                return;
            }

            if (!roomMemberRepository.existsByRoomNameAndUsername(roomName, from)) {
                handler.sendMessage(session, "error", "CALL_INVITE", "Bạn không thuộc nhóm này", null);
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

                WebSocketSession targetSession = handler.userSessions.get(memberUsername);
                if (targetSession != null && targetSession.isOpen()) {
                    hasOnlineReceiver = true;
                    handler.sendRealtimeToUser(memberUsername, "CALL_INVITE", "Có cuộc gọi nhóm đến", payload);
                }
            }

            if (!hasOnlineReceiver) {
                handler.sendMessage(session, "error", "CALL_INVITE", "Không có thành viên nào trong nhóm đang online", payload);
                return;
            }

            handler.sendMessage(session, "success", "CALL_INVITE_SENT", "Đã gửi lời mời gọi nhóm", payload);
            return;
        }

        if (to == null || to.equals(from)) {
            handler.sendMessage(session, "error", "CALL_INVITE", "Người nhận cuộc gọi không hợp lệ", null);
            return;
        }

        if (userRepository.findByUsername(to).isEmpty()) {
            handler.sendMessage(session, "error", "CALL_INVITE", "Người dùng không tồn tại", null);
            return;
        }

        Map<String, Object> payload = buildCallPayload(from, to, callId, callType, data);
        payload.put("isGroupCall", false);
        payload.put("chatType", "people");

        WebSocketSession targetSession = handler.userSessions.get(to);

        if (targetSession == null || !targetSession.isOpen()) {
            handler.sendMessage(session, "error", "CALL_INVITE", "Người dùng hiện đang offline", payload);
            return;
        }

        handler.sendRealtimeToUser(to, "CALL_INVITE", "Có cuộc gọi đến", payload);
        handler.sendMessage(session, "success", "CALL_INVITE_SENT", "Đã gửi lời mời gọi", payload);
    }

    public void handleCallControl(
            ChatWebSocketHandler handler,
            WebSocketSession session,
            Map<String, Object> data,
            String forwardEvent,
            String ackEvent,
            String defaultMessage
    ) throws Exception {

        String from = handler.getUsernameFromSession(session);
        String to = handler.readString(data, "to", "receiver", "user", "username", "name");
        String roomName = handler.readString(data, "roomName", "room", "groupName");
        String callType = normalizeCallType(handler.readString(data, "callType", "type"));
        String callId = handler.readString(data, "callId", "id");
        boolean isGroupCall = isGroupCallPayload(handler, data, roomName);

        if (from == null) {
            handler.sendMessage(session, "error", ackEvent, "Bạn cần đăng nhập trước", null);
            return;
        }

        if (callId == null) {
            handler.sendMessage(session, "error", ackEvent, "Dữ liệu cuộc gọi không hợp lệ", null);
            return;
        }

        if (isGroupCall) {
            if (roomName == null) {
                roomName = to;
            }

            if (roomName == null || roomRepository.findByName(roomName).isEmpty()) {
                handler.sendMessage(session, "error", ackEvent, "Nhóm không tồn tại", null);
                return;
            }

            if (!roomMemberRepository.existsByRoomNameAndUsername(roomName, from)) {
                handler.sendMessage(session, "error", ackEvent, "Bạn không thuộc nhóm này", null);
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

            handler.broadcastRoomData(roomName, from, forwardEvent, defaultMessage, payload);
            handler.sendMessage(session, "success", ackEvent, defaultMessage, payload);

            if (shouldCreateCallLog(forwardEvent)) {
                createAndBroadcastCallLog(handler, session, from, roomName, callId, callType, forwardEvent, data, true);
            }
            return;
        }

        if (to == null) {
            handler.sendMessage(session, "error", ackEvent, "Dữ liệu cuộc gọi không hợp lệ", null);
            return;
        }

        Map<String, Object> payload = buildCallPayload(from, to, callId, callType, data);
        payload.put("isGroupCall", false);
        payload.put("chatType", "people");

        Object reason = data == null ? null : data.get("reason");
        if (reason != null) {
            payload.put("reason", String.valueOf(reason));
        }

        handler.sendRealtimeToUser(to, forwardEvent, defaultMessage, payload);
        handler.sendMessage(session, "success", ackEvent, defaultMessage, payload);

        if (shouldCreateCallLog(forwardEvent)) {
            createAndBroadcastCallLog(handler, session, from, to, callId, callType, forwardEvent, data, false);
        }
    }

    private boolean shouldCreateCallLog(String event) {
        return "CALL_REJECTED".equals(event)
                || "CALL_CANCELED".equals(event)
                || "CALL_ENDED".equals(event);
    }

    private void createAndBroadcastCallLog(
            ChatWebSocketHandler handler,
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
        String reason = handler.readString(data, "reason");
        Long durationSeconds = handler.readLong(data, "durationSeconds", "duration", "durationInSeconds");

        if (durationSeconds == null || durationSeconds < 0) {
            durationSeconds = 0L;
        }

        String callStatus = resolveCallStatus(event, reason, durationSeconds);
        String caller = handler.readString(data, "caller", "callerUsername");
        String receiver = handler.readString(data, "receiver", "receiverUsername");

        if (caller == null || receiver == null) {
            if (isGroupCall) {
                caller = handler.readString(data, "caller", "callerUsername");
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

        Map<String, Object> messagePayload = handler.toClientMessage(savedMessage);

        handler.sendMessage(requesterSession, "success", "SEND_CHAT", "New message", messagePayload);

        if (isGroupCall) {
            handler.broadcastRoomData(to, from, "SEND_CHAT", "New message", messagePayload);
        } else {
            handler.sendRealtimeToUser(to, "SEND_CHAT", "New message", messagePayload);
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

    public void handleWebRtcSignal(
            ChatWebSocketHandler handler,
            WebSocketSession session,
            String event,
            Map<String, Object> data
    ) throws Exception {

        String from = handler.getUsernameFromSession(session);
        String to = handler.readString(data, "to", "receiver", "user", "username", "name");
        String roomName = handler.readString(data, "roomName", "room", "groupName");
        String callType = normalizeCallType(handler.readString(data, "callType", "type"));
        String callId = handler.readString(data, "callId", "id");
        boolean isGroupCall = isGroupCallPayload(handler, data, roomName);

        if (from == null) {
            handler.sendMessage(session, "error", event, "Bạn cần đăng nhập trước", null);
            return;
        }

        if (to == null || callId == null) {
            handler.sendMessage(session, "error", event, "Dữ liệu WebRTC không hợp lệ", null);
            return;
        }

        if (isGroupCall) {
            if (roomName == null || roomRepository.findByName(roomName).isEmpty()) {
                handler.sendMessage(session, "error", event, "Nhóm không tồn tại", null);
                return;
            }

            if (!roomMemberRepository.existsByRoomNameAndUsername(roomName, from)
                    || !roomMemberRepository.existsByRoomNameAndUsername(roomName, to)) {
                handler.sendMessage(session, "error", event, "Thành viên không thuộc nhóm này", null);
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

        WebSocketSession targetSession = handler.userSessions.get(to);

        if (targetSession == null || !targetSession.isOpen()) {
            handler.sendMessage(session, "error", event, "Người dùng hiện đang offline", payload);
            return;
        }

        handler.sendRealtimeToUser(to, event, "WebRTC signaling", payload);
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

    private boolean isGroupCallPayload(ChatWebSocketHandler handler, Map<String, Object> data, String roomName) {
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

        String chatType = handler.readString(data, "chatType");
        return "room".equalsIgnoreCase(chatType) || "group".equalsIgnoreCase(chatType);
    }
}
