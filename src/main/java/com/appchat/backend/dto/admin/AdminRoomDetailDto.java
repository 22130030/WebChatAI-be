package com.appchat.backend.dto.admin;

import com.appchat.backend.entity.RoomMember;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AdminRoomDetailDto {
    private Long id;
    private String name;
    private String type;
    private String ownerUsername;
    private LocalDateTime createdAt;
    private List<MemberInfo> members;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class MemberInfo {
        private String username;
        private String role;
    }
}
