package com.appchat.backend.controller;

import com.appchat.backend.dto.ApiResponse;
import com.appchat.backend.dto.GroupThemeRequest;
import com.appchat.backend.dto.ThemeRequest;
import com.appchat.backend.entity.GroupTheme;
import com.appchat.backend.repository.RoomMemberRepository;
import com.appchat.backend.service.SettingsService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/chat/settings")
@RequiredArgsConstructor
public class SettingsController {

    private final SettingsService service;
    private final RoomMemberRepository roomMemberRepository;

    @GetMapping("/theme")
    public ApiResponse<String> getTheme(
            @RequestParam String user1,
            @RequestParam String user2,
            Authentication authentication
    ) {
        validateParticipant(authentication, user1, user2);
        String themeId = service.getChatTheme(user1, user2);
        return new ApiResponse<>("GET_THEME_SUCCESS", themeId);
    }

    @PostMapping("/theme")
    public ApiResponse<String> setTheme(
            @RequestBody ThemeRequest req,
            Authentication authentication
    ) {
        validateParticipant(authentication, req.getUserOne(), req.getUserTwo());
        String themeId = service.updateChatTheme(req.getUserOne(), req.getUserTwo(), req.getThemeId());
        return new ApiResponse<>("SET_THEME_SUCCESS", themeId);
    }

    @GetMapping("/group")
    public ApiResponse<GroupTheme> getGroupTheme(
            @RequestParam String groupName,
            Authentication authentication
    ) {
        validateGroupMember(authentication, groupName);
        GroupTheme theme = service.getGroupTheme(groupName);
        return new ApiResponse<>("GET_GROUP_THEME_SUCCESS", theme);
    }

    @PostMapping("/group")
    public ApiResponse<GroupTheme> setGroupTheme(
            @RequestBody GroupThemeRequest req,
            Authentication authentication
    ) {
        validateGroupMember(authentication, req.getGroupName());
        // For security, enforce that the theme change is attributed to the authenticated user
        String activeUser = authentication.getName();
        GroupTheme theme = service.updateGroupTheme(req.getGroupName(), activeUser, req.getThemeId());
        return new ApiResponse<>("SET_GROUP_THEME_SUCCESS", theme);
    }

    private void validateParticipant(Authentication authentication, String u1, String u2) {
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new AccessDeniedException("Unauthorized");
        }
        String current = authentication.getName();
        if (!current.equals(u1) && !current.equals(u2)) {
            throw new AccessDeniedException("Unauthorized");
        }
    }

    private void validateGroupMember(Authentication authentication, String groupName) {
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new AccessDeniedException("Unauthorized");
        }
        String current = authentication.getName();
        if (!roomMemberRepository.existsByRoomNameAndUsername(groupName, current)) {
            throw new AccessDeniedException("Unauthorized");
        }
    }
}
