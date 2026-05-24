package com.appchat.backend.service;

import com.appchat.backend.entity.ChatTheme;
import com.appchat.backend.entity.GroupTheme;
import com.appchat.backend.repository.ChatThemeRepository;
import com.appchat.backend.repository.GroupThemeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class SettingsService {

    private final ChatThemeRepository chatThemeRepository;
    private final GroupThemeRepository groupThemeRepository;

    public String getChatTheme(String u1, String u2) {
        return chatThemeRepository.findThemeBetweenUsers(u1, u2)
                .map(ChatTheme::getThemeId)
                .orElse("DEFAULT");
    }

    public String updateChatTheme(String u1, String u2, String themeId) {
        ChatTheme theme = chatThemeRepository.findThemeBetweenUsers(u1, u2)
                .orElseGet(() -> ChatTheme.builder().user1(u1).user2(u2).build());
        theme.setThemeId(themeId);
        chatThemeRepository.save(theme);
        return themeId;
    }

    public GroupTheme getGroupTheme(String groupName) {
        return groupThemeRepository.findByGroupName(groupName).orElse(null);
    }

    public GroupTheme updateGroupTheme(String groupName, String username, String themeId) {
        GroupTheme theme = groupThemeRepository.findByGroupName(groupName)
                .orElseGet(() -> GroupTheme.builder().groupName(groupName).build());
        theme.setThemeId(themeId);
        theme.setLastChangedBy(username);
        return groupThemeRepository.save(theme);
    }
}
