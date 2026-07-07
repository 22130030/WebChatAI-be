package com.appchat.backend.dto.admin;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AdminUserUpdateRequest {
    private String displayName;
    private String bio;
    private String status;
    private String role;
}
