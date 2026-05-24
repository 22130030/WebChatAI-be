package com.appchat.backend.repository;

import com.appchat.backend.entity.RoomMember;
import com.appchat.backend.entity.RoomMemberId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface RoomMemberRepository extends JpaRepository<RoomMember, RoomMemberId> {
    List<RoomMember> findByRoomName(String roomName);
    List<RoomMember> findByUsername(String username);
}
