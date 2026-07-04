package com.appchat.backend.service;

import com.appchat.backend.entity.PendingConversation;
import com.appchat.backend.repository.PendingConversationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class PendingConversationService {

    private final PendingConversationRepository repository;

    public PendingConversation createRequest(String from, String to) {
        if (from == null || to == null || from.equals(to)) {
            throw new IllegalArgumentException("Người gửi hoặc người nhận không hợp lệ");
        }

        Optional<PendingConversation> existing = repository.findBetweenUsers(from, to);

        if (existing.isPresent()) {
            PendingConversation pc = existing.get();

            if ("ACCEPTED".equals(pc.getStatus())) {
                return pc;
            }

            pc.setFromUsername(from);
            pc.setToUsername(to);
            pc.setStatus("PENDING");

            return repository.save(pc);
        }

        PendingConversation pc = PendingConversation.builder()
                .fromUsername(from)
                .toUsername(to)
                .status("PENDING")
                .build();

        return repository.save(pc);
    }

    public List<PendingConversation> getIncomingRequests(String username) {
        return repository.findByToUsernameAndStatus(username, "PENDING");
    }

    public List<PendingConversation> getOutgoingRequests(String username) {
        return repository.findByFromUsernameAndStatus(username, "PENDING");
    }

    public List<PendingConversation> getAcceptedConversations(String username) {
        return repository.findAcceptedConversations(username);
    }

    public void acceptRequest(String from, String to) {
        repository.findByFromUsernameAndToUsername(from, to)
                .ifPresent(pc -> {
                    pc.setStatus("ACCEPTED");
                    repository.save(pc);
                });
    }

    public void deleteRequest(String from, String to) {
        repository.findBetweenUsers(from, to)
                .ifPresent(repository::delete);
    }

    public void removeContact(String currentUser, String otherUser) {
        PendingConversation pc = repository.findBetweenUsers(currentUser, otherUser)
                .orElseThrow(() -> new RuntimeException("Không tìm thấy liên hệ"));

        if (!"ACCEPTED".equals(pc.getStatus())) {
            throw new RuntimeException("Hai người chưa là liên hệ");
        }

        pc.setStatus("REMOVED");
        repository.save(pc);
    }

    public boolean isAccepted(String user1, String user2) {
        return repository.existsAcceptedBetween(user1, user2);
    }
}