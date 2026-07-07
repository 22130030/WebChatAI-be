package com.appchat.backend.service;

import com.appchat.backend.dto.admin.*;
import com.appchat.backend.entity.Message;
import com.appchat.backend.entity.Room;
import com.appchat.backend.entity.User;
import com.appchat.backend.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.NoSuchElementException;

@Service
@RequiredArgsConstructor
public class AdminService {

    private final UserRepository userRepository;
    private final RoomRepository roomRepository;
    private final RoomMemberRepository roomMemberRepository;
    private final MessageRepository messageRepository;
    private final PendingConversationRepository pendingConversationRepository;
    private final ChatSummaryRepository chatSummaryRepository;
    private final OnlineStatusService onlineStatusService;
    private final FriendshipRepository friendshipRepository;

    // =========================================================
    // DASHBOARD STATS
    // =========================================================

    public AdminStatsDto getStats() {
        LocalDateTime startOfDay = LocalDateTime.now().toLocalDate().atStartOfDay();

        long totalUsers = userRepository.count();
        long onlineUsers = userRepository.findAll()
                .stream()
                .filter(user -> onlineStatusService.isOnline(user.getUsername()))
                .count();
        long totalRooms = roomRepository.count();
        long totalMessages = messageRepository.count();
        long newUsersToday = userRepository.countByCreatedAtAfter(startOfDay);
        long newMessagesToday = messageRepository.countByCreatedAtAfter(startOfDay);
        long pendingConversations = pendingConversationRepository.count();

        return AdminStatsDto.builder()
                .totalUsers(totalUsers)
                .onlineUsers(onlineUsers)
                .totalRooms(totalRooms)
                .totalMessages(totalMessages)
                .newUsersToday(newUsersToday)
                .newMessagesToday(newMessagesToday)
                .pendingConversations(pendingConversations)
                .build();
    }

    // =========================================================
    // USER MANAGEMENT
    // =========================================================

    public PagedResponse<AdminUserDto> getAllUsers(int page, int size, String search) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<User> userPage;

        if (search != null && !search.isBlank()) {
            userPage = userRepository.findBySearchQuery(search.trim(), pageable);
        } else {
            userPage = userRepository.findAll(pageable);
        }

