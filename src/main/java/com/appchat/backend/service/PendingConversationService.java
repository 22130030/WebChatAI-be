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
        Optional<PendingConversation> existing = repository.findByFromUsernameAndToUsername(from, to);
        if (existing.isPresent()) {
            PendingConversation pc = existing.get();
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

    public List<PendingConversation> getIncomingRequests(String toUsername) {
        return repository.findByToUsernameAndStatus(toUsername, "PENDING");
    }

    public void acceptRequest(String from, String to) {
        repository.findByFromUsernameAndToUsername(from, to).ifPresent(pc -> {
            pc.setStatus("ACCEPTED");
            repository.save(pc);
        });
    }

    public void deleteRequest(String from, String to) {
        repository.findByFromUsernameAndToUsername(from, to).ifPresent(repository::delete);
    }
}
