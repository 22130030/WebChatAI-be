package com.appchat.backend.dto;

import lombok.Data;

@Data
public class GroupThemeRequest {
    private String groupName;
    private String username;
    private String themeId;
}
