package com.appchat.backend.repository;

import com.appchat.backend.entity.RoomMember;
import com.appchat.backend.entity.RoomMemberId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Repository
public interface RoomMemberRepository extends JpaRepository<RoomMember, RoomMemberId> {
    List<RoomMember> findByRoomName(String roomName);
    List<RoomMember> findByUsername(String username);
    boolean existsByRoomNameAndUsername(String roomName, String username);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query(value = "UPDATE room_members SET room_name = :newName WHERE room_name = :oldName", nativeQuery = true)
    int renameRoomName(@Param("oldName") String oldName, @Param("newName") String newName);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query(value = "DELETE FROM room_members WHERE room_name = :roomName AND username = :username", nativeQuery = true)
    int deleteMemberFromRoom(@Param("roomName") String roomName, @Param("username") String username);

}
