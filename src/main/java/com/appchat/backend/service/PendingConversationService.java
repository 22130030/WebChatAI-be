package com.appchat.backend.service;

import com.appchat.backend.entity.PendingConversation;
import com.appchat.backend.entity.User;
import com.appchat.backend.repository.PendingConversationRepository;
import com.appchat.backend.repository.UserRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class PendingConversationService {

    private final PendingConversationRepository repository;
    private final FriendshipService friendshipService;
    private final UserRepository userRepository;

    @PostConstruct
    @Transactional
    public void migrateAcceptedPendingConversations() {
        repository.findByStatus("ACCEPTED").forEach(pc -> {
            friendshipService.createFriendship(pc.getFromUsername(), pc.getToUsername());
            repository.delete(pc);
        });

        repository.findByStatus("REMOVED").forEach(repository::delete);
    }

    private PendingConversation populateUserDetails(PendingConversation pc) {
        if (pc == null) {
            return null;
        }
        userRepository.findByUsername(pc.getFromUsername()).ifPresent(u -> {
            pc.setFromAvatar(u.getAvatar());
            pc.setFromDisplayName(getEffectiveDisplayName(u));
        });
        userRepository.findByUsername(pc.getToUsername()).ifPresent(u -> {
            pc.setToAvatar(u.getAvatar());
            pc.setToDisplayName(getEffectiveDisplayName(u));
        });
        return pc;
    }

    private String getEffectiveDisplayName(User user) {
        String displayName = user.getDisplayName();
        if (displayName == null || displayName.isBlank()) {
            return user.getUsername();
        }
        return displayName;
    }

    public PendingConversation createRequest(String from, String to) {
        if (from == null || to == null || from.equals(to)) {
            throw new IllegalArgumentException("Người gửi hoặc người nhận không hợp lệ");
        }

        Optional<PendingConversation> existing = repository.findBetweenUsers(from, to);

        if (friendshipService.areFriends(from, to)) {
            return populateUserDetails(PendingConversation.builder()
                    .fromUsername(from)
                    .toUsername(to)
                    .status("ACCEPTED")
                    .build());
        }

        if (existing.isPresent()) {
            return populateUserDetails(existing.get());
        }


        PendingConversation pc = PendingConversation.builder()
                .fromUsername(from)
                .toUsername(to)
                .status("PENDING")
                .build();

        return populateUserDetails(repository.save(pc));
    }

    public List<PendingConversation> getIncomingRequests(String username) {
        return repository.findByToUsernameAndStatus(username, "PENDING")
                .stream()
                .map(this::populateUserDetails)
                .toList();
    }

    public List<PendingConversation> getOutgoingRequests(String username) {
        return repository.findByFromUsernameAndStatus(username, "PENDING")
                .stream()
                .map(this::populateUserDetails)
                .toList();
    }

    public List<PendingConversation> getAcceptedConversations(String username) {
        return friendshipService.findFriendships(username)
                .stream()
                .map(friendship -> {
                    PendingConversation pc = PendingConversation.builder()
                            .fromUsername(username)
                            .toUsername(friendshipService.otherUser(friendship, username))
                            .status("ACCEPTED")
                            .createdAt(friendship.getCreatedAt())
                            .updatedAt(friendship.getCreatedAt())
                            .build();
                    return populateUserDetails(pc);
                })
                .toList();
    }

    public void acceptRequest(String from, String to) {
        repository.findByFromUsernameAndToUsername(from, to)
                .ifPresent(pc -> {
                    friendshipService.createFriendship(from, to);
                    repository.delete(pc);
                });
    }

    public void deleteRequest(String from, String to) {
        repository.findBetweenUsers(from, to)
                .ifPresent(repository::delete);
    }

    public void removeContact(String currentUser, String otherUser) {
        if (!friendshipService.areFriends(currentUser, otherUser)) {
            throw new RuntimeException("Hai người chưa là liên hệ");
        }

        friendshipService.removeFriendship(currentUser, otherUser);
    }

    public boolean isAccepted(String user1, String user2) {
        return friendshipService.areFriends(user1, user2);
    }
}
