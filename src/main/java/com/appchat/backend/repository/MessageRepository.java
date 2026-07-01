package com.appchat.backend.repository;

import com.appchat.backend.entity.Message;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Repository
public interface MessageRepository extends JpaRepository<Message, Long> {

    @Query("""
        SELECT m
        FROM Message m
        WHERE m.type = 'people'
          AND (
                (m.sender = :userA AND m.receiver = :userB)
                OR
                (m.sender = :userB AND m.receiver = :userA)
              )
        ORDER BY m.createdAt DESC
        """)
    List<Message> findPeopleMessages(
            String userA,
            String userB,
            Pageable pageable
    );

    @Query("""
        SELECT m
        FROM Message m
        WHERE m.type = 'room'
          AND m.receiver = :roomName
        ORDER BY m.createdAt DESC
        """)
    List<Message> findRoomMessages(
            String roomName,
            Pageable pageable
    );

    @Query("""
        SELECT m
        FROM Message m
        WHERE m.type = 'people'
          AND (
                (m.sender = :userA AND m.receiver = :userB)
                OR
                (m.sender = :userB AND m.receiver = :userA)
              )
          AND m.createdAt >= :fromTime
          AND m.createdAt < :toTime
        ORDER BY m.createdAt DESC
        """)
    List<Message> findPeopleMessagesBetween(
            String userA,
            String userB,
            java.time.LocalDateTime fromTime,
            java.time.LocalDateTime toTime,
            Pageable pageable
    );

    @Query("""
        SELECT m
        FROM Message m
        WHERE m.type = 'room'
          AND m.receiver = :roomName
          AND m.createdAt >= :fromTime
          AND m.createdAt < :toTime
        ORDER BY m.createdAt DESC
        """)
    List<Message> findRoomMessagesBetween(
            String roomName,
            java.time.LocalDateTime fromTime,
            java.time.LocalDateTime toTime,
            Pageable pageable
    );

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query(value = "UPDATE messages SET receiver = :newName WHERE type = 'room' AND receiver = :oldName", nativeQuery = true)
    int renameRoomMessages(@Param("oldName") String oldName, @Param("newName") String newName);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query(value = "DELETE FROM messages WHERE type = 'room' AND receiver = :roomName", nativeQuery = true)
    int deleteRoomMessages(@Param("roomName") String roomName);
    Optional<Message> findTopByTypeAndSenderAndReceiverOrTypeAndSenderAndReceiverOrderByCreatedAtDesc(
            String type1,
            String sender1,
            String receiver1,
            String type2,
            String sender2,
            String receiver2
    );

    Optional<Message> findTopByTypeAndReceiverOrderByCreatedAtDesc(
            String type,
            String receiver
    );
    @Query("""
    SELECT m FROM Message m
    WHERE m.type = 'people'
      AND (
        (m.sender = :user1 AND m.receiver = :user2)
        OR
        (m.sender = :user2 AND m.receiver = :user1)
      )
    ORDER BY m.createdAt DESC
""")
    List<Message> findLastPeopleMessage(
            @Param("user1") String user1,
            @Param("user2") String user2,
            Pageable pageable
    );

}
