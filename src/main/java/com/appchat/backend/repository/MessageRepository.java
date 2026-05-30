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

@Repository
public interface MessageRepository extends JpaRepository<Message, Long> {
    @Query("SELECT m FROM Message m WHERE m.type = 'people' AND ((m.sender = :u1 AND m.receiver = :u2) OR (m.sender = :u2 AND m.receiver = :u1)) ORDER BY m.createdAt DESC")
    List<Message> findPeopleMessages(@Param("u1") String u1, @Param("u2") String u2, Pageable pageable);

    @Query("SELECT m FROM Message m WHERE m.type = 'room' AND m.receiver = :roomName ORDER BY m.createdAt DESC")
    List<Message> findRoomMessages(@Param("roomName") String roomName, Pageable pageable);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query(value = "UPDATE messages SET receiver = :newName WHERE type = 'room' AND receiver = :oldName", nativeQuery = true)
    int renameRoomMessages(@Param("oldName") String oldName, @Param("newName") String newName);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query(value = "DELETE FROM messages WHERE type = 'room' AND receiver = :roomName", nativeQuery = true)
    int deleteRoomMessages(@Param("roomName") String roomName);

}