        Page<AdminUserDto> dtoPage = userPage.map(this::toAdminUserDto);
        return PagedResponse.of(dtoPage);
    }

    public AdminUserDto getUserById(Long id) {
        return userRepository.findById(id)
                .map(this::toAdminUserDto)
                .orElseThrow(() -> new NoSuchElementException("Không tìm thấy user với id: " + id));
    }

    public AdminUserDto updateUser(Long id, AdminUserUpdateRequest request) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Không tìm thấy user với id: " + id));

        if (request.getDisplayName() != null) {
            user.setDisplayName(request.getDisplayName());
        }
        if (request.getBio() != null) {
            user.setBio(request.getBio());
        }
        if (request.getStatus() != null) {
            user.setStatus(request.getStatus());
        }
        if (request.getRole() != null) {
            String role = request.getRole().toUpperCase();
            if (!role.equals("USER") && !role.equals("ADMIN")) {
                throw new IllegalArgumentException("Role phải là USER hoặc ADMIN");
            }
            user.setRole(role);
        }

        return toAdminUserDto(userRepository.save(user));
    }

    @Transactional
    public void deleteUser(Long id) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Không tìm thấy user với id: " + id));

        String username = user.getUsername();

        // Xóa rooms mà user là owner
        roomRepository.findAll().stream()
                .filter(r -> username.equals(r.getOwnerUsername()))
                .forEach(r -> deleteRoomInternal(r.getName()));

        // Xóa room memberships
        roomMemberRepository.findByUsername(username)
                .forEach(roomMemberRepository::delete);

        // Xóa pending conversations
        pendingConversationRepository.findAll().stream()
                .filter(pc -> username.equals(pc.getFromUsername()) || username.equals(pc.getToUsername()))
                .forEach(pendingConversationRepository::delete);

        // Xóa friendships
        friendshipRepository.findByUsername(username)
                .forEach(friendshipRepository::delete);

        // Xóa messages của user (MongoDB)
        messageRepository.findAll().stream()
                .filter(m -> username.equals(m.getSender()) || username.equals(m.getReceiver()))
                .forEach(messageRepository::delete);

        userRepository.delete(user);
    }

    // =========================================================
    // ROOM MANAGEMENT
    // =========================================================

    public PagedResponse<AdminRoomDto> getAllRooms(int page, int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<Room> roomPage = roomRepository.findAll(pageable);

        Page<AdminRoomDto> dtoPage = roomPage.map(room -> {
            int memberCount = roomMemberRepository.findByRoomName(room.getName()).size();
            return AdminRoomDto.builder()
                    .id(room.getId())
                    .name(room.getName())
                    .type(room.getType())
                    .ownerUsername(room.getOwnerUsername())
                    .memberCount(memberCount)
                    .createdAt(room.getCreatedAt())
                    .build();
        });

        return PagedResponse.of(dtoPage);
    }

    public AdminRoomDetailDto getRoomDetail(String roomName) {
        Room room = roomRepository.findByName(roomName)
                .orElseThrow(() -> new NoSuchElementException("Không tìm thấy room: " + roomName));

        List<AdminRoomDetailDto.MemberInfo> members = roomMemberRepository.findByRoomName(roomName)
                .stream()
                .map(rm -> AdminRoomDetailDto.MemberInfo.builder()
                        .username(rm.getUsername())
                        .role(rm.getRole())
                        .build())
                .toList();

        return AdminRoomDetailDto.builder()
                .id(room.getId())
                .name(room.getName())
                .type(room.getType())
                .ownerUsername(room.getOwnerUsername())
                .createdAt(room.getCreatedAt())
                .members(members)
                .build();
    }

    @Transactional
    public void deleteRoom(String roomName) {
        if (roomRepository.findByName(roomName).isEmpty()) {
            throw new NoSuchElementException("Không tìm thấy room: " + roomName);
        }
        deleteRoomInternal(roomName);
    }

    private void deleteRoomInternal(String roomName) {
        // Xóa messages trong room (MongoDB)
        messageRepository.deleteByTypeAndReceiver("room", roomName);

        // Xóa room members
        roomMemberRepository.findByRoomName(roomName)
                .forEach(roomMemberRepository::delete);

        // Xóa chat summaries cho room
        chatSummaryRepository.findAll().stream()
                .filter(cs -> "room".equals(cs.getConversationType()) && roomName.equals(cs.getTarget()))
                .forEach(chatSummaryRepository::delete);

        // Xóa room
        roomRepository.findByName(roomName).ifPresent(roomRepository::delete);
    }

    // =========================================================
    // MESSAGE MANAGEMENT
    // =========================================================

    public PagedResponse<AdminMessageDto> searchMessages(String type, String sender, String receiver, int page, int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));

        // Normalize empty strings to null for optional filters
        String typeFilter = (type != null && !type.isBlank()) ? type : null;
        String senderFilter = (sender != null && !sender.isBlank()) ? sender : null;
        String receiverFilter = (receiver != null && !receiver.isBlank()) ? receiver : null;

        List<Message> messages = messageRepository.adminSearch(typeFilter, senderFilter, receiverFilter, pageable);
        long total = messageRepository.adminSearchCount(typeFilter, senderFilter, receiverFilter);

        List<AdminMessageDto> dtos = messages.stream().map(this::toAdminMessageDto).toList();

        int totalPages = (int) Math.ceil((double) total / size);
        return new PagedResponse<>(dtos, page, size, total, totalPages, page == 0, page >= totalPages - 1);
    }

    public void deleteMessage(String messageId) {
        if (!messageRepository.existsById(messageId)) {
            throw new NoSuchElementException("Không tìm thấy tin nhắn: " + messageId);
        }
        messageRepository.deleteById(messageId);
    }

    public AdminMessageDto recallMessage(String messageId) {
        Message message = messageRepository.findById(messageId)
                .orElseThrow(() -> new NoSuchElementException("Không tìm thấy tin nhắn: " + messageId));
        message.setRecalled(true);
        message.setContent("[Tin nhắn đã bị thu hồi]");
        return toAdminMessageDto(messageRepository.save(message));
    }

    // =========================================================
    // MAPPERS
    // =========================================================

    private AdminUserDto toAdminUserDto(User user) {
        return AdminUserDto.builder()
                .id(user.getId())
                .username(user.getUsername())
                .displayName(user.getDisplayName())
                .avatar(user.getAvatar())
                .bio(user.getBio())
                .status(onlineStatusService.statusOf(user.getUsername()))
                .role(user.getRole())
                .createdAt(user.getCreatedAt())
                .build();
    }

    private AdminMessageDto toAdminMessageDto(Message message) {
        return AdminMessageDto.builder()
                .id(message.getId())
                .type(message.getType())
                .sender(message.getSender())
                .receiver(message.getReceiver())
                .content(message.getContent())
                .recalled(message.getRecalled())
                .edited(message.getEdited())
                .status(message.getStatus())
                .createdAt(message.getCreatedAt())
                .build();
    }
}
