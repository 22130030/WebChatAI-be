package com.appchat.backend.repository;

import com.appchat.backend.entity.PendingConversation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PendingConversationRepository extends JpaRepository<PendingConversation, Long> {
    List<PendingConversation> findByToUsernameAndStatus(String toUsername, String status);
    Optional<PendingConversation> findByFromUsernameAndToUsername(String fromUsername, String toUsername);
}
