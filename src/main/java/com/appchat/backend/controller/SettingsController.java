package com.appchat.backend.controller;

import com.appchat.backend.dto.ApiResponse;
import com.appchat.backend.dto.GroupThemeRequest;
import com.appchat.backend.dto.ThemeRequest;
import com.appchat.backend.entity.GroupTheme;
import com.appchat.backend.service.SettingsService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/chat/settings")
@RequiredArgsConstructor
public class SettingsController {

    private final SettingsService service;

    @GetMapping("/theme")
    public ApiResponse<String> getTheme(@RequestParam String user1, @RequestParam String user2) {
        String themeId = service.getChatTheme(user1, user2);
        return new ApiResponse<>("GET_THEME_SUCCESS", themeId);
    }

    @PostMapping("/theme")
    public ApiResponse<String> setTheme(@RequestBody ThemeRequest req) {
        String themeId = service.updateChatTheme(req.getUserOne(), req.getUserTwo(), req.getThemeId());
        return new ApiResponse<>("SET_THEME_SUCCESS", themeId);
    }

    @GetMapping("/group")
    public ApiResponse<GroupTheme> getGroupTheme(@RequestParam String groupName) {
        GroupTheme theme = service.getGroupTheme(groupName);
        return new ApiResponse<>("GET_GROUP_THEME_SUCCESS", theme);
    }

    @PostMapping("/group")
    public ApiResponse<GroupTheme> setGroupTheme(@RequestBody GroupThemeRequest req) {
        GroupTheme theme = service.updateGroupTheme(req.getGroupName(), req.getUsername(), req.getThemeId());
        return new ApiResponse<>("SET_GROUP_THEME_SUCCESS", theme);
    }
}
