package com.appchat.backend.dto.admin;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AdminUserDto {
    private Long id;
    private String username;
    private String displayName;
    private String avatar;
    private String bio;
    private String status;
    private String role;
    private LocalDateTime createdAt;
}
