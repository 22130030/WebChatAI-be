package com.appchat.backend.websocket;

import com.appchat.backend.entity.Room;
import com.appchat.backend.entity.RoomMember;
import com.appchat.backend.repository.GroupThemeRepository;
import com.appchat.backend.repository.MessageRepository;
import com.appchat.backend.repository.RoomMemberRepository;
import com.appchat.backend.repository.RoomRepository;
import com.appchat.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class RoomWebSocketHandler {

    private final RoomRepository roomRepository;
    private final RoomMemberRepository roomMemberRepository;
    private final UserRepository userRepository;
    private final MessageRepository messageRepository;
    private final GroupThemeRepository groupThemeRepository;

    public void handleCreateRoom(
            ChatWebSocketHandler handler,
            WebSocketSession session,
            Map<String, Object> data
    ) throws Exception {

        String creator = handler.getUsernameFromSession(session);
        String roomName = handler.readString(data, "name", "roomName", "room");

        if (creator == null) {
            handler.sendMessage(session, "error", "CREATE_ROOM", "Bạn cần đăng nhập trước", null);
            return;
        }

        if (roomName == null || roomName.isBlank()) {
            handler.sendMessage(session, "error", "CREATE_ROOM", "Tên nhóm không được để trống", null);
            return;
        }

        roomName = roomName.trim();

        if (roomRepository.findByName(roomName).isPresent()) {
            handler.sendMessage(session, "error", "CREATE_ROOM", "Tên nhóm đã tồn tại", null);
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

        Map<String, Object> roomData = handler.buildRoomData(roomName);
        roomData.put("currentUserRole", RoomMember.ROLE_OWNER);

        handler.sendMessage(session, "success", "CREATE_ROOM", "Tạo nhóm thành công", roomData);
        handler.accountWebSocketHandler.handleGetUserList(handler, session);
    }

    public void handleJoinRoom(
            ChatWebSocketHandler handler,
            WebSocketSession session,
            Map<String, Object> data
    ) throws Exception {

        String username = handler.getUsernameFromSession(session);
        String roomName = handler.readString(data, "name", "roomName", "room");

        if (username == null || roomName == null) {
            handler.sendMessage(session, "error", "JOIN_ROOM", "Thông tin nhóm không hợp lệ", null);
            return;
        }

        if (roomRepository.findByName(roomName).isEmpty()) {
            handler.sendMessage(session, "error", "JOIN_ROOM", "Nhóm không tồn tại", null);
            return;
        }

        Optional<RoomMember> currentMember =
                roomMemberRepository.findByRoomNameAndUsername(roomName, username);

        if (currentMember.isEmpty()) {
            handler.sendMessage(session, "error", "JOIN_ROOM", "Bạn chưa được thêm vào nhóm này", null);
            return;
        }

        Map<String, Object> roomData = handler.buildRoomData(roomName);
        roomData.put("currentUserRole", handler.normalizeRoomRole(currentMember.get().getRole()));

        handler.sendMessage(
                session,
                "success",
                "JOIN_ROOM",
                "Joined room successfully",
                roomData
        );
    }

    public void handleAddUserToRoom(
            ChatWebSocketHandler handler,
            WebSocketSession session,
            Map<String, Object> data
    ) throws Exception {

        String requester = handler.getUsernameFromSession(session);
        String roomName = handler.readString(data, "name", "roomName", "room");
        String newUsername = handler.readString(data, "user", "username", "member", "targetUsername");

        if (requester == null) {
            handler.sendMessage(session, "error", "ADD_USER_TO_ROOM", "Bạn cần đăng nhập trước", null);
            return;
        }

        if (roomName == null || newUsername == null) {
            handler.sendMessage(session, "error", "ADD_USER_TO_ROOM", "Tên nhóm hoặc username không hợp lệ", null);
            return;
        }

        if (roomRepository.findByName(roomName).isEmpty()) {
            handler.sendMessage(session, "error", "ADD_USER_TO_ROOM", "Nhóm không tồn tại", null);
            return;
        }

        Optional<RoomMember> requesterMember =
                roomMemberRepository.findByRoomNameAndUsername(roomName, requester);

        if (requesterMember.isEmpty()) {
            handler.sendMessage(session, "error", "ADD_USER_TO_ROOM", "Bạn không thuộc nhóm này", null);
            return;
        }

        if (requester.equals(newUsername)) {
            handler.sendMessage(session, "error", "ADD_USER_TO_ROOM", "Bạn đã có trong nhóm", null);
            return;
        }

        if (userRepository.findByUsername(newUsername).isEmpty()) {
            handler.sendMessage(session, "error", "ADD_USER_TO_ROOM", "User cần thêm không tồn tại", null);
            return;
        }

        if (roomMemberRepository.existsByRoomNameAndUsername(roomName, newUsername)) {
            handler.sendMessage(session, "error", "ADD_USER_TO_ROOM", "User đã có trong nhóm", null);
            return;
        }

        RoomMember newMember = RoomMember.builder()
                .roomName(roomName)
                .username(newUsername)
                .role(RoomMember.ROLE_MEMBER)
                .build();

        roomMemberRepository.save(newMember);

        Map<String, Object> roomData = handler.buildRoomData(roomName);
        roomData.put("addedUser", newUsername);
        roomData.put("actor", requester);

        handler.sendMessage(session, "success", "ADD_USER_TO_ROOM", "Thêm thành viên thành công", roomData);

        for (RoomMember member : roomMemberRepository.findByRoomName(roomName)) {
            if (member.getUsername().equals(requester)) {
                handler.refreshUserListForOnlineUser(member.getUsername());
                continue;
            }

            handler.sendRealtimeToUser(
                    member.getUsername(),
                    member.getUsername().equals(newUsername) ? "ADDED_TO_ROOM" : "ROOM_MEMBER_ADDED",
                    member.getUsername().equals(newUsername)
                            ? "Bạn đã được thêm vào nhóm " + roomName
                            : newUsername + " đã được thêm vào nhóm",
                    roomData
            );

            handler.refreshUserListForOnlineUser(member.getUsername());
        }
    }

    public void handleGetRoomMembers(
            ChatWebSocketHandler handler,
            WebSocketSession session,
            Map<String, Object> data
    ) throws Exception {

        String requester = handler.getUsernameFromSession(session);
        String roomName = handler.readString(data, "name", "roomName", "room");

        if (requester == null || roomName == null) {
            handler.sendMessage(session, "error", "GET_ROOM_MEMBERS", "Thông tin nhóm không hợp lệ", null);
            return;
        }

        Optional<RoomMember> requesterMember =
                roomMemberRepository.findByRoomNameAndUsername(roomName, requester);

        if (requesterMember.isEmpty()) {
            handler.sendMessage(session, "error", "GET_ROOM_MEMBERS", "Bạn không thuộc nhóm này", null);
            return;
        }

        Map<String, Object> roomData = handler.buildRoomData(roomName);
        roomData.put("currentUserRole", handler.normalizeRoomRole(requesterMember.get().getRole()));

        handler.sendMessage(
                session,
                "success",
                "GET_ROOM_MEMBERS",
                "Lấy danh sách thành viên thành công",
                roomData
        );
    }

    public void handleSetRoomDeputy(
            ChatWebSocketHandler handler,
            WebSocketSession session,
            Map<String, Object> data
    ) throws Exception {

        String requester = handler.getUsernameFromSession(session);
        String roomName = handler.readString(data, "name", "roomName", "room");
        String targetUsername = handler.readString(data, "user", "username", "member", "targetUsername");

        if (requester == null) {
            handler.sendMessage(session, "error", "SET_ROOM_DEPUTY", "Bạn cần đăng nhập trước", null);
            return;
        }

        if (roomName == null || targetUsername == null) {
            handler.sendMessage(session, "error", "SET_ROOM_DEPUTY", "Thông tin nhóm hoặc thành viên không hợp lệ", null);
            return;
        }

        Optional<RoomMember> requesterMember =
                roomMemberRepository.findByRoomNameAndUsername(roomName, requester);
        Optional<RoomMember> targetMember =
                roomMemberRepository.findByRoomNameAndUsername(roomName, targetUsername);

        if (requesterMember.isEmpty() || targetMember.isEmpty()) {
            handler.sendMessage(session, "error", "SET_ROOM_DEPUTY", "Thành viên không tồn tại trong nhóm", null);
            return;
        }

        if (!isRoomOwner(handler, requesterMember.get())) {
            handler.sendMessage(session, "error", "SET_ROOM_DEPUTY", "Chỉ trưởng nhóm mới được cấp phó nhóm", null);
            return;
        }

        if (isRoomOwner(handler, targetMember.get())) {
            handler.sendMessage(session, "error", "SET_ROOM_DEPUTY", "Không thể cấp phó cho trưởng nhóm", null);
            return;
        }

        roomMemberRepository.updateRole(roomName, targetUsername, RoomMember.ROLE_DEPUTY);

        Map<String, Object> roomData = handler.buildRoomData(roomName);
        roomData.put("targetUser", targetUsername);
        roomData.put("targetRole", RoomMember.ROLE_DEPUTY);
        roomData.put("actor", requester);

        handler.sendMessage(session, "success", "SET_ROOM_DEPUTY", "Đã cấp phó nhóm", roomData);
        handler.broadcastRoomData(roomName, requester, "ROOM_ROLE_UPDATED", "Vai trò thành viên đã được cập nhật", roomData);
        handler.refreshUserListsInRoom(roomName);
    }

    public void handleRemoveRoomDeputy(
            ChatWebSocketHandler handler,
            WebSocketSession session,
            Map<String, Object> data
    ) throws Exception {

        String requester = handler.getUsernameFromSession(session);
        String roomName = handler.readString(data, "name", "roomName", "room");
        String targetUsername = handler.readString(data, "user", "username", "member", "targetUsername");

        if (requester == null) {
            handler.sendMessage(session, "error", "REMOVE_ROOM_DEPUTY", "Bạn cần đăng nhập trước", null);
            return;
        }

        if (roomName == null || targetUsername == null) {
            handler.sendMessage(session, "error", "REMOVE_ROOM_DEPUTY", "Thông tin nhóm hoặc thành viên không hợp lệ", null);
            return;
        }

        Optional<RoomMember> requesterMember =
                roomMemberRepository.findByRoomNameAndUsername(roomName, requester);
        Optional<RoomMember> targetMember =
                roomMemberRepository.findByRoomNameAndUsername(roomName, targetUsername);

        if (requesterMember.isEmpty() || targetMember.isEmpty()) {
            handler.sendMessage(session, "error", "REMOVE_ROOM_DEPUTY", "Thành viên không tồn tại trong nhóm", null);
            return;
        }

        if (!isRoomOwner(handler, requesterMember.get())) {
            handler.sendMessage(session, "error", "REMOVE_ROOM_DEPUTY", "Chỉ trưởng nhóm mới được hủy phó nhóm", null);
            return;
        }

        if (isRoomOwner(handler, targetMember.get())) {
            handler.sendMessage(session, "error", "REMOVE_ROOM_DEPUTY", "Không thể hủy vai trò trưởng nhóm", null);
            return;
        }

        roomMemberRepository.updateRole(roomName, targetUsername, RoomMember.ROLE_MEMBER);

        Map<String, Object> roomData = handler.buildRoomData(roomName);
        roomData.put("targetUser", targetUsername);
        roomData.put("targetRole", RoomMember.ROLE_MEMBER);
        roomData.put("actor", requester);

        handler.sendMessage(session, "success", "REMOVE_ROOM_DEPUTY", "Đã hủy phó nhóm", roomData);
        handler.broadcastRoomData(roomName, requester, "ROOM_ROLE_UPDATED", "Vai trò thành viên đã được cập nhật", roomData);
        handler.refreshUserListsInRoom(roomName);
    }

    public void handleRemoveRoomMember(
            ChatWebSocketHandler handler,
            WebSocketSession session,
            Map<String, Object> data
    ) throws Exception {

        String requester = handler.getUsernameFromSession(session);
        String roomName = handler.readString(data, "name", "roomName", "room");
        String targetUsername = handler.readString(data, "user", "username", "member", "targetUsername");

        if (requester == null) {
            handler.sendMessage(session, "error", "REMOVE_ROOM_MEMBER", "Bạn cần đăng nhập trước", null);
            return;
        }

        if (roomName == null || targetUsername == null) {
            handler.sendMessage(session, "error", "REMOVE_ROOM_MEMBER", "Thông tin nhóm hoặc thành viên không hợp lệ", null);
            return;
        }

        Optional<RoomMember> requesterMember =
                roomMemberRepository.findByRoomNameAndUsername(roomName, requester);
        Optional<RoomMember> targetMember =
                roomMemberRepository.findByRoomNameAndUsername(roomName, targetUsername);

        if (requesterMember.isEmpty()) {
            handler.sendMessage(session, "error", "REMOVE_ROOM_MEMBER", "Bạn không thuộc nhóm này", null);
            return;
        }

        if (targetMember.isEmpty()) {
            handler.sendMessage(session, "error", "REMOVE_ROOM_MEMBER", "Thành viên cần xóa không tồn tại trong nhóm", null);
            return;
        }

        if (!isRoomManager(handler, requesterMember.get())) {
            handler.sendMessage(session, "error", "REMOVE_ROOM_MEMBER", "Chỉ trưởng nhóm hoặc phó nhóm mới được xóa thành viên", null);
            return;
        }

        if (requester.equals(targetUsername)) {
            handler.sendMessage(session, "error", "REMOVE_ROOM_MEMBER", "Muốn tự rời nhóm hãy dùng chức năng Rời khỏi phòng chat", null);
            return;
        }

        if (isRoomOwner(handler, targetMember.get())) {
            handler.sendMessage(session, "error", "REMOVE_ROOM_MEMBER", "Không thể xóa trưởng nhóm", null);
            return;
        }

        if (isRoomDeputy(handler, targetMember.get()) && !isRoomOwner(handler, requesterMember.get())) {
            handler.sendMessage(session, "error", "REMOVE_ROOM_MEMBER", "Phó nhóm không thể xóa phó nhóm khác", null);
            return;
        }

        roomMemberRepository.deleteMemberFromRoom(roomName, targetUsername);

        Map<String, Object> roomData = handler.buildRoomData(roomName);
        roomData.put("removedUser", targetUsername);
        roomData.put("actor", requester);

        handler.sendMessage(session, "success", "REMOVE_ROOM_MEMBER", "Đã xóa thành viên khỏi nhóm", roomData);

        handler.sendRealtimeToUser(
                targetUsername,
                "ROOM_MEMBER_REMOVED_FROM_ROOM",
                "Bạn đã bị xóa khỏi nhóm " + roomName,
                roomData
        );
        handler.refreshUserListForOnlineUser(targetUsername);

        handler.broadcastRoomData(roomName, requester, "ROOM_MEMBER_REMOVED", targetUsername + " đã bị xóa khỏi nhóm", roomData);
        handler.refreshUserListsInRoom(roomName);
    }

    public void handleRenameRoom(
            ChatWebSocketHandler handler,
            WebSocketSession session,
            Map<String, Object> data
    ) throws Exception {

        String requester = handler.getUsernameFromSession(session);
        String oldName = handler.readString(data, "oldName", "name", "roomName");
        String newName = handler.readString(data, "newName", "newRoomName");

        if (requester == null) {
            handler.sendMessage(session, "error", "RENAME_ROOM", "Bạn cần đăng nhập trước", null);
            return;
        }

        if (oldName == null || newName == null) {
            handler.sendMessage(
                    session,
                    "error",
                    "RENAME_ROOM",
                    "Tên nhóm cũ hoặc tên nhóm mới không hợp lệ",
                    null
            );
            return;
        }

        if (oldName.equalsIgnoreCase(newName)) {
            handler.sendMessage(
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
            handler.sendMessage(session, "error", "RENAME_ROOM", "Nhóm không tồn tại", null);
            return;
        }

        if (!roomMemberRepository.existsByRoomNameAndUsername(oldName, requester)) {
            handler.sendMessage(session, "error", "RENAME_ROOM", "Bạn không thuộc nhóm này", null);
            return;
        }

        if (roomRepository.findByName(newName).isPresent()) {
            handler.sendMessage(session, "error", "RENAME_ROOM", "Tên nhóm này đã tồn tại", null);
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

        Map<String, Object> roomData = handler.buildRoomData(newName);
        roomData.put("oldName", oldName);
        roomData.put("newName", newName);

        handler.sendMessage(session, "success", "RENAME_ROOM", "Đổi tên nhóm thành công", roomData);

        for (String member : members) {
            if (!requester.equals(member)) {
                handler.sendRealtimeToUser(
                        member,
                        "ROOM_RENAMED",
                        "Tên nhóm đã được thay đổi",
                        roomData
                );
            }

            handler.refreshUserListForOnlineUser(member);
        }
    }

    public void handleLeaveRoom(
            ChatWebSocketHandler handler,
            WebSocketSession session,
            Map<String, Object> data
    ) throws Exception {

        String requester = handler.getUsernameFromSession(session);
        String roomName = handler.readString(data, "name", "roomName", "room");
        String newOwnerUsername = handler.readString(data, "newOwner", "newOwnerUsername", "ownerUsername");

        if (requester == null) {
            handler.sendMessage(session, "error", "LEAVE_ROOM", "Bạn cần đăng nhập trước", null);
            return;
        }

        if (roomName == null || roomName.isBlank()) {
            handler.sendMessage(session, "error", "LEAVE_ROOM", "Tên nhóm không hợp lệ", null);
            return;
        }

        roomName = roomName.trim();

        var roomOptional = roomRepository.findByName(roomName);

        if (roomOptional.isEmpty()) {
            handler.sendMessage(session, "error", "LEAVE_ROOM", "Nhóm không tồn tại", null);
            return;
        }

        Optional<RoomMember> requesterMemberOptional =
                roomMemberRepository.findByRoomNameAndUsername(roomName, requester);

        if (requesterMemberOptional.isEmpty()) {
            handler.sendMessage(session, "error", "LEAVE_ROOM", "Bạn không thuộc nhóm này", null);
            return;
        }

        Room room = roomOptional.get();
        RoomMember requesterMember = requesterMemberOptional.get();

        boolean requesterIsOwner = isRoomOwner(handler, requesterMember)
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

            handler.sendMessage(
                    session,
                    "success",
                    "LEAVE_ROOM",
                    "Bạn đã rời nhóm. Nhóm trống nên đã được xóa.",
                    leaveData
            );

            handler.accountWebSocketHandler.handleGetUserList(handler, session);
            return;
        }

        if (requesterIsOwner) {
            if (newOwnerUsername == null || newOwnerUsername.isBlank()) {
                handler.sendMessage(session, "error", "LEAVE_ROOM", "Trưởng nhóm phải chọn người nhận quyền trước khi rời nhóm", null);
                return;
            }

            newOwnerUsername = newOwnerUsername.trim();

            if (requester.equals(newOwnerUsername)) {
                handler.sendMessage(session, "error", "LEAVE_ROOM", "Người nhận quyền phải là thành viên khác", null);
                return;
            }

            Optional<RoomMember> newOwnerMember =
                    roomMemberRepository.findByRoomNameAndUsername(roomName, newOwnerUsername);

            if (newOwnerMember.isEmpty()) {
                handler.sendMessage(session, "error", "LEAVE_ROOM", "Người nhận quyền không thuộc nhóm này", null);
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

        handler.sendMessage(
                session,
                "success",
                "LEAVE_ROOM",
                requesterIsOwner
                        ? "Bạn đã rời nhóm và nhường quyền trưởng nhóm thành công"
                        : "Rời khỏi phòng chat thành công",
                leaveData
        );

        Map<String, Object> roomData = handler.buildRoomData(roomName);
        roomData.put("leftUser", requester);

        if (requesterIsOwner) {
            roomData.put("newOwner", newOwnerUsername);
            roomData.put("newOwnerUsername", newOwnerUsername);
        }

        for (RoomMember member : remainingMembers) {
            handler.sendRealtimeToUser(
                    member.getUsername(),
                    requesterIsOwner ? "ROOM_OWNER_CHANGED" : "ROOM_MEMBER_LEFT",
                    requesterIsOwner
                            ? requester + " đã rời nhóm và nhường quyền trưởng nhóm cho " + newOwnerUsername
                            : requester + " đã rời khỏi nhóm",
                    roomData
            );
            handler.refreshUserListForOnlineUser(member.getUsername());
        }

        handler.accountWebSocketHandler.handleGetUserList(handler, session);
    }

    private boolean isRoomOwner(ChatWebSocketHandler handler, RoomMember member) {
        return member != null
                && RoomMember.ROLE_OWNER.equals(handler.normalizeRoomRole(member.getRole()));
    }

    private boolean isRoomDeputy(ChatWebSocketHandler handler, RoomMember member) {
        return member != null
                && RoomMember.ROLE_DEPUTY.equals(handler.normalizeRoomRole(member.getRole()));
    }

    private boolean isRoomManager(ChatWebSocketHandler handler, RoomMember member) {
        return isRoomOwner(handler, member) || isRoomDeputy(handler, member);
    }
}
