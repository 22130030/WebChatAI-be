package com.appchat.backend.repository;

import com.appchat.backend.entity.PendingConversation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface PendingConversationRepository extends JpaRepository<PendingConversation, Long> {

    List<PendingConversation> findByToUsernameAndStatus(String toUsername, String status);

    List<PendingConversation> findByFromUsernameAndStatus(String fromUsername, String status);

    List<PendingConversation> findByStatus(String status);

    Optional<PendingConversation> findByFromUsernameAndToUsername(
            String fromUsername,
            String toUsername
    );

    @Query("""
        SELECT pc FROM PendingConversation pc
        WHERE (pc.fromUsername = :u1 AND pc.toUsername = :u2)
           OR (pc.fromUsername = :u2 AND pc.toUsername = :u1)
    """)
    Optional<PendingConversation> findBetweenUsers(
            @Param("u1") String u1,
            @Param("u2") String u2
    );

}
