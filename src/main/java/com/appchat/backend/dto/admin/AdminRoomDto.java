package com.appchat.backend.dto.admin;

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
public class AdminRoomDto {
    private Long id;
    private String name;
    private String type;
    private String ownerUsername;
    private int memberCount;
    private LocalDateTime createdAt;
}
