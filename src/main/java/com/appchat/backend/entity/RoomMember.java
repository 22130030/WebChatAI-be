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
    public static final String ROLE_OWNER = "OWNER";
    public static final String ROLE_DEPUTY = "DEPUTY";
    public static final String ROLE_MEMBER = "MEMBER";

    @Id
    @Column(name = "room_name")
    private String roomName;

    @Id
    private String username;

    @Builder.Default
    @Column(nullable = false, length = 20)
    private String role = ROLE_MEMBER;
}