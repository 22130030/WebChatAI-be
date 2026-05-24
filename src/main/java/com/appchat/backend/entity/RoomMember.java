package com.appchat.backend.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "room_members")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@IdClass(RoomMemberId.class)
public class RoomMember {
    @Id
    @Column(name = "room_name")
    private String roomName;

    @Id
    private String username;
}
